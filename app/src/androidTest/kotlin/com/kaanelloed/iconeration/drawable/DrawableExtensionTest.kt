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
