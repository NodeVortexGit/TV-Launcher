package com.nodevortex.aura.system

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.nodevortex.aura.Aura
import java.util.concurrent.CopyOnWriteArrayList

/** What is playing right now (Spotify, YouTube, …), from the system media sessions. */
object MediaWatcher {
    class Now(
        val pkg: String, val title: String, val artist: String, val art: Bitmap?,
        val playing: Boolean, val duration: Long, val position: Long, val positionAt: Long,
        val controller: MediaController, val uri: String? = null,
    ) {
        fun positionNow(): Long =
            if (!playing) position else position + (SystemClock.elapsedRealtime() - positionAt)
    }

    @Volatile var now: Now? = null
        private set
    private val listeners = CopyOnWriteArrayList<(Now?) -> Unit>()
    private var msm: MediaSessionManager? = null
    private var comp: ComponentName? = null
    private val tracked = HashMap<MediaController, MediaController.Callback>()
    private var lastPlayingAt = 0L

    fun addListener(l: (Now?) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Now?) -> Unit) { listeners.remove(l) }

    /** Requires notification-listener access (granted by the setup script). */
    fun start(ctx: Context) {
        if (msm != null) return
        val m = ctx.getSystemService(MediaSessionManager::class.java)
        val c = ComponentName(ctx, NotificationService::class.java)
        try {
            m.addOnActiveSessionsChangedListener({ list -> track(list ?: emptyList()) }, c, Aura.main)
            msm = m; comp = c
            track(m.getActiveSessions(c))
        } catch (_: SecurityException) { }
    }

    private fun track(list: List<MediaController>) {
        tracked.keys.filter { k -> list.none { it.sessionToken == k.sessionToken } }.forEach { k -> k.unregisterCallback(tracked.remove(k)!!) }
        for (mc in list) if (tracked.keys.none { it.sessionToken == mc.sessionToken }) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = recompute()
                override fun onMetadataChanged(metadata: MediaMetadata?) = recompute()
                override fun onSessionDestroyed() = recompute()
            }
            mc.registerCallback(cb, Aura.main)
            tracked[mc] = cb
        }
        recompute()
    }

    private fun recompute() {
        val sessions = tracked.keys.toList()
        val playing = sessions.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val pick = playing.firstOrNull { it.packageName == SPOTIFY } ?: playing.firstOrNull()
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED && it.metadata != null }
        val n = pick?.let { mc ->
            val md = mc.metadata
            val st = mc.playbackState
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
            if (title.isEmpty()) return@let null
            Now(
                mc.packageName, title,
                md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "",
                md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                st?.state == PlaybackState.STATE_PLAYING,
                md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                st?.position ?: 0L, st?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(), mc,
                md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { SpotifyLink.isTrack(it) },
            )
        }
        if (n?.playing == true) {
            lastPlayingAt = System.currentTimeMillis()
            com.nodevortex.aura.data.Signals.notePlay(n.title, n.artist, n.pkg, n.art, n.uri)
        }
        now = n
        listeners.forEach { it(n) }
    }

    fun isMusicPlaying(): Boolean {
        val n = now ?: return false
        return n.playing && n.pkg in MUSIC_APPS
    }

    fun playPause() { now?.let { if (it.playing) it.controller.transportControls.pause() else it.controller.transportControls.play() } }
    fun next() { now?.controller?.transportControls?.skipToNext() }

    fun previous() { now?.controller?.transportControls?.skipToPrevious() }

    const val SPOTIFY = "com.spotify.tv.android"
    val MUSIC_APPS = setOf(SPOTIFY, "com.google.android.apps.youtube.music", "com.google.android.youtube.tvmusic", "deezer.android.tv", "com.aspiro.tidal")
}

/** Notifications for the pop-ups and the quick-settings list. */
object NotificationHub {
    class Item(
        val key: String, val pkg: String, val app: String, val title: String, val text: String,
        val icon: Bitmap?, val time: Long, val intent: PendingIntent?, val clearable: Boolean,
    )

    val items = CopyOnWriteArrayList<Item>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    var popup: ((Item) -> Unit)? = null
    private val lastPosted = HashMap<String, String>()

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    internal fun reset(svc: NotificationService, list: Array<StatusBarNotification>?) {
        items.clear()
        list?.sortedByDescending { it.postTime }?.forEach { sbn -> convert(svc, sbn)?.let(items::add) }
        notifyChanged()
    }

    internal fun posted(svc: NotificationService, sbn: StatusBarNotification, ranking: NotificationListenerService.RankingMap?) {
        val item = convert(svc, sbn) ?: return
        items.removeAll { it.key == item.key }
        items.add(0, item)
        notifyChanged()
        val sig = item.title + "|" + item.text
        val important = ranking?.let { rm ->
            val r = NotificationListenerService.Ranking()
            !rm.getRanking(sbn.key, r) || r.importance >= NotificationManager.IMPORTANCE_DEFAULT
        } ?: true
        val prev = lastPosted[item.key]
        lastPosted[item.key] = sig
        val alertOnce = sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        if (important && prev != sig && !(alertOnce && prev != null)) popup?.invoke(item)
    }

    internal fun removed(sbn: StatusBarNotification) {
        if (items.removeAll { it.key == sbn.key }) notifyChanged()
    }

    fun dismiss(item: Item) {
        NotificationService.instance?.let { try { it.cancelNotification(item.key) } catch (_: Throwable) { } }
        items.removeAll { it.key == item.key }; notifyChanged()
    }

    fun open(item: Item) {
        try { item.intent?.send() } catch (_: Throwable) { }
    }

    private fun notifyChanged() = Aura.ui { listeners.forEach { it() } }

    private fun convert(ctx: Context, sbn: StatusBarNotification): Item? {
        if (sbn.packageName == ctx.packageName) return null
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.category == Notification.CATEGORY_TRANSPORT || n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null
        if (sbn.isOngoing && n.category != Notification.CATEGORY_CALL) return null
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val pm = ctx.packageManager
        val app = try { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Throwable) { sbn.packageName }
        val icon = iconBitmap(ctx, n.getLargeIcon()) ?: try { drawableBitmap(pm.getApplicationIcon(sbn.packageName)) } catch (_: Throwable) { null }
        return Item(sbn.key, sbn.packageName, app, title, text, icon, sbn.postTime, n.contentIntent, sbn.isClearable)
    }

    private fun iconBitmap(ctx: Context, ic: Icon?): Bitmap? = try { ic?.loadDrawable(ctx)?.let(::drawableBitmap) } catch (_: Throwable) { null }

    private fun drawableBitmap(d: android.graphics.drawable.Drawable): Bitmap {
        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 96, 96); d.draw(Canvas(b)); return b
    }
}

class NotificationService : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        MediaWatcher.start(this)
        NotificationHub.reset(this, try { activeNotifications } catch (_: Throwable) { null })
    }
    override fun onListenerDisconnected() { instance = null }
    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) = NotificationHub.posted(this, sbn, rankingMap)
    override fun onNotificationRemoved(sbn: StatusBarNotification) = NotificationHub.removed(sbn)
    companion object { @Volatile var instance: NotificationService? = null }
}
