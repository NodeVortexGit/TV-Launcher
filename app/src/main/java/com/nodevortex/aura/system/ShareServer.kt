package com.nodevortex.aura.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import com.nodevortex.aura.Aura
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * "Send to TV" page on http://<tv-ip>:8765 – open any YouTube/Spotify/web link on the TV, switch
 * COTV channels and control playback from any phone browser on the home network.
 */
object ShareServer {
    const val PORT = 8765
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2)
    private lateinit var app: Context

    fun start(ctx: Context) {
        if (server != null || !Aura.prefs.getBoolean("shareServer", true)) return
        app = ctx.applicationContext
        Thread({
            try {
                val s = ServerSocket(PORT)
                server = s
                while (!s.isClosed) {
                    val c = s.accept()
                    pool.execute { handle(c) }
                }
            } catch (_: Throwable) { server = null }
        }, "aura-share").start()
    }

    fun stop() { try { server?.close() } catch (_: Throwable) { }; server = null }

    fun address(ctx: Context): String? {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) if (a is Inet4Address && a.isSiteLocalAddress) return "http://${a.hostAddress}:$PORT"
            }
        } catch (_: Throwable) { }
        @Suppress("DEPRECATION")
        val ip = ctx.getSystemService(WifiManager::class.java)?.connectionInfo?.ipAddress ?: 0
        return if (ip == 0) null else "http://${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}:$PORT"
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            val a = s.inetAddress
            if (!(a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress)) return
            s.soTimeout = 15000
            val ins = java.io.BufferedInputStream(s.getInputStream(), 64 * 1024)
            val head = readHead(ins) ?: return
            val lines = head.split("\r\n")
            val (method, path) = lines[0].split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
            val len = lines.firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
            val route = path.substringBefore('?')
            if (method == "POST" && route == "/api/update") {
                val out = try { receiveUpdate(ins, len) } catch (t: Throwable) { "error: ${t.message}" }
                respond(s, "text/plain; charset=utf-8", out); return
            }
            val body = if (len in 1..65536) String(readN(ins, len.toInt()), Charsets.UTF_8) else ""
            val form = parse(body) + parse(path.substringAfter('?', ""))
            val (type, out) = when {
                method == "GET" && route == "/" -> "text/html; charset=utf-8" to PAGE
                route == "/api/channels" -> "application/json" to channels()
                route == "/api/now" -> "application/json" to nowPlaying()
                route == "/api/log" -> "text/plain; charset=utf-8" to ownLog(form["n"]?.toIntOrNull() ?: 400)
                route == "/api/state" -> "application/json" to state()
                method == "POST" && route == "/api/open" -> "application/json" to ok(open(form["url"] ?: ""))
                method == "POST" && route == "/api/tune" -> "application/json" to ok(tune(form["n"]?.toIntOrNull()))
                method == "POST" && route.startsWith("/api/media/") -> "application/json" to ok(media(route.substringAfterLast('/')))
                method == "POST" && route == "/api/key" -> "application/json" to ok(key(form["k"] ?: ""))
                method == "POST" && route == "/api/debug" -> "application/json" to ok(debug(form["what"] ?: ""))
                else -> "text/plain" to "not found"
            }
            respond(s, type, out)
        }
    }

    private fun respond(s: Socket, type: String, out: String) {
        val bytes = out.toByteArray(Charsets.UTF_8)
        val os = s.getOutputStream()
        os.write(("HTTP/1.1 ${if (out == "not found") "404 Not Found" else "200 OK"}\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        os.write(bytes); os.flush()
    }

    private fun readHead(ins: java.io.InputStream): String? {
        val buf = java.io.ByteArrayOutputStream()
        var m = 0
        while (buf.size() < 16384) {
            val b = ins.read(); if (b < 0) return null
            buf.write(b)
            m = when { b == '\r'.code && (m == 0 || m == 2) -> m + 1; b == '\n'.code && (m == 1 || m == 3) -> m + 1; else -> 0 }
            if (m == 4) return String(buf.toByteArray(), Charsets.UTF_8).trimEnd()
        }
        return null
    }

    private fun readN(ins: java.io.InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var o = 0
        while (o < n) { val r = ins.read(b, o, n - o); if (r < 0) break; o += r }
        return b
    }

    /**
     * Over-the-air update of Aura itself (from the dev machine on the same Wi-Fi). Only an APK with
     * Aura's package name AND Aura's signing certificate is accepted; Android still asks on the TV.
     */
    private fun receiveUpdate(ins: java.io.InputStream, len: Long): String {
        if (len <= 0 || len > 80L * 1024 * 1024) return "bad length"
        val f = java.io.File(app.cacheDir, "update.apk")
        f.outputStream().use { out ->
            val buf = ByteArray(64 * 1024); var left = len
            while (left > 0) { val r = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) break; out.write(buf, 0, r); left -= r }
            if (left > 0) return "truncated"
        }
        val pm = app.packageManager
        val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        val info = pm.getPackageArchiveInfo(f.path, flags) ?: return "not an apk"
        if (info.packageName != app.packageName) return "wrong package ${info.packageName}"
        val mine = pm.getPackageInfo(app.packageName, flags).signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val theirs = info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        if (mine.isNullOrEmpty() || mine != theirs) return "signature mismatch"
        val installer = pm.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("aura.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val flagsPi = android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
            val pi = android.app.PendingIntent.getBroadcast(app, id, Intent(app, UpdateReceiver::class.java), flagsPi)
            session.commit(pi.intentSender)
        }
        return "ok: v${info.versionName} (${f.length() / 1024} KB) — confirm on the TV"
    }

    /** Aura's own log (an app may always read its own process log). */
    private fun ownLog(n: Int): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", n.coerceIn(10, 3000).toString(), "--pid=${android.os.Process.myPid()}"))
        p.inputStream.bufferedReader().readText()
    } catch (t: Throwable) { "log unavailable: ${t.message}" }

    private fun state(): String {
        val pr = Aura.prefs
        val l = com.nodevortex.aura.lock.Lock
        return JSONObject()
            .put("version", try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (_: Throwable) { "?" })
            .put("helper", AuraService.instance != null)
            .put("lockEnabled", l.enabled).put("pinSet", l.pinSet).put("owner", l.owner).put("current", l.current).put("active", l.active)
            .put("lockRequired", l.required()).put("lockShowing", l.showing)
            .put("profiles", JSONArray(l.profiles))
            .put("bridge", Aura.app.bridge.state.name)
            .put("stockDisabled", StockLauncher.disabled(app)).put("auraIsDefault", StockLauncher.auraIsDefault(app))
            .put("epgDays", Aura.app.epg.daysStored)
            .put("musicSaver", pr.getBoolean("musicSaver", true))
            .toString(2)
    }

    /** Small debug hooks: show the lock screen, the PIN pad or the music visualiser on the TV. */
    private fun debug(what: String): Boolean {
        val i = Intent(app, com.nodevortex.aura.HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        when (what) {
            "lock" -> Aura.ui { com.nodevortex.aura.lock.Lock.show(app) }
            "saver" -> app.startActivity(Intent(app, MusicSaverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> app.startActivity(i.putExtra(com.nodevortex.aura.HomeActivity.EXTRA_OPEN, what))
        }
        return true
    }

    private fun parse(s: String): Map<String, String> = s.split('&').filter { '=' in it }
        .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun ok(b: Boolean) = JSONObject().put("ok", b).toString()

    private fun open(raw: String): Boolean {
        val url = Regex("https?://\\S+|spotify:\\S+").find(raw)?.value ?: return false
        val u = Uri.parse(url)
        val host = u.host ?: ""
        val pkg = when {
            host.contains("youtu") -> "com.google.android.youtube.tv"
            host.contains("spotify") || url.startsWith("spotify:") -> "com.spotify.tv.android"
            host.contains("twitch") -> "tv.twitch.android.app"
            else -> null
        }
        return try {
            app.startActivity(Intent(Intent.ACTION_VIEW, u).apply { if (pkg != null) setPackage(pkg) }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (_: Throwable) {
            try { app.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Throwable) { false }
        }
    }

    private fun tune(n: Int?): Boolean {
        val ch = n?.let { Aura.app.channels.byNumber(it) } ?: return false
        Aura.ui { Aura.app.channels.tune(app, ch) }
        return true
    }

    private fun media(cmd: String): Boolean {
        Aura.ui { when (cmd) { "toggle" -> MediaWatcher.playPause(); "next" -> MediaWatcher.next(); "prev" -> MediaWatcher.previous() } }
        return MediaWatcher.now != null
    }

    private val KEYS = mapOf("up" to "DPAD_UP", "down" to "DPAD_DOWN", "left" to "DPAD_LEFT", "right" to "DPAD_RIGHT",
        "ok" to "DPAD_CENTER", "back" to "BACK", "home" to "HOME", "playpause" to "MEDIA_PLAY_PAUSE", "volup" to "VOLUME_UP", "voldown" to "VOLUME_DOWN")

    private fun key(k: String): Boolean {
        val code = KEYS[k] ?: return false
        val b = Aura.app.bridge
        if (!b.ready) return false
        b.shell("input keyevent KEYCODE_$code"); return true
    }

    private fun channels(): String {
        val epg = Aura.app.epg
        val now = try { epg.nowAll() } catch (_: Throwable) { emptyMap() }
        val arr = JSONArray()
        Aura.app.channels.list.forEach { c -> arr.put(JSONObject().put("n", c.n).put("name", c.name).put("now", c.epg?.let { now[it]?.name } ?: "")) }
        return arr.toString()
    }

    private fun nowPlaying(): String {
        val n = MediaWatcher.now ?: return "{}"
        return JSONObject().put("title", n.title).put("artist", n.artist).put("playing", n.playing).put("app", n.pkg).toString()
    }

    private const val PAGE = """<!doctype html><html lang="bg"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Aura · Към телевизора</title><style>
:root{--bg:#0b0d12;--card:#171a21;--line:#262a33;--text:#f2f4f8;--dim:#9aa3b2;--acc:#8ab4f8}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px system-ui,-apple-system,Segoe UI,Roboto,sans-serif;padding:16px;max-width:720px;margin:auto}
h1{font-size:22px;font-weight:600;margin:8px 0 16px}h2{font-size:14px;color:var(--dim);font-weight:600;margin:22px 0 10px;text-transform:uppercase;letter-spacing:.06em}
.card{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:14px}
input{width:100%;background:#0f1218;border:1px solid var(--line);color:var(--text);border-radius:12px;padding:12px;font-size:16px}
button{background:var(--text);color:#111;border:0;border-radius:999px;padding:11px 18px;font-size:15px;font-weight:600;cursor:pointer}
button.ghost{background:#232733;color:var(--text)}.row{display:flex;gap:10px;align-items:center}.row>*{flex:none}.grow{flex:1!important}
#ch{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:8px;max-height:420px;overflow:auto}
.c{background:#11141a;border:1px solid var(--line);border-radius:12px;padding:10px;cursor:pointer}.c b{color:var(--acc);margin-right:6px}.c small{display:block;color:var(--dim);margin-top:4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.pad{display:grid;grid-template-columns:repeat(3,64px);gap:8px;justify-content:center}.pad button{height:56px;padding:0;border-radius:16px}
#msg{color:var(--dim);font-size:14px;min-height:20px;margin-top:8px}
</style></head><body><h1>Към телевизора</h1>
<div class="card"><div class="row"><input id="url" class="grow" placeholder="Поставете връзка от YouTube, Spotify или Twitch"><button onclick="openUrl()">Пусни</button></div><div id="msg"></div></div>
<h2>Сега върви</h2><div class="card row"><div class="grow" id="np">Нищо не върви</div><button class="ghost" onclick="m('prev')">⏮</button><button onclick="m('toggle')">⏯</button><button class="ghost" onclick="m('next')">⏭</button></div>
<h2>Дистанционно</h2><div class="card"><div class="pad"><span></span><button class="ghost" onclick="k('up')">▲</button><span></span><button class="ghost" onclick="k('left')">◀</button><button onclick="k('ok')">OK</button><button class="ghost" onclick="k('right')">▶</button><button class="ghost" onclick="k('back')">↩</button><button class="ghost" onclick="k('down')">▼</button><button class="ghost" onclick="k('home')">⌂</button></div></div>
<h2>Канали на COTV</h2><div class="card"><input id="f" placeholder="Търсене на канал" oninput="draw()" style="margin-bottom:10px"><div id="ch"></div></div>
<script>
let C=[];const post=(u,b)=>fetch(u,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(b||{})}).then(r=>r.json());
function msg(t){document.getElementById('msg').textContent=t}
function openUrl(){post('/api/open',{url:document.getElementById('url').value}).then(r=>msg(r.ok?'Отваря се на телевизора…':'Тази връзка не може да се отвори'))}
function m(c){post('/api/media/'+c).then(np)}function k(c){post('/api/key',{k:c}).then(r=>{if(!r.ok)msg('Дистанционното изисква разширените функции на Aura')})}
function tune(n){post('/api/tune',{n}).then(()=>msg('Пускам канал '+n+'…'))}
function draw(){const q=document.getElementById('f').value.toLowerCase();document.getElementById('ch').innerHTML=C.filter(c=>!q||(c.n+' '+c.name+' '+c.now).toLowerCase().includes(q)).map(c=>'<div class="c" onclick="tune('+c.n+')"><b>'+c.n+'</b>'+c.name+'<small>'+(c.now||'')+'</small></div>').join('')}
function np(){fetch('/api/now').then(r=>r.json()).then(j=>{document.getElementById('np').textContent=j.title?(j.title+' · '+j.artist):'Нищо не върви'})}
fetch('/api/channels').then(r=>r.json()).then(j=>{C=j;draw()});np();setInterval(np,5000);
const p=new URLSearchParams(location.search);if(p.get('url')){document.getElementById('url').value=p.get('url');openUrl()}
</script></body></html>"""
}
