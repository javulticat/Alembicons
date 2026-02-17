package com.kaanelloed.iconeration.extension

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the [forEachBatch] and [forEachBatchIndexed] utility functions.
 *
 * These utilities centralize the batch-process-then-GC pattern used by
 * refreshIcons, saveAlchemiconPack, and IconPackBuilder to prevent OOM
 * crashes when processing 500+ items.
 */
class BatchProcessingTest {

    // --- forEachBatch ---

    @Test
    fun `forEachBatch processes all items`() {
        val items = (1..500).toList()
        val processed = mutableListOf<Int>()

        items.forEachBatch(50) { batch ->
            processed.addAll(batch)
        }

        assertEquals("All items should be processed", items, processed)
    }

    @Test
    fun `forEachBatch creates correctly sized batches`() {
        val items = (1..127).toList()
        val batchSizes = mutableListOf<Int>()

        items.forEachBatch(50) { batch ->
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

        items.forEachBatch(50) { batch ->
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

        items.forEachBatch(50) {
            batchCount++
        }

        assertEquals("Empty list should produce no batches", 0, batchCount)
    }

    @Test
    fun `forEachBatch handles single item`() {
        val items = listOf(42)
        val processed = mutableListOf<Int>()

        items.forEachBatch(50) { batch ->
            processed.addAll(batch)
        }

        assertEquals("Single item should be processed", listOf(42), processed)
    }

    @Test
    fun `forEachBatch handles list smaller than batch size`() {
        val items = (1..10).toList()
        var batchCount = 0

        items.forEachBatch(50) { batch ->
            batchCount++
            assertEquals("Single batch should contain all items", 10, batch.size)
        }

        assertEquals("Should have exactly 1 batch", 1, batchCount)
    }

    @Test
    fun `forEachBatch works with Set entries`() {
        // IconPackBuilder uses calendarIconsDrawable.entries.forEachBatch
        val map = (1..75).associateWith { "value_$it" }
        val processedKeys = mutableListOf<Int>()

        map.entries.forEachBatch(50) { batch ->
            processedKeys.addAll(batch.map { it.key })
        }

        assertEquals("All map entries should be processed", map.keys.sorted(), processedKeys.sorted())
    }

    @Test
    fun `forEachBatch preserves item order`() {
        val items = (1..200).toList()
        val processed = mutableListOf<Int>()

        items.forEachBatch(30) { batch ->
            processed.addAll(batch)
        }

        assertEquals("Item order should be preserved", items, processed)
    }

    @Test
    fun `forEachBatch with batch size of 1 processes each item individually`() {
        val items = (1..5).toList()
        val batchSizes = mutableListOf<Int>()

        items.forEachBatch(1) { batch ->
            batchSizes.add(batch.size)
        }

        assertEquals("Should have 5 batches", 5, batchSizes.size)
        batchSizes.forEach { assertEquals("Each batch should have 1 item", 1, it) }
    }

    // --- forEachBatchIndexed ---

    @Test
    fun `forEachBatchIndexed processes all items`() {
        val items = (1..500).toList()
        val processed = mutableListOf<Int>()

        items.forEachBatchIndexed(50) { _, batch ->
            processed.addAll(batch)
        }

        assertEquals("All items should be processed", items, processed)
    }

    @Test
    fun `forEachBatchIndexed provides correct start indices`() {
        val items = (1..127).toList()
        val startIndices = mutableListOf<Int>()

        items.forEachBatchIndexed(50) { startIndex, _ ->
            startIndices.add(startIndex)
        }

        assertEquals("Should have 3 batches", 3, startIndices.size)
        assertEquals("First batch starts at 0", 0, startIndices[0])
        assertEquals("Second batch starts at 50", 50, startIndices[1])
        assertEquals("Third batch starts at 100", 100, startIndices[2])
    }

    @Test
    fun `forEachBatchIndexed start index plus batch index gives absolute index`() {
        val items = (0 until 127).toList()
        val absoluteIndices = mutableListOf<Int>()

        items.forEachBatchIndexed(50) { startIndex, batch ->
            for (i in batch.indices) {
                absoluteIndices.add(startIndex + i)
            }
        }

        assertEquals("Absolute indices should cover entire list", items, absoluteIndices)
    }

    @Test
    fun `forEachBatchIndexed uses subList views not copies`() {
        // subList returns a view backed by the original list, meaning
        // modifications to the original list are reflected. This verifies
        // that forEachBatchIndexed provides views, not independent copies.
        val items = (0 until 100).toMutableList()
        val batches = mutableListOf<List<Int>>()

        items.forEachBatchIndexed(50) { _, batch ->
            // Capture the batch reference
            batches.add(batch)
        }

        // subList views reflect changes to the original list
        items[0] = 999
        assertEquals(
            "subList view should reflect changes to original (confirming view, not copy)",
            999, batches[0][0]
        )
    }

    @Test
    fun `forEachBatchIndexed handles empty list`() {
        val items = emptyList<Int>()
        var batchCount = 0

        items.forEachBatchIndexed(50) { _, _ ->
            batchCount++
        }

        assertEquals("Empty list should produce no batches", 0, batchCount)
    }

    @Test
    fun `forEachBatchIndexed handles single item`() {
        val items = listOf("only")
        var receivedStart = -1
        var receivedBatch: List<String>? = null

        items.forEachBatchIndexed(50) { startIndex, batch ->
            receivedStart = startIndex
            receivedBatch = batch
        }

        assertEquals("Start index should be 0", 0, receivedStart)
        assertEquals("Batch should contain the single item", listOf("only"), receivedBatch)
    }

    @Test
    fun `forEachBatchIndexed simulates refreshIcons pattern`() {
        // Simulates the exact refreshIcons pattern:
        // 1. For each batch, build an index map from batch items to absolute indices
        // 2. Process items and collect edits with absolute indices
        // 3. Apply edits

        data class AppKey(val name: String, val version: Int = 0) {
            fun changeExport(): AppKey = copy(version = version + 1)
        }

        val apps = (0 until 500).map { AppKey("app_$it") }
        val allEdits = mutableListOf<Pair<Int, AppKey>>()

        apps.forEachBatchIndexed(50) { batchStart, batch ->
            val batchEdits = mutableListOf<Pair<Int, AppKey>>()
            val batchIndexMap = HashMap<AppKey, Int>(batch.size)
            for (i in batch.indices) {
                batchIndexMap[batch[i]] = batchStart + i
            }

            // Simulate generateIcons callback
            for (app in batch) {
                val index = batchIndexMap[app]
                if (index != null) {
                    batchEdits.add(Pair(index, app.changeExport()))
                }
            }

            allEdits.addAll(batchEdits)
        }

        assertEquals("All 500 apps should produce edits", 500, allEdits.size)

        // Verify absolute indices are correct
        for ((index, edit) in allEdits) {
            assertEquals("Edit at index $index should have correct name", "app_$index", edit.name)
            assertEquals("Edit should have incremented version", 1, edit.version)
        }
    }

    // --- Batch count tests for various sizes ---

    @Test
    fun `forEachBatch scales correctly for large app counts`() {
        val appCounts = listOf(100, 250, 500, 750, 1000, 1500)
        val batchSize = 50

        for (count in appCounts) {
            val items = (1..count).toList()
            var batchCount = 0
            var processedCount = 0

            items.forEachBatch(batchSize) { batch ->
                batchCount++
                processedCount += batch.size
                assertTrue("Batch size should not exceed $batchSize", batch.size <= batchSize)
            }

            val expectedBatches = (count + batchSize - 1) / batchSize
            assertEquals("$count items with batch $batchSize should produce $expectedBatches batches", expectedBatches, batchCount)
            assertEquals("All $count items should be processed", count, processedCount)
        }
    }

    @Test
    fun `forEachBatchIndexed scales correctly for large app counts`() {
        val appCounts = listOf(100, 250, 500, 750, 1000)
        val batchSize = 50

        for (count in appCounts) {
            val items = (0 until count).toList()
            var lastStartIndex = -1
            var processedCount = 0

            items.forEachBatchIndexed(batchSize) { startIndex, batch ->
                assertTrue("Start index should increase", startIndex > lastStartIndex)
                lastStartIndex = startIndex
                processedCount += batch.size
            }

            assertEquals("All $count items should be processed", count, processedCount)
        }
    }
}
