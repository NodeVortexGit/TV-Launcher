package com.nodevortex.aura.bridge

import android.content.Context
import android.util.Log
import android.util.Base64
import com.nodevortex.aura.Aura
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.crypto.Cipher

/**
 * Minimal ADB client talking to the Chromecast's own adbd on loopback.
 *
 * Gives the launcher shell-level powers that no normal app has: keeping Google's launcher off,
 * typing channel numbers into COTV, clearing other apps' caches, force-stopping.
 * Needs a one-time "Allow debugging" approval on the TV (over port 5555, from tools/setup.sh);
 * after a reboot it reconnects by itself through Wireless debugging (TLS) with the same key.
 */
class AdbBridge(private val ctx: Context) {

    enum class State { UNKNOWN, CONNECTING, WAITING_APPROVAL, READY, OFFLINE }

    @Volatile var state = State.UNKNOWN
        private set
    val ready get() = state == State.READY
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "aura-adb") }
    private val keys: KeyPair by lazy { loadOrCreateKeys() }

    fun addListener(l: (State) -> Unit) = listeners.add(l)
    fun removeListener(l: (State) -> Unit) = listeners.remove(l)

    private fun set(s: State) {
        if (state == s) return
        state = s
        Aura.ui { listeners.forEach { it(s) } }
    }

    /** Quietly check whether the bridge works (only succeeds if our key is already trusted). */
    fun probe(attempt: Int = 0) { exec.execute {
        if (!Aura.prefs.getBoolean("bridgeTrusted", false)) { set(State.OFFLINE); return@execute }
        val out = runShell("echo ok", 4000, allowPrompt = false)
        val ok = out?.trim() == "ok"
        set(if (ok) State.READY else State.OFFLINE)
        // Right after boot adbd / wireless debugging may still be starting: try again a few times.
        if (!ok && attempt < 4) Aura.main.postDelayed({ if (state != State.READY) probe(attempt + 1) }, 20_000L shl attempt)
    } }

    /** Interactive connect: may show "Allow USB debugging?" on the TV for our key. */
    fun connect(cb: ((Boolean) -> Unit)? = null) = exec.execute {
        set(State.CONNECTING)
        val out = runShell("echo ok", 60_000, allowPrompt = true)
        val ok = out?.trim() == "ok"
        if (ok) Aura.prefs.edit().putBoolean("bridgeTrusted", true).apply()
        set(if (ok) State.READY else State.OFFLINE)
        Aura.ui { cb?.invoke(ok) }
    }

    fun shell(cmd: String, timeoutMs: Long = 15_000, cb: ((String?) -> Unit)? = null) = exec.execute {
        val out = if (state == State.READY || Aura.prefs.getBoolean("bridgeTrusted", false)) runShell(cmd, timeoutMs, false) else null
        if (out == null && state == State.READY) set(State.OFFLINE) else if (out != null && state != State.READY) set(State.READY)
        if (cb != null) Aura.ui { cb(out) }
    }

    // ------------------------------------------------------------------------------ protocol

    /**
     * A shell on this TV. Tries adbd's plain TCP port (after `adb tcpip 5555`) first, then the
     * encrypted Wireless-debugging port, which VeloCinema can switch on by itself after a reboot
     * (it holds WRITE_SECURE_SETTINGS). Both accept the key you approved once on the TV.
     */
    private fun runShell(cmd: String, timeoutMs: Long, allowPrompt: Boolean): String? {
        // One persistent connection, like the real adb: adbd copes badly with rapid reconnects
        // (it resets or silently drops a connection that arrives while the last one is closing).
        for (attempt in 0..1) {
            val link = conn ?: (linkTcp(timeoutMs, allowPrompt) ?: linkTls(timeoutMs))?.also { conn = it }
                ?: run { Log.w(TAG, "no adbd link (5555 closed, TLS port ${Aura.prefs.getInt("adbTlsPort", 0)})"); return null }
            val local = nextLocal++.also { if (nextLocal > 1_000_000) nextLocal = 1 }
            try {
                link.sock.soTimeout = timeoutMs.toInt().coerceAtLeast(2000)
                send(link.os, OPEN, local, 0, "shell:$cmd\u0000".toByteArray())
                val sb = StringBuilder()
                var remote = 0
                while (true) {
                    val m = read(link.ins)
                    if (m.arg1 != local) continue // late packets of an earlier stream
                    when (m.cmd) {
                        OKAY -> remote = m.arg0
                        WRTE -> { sb.append(String(m.data, Charsets.UTF_8)); send(link.os, OKAY, local, m.arg0, ByteArray(0)) }
                        CLSE -> { send(link.os, CLSE, local, remote, ByteArray(0)); return sb.toString() }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "shell '$cmd' failed (attempt $attempt): $t")
                drop()
                if (attempt == 0) Thread.sleep(600)
            }
        }
        return null
    }

    @Volatile private var conn: Link? = null
    private var nextLocal = 1
    private fun drop() { try { conn?.sock?.close() } catch (_: Throwable) { }; conn = null }

    private class Link(val sock: Socket, val ins: DataInputStream, val os: OutputStream)

    /** [timeoutMs] bounds the handshake only (long when the TV may be showing the approval prompt). */
    private fun open(port: Int, timeoutMs: Long): Socket? = try {
        Socket().apply {
            connect(InetSocketAddress("127.0.0.1", port), 1500)
            soTimeout = timeoutMs.toInt().coerceIn(2000, 60_000); tcpNoDelay = true; keepAlive = true
        }
    } catch (_: Throwable) { null }

    private fun linkTcp(timeoutMs: Long, allowPrompt: Boolean): Link? {
        val s = open(5555, if (allowPrompt) timeoutMs else 5000) ?: return null
        return handshake(s, allowPrompt).also { if (it == null) try { s.close() } catch (_: Throwable) { } }
    }

    /**
     * Straight to the remembered port (no probe first: adbd resets a connection that arrives while it
     * is still tearing down the previous one from the same address). Rediscover only if that fails.
     */
    private fun linkTls(timeoutMs: Long): Link? {
        val cached = Aura.prefs.getInt("adbTlsPort", 0)
        if (cached > 0) tlsLink(cached, timeoutMs)?.let { return it }
        val port = findTlsPort() ?: return null
        Thread.sleep(400)
        return tlsLink(port, timeoutMs) ?: run { Thread.sleep(900); tlsLink(port, timeoutMs) }
    }

    private fun tlsLink(port: Int, timeoutMs: Long): Link? {
        val s = open(port, 5000) ?: return null
        return handshake(s, false).also { if (it == null) try { s.close() } catch (_: Throwable) { } }
    }

    /** CNXN, then either classic RSA AUTH or STLS (TLS 1.3 with our key as the client certificate). */
    private fun handshake(sock: Socket, allowPrompt: Boolean): Link? {
        try {
            val ins = DataInputStream(sock.getInputStream().buffered())
            val os = sock.getOutputStream()
            send(os, CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
            var signed = false
            while (true) {
                val m = read(ins)
                when (m.cmd) {
                    AUTH -> {
                        if (m.arg0 != 1) return null
                        if (!signed) { send(os, AUTH, 2, 0, sign(m.data)); signed = true }
                        else {
                            if (!allowPrompt) return null
                            set(State.WAITING_APPROVAL)
                            send(os, AUTH, 3, 0, publicKeyBlob())
                        }
                    }
                    STLS -> {
                        send(os, STLS, STLS_VERSION, 0, ByteArray(0))
                        val ssl = tlsContext().socketFactory.createSocket(sock, "127.0.0.1", sock.port, true) as javax.net.ssl.SSLSocket
                        ssl.useClientMode = true
                        ssl.startHandshake()
                        val sins = DataInputStream(ssl.inputStream.buffered())
                        val reply = read(sins)
                        return if (reply.cmd == CNXN) Link(ssl, sins, ssl.outputStream) else null
                    }
                    CNXN -> return Link(sock, ins, os)
                    else -> return null
                }
            }
        } catch (t: Throwable) { Log.w(TAG, "handshake on ${sock.port} failed: $t"); return null }
    }

    // ------------------------------------------------------------------------------ TLS (wireless debugging)

    private fun tlsContext(): javax.net.ssl.SSLContext {
        val cert = clientCert
        val km = object : javax.net.ssl.X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf("k")
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?) = "k"
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, engine: javax.net.ssl.SSLEngine?) = "k"
            override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?) = arrayOf(cert)
            override fun getPrivateKey(alias: String?) = keys.private
        }
        // adbd presents a self-signed certificate; we are talking to our own device over loopback.
        val tm = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        return javax.net.ssl.SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(km), arrayOf(tm), null) }
    }

    /** Minimal self-signed X.509 certificate for our ADB key (DER built by hand; no BouncyCastle). */
    private val clientCert: java.security.cert.X509Certificate by lazy { buildCert() }

    private fun buildCert(): java.security.cert.X509Certificate {
        fun len(n: Int): ByteArray = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
            else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        }
        fun tlv(tag: Int, body: ByteArray) = byteArrayOf(tag.toByte()) + len(body.size) + body
        fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
        val sha256Rsa = seq(byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B), byteArrayOf(0x05, 0x00))
        val name = seq(tlv(0x31, seq(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03), tlv(0x0C, "velocinema".toByteArray()))))
        val validity = seq(tlv(0x17, "250101000000Z".toByteArray()), tlv(0x17, "491231235959Z".toByteArray()))
        val tbs = seq(
            tlv(0xA0, tlv(0x02, byteArrayOf(2))),               // v3
            tlv(0x02, BigInteger.valueOf(System.currentTimeMillis()).toByteArray()),
            sha256Rsa, name, validity, name,
            keys.public.encoded,                                 // SubjectPublicKeyInfo
        )
        val sig = java.security.Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update(tbs); sign() }
        val der = seq(tbs, sha256Rsa, tlv(0x03, byteArrayOf(0) + sig))
        return java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as java.security.cert.X509Certificate
    }

    /** Port of adbd's TLS (wireless debugging) server: switch wireless debugging on if needed and find it. */
    private fun findTlsPort(): Int? {
        if (!Aura.prefs.getBoolean("bridgeTrusted", false)) return null
        try {
            val cr = ctx.contentResolver
            if (android.provider.Settings.Global.getInt(cr, "adb_wifi_enabled", 0) != 1) {
                android.provider.Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                Thread.sleep(2500)
            }
        } catch (_: Throwable) { }
        // Fast path: adbd runs as the shell uid and is (nearly) its only listener — read it from the
        // kernel's socket table (readable for targetSdk ≤ 28 apps). Retry while adbd is still starting.
        repeat(10) {
            val cands = adbdListeners()
            // A single adbd listener needs no probe; several (rare) get asked for STLS.
            (if (cands.size == 1) cands.first() else cands.firstOrNull { isStls(it) })
                ?.let { Aura.prefs.edit().putInt("adbTlsPort", it).apply(); return it }
            Thread.sleep(1000)
        }
        scanForStls()?.let { Aura.prefs.edit().putInt("adbTlsPort", it).apply(); return it }
        return null
    }

    /** Listening TCP ports owned by uid 2000 (shell = adbd), from /proc/net/tcp{6,}. */
    private fun adbdListeners(): List<Int> {
        val out = ArrayList<Int>(2)
        for (f in arrayOf("/proc/net/tcp6", "/proc/net/tcp")) try {
            java.io.File(f).forEachLine { line ->
                val c = line.trim().split(' ').filter { it.isNotEmpty() }
                if (c.size > 7 && c[3] == "0A" && c[7] == "2000") {
                    val port = c[1].substringAfterLast(':').toIntOrNull(16) ?: return@forEachLine
                    if (port != 5555 && port !in out) out += port
                }
            }
        } catch (_: Throwable) { }
        return out
    }

    /** adbd picks a random ephemeral port; scan loopback quickly and ask each open port for STLS. */
    private fun scanForStls(): Int? {
        val open = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val pool = Executors.newFixedThreadPool(8)
        val range = 32768..60999
        val chunk = (range.last - range.first + 1) / 8 + 1
        for (t in 0 until 8) pool.execute {
            val from = range.first + t * chunk; val to = minOf(range.last, from + chunk - 1)
            for (p in from..to) {
                if (p == com.nodevortex.aura.system.ShareServer.PORT) continue
                try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", p), 40) }; open.add(p) } catch (_: Throwable) { }
            }
        }
        pool.shutdown(); pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
        return open.sorted().firstOrNull { isStls(it) }
    }

    private fun isStls(port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 400); s.soTimeout = 1500
            send(s.getOutputStream(), CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
            read(DataInputStream(s.getInputStream())).cmd == STLS
        }
    } catch (_: Throwable) { false }

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun read(ins: DataInputStream): Msg {
        val h = ByteArray(24); ins.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        val data = ByteArray(len); if (len > 0) ins.readFully(data)
        return Msg(cmd, a0, a1, data)
    }

    private fun send(os: OutputStream, cmd: Int, a0: Int, a1: Int, data: ByteArray) {
        var sum = 0; for (x in data) sum += x.toInt() and 0xff
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(sum).putInt(cmd xor -1).put(data)
        os.write(b.array()); os.flush()
    }

    private fun sign(token: ByteArray): ByteArray {
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.ENCRYPT_MODE, keys.private)
        c.update(SHA1_PREFIX)
        return c.doFinal(token)
    }

    /** adbd's mincrypt RSAPublicKey struct, base64 + " name\0". */
    private fun publicKeyBlob(): ByteArray {
        val pub = keys.public as RSAPublicKey
        val n = pub.modulus
        val words = 64
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words).putInt(n0inv.toInt())
        for (i in 0 until words) b.putInt(n.shiftRight(32 * i).mod(r32).toInt())
        for (i in 0 until words) b.putInt(rr.shiftRight(32 * i).mod(r32).toInt())
        b.putInt(pub.publicExponent.toInt())
        return (Base64.encodeToString(b.array(), Base64.NO_WRAP) + " velocinema@launcher\u0000").toByteArray()
    }

    private fun loadOrCreateKeys(): KeyPair {
        val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "key"); val pub = File(dir, "key.pub")
        if (priv.exists() && pub.exists()) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                return KeyPair(kf.generatePublic(X509EncodedKeySpec(pub.readBytes())), kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())))
            } catch (_: Throwable) { }
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        priv.writeBytes(kp.private.encoded); pub.writeBytes(kp.public.encoded)
        return kp
    }

    companion object {
        private const val TAG = "VeloBridge"
        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val CLSE = 0x45534c43
        private const val WRTE = 0x45545257
        private const val STLS = 0x534C5453
        private const val STLS_VERSION = 0x01000000
        private const val VERSION = 0x01000001
        private const val MAX_DATA = 256 * 1024
        private val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
    }
}
