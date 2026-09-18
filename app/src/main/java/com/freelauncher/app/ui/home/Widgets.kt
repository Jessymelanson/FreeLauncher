package com.freelauncher.app.ui.home

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect as AndroidRect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.freelauncher.app.data.IconCache
import kotlin.math.ceil

/**
 * The launcher's widget host.
 *
 * A widget is another app's RemoteViews inflated into this process, and the
 * only thing allowed to do that is an AppWidgetHost with a stable host id. The
 * id has to stay the same for the life of the install: the system keys every
 * binding this launcher has been granted against it, so changing it silently
 * orphans every widget the user ever placed.
 */
const val WIDGET_HOST_ID = 0x464C // "FL"

class LauncherWidgetHost(context: Context) : AppWidgetHost(context, WIDGET_HOST_ID) {

    private var listening = false

    /**
     * startListening throws on some devices when the host has no widgets yet,
     * and the launcher must not fail to start because of it. Idempotent so
     * onStart can call it without tracking whether it already did.
     */
    fun startSafely() {
        if (listening) return
        runCatching { startListening() }
            .onSuccess { listening = true }
            .onFailure { Log.w(TAG, "widget host could not start listening", it) }
    }

    fun stopSafely() {
        if (!listening) return
        runCatching { stopListening() }
        listening = false
    }

    private companion object {
        const val TAG = "LauncherWidgetHost"
    }
}

/**
 * How many grid cells a widget wants.
 *
 * A provider states its minimum size in dp, not in cells, because it has no
 * idea what grid it will land in. Rounding up rather than to nearest matters:
 * a widget given less room than its minimum does not scale down, it clips, and
 * a clipped clock with its right-hand digits missing is the usual symptom.
 */
fun widgetSpan(info: AppWidgetProviderInfo, cellW: Dp, cellH: Dp): Pair<Int, Int> {
    val cols = ceil(info.minWidth / cellW.value.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
    val rows = ceil(info.minHeight / cellH.value.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
    return cols to rows
}

/**
 * A live widget, inflated into the Compose tree.
 *
 * AndroidView because there is no Compose path to RemoteViews -- the content
 * belongs to another process and only AppWidgetHostView knows how to talk to
 * it. The size is pushed down explicitly on every layout change, since the
 * widget cannot read the cell it was placed in and will draw at its default
 * size until told otherwise.
 */
@Composable
fun WidgetCell(
    host: LauncherWidgetHost,
    widgetId: Int,
    provider: String?,
    widthDp: Dp,
    heightDp: Dp,
    modifier: Modifier = Modifier,
    onReplace: () -> Unit = {},
) {
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    val info = remember(widgetId, provider) {
        runCatching { manager.getAppWidgetInfo(widgetId) }.getOrNull()
    }

    /** Last width and height handed to the widget, so a repeat can be skipped. */
    val lastPushedSize = remember(widgetId) { intArrayOf(-1, -1) }

    if (info == null) {
        // Bound on another phone, or the provider has been uninstalled. Kept as
        // a placeholder rather than dropped, so the space the widget occupied
        // is still reserved and the user can see what used to be there -- and
        // tapping it goes straight to placing one, which is the only thing they
        // are likely to want.
        MissingWidget(provider, modifier, onReplace)
        return
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view: AppWidgetHostView = host.createView(ctx, widgetId, info)
            view.setAppWidget(widgetId, info)

            // The padding the platform says this particular widget wants.
            //
            // Widgets written before Android 4.0 expect the launcher to inset
            // them, and plenty of current ones still declare it. Without this
            // their content runs right into the edge of the cell and touches
            // the icons either side; with it, they sit where their author
            // expected. getDefaultPaddingForWidget returns zero for widgets
            // that handle their own margins, so it is safe to apply to all.
            val padding = AndroidRect()
            AppWidgetHostView.getDefaultPaddingForWidget(ctx, info.provider, padding)
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)

            view.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            view
        },
        update = { view ->
            val w = widthDp.value.toInt()
            val h = heightDp.value.toInt()

            // Only when the size has actually changed.
            //
            // AndroidView's update block runs on every recomposition of this
            // node, and telling a widget its size is a binder call into another
            // process. A home screen with four widgets that recomposes during an
            // animation was making four cross-process calls per frame to say
            // nothing had changed. The size only moves when the grid or the
            // widget's span does, which is rarely.
            if (lastPushedSize[0] != w || lastPushedSize[1] != h) {
                lastPushedSize[0] = w
                lastPushedSize[1] = h
            } else {
                return@AndroidView
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The modern call takes the exact sizes the widget may be
                // shown at, which is what lets a responsive widget pick a
                // layout instead of scaling one.
                val options = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
                }
                runCatching { view.updateAppWidgetOptions(options) }
            } else {
                @Suppress("DEPRECATION")
                runCatching { view.updateAppWidgetSize(null, w, h, w, h) }
            }
        },
    )
}

@Composable
private fun MissingWidget(
    provider: String?,
    modifier: Modifier = Modifier,
    onReplace: () -> Unit = {},
) {
    val context = LocalContext.current
    val packageName = provider?.substringBefore('/')

    // The app's own name where the package can still be resolved, its last
    // path segment where it cannot. "Clock" is recognisable; "com.android
    // .deskclock" is a guess the user has to decode.
    val label = remember(packageName) {
        packageName?.let { pkg ->
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrElse { pkg.substringAfterLast('.') }
        }
    }

    // Deliberately faint. This is a gap in the layout, not content, and a
    // widget-sized block of solid colour would draw far more attention than
    // the thing it is standing in for ever did.
    Box(
        modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(18.dp)),
        // Deliberately not clickable here. The cell around this already runs a
        // tap/long-press/drag detector, and two pointer handlers on the same
        // element fight: whichever consumes first kills the other, so adding a
        // clickable made the placeholder impossible to drag. The tap is routed
        // in from there instead.
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.Widgets,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = label ?: "Widget",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f),
            )
            Text(
                text = "Tap to place a widget",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.45f),
            )
        }
    }
}

