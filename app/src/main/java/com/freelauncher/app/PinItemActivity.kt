package com.freelauncher.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.freelauncher.app.data.Container
import com.freelauncher.app.data.IconCache
import com.freelauncher.app.data.ItemType
import com.freelauncher.app.data.LauncherItem
import com.freelauncher.app.ui.home.LauncherWidgetHost
import com.freelauncher.app.ui.home.widgetSpan
import com.freelauncher.app.ui.theme.FreeLauncherTheme

/**
 * "Add to home screen", from another app.
 *
 * This is the piece that makes a browser's *Add to Home screen* work, and the
 * reason it has to exist at all is not obvious: an app cannot pin anything by
 * itself. It calls `ShortcutManager.requestPinShortcut`, the system looks at
 * the current home screen, and asks whether it handles
 * `CONFIRM_PIN_SHORTCUT`. A launcher that does not declare this activity is
 * reported back through `isRequestPinShortcutSupported` as not supporting
 * pinning at all -- so the browser either greys the option out or does nothing
 * when it is tapped. Nothing is broken and nothing logs an error; the feature
 * is simply absent, which is exactly how it looked.
 *
 * Widgets arrive here too, by the same mechanism, when an app offers to place
 * its own widget.
 */
class PinItemActivity : ComponentActivity() {

    private lateinit var launcherApps: LauncherApps
    private var request: LauncherApps.PinItemRequest? = null

    /** Allocated before the widget is accepted, so it can be handed back. */
    private var pendingWidgetId: Int = -1
    private var widgetHost: LauncherWidgetHost? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

        val pending = runCatching { launcherApps.getPinItemRequest(intent) }.getOrNull()
        if (pending == null || !pending.isValid) {
            // Stale or malformed. Nothing to show and nothing to accept.
            Log.w(TAG, "pin request missing or no longer valid")
            finish()
            return
        }
        request = pending

        val label: String
        val preview: ImageBitmap?

        when (pending.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> {
                val info = pending.shortcutInfo
                if (info == null) {
                    finish()
                    return
                }
                label = info.shortLabel?.toString()
                    ?: info.longLabel?.toString()
                    ?: getString(R.string.app_name)
                preview = shortcutIcon(info)
            }

            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> {
                val provider = runCatching { pending.getAppWidgetProviderInfo(this) }.getOrNull()
                if (provider == null) {
                    finish()
                    return
                }
                label = runCatching { provider.loadLabel(packageManager) }.getOrNull()
                    ?: provider.provider.shortClassName.substringAfterLast('.')
                preview = null
            }

            else -> {
                finish()
                return
            }
        }

