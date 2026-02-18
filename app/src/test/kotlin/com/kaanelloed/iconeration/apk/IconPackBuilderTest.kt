package com.kaanelloed.iconeration.apk

import com.kaanelloed.iconeration.extension.BATCH_SIZE
import com.kaanelloed.iconeration.extension.forEachBatch
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for IconPackBuilder, focusing on memory-efficient batch processing.
 */
class IconPackBuilderTest {

    @Test
    fun `batch processing covers all items`() {
        val apps = (1..500).toList()
        val processedItems = mutableListOf<Int>()

        apps.forEachBatch(BATCH_SIZE) { _, batch ->
            processedItems.addAll(batch)
        }

        assertEquals("All items should be processed", apps, processedItems)
    }

    @Test
    fun `batch processing handles exact multiple of batch size`() {
        val apps = (1..(BATCH_SIZE * 3)).toList()
        var batchCount = 0

        apps.forEachBatch(BATCH_SIZE) { _, batch ->
            batchCount++
            assertEquals("Each batch should have full batch size", BATCH_SIZE, batch.size)
        }

        assertEquals("Should have exactly 3 batches", 3, batchCount)
    }

    @Test
    fun `batch processing handles remainder`() {
        val apps = (1..(BATCH_SIZE * 2 + 7)).toList()
        val batchSizes = mutableListOf<Int>()

        apps.forEachBatch(BATCH_SIZE) { _, batch ->
            batchSizes.add(batch.size)
        }

        assertEquals("Should have 3 batches", 3, batchSizes.size)
        assertEquals("First batch should be full", BATCH_SIZE, batchSizes[0])
        assertEquals("Second batch should be full", BATCH_SIZE, batchSizes[1])
        assertEquals("Last batch should have remainder", 7, batchSizes[2])
    }

    @Test
    fun `batch processing handles empty list`() {
        val apps = emptyList<Int>()
        var batchCount = 0

        apps.forEachBatch(BATCH_SIZE) { _, _ ->
            batchCount++
        }

        assertEquals("Empty list should produce no batches", 0, batchCount)
    }

    @Test
    fun `batch processing handles list smaller than batch size`() {
        val apps = (1..5).toList()
        var batchCount = 0

        apps.forEachBatch(BATCH_SIZE) { _, batch ->
            batchCount++
            assertEquals("Batch should contain all items", apps, batch)
        }

        assertEquals("Should have exactly 1 batch", 1, batchCount)
    }

    @Test
    fun `batch processing scales appropriately for large app counts`() {
        val appCounts = listOf(100, 250, 500, 750, 1000, 1500)

        for (count in appCounts) {
            val apps = (1..count).toList()
            var processedCount = 0

            apps.forEachBatch(BATCH_SIZE) { _, batch ->
                assertTrue("Batch size should not exceed limit", batch.size <= BATCH_SIZE)
                processedCount += batch.size
            }

            assertEquals("All $count apps should be processed", count, processedCount)
        }
    }

    // Calendar icon batch processing tests

    @Test
    fun `calendar icons batch processing handles typical calendar app count`() {
        // Each calendar app has 31 day icons (1-31)
        // Simulate 3 calendar apps = 93 icons
        val totalCalendarIcons = 3 * 31
        val calendarIcons = (1..totalCalendarIcons).toList()
        var processedCount = 0

        calendarIcons.forEachBatch(BATCH_SIZE) { _, batch ->
            processedCount += batch.size
        }

        assertEquals("All $totalCalendarIcons calendar icons should be processed", totalCalendarIcons, processedCount)
    }

    @Test
    fun `calendar icons batch processing handles many calendar apps`() {
        // Stress test: 10 calendar apps = 310 icons
        val totalCalendarIcons = 10 * 31
        val calendarIcons = (1..totalCalendarIcons).toList()
        val processedItems = mutableListOf<Int>()

        calendarIcons.forEachBatch(BATCH_SIZE) { _, batch ->
            processedItems.addAll(batch)
        }

        assertEquals("All $totalCalendarIcons calendar icons should be processed", calendarIcons, processedItems)
    }

    @Test
    fun `calendar icons batch processing handles single calendar app`() {
        // Single calendar app = 31 icons (less than batch size)
        val calendarIcons = (1..31).toList()
        var batchCount = 0

        calendarIcons.forEachBatch(BATCH_SIZE) { _, _ ->
            batchCount++
        }

        assertEquals("31 icons should fit in single batch", 1, batchCount)
    }

    @Test
    fun `calendar icons batch processing handles no calendar apps`() {
        val calendarIcons = emptyList<Int>()
        var batchCount = 0

        calendarIcons.forEachBatch(BATCH_SIZE) { _, _ ->
            batchCount++
        }

        assertEquals("No calendar icons should produce no batches", 0, batchCount)
    }
}