// ---- the picker ----------------------------------------------------------

class WidgetChoice(
    val info: AppWidgetProviderInfo,
    val appLabel: String,
    val widgetLabel: String,
)

/**
 * Every widget on the phone, grouped under the app that provides it.
 *
 * Loading the preview images is left to the rows so that opening the picker is
 * instant: a phone with sixty widgets would otherwise decode sixty bitmaps
 * before the first one appears.
 */
@Composable
fun WidgetPicker(
    cellW: Dp,
    cellH: Dp,
    onDismiss: () -> Unit,
    onPick: (AppWidgetProviderInfo) -> Unit,
) {
    val context = LocalContext.current
    var choices by remember { mutableStateOf<List<WidgetChoice>>(emptyList()) }

    LaunchedEffect(Unit) {
        val manager = AppWidgetManager.getInstance(context)
        val pm = context.packageManager
        choices = runCatching {
            manager.installedProviders
                .map { info ->
                    val appLabel = runCatching {
                        pm.getApplicationLabel(
                            pm.getApplicationInfo(info.provider.packageName, 0)
                        ).toString()
                    }.getOrElse { info.provider.packageName }
                    WidgetChoice(
                        info = info,
                        appLabel = appLabel,
                        widgetLabel = runCatching { info.loadLabel(pm) }
                            .getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: info.provider.shortClassName.substringAfterLast('.'),
                    )
                }
                .sortedWith(compareBy({ it.appLabel.lowercase() }, { it.widgetLabel.lowercase() }))
        }.getOrElse {
            Log.w("WidgetPicker", "could not list widget providers", it)
            emptyList()
        }
    }

    WidgetPickerSheet(choices, cellW, cellH, onDismiss, onPick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetPickerSheet(
    choices: List<WidgetChoice>,
    cellW: Dp,
    cellH: Dp,
    onDismiss: () -> Unit,
    onPick: (AppWidgetProviderInfo) -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 18.dp)) {
                Text(
                    "Widgets",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                )
                if (choices.isEmpty()) {
                    Text(
                        "No widgets are available on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(22.dp),
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.heightIn(max = 480.dp),
                    ) {
                        items(choices, key = { it.info.provider.flattenToString() + it.widgetLabel }) { choice ->
                            WidgetRow(choice, cellW, cellH) { onPick(choice.info) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetRow(
    choice: WidgetChoice,
    cellW: Dp,
    cellH: Dp,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // Cells, not dp. A widget's own declared size is in density-independent
    // pixels, which tells the user nothing about whether it will fit; what they
    // are deciding is how much of their grid it will take.
    val (spanX, spanY) = remember(choice.info, cellW, cellH) {
        widgetSpan(choice.info, cellW, cellH)
    }
    var preview by remember(choice.info.provider) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }

    LaunchedEffect(choice.info.provider) {
        preview = runCatching {
            val drawable = choice.info.loadPreviewImage(context, context.resources.displayMetrics.densityDpi)
                ?: choice.info.loadIcon(context, context.resources.displayMetrics.densityDpi)
            drawable?.let { IconCache.toBitmap(it, with(density) { 48.dp.roundToPx() }).asImageBitmap() }
        }.getOrNull()
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = preview
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                )
            } else {
                Icon(
                    Icons.Rounded.Widgets,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(choice.widgetLabel, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                choice.appLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "$spanX × $spanY",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

// ---- adding one ----------------------------------------------------------

/**
 * The three-step dance to place a widget.
 *
 * Every step can be refused by the user or the system, and each refusal has to
 * hand back the allocated id or it leaks -- an id that is allocated but never
 * bound stays reserved against this host forever. So every path out of here
 * either places a widget or deletes the id.
 */
class WidgetPlacement(
    private val activity: Activity,
    private val host: LauncherWidgetHost,
) {
    private val manager = AppWidgetManager.getInstance(activity)

    fun allocate(): Int = host.allocateAppWidgetId()

    fun release(id: Int) {
        runCatching { host.deleteAppWidgetId(id) }
    }

    /**
     * True when the binding already exists or was granted silently. Default
     * launchers are usually allowed to bind without asking; everyone else gets
     * false and has to show the system's consent screen.
     */
    fun bindIfAllowed(id: Int, provider: ComponentName, profile: android.os.UserHandle?): Boolean =
        runCatching {
            if (profile != null) {
                manager.bindAppWidgetIdIfAllowed(id, profile, provider, null)
            } else {
                manager.bindAppWidgetIdIfAllowed(id, provider)
            }
        }.getOrDefault(false)

    fun bindIntent(id: Int, provider: ComponentName) =
        android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
        }

    fun needsConfigure(id: Int): Boolean =
        runCatching { manager.getAppWidgetInfo(id)?.configure != null }.getOrDefault(false)

}

// Configuration is started from MainActivity rather than from here, because
// startAppWidgetConfigureActivityForResult answers through onActivityResult and
// only an Activity has one. There is no equivalent of it on this class for the
// same reason -- it would have to hand the result somewhere it cannot reach.
