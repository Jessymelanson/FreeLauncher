package com.freelauncher.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What was opened from this launcher, most recent first.
 *
 * The launcher's own record, not the system's. Android will tell an app which
 * apps the user has been in, through UsageStatsManager, but only with the
 * PACKAGE_USAGE_STATS special access - and that grants sight of every app on the
 * phone, all the time, however it was opened. Asking for it to populate one
 * panel would trade the thing this launcher is for a list of shortcuts, and it
 * would make the permission table in the README untrue.
 *
 * Launching an app is something the launcher does itself, so it can simply
 * remember. The record is narrower than the system's by definition: it knows
 * only what was started from here, and nothing about what happened afterwards.
 *
 * Private space launches are deliberately NOT recorded. An app that is behind a
 * lock should not be named on the home screen a moment later.
 */
class RecentApps(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _keys = MutableStateFlow(read())

    /** [AppEntry.key] values, most recently opened first. */
    val keys: StateFlow<List<String>> = _keys.asStateFlow()

    private fun read(): List<String> =
        prefs.getString(KEY, "").orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }

    /** Records a launch, moving the app to the front. */
    fun record(appKey: String) {
        if (appKey.isBlank()) return
        val next = ArrayList<String>(LIMIT)
        next.add(appKey)
        for (k in _keys.value) {
            if (k != appKey) next.add(k)
            if (next.size >= LIMIT) break
        }
        _keys.value = next
        prefs.edit().putString(KEY, next.joinToString("\n")).apply()
    }

    /** Drops anything that no longer resolves, so an uninstall does not linger. */
    fun prune(known: Set<String>) {
        val kept = _keys.value.filter { it in known }
        if (kept.size == _keys.value.size) return
        _keys.value = kept
        prefs.edit().putString(KEY, kept.joinToString("\n")).apply()
    }

    fun clear() {
        _keys.value = emptyList()
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val FILE = "recent_apps"
        const val KEY = "keys"

        /**
         * Enough for any panel that shows recents, and short enough that the
         * whole thing stays a single small string.
         */
        const val LIMIT = 30
    }
}
