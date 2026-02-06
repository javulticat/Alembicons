package com.kaanelloed.iconeration.apk

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for IconPackBuilder, focusing on memory-efficient batch processing.
 */
class IconPackBuilderTest {

    @Test
    fun `batch size constant is positive and reasonable`() {
        // Batch size should be positive
        assertTrue("Batch size should be positive", IconPackBuilder.ICON_BATCH_SIZE > 0)

        // Batch size should be small enough to prevent OOM but large enough for efficiency
        // With ~1-5MB per bitmap, 50 icons would use 50-250MB which is reasonable
        assertTrue("Batch size should be <= 100 for memory safety", IconPackBuilder.ICON_BATCH_SIZE <= 100)
        assertTrue("Batch size should be >= 10 for efficiency", IconPackBuilder.ICON_BATCH_SIZE >= 10)
    }

    @Test
    fun `chunked processing covers all items`() {
        // Simulate a list of apps like buildAndSign would process
        val apps = (1..500).toList()
        val batchSize = IconPackBuilder.ICON_BATCH_SIZE

        val processedItems = mutableListOf<Int>()
        for (batch in apps.chunked(batchSize)) {
            processedItems.addAll(batch)
        }

        assertEquals("All items should be processed", apps, processedItems)
    }

    @Test
    fun `chunked processing handles exact multiple of batch size`() {
        val batchSize = IconPackBuilder.ICON_BATCH_SIZE
        val apps = (1..(batchSize * 3)).toList() // Exactly 3 batches

        val batches = apps.chunked(batchSize)

        assertEquals("Should have exactly 3 batches", 3, batches.size)
        batches.forEach { batch ->
            assertEquals("Each batch should have full batch size", batchSize, batch.size)
        }
    }

    @Test
    fun `chunked processing handles remainder`() {
        val batchSize = IconPackBuilder.ICON_BATCH_SIZE
        val apps = (1..(batchSize * 2 + 7)).toList() // 2 full batches + 7 remainder

        val batches = apps.chunked(batchSize)

        assertEquals("Should have 3 batches", 3, batches.size)
        assertEquals("First batch should be full", batchSize, batches[0].size)
        assertEquals("Second batch should be full", batchSize, batches[1].size)
        assertEquals("Last batch should have remainder", 7, batches[2].size)
    }

    @Test
    fun `chunked processing handles empty list`() {
        val apps = emptyList<Int>()
        val batches = apps.chunked(IconPackBuilder.ICON_BATCH_SIZE)

        assertTrue("Empty list should produce no batches", batches.isEmpty())
    }

    @Test
    fun `chunked processing handles list smaller than batch size`() {
        val apps = (1..5).toList()
        val batches = apps.chunked(IconPackBuilder.ICON_BATCH_SIZE)

        assertEquals("Should have exactly 1 batch", 1, batches.size)
        assertEquals("Batch should contain all items", apps, batches[0])
    }

    @Test
    fun `batch processing scales appropriately for large app counts`() {
        // Simulate device with 1000+ apps
        val appCounts = listOf(100, 250, 500, 750, 1000, 1500)
        val batchSize = IconPackBuilder.ICON_BATCH_SIZE

        for (count in appCounts) {
            val apps = (1..count).toList()
            val batches = apps.chunked(batchSize)

            // Verify expected number of batches
            val expectedBatches = (count + batchSize - 1) / batchSize // ceiling division
            assertEquals("App count $count should produce $expectedBatches batches", expectedBatches, batches.size)

            // Verify all items are processed
            val processedCount = batches.sumOf { it.size }
            assertEquals("All $count apps should be processed", count, processedCount)
        }
    }

    // Calendar icon batch processing tests

    @Test
    fun `calendar icons batch processing handles typical calendar app count`() {
        // Each calendar app has 31 day icons (1-31)
        // Simulate 3 calendar apps = 93 icons
        val calendarApps = 3
        val daysPerCalendar = 31
        val totalCalendarIcons = calendarApps * daysPerCalendar

        val batchSize = IconPackBuilder.ICON_BATCH_SIZE
        val calendarIcons = (1..totalCalendarIcons).toList()
        val batches = calendarIcons.chunked(batchSize)

        // With batch size 50 and 93 icons, should have 2 batches
        val expectedBatches = (totalCalendarIcons + batchSize - 1) / batchSize
        assertEquals("$totalCalendarIcons calendar icons should produce $expectedBatches batches", expectedBatches, batches.size)

        // Verify all icons are processed
        val processedCount = batches.sumOf { it.size }
        assertEquals("All $totalCalendarIcons calendar icons should be processed", totalCalendarIcons, processedCount)
    }

    @Test
    fun `calendar icons batch processing handles many calendar apps`() {
        // Stress test: 10 calendar apps = 310 icons
        val calendarApps = 10
        val daysPerCalendar = 31
        val totalCalendarIcons = calendarApps * daysPerCalendar

        val batchSize = IconPackBuilder.ICON_BATCH_SIZE
        val calendarIcons = (1..totalCalendarIcons).toList()

        val processedItems = mutableListOf<Int>()
        for (batch in calendarIcons.chunked(batchSize)) {
            processedItems.addAll(batch)
        }

        assertEquals("All $totalCalendarIcons calendar icons should be processed", calendarIcons, processedItems)
    }

    @Test
    fun `calendar icons batch processing handles single calendar app`() {
        // Single calendar app = 31 icons (less than batch size)
        val totalCalendarIcons = 31
        val batchSize = IconPackBuilder.ICON_BATCH_SIZE

        val calendarIcons = (1..totalCalendarIcons).toList()
        val batches = calendarIcons.chunked(batchSize)

        assertEquals("31 icons should fit in single batch", 1, batches.size)
        assertEquals("Single batch should contain all 31 icons", totalCalendarIcons, batches[0].size)
    }

    @Test
    fun `calendar icons batch processing handles no calendar apps`() {
        val calendarIcons = emptyList<Int>()
        val batches = calendarIcons.chunked(IconPackBuilder.ICON_BATCH_SIZE)

        assertTrue("No calendar icons should produce no batches", batches.isEmpty())
    }

    @Test
    fun `calendar icons use same batch size as app icons for consistency`() {
        // Calendar icons and app icons should use the same ICON_BATCH_SIZE
        // This ensures consistent memory management across both icon types
        val batchSize = IconPackBuilder.ICON_BATCH_SIZE

        // Verify batch size works for both typical scenarios
        val typicalAppCount = 500
        val typicalCalendarIconCount = 5 * 31 // 5 calendar apps

        val appBatches = (1..typicalAppCount).chunked(batchSize)
        val calendarBatches = (1..typicalCalendarIconCount).chunked(batchSize)

        // Both should produce reasonable batch counts
        assertTrue("App batches should be > 0", appBatches.isNotEmpty())
        assertTrue("Calendar batches should be > 0", calendarBatches.isNotEmpty())

        // All items should be processed
        assertEquals(typicalAppCount, appBatches.sumOf { it.size })
        assertEquals(typicalCalendarIconCount, calendarBatches.sumOf { it.size })
    }
}