        setContent {
            val settings by launcher.settings.state.collectAsState()
            FreeLauncherTheme(settings) {
                ConfirmSheet(
                    label = label,
                    preview = preview,
                    isWidget = pending.requestType ==
                        LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET,
                    onAdd = ::acceptRequest,
                    onCancel = { finish() },
                )
            }
        }
    }

    private fun shortcutIcon(info: ShortcutInfo): ImageBitmap? = runCatching {
        val drawable = launcherApps.getShortcutIconDrawable(info, resources.displayMetrics.densityDpi)
        drawable?.let { IconCache.toBitmap(it, launcher.icons.sizePx).asImageBitmap() }
    }.getOrNull()

    // ---- accepting -------------------------------------------------------

    private fun acceptRequest() {
        val pending = request ?: run { finish(); return }
        when (pending.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> acceptShortcut(pending)
            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> acceptWidget(pending)
            else -> finish()
        }
    }

    private fun acceptShortcut(pending: LauncherApps.PinItemRequest) {
        val info = pending.shortcutInfo ?: run { finish(); return }

        // accept() is what actually pins it. Until this returns true the
        // shortcut is not ours and starting it later would be refused.
        if (!runCatching { pending.accept() }.getOrDefault(false)) {
            Log.w(TAG, "the system declined the pin request")
            finish()
            return
        }

        val app = launcher
        val settings = app.settings.value

        // The icon is stored now or never: a web shortcut's icon belongs to no
        // installed package, so there is nothing to look it up from later.
        val iconKey = runCatching {
            launcherApps.getShortcutIconDrawable(info, resources.displayMetrics.densityDpi)
                ?.let { IconCache.toPng(it, app.icons.sizePx) }
                ?.let { app.icons.writeCustomIcon("pin_${info.`package`}_${info.id}.png", it) }
        }.getOrNull()

        val (screen, x, y) = app.layout.findSlot(
            preferredScreen = settings.defaultPage,
            cols = settings.desktopCols,
            rows = settings.desktopRows,
            screens = app.layout.screenCount.value,
        )

        app.layout.add(
            LauncherItem(
                id = app.layout.nextId(),
                type = ItemType.DEEP_SHORTCUT,
                title = info.shortLabel?.toString() ?: info.longLabel?.toString().orEmpty(),
                component = info.activity?.flattenToString(),
                packageName = info.`package`,
                shortcutId = info.id,
                // Which profile published it.
                //
                // This was left at the default of zero, which is the personal
                // user, and a shortcut pinned from a work profile app was
                // therefore recorded as a personal one. Starting it later looks
                // the shortcut up by profile, finds nothing under that user,
                // and fails -- so the icon appeared, looked right, and did
                // nothing when tapped.
                userSerial = runCatching {
                    val users = getSystemService(Context.USER_SERVICE) as android.os.UserManager
                    users.getSerialNumberForUser(info.userHandle)
                }.getOrDefault(0L),
                container = Container.DESKTOP,
                screen = screen,
                cellX = x,
                cellY = y,
                iconKey = iconKey,
            )
        )
        finish()
    }

    private fun acceptWidget(pending: LauncherApps.PinItemRequest) {
        val provider = runCatching { pending.getAppWidgetProviderInfo(this) }.getOrNull()
            ?: run { finish(); return }

        val host = LauncherWidgetHost(applicationContext).also { widgetHost = it }
        val widgetId = runCatching { host.allocateAppWidgetId() }.getOrDefault(-1)
        if (widgetId == -1) {
            finish()
            return
        }
        pendingWidgetId = widgetId

        // The id is handed to the system in the accept, which is what binds it
        // to this host. Passing nothing would pin a widget this launcher has no
        // way to draw.
        val extras = Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId) }
        if (!runCatching { pending.accept(extras) }.getOrDefault(false)) {
            runCatching { host.deleteAppWidgetId(widgetId) }
            finish()
            return
        }

        val needsConfigure = runCatching {
            AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.configure != null
        }.getOrDefault(false)

        if (needsConfigure) {
            val started = runCatching {
                host.startAppWidgetConfigureActivityForResult(this, widgetId, 0, REQUEST_CONFIGURE, null)
                true
            }.getOrDefault(false)
            if (started) return
        }

        placeWidget(widgetId, provider)
        finish()
    }

    private fun placeWidget(widgetId: Int, provider: android.appwidget.AppWidgetProviderInfo) {
        val app = launcher
        val settings = app.settings.value
        val cellW = resources.configuration.screenWidthDp.dp / settings.desktopCols
        val cellH = (resources.configuration.screenHeightDp.dp * 0.75f) / settings.desktopRows
        val (wantX, wantY) = widgetSpan(provider, cellW, cellH)
        val spanX = wantX.coerceIn(1, settings.desktopCols)
        val spanY = wantY.coerceIn(1, settings.desktopRows)

        val (screen, x, y) = app.layout.findSlot(
            preferredScreen = settings.defaultPage,
            cols = settings.desktopCols,
            rows = settings.desktopRows,
            screens = app.layout.screenCount.value,
            spanX = spanX,
            spanY = spanY,
        )

        app.layout.add(
            LauncherItem(
                id = app.layout.nextId(),
                type = ItemType.WIDGET,
                title = "",
                container = Container.DESKTOP,
                screen = screen,
                cellX = x.coerceAtMost((settings.desktopCols - spanX).coerceAtLeast(0)),
                cellY = y.coerceAtMost((settings.desktopRows - spanY).coerceAtLeast(0)),
                spanX = spanX,
                spanY = spanY,
                widgetId = widgetId,
                widgetProvider = provider.provider.flattenToString(),
            )
        )
    }

    @Deprecated("Needed for startAppWidgetConfigureActivityForResult, which has no modern equivalent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIGURE) return

        val widgetId = pendingWidgetId
        val provider = runCatching {
            AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
        }.getOrNull()

        if (resultCode == RESULT_OK && provider != null) {
            placeWidget(widgetId, provider)
        } else {
            // Configuration cancelled. The id goes back, or it stays allocated
            // against this host for the life of the install.
            runCatching { widgetHost?.deleteAppWidgetId(widgetId) }
        }
        finish()
    }

    private companion object {
        const val TAG = "PinItemActivity"
        const val REQUEST_CONFIGURE = 0x5702
    }
}

/**
 * The confirmation.
 *
 * Shown rather than accepting silently, because the request arrives from
 * another app and the user's last interaction was with that app, not with the
 * home screen. A thing appearing on the home screen with no acknowledgement is
 * indistinguishable from a thing appearing on its own.
 */
@androidx.compose.runtime.Composable
private fun ConfirmSheet(
    label: String,
    preview: ImageBitmap?,
    isWidget: Boolean,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(
                indication = null,
                interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                onClick = onCancel,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp,
            shadowElevation = 20.dp,
            modifier = Modifier
                .padding(28.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (preview != null) {
                    androidx.compose.foundation.Image(
                        bitmap = preview,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                }

                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (isWidget) "Add this widget to your home screen?"
                    else "Add this shortcut to your home screen?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(22.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onAdd) { Text("Add") }
                }
            }
        }
    }
}
