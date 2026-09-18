package com.freelauncher.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import com.freelauncher.app.ui.settings.SettingsScreen
import com.freelauncher.app.ui.theme.FreeLauncherTheme

/**
 * Settings, as a normal activity.
 *
 * Separate from the home screen rather than a page inside it, and this is the
 * entry that carries CATEGORY_LAUNCHER. That is what makes FreeLauncher
 * configurable before it is the default launcher: MainActivity answers only to
 * HOME, so until the user switches over there would be no way to open this app
 * at all.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val settings by launcher.settings.state.collectAsState()
            FreeLauncherTheme(settings) {
                SettingsScreen(onBack = { finish() })
            }
        }
    }
}
