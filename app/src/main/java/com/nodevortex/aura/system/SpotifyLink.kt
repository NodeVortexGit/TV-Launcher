package com.nodevortex.aura.system

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Starts a song in Spotify's TV app. Its media session ignores playFromSearch / playFromMediaId /
 * playFromUri from other apps (only play/pause are honoured), but these two paths do play:
 *  - a known track: opening `spotify:track:<id>` starts it immediately (ids are remembered from
 *    Spotify's now-playing metadata, see Signals.notePlay);
 *  - anything else: Google TV's assistant, given the typed request "play <query> on Spotify".
 */
object SpotifyLink {
    fun playTrack(ctx: Context, uri: String): Boolean = try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(MediaWatcher.SPOTIFY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Throwable) { false }

    fun playQuery(ctx: Context, query: String): Boolean = try {
        ctx.startActivity(Intent("android.search.action.GLOBAL_SEARCH")
            .putExtra(SearchManager.QUERY, "play $query on Spotify").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Throwable) { false }

    fun isTrack(uri: String?) = uri != null && uri.startsWith("spotify:track:")
}
