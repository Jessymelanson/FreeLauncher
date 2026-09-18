package com.freelauncher.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings

/**
 * What the dock holds before the user has arranged anything.
 *
 * A launcher that installs to a blank screen is technically correct and
 * useless: the person has just replaced their home screen and the first thing
 * they see is nothing at all, with no indication that it works. Five sensible
 * icons is the difference between "set up and use off the bat" and "work out
 * how to put something on it first".
 *
 * The apps are found by asking the system what handles each job, not by
 * guessing at package names. Hard-coding `com.android.dialer` finds nothing on
 * a Samsung and nothing on a Pixel either; resolving ACTION_DIAL finds whatever
 * that particular phone actually uses, including a replacement the user chose
 * themselves.
 */
object FirstRun {

    /**
     * In dock order, left to right. Phone and messages at the outside edges
     * because those are the two most likely to be tapped one-handed.
     */
    private fun probes(): List<Intent> = listOf(
        Intent(Intent.ACTION_DIAL),
        Intent(Intent.ACTION_VIEW, Uri.parse("sms:")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        Intent(Settings.ACTION_SETTINGS),
    )

    /**
     * Fill the dock, once.
     *
     * Does nothing if anything is already placed. The check is belt and braces
     * on top of [LayoutStore.firstRun]: the layout loads asynchronously, and a
     * seeder that raced it could otherwise append a second copy of everything
     * to a restored backup.
     */
    fun seedDock(
        context: Context,
        layout: LayoutStore,
        apps: AppRepository,
        settings: LauncherSettings,
    ) {
        if (layout.items.value.isNotEmpty()) {
            // Something is already placed, so there is nothing to seed. Marked
            // anyway: leaving the flag set would have the caller's effect
            // re-run this on every app list change for the life of the process.
            layout.markSeeded()
            return
        }

        // Nothing to seed *with* yet. Deliberately not marked, so this runs
        // again when the app list arrives -- which is the one case where
        // retrying is the right answer.
        val installed = apps.apps.value
        if (installed.isEmpty()) return

        val pm = context.packageManager
        val chosen = LinkedHashSet<AppEntry>()

        for (probe in probes()) {
            if (chosen.size >= settings.dockCols) break
            val packageName = runCatching {
                pm.resolveActivity(probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName
            }.getOrNull() ?: continue

            // The resolver can answer with the system's disambiguation dialog
            // rather than a real app when several handle the intent and none is
            // the default. That is not something to put in a dock.
            if (packageName == "android" || packageName.isEmpty()) continue

            val entry = installed.firstOrNull { it.packageName == packageName } ?: continue
            chosen += entry
        }

        // Anything the probes could not fill, taken alphabetically from what is
        // installed. A dock with a gap in the middle looks broken; a dock with a
        // slightly arbitrary fifth icon does not, and the user will change it.
        if (chosen.size < settings.dockCols) {
            for (entry in installed) {
                if (chosen.size >= settings.dockCols) break
                if (entry.packageName == context.packageName) continue
                chosen += entry
            }
        }

        val items = chosen.mapIndexed { index, entry ->
            LauncherItem(
                id = layout.nextId(),
                type = ItemType.APP,
                title = entry.label,
                component = entry.component.flattenToString(),
                packageName = entry.packageName,
                userSerial = entry.userSerial,
                container = Container.DOCK,
                screen = 0,
                cellX = index,
                cellY = 0,
            )
        }
        if (items.isNotEmpty()) layout.addAll(items)
        layout.markSeeded()
    }
}
