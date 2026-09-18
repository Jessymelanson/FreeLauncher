package com.freelauncher.app.data

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.withSign

/**
 * Renders and caches app icons.
 *
 * Two jobs that look like one. Turning a Drawable into a masked Bitmap is
 * genuinely expensive -- an adaptive icon is two vector layers rasterised and
 * clipped -- and a drawer scrolling past two hundred apps would do it sixty
 * times a second without a cache in front of it.
 */
class IconCache(context: Context, private val iconDir: File) {

    private val appContext = context.applicationContext

    /**
     * How large to rasterise.
     *
     * Taken from the system rather than picked, because it already accounts for
     * screen density and for the manufacturer having decided icons are larger
     * than stock. Rendering smaller than this and scaling up is visibly soft on
     * the home screen; rendering much larger just spends memory.
     */
    val sizePx: Int = run {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.launcherLargeIconSize.coerceIn(96, 256)
    }

    /**
     * Bounded by bytes, not by entry count.
     *
     * An entry count is the wrong unit here: the same 200-icon cache is 12 MB
     * on a phone with small icons and 50 MB on a tablet with large ones, and
     * only one of those gets the app killed. An eighth of the available heap is
     * the ratio the platform's own icon caches use.
     */
    private val cache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 8).coerceIn(4L * 1024 * 1024, 48L * 1024 * 1024)).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Shape masks are size-invariant in construction but not in coordinates.
     *
     * Concurrent, because this is written from whichever IO threads happen to
     * be rasterising: [iconFor] and [customIcon] both hand off to
     * Dispatchers.IO, and a drawer opening for the first time puts a screenful
     * of icons through here at once. A plain HashMap resized from two threads
     * at the same moment loses entries or, on an unlucky interleaving, does
     * something considerably worse -- and it is rare enough that it would never
     * be reproduced on purpose.
     */
    private val pathCache = java.util.concurrent.ConcurrentHashMap<String, Path>()

    /** Smooths the rescale when a stored icon is not exactly [sizePx] across. */
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * Bumped whenever something is thrown out of the cache.
     *
     * Evicting a stale icon is only half of replacing it. The other half is
     * telling whoever is already drawing one, and there was nothing doing that:
     * an app that changed its icon in an update had its entries dropped here
     * and went on showing the old picture anyway, because the composable
     * holding it had its bitmap in hand and no reason to ask again. The
     * eviction was real and invisible, which is the worst of both -- the work
     * was redone on the next cold start and the user, who had just updated the
     * app, saw the old icon until they rebooted.
     *
     * A counter rather than a per-package signal: a redraw is a cache lookup,
     * and the cases that bump this -- a package changing, the shape setting
     * changing -- are rare and already redraw most of the screen.
     */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /**
     * The icon pack in force, if any.
     *
     * Held here rather than passed in per call because it belongs to the same
     * decision the cache is already keyed on: two renderings of one app under
     * two packs are two different bitmaps, and the key has to say which. Set
     * from a settings observer, which also empties the cache -- every entry in
     * it was rendered under the old answer.
     */
    @Volatile
    private var pack: IconPackLoader? = null

    private val packKey: String get() = pack?.packageName ?: ""

    /**
     * Load a pack, or none, off the calling thread.
     *
     * Parsing appfilter.xml is a few thousand XML elements out of another app's
     * resources, so this is a worker's job and the caller is expected to be one.
     */
    fun setIconPack(context: Context, packageName: String) {
        val wanted = packageName.takeIf { it.isNotEmpty() }
        if (wanted == pack?.packageName) return
        pack = wanted?.let { name ->
            IconPackLoader(context, name).takeIf { it.isUsable }
        }
        clear()
    }

    fun clear() {
        cache.evictAll()
        _generation.value++
    }

    /**
     * Drop one app's entries, when a package is updated or removed.
     *
     * Matched on the exact `package/` prefix. A looser test would also catch
     * every package that merely starts with the same characters, so removing
     * `com.example` would quietly evict `com.example2` as well -- harmless but
     * wasteful, and the sort of thing that is very hard to notice.
     *
     * Custom icon entries are keyed "file|..." and are deliberately untouched:
     * they are user overrides and have nothing to do with what the package
     * currently ships.
     */
    fun evictPackage(packageName: String) {
        val prefix = "$packageName/"
        val doomed = cache.snapshot().keys.filter { it.startsWith(prefix) }
        for (key in doomed) cache.remove(key)
        if (doomed.isNotEmpty()) _generation.value++
    }

    suspend fun iconFor(
        repo: AppRepository,
        entry: AppEntry,
        shape: IconShape,
    ): Bitmap? {
        val key = "${entry.key}|${shape.name}|$packKey"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            // Re-check inside the worker: several cells can ask for the same
            // icon in the same frame, and without this they each rasterise it.
            cache.get(key)?.let { return@withContext it }

            // The pack's own artwork for this app, if it has any, and drawn
            // exactly as the pack drew it.
            //
            // Deliberately not masked to the launcher's icon shape. A pack icon
            // is a finished piece of art with its own silhouette -- that is what
            // the user installed the pack for -- and clipping it to a circle
            // would cut the artwork rather than a background.
            val current = pack
            val themed = current?.let { p ->
                runCatching { p.iconFor(entry.component) }.getOrNull()
            }
            if (themed != null) {
                val bitmap = runCatching { rasterise(themed, sizePx) }.getOrNull()
                if (bitmap != null) {
                    cache.put(key, bitmap)
                    return@withContext bitmap
                }
            }

            val drawable = repo.iconFor(entry, appContext.resources.displayMetrics.densityDpi)
                ?: return@withContext null

            // The pack's treatment for apps it does not theme: its background
            // plate, its mask, its overlay. A pack with none of those returns
            // null and the launcher's own shaping takes over.
            val dressed = current?.let { p ->
                runCatching {
                    p.theme(
                        rasterise(drawable, sizePx),
                        sizePx,
                        entry.component.flattenToString().hashCode(),
                    )
                }.getOrNull()
            }
            if (dressed != null) {
                cache.put(key, dressed)
                return@withContext dressed
            }

            val bitmap = runCatching { render(drawable, shape) }.getOrElse {
                Log.w(TAG, "could not render ${entry.component}", it)
                null
            }
            if (bitmap != null) cache.put(key, bitmap)
            bitmap
        }
    }

    /**
     * An icon stored as a file, overriding whatever the system would give.
     *
     * Every stored icon is masked to the shape the user picked, with no
     * exceptions. Pinned shortcuts used to be drawn exactly as stored and sat
     * on the home screen as hard squares among circles; Nova imports were
     * exempted for longer, on the argument that Nova had already masked them
     * and a second mask would cut into artwork cropped once already. That
     * argument loses. The setting says what shape icons are, and an icon that
     * quietly opts out of it is a bug however good the reason.
     *
     * Masked at draw time rather than at capture time, so changing the setting
     * restyles what is already on the home screen instead of applying only to
     * whatever is added next.
     *
     * A mask can only remove pixels, so a stored icon that arrived already
     * cropped -- to a circle, or to the rounded tile a browser generates for a
     * site with no favicon -- has no corners to restore and cannot be clipped
     * into a square. [finish] handles that by putting a plate behind it rather
     * than by clipping harder.
     */
    suspend fun customIcon(iconKey: String, shape: IconShape): Bitmap? {
        val key = "file|$iconKey|${shape.name}"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            cache.get(key)?.let { return@withContext it }
            val file = File(iconDir, iconKey)
            if (!file.exists()) return@withContext null
            val stored = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                ?: return@withContext null
            val bitmap = runCatching { applyMask(stored, shape) }.getOrElse {
                Log.w(TAG, "could not mask stored icon $iconKey", it)
                stored
            }
            cache.put(key, bitmap)
            bitmap
        }
    }

    /**
     * Write an icon into the store and return the key that finds it again.
     *
     * The name is sanitised here rather than trusted, because the callers build
     * it out of things that are not filenames. A pinned shortcut's id is chosen
     * by the app that published it and a browser's is very often the page's
     * address -- so "pin_com.brave.browser_https://example.com/page.png" was
     * being handed to File(), which read the slashes as directories that do not
     * exist, threw, and was caught.
     *
     * What the user saw was every web shortcut wearing the browser's logo
     * instead of the site's favicon: the icon had failed to store, so there was
     * nothing to draw but the publisher's own icon -- which is the documented
     * fallback and looks exactly like a deliberate choice.
     *
     * A hash of the overflow rather than a truncation, so two shortcuts from
     * one app whose ids differ only past the cut cannot land on the same file
     * and quietly wear each other's icons.
     */
    fun writeCustomIcon(name: String, png: ByteArray): String? = runCatching {
        if (!iconDir.exists()) iconDir.mkdirs()
        val safe = safeFileName(name)
        File(iconDir, safe).writeBytes(png)
        safe
    }.getOrElse {
        Log.w(TAG, "could not store icon $name", it)
        null
    }

    /**
     * Take a picture the user picked and make an icon file out of it.
     *
     * Two things are done here rather than left to the mask that runs at draw
     * time, because a mask can only take pixels away.
     *
     * It is **sampled down while decoding**. A modern phone photo is a hundred
     * times the area of an icon, and decoding one at full size to throw away
     * 99% of it is how an app runs out of memory doing something trivial.
     *
     * It is **cropped square from the centre**. The mask assumes a square that
     * reaches its own edges; hand it a 4:3 photo and [finish] letterboxes the
     * picture and puts a plate behind the bands, which is not what anyone means
     * by choosing a photo as an icon. Cropping loses the sides and fills the
     * shape, which is what every other launcher does with this.
     */
    fun storeImage(resolver: android.content.ContentResolver, uri: android.net.Uri, name: String): String? =
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val smallest = minOf(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options().apply {
                // Powers of two only; decoders round up to one anyway and
                // saying so keeps the result predictable.
                var sample = 1
                while (smallest / (sample * 2) >= sizePx) sample *= 2
                inSampleSize = sample
            }
            val decoded = resolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return@runCatching null

            val side = minOf(decoded.width, decoded.height)
            val cropped = Bitmap.createBitmap(
                decoded,
                (decoded.width - side) / 2,
                (decoded.height - side) / 2,
                side,
                side,
            )
            val square = Bitmap.createScaledBitmap(cropped, sizePx, sizePx, true)

            val png = java.io.ByteArrayOutputStream().use { out ->
                square.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            writeCustomIcon(name, png)
        }.getOrElse {
            Log.w(TAG, "could not read the picked image", it)
            null
        }

    /**
     * Delete every stored icon that no item refers to.
     *
     * The icons directory is written to from four directions -- a Nova import,
     * a pinned shortcut, a legacy INSTALL_SHORTCUT broadcast, and a picture the
     * user chose -- and until now nothing ever deleted from it. Removing the
     * icon that owned a file, uninstalling into a dead icon, deleting a page,
     * or emptying a folder all left the file behind, and every one of those is
     * something people do repeatedly. A phone that has had a few Nova restores
     * carries every icon from every one of them for ever.
     *
     * Swept once at startup against the whole layout rather than hooked into
     * each removal, because the removals are the easy half to get wrong: a
     * folder taken off the home screen removes its children too, a page takes
     * everything on it, and a path that forgets is silent. One sweep over what
     * is actually referenced cannot forget, and cannot delete something still
     * in use.
     */
    fun pruneIcons(referenced: Set<String>) {
        val files = runCatching { iconDir.listFiles() }.getOrNull() ?: return
        var removed = 0
        for (file in files) {
            if (!file.isFile || file.name in referenced) continue
            if (runCatching { file.delete() }.getOrDefault(false)) removed++
        }
        if (removed > 0) Log.i(TAG, "removed $removed unused icon file(s)")
    }

    /**
     * Forget a stored icon, file and all.
     *
     * Called when a chosen icon is replaced or reset. Without it the icons
     * directory grows by one file every time somebody changes their mind, and
     * nothing ever looks at them again.
     */
    fun deleteCustomIcon(iconKey: String) {
        runCatching { File(iconDir, iconKey).delete() }
        val prefix = "file|$iconKey|"
        for (key in cache.snapshot().keys.filter { it.startsWith(prefix) }) cache.remove(key)
    }

    // ---- rendering -------------------------------------------------------

    private fun render(drawable: Drawable, shape: IconShape): Bitmap {
        val size = sizePx

        // An adaptive icon on the system shape is the one case the platform
        // finishes for us: draw() applies the device's own mask, at the
        // device's own scale. Nothing here can do it more correctly.
        if (shape == IconShape.SYSTEM && drawable is AdaptiveIconDrawable) {
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(out))
            return out
        }

        return finish(rasterise(drawable, size), shape)
    }

    /**
     * Take a flattened icon and make it the shape the user asked for.
     *
     * Masking alone is not enough, and one app in twenty proves it. A mask can
     * only remove pixels, so it turns a square into a circle and is powerless
     * the other way: an icon whose own artwork is a circle -- a legacy icon
     * drawn that way by its author, or an adaptive one whose background layer
     * is a circle on transparency rather than the full bleed the spec asks for
     * -- stays a circle under a square setting no matter how it is clipped. It
     * sits among square icons looking like the setting is broken.
     *
     * So when the artwork leaves the chosen shape's corners empty, a plate in
     * that shape is drawn behind it first, in a colour taken from the icon. The
     * silhouette then belongs to the launcher rather than to whoever drew the
     * icon, which is what the setting promises.
     */
    private fun finish(raw: Bitmap, shape: IconShape): Bitmap {
        val size = sizePx
        val mask = if (shape == IconShape.SYSTEM) systemMask(size) else maskPath(shape, size)

        // Where the artwork lands if it is drawn edge to edge, which is the
        // question the plate test has to be asked against: does the icon, at
        // full size, reach the edge of the shape the user picked.
        val full = fitRect(raw, size, 0)
        val plate = needsPlate(raw, full, mask, size)

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.clipPath(mask)

        // Artwork on a plate is inset slightly. A round logo sized to touch the
        // edges of a square would have its own edge land exactly on the
        // plate's, and that join reads as a rendering fault rather than a
        // design.
        val dest = if (plate) {
            canvas.drawColor(plateColour(raw))
            fitRect(raw, size, (size * PLATE_INSET).roundToInt())
        } else {
            full
        }
        canvas.drawBitmap(raw, null, dest, bitmapPaint)
        return out
    }

    /** Kept for the stored-icon path, which starts from a bitmap already. */
    private fun applyMask(source: Bitmap, shape: IconShape): Bitmap = finish(source, shape)

    /**
     * Where the artwork sits: scaled to fit, centred, never stretched.
     *
     * A pinned shortcut's icon is square and fills the space either way, but a
     * Nova import is whatever the icon pack shipped, and stretching one of
     * those to a square would be a far more obvious disfigurement than the
     * shaping this whole path exists to do.
     */
    private fun fitRect(source: Bitmap, size: Int, inset: Int): Rect {
        val room = size - inset * 2
        val scale = minOf(room.toFloat() / source.width, room.toFloat() / source.height)
        val w = (source.width * scale).roundToInt()
        val h = (source.height * scale).roundToInt()
        val left = (size - w) / 2
        val top = (size - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /**
     * Whether the artwork actually reaches the edge of the chosen shape.
     *
     * Walks the mask's own outline and asks, at each step, whether there is
     * anything painted just inside it. That is the real question, and probing
     * four corners was only ever an approximation of it -- one that a browser's
     * generated tile for a site with no favicon walks straight through. Those
     * tiles are rounded squares, so their corners are opaque where a corner
     * probe looks, and clipping a 20% rounded tile with a 6% rounded square
     * changes nothing at all: the shape setting appeared to be ignored.
     *
     * Walking the outline asks the question for every shape at once, with no
     * per-shape special cases. A circular icon under a square mask fails at the
     * corners. A rounded tile under a square mask fails at the corners by less,
     * and still fails. A full-bleed square under a circle mask passes, because
     * a circle's outline is well inside it. And an icon under its own shape
     * passes, which is what stops a round icon being plated on a round setting.
     *
     * Each sample steps in from the boundary before it reads, so the artwork's
     * own antialiased edge is not mistaken for a hole, and a sample that falls
     * outside the artwork entirely counts as empty -- which is how a
     * letterboxed non-square icon earns a plate rather than two bare bands.
     */
    private fun needsPlate(source: Bitmap, dest: Rect, mask: Path, size: Int): Boolean {
        if (source.width < 8 || source.height < 8) return false
        val measure = PathMeasure(mask, true)
        val length = measure.length
        if (length <= 0f) return false

        val centre = size / 2f
        val point = FloatArray(2)
        var empty = 0
        var taken = 0
        for (i in 0 until EDGE_SAMPLES) {
            if (!measure.getPosTan(length * i / EDGE_SAMPLES, point, null)) continue
            taken++
            val x = point[0] + (centre - point[0]) * EDGE_INSET
            val y = point[1] + (centre - point[1]) * EDGE_INSET
            if (x < dest.left || x >= dest.right || y < dest.top || y >= dest.bottom) {
                empty++
                continue
            }
            val sx = ((x - dest.left) / dest.width() * source.width)
                .roundToInt().coerceIn(0, source.width - 1)
            val sy = ((y - dest.top) / dest.height() * source.height)
                .roundToInt().coerceIn(0, source.height - 1)
            if ((source.getPixel(sx, sy) ushr 24) < CORNER_ALPHA) empty++
        }
        return taken > 0 && empty > taken * EDGE_TOLERANCE
    }

    /**
     * What colour to put behind the artwork.
     *
     * Sampled from a ring just inside the icon's own edge, which for the icons
     * this applies to is the circle's fill: the plate then continues the icon's
     * own colour out to the corners and the join disappears. White is the
     * fallback, and is used whenever that ring is not one colour -- a gradient
     * or a photo has no single colour to extend, and guessing at one produces a
     * worse result than the neutral the platform itself uses for legacy icons.
     */
    private fun plateColour(bitmap: Bitmap): Int {
        val cx = bitmap.width / 2f
        val cy = bitmap.height / 2f
        val radius = minOf(cx, cy) * RING_RADIUS
        val counts = HashMap<Int, Int>()
        var opaque = 0
        for (i in 0 until RING_SAMPLES) {
            val angle = i.toDouble() / RING_SAMPLES * 2.0 * Math.PI
            val x = (cx + cos(angle) * radius).roundToInt().coerceIn(0, bitmap.width - 1)
            val y = (cy + sin(angle) * radius).roundToInt().coerceIn(0, bitmap.height - 1)
            val pixel = bitmap.getPixel(x, y)
            if ((pixel ushr 24) < 200) continue
            opaque++
            // Quantised before counting. Antialiasing and JPEG-ish artefacts
            // mean a flat fill is never bit-identical across a ring, and an
            // exact histogram would find eighty colours in one solid circle.
            val key = pixel and 0xFFF0F0F0.toInt()
            counts[key] = (counts[key] ?: 0) + 1
        }
        if (opaque < RING_SAMPLES / 2) return android.graphics.Color.WHITE
        val best = counts.maxByOrNull { it.value } ?: return android.graphics.Color.WHITE
        if (best.value < opaque * RING_AGREEMENT) return android.graphics.Color.WHITE
        return best.key or 0xFF000000.toInt()
    }

    /**
     * The shape this device gives its own adaptive icons.
     *
     * [buildMask] answers SYSTEM with a plain square, which is right where it
     * is used: an AdaptiveIconDrawable applies the platform mask itself when it
     * draws, so clipping it again would be redundant. A stored shortcut icon is
     * a flat bitmap with no such opinion, so for it "system" has to be asked
     * for outright -- and an empty AdaptiveIconDrawable exists to be asked.
     *
     * Falls back to the 22% rounded rectangle if the platform declines, which
     * is the shape stock Android has used since adaptive icons arrived.
     */
    private fun systemMask(size: Int): Path {
        val key = "SYSTEM_MASK|$size"
        pathCache[key]?.let { return it }
        val path = runCatching {
            val probe = AdaptiveIconDrawable(null, null)
            probe.setBounds(0, 0, size, size)
            Path(probe.iconMask)
        }.getOrElse {
            Log.w(TAG, "no platform icon mask available", it)
            buildMask(IconShape.ROUNDED, size.toFloat())
        }
        pathCache[key] = path
        return path
    }

    private fun maskPath(shape: IconShape, size: Int): Path {
        val key = "${shape.name}|$size"
        pathCache[key]?.let { return it }
        val path = buildMask(shape, size.toFloat())
        pathCache[key] = path
        return path
    }

    private fun buildMask(shape: IconShape, s: Float): Path = Path().apply {
        when (shape) {
            IconShape.CIRCLE -> addCircle(s / 2f, s / 2f, s / 2f, Path.Direction.CW)

            IconShape.ROUNDED -> addRoundRect(0f, 0f, s, s, s * 0.22f, s * 0.22f, Path.Direction.CW)

            IconShape.SQUARE -> addRoundRect(0f, 0f, s, s, s * 0.06f, s * 0.06f, Path.Direction.CW)

            // A real superellipse, |x|^4 + |y|^4 = 1, rather than a rounded
            // rectangle with a large radius. The difference is the whole point
            // of the shape: a rounded rect has a sudden change of curvature
            // where the arc meets the straight edge, and at icon sizes that
            // reads as a slightly lumpy circle.
            IconShape.SQUIRCLE -> {
                val r = s / 2f
                val steps = 96
                for (i in 0..steps) {
                    val t = i.toDouble() / steps * 2.0 * Math.PI
                    val c = cos(t)
                    val n = sin(t)
                    val x = c.absoluteValue.pow(0.5).withSign(c).toFloat() * r + r
                    val y = n.absoluteValue.pow(0.5).withSign(n).toFloat() * r + r
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }

            IconShape.SYSTEM -> addRect(0f, 0f, s, s, Path.Direction.CW)
        }
    }

    companion object {
        private const val TAG = "IconCache"

        /**
         * An adaptive icon is 108 units wide with only the middle 72 guaranteed
         * visible; the outer quarter on each side is there so a launcher can
         * shift the layers for parallax. Filling a square with the visible
         * region means drawing at 1.5x and hanging this much off each edge.
         */
        private const val ADAPTIVE_INSET = 0.25f

        /** How many points are taken around the mask's outline. */
        private const val EDGE_SAMPLES = 64

        /** How far each sample steps in from the outline before it reads. */
        private const val EDGE_INSET = 0.07f

        /** Fraction of the outline that may be empty before a plate is added. */
        private const val EDGE_TOLERANCE = 0.06f

        /** Below this alpha a corner counts as empty. */
        private const val CORNER_ALPHA = 40

        /** Breathing room between artwork and the edge of its plate. */
        private const val PLATE_INSET = 0.06f

        /** Where the colour ring is sampled, as a fraction of the radius. */
        private const val RING_RADIUS = 0.92f

        private const val RING_SAMPLES = 72

        /** How much of the ring must agree before its colour is trusted. */
        private const val RING_AGREEMENT = 0.7f

        /**
         * Names icons that came out of a Nova backup.
         *
         * A naming convention only. It used to decide whether an icon was
         * masked, and no longer does -- everything stored is masked to the
         * user's shape -- but keeping the source legible in the filename is
         * worth the constant on its own when reading a crash report or an
         * icons directory by hand.
         */
        const val NOVA_PREFIX = "nova_"

        /**
         * A drawable as PNG bytes, ready for [writeCustomIcon].
         *
         * Used by the pin flow: a shortcut a browser adds carries its own icon
         * -- a site's favicon -- which belongs to no installed package and can
         * therefore never be looked up again. If it is not stored at the moment
         * it arrives, it is gone.
         */
        /**
         * A name a filesystem will accept, from a name chosen by another app.
         *
         * Everything outside a conservative set becomes an underscore, and
         * anything long enough to risk the 255-byte limit keeps a readable
         * head and gets a hash of the whole for its tail.
         */
        fun safeFileName(name: String): String {
            val cleaned = name.map { c ->
                if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_'
            }.joinToString("")
            if (cleaned.length <= 120) return cleaned.ifEmpty { "icon_${name.hashCode()}.png" }

            val suffix = cleaned.substringAfterLast('.', "").take(8)
            val stem = cleaned.take(100)
            return "$stem-${name.hashCode().toUInt().toString(16)}${if (suffix.isEmpty()) "" else ".$suffix"}"
        }

        fun toPng(drawable: Drawable, size: Int): ByteArray? = runCatching {
            val bitmap = rasterise(drawable, size)
            java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }.getOrNull()

        /**
         * A drawable flattened to a full-bleed square, ready to be masked later.
         *
         * Not the same job as [toBitmap], which hands back whatever a
         * BitmapDrawable is already carrying, at whatever size that happens to
         * be. What gets stored has to be a known size and has to cover the whole
         * square, because a mask applied to it afterwards can only ever remove
         * pixels ; anything short of the edges shows up as a clipped icon
         * floating inside its own shape.
         *
         * An adaptive icon is drawn as its two layers at 1.5x, hung a quarter
         * off each edge, rather than by calling draw() on it. draw() would apply
         * the platform's own mask, baking today's system shape into a file that
         * outlives the setting -- which is the thing this whole path exists to
         * avoid.
         */
        fun rasterise(drawable: Drawable, size: Int): Bitmap {
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            if (drawable is AdaptiveIconDrawable) {
                val inset = (size * ADAPTIVE_INSET).toInt()
                val l = -inset
                val t = -inset
                val r = size + inset
                val b = size + inset
                drawable.background?.apply { setBounds(l, t, r, b) }?.draw(canvas)
                drawable.foreground?.apply { setBounds(l, t, r, b) }?.draw(canvas)
            } else {
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
            }
            return out
        }

        /** Bitmap out of any drawable, for callers that do not want the cache. */
        fun toBitmap(drawable: Drawable, size: Int): Bitmap {
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                return drawable.bitmap
            }
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            return out
        }
    }
}
