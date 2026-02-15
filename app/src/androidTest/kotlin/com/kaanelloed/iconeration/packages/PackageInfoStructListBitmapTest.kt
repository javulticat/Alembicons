package com.kaanelloed.iconeration.packages

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kaanelloed.iconeration.drawable.DrawableExtension
import com.kaanelloed.iconeration.drawable.DrawableExtension.Companion.sizeIsGreaterThanZero
import com.kaanelloed.iconeration.icon.EmptyIcon
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests for PackageInfoStruct.listBitmap lazy caching.
 *
 * The previous implementation used Compose's remember() inside LazyColumn items
 * to cache shrunk bitmaps. That cache was discarded every time an item scrolled
 * off-screen, causing shrinkIfBiggerThan() to reallocate bitmaps on every scroll
 * cycle. With 500+ apps this produced 23-26MB of GC churn per cycle, blocking the
 * main thread for 44-58ms and skipping 44-46 frames.
 *
 * The fix moves caching to a lazy property on PackageInfoStruct so the bitmap is
 * computed once and survives composable disposal. These tests verify that behavior.
 */
@RunWith(AndroidJUnit4::class)
class PackageInfoStructListBitmapTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun createDrawableWithSize(width: Int, height: Int): BitmapDrawable {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun createPackageInfoStruct(
        iconWidth: Int = 512,
        iconHeight: Int = 512,
        packageName: String = "com.example.app",
        activityName: String = ".MainActivity"
    ): PackageInfoStruct {
        return PackageInfoStruct(
            appName = "Test App",
            packageName = packageName,
            activityName = activityName,
            icon = createDrawableWithSize(iconWidth, iconHeight),
            iconID = 0
        )
    }

    // --- Core caching behavior: the fix for the stuttering issue ---

    @Test
    fun listBitmap_returnsSameInstanceOnRepeatedAccess() {
        // This is the core property that fixes the stuttering. The old remember()-based
        // cache was lost on every LazyColumn composable disposal. The lazy property
        // must return the exact same Bitmap object on every access.
        val app = createPackageInfoStruct()

        val first = app.listBitmap
        val second = app.listBitmap
        val third = app.listBitmap

        assertSame(
            "listBitmap must return the same Bitmap instance — reallocation here caused the GC churn",
            first, second
        )
        assertSame(first, third)
    }

    @Test
    fun listBitmap_noNewAllocationsOnRepeatedAccess() {
        // Simulates what happens during scrolling: listBitmap is accessed every time
        // the item composes. With the old remember() approach, each scroll-in produced
        // a new Bitmap. With lazy, every access after the first must be free.
        val app = createPackageInfoStruct(iconWidth = 1024, iconHeight = 1024)

        val baseline = app.listBitmap
        // Access 100 times, simulating 100 scroll-in/scroll-out cycles
        repeat(100) {
            assertSame(
                "Access #$it must return the cached instance, not a new allocation",
                baseline, app.listBitmap
            )
        }
    }

    @Test
    fun listBitmap_respectsMaxIconListSize() {
        val app = createPackageInfoStruct(iconWidth = 2000, iconHeight = 2000)

        val bitmap = app.listBitmap

        assertTrue(
            "listBitmap width (${bitmap.width}) must be <= MAX_ICON_LIST_SIZE",
            bitmap.width <= DrawableExtension.MAX_ICON_LIST_SIZE
        )
        assertTrue(
            "listBitmap height (${bitmap.height}) must be <= MAX_ICON_LIST_SIZE",
            bitmap.height <= DrawableExtension.MAX_ICON_LIST_SIZE
        )
    }

    @Test
    fun listBitmap_cachedBitmapRemainsValidForRendering() {
        // The cached bitmap must stay valid across many render cycles (simulating
        // the item scrolling in and out of view repeatedly).
        val app = createPackageInfoStruct()
        val cached = app.listBitmap

        val target = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)

        repeat(50) {
            assertFalse("Cached bitmap must not be recycled at render #$it", cached.isRecycled)
            canvas.drawBitmap(cached, 0f, 0f, null)
        }

