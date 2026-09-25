package com.freelauncher.app.ui.home

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
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
     * One view per widget, made once and kept.
     *
     * The home pager composes the page on screen and one either side, and drops
     * the rest. A widget whose page left that window lost its view, and got a
     * brand new one when the page came back: another app's layout inflated, its
     * RemoteViews applied, a list widget's adapter bound across processes, and
     * its size reported again - which asks the providing app to render the
     * widget afresh. All of that on the frames of a page swipe. Measured on a
     * three-page home with four widgets, twelve swipes recreated widget views
     * eleven times, and that was the stutter.
     *
     * Kept here instead, and moved into whichever slot is showing the widget.
     * Launchers built on Views never had this cost: every page stays attached.
     * The views go with this host, which goes with the activity, so nothing
     * outlives the context it was inflated in.
     */
    private val views = HashMap<Int, CachedWidget>()

    private class CachedWidget(val view: AppWidgetHostView) {
        /** Width and height last reported to the widget, in dp. */
        val reportedSize = intArrayOf(-1, -1)
    }

    /** The widget's view, from the cache when there is one. */
    fun viewFor(context: Context, widgetId: Int, info: AppWidgetProviderInfo): AppWidgetHostView =
        cached(context, widgetId, info).view

    private fun cached(context: Context, widgetId: Int, info: AppWidgetProviderInfo): CachedWidget {
        views[widgetId]?.let { kept ->
            if (kept.view.appWidgetInfo?.provider == info.provider) return kept
        }
        val view = createView(context, widgetId, info)
        view.setAppWidget(widgetId, info)
        // No padding of the platform's own. The space around a widget is one
        // setting, applied by the caller to every widget alike.
        //
        // The platform's suggestion depends on which Android version the widget
        // was built for, so two widgets side by side sat at visibly different
        // distances from their cells, and nothing the user could change moved
        // either of them.
        view.setPadding(0, 0, 0, 0)
        return CachedWidget(view).also { views[widgetId] = it }
    }

    /**
     * Tells the widget the size it is drawn at, unless it was already told.
     *
     * Kept with the view rather than with the composable, so a page scrolling
     * back into range does not report the same size again. Each report is a
     * binder call, and most providers answer it by rendering the widget anew.
     */
    fun reportSize(context: Context, widgetId: Int, info: AppWidgetProviderInfo, widthDp: Int, heightDp: Int) {
        val kept = cached(context, widgetId, info)
        if (kept.reportedSize[0] == widthDp && kept.reportedSize[1] == heightDp) return
        kept.reportedSize[0] = widthDp
        kept.reportedSize[1] = heightDp
        val view = kept.view
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The modern call takes the exact sizes the widget may be shown at,
            // which is what lets a responsive widget pick the layout meant for
            // that size instead of stretching another. Setting only the min and
            // max options, as this once did, leaves the size list empty and such
            // a widget guessing.
            runCatching {
                view.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp.toFloat(), heightDp.toFloat())))
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching { view.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp) }
        }
    }

    /** A deleted widget's view goes with it. */
    override fun deleteAppWidgetId(appWidgetId: Int) {
        views.remove(appWidgetId)?.view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        super.deleteAppWidgetId(appWidgetId)
    }

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
 * A provider states its size rather than a cell count, because it has no idea
 * what grid it will land in. Rounding up rather than to nearest matters:
 * a widget given less room than its minimum does not scale down, it clips, and
 * a clipped clock with its right-hand digits missing is the usual symptom.
 */
fun widgetSpan(info: AppWidgetProviderInfo, cellW: Dp, cellH: Dp, density: Float): Pair<Int, Int> {
    // A widget built for Android 12 can say how many cells it wants outright.
    // That is its author's own answer, so it wins over any arithmetic here.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        info.targetCellWidth > 0 && info.targetCellHeight > 0
    ) {
        return info.targetCellWidth to info.targetCellHeight
    }
    return cellsFor(info.minWidth, info.minHeight, cellW, cellH, density)
}

/**
 * The smallest block a widget can be resized down to.
 *
 * Its declared minimum resize size, or its default size when it declares none or
 * declares one larger - the platform reads the fields the same way. Shrinking
 * below this does not make a widget smaller, it clips it.
 */
fun widgetMinSpan(info: AppWidgetProviderInfo, cellW: Dp, cellH: Dp, density: Float): Pair<Int, Int> {
    val w = info.minResizeWidth.takeIf { it in 1..info.minWidth } ?: info.minWidth
    val h = info.minResizeHeight.takeIf { it in 1..info.minHeight } ?: info.minHeight
    return cellsFor(w, h, cellW, cellH, density)
}

/**
 * Pixels to cells.
 *
 * AppWidgetProviderInfo gives its sizes in **pixels** - the framework converts
 * the dp in the widget's XML when it reads it. Dividing those pixels by a cell
 * width in dp overstated every widget by the screen's density, around two and a
 * half times on a typical phone, so a small clock asked for the whole page and
 * was clamped to it. Converting back to dp first is the whole fix.
 */
private fun cellsFor(widthPx: Int, heightPx: Int, cellW: Dp, cellH: Dp, density: Float): Pair<Int, Int> {
    val d = density.coerceAtLeast(0.1f)
    val cols = ceil((widthPx / d) / cellW.value.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
    val rows = ceil((heightPx / d) / cellH.value.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
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
        // A slot for the widget's view rather than the view itself, because the
        // view is kept by the host and outlives any one slot. See WidgetSlot.
        factory = { ctx -> WidgetSlot(ctx, host.viewFor(ctx, widgetId, info)) },
        update = { slot ->
            // The size only changes when the grid or the widget's span does, and
            // the host skips a report that says nothing new. That matters here:
            // this block runs on every recomposition of the node, and each
            // report is a call into another process.
            host.reportSize(slot.context, widgetId, info, widthDp.value.toInt(), heightDp.value.toInt())
        },
        // A slot being thrown away hands the view back, so the next slot for this
        // widget can take it without prising it out of a dead one.
        onRelease = { slot -> slot.letGo() },
    )
}

/**
 * The place one composable shows a widget, holding the host's view while it
 * is on screen.
 *
 * A view can have only one parent, and the pager can briefly hold two slots
 * for the same widget: one being retired with its page, one arriving with the
 * page's return. So the view goes to whichever slot is attached to the window,
 * taken from wherever it was when that slot attaches. A retired slot is detached,
 * so it never takes the view back from the one on screen.
 */
private class WidgetSlot(context: Context, private val widget: AppWidgetHostView) : FrameLayout(context) {

    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        take()
    }

    override fun onAttachedToWindow() {
        take()
        super.onAttachedToWindow()
    }

    private fun take() {
        if (widget.parent === this) return
        (widget.parent as? ViewGroup)?.removeView(widget)
        addView(widget, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Gives the view up, if this slot still has it. */
    fun letGo() {
        if (widget.parent === this) removeView(widget)
    }
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
    val (spanX, spanY) = remember(choice.info, cellW, cellH, density.density) {
        widgetSpan(choice.info, cellW, cellH, density.density)
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
