package com.kaanelloed.iconeration.extension

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the [forEachBatch] utility function and [BATCH_SIZE] constant.
 *
 * [forEachBatch] centralizes the batch-process-then-GC pattern used by
 * refreshIcons, saveAlchemiconPack, and IconPackBuilder to prevent OOM
 * crashes when processing 500+ items.
 */
class BatchProcessingTest {

    // --- BATCH_SIZE constant ---

    @Test
    fun `BATCH_SIZE is positive and reasonable`() {
        assertTrue("BATCH_SIZE should be positive", BATCH_SIZE > 0)
        assertTrue("BATCH_SIZE should be >= 10 for efficiency", BATCH_SIZE >= 10)
        assertTrue("BATCH_SIZE should be <= 100 for memory safety", BATCH_SIZE <= 100)
    }

    // --- Core behavior ---

    @Test
    fun `forEachBatch processes all items`() {
        val items = (1..500).toList()
        val processed = mutableListOf<Int>()

        items.forEachBatch(50) { _, batch ->
            processed.addAll(batch)
        }

        assertEquals("All items should be processed", items, processed)
    }

    @Test
    fun `forEachBatch creates correctly sized batches`() {
        val items = (1..127).toList()
        val batchSizes = mutableListOf<Int>()

        items.forEachBatch(50) { _, batch ->
            batchSizes.add(batch.size)
        }

        assertEquals("Should have 3 batches", 3, batchSizes.size)
        assertEquals("First batch should be full", 50, batchSizes[0])
        assertEquals("Second batch should be full", 50, batchSizes[1])
        assertEquals("Last batch should have remainder", 27, batchSizes[2])
    }

    @Test
    fun `forEachBatch handles exact multiple of batch size`() {
        val items = (1..150).toList()
        val batchSizes = mutableListOf<Int>()

        items.forEachBatch(50) { _, batch ->
            batchSizes.add(batch.size)
        }

        assertEquals("Should have exactly 3 batches", 3, batchSizes.size)
        batchSizes.forEach { size ->
            assertEquals("Each batch should be exactly 50", 50, size)
        }
    }

    @Test
    fun `forEachBatch handles empty list`() {
        val items = emptyList<Int>()
        var batchCount = 0

        items.forEachBatch(50) { _, _ ->
            batchCount++
        }

        assertEquals("Empty list should produce no batches", 0, batchCount)
    }

    @Test
    fun `forEachBatch handles single item`() {
        val items = listOf(42)
        val processed = mutableListOf<Int>()

        items.forEachBatch(50) { _, batch ->
            processed.addAll(batch)
        }

        assertEquals("Single item should be processed", listOf(42), processed)
    }

    @Test
    fun `forEachBatch handles list smaller than batch size`() {
        val items = (1..10).toList()
        var batchCount = 0

        items.forEachBatch(50) { _, batch ->
            batchCount++
            assertEquals("Single batch should contain all items", 10, batch.size)
        }

        assertEquals("Should have exactly 1 batch", 1, batchCount)
    }

    @Test
    fun `forEachBatch preserves item order`() {
        val items = (1..200).toList()
        val processed = mutableListOf<Int>()

        items.forEachBatch(30) { _, batch ->
            processed.addAll(batch)
        }

        assertEquals("Item order should be preserved", items, processed)
    }

    @Test
    fun `forEachBatch with batch size of 1 processes each item individually`() {
        val items = (1..5).toList()
        val batchSizes = mutableListOf<Int>()

        items.forEachBatch(1) { _, batch ->
            batchSizes.add(batch.size)
        }

        assertEquals("Should have 5 batches", 5, batchSizes.size)
        batchSizes.forEach { assertEquals("Each batch should have 1 item", 1, it) }
    }

    // --- Start index behavior ---

