package com.freelauncher.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.Log
import com.freelauncher.app.data.Container
import com.freelauncher.app.data.IconCache
import com.freelauncher.app.data.ItemType
import com.freelauncher.app.data.LauncherItem

/**
 * The old way of adding a shortcut, for apps that still use it.
 *
 * `com.android.launcher.action.INSTALL_SHORTCUT` predates Android 8 and was
 * superseded by `ShortcutManager.requestPinShortcut` -- which is what
 * [PinItemActivity] handles and what anything current uses. The broadcast was
 * deprecated, not removed, and plenty of apps that have not been touched in
 * years still send it. Without a receiver they fail silently, which is
 * indistinguishable from the launcher being broken.
 *
 * Deliberately permissive about what it accepts and strict about what it
 * stores: any app holding the INSTALL_SHORTCUT permission can send this, so
 * the intent inside is never parsed or inspected here, only kept verbatim to be
 * handed back to the system if the user taps it.
 */
class InstallShortcutReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        @Suppress("DEPRECATION")
        val target = intent.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT) ?: run {
            Log.w(TAG, "shortcut broadcast with no intent in it")
            return
        }

        @Suppress("DEPRECATION")
        val label = intent.getStringExtra(Intent.EXTRA_SHORTCUT_NAME).orEmpty()

        // The same broadcast can arrive twice.
        //
        // This receiver is declared in the manifest *and* registered at
        // runtime, because neither alone covers both kinds of sender -- see
        // FreeLauncherApp. A sender that names this package explicitly is
        // delivered to both, and the user would get two identical icons.
        //
        // Keyed on what the shortcut actually is rather than on a counter, and
        // held only for a moment: adding the same shortcut twice on purpose is
        // something a person might do, just not within a second of themselves.
        val fingerprint = "$label|${target.toUri(Intent.URI_INTENT_SCHEME)}"
        val now = System.currentTimeMillis()
        synchronized(recent) {
            val seen = recent[fingerprint]
            if (seen != null && now - seen < DUPLICATE_WINDOW_MS) {
                Log.i(TAG, "ignoring a repeat of the same shortcut")
                return
            }
            recent[fingerprint] = now
            recent.entries.removeAll { now - it.value > DUPLICATE_WINDOW_MS }
        }

        val app = context.launcher
        val settings = app.settings.value

        // Who sent this, so the icon can be badged with it.
        //
        // The modern pin request names its publisher outright. This broadcast
        // does not, and before API 34 there was no way to ask -- so on older
        // releases the best available answer is whatever the shortcut's own
        // intent points at, which is right for an app shortcut and wrong for a
        // web one. A wrong badge is worse than none, so the fallback is only
        // taken when the intent names a package explicitly rather than being
        // guessed at from a component.
        val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { sentFromPackage }.getOrNull() ?: target.`package`
        } else {
            target.`package`
        }

        // Only the icon the sender supplied. There is no package to fall back
        // on: a legacy shortcut names an intent, not an app.
        val iconKey = extractIcon(context, intent)?.let { png ->
            app.icons.writeCustomIcon("legacy_${System.currentTimeMillis()}.png", png)
        }

        val (screen, x, y) = app.layout.findSlot(
            preferredScreen = settings.defaultPage,
            cols = settings.desktopCols,
            rows = settings.desktopRows,
            screens = app.layout.screenCount.value,
        )

        app.layout.add(
            LauncherItem(
                id = app.layout.nextId(),
                type = ItemType.SHORTCUT,
                title = label,
                packageName = sender,
                intentUri = target.toUri(Intent.URI_INTENT_SCHEME),
                container = Container.DESKTOP,
                screen = screen,
                cellX = x,
                cellY = y,
                iconKey = iconKey,
            )
        )
        Log.i(TAG, "added legacy shortcut: ${label.ifEmpty { "(unnamed)" }}")
    }

    private fun extractIcon(context: Context, intent: Intent): ByteArray? = runCatching {
        val size = context.launcher.icons.sizePx

        @Suppress("DEPRECATION")
        val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON, Bitmap::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON)
        }
        if (bitmap != null) {
            return@runCatching IconCache.toPng(BitmapDrawable(context.resources, bitmap), size)
        }

        // The other form: a drawable resource belonging to the sending app,
        // which has to be resolved against that app's resources rather than
        // ours.
        @Suppress("DEPRECATION")
        val resource: Intent.ShortcutIconResource? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                    Intent.ShortcutIconResource::class.java,
                )
            } else {
                intent.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE)
            }
        if (resource != null) {
            val theirs = context.packageManager.getResourcesForApplication(resource.packageName)
            val id = theirs.getIdentifier(resource.resourceName, null, null)
            if (id != 0) {
                val drawable = androidx.core.content.res.ResourcesCompat.getDrawable(theirs, id, null)
                if (drawable != null) return@runCatching IconCache.toPng(drawable, size)
            }
        }
        null
    }.getOrElse {
        Log.w(TAG, "could not read the shortcut's icon", it)
        null
    }

    private companion object {
        const val TAG = "InstallShortcut"
        const val ACTION = "com.android.launcher.action.INSTALL_SHORTCUT"

        /** How long two identical requests count as the same one. */
        const val DUPLICATE_WINDOW_MS = 3_000L

        /**
         * Shared across instances on purpose: the manifest receiver and the
         * runtime one are different objects handling the same broadcast.
         */
        val recent = HashMap<String, Long>()
    }
}
