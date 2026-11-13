package com.nodevortex.aura.system

import com.nodevortex.aura.Aura
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Real throughput test against Cloudflare's speed endpoints (download, upload, latency). */
class SpeedTest(private val onUpdate: (phase: Phase, mbps: Float, pingMs: Int) -> Unit, private val onDone: (down: Float, up: Float, ping: Int) -> Unit) {
    enum class Phase { PING, DOWNLOAD, UPLOAD, DONE, FAILED }
    private val cancelled = AtomicBoolean(false)

    fun start() = Thread({ run() }, "aura-speedtest").start()
    fun cancel() = cancelled.set(true)

    private fun post(p: Phase, mbps: Float, ping: Int) = Aura.ui { if (!cancelled.get() || p == Phase.DONE) onUpdate(p, mbps, ping) }

    private fun run() {
        try {
            var ping = Int.MAX_VALUE
            repeat(5) {
                val t0 = System.nanoTime()
                val c = URL("$BASE/__down?bytes=0").openConnection() as HttpURLConnection
                c.connectTimeout = 4000; c.readTimeout = 4000
                c.inputStream.use { it.readBytes() }
                val ms = ((System.nanoTime() - t0) / 1_000_000).toInt()
                if (it > 0) ping = minOf(ping, ms)
                post(Phase.PING, 0f, if (ping == Int.MAX_VALUE) ms else ping)
            }
            if (cancelled.get()) return
            val down = measure(download = true, ping)
            if (cancelled.get()) return
            val up = measure(download = false, ping)
            Aura.ui { onUpdate(Phase.DONE, down, ping); onDone(down, up, ping) }
        } catch (t: Throwable) {
            post(Phase.FAILED, 0f, 0)
        }
    }

    private fun measure(download: Boolean, ping: Int): Float {
        val bytes = AtomicLong(0)
        val stop = AtomicBoolean(false)
        val threads = (0 until 3).map {
            Thread {
                try {
                    while (!stop.get() && !cancelled.get()) {
                        if (download) {
                            val c = URL("$BASE/__down?bytes=25000000").openConnection() as HttpURLConnection
                            c.connectTimeout = 5000; c.readTimeout = 5000
                            c.inputStream.use { ins ->
                                val buf = ByteArray(64 * 1024)
                                while (!stop.get()) { val n = ins.read(buf); if (n < 0) break; bytes.addAndGet(n.toLong()) }
                            }
                        } else {
                            val payload = Random.nextBytes(1024 * 1024)
                            val c = URL("$BASE/__up").openConnection() as HttpURLConnection
                            c.connectTimeout = 5000; c.readTimeout = 8000
                            c.doOutput = true; c.requestMethod = "POST"
                            c.setFixedLengthStreamingMode(payload.size * 6)
                            c.setRequestProperty("Content-Type", "application/octet-stream")
                            c.outputStream.use { os ->
                                repeat(6) {
                                    var off = 0
                                    while (off < payload.size && !stop.get()) { val n = minOf(16 * 1024, payload.size - off); os.write(payload, off, n); off += n; bytes.addAndGet(n.toLong()) }
                                }
                            }
                            try { c.inputStream.use { it.readBytes() } } catch (_: Throwable) { }
                        }
                    }
                } catch (_: Throwable) { }
            }.apply { isDaemon = true; start() }
        }
        val phase = if (download) Phase.DOWNLOAD else Phase.UPLOAD
        val start = System.nanoTime()
        var lastBytes = 0L; var lastT = start
        var smoothed = 0f
        val duration = if (download) 9_000L else 7_000L
        while (!cancelled.get()) {
            Thread.sleep(200)
            val now = System.nanoTime()
            val b = bytes.get()
            val inst = (b - lastBytes) * 8f / ((now - lastT) / 1e9f) / 1e6f
            lastBytes = b; lastT = now
            val elapsed = (now - start) / 1_000_000
            smoothed = if (smoothed == 0f) inst else smoothed * 0.7f + inst * 0.3f
            post(phase, smoothed, ping)
            if (elapsed > duration) break
        }
        stop.set(true)
        val total = (System.nanoTime() - start) / 1e9f
        // ignore TCP slow-start: average over the run but never below the smoothed tail
        return maxOf(bytes.get() * 8f / total / 1e6f, smoothed * 0.95f)
    }

    companion object { const val BASE = "https://speed.cloudflare.com" }
}
