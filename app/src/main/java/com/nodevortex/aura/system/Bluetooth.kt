package com.nodevortex.aura.system

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.Build
import com.nodevortex.aura.Aura
import com.nodevortex.aura.R
import java.io.InputStream
import java.util.UUID

@SuppressLint("MissingPermission")
object Bt {
    val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()
    @Volatile var a2dp: BluetoothA2dp? = null
        private set
    private val batteryCache = HashMap<String, Int>()

    fun init(ctx: Context) {
        if (a2dp != null) return
        adapter?.getProfileProxy(ctx.applicationContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { a2dp = proxy as BluetoothA2dp }
            override fun onServiceDisconnected(profile: Int) { a2dp = null }
        }, BluetoothProfile.A2DP)
    }

    fun bonded(): List<BluetoothDevice> = try { adapter?.bondedDevices?.toList() ?: emptyList() } catch (_: Throwable) { emptyList() }

    fun isConnected(d: BluetoothDevice): Boolean = try {
        d.javaClass.getMethod("isConnected").invoke(d) as Boolean
    } catch (_: Throwable) { a2dp?.connectedDevices?.contains(d) == true }

    fun isAudio(d: BluetoothDevice): Boolean {
        val c = d.bluetoothClass ?: return false
        return c.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO || c.hasService(BluetoothClass.Service.AUDIO) || c.hasService(BluetoothClass.Service.RENDER)
    }

    fun kindIcon(d: BluetoothDevice): Int {
        val c = d.bluetoothClass
        val name = (d.name ?: "").lowercase()
        return when {
            name.contains("remote") -> R.drawable.ic_remote
            c == null -> R.drawable.ic_bluetooth
            c.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES || c.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
                c.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE || name.contains("buds") || name.contains("pods") -> R.drawable.ic_headphones
            c.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO -> R.drawable.ic_speaker
            c.majorDeviceClass == BluetoothClass.Device.Major.PERIPHERAL && name.contains("key") -> R.drawable.ic_keyboard
            c.majorDeviceClass == BluetoothClass.Device.Major.PERIPHERAL -> R.drawable.ic_gamepad
            else -> R.drawable.ic_bluetooth
        }
    }

    /** Battery % the Bluetooth stack knows (HFP/AVRCP/BAS); -1 if unknown. */
    fun battery(d: BluetoothDevice): Int {
        val v = try { d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int } catch (_: Throwable) { -1 }
        if (v in 0..100) { batteryCache[d.address] = v; return v }
        return batteryCache[d.address] ?: -1
    }

    fun noteBattery(addr: String, v: Int) { if (v in 0..100) batteryCache[addr] = v }

    fun displayName(d: BluetoothDevice): String = try {
        (d.javaClass.getMethod("getAlias").invoke(d) as? String) ?: d.name ?: d.address
    } catch (_: Throwable) { d.name ?: d.address }

    /** Tries to connect a paired device's audio profile. Returns false if the OS refuses. */
    fun connect(d: BluetoothDevice): Boolean {
        val p = a2dp ?: return false
        return try { p.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(p, d) as Boolean } catch (_: Throwable) { false }
    }
}

/** Audio outputs for the volume pop-up and quick settings. */
object AudioOutputs {
    enum class Kind { TV, HEADPHONES, SPEAKER, OTHER }
    class Output(val id: String, val name: String, val kind: Kind, val connected: Boolean, val active: Boolean, val device: BluetoothDevice?, val battery: Int)

