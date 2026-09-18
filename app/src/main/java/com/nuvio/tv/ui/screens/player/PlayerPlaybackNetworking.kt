package com.nuvio.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.core.network.IPv4FirstDns
import okhttp3.OkHttpClient
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal object PlayerPlaybackNetworking {
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val playbackHostnameVerifier = HostnameVerifier { _, _ -> true }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    /**
     * Fallback OkHttpClient equipped with trust-all SSL configuration for self-signed
     * or untrusted local media servers (e.g. self-signed WebDAV / Plex / Jellyfin).
     */
    internal val trustAllPlaybackHttpClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(IPv4FirstDns())
            .eventListenerFactory(PlaybackConnectionEvents)
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier(playbackHostnameVerifier)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Primary OkHttpClient using standard system SSL certificates and full SNI support.
     * Includes an automatic fallback to [trustAllPlaybackHttpClient] if an [SSLException]
     * occurs on self-signed local media servers.
     */
    internal val playbackHttpClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(IPv4FirstDns())
            .eventListenerFactory(PlaybackConnectionEvents)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request()
                try {
                    chain.proceed(request)
                } catch (e: SSLException) {
                    // Fallback to trust-all client if standard system SSL fails (e.g. self-signed local server)
                    trustAllPlaybackHttpClient.newCall(request).execute()
                }
            }
            .build()
    }

    fun createHttpClient(defaultHeaders: Map<String, String> = emptyMap()): OkHttpClient {
        val builder = playbackHttpClient.newBuilder()
        if (defaultHeaders.any { it.key.equals("Authorization", ignoreCase = true) }) {
            // OkHttp strips the Authorization header on cross-host redirects.
            // WebDAV servers behind reverse proxies commonly redirect to a
            // different host/port, causing auth to be lost. A network
            // interceptor ensures the header is always present on every
            // outgoing request — same behavior as mpv/curl.
            val authValue = defaultHeaders.entries
                .first { it.key.equals("Authorization", ignoreCase = true) }
                .value
            builder.addNetworkInterceptor { chain ->
                val request = chain.request()
                if (request.header("Authorization") == null) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Authorization", authValue)
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
        }
        return builder
            .let { NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }
            .build()
    }

    @UnstableApi
    fun createHttpDataSourceFactory(defaultHeaders: Map<String, String> = emptyMap()): DataSource.Factory {
        val client = createHttpClient(defaultHeaders)
        val httpFactory = OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(defaultHeaders)
            if (defaultHeaders.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            }
        }
        return LoggingDataSourceFactory(httpFactory, "HTTP")
    }

    @UnstableApi
    fun createDataSourceFactory(
        context: android.content.Context,
        defaultHeaders: Map<String, String> = emptyMap()
    ): DataSource.Factory {
        return DefaultDataSource.Factory(context, createHttpDataSourceFactory(defaultHeaders))
    }

    fun openConnection(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String? = null
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            setRequestProperty("User-Agent", headers["User-Agent"] ?: PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            headers.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                setRequestProperty(key, value)
            }
            range?.let { setRequestProperty("Range", it) }
        }
    }

    @Volatile
    private var lastWarmUrl: String? = null

    @Volatile
    private var lastWarmAtMs: Long = 0L

    private const val WARM_DEDUP_WINDOW_MS = 60_000L

    /**
     * Pre-warms the HTTP/HTTPS playback connection for a stream URL so TCP handshake,
     * TLS negotiation, and CDN edge caches are already warmed when playback begins.
     * Fires a non-blocking 256 KB Range GET request and releases the connection back
     * into OkHttp's connection pool upon completion.
     */
    fun prewarmPlaybackConnection(url: String?, headers: Map<String, String>? = null) {
        val target = url?.trim().orEmpty()
        if (!target.startsWith("http://", ignoreCase = true) &&
            !target.startsWith("https://", ignoreCase = true)
        ) {
            return
        }
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val warmSuppressed = target == lastWarmUrl && (nowMs - lastWarmAtMs) in 0 until WARM_DEDUP_WINDOW_MS
        if (warmSuppressed) {
            return
        }
        lastWarmUrl = target
        lastWarmAtMs = nowMs

        try {
            val requestBuilder = okhttp3.Request.Builder().url(target)
            headers?.forEach { (name, value) ->
                if (!name.equals("Range", ignoreCase = true)) {
                    requestBuilder.header(name, value)
                }
            }
            requestBuilder.header("Range", "bytes=0-262143")
            val request = requestBuilder.build()

            playbackHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    // Benign fire-and-forget; failure leaves ExoPlayer to connect normally.
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use { resp ->
                        try {
                            resp.body?.source()?.readByteString()
                        } catch (_: Throwable) {
                            // Ignored
                        }
                    }
                }
            })
        } catch (_: Throwable) {
            // Ignored
        }
    }
}
