package coil.fetch

import android.net.Uri
import android.os.NetworkOnMainThreadException
import android.webkit.MimeTypeMap
import androidx.annotation.VisibleForTesting
import coil.ImageLoader
import coil.decode.DataSource.DISK
import coil.decode.DataSource.NETWORK
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.network.HttpException
import coil.request.Options
import coil.request.Parameters
import coil.util.await
import coil.util.closeQuietly
import coil.util.dispatcher
import coil.util.getMimeTypeFromUrl
import kotlinx.coroutines.MainCoroutineDispatcher
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import kotlin.coroutines.coroutineContext

internal class HttpUrlFetcher(
    private val url: String,
    private val options: Options,
    private val callFactory: Call.Factory,
    private val diskCache: DiskCache?
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        // Fast path: fetch the image from the disk cache.
        var snapshot = readFromDiskCache()
        if (snapshot != null) {
            try {
                val source = snapshot.toImageSource()
                val metadata = snapshot.metadata.source().buffer().use(::Metadata)
                val mimeType = getMimeType(url, metadata.contentType())
                return SourceResult(source, mimeType, DISK)
            } catch (e: Exception) {
                snapshot.closeQuietly()
                throw e
            }
        }

        // Slow path: fetch the image from the network.
        val response = executeNetworkRequest()
        val body = checkNotNull(response.body) { "response body == null" }
        try {
            // Read the response from the disk cache after writing it.
            snapshot = writeToDiskCache(response, body)
            if (snapshot != null) {
                try {
                    val source = snapshot.toImageSource()
                    val metadata = snapshot.metadata.source().buffer().use(::Metadata)
                    val mimeType = getMimeType(url, metadata.contentType())
                    return SourceResult(source, mimeType, NETWORK)
                } catch (e: Exception) {
                    snapshot.closeQuietly()
                    throw e
                }
            }

            // Read the response directly from the response body.
            val source = ImageSource(body.source(), options.context)
            val mimeType = getMimeType(url, body.contentType())
            val dataSource = if (response.networkResponse != null) NETWORK else DISK
            return SourceResult(source, mimeType, dataSource)
        } catch (e: Exception) {
            body.closeQuietly()
            throw e
        }
    }

    private fun readFromDiskCache(): DiskCache.Snapshot? {
        if (!options.diskCachePolicy.readEnabled) return null
        return diskCache?.get(url)
    }

    private fun writeToDiskCache(response: Response, body: ResponseBody): DiskCache.Snapshot? {
        if (!options.diskCachePolicy.writeEnabled) return null
        val editor = diskCache?.edit(url) ?: return null
        try {
            editor.metadata.sink().buffer().use { Metadata(response).writeTo(it) }
            editor.data.sink().buffer().use { it.writeAll(body.source()) }
            return if (options.diskCachePolicy.readEnabled) {
                editor.commitAndGet()
            } else {
                editor.commit().run { null }
            }
        } catch (e: Exception) {
            try {
                editor.abort()
            } catch (_: Exception) {}
            throw e
        }
    }

    private suspend fun executeNetworkRequest(): Response {
        val request = Request.Builder()
            .url(url)
            .headers(options.headers)
            // Support attaching custom data to the network request.
            .tag(Parameters::class.java, options.parameters)

        val diskRead = options.diskCachePolicy.readEnabled
        val networkRead = options.networkCachePolicy.readEnabled
        when {
            !networkRead && diskRead -> {
                request.cacheControl(CacheControl.FORCE_CACHE)
            }
            networkRead && !diskRead -> if (options.diskCachePolicy.writeEnabled) {
                request.cacheControl(CacheControl.FORCE_NETWORK)
            } else {
                request.cacheControl(CACHE_CONTROL_FORCE_NETWORK_NO_CACHE)
            }
            !networkRead && !diskRead -> {
                // This causes the request to fail with a 504 Unsatisfiable Request.
                request.cacheControl(CACHE_CONTROL_NO_NETWORK_NO_CACHE)
            }
        }

        val response = if (coroutineContext.dispatcher is MainCoroutineDispatcher) {
            if (networkRead) {
                // Prevent executing requests on the main thread that could block due to a
                // networking operation.
                throw NetworkOnMainThreadException()
            } else {
                // Work around https://github.com/Kotlin/kotlinx.coroutines/issues/2448 by
                // blocking the current context.
                callFactory.newCall(request.build()).execute()
            }
        } else {
            // Suspend and enqueue the request on one of OkHttp's dispatcher threads.
            callFactory.newCall(request.build()).await()
        }
        if (!response.isSuccessful) {
            response.body?.closeQuietly()
            throw HttpException(response)
        }
        return response
    }

    /**
     * Parse the response's `content-type` header.
     *
     * "text/plain" is often used as a default/fallback MIME type.
     * Attempt to guess a better MIME type from the file extension.
     */
    @VisibleForTesting
    internal fun getMimeType(url: String, contentType: MediaType?): String? {
        val rawContentType = contentType?.toString()
        if (rawContentType == null || rawContentType.startsWith(MIME_TYPE_TEXT_PLAIN)) {
            MimeTypeMap.getSingleton().getMimeTypeFromUrl(url)?.let { return it }
        }
        return rawContentType?.substringBefore(';')
    }

    private fun DiskCache.Snapshot.toImageSource(): ImageSource {
        return ImageSource(file = data, diskCacheKey = url, closeable = this)
    }

    private class Metadata {

        val sentRequestMillis: Long
        val receivedResponseMillis: Long
        val responseHeaders: Headers

        constructor(source: BufferedSource) {
            this.sentRequestMillis = source.readUtf8LineStrict().toLong()
            this.receivedResponseMillis = source.readUtf8LineStrict().toLong()
            val responseHeadersLineCount = source.readUtf8LineStrict().toInt()
            val responseHeaders = Headers.Builder()
            for (i in 0 until responseHeadersLineCount) {
                responseHeaders.add(source.readUtf8LineStrict())
            }
            this.responseHeaders = responseHeaders.build()
        }

        constructor(response: Response) {
            this.sentRequestMillis = response.sentRequestAtMillis
            this.receivedResponseMillis = response.receivedResponseAtMillis
            this.responseHeaders = response.headers
        }

        fun writeTo(sink: BufferedSink) {
            sink.writeDecimalLong(sentRequestMillis).writeByte('\n'.code)
            sink.writeDecimalLong(receivedResponseMillis).writeByte('\n'.code)
            sink.writeDecimalLong(responseHeaders.size.toLong()).writeByte('\n'.code)
            for (i in 0 until responseHeaders.size) {
                sink.writeUtf8(responseHeaders.name(i))
                    .writeUtf8(": ")
                    .writeUtf8(responseHeaders.value(i))
                    .writeByte('\n'.code)
            }
        }

        fun contentType(): MediaType? {
            return responseHeaders[CONTENT_TYPE_HEADER]?.toMediaTypeOrNull()
        }
    }

    class Factory(
        private val callFactory: Call.Factory,
        private val diskCache: DiskCache?
    ) : Fetcher.Factory<Uri> {

        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!isApplicable(data)) return null
            return HttpUrlFetcher(data.toString(), options, callFactory, diskCache)
        }

        private fun isApplicable(data: Uri): Boolean {
            return data.scheme == "http" || data.scheme == "https"
        }
    }

    companion object {
        private const val MIME_TYPE_TEXT_PLAIN = "text/plain"
        private const val CONTENT_TYPE_HEADER = "content-type"
        private val CACHE_CONTROL_FORCE_NETWORK_NO_CACHE =
            CacheControl.Builder().noCache().noStore().build()
        private val CACHE_CONTROL_NO_NETWORK_NO_CACHE =
            CacheControl.Builder().noCache().onlyIfCached().build()
    }
}