package com.nodevortex.aura.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** One entry on the home grid: an app or a folder of apps. */
sealed class HomeItem {
    abstract val id: String
    data class App(val key: String) : HomeItem() { override val id get() = key }
    class Folder(override val id: String, var name: String, val apps: MutableList<String>) : HomeItem()
}

/** Persistent grid order, folders and hidden apps. */
class HomeLayout(private val prefs: SharedPreferences) {

    val items = mutableListOf<HomeItem>()
    val hidden = linkedSetOf<String>()
    private var initialised = false

    init { load() }

    private fun load() {
        val raw = prefs.getString("layout", null) ?: return
        try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("items")
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                when (it.getString("t")) {
                    "a" -> items += HomeItem.App(it.getString("k"))
                    "f" -> {
                        val apps = it.getJSONArray("apps")
                        items += HomeItem.Folder(it.getString("id"), it.getString("name"),
                            MutableList(apps.length()) { j -> apps.getString(j) })
                    }
                }
            }
            val h = o.optJSONArray("hidden") ?: JSONArray()
            for (i in 0 until h.length()) hidden += h.getString(i)
            initialised = true
        } catch (_: Throwable) { items.clear(); hidden.clear() }
    }

    fun save() {
        val arr = JSONArray()
        for (item in items) when (item) {
            is HomeItem.App -> arr.put(JSONObject().put("t", "a").put("k", item.key))
            is HomeItem.Folder -> arr.put(JSONObject().put("t", "f").put("id", item.id).put("name", item.name)
                .put("apps", JSONArray(item.apps)))
        }
        prefs.edit().putString("layout", JSONObject().put("items", arr).put("hidden", JSONArray(hidden.toList())).toString()).apply()
    }

    /** Reconcile with the installed apps: append new ones, drop uninstalled ones. */
    fun sync(apps: List<AppEntry>) {
        val installed = apps.associateBy { it.key }
        val installedPkgs = apps.associateBy { it.pkg }
        // migrate keys whose activity class changed but package is still installed
        fun fix(k: String): String? = if (installed.containsKey(k)) k else installedPkgs[k.substringBefore('/')]?.key
        val placed = HashSet<String>()
        val it = items.listIterator()
        while (it.hasNext()) {
            when (val item = it.next()) {
                is HomeItem.App -> {
                    val k = fix(item.key)
                    if (k == null || !placed.add(k)) it.remove() else if (k != item.key) it.set(HomeItem.App(k))
                }
                is HomeItem.Folder -> {
                    val fixed = item.apps.mapNotNull { fix(it) }.filter { placed.add(it) }
                    item.apps.clear(); item.apps.addAll(fixed)
                    if (item.apps.isEmpty()) it.remove()
                }
            }
        }
        hidden.retainAll { fix(it) != null }
        val fresh = apps.filter { it.key !in placed }
        val ordered = if (!initialised) fresh.sortedWith(compareBy<AppEntry>({ priority(it.pkg) }, { it.label.lowercase() })) else fresh
        ordered.forEach { items += HomeItem.App(it.key) }
        initialised = true
        save()
    }

    private fun priority(pkg: String) = FIRST.indexOf(pkg).let { if (it < 0) 100 else it }

    fun folder(id: String) = items.firstOrNull { it is HomeItem.Folder && it.id == id } as? HomeItem.Folder

    fun folderOf(key: String) = items.firstOrNull { it is HomeItem.Folder && key in it.apps } as? HomeItem.Folder

    fun move(from: Int, to: Int) {
        if (from == to || from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from)); save()
    }

    fun newFolder(name: String, key: String): HomeItem.Folder {
        val idx = items.indexOfFirst { it is HomeItem.App && it.key == key }
        val f = HomeItem.Folder("f" + System.currentTimeMillis().toString(36), name, mutableListOf(key))
        if (idx >= 0) items[idx] = f else items += f
        save(); return f
    }

    fun addToFolder(f: HomeItem.Folder, key: String) {
        folderOf(key)?.let { removeFromFolder(it, key, reinsert = false) }
        items.removeAll { it is HomeItem.App && it.key == key }
        if (key !in f.apps) f.apps += key
        save()
    }

    fun removeFromFolder(f: HomeItem.Folder, key: String, reinsert: Boolean = true) {
        f.apps.remove(key)
        val idx = items.indexOf(f)
        if (reinsert) items.add((idx + 1).coerceAtMost(items.size), HomeItem.App(key))
        if (f.apps.isEmpty()) items.remove(f)
        save()
    }

    fun ungroup(f: HomeItem.Folder) {
        val idx = items.indexOf(f)
        if (idx < 0) return
        items.removeAt(idx)
        items.addAll(idx, f.apps.map { HomeItem.App(it) })
        save()
    }

    fun hide(key: String) {
        folderOf(key)?.let { removeFromFolder(it, key, reinsert = false) }
        items.removeAll { it is HomeItem.App && it.key == key }
        hidden += key; save()
    }

    fun unhide(key: String) {
        if (hidden.remove(key)) { items += HomeItem.App(key); save() }
    }

    companion object {
        val FIRST = listOf(
            "com.google.android.youtube.tv", "com.spotify.tv.android", "bg.comnet.cotv", "tv.twitch.android.app",
            "com.netflix.ninja", "com.android.vending",
        )
    }
}
