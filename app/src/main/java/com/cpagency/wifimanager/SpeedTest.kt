package com.cpagency.wifimanager

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Real internet speed test against Cloudflare's speed servers
 * (same endpoints speed.cloudflare.com uses):
 *   GET  /__down?bytes=N  -> sends N bytes   (download)
 *   POST /__up            -> accepts a body  (upload)
 *   GET  /meta            -> your ISP, IP, server city
 *
 * Several connections run at once (like Speedtest/fast.com) so one slow TCP
 * stream doesn't hide the real line speed. The first second is ignored
 * (TCP warm-up), the rest is averaged.
 *
 * Plain Kotlin + OkHttp, no Android classes. Call from a background thread.
 */
class SpeedTest(private val base: String = "https://speed.cloudflare.com") {

    data class Meta(val isp: String, val ip: String, val server: String)
    data class Ping(val ms: Double, val jitterMs: Double)

    /** Live sample: current speed (last ~1 s), average so far, 0-100 progress of this phase. */
    data class Live(val currentMbps: Double, val averageMbps: Double, val progress: Int)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val stopped = AtomicBoolean(false)
    private val calls = CopyOnWriteArrayList<Call>()

    /** Stops whatever is running (e.g. user left the screen). */
    fun stop() {
        stopped.set(true)
        calls.forEach { it.cancel() }
    }

    fun meta(): Meta? = try {
        val req = Request.Builder().url("$base/meta").header("User-Agent", UA).build()
        client.newCall(req).execute().use { r ->
            val j = JSONObject(r.body?.string() ?: "{}")
            val city = j.optString("city")
            val colo = j.optString("colo")
            Meta(
                isp = j.optString("asOrganization").ifBlank { "--" },
                ip = j.optString("clientIp").ifBlank { "--" },
                server = listOf(city, colo).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "Cloudflare" }
            )
        }
    } catch (e: Exception) { null }

    /** Latency: time for tiny requests on a warm connection. */
    fun ping(count: Int = 8): Ping {
        val times = ArrayList<Double>()
        // first call opens the connection (TLS etc.) - not counted
        runCatching { tinyRequest() }
        repeat(count) {
            if (stopped.get()) return@repeat
            val t0 = System.nanoTime()
            if (runCatching { tinyRequest() }.isSuccess) times += (System.nanoTime() - t0) / 1e6
        }
        if (times.isEmpty()) throw IllegalStateException("No internet connection")
        val sorted = times.sorted()
        val median = sorted[sorted.size / 2]
        val jitter = times.zipWithNext { a, b -> Math.abs(a - b) }.average().takeIf { !it.isNaN() } ?: 0.0
        return Ping(median, jitter)
    }

    private fun tinyRequest() {
        val req = Request.Builder().url("$base/__down?bytes=0").header("User-Agent", UA).build()
        val call = client.newCall(req)
        calls += call
        try { call.execute().use { it.body?.bytes() } } finally { calls -= call }
    }

    fun download(seconds: Int = 10, streams: Int = 4, onLive: (Live) -> Unit): Double =
        measure(seconds, streams, onLive) { counter, running ->
            while (running.get()) {
                val req = Request.Builder().url("$base/__down?bytes=$CHUNK").header("User-Agent", UA).build()
                val call = client.newCall(req)
                calls += call
                try {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        val src = resp.body!!.byteStream()
                        val buf = ByteArray(32 * 1024)
                        while (running.get()) {
                            val n = src.read(buf)
                            if (n < 0) break
                            counter.addAndGet(n.toLong())
                        }
                    }
                } finally { calls -= call }
            }
        }

    fun upload(seconds: Int = 10, streams: Int = 4, onLive: (Live) -> Unit): Double =
        measure(seconds, streams, onLive) { counter, running ->
            val payload = ByteArray(64 * 1024) { (it * 31).toByte() }
            while (running.get()) {
                val body = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaType()
                    override fun contentLength() = CHUNK
                    override fun writeTo(sink: BufferedSink) {
                        var left = CHUNK
                        while (left > 0) {
                            if (!running.get()) throw java.io.IOException("stopped")
                            val n = minOf(left, payload.size.toLong()).toInt()
                            sink.write(payload, 0, n)
                            sink.flush()
                            counter.addAndGet(n.toLong())
                            left -= n
                        }
                    }
                }
                val req = Request.Builder().url("$base/__up").header("User-Agent", UA).post(body).build()
                val call = client.newCall(req)
                calls += call
                try { call.execute().use { it.body?.bytes() } } finally { calls -= call }
            }
        }

    /**
     * Runs [worker] on several threads for [seconds], sampling the byte counter
     * every 250 ms. Returns the average Mbps (ignoring the first second).
     */
    private fun measure(
        seconds: Int,
        streams: Int,
        onLive: (Live) -> Unit,
        worker: (AtomicLong, AtomicBoolean) -> Unit
    ): Double {
        if (stopped.get()) throw InterruptedException("stopped")
        val counter = AtomicLong(0)
        val running = AtomicBoolean(true)
        val errors = CopyOnWriteArrayList<Throwable>()
        // each connection keeps going even if one request fails (busy server, 429 etc.)
        val threads = (1..streams).map {
            thread(isDaemon = true) {
                while (running.get()) {
                    try { worker(counter, running) } catch (e: Throwable) {
                        if (!running.get()) break
                        errors += e
                        try { Thread.sleep(400) } catch (_: InterruptedException) { break }
                    }
                }
            }
        }

        val start = System.nanoTime()
        val totalMs = seconds * 1000L
        val warmupMs = 1000L
        var warmBytes = -1L
        var warmTime = 0L
        val window = ArrayDeque<Pair<Long, Long>>() // (time ms, bytes)
        var avg = 0.0
        try {
            while (true) {
                Thread.sleep(250)
                if (stopped.get()) throw InterruptedException("stopped")
                val now = (System.nanoTime() - start) / 1_000_000
                val bytes = counter.get()
                window.addLast(now to bytes)
                while (window.size > 1 && now - window.first().first > 1000) window.removeFirst()
                val (t0, b0) = window.first()
                val current = if (now > t0) mbps(bytes - b0, now - t0) else 0.0

                if (now >= warmupMs && warmBytes < 0) { warmBytes = bytes; warmTime = now }
                avg = if (warmBytes >= 0 && now > warmTime) mbps(bytes - warmBytes, now - warmTime) else current

                onLive(Live(current, avg, ((now * 100) / totalMs).toInt().coerceIn(0, 100)))
                if (now >= totalMs) break
                // nothing at all got through for 5 s -> give up with the real reason
                if (bytes == 0L && now >= 5000 && errors.isNotEmpty()) {
                    val msg = errors.last().message ?: "Connection failed"
                    throw IllegalStateException(if (msg.contains("429")) "Server busy, try again in a minute" else msg)
                }
            }
        } finally {
            running.set(false)
            calls.forEach { it.cancel() }
            threads.forEach { it.join(2000) }
        }
        return avg
    }

    private fun mbps(bytes: Long, ms: Long) = if (ms <= 0) 0.0 else bytes * 8.0 / ms / 1000.0

    companion object {
        private const val CHUNK = 25_000_000L
        private const val UA = "RouterManagerApp"

        /** Simple quality words for the result. */
        fun rating(mbps: Double) = when {
            mbps >= 50 -> "Excellent"
            mbps >= 20 -> "Good"
            mbps >= 8 -> "Average"
            mbps >= 2 -> "Slow"
            else -> "Very slow"
        }
    }
}