        target.recycle()
    }

    // --- Behavior with 500+ items (the scenario that caused the issue) ---

    @Test
    fun listBitmap_manyInstances_eachCachesIndependently() {
        // Simulate 500 installed apps. Each must cache its own bitmap without
        // interfering with others.
        val apps = (0 until 500).map { i ->
            createPackageInfoStruct(
                packageName = "com.example.app$i",
                activityName = ".Main$i"
            )
        }

        // First access: triggers lazy evaluation for each
        val bitmaps = apps.map { it.listBitmap }

        // Second access: must return the exact same cached instances
        for (i in apps.indices) {
            assertSame(
                "App $i: second access must return cached instance",
                bitmaps[i], apps[i].listBitmap
            )
        }
    }

    @Test
    fun listBitmap_manyInstances_simulatedScrollReusesCache() {
        // Simulate scrolling through 500 apps: access items in sliding windows
        // of ~20 items (like LazyColumn's visible window), scrolling forward then
        // backward. Every access must return the cached bitmap.
        val apps = (0 until 500).map { i ->
            createPackageInfoStruct(
                iconWidth = 300,
                iconHeight = 300,
                packageName = "com.example.app$i",
                activityName = ".Main$i"
            )
        }

        // Warm up: first access for all items
        val cachedBitmaps = apps.map { it.listBitmap }

        val windowSize = 20

        // Scroll forward
        for (start in 0 until apps.size - windowSize step 5) {
            for (i in start until start + windowSize) {
                assertSame(
                    "Forward scroll: app $i must reuse cached bitmap",
                    cachedBitmaps[i], apps[i].listBitmap
                )
            }
        }

        // Scroll backward (revisiting items that previously "left the viewport")
        for (start in (apps.size - windowSize) downTo 0 step 5) {
            for (i in start until start + windowSize) {
                assertSame(
                    "Backward scroll: app $i must reuse cached bitmap",
                    cachedBitmaps[i], apps[i].listBitmap
                )
            }
        }
    }

    // --- Interaction with changeExport() ---

    @Test
    fun listBitmap_afterChangeExport_newInstanceStillCaches() {
        // changeExport() creates a new PackageInfoStruct (for editing an icon).
        // The new instance has a fresh lazy, but it must still cache on repeated access.
        val original = createPackageInfoStruct()
        val edited = original.changeExport(EmptyIcon())

        val first = edited.listBitmap
        val second = edited.listBitmap

        assertSame(
            "listBitmap on edited instance must cache just like the original",
            first, second
        )
    }

    @Test
    fun listBitmap_afterChangeExport_dimensionsMatchOriginal() {
        // changeExport() doesn't change the icon drawable, so the shrunk bitmap
        // dimensions should be identical.
        val original = createPackageInfoStruct(iconWidth = 800, iconHeight = 400)
        val edited = original.changeExport(EmptyIcon())

        assertEquals(original.listBitmap.width, edited.listBitmap.width)
        assertEquals(original.listBitmap.height, edited.listBitmap.height)
    }

    @Test
    fun listBitmap_afterChangeExport_reusesSameBitmapInstance() {
        // changeExport() does not change the icon drawable, so it must pass the
        // already-computed listBitmap to the new instance. Without this, refreshIcons()
        // on 500+ apps re-allocates every bitmap, causing OOM.
        val original = createPackageInfoStruct(iconWidth = 512, iconHeight = 512)

        // Trigger lazy init on the original
        val originalBitmap = original.listBitmap

        // changeExport should carry the bitmap forward
        val edited = original.changeExport(EmptyIcon())

        assertSame(
            "changeExport must reuse the original's listBitmap to avoid OOM during bulk refresh",
            originalBitmap, edited.listBitmap
        )
    }

    @Test
    fun listBitmap_afterMultipleChangeExports_reusesSameBitmapInstance() {
        // Simulate multiple edits (e.g., refreshIcons followed by loadAlchemiconPack).
        // The bitmap must be carried through every changeExport call.
        val original = createPackageInfoStruct(iconWidth = 1024, iconHeight = 1024)
        val originalBitmap = original.listBitmap

        val edited1 = original.changeExport(EmptyIcon())
        val edited2 = edited1.changeExport(EmptyIcon())
        val edited3 = edited2.changeExport(EmptyIcon())

        assertSame("First changeExport must reuse bitmap", originalBitmap, edited1.listBitmap)
        assertSame("Second changeExport must reuse bitmap", originalBitmap, edited2.listBitmap)
        assertSame("Third changeExport must reuse bitmap", originalBitmap, edited3.listBitmap)
    }

    @Test
    fun listBitmap_bulkChangeExport_noNewAllocations() {
        // Simulate the refreshIcons pattern: 500 apps all go through changeExport.
        // Every new instance must share the same bitmap as its original.
        val apps = (0 until 500).map { i ->
            createPackageInfoStruct(
                packageName = "com.example.app$i",
                activityName = ".Main$i"
            )
        }

        // Pre-warm originals (like initializeApplications does)
        val originalBitmaps = apps.map { it.listBitmap }

        // Bulk changeExport (like refreshIcons does)
        val editedApps = apps.map { it.changeExport(EmptyIcon()) }

        for (i in apps.indices) {
            assertSame(
                "App $i: changeExport must reuse bitmap, not allocate a new one",
                originalBitmaps[i], editedApps[i].listBitmap
            )
        }
    }

    // --- Pre-warming tests ---
    // ApplicationProvider.initializeApplications() now pre-warms listBitmap on a
    // background thread so the lazy init doesn't happen on the main/composition
    // thread during scroll. These tests verify that pre-warming behavior.

    @Test
    fun preWarm_backgroundThreadInitMakesCacheAvailableOnMainThread() {
        // Simulates the pre-warming pattern: a background thread accesses listBitmap
        // first, then the main thread should get the same cached instance.
        val app = createPackageInfoStruct(iconWidth = 512, iconHeight = 512)

        var backgroundBitmap: Bitmap? = null
        val latch = CountDownLatch(1)

        // Pre-warm on a background thread (like Dispatchers.Default)
        Thread {
            backgroundBitmap = app.listBitmap
            latch.countDown()
        }.start()

        assertTrue("Background thread should complete", latch.await(5, TimeUnit.SECONDS))

        // Main thread access should return the exact same cached instance
        val mainThreadBitmap = app.listBitmap
        assertSame(
            "Main thread must get the same bitmap that was pre-warmed on the background thread",
            backgroundBitmap, mainThreadBitmap
        )
    }

    @Test
    fun preWarm_allItemsInListAreReadyAfterBackgroundInit() {
        // Simulates preWarmListBitmaps: iterate all apps on a background thread,
        // then verify every item's listBitmap is already resolved.
        val apps = (0 until 100).map { i ->
            createPackageInfoStruct(
                packageName = "com.example.app$i",
                activityName = ".Main$i"
            )
        }

        val preWarmed = arrayOfNulls<Bitmap>(apps.size)
        val latch = CountDownLatch(1)

        // Pre-warm on background thread
        Thread {
            for ((i, app) in apps.withIndex()) {
                if (app.icon.sizeIsGreaterThanZero()) {
                    preWarmed[i] = app.listBitmap
                }
            }
            latch.countDown()
        }.start()

        assertTrue("Pre-warming should complete", latch.await(10, TimeUnit.SECONDS))

        // Verify all items return their pre-warmed instances
        for ((i, app) in apps.withIndex()) {
            assertSame(
                "App $i: listBitmap must return the pre-warmed instance",
                preWarmed[i], app.listBitmap
            )
        }
    }

    @Test
    fun preWarm_changeExportItemsCanBePreWarmedBeforeBatchEdit() {
        // Simulates preWarmEditBitmaps: new PackageInfoStruct instances from
        // changeExport() are pre-warmed before editApplicationsBatch makes them
        // visible to the UI.
        val originals = (0 until 50).map { i ->
            createPackageInfoStruct(
                packageName = "com.example.app$i",
                activityName = ".Main$i"
            )
        }

        // Create edited versions (like loadAlchemiconPack does)
        val edits = originals.mapIndexed { index, app ->
            index to app.changeExport(EmptyIcon())
        }

        val preWarmed = mutableMapOf<Int, Bitmap>()
        val latch = CountDownLatch(1)

        // Pre-warm the new items on background thread
        Thread {
            for ((index, newApp) in edits) {
                if (newApp.icon.sizeIsGreaterThanZero()) {
                    preWarmed[index] = newApp.listBitmap
                }
            }
            latch.countDown()
        }.start()

        assertTrue("Pre-warming edits should complete", latch.await(10, TimeUnit.SECONDS))

        // After "editApplicationsBatch", the new items' bitmaps are already cached
        for ((index, newApp) in edits) {
            assertSame(
                "Edited app $index: listBitmap must be pre-warmed",
                preWarmed[index], newApp.listBitmap
            )
            assertFalse(
                "Edited app $index: pre-warmed bitmap must be valid",
                newApp.listBitmap.isRecycled
            )
        }
    }

    @Test
    fun preWarm_concurrentAccessDuringWarmUpIsSafe() {
        // Verify thread safety: if the main thread accesses listBitmap while the
        // background thread is pre-warming the same item, both get the same result.
        val app = createPackageInfoStruct(iconWidth = 1024, iconHeight = 1024)

        var bgResult: Bitmap? = null
        var mainResult: Bitmap? = null
        val bgStarted = CountDownLatch(1)
        val latch = CountDownLatch(1)

        // Background thread accesses listBitmap
        Thread {
            bgStarted.countDown()
            bgResult = app.listBitmap
            latch.countDown()
        }.start()

        // Main thread also accesses listBitmap concurrently
        bgStarted.await(1, TimeUnit.SECONDS)
        mainResult = app.listBitmap

        assertTrue("Background thread should complete", latch.await(5, TimeUnit.SECONDS))

        assertSame(
            "Concurrent access must return the same instance (lazy is thread-safe)",
            bgResult, mainResult
        )
    }
}
