package app.couchmate

import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlin.math.abs

/** The id of the CC subtitle track (the plugin's captions). */
const val CAPTIONS_ID = "cc"

/**
 * Makes ExoPlayer follow the room's state. Called every 250 ms. Small differences are corrected with the
 * playback speed (±8 %), large ones with a seek (with an adaptive lead to make up for buffering time).
 */
class PlaybackSync(private val player: ExoPlayer, private val sync: SyncClient) {
    var active = true
    var status by mutableStateOf("")
        private set
    var drift by mutableStateOf(0.0)
        private set

    private var loadedUrl: String? = null

    /** The address that is played (plugins' own player choices are added to it). */
    var urlFor: (Media) -> String = { it.url }

    /** The captions to show with it (WebVTT), when CC is on and the plugin has them – else null. */
    var captionsFor: (Media, String) -> String? = { _, _ -> null }

    /** CC was switched on or off: load the one that plays again (with or without captions) where the room is. */
    fun reloadCaptions() {
        if (player.mediaItemCount > 1) player.removeMediaItems(1, player.mediaItemCount)
        preparedUrl = null
        loadedUrl = null
    }
    private var seekedVersion = -1
    private var readyVersion = -1
    private var cooldownUntil = 0L
    private var lastSeekAt = 0L
    private var correcting = false
    private var livePaused = false
    private var seekLead = 1.0
    private var leadCheck = false
    private var rateWorks: Boolean? = null // null = not known yet
    private var correctStartAt = 0L
    private var correctStartDrift = 0.0
    private var lastFineSeekAt = 0L
    /** Standing still until then (ms) – how a player that cannot change speed lets the others catch up. */
    private var holdUntil = 0L
    private var lastHoldAt = 0L
    /** How far past the target the last seek aimed on purpose (s) – left out when the lead is learned. */
    private var overshoot = 0.0
    private var rate = 1f
    private var errorAt = 0L
    private var lastError = ""

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                lastError = error.errorCodeName
                errorAt = SystemClock.elapsedRealtime()
            }
        })
    }

    fun positionSec() = player.currentPosition / 1000.0
    fun durationSec() = player.duration.let { if (it == C.TIME_UNSET || it <= 0) 0.0 else it / 1000.0 }

    fun tick(st: RoomState?) {
        val now = SystemClock.elapsedRealtime()
        if (!active) {
            player.playWhenReady = false
            return
        }
        val media = st?.media
        if (st == null || media == null) {
            if (loadedUrl != null) {
                player.stop()
                player.clearMediaItems()
                loadedUrl = null
                preparedUrl = null
            }
            status = tr("Nothing playing")
            return
        }

        val url = urlFor(media)
        if (url != loadedUrl) {
            loadedUrl = url
            seekedVersion = -1
            readyVersion = -1
            correcting = false
            livePaused = false
            leadCheck = false
            rateWorks = null
            holdUntil = 0L
            setRate(1f, force = true)
            hls = media.type == "hls"
            audioUrl = audioFor(media, url)
            captionsUrl = captionsFor(media, url)
            // Joining in the middle of a movie starts right where the others are.
            val start = if (st.paused || sync.serverNow() < st.refTime) st.position
            else st.position + (sync.serverNow() - st.refTime) / 1000.0 + seekLead
            val handover = preparedUrl == url && player.mediaItemCount > 1
            preparedUrl = null
            if (handover) {
                // Already buffered behind the one that just ended: switch to it (unless the player already moved
                // on by itself) and drop the old one – a nearly seamless switch.
                if (player.currentMediaItemIndex == 0) player.seekTo(1, ms(start))
                player.removeMediaItem(0)
                seekedVersion = st.version
                lastSeekAt = now
            } else {
                load(url)
                if (!media.live) {
                    player.seekTo(ms(start))
                    seekedVersion = st.version
                    lastSeekAt = now
                }
            }
        }

        if (!media.live) prepareNext(st)

        if (player.playerError != null) {
            status = tr("Playback error ({error}) – retrying…", "error" to lastError)
            if (now - errorAt > 3000) {
                errorAt = now
                if (media.live) load(url) else player.prepare()
            }
            return
        }

        if (media.live) tickLive(st) else tickVod(st, now)
    }

    private fun tickLive(st: RoomState) {
        setRate(1f)
        if (st.paused) {
            player.playWhenReady = false
            livePaused = true
            status = tr("Paused")
            return
        }
        if (livePaused) {
            // Jump back to live when someone presses play again.
            livePaused = false
            load(urlFor(st.media!!))
        }
        player.playWhenReady = true
        status = if (player.playbackState == Player.STATE_BUFFERING) tr("Buffering…") else ""
    }

    private fun tickVod(st: RoomState, now: Long) {
        if (player.currentMediaItemIndex > 0) {
            if (st.upNext?.early == true && !st.paused) {
                // The one that was ending is over and the buffered next one already plays – the server switches
                // everyone to it in a moment. No syncing against the old one meanwhile.
                player.playWhenReady = true
                status = ""
                return
            }
            // Cancelled or paused just as it ended: back to the old one, where the room is.
            player.seekTo(0, ms(st.position))
            player.removeMediaItems(1, player.mediaItemCount)
            preparedUrl = null
            lastSeekAt = now
        }
        val pos = positionSec()
        // The length, for "next" (once per item – the server may not know it when nobody had to get ready).
        val dur0 = durationSec()
        if (dur0 > 0 && reportedDurationFor != loadedUrl && player.playbackState == Player.STATE_READY) {
            reportedDurationFor = loadedUrl
            sync.duration(dur0)
        }

        if (st.paused) {
            player.playWhenReady = false
            setRate(1f)
            correcting = false
            if (seekedVersion != st.version) {
                if (abs(pos - st.position) > 0.25) {
                    player.seekTo(ms(st.position))
                    lastSeekAt = now
                }
                seekedVersion = st.version
                return
            }
            if (st.waiting) {
                status = tr("Waiting for everyone to be ready…")
                if (readyVersion != st.version && player.playbackState == Player.STATE_READY && now - lastSeekAt > 300) {
                    sync.ready(st.version, durationSec())
                    readyVersion = st.version
                }
            } else {
                status = tr("Paused")
            }
            return
        }

        val serverNow = sync.serverNow()
        if (serverNow < st.refTime) {
            // Everyone starts exactly at refTime.
            player.playWhenReady = false
            if (abs(pos - st.position) > 0.5 && now - lastSeekAt > 1000) {
                player.seekTo(ms(st.position))
                lastSeekAt = now
            }
            status = tr("Starting…")
            return
        }

        val target = st.position + (serverNow - st.refTime) / 1000.0
        if (now < holdUntil) {
            // Ahead of the others: standing still a moment (see below).
            player.playWhenReady = false
            status = ""
            return
        }
        player.playWhenReady = true
        if (player.playbackState == Player.STATE_BUFFERING) {
            status = tr("Buffering…")
            return
        }
        if (now < cooldownUntil) return

        val d = pos - target
        drift = d
        if (leadCheck) {
            // Learn how long a seek takes so the next one lands right away.
            seekLead = (seekLead - (d - overshoot) * 0.7).coerceIn(0.3, 6.0)
            leadCheck = false
        }
        val dur = durationSec()
        if (dur > 0 && target > dur) {
            status = tr("Ended")
            return
        }
        // Does changing the speed work? Not when the sound goes to the TV or amplifier undecoded (DTS, Dolby) –
        // known straight away from the audio format, otherwise found out by trying for a few seconds.
        if (rateWorks == null && passthroughAudio()) rateWorks = false
        if (correcting && rateWorks == null && now - correctStartAt > 5000) {
            rateWorks = abs(d) < abs(correctStartDrift) - 0.1
        }
        // Without speed changes: ahead → stand still for exactly that long (no new buffering); behind → one jump
        // a little too far, and standing still takes off the rest. A jump empties the buffer, so as few as possible.
        val fixed = rateWorks == false
        val behindJump = fixed && d < -0.4 && now - lastFineSeekAt > 4000
        when {
            abs(d) > 2.0 || behindJump -> {
                setRate(1f)
                correcting = false
                if (behindJump) lastFineSeekAt = now
                overshoot = if (fixed) 0.4 else 0.0
                player.seekTo(ms(target + seekLead + overshoot))
                lastSeekAt = now
                cooldownUntil = now + 2500
                leadCheck = true
                status = tr("Syncing…")
            }
            fixed && d > 0.12 && now - lastHoldAt > 1500 -> {
                holdUntil = now + (d * 1000).toLong()
                lastHoldAt = now
                player.playWhenReady = false
                status = ""
            }
            rateWorks != false && (abs(d) > 0.3 || (correcting && abs(d) > 0.06)) -> {
                if (!correcting) {
                    correctStartAt = now
                    correctStartDrift = d
                }
                correcting = true
                setRate((1.0 - d / 3.0).coerceIn(0.92, 1.08).toFloat())
                status = ""
            }
            else -> {
                correcting = false
                setRate(1f)
                status = ""
            }
        }
    }

    /** Whether the media that plays is HLS (told by the server; the address has no file extension). */
    private var hls = false

    /** The separate audio file that plays together with the video, if there is one. */
    private var audioUrl: String? = null
    private var captionsUrl: String? = null
    private val http by lazy { DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true) }

    private fun audioFor(media: Media, url: String) =
        if (media.audio && media.type != "hls") url + (if ('?' in url) "&" else "?") + "part=audio" else null

    /** The address of the next one that is buffered behind the current one (see [prepareNext]). */
    private var preparedUrl: String? = null
    private var reportedDurationFor: String? = null
    private var reportedNextReady: String? = null

    /**
     * "Next" shown before the end (music): the next one is put behind the current one in the player, which
     * buffers it while the current one ends – so the switch is nearly seamless. Removed again if it is cancelled.
     */
    private fun prepareNext(st: RoomState) {
        // Told early (preparedNext, ~40 s before the end) or with the card (upNext) – whichever comes first.
        val early = st.upNext?.takeIf { it.early && it.media != null }
        val nextId = early?.item?.id ?: st.preparedNext?.item?.id
        val next = (early?.media ?: st.preparedNext?.media)?.takeIf { !it.live }
        val nextUrl = next?.let(urlFor)
        if (nextUrl == preparedUrl) {
            // Tell the server once a few seconds of it are buffered – it only switches straight on when every
            // app has done so (otherwise everyone waits for each other as usual).
            if (nextUrl != null && reportedNextReady != nextUrl && player.currentMediaItemIndex == 0 && player.mediaItemCount > 1) {
                val left = (player.duration - player.currentPosition).takeIf { player.duration != C.TIME_UNSET } ?: return
                if (player.totalBufferedDuration - left >= 3000) {
                    reportedNextReady = nextUrl
                    nextId?.let(sync::nextReady)
                }
            }
            return
        }
        if (player.mediaItemCount > 1 && player.currentMediaItemIndex == 0) player.removeMediaItems(1, player.mediaItemCount)
        preparedUrl = null
        if (next != null && nextUrl != null && player.mediaItemCount == 1) {
            addSource(nextUrl, next.type == "hls", audioFor(next, nextUrl), captionsFor(next, nextUrl), append = true)
            preparedUrl = nextUrl
        }
    }

    private fun load(url: String) {
        addSource(url, hls, audioUrl, captionsUrl, append = false)
        player.prepare()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun addSource(url: String, hls: Boolean, audio: String?, captions: String?, append: Boolean) {
        val item = MediaItem.Builder().setUri(url).apply {
            if (hls) setMimeType(MimeTypes.APPLICATION_M3U8)
            // CC: the plugin's captions as a subtitle file next to the video, shown straight away.
            if (captions != null) setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(captions))
                    .setId(CAPTIONS_ID).setLabel("CC").setMimeType(MimeTypes.TEXT_VTT)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build(),
            ))
        }.build()
        if (audio == null) {
            if (append) player.addMediaItem(item) else player.setMediaItem(item)
        } else {
            // Video and audio come as two files (e.g. YouTube in HD) – played as one (with the captions, if any).
            val video = DefaultMediaSourceFactory(http).createMediaSource(item)
            val source = MergingMediaSource(video, ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(audio)))
            if (append) player.addMediaSource(source) else player.setMediaSource(source)
        }
    }

    /** The sound goes out undecoded (to the TV or an amplifier) – then the speed cannot be changed. */
    private fun passthroughAudio(): Boolean {
        val mime = player.audioFormat?.sampleMimeType ?: return false
        return mime in setOf(
            MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_AC4,
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_TRUEHD,
        )
    }

    private fun setRate(r: Float, force: Boolean = false) {
        if (force || abs(r - rate) > 0.004f) {
            rate = r
            player.setPlaybackSpeed(r)
        }
    }

    private fun ms(sec: Double) = (sec.coerceAtLeast(0.0) * 1000).toLong()
}
