package com.freelauncher.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.CompositionLocalProvider
import com.freelauncher.app.ui.common.LocalNotificationDots
import com.freelauncher.app.ui.home.HomeRoot
import com.freelauncher.app.ui.home.LauncherWidgetHost
import com.freelauncher.app.ui.theme.FreeLauncherTheme

/**
 * The home screen.
 *
 * Thin on purpose: everything that can live in a composable does, and what is
 * left here is the handful of things that genuinely cannot -- the window, the
 * widget host's lifecycle, and the Home key.
 */
class MainActivity : ComponentActivity() {

    /**
     * The widget host lives here, not in the composition.
     *
     * startAppWidgetConfigureActivityForResult takes an Activity and delivers
     * its answer to onActivityResult, so the host has to be owned by something
     * that has both. It also has to outlive recomposition: a host recreated
     * mid-configuration would lose the callback and strand the allocated id.
     */
    private lateinit var widgetHost: LauncherWidgetHost

    private var widgetConfigCallback: ((Boolean) -> Unit)? = null

    /**
     * Bumped every time Home is pressed.
     *
     * A counter rather than a flag because the interesting event is the press
     * itself, and two presses in a row must both be observable -- a boolean
     * that is already true the second time would do nothing.
     */
    private var homeResetKey by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge to edge. The whole point of a launcher window is that the
        // wallpaper shows through it, and that includes behind the status and
        // navigation bars; the composables inset themselves with
        // statusBarsPadding and navigationBarsPadding.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        widgetHost = LauncherWidgetHost(applicationContext)

        setContent {
            val settings by launcher.settings.state.collectAsState()

            // In an effect, not in the composable body.
            //
            // Touching the window is a side effect on the activity, and the
            // body of a composable runs on every recomposition -- which here
            // means every settings change, every drag frame, every page turn.
            // Keyed on the one setting it reads, so it runs when that changes
            // and at no other time.
            //
            // Light icons in the system bars, always: they sit over the
            // wallpaper rather than over the launcher's own surfaces, so they
            // follow the same reasoning as the icon labels. The wallpaper is
            // usually dark and is never something the theme setting knows about.
            LaunchedEffect(settings.hideStatusBar) {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                    if (settings.hideStatusBar) {
                        hide(WindowInsetsCompat.Type.statusBars())
                        systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    } else {
                        show(WindowInsetsCompat.Type.statusBars())
                    }
                }
            }

            // Gated here rather than inside the icons. The listener keeps
            // reporting for as long as the system leaves it bound -- revoking
            // access is the user's business and not something an app can do to
            // itself -- so the switch is honoured at the one point every icon
            // reads from, and turning it off blanks every dot at once.
            val dots by launcher.dots.packages.collectAsState()
            val visibleDots = if (settings.notificationDots) dots else emptySet()

            FreeLauncherTheme(settings) {
                CompositionLocalProvider(LocalNotificationDots provides visibleDots) {
                    HomeRoot(
                        settings = settings,
                        homeResetKey = homeResetKey,
                        widgetHost = widgetHost,
                        onOpenSettings = {
                            startActivity(Intent(this, SettingsActivity::class.java))
                        },
                        onConfigureWidget = ::configureWidget,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        widgetHost.startSafely()
    }

    override fun onStop() {
        super.onStop()
        // Listening costs a binder connection per widget and keeps their
        // update alarms live. The home screen is hidden most of the time, so
        // holding that open whenever the user is in another app is the single
        // easiest thing for a launcher to get wrong about battery.
        widgetHost.stopSafely()
    }

    /**
     * Home was pressed while this activity was already the foreground task.
     *
     * The only signal a launcher gets for it. Without handling it, pressing
     * Home from inside FreeLauncher does nothing at all, and an open drawer or
     * folder stays open -- which is the single most noticeable way a launcher
     * feels broken.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        homeResetKey++
    }

    private fun configureWidget(widgetId: Int, onResult: (Boolean) -> Unit) {
        widgetConfigCallback = onResult
        val started = runCatching {
            widgetHost.startAppWidgetConfigureActivityForResult(
                this, widgetId, 0, REQUEST_CONFIGURE, null,
            )
            true
        }.getOrDefault(false)

        if (!started) {
            // The widget is already bound; it simply has no reachable
            // configuration screen. Keeping it with its defaults is better than
            // discarding a placement the user asked for.
            widgetConfigCallback = null
            onResult(true)
        }
    }

    @Deprecated("Needed for startAppWidgetConfigureActivityForResult, which has no modern equivalent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIGURE) return
        val callback = widgetConfigCallback
        widgetConfigCallback = null
        callback?.invoke(resultCode == RESULT_OK)
    }

    private companion object {
        const val REQUEST_CONFIGURE = 0x5701
    }
}
