package app.couchmate

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * How pictures (posters, logos, stream previews) are fetched:
 *  - many at a time from the same site – a page of Twitch or YouTube cards all come from one host, and the
 *    default (5 at a time) made them trickle in;
 *  - kept on the device for a while even when the site says not to (Twitch previews carry their own
 *    time stamp, so they still change every few minutes);
 *  - a larger memory cache, so going back to a page shows its pictures at once.
 */
fun imageLoader(context: Context): ImageLoader {
    val http = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 24 })
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    return ImageLoader.Builder(context)
        .okHttpClient(http)
        .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("images")).maxSizeBytes(300L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .crossfade(false)
        .build()
}
