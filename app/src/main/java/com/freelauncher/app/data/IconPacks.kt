package com.freelauncher.app.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** One installed icon pack. */
data class IconPack(val packageName: String, val label: String)

/**
 * Third-party icon packs.
 *
 * There is no Android API for this and there never was. What exists instead is a
 * convention that ADW Launcher started in 2010 and everyone else copied: a pack
 * is an ordinary app that declares a themes intent, and inside it is an
 * `appfilter.xml` mapping component names to drawable names in its own
 * resources. Thousands of packs on Play follow it exactly, which is why it is
 * worth implementing a convention rather than waiting for an API.
 *
 * Three actions are queried because the convention was copied three times and
 * packs declare whichever their author had heard of. A pack that declares none
 * of them is invisible here, which is the same as it is invisible to every other
 * launcher.
 */
object IconPacks {

    private val THEME_ACTIONS = listOf(
        "com.novalauncher.THEME",
        "org.adw.launcher.THEMES",
        "com.gau.go.launcherex.theme",
    )

    fun installed(context: Context): List<IconPack> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, IconPack>()
        for (action in THEME_ACTIONS) {
            val matches = runCatching {
                pm.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA)
            }.getOrDefault(emptyList())
            for (info in matches) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg in found) continue
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrElse { pkg }
                found[pkg] = IconPack(pkg, label)
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }
}

/**
 * One pack, loaded and ready to answer.
 *
 * Built once per pack and held by [IconCache]. Parsing `appfilter.xml` means
 * reading another app's resources and walking a few thousand XML elements, so
 * doing it per icon would be absurd; doing it on the main thread would be
 * worse. Construction is therefore expected to happen on a worker, and
 * everything after it is a hash lookup.
 *
 * A pack that cannot be read at all -- uninstalled between being chosen and
 * being loaded, or simply malformed -- produces a loader that answers null to
 * everything, which degrades to the app's own icons rather than to a crash or a
 * screen of blanks.
 */
class IconPackLoader(context: Context, val packageName: String) {

    private val resources: Resources? = runCatching {
        context.packageManager.getResourcesForApplication(packageName)
    }.getOrElse {
        Log.w(TAG, "icon pack $packageName could not be opened", it)
        null
    }

    /** `ComponentInfo{pkg/cls}` to the drawable name the pack uses for it. */
    private val mapping = HashMap<String, String>(2048)

    /**
     * The pack's treatment for apps it has no icon for.
     *
     * A good pack themes a few hundred apps and leaves the rest alone, and a
     * home screen where a fifth of the icons match and the rest do not looks
     * worse than one where none of them do. These three -- a background plate,
     * a mask to cut the app's icon with, and a foreground overlay -- are how a
     * pack says what an unthemed icon should be made to look like, and applying
     * them is the difference between a pack that works and a pack that half
     * works.
     */
    private var backNames: List<String> = emptyList()
    private var maskName: String? = null
    private var uponName: String? = null
    private var scale: Float = 1f

    init {
        runCatching { parse() }.onFailure { Log.w(TAG, "appfilter unreadable in $packageName", it) }
    }

    val isUsable: Boolean get() = resources != null && (mapping.isNotEmpty() || maskName != null || backNames.isNotEmpty())

    // ---- reading the pack ------------------------------------------------

    private fun parse() {
        val res = resources ?: return
        val parser = openAppFilter(res) ?: return

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val component = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (component != null && drawable != null) {
                            // Kept in the pack's own notation rather than
                            // unpacked into a ComponentName. Packs are not
                            // consistent about the form, and comparing the
                            // strings the pack itself wrote against the same
                            // string built the same way cannot drift.
                            mapping[component] = drawable
                        }
                    }