    @SuppressLint("MissingPermission")
    fun list(ctx: Context): List<Output> {
        Bt.init(ctx)
        val am = ctx.getSystemService(AudioManager::class.java)
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val btActive = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER }
        val res = ArrayList<Output>()
        res += Output("tv", tvName(ctx), Kind.TV, true, btActive == null, null, -1)
        for (d in Bt.bonded().filter { Bt.isAudio(it) }) {
            val connected = Bt.isConnected(d)
            val icon = Bt.kindIcon(d)
            val active = connected && btActive != null && (btActive.address == d.address || btActive.productName?.toString() == d.name)
            res += Output(d.address, Bt.displayName(d), if (icon == R.drawable.ic_speaker) Kind.SPEAKER else Kind.HEADPHONES, connected, active, d, if (connected) Bt.battery(d) else -1)
        }
        return res.sortedWith(compareByDescending<Output> { it.active }.thenByDescending { it.connected })
    }

    fun current(ctx: Context): Output = list(ctx).firstOrNull { it.active } ?: Output("tv", tvName(ctx), Kind.TV, true, true, null, -1)

    private fun tvName(ctx: Context) = "Телевизор"

    fun icon(k: Kind) = when (k) {
        Kind.TV -> R.drawable.ic_tv; Kind.HEADPHONES -> R.drawable.ic_headphones; Kind.SPEAKER -> R.drawable.ic_speaker; Kind.OTHER -> R.drawable.ic_volume
    }

    /**
     * Switch output. Tries the public MediaRouter2 system routes first, then the Bluetooth
     * profile, and finally falls back to the system's own output picker.
     */
    fun select(ctx: Context, o: Output, done: (Boolean) -> Unit) {
        if (o.active) { done(true); return }
        Aura.io.execute {
            var ok = viaRouter(ctx, o)
            if (!ok && o.device != null && !o.connected) ok = Bt.connect(o.device)
            Aura.ui {
                if (!ok) openSystemPicker(ctx)
                done(ok)
            }
        }
    }

    private fun viaRouter(ctx: Context, o: Output): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return try {
            val mr = MediaRouter2.getInstance(ctx)
            val cb = object : MediaRouter2.RouteCallback() {}
            mr.registerRouteCallback(Aura.io, cb, RouteDiscoveryPreference.Builder(emptyList(), true).build())
            Thread.sleep(250)
            val routes = mr.routes.filter { it.isSystemRoute }
            val target = routes.firstOrNull { r ->
                when (o.kind) {
                    Kind.TV -> r.type == MediaRoute2Info.TYPE_HDMI || r.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER
                    else -> (r.type == MediaRoute2Info.TYPE_BLUETOOTH_A2DP || r.type == MediaRoute2Info.TYPE_BLE_HEADSET) && r.name.toString() == o.name
                }
            }
            if (target != null) mr.transferTo(target)
            Thread.sleep(600)
            mr.unregisterRouteCallback(cb)
            target != null && current(ctx).id == o.id
        } catch (_: Throwable) { false }
    }

    fun openSystemPicker(ctx: Context) {
        try {
            ctx.startActivity(Intent("com.android.systemui.TV_MEDIA_OUTPUT_DIALOG")
                .setComponent(ComponentName("com.android.systemui", "com.android.systemui.media.dialog.tv.TvMediaOutputDialogActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            try { ctx.startActivity(Intent("android.settings.SOUND_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
        }
    }
}

/**
 * Galaxy Buds report left/right/case battery over Samsung's SPP channel. This reads one status
 * frame and disconnects (best effort; the protocol is undocumented).
 */
object GalaxyBuds {
    private val UUID_NEW = UUID.fromString("2e73a4ad-332d-41fc-90e2-16bef06523f2")
    private val UUID_LEGACY = UUID.fromString("00001102-0000-1000-8000-00805f9b34fd")
    class Battery(val left: Int, val right: Int, val case: Int)

    fun isGalaxy(d: BluetoothDevice): Boolean {
        val name = (Bt.displayName(d) + " " + (d.name ?: "")).lowercase()
        val uuids = try { d.uuids?.map { it.uuid } ?: emptyList() } catch (_: Throwable) { emptyList() }
        return UUID_NEW in uuids || UUID_LEGACY in uuids || (name.contains("buds") && !name.contains("redmi") && !name.contains("xiaomi") && !name.contains("nothing"))
    }

    @SuppressLint("MissingPermission")
    fun query(d: BluetoothDevice, cb: (Battery?) -> Unit) {
        Thread({
            var result: Battery? = null
            val legacy = (d.name ?: "").matches(Regex("(?i).*galaxy buds \\(.*"))
            val sock = try { d.createRfcommSocketToServiceRecord(if (legacy) UUID_LEGACY else UUID_NEW) } catch (_: Throwable) { null }
            if (sock != null) {
                val watchdog = Thread { try { Thread.sleep(6000); sock.close() } catch (_: Throwable) { } }.apply { isDaemon = true; start() }
                try {
                    Bt.adapter?.cancelDiscovery()
                    sock.connect()
                    result = readStatus(sock.inputStream, if (legacy) 0xFE else 0xFD, if (legacy) 0xEE else 0xDD)
                } catch (_: Throwable) { }
                try { sock.close() } catch (_: Throwable) { }
                watchdog.interrupt()
            }
            Aura.ui { cb(result) }
        }, "aura-buds").start()
    }

    private fun readStatus(ins: InputStream, som: Int, eom: Int): Battery? {
        val buf = ByteArray(4096); var len = 0
        val deadline = System.currentTimeMillis() + 4500
        while (System.currentTimeMillis() < deadline && len < buf.size) {
            val n = ins.read(buf, len, buf.size - len)
            if (n <= 0) break
            len += n
            var i = 0
            while (i + 4 < len) {
                if ((buf[i].toInt() and 0xff) == som) {
                    val size = ((buf[i + 1].toInt() and 0xff) or ((buf[i + 2].toInt() and 0xff) shl 8)) and 0x3FF
                    val end = i + 3 + size
                    if (size >= 3 && end < len && (buf[end].toInt() and 0xff) == eom) {
                        val id = buf[i + 3].toInt() and 0xff
                        val p = buf.copyOfRange(i + 4, i + 3 + size - 2).map { it.toInt() and 0xff }
                        val b = when {
                            id == 0x61 && p.size > 7 -> Battery(p[2], p[3], p[7])
                            id == 0x60 && p.size > 6 -> Battery(p[1], p[2], p[6])
                            else -> null
                        }
                        if (b != null && b.left in 0..100 && b.right in 0..100) return b
                        i = end + 1; continue
                    }
                }
                i++
            }
        }
        return null
    }
}
