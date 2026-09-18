package com.freelauncher.app.data

import android.app.Notification
import android.content.Context
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.freelauncher.app.launcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which apps have something waiting.
 *
 * A set of package-and-profile keys rather than counts. A launcher draws a dot,
 * not a number: the number belongs to the app's own notification, the user is
 * about to see it anyway, and a count that is one behind reality is worse than
 * no count at all. Keeping only the set also means the common change -- a
 * notification arriving for an app that already had one -- publishes nothing
 * and recomposes nothing.
 *
 * Empty whenever the listener is not connected, which is the honest state: this
 * app genuinely does not know, and a stale dot outlives the notification it
 * stood for.
 */
class NotificationDots {

    private val _packages = MutableStateFlow<Set<String>>(emptySet())
    val packages: StateFlow<Set<String>> = _packages.asStateFlow()

    fun publish(keys: Set<String>) {
        _packages.value = keys
    }

    fun clear() {
        _packages.value = emptySet()
    }

    companion object {
        /** The same shape of key [AppEntry] uses, minus the activity. */
        fun keyOf(packageName: String?, userSerial: Long): String? =
            packageName?.let { "$it#$userSerial" }

        /**
         * Whether the user has granted notification access.
         *
         * Asked rather than remembered: it is granted and revoked in system
         * settings, and nothing tells an app when that happens.
         */
        fun hasAccess(context: Context): Boolean = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        }.getOrDefault(false)
    }
}

/**
 * The listener itself.
 *
 * This is the only way for any app to know that another app has a notification,
 * and it costs a permission the user has to grant by hand, in a system screen,
 * with a warning on it. That is proportionate -- the same grant lets an app read
 * the text of every notification on the phone -- so it is off until asked for,
 * and the only thing taken from each notification here is the name of the
 * package that posted it.
 *
 * Every callback rebuilds the whole set from getActiveNotifications rather than
 * adding and removing as it goes. Incremental bookkeeping has to be right about
 * every path -- a notification updated in place, a group summary replaced, an
 * app whose process died -- and the cost of being wrong is a dot that never
 * goes away. Rebuilding is a handful of objects already in memory.
 */
class LauncherNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        refresh()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        runCatching { applicationContext.launcher.dots.clear() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        val app = runCatching { applicationContext.launcher }.getOrNull() ?: return
        val users = applicationContext.getSystemService(Context.USER_SERVICE) as? UserManager

        val active = runCatching { activeNotifications }.getOrElse {
            // Thrown when the listener is not connected yet, which happens if a
            // callback arrives during binding.
            Log.w(TAG, "could not read active notifications", it)
            return
        } ?: return

        val keys = HashSet<String>(16)
        for (sbn in active) {
            if (sbn == null) continue
            if (sbn.packageName == packageName) continue

            // Ongoing notifications are not news. A media player, a download, a
            // navigation session and "this app is running in the background"
            // are all permanent while they last, so a dot for them would be a
            // dot that never goes out and never means anything.
            val flags = runCatching { sbn.notification.flags }.getOrDefault(0)
            if (flags and Notification.FLAG_ONGOING_EVENT != 0) continue

            val serial = users?.let {
                runCatching { it.getSerialNumberForUser(sbn.user) }.getOrNull()
            } ?: 0L
            NotificationDots.keyOf(sbn.packageName, serial)?.let { keys += it }
        }
        app.dots.publish(keys)
    }

    private companion object {
        const val TAG = "NotificationDots"
    }
}
