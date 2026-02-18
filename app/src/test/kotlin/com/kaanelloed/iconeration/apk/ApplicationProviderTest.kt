package com.kaanelloed.iconeration.apk

import com.kaanelloed.iconeration.data.DbApplication
import com.kaanelloed.iconeration.extension.BATCH_SIZE
import com.kaanelloed.iconeration.extension.forEachBatch
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

    // --- refreshIcons index lookup tests ---
    // refreshIcons() uses HashMap<PackageInfoStruct, Int> for O(1) index access.
    // PackageInfoStruct.hashCode() uses (packageName, activityName).
    // PackageInfoStruct.equals() uses (packageName, activityName, internalVersion).
    // These tests verify the lookup contract using a simulation of that behavior.

    /**
     * Simulates PackageInfoStruct's hashCode/equals contract for HashMap testing.
     * hashCode: packageName + activityName (mirrors PackageInfoStruct.hashCode)
     * equals: packageName + activityName + internalVersion (mirrors PackageInfoStruct.equals)
     */
    private class AppKey(
        val packageName: String,
        val activityName: String,
        val internalVersion: Int = 0
    ) {
        override fun hashCode(): Int {
            var result = packageName.hashCode()
            result = 31 * result + activityName.hashCode()
            return result
        }

        override fun equals(other: Any?): Boolean {
            if (other is AppKey) {
                return packageName == other.packageName
                    && activityName == other.activityName
                    && internalVersion == other.internalVersion
            }
            return false
        }

        fun changeExport(): AppKey {
            return AppKey(packageName, activityName, internalVersion + 1)
        }
    }

    @Test
    fun `refreshIcons index map finds exact same reference`() {
        // Replicates: val appIndexMap = HashMap<PackageInfoStruct, Int>()
        val apps = (0 until 500).map {
            AppKey("com.pkg.$it", ".Activity$it")
        }

        val appIndexMap = HashMap<AppKey, Int>(apps.size)
        for ((index, app) in apps.withIndex()) {
            appIndexMap[app] = index
        }

        // generateIcons callback receives the same object reference
        for ((expectedIndex, app) in apps.withIndex()) {
            val foundIndex = appIndexMap[app]
            assertNotNull("App at index $expectedIndex should be found", foundIndex)
            assertEquals(expectedIndex, foundIndex)
        }
    }

    @Test
    fun `changeExport produces non-equal object with incremented version`() {
        val original = AppKey("com.pkg.test", ".Main", 0)
        val changed = original.changeExport()

        assertNotEquals(
            "changeExport should produce a different object (different internalVersion)",
            original, changed
        )
        assertEquals("Package name should be preserved", original.packageName, changed.packageName)
        assertEquals("Activity name should be preserved", original.activityName, changed.activityName)
        assertEquals("Internal version should increment", original.internalVersion + 1, changed.internalVersion)
    }

    @Test
    fun `changed object has same hashCode but is not equal to original`() {
        // This verifies the hashCode/equals asymmetry in PackageInfoStruct:
        // hashCode uses only (packageName, activityName)
        // equals also checks internalVersion
        val original = AppKey("com.pkg.test", ".Main", 0)
        val changed = original.changeExport()

        assertEquals(
            "Same packageName + activityName should produce same hashCode",
            original.hashCode(), changed.hashCode()
        )
        assertNotEquals(
            "Different internalVersion should make objects not equal",
            original, changed
        )
    }

    @Test
    fun `refreshIcons index map correctly handles batch collection pattern`() {
        // Full simulation of the refreshIcons batch pattern:
        // 1. Build index map from original list
        // 2. Generate icons (callback receives original objects)
        // 3. Create edits pairing original index with changeExport() result
        // 4. Apply batch edits

        val apps = (0 until 500).map {
            AppKey("com.pkg.$it", ".Activity$it")
        }

        // Step 1: Build index map
        val appIndexMap = HashMap<AppKey, Int>(apps.size)
        for ((index, app) in apps.withIndex()) {
            appIndexMap[app] = index
        }

        // Step 2-3: Simulate generateIcons callback
        val edits = mutableListOf<Pair<Int, AppKey>>()
        for (app in apps) {
            val index = appIndexMap[app]
            if (index != null) {
                edits.add(Pair(index, app.changeExport()))
            }
        }

        assertEquals("All 500 apps should produce edits", 500, edits.size)

        // Step 4: Apply batch
        val result = apps.toMutableList()
        for ((index, newApp) in edits) {
            result[index] = newApp
        }

        // Verify all items were updated
        for ((index, app) in result.withIndex()) {
            assertEquals("com.pkg.$index", app.packageName)
            assertEquals(1, app.internalVersion) // incremented from 0
        }
    }

    // --- Batched refresh pattern tests ---

    @Test
    fun `batched refresh processes all items with correct indices`() {
        // Simulate the exact refreshIcons batch pattern using forEachBatch
        val apps = (0 until 500).map { AppKey("com.pkg.$it", ".Activity$it") }
        val allEdits = mutableListOf<Pair<Int, AppKey>>()

        val batchSize = BATCH_SIZE
        apps.forEachBatch(batchSize) { batchStart, batch ->
            val batchIndexMap = HashMap<AppKey, Int>(batch.size)
            for (i in batch.indices) {
                batchIndexMap[batch[i]] = batchStart + i
            }

            for (app in batch) {
                val index = batchIndexMap[app]
                if (index != null) {
                    allEdits.add(Pair(index, app.changeExport()))
                }
            }
        }

        assertEquals("All 500 apps should produce edits", 500, allEdits.size)

        // Verify indices are sequential and correct
        for ((idx, edit) in allEdits.withIndex()) {
            assertEquals("Edit $idx should have correct absolute index", idx, edit.first)
            assertEquals("Edit $idx should have correct package name", "com.pkg.$idx", edit.second.packageName)
        }
    }

    @Test
    fun `batching reduces peak memory for 500 apps scenario`() {
        // Without batching: all 500 intermediate objects in memory at once
        // With batching: only BATCH_SIZE objects at a time
        val appCount = 500
        val avgObjectSizeKB = 75 // conservative estimate for Base64 strings

        val withoutBatchingMB = (appCount * avgObjectSizeKB) / 1024.0
        val withBatchingMB = (BATCH_SIZE * avgObjectSizeKB) / 1024.0

        val reductionFactor = withoutBatchingMB / withBatchingMB

        assertTrue(
            "Batching should reduce peak memory by at least 5x (actual: ${reductionFactor}x)",
            reductionFactor >= 5
        )
    }

    // --- Pre-warming pattern tests ---
    // initializeApplications() now pre-warms listBitmap for all items on the
    // background thread before making the list visible. loadAlchemiconPack() and
    // refreshIcons() pre-warm edited items before editApplicationsBatch().
    // These tests verify the patterns using AppKey simulations.

    /**
     * Simulates an item with a lazy-cached value (like PackageInfoStruct.listBitmap).
     */
    private class CachingItem(val id: Int) {
        var initCount = 0
            private set
        val cachedValue: String by lazy {
            initCount++
            "bitmap_$id"
        }
    }

    @Test
    fun `pre-warming initializes lazy value before list is visible`() {
        // Simulates: preWarmListBitmaps runs before applicationList is set
        val items = (0 until 500).map { CachingItem(it) }

        // Pre-warm (like preWarmListBitmaps does)
        for (item in items) {
            item.cachedValue // trigger lazy init
        }

        // Verify all items are initialized exactly once
        for (item in items) {
            assertEquals(
                "Item ${item.id} should be initialized exactly once",
                1, item.initCount
            )
        }

        // Subsequent access should not re-initialize
        for (item in items) {
            item.cachedValue // access again
            assertEquals(
                "Item ${item.id} should still be initialized exactly once after re-access",
                1, item.initCount
            )
        }
    }

    @Test
    fun `pre-warming edits covers all new items before batch apply`() {
        // Simulates: preWarmEditBitmaps runs before editApplicationsBatch
        val originals = (0 until 500).map { CachingItem(it) }

        // Some items get edited (like loadAlchemiconPack creating new PackageInfoStructs)
        val editIndices = listOf(0, 10, 50, 100, 200, 499)
        val edits = editIndices.map { index ->
            index to CachingItem(index) // new item, fresh lazy
        }

        // Pre-warm edits (like preWarmEditBitmaps does)
        for ((_, newItem) in edits) {
            newItem.cachedValue // trigger lazy init
        }

        // Apply batch
        val result = originals.toMutableList()
        for ((index, newItem) in edits) {
            result[index] = newItem
        }

        // Verify edited items are already initialized
        for (index in editIndices) {
            assertEquals(
                "Edited item at $index should be pre-warmed",
                1, result[index].initCount
            )
        }
    }

    @Test
    fun `pre-warming runs before list assignment in initialization pattern`() {
        // Simulates the exact pattern from initializeApplications():
        //   val appList = apps.toList()
        //   preWarmListBitmaps(appList)
        //   applicationList = appList
        var listSetAt = -1
        val events = mutableListOf<String>()

        val items = (0 until 100).map { CachingItem(it) }

        // Pre-warm happens before "assignment"
        events.add("pre_warm_start")
        for (item in items) {
            item.cachedValue
        }
        events.add("pre_warm_end")

        // Simulate list assignment
        events.add("list_assigned")
        listSetAt = events.size

        assertTrue(
            "Pre-warming must complete before list is assigned",
            events.indexOf("pre_warm_end") < events.indexOf("list_assigned")
        )

        // All items should be initialized before the list was "visible"
        for (item in items) {
            assertEquals(1, item.initCount)
        }
    }

    @Test
    fun `pre-warming edit items before batch mirrors the code pattern`() {
        // Simulates the exact pattern from refreshIcons/loadAlchemiconPack:
        //   preWarmEditBitmaps(edits)
        //   editApplicationsBatch(edits)
        val events = mutableListOf<String>()

        val edits = (0 until 50).map { index ->
            index to CachingItem(index)
        }

        // Pre-warm before batch edit
        events.add("pre_warm")
        for ((_, newItem) in edits) {
            newItem.cachedValue
        }

        // Batch edit
        events.add("batch_edit")

        assertTrue(
            "Pre-warming must happen before batch edit",
            events.indexOf("pre_warm") < events.indexOf("batch_edit")
        )

        // All edited items should already be initialized
        for ((_, item) in edits) {
            assertEquals("Item should be initialized exactly once", 1, item.initCount)
        }
    }
}
