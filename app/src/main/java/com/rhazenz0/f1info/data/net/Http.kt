package com.rhazenz0.f1info.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class HttpException(val code: Int, message: String) : IOException(message)

/** Marks a request whose response never changes (a finished session's archive file). */
const val HEADER_IMMUTABLE = "X-F1Info-Immutable"

object Http {
    private const val USER_AGENT = "F1Info-Android/1.0"

    @Volatile
    private var instance: OkHttpClient? = null

    fun init(cacheDir: File?) {
        if (instance != null) return
        synchronized(this) {
            if (instance == null) instance = build(cacheDir)
        }
    }

    val client: OkHttpClient
        get() = instance ?: synchronized(this) { instance ?: build(null).also { instance = it } }

    private fun build(cacheDir: File?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .cookieJar(MemoryCookieJar())
            .addInterceptor(Interceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            })
            .addNetworkInterceptor(Interceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                if (request.header(HEADER_IMMUTABLE) != null && response.isSuccessful) {
                    response.newBuilder()
                        .header("Cache-Control", "public, max-age=2592000")
                        .removeHeader("Pragma")
                        .build()
                } else {
                    response
                }
            })
        if (cacheDir != null) builder.cache(Cache(File(cacheDir, "http"), 200L * 1024 * 1024))
        return builder.build()
    }

    /**
     * GETs [url] as text. Returns null when [missingIsNull] and the server answers 403/404
     * (the timing archive answers 403 for files that do not exist).
     */
    suspend fun getString(
        url: String,
        missingIsNull: Boolean = false,
        immutable: Boolean = false,
        limiter: RequestLimiter? = null,
    ): String? = withContext(Dispatchers.IO) {
        limiter?.acquire()
        val request = Request.Builder().url(url).apply { if (immutable) header(HEADER_IMMUTABLE, "1") }.build()
        client.newCall(request).await().use { response ->
            when {
                response.isSuccessful -> response.body?.string()?.removePrefix(com.rhazenz0.f1info.data.timing.BOM)
                missingIsNull && (response.code == 404 || response.code == 403) -> null
                response.code == 429 -> throw HttpException(429, "The data service is busy (rate limited). Try again in a minute.")
                else -> throw HttpException(response.code, "Server returned ${response.code} for ${request.url.host}")
            }
        }
    }

    suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }
}

/** Keeps load-balancer cookies so live timing requests stick to one server. */
private class MemoryCookieJar : CookieJar {
    private val store = HashMap<String, MutableMap<String, Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val map = store.getOrPut(url.host) { HashMap() }
        cookies.forEach { map[it.name] = it }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store[url.host]?.values?.filter { it.expiresAt > now && it.matches(url) }.orEmpty()
    }
}

/** Spaces requests out so a public API's per-second limit is respected. */
class RequestLimiter(private val minIntervalMs: Long) {
    private val mutex = Mutex()
    private var last = 0L

    suspend fun acquire() = mutex.withLock {
        val wait = last + minIntervalMs - System.currentTimeMillis()
        if (wait > 0) kotlinx.coroutines.delay(wait)
        last = System.currentTimeMillis()
    }
}
