package app.couchmate

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

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
            setRate(1f, force = true)
            hls = media.type == "hls"
            load(url)
            if (!media.live) {
                // Joining in the middle of a movie starts right where the others are.
                val start = if (st.paused || sync.serverNow() < st.refTime) st.position
                else st.position + (sync.serverNow() - st.refTime) / 1000.0 + seekLead
                player.seekTo(ms(start))
                seekedVersion = st.version
                lastSeekAt = now
            }
        }

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
        val pos = positionSec()

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
            seekLead = (seekLead - d * 0.7).coerceIn(0.3, 6.0)
            leadCheck = false
        }
        val dur = durationSec()
        if (dur > 0 && target > dur) {
            status = tr("Ended")
            return
        }
        // Does changing the speed work? With e.g. AC3/Dolby passed through undecoded the speed cannot change –
        // then we correct with small exact jumps instead.
        if (correcting && rateWorks == null && now - correctStartAt > 5000) {
            rateWorks = abs(d) < abs(correctStartDrift) - 0.1
        }
        val fineSeek = rateWorks == false && abs(d) > 0.25 && now - lastFineSeekAt > 8000
        when {
            abs(d) > 2.0 || fineSeek -> {
                setRate(1f)
                correcting = false
                if (fineSeek) lastFineSeekAt = now
                player.seekTo(ms(target + seekLead))
                lastSeekAt = now
                cooldownUntil = now + 2500
                leadCheck = true
                status = tr("Syncing…")
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

    private fun load(url: String) {
        val item = MediaItem.Builder().setUri(url).apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        player.setMediaItem(item)
        player.prepare()
    }

    private fun setRate(r: Float, force: Boolean = false) {
        if (force || abs(r - rate) > 0.004f) {
            rate = r
            player.setPlaybackSpeed(r)
        }
    }

    private fun ms(sec: Double) = (sec.coerceAtLeast(0.0) * 1000).toLong()
}
