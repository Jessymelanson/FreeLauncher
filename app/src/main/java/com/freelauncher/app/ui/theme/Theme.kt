package com.freelauncher.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.freelauncher.app.data.AccentColor
import com.freelauncher.app.data.LauncherSettings
import com.freelauncher.app.data.ThemeMode

/**
 * Colours that only a launcher needs.
 *
 * Material's scheme assumes it owns the background. A launcher does not: most
 * of what the user sees is their wallpaper, and every surface the launcher
 * draws floats on top of it. These are the values for that -- the scrim over
 * the wallpaper, the glass of the dock, the colour text has to be when it might
 * land on anything at all.
 */
data class LauncherPalette(
    /** Text drawn straight onto the wallpaper. */
    val onWallpaper: Color,
    val onWallpaperMuted: Color,

    /** The shadow that keeps [onWallpaper] readable over a pale photo. */
    val wallpaperTextShadow: Color,

    /** Dock and folder glass. */
    val glass: Color,
    val glassBorder: Color,

    /** The app drawer's own background, before the opacity setting. */
    val drawerBackground: Color,

    val accent: Color,
)

val LocalLauncherPalette = staticCompositionLocalOf {
    LauncherPalette(
        onWallpaper = Color.White,
        onWallpaperMuted = Color(0xCCFFFFFF),
        wallpaperTextShadow = Color(0x99000000),
        glass = Color(0x33FFFFFF),
        glassBorder = Color(0x1AFFFFFF),
        drawerBackground = Color(0xFF121317),
        accent = Color(0xFF7C4DD1),
    )
}

/**
 * Text drawn over the wallpaper always uses light colours with a shadow, in
 * every theme.
 *
 * This looks like an oversight and is not. The theme setting controls the
 * launcher's own surfaces -- the drawer, the settings screen, the folder sheet.
 * It says nothing about the wallpaper, which the user picked separately and
 * which is usually dark. Light-on-wallpaper with a soft shadow is legible over
 * both a black photo and a white one; dark text in light theme is legible over
 * neither reliably.
 */
private val WallpaperText = LauncherPalette(
    onWallpaper = Color.White,
    onWallpaperMuted = Color(0xE6FFFFFF),
    wallpaperTextShadow = Color(0xA6000000),
    glass = Color(0x30FFFFFF),
    glassBorder = Color(0x1FFFFFFF),
    drawerBackground = Color(0xFF121317),
    accent = Color(0xFF7C4DD1),
)

@Composable
fun FreeLauncherTheme(
    settings: LauncherSettings,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
    }
    val black = settings.themeMode == ThemeMode.BLACK
    val accent = Color(settings.accent.rgb)

    val scheme = if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = accent.copy(alpha = 0.22f),
            onPrimaryContainer = Color.White,
            secondary = accent.copy(alpha = 0.75f),
            // Black mode is for OLED panels, where a true zero pixel is off
            // rather than dim. Surfaces still step apart from each other, just
            // from a lower floor -- if every level were 0x000000 the drawer
            // would have no visible edge against the wallpaper behind it.
            background = if (black) Color.Black else Color(0xFF101115),
            onBackground = Color(0xFFE8E9ED),
            surface = if (black) Color.Black else Color(0xFF16171C),
            onSurface = Color(0xFFE8E9ED),
            surfaceVariant = if (black) Color(0xFF0E0E10) else Color(0xFF23252C),
            onSurfaceVariant = Color(0xFFB9BCC6),
            outline = Color(0xFF41444E),
            outlineVariant = Color(0xFF2A2C34),
            error = Color(0xFFFF8A80),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = accent.copy(alpha = 0.14f),
            onPrimaryContainer = accent,
            secondary = accent.copy(alpha = 0.8f),
            background = Color(0xFFF7F7FA),
            onBackground = Color(0xFF15161A),
            surface = Color.White,
            onSurface = Color(0xFF15161A),
            surfaceVariant = Color(0xFFEDEEF3),
            onSurfaceVariant = Color(0xFF52555E),
            outline = Color(0xFFC3C6CF),
            outlineVariant = Color(0xFFE2E4EA),
        )
    }

    val palette = WallpaperText.copy(
        accent = accent,
        drawerBackground = if (dark) {
            if (black) Color.Black else Color(0xFF101115)
        } else {
            Color(0xFFF7F7FA)
        },
    )

    CompositionLocalProvider(LocalLauncherPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = LauncherTypography,
            content = content,
        )
    }
}

private val LauncherTypography = Typography()

/**
 * The icon label style.
 *
 * Separate from the type scale because it is not body text and does not scale
 * with it: it sits under a 48dp icon in a cell whose width is fixed by the grid,
 * so it has to stay small enough that a two-word app name still fits on one
 * line. The shadow is what makes it readable over an arbitrary wallpaper.
 */
@Composable
fun iconLabelStyle(scale: Float, onWallpaper: Boolean): TextStyle {
    val palette = LocalLauncherPalette.current
    return TextStyle(
        fontSize = (12f * scale).sp,
        lineHeight = (14f * scale).sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        color = if (onWallpaper) palette.onWallpaper else MaterialTheme.colorScheme.onSurface,
        shadow = if (onWallpaper) {
            androidx.compose.ui.graphics.Shadow(
                color = palette.wallpaperTextShadow,
                offset = androidx.compose.ui.geometry.Offset(0f, 1.5f),
                blurRadius = 4f,
            )
        } else {
            null
        },
    )
}

fun AccentColor.color(): Color = Color(rgb)