    @Test
    fun `forEachBatch provides correct start indices`() {
        val items = (1..127).toList()
        val startIndices = mutableListOf<Int>()

        items.forEachBatch(50) { startIndex, _ ->
            startIndices.add(startIndex)
        }

        assertEquals("Should have 3 batches", 3, startIndices.size)
        assertEquals("First batch starts at 0", 0, startIndices[0])
        assertEquals("Second batch starts at 50", 50, startIndices[1])
        assertEquals("Third batch starts at 100", 100, startIndices[2])
    }

    @Test
    fun `forEachBatch start index plus batch index gives absolute index`() {
        val items = (0 until 127).toList()
        val absoluteIndices = mutableListOf<Int>()

        items.forEachBatch(50) { startIndex, batch ->
            for (i in batch.indices) {
                absoluteIndices.add(startIndex + i)
            }
        }

        assertEquals("Absolute indices should cover entire list", items, absoluteIndices)
    }

    @Test
    fun `forEachBatch uses subList views not copies`() {
        // subList returns a view backed by the original list, meaning
        // modifications to the original list are reflected. This verifies
        // that forEachBatch provides views, not independent copies.
        val items = (0 until 100).toMutableList()
        val batches = mutableListOf<List<Int>>()

        items.forEachBatch(50) { _, batch ->
            batches.add(batch)
        }

        // subList views reflect changes to the original list
        items[0] = 999
        assertEquals(
            "subList view should reflect changes to original (confirming view, not copy)",
            999, batches[0][0]
        )
    }

    // --- refreshIcons pattern simulation ---

    @Test
    fun `forEachBatch simulates refreshIcons pattern with index tracking`() {
        data class AppKey(val name: String, val version: Int = 0) {
            fun changeExport(): AppKey = copy(version = version + 1)
        }

        val apps = (0 until 500).map { AppKey("app_$it") }
        val allEdits = mutableListOf<Pair<Int, AppKey>>()

        apps.forEachBatch(50) { batchStart, batch ->
            val batchEdits = mutableListOf<Pair<Int, AppKey>>()
            val batchIndexMap = HashMap<AppKey, Int>(batch.size)
            for (i in batch.indices) {
                batchIndexMap[batch[i]] = batchStart + i
            }

            for (app in batch) {
                val index = batchIndexMap[app]
                if (index != null) {
                    batchEdits.add(Pair(index, app.changeExport()))
                }
            }

            allEdits.addAll(batchEdits)
        }

        assertEquals("All 500 apps should produce edits", 500, allEdits.size)

        for ((index, edit) in allEdits) {
            assertEquals("Edit at index $index should have correct name", "app_$index", edit.name)
            assertEquals("Edit should have incremented version", 1, edit.version)
        }
    }

    // --- Scaling tests ---

    @Test
    fun `forEachBatch scales correctly for large app counts`() {
        val appCounts = listOf(100, 250, 500, 750, 1000, 1500)
        val batchSize = BATCH_SIZE

        for (count in appCounts) {
            val items = (1..count).toList()
            var batchCount = 0
            var processedCount = 0

            items.forEachBatch(batchSize) { _, batch ->
                batchCount++
                processedCount += batch.size
                assertTrue("Batch size should not exceed $batchSize", batch.size <= batchSize)
            }

            val expectedBatches = (count + batchSize - 1) / batchSize
            assertEquals("$count items should produce $expectedBatches batches", expectedBatches, batchCount)
            assertEquals("All $count items should be processed", count, processedCount)
        }
    }

    @Test
    fun `forEachBatch memory estimation per batch is reasonable`() {
        // Worst case: icon generation with 500x500 ARGB_8888 bitmaps (1MB each)
        val maxBitmapSizeMB = 1.0
        val estimatedBatchMemoryMB = BATCH_SIZE * maxBitmapSizeMB

        assertTrue(
            "Estimated batch memory ($estimatedBatchMemoryMB MB) should be < 100MB",
            estimatedBatchMemoryMB < 100.0
        )
    }
}
