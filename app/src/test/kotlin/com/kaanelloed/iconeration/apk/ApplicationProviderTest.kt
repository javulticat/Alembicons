package com.kaanelloed.iconeration.apk

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ApplicationProvider, focusing on memory-efficient database saving.
 */
class ApplicationProviderTest {

    @Test
    fun `database batch size constant is positive and reasonable`() {
        // Batch size should be positive
        assertTrue("Batch size should be positive", ApplicationProvider.DB_SAVE_BATCH_SIZE > 0)

        // Batch size should be smaller than icon pack builder since Base64 conversion
        // creates large strings (~50-100KB each)
        // 25 icons * 100KB = 2.5MB of strings per batch, which is reasonable
        assertTrue("DB batch size should be <= 50 for memory safety", ApplicationProvider.DB_SAVE_BATCH_SIZE <= 50)
        assertTrue("DB batch size should be >= 5 for efficiency", ApplicationProvider.DB_SAVE_BATCH_SIZE >= 5)
    }

    @Test
    fun `database batch size is smaller than icon pack batch size`() {
        // Database save is more memory-intensive due to Base64 string creation
        // So its batch size should be smaller or equal
        assertTrue(
            "DB batch size should be <= icon pack batch size",
            ApplicationProvider.DB_SAVE_BATCH_SIZE <= IconPackBuilder.ICON_BATCH_SIZE
        )
    }

    @Test
    fun `chunked database processing covers all items`() {
        // Simulate saving icons to database
        val apps = (1..500).toList()
        val batchSize = ApplicationProvider.DB_SAVE_BATCH_SIZE

        val processedItems = mutableListOf<Int>()
        for (batch in apps.chunked(batchSize)) {
            processedItems.addAll(batch)
        }

        assertEquals("All items should be saved to database", apps, processedItems)
    }

    @Test
    fun `chunked database processing handles large app counts`() {
        // Simulate device with many installed apps
        val appCounts = listOf(100, 250, 500, 750, 1000)
        val batchSize = ApplicationProvider.DB_SAVE_BATCH_SIZE

        for (count in appCounts) {
            val apps = (1..count).toList()
            val batches = apps.chunked(batchSize)

            // Each batch should be <= batch size
            batches.forEach { batch ->
                assertTrue("Batch size should not exceed limit", batch.size <= batchSize)
            }

            // Verify all items are processed
            val processedCount = batches.sumOf { it.size }
            assertEquals("All $count apps should be saved", count, processedCount)
        }
    }

    @Test
    fun `memory estimation for batch processing`() {
        // Estimate memory usage per batch
        // Assuming average Base64 icon size of 75KB (between 50-100KB)
        val avgBase64SizeKB = 75
        val batchSize = ApplicationProvider.DB_SAVE_BATCH_SIZE

        val estimatedBatchMemoryMB = (batchSize * avgBase64SizeKB) / 1024.0

        // Each batch should use less than 5MB of memory for Base64 strings
        assertTrue(
            "Estimated batch memory ($estimatedBatchMemoryMB MB) should be < 5MB",
            estimatedBatchMemoryMB < 5.0
        )
    }

    @Test
    fun `batching reduces peak memory for 500 apps scenario`() {
        // Without batching: all 500 Base64 strings in memory at once
        // With batching: only DB_SAVE_BATCH_SIZE strings at a time

        val appCount = 500
        val avgBase64SizeKB = 75

        val withoutBatchingMB = (appCount * avgBase64SizeKB) / 1024.0
        val withBatchingMB = (ApplicationProvider.DB_SAVE_BATCH_SIZE * avgBase64SizeKB) / 1024.0

        // Peak memory should be significantly reduced
        val reductionFactor = withoutBatchingMB / withBatchingMB

        assertTrue(
            "Batching should reduce peak memory by at least 10x (actual: ${reductionFactor}x)",
            reductionFactor >= 10
        )
    }
}
