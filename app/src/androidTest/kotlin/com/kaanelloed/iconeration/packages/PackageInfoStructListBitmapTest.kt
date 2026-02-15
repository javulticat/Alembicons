package com.kaanelloed.iconeration.packages

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kaanelloed.iconeration.drawable.DrawableExtension
import com.kaanelloed.iconeration.icon.EmptyIcon
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

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
}
