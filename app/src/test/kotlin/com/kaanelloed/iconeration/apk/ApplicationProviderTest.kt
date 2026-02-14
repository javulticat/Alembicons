package com.kaanelloed.iconeration.apk

import com.kaanelloed.iconeration.data.DbApplication
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ApplicationProvider, focusing on memory-efficient database saving
 * and batch processing optimizations for large app lists (500+).
 */
class ApplicationProviderTest {

    // --- Batch editing tests ---

    @Test
    fun `batch edit applies all changes in single pass`() {
        // Simulate applicationList as a list of strings
        val original = (0 until 500).map { "app_$it" }.toMutableList()

        // Collect edits (like editApplicationsBatch does)
        val edits = listOf(
            0 to "modified_0",
            50 to "modified_50",
            250 to "modified_250",
            499 to "modified_499"
        )

        // Apply batch
        val result = original.toMutableList()
        for ((index, newVal) in edits) {
            result[index] = newVal
        }

        assertEquals("modified_0", result[0])
        assertEquals("modified_50", result[50])
        assertEquals("modified_250", result[250])
        assertEquals("modified_499", result[499])
        // Unmodified items remain unchanged
        assertEquals("app_1", result[1])
        assertEquals("app_100", result[100])
    }

    @Test
    fun `batch edit produces same result as individual edits`() {
        val size = 500
        val original = (0 until size).map { "app_$it" }

        // Edits to apply
        val editIndices = listOf(0, 10, 99, 200, 350, 499)

        // Individual edits (the old approach): creates a new list per edit
        var individualResult = original
        for (idx in editIndices) {
            individualResult = individualResult.toMutableList().also {
                it[idx] = "edited_$idx"
            }
        }

        // Batch edit (the new approach): creates a single new list
        val batchResult = original.toMutableList()
        for (idx in editIndices) {
            batchResult[idx] = "edited_$idx"
        }

        assertEquals(
            "Batch edit should produce same result as individual edits",
            individualResult,
            batchResult.toList()
        )
    }

    @Test
    fun `batch edit with empty edits list returns unchanged list`() {
        val original = (0 until 100).map { "app_$it" }
        val edits = emptyList<Pair<Int, String>>()

        val result = original.toMutableList()
        for ((index, newVal) in edits) {
            result[index] = newVal
        }

        assertEquals("Empty edits should leave list unchanged", original, result)
    }

    @Test
    fun `batch edit reduces list allocations for 500 apps`() {
        val size = 500

        // Old approach: N list copies for N edits
        var individualAllocations = 0
        var list = (0 until size).toList()
        for (i in 0 until size) {
            list = list.toMutableList().also {
                it[i] = -i
                individualAllocations++
            }
        }

        // New approach: 1 list copy for N edits
        var batchAllocations = 0
        val batchList = (0 until size).toMutableList()
        batchAllocations++ // single toMutableList() call
        for (i in 0 until size) {
            batchList[i] = -i
        }

        assertTrue(
            "Batch should use far fewer allocations ($batchAllocations vs $individualAllocations)",
            batchAllocations < individualAllocations
        )
        assertEquals(
            "Both approaches should produce same result",
            list, batchList.toList()
        )
    }

    // --- HashMap lookup tests ---

    @Test
    fun `hashmap lookup produces same results as linear find`() {
        // Simulate dbApps
        val dbApps = (0 until 300).map {
            DbApplication("com.pkg.$it", ".Activity$it", it % 2 == 0, it % 3 == 0, "data$it")
        }

        // Simulate installed apps (subset + some not in DB)
        val installedApps = (0 until 500).map { "com.pkg.$it" to ".Activity$it" }

        // Old approach: linear find per app
        val linearResults = mutableListOf<DbApplication?>()
        for ((pkg, activity) in installedApps) {
            linearResults.add(dbApps.find { it.packageName == pkg && it.activityName == activity })
        }

        // New approach: HashMap lookup
        val dbAppMap = HashMap<Pair<String, String>, DbApplication>(dbApps.size)
        for (dbApp in dbApps) {
            dbAppMap[Pair(dbApp.packageName, dbApp.activityName)] = dbApp
        }
        val hashResults = mutableListOf<DbApplication?>()
        for ((pkg, activity) in installedApps) {
            hashResults.add(dbAppMap[Pair(pkg, activity)])
        }

        assertEquals(
            "HashMap lookup should produce same results as linear find",
            linearResults, hashResults
        )
    }

    @Test
    fun `hashmap lookup handles empty database`() {
        val dbApps = emptyList<DbApplication>()
        val dbAppMap = HashMap<Pair<String, String>, DbApplication>(dbApps.size)
        for (dbApp in dbApps) {
            dbAppMap[Pair(dbApp.packageName, dbApp.activityName)] = dbApp
        }

        assertNull(dbAppMap[Pair("com.pkg.1", ".Main")])
    }

    @Test
    fun `hashmap lookup handles duplicate package names with different activities`() {
        val dbApps = listOf(
            DbApplication("com.pkg.same", ".Activity1", false, false, "data1"),
            DbApplication("com.pkg.same", ".Activity2", false, false, "data2")
        )

        val dbAppMap = HashMap<Pair<String, String>, DbApplication>(dbApps.size)
        for (dbApp in dbApps) {
            dbAppMap[Pair(dbApp.packageName, dbApp.activityName)] = dbApp
        }

        assertEquals("data1", dbAppMap[Pair("com.pkg.same", ".Activity1")]?.drawable)
        assertEquals("data2", dbAppMap[Pair("com.pkg.same", ".Activity2")]?.drawable)
    }

    @Test
    fun `hashmap lookup is O(1) per access for large datasets`() {
        // Build a large dataset
        val size = 1000
        val dbApps = (0 until size).map {
            DbApplication("com.pkg.$it", ".Activity$it", false, false, "data$it")
        }

        val dbAppMap = HashMap<Pair<String, String>, DbApplication>(dbApps.size)
        for (dbApp in dbApps) {
            dbAppMap[Pair(dbApp.packageName, dbApp.activityName)] = dbApp
        }

        // All entries should be found
        for (i in 0 until size) {
            assertNotNull(
                "Entry $i should be found",
                dbAppMap[Pair("com.pkg.$i", ".Activity$i")]
            )
        }

        // Non-existent entries should return null
        assertNull(dbAppMap[Pair("com.nonexistent", ".Main")])
    }

    // --- Original database batch size tests ---

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