                    "iconback" -> backNames = parser.drawableList()
                    "iconmask" -> maskName = parser.firstDrawable()
                    "iconupon" -> uponName = parser.firstDrawable()
                    "scale" -> scale = parser.getAttributeValue(null, "factor")
                        ?.toFloatOrNull()?.coerceIn(0.1f, 2f) ?: 1f
                }
            }
            event = parser.next()
        }
        Log.i(TAG, "$packageName: ${mapping.size} themed icon(s)")
    }

    /**
     * The pack's appfilter, from wherever that pack keeps it.
     *
     * Two conventions again, and packs use one or the other with no way to tell
     * from outside: compiled into `res/xml` where it is a resource, or dropped
     * into `assets` where it is a file. Both are tried.
     */
    private fun openAppFilter(res: Resources): XmlPullParser? {
        val id = runCatching { res.getIdentifier("appfilter", "xml", packageName) }.getOrDefault(0)
        if (id != 0) {
            runCatching { return res.getXml(id) }
        }
        return runCatching {
            val stream = res.assets.open("appfilter.xml")
            XmlPullParserFactory.newInstance().newPullParser().apply {
                setInput(stream, null)
            }
        }.getOrNull()
    }

    private fun XmlPullParser.firstDrawable(): String? =
        getAttributeValue(null, "img") ?: getAttributeValue(null, "img1")

    private fun XmlPullParser.drawableList(): List<String> =
        (0 until attributeCount).mapNotNull { i ->
            getAttributeValue(i)?.takeIf { getAttributeName(i).startsWith("img") }
        }

    private fun drawable(name: String?): Drawable? {
        val res = resources ?: return null
        if (name.isNullOrEmpty()) return null
        val id = runCatching { res.getIdentifier(name, "drawable", packageName) }.getOrDefault(0)
        if (id == 0) return null
        return runCatching {
            androidx.core.content.res.ResourcesCompat.getDrawable(res, id, null)
        }.getOrNull()
    }

    // ---- answering -------------------------------------------------------

    /** The pack's own icon for a component, if it has one. */
    fun iconFor(component: ComponentName): Drawable? {
        if (resources == null || mapping.isEmpty()) return null
        val key = "ComponentInfo{${component.flattenToString()}}"
        return drawable(mapping[key])
    }

    /**
     * Make an app's own icon look like it belongs to the pack.
     *
     * Returns null when the pack offers no treatment, which is common: plenty
     * of packs theme a list of apps and say nothing about the rest. The caller
     * then falls back to the launcher's own shaping, which is the right answer
     * -- better a consistent launcher shape than a pack's half-applied one.
     */
    fun theme(source: Bitmap, size: Int, seed: Int): Bitmap? {
        if (resources == null) return null

        // Which plate, chosen from the app's own name rather than at random.
        //
        // A pack offering several backgrounds means them to be spread across
        // the home screen, and picking at random does that -- once. The result
        // is cached, so the choice looks stable until something empties the
        // cache, and then an app the user knows by its colour quietly becomes a
        // different colour. Seeding from the component gives the same spread
        // and the same answer every time.
        val back = backNames
            .takeIf { it.isNotEmpty() }
            ?.let { it[(seed.toLong().and(0xFFFFFFFFL) % it.size).toInt()] }
            ?.let { drawable(it) }
        val mask = drawable(maskName)
        val upon = drawable(uponName)
        if (back == null && mask == null && upon == null) return null

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val full = Rect(0, 0, size, size)

        back?.apply { bounds = full }?.draw(canvas)

        // The app's icon, shrunk by the pack's factor and centred. Packs
        // choose a scale because their background plate has a border and the
        // icon has to sit inside it.
        val inner = (size * scale).toInt().coerceIn(1, size)
        val offset = (size - inner) / 2
        val iconLayer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(iconLayer).drawBitmap(
            source,
            null,
            Rect(offset, offset, offset + inner, offset + inner),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )

        if (mask != null) {
            // DST_OUT: the mask's opaque pixels are the ones removed. That is
            // the convention these packs were written against, and getting the
            // polarity the wrong way round produces an icon that is exactly the
            // corners and nothing else -- which at least fails visibly.
            val cut = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val cutCanvas = Canvas(cut)
            mask.bounds = full
            mask.draw(cutCanvas)
            Canvas(iconLayer).drawBitmap(
                cut,
                0f,
                0f,
                Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) },
            )
        }

        canvas.drawBitmap(iconLayer, 0f, 0f, null)
        upon?.apply { bounds = full }?.draw(canvas)
        return out
    }

    private companion object {
        const val TAG = "IconPack"
    }
}
