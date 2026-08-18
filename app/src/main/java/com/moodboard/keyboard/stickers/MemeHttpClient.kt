package com.moodboard.keyboard.stickers

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp client for every [MemeSource]. Timeouts are deliberately short: each
 * source also runs under [MemeAggregator]'s ~6s `withTimeoutOrNull` per-source budget,
 * and a synchronous OkHttp call is not itself cancelled by coroutine cancellation - it
 * keeps blocking its IO-dispatcher thread until it returns on its own. Keeping OkHttp's
 * own connect/read timeouts close to that budget means a hung provider frees its thread
 * back to the pool promptly instead of parking it indefinitely.
 */
internal object MemeHttpClient {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /** GET [url], returning the response body. Throws on transport failure or non-2xx. */
    fun get(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("Meme source HTTP ${resp.code}: ${body.take(160)}")
            }
            return body
        }
    }

    /** GET [url], returning the raw body bytes (image download). Null on any failure. */
    fun getBytes(url: String): ByteArray? = try {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    } catch (t: Throwable) {
        null
    }
}
