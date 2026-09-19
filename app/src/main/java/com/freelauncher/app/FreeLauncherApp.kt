package com.freelauncher.app

import android.app.Application
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import com.freelauncher.app.data.AppRepository
import com.freelauncher.app.data.IconCache
import com.freelauncher.app.data.LayoutStore
import com.freelauncher.app.data.NotificationDots
import com.freelauncher.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The launcher's long-lived state.
 *
 * Held on the Application rather than in an activity because both activities
 * need it and because the home screen is restarted far more often than the
 * process is. Rebuilding the icon cache every time the user presses Home would
 * throw away exactly the work it exists to avoid.
 *
 * No dependency injection framework. There are four objects, they have no
 * cycles, and the wiring is the five lines below; a container would add a
 * compile step and a layer of indirection to express the same thing.
 */
class FreeLauncherApp : Application() {

    /**
     * For work that has to outlive any one screen, which so far is exactly one
     * thing: watching the icon pack setting.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var settings: SettingsStore
        private set
    lateinit var layout: LayoutStore
        private set
    lateinit var apps: AppRepository
        private set
    lateinit var icons: IconCache
        private set

    /**
     * Which apps have a notification waiting.
     *
     * Lives here rather than in the listener service because the service is
     * created and destroyed by the system whenever access is granted, revoked
     * or the process is rebuilt, and the home screen must not lose its dots to
     * that. The service writes; everything else reads.
     */
    val dots = NotificationDots()

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        layout = LayoutStore(this)
        icons = IconCache(this, layout.iconDir)
        apps = AppRepository(this)

        // The one wire between them: a package that changed has icons in the
        // cache that are now wrong.
        apps.onPackageInvalidated = { packageName -> icons.evictPackage(packageName) }

        layout.load()
        apps.start()
        registerLegacyShortcuts()

        // The chosen icon pack, applied now and again whenever it changes.
        //
        // Watched here rather than acted on from the settings screen, because
        // the setting also arrives from a restored backup and from a Nova
        // import -- neither of which goes anywhere near that screen, and both
        // of which would otherwise leave the launcher showing one pack and
        // believing in another until it was next started.
        scope.launch {
            settings.state
                .map { it.iconPack }
                .distinctUntilChanged()
                .collect { name ->
                    withContext(Dispatchers.IO) { icons.setIconPack(this@FreeLauncherApp, name) }
                }
        }
    }

    /**
     * The pre-Android-8 "add this to the home screen" broadcast, received at
     * runtime as well as through the manifest.
     *
     * Three platform eras, and it is worth writing all three down because the
     * failure looks identical in two of them -- an app says it added a
     * shortcut, and nothing appears.
     *
     * - **Android 7 and earlier.** The manifest receiver works.
     * - **Android 8 to 14.** Implicit broadcasts stopped being delivered to
     *   manifest-declared receivers, and INSTALL_SHORTCUT is implicit: it names
     *   an action and no package. It is not on the exemption list, so the
     *   manifest receiver is never called. A receiver registered at *runtime*
     *   is exempt from that rule, which is what the registration below is for,
     *   and a launcher is the one kind of app that can rely on it -- the home
     *   process is alive whenever the screen is on, which is the only time an
     *   app can ask for a shortcut anyway.
     * - **Android 15 and later.** The platform refuses the broadcast outright,
     *   to everyone. It logs `Broadcast com.android.launcher.action
     *   .INSTALL_SHORTCUT no longer supported. It will not be delivered.` and
     *   drops it before any receiver is consulted. Nothing a launcher does can
     *   bring it back, and an app still using this API on a modern phone cannot
     *   create a shortcut on any launcher, stock ones included.
     *
     * So this is kept for the middle era, where it is the difference between
     * working and not, and is simply inert on the newest one.
     *
     * The manifest entry stays for the other case: a sender that names this
     * package explicitly makes the broadcast a directed one, which is delivered
     * to a manifest receiver and works even if the process is not running.
     * Both can fire for the same broadcast, which is what the guard inside the
     * receiver is for.
     */
    private fun registerLegacyShortcuts() {
        val filter = IntentFilter("com.android.launcher.action.INSTALL_SHORTCUT")
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(InstallShortcutReceiver(), filter, RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(InstallShortcutReceiver(), filter)
            }
        }
    }

    /**
     * Release the icon cache, but only under real memory pressure.
     *
     * The levels are not ordered the way they read. TRIM_MEMORY_UI_HIDDEN is
     * 20, which is *higher* than TRIM_MEMORY_RUNNING_CRITICAL at 15 -- and
     * UI_HIDDEN fires every single time the launcher goes to the background,
     * which is every single time the user opens an app.
     *
     * So `level >= TRIM_MEMORY_RUNNING_LOW`, which is the obvious thing to
     * write and what this did first, threw away every rendered icon on every
     * app launch. Coming back to the home screen then had to rasterise the lot
     * again, and the user sees that as the home screen being slow to appear.
     * That is the entire bug: the launcher was not slow, it was deleting its
     * own work on the way out.
     *
     * Named levels only, therefore, and never UI_HIDDEN. The cache is bounded
     * to an eighth of the heap regardless, which is what actually keeps the
     * footprint in check; this is a courtesy on top of it.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val underPressure = level == TRIM_MEMORY_RUNNING_CRITICAL || level == TRIM_MEMORY_COMPLETE
        if (underPressure) icons.clear()
    }
}

/** Reaching the singletons from anywhere that has a Context. */
val Context.launcher: FreeLauncherApp
    get() = applicationContext as FreeLauncherApp
