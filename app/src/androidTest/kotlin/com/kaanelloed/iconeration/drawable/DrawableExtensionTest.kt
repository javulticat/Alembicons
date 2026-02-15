package com.kaanelloed.iconeration.drawable

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kaanelloed.iconeration.drawable.DrawableExtension.Companion.shrinkIfBiggerThan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for DrawableExtension.shrinkIfBiggerThan().
 * These tests verify the fix for the "Canvas: trying to draw too large bitmap" crash
 * that occurs when scrolling through the app icon list with 500+ apps installed.
 */
@RunWith(AndroidJUnit4::class)
class DrawableExtensionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun createDrawableWithSize(width: Int, height: Int): BitmapDrawable {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return BitmapDrawable(context.resources, bitmap)
    }

    @Test
    fun shrinkIfBiggerThan_scalesDownOversizedSquareDrawable() {
        val drawable = createDrawableWithSize(2000, 2000)
        val maxSize = 256

        val result = drawable.shrinkIfBiggerThan(maxSize)

        assertEquals(maxSize, result.width)
        assertEquals(maxSize, result.height)
    }

    @Test
    fun shrinkIfBiggerThan_scalesDownOversizedWideDrawable() {
        val drawable = createDrawableWithSize(4000, 2000)
        val maxSize = 256

        val result = drawable.shrinkIfBiggerThan(maxSize)

        // The larger dimension (width=4000) should be scaled to maxSize
        assertEquals(maxSize, result.width)
        // Height should scale proportionally: 2000 * (256/4000) = 128
        assertEquals(128, result.height)
    }

    @Test
    fun shrinkIfBiggerThan_scalesDownOversizedTallDrawable() {
        val drawable = createDrawableWithSize(2000, 4000)
        val maxSize = 256

        val result = drawable.shrinkIfBiggerThan(maxSize)

        // Width should scale proportionally: 2000 * (256/4000) = 128
        assertEquals(128, result.width)
        // The larger dimension (height=4000) should be scaled to maxSize
        assertEquals(maxSize, result.height)
    }

    @Test
    fun shrinkIfBiggerThan_doesNotScaleSmallDrawable() {
        val drawable = createDrawableWithSize(100, 100)
        val maxSize = 256

        val result = drawable.shrinkIfBiggerThan(maxSize)

        assertEquals(100, result.width)
        assertEquals(100, result.height)
    }

    @Test
    fun shrinkIfBiggerThan_doesNotScaleExactSizeDrawable() {
        val drawable = createDrawableWithSize(256, 256)
        val maxSize = 256

        val result = drawable.shrinkIfBiggerThan(maxSize)

        assertEquals(256, result.width)
        assertEquals(256, result.height)
    }

    @Test
    fun shrinkIfBiggerThan_resultCanBeDrawnOnCanvas() {
        // This directly tests the crash scenario: a very large drawable that would
        // exceed Canvas limits should be scaled down enough to draw successfully.
        val drawable = createDrawableWithSize(7152, 7152)
        val maxSize = DrawableExtension.MAX_ICON_LIST_SIZE

        val result = drawable.shrinkIfBiggerThan(maxSize)

        // Verify the bitmap is small enough for Canvas
        val bitmapByteCount = result.allocationByteCount
        assertTrue(
            "Bitmap size ($bitmapByteCount bytes) should be well under Canvas limit",
            bitmapByteCount < 100_000_000
        )

        // Verify it can actually be drawn on a Canvas without crashing
        val targetBitmap = Bitmap.createBitmap(maxSize, maxSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(targetBitmap)
        canvas.drawBitmap(result, 0f, 0f, null)
        targetBitmap.recycle()
        result.recycle()
    }

    @Test
    fun shrinkIfBiggerThan_preservesAspectRatio() {
        val drawable = createDrawableWithSize(3000, 1500)
        val maxSize = 300

        val result = drawable.shrinkIfBiggerThan(maxSize)

        // Original aspect ratio is 2:1, so the result should maintain that
        val ratio = result.width.toFloat() / result.height.toFloat()
        assertEquals(2.0f, ratio, 0.01f)
    }

    @Test
    fun shrinkIfBiggerThan_handlesOneDimensionOverLimit() {
        // Only width exceeds the limit
        val drawable = createDrawableWithSize(512, 100)
        val maxSize = 256

        val result = drawable.shrinkIfBiggerThan(maxSize)

        // Width should be scaled to maxSize
        assertEquals(maxSize, result.width)
        // Height should scale proportionally: 100 * (256/512) = 50
        assertEquals(50, result.height)
    }

    // --- Bitmap caching safety tests ---
    // PackageInfoStruct.listBitmap caches the result of shrinkIfBiggerThan() via
    // a lazy property, so the bitmap survives LazyColumn composable disposal during
    // scrolling. These tests verify that caching is safe: the function is
    // deterministic and produces consistent results for the same drawable input.

    @Test
    fun shrinkIfBiggerThan_producesConsistentDimensionsOnRepeatedCalls() {
        // Verifies that the lazy-cached listBitmap produces stable dimensions:
        // the function must be deterministic for the same drawable input
        val drawable = createDrawableWithSize(2000, 1500)
        val maxSize = DrawableExtension.MAX_ICON_LIST_SIZE

        val result1 = drawable.shrinkIfBiggerThan(maxSize)
        val result2 = drawable.shrinkIfBiggerThan(maxSize)
        val result3 = drawable.shrinkIfBiggerThan(maxSize)

        assertEquals("Width should be consistent across calls", result1.width, result2.width)
        assertEquals("Width should be consistent across calls", result2.width, result3.width)
        assertEquals("Height should be consistent across calls", result1.height, result2.height)
        assertEquals("Height should be consistent across calls", result2.height, result3.height)

        result1.recycle()
        result2.recycle()
        result3.recycle()
    }

    @Test
    fun shrinkIfBiggerThan_cachedResultIsSafeToReuseForRendering() {
        // Verifies a cached bitmap (like PackageInfoStruct.listBitmap) can be
        // drawn repeatedly without issues — simulating LazyColumn scroll cycles
        val drawable = createDrawableWithSize(1024, 1024)
        val maxSize = DrawableExtension.MAX_ICON_LIST_SIZE

        val cached = drawable.shrinkIfBiggerThan(maxSize)

        // Draw the same cached bitmap 10 times (simulating scroll recompositions)
        val target = Bitmap.createBitmap(maxSize, maxSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        repeat(10) {
            canvas.drawBitmap(cached, 0f, 0f, null)
        }

        assertFalse("Cached bitmap should not be recycled after repeated use", cached.isRecycled)

        target.recycle()
        cached.recycle()
    }

    @Test
    fun shrinkIfBiggerThan_eachCallAllocatesNewBitmap() {
        // Verifies that without caching, each call creates a new allocation.
        // This is the problem that PackageInfoStruct.listBitmap (lazy) solves.
        val drawable = createDrawableWithSize(500, 500)
        val maxSize = DrawableExtension.MAX_ICON_LIST_SIZE

        val result1 = drawable.shrinkIfBiggerThan(maxSize)
        val result2 = drawable.shrinkIfBiggerThan(maxSize)

        // They're different Bitmap objects (new allocation each time)
        assertFalse(
            "Without caching, each call should produce a distinct Bitmap object",
            result1 === result2
        )

        // But with same content dimensions
        assertEquals(result1.width, result2.width)
        assertEquals(result1.height, result2.height)

        result1.recycle()
        result2.recycle()
    }

    @Test
    fun shrinkIfBiggerThan_smallDrawableProducesConsistentResults() {
        // Even drawables under the threshold should behave consistently for caching
        val drawable = createDrawableWithSize(100, 100)
        val maxSize = DrawableExtension.MAX_ICON_LIST_SIZE

        val result1 = drawable.shrinkIfBiggerThan(maxSize)
        val result2 = drawable.shrinkIfBiggerThan(maxSize)

        assertEquals(result1.width, result2.width)
        assertEquals(result1.height, result2.height)
        assertEquals("Small drawable should pass through at original size", 100, result1.width)

        result1.recycle()
        result2.recycle()
    }

    @Test
    fun maxIconListSize_isReasonableForDisplayUse() {
        val maxSize = DrawableExtension.MAX_ICON_LIST_SIZE

        // Should be large enough for crisp display at 78dp (typical xxhdpi = 3x = 234px)
        assertTrue("MAX_ICON_LIST_SIZE should be >= 234 for xxhdpi displays", maxSize >= 234)

        // Should be small enough to avoid memory issues with many icons
        // At 256x256 ARGB_8888 = 256KB per bitmap. Even 500 icons = 128MB which is safe.
        val bytesPerIcon = maxSize * maxSize * 4L // ARGB_8888 = 4 bytes per pixel
        val totalFor500Icons = bytesPerIcon * 500
        assertTrue(
            "500 icons at MAX_ICON_LIST_SIZE should use < 200MB (actual: ${totalFor500Icons / 1_000_000}MB)",
            totalFor500Icons < 200_000_000
        )
    }
}
