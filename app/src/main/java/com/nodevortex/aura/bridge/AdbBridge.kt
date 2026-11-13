package com.nodevortex.aura.bridge

import android.content.Context
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
 * Minimal ADB client talking to the Chromecast's own adbd over 127.0.0.1:5555.
 *
 * Gives the launcher shell-level powers that no normal app has: typing channel numbers into
 * COTV, clearing other apps' caches, force-stopping, whitelisting from background limits.
 * Needs `adb tcpip 5555` once per boot and a one-time "Allow debugging" approval on the TV.
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
    fun probe() = exec.execute {
        if (!Aura.prefs.getBoolean("bridgeTrusted", false)) { set(State.OFFLINE); return@execute }
        val out = runShell("echo ok", 4000, allowPrompt = false)
        set(if (out?.trim() == "ok") State.READY else State.OFFLINE)
    }

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

    private fun runShell(cmd: String, timeoutMs: Long, allowPrompt: Boolean): String? {
        var sock: Socket? = null
        try {
            sock = Socket()
            sock.connect(InetSocketAddress("127.0.0.1", 5555), 1500)
            sock.soTimeout = timeoutMs.toInt().coerceAtLeast(2000)
            sock.tcpNoDelay = true
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
                    CNXN -> break
                    else -> return null
                }
            }
            val local = 1
            send(os, OPEN, local, 0, "shell:$cmd\u0000".toByteArray())
            val sb = StringBuilder()
            var remote = 0
            while (true) {
                val m = read(ins)
                when (m.cmd) {
                    OKAY -> remote = m.arg0
                    WRTE -> { sb.append(String(m.data, Charsets.UTF_8)); send(os, OKAY, local, m.arg0, ByteArray(0)) }
                    CLSE -> { send(os, CLSE, local, remote, ByteArray(0)); return sb.toString() }
                }
            }
        } catch (t: Throwable) {
            return null
        } finally {
            try { sock?.close() } catch (_: Throwable) { }
        }
    }

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
        return (Base64.encodeToString(b.array(), Base64.NO_WRAP) + " aura@launcher\u0000").toByteArray()
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
        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val CLSE = 0x45534c43
        private const val WRTE = 0x45545257
        private const val VERSION = 0x01000001
        private const val MAX_DATA = 256 * 1024
        private val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
    }
}
