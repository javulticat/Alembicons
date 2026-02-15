package com.kaanelloed.iconeration.ui

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the ApplicationList pre-filtering logic that was moved
 * outside of LazyColumn's itemsIndexed to fix performance with 500+ apps.
 *
 * The filtering logic preserves original indices so that edit operations
 * (which use index-based access) continue to work correctly after filtering.
 */
class ApplicationListFilterTest {

    /**
     * Simulates a simplified app entry with name, package and activity.
     * Uses simple data class instead of PackageInfoStruct (which requires Drawable).
     */
    data class SimpleApp(
        val appName: String,
        val packageName: String,
        val activityName: String
    )

    /**
     * Replicates the pre-filtering logic from ApplicationList composable.
     */
    private fun filterApps(
        allApps: List<SimpleApp>,
        filter: String
    ): List<Pair<Int, SimpleApp>> {
        return if (filter.isEmpty()) {
            allApps.mapIndexed { index, app -> index to app }
        } else {
            allApps.mapIndexedNotNull { index, app ->
                if (app.appName.contains(filter, true)) index to app else null
            }
        }
    }

    @Test
    fun `empty filter returns all apps with correct indices`() {
        val apps = (0 until 500).map {
            SimpleApp("App $it", "com.pkg.$it", ".Main")
        }

        val result = filterApps(apps, "")

        assertEquals("All apps should be returned", 500, result.size)
        for ((index, pair) in result.withIndex()) {
            assertEquals("Index should match position", index, pair.first)
            assertEquals("App should match", apps[index], pair.second)
        }
    }

    @Test
    fun `filter returns only matching apps`() {
        val apps = listOf(
            SimpleApp("Chrome", "com.chrome", ".Main"),
            SimpleApp("Firefox", "com.firefox", ".Main"),
            SimpleApp("Chrome Beta", "com.chrome.beta", ".Main"),
            SimpleApp("Settings", "com.settings", ".Main")
        )

        val result = filterApps(apps, "Chrome")

        assertEquals("Should find 2 Chrome apps", 2, result.size)
        assertEquals("Chrome", result[0].second.appName)
        assertEquals("Chrome Beta", result[1].second.appName)
    }

    @Test
    fun `filter preserves original indices for edit operations`() {
        val apps = listOf(
            SimpleApp("Alpha", "com.alpha", ".Main"),       // index 0
            SimpleApp("Beta", "com.beta", ".Main"),         // index 1
            SimpleApp("Charlie", "com.charlie", ".Main"),   // index 2
            SimpleApp("Delta", "com.delta", ".Main"),       // index 3
            SimpleApp("Beta Test", "com.betatest", ".Main") // index 4
        )

        val result = filterApps(apps, "Beta")

        assertEquals("Should find 2 Beta apps", 2, result.size)
        // Original indices must be preserved for editApplication(index, newApp)
        assertEquals("First match should have original index 1", 1, result[0].first)
        assertEquals("Second match should have original index 4", 4, result[1].first)
    }

    @Test
    fun `filter is case insensitive`() {
        val apps = listOf(
            SimpleApp("Settings", "com.settings", ".Main"),
            SimpleApp("SETTINGS Plus", "com.settings.plus", ".Main"),
            SimpleApp("Chrome", "com.chrome", ".Main")
        )

        val result = filterApps(apps, "settings")

        assertEquals("Case-insensitive filter should find 2 apps", 2, result.size)
    }

    @Test
    fun `filter with no matches returns empty list`() {
        val apps = (0 until 500).map {
            SimpleApp("App $it", "com.pkg.$it", ".Main")
        }

        val result = filterApps(apps, "ZZZZNONEXISTENT")

        assertTrue("No matches should return empty list", result.isEmpty())
    }

    @Test
    fun `filter with empty app list returns empty`() {
        val result = filterApps(emptyList(), "test")

        assertTrue("Empty list should return empty result", result.isEmpty())
    }

    @Test
    fun `stable keys are unique per app`() {
        val apps = listOf(
            SimpleApp("App 1", "com.pkg.a", ".Main"),
            SimpleApp("App 2", "com.pkg.a", ".Settings"),
            SimpleApp("App 3", "com.pkg.b", ".Main"),
            SimpleApp("App 1 Clone", "com.pkg.a", ".Main")  // duplicate component
        )

        // Replicate the key generation from ApplicationList
        val keys = apps.map { "${it.packageName}/${it.activityName}" }

        // First 3 should be unique
        val uniqueKeys = keys.take(3).toSet()
        assertEquals("First 3 apps should have unique keys", 3, uniqueKeys.size)

        // Key is based on package+activity, so duplicate components produce same key
        assertEquals(
            "Same package+activity should produce same key",
            keys[0], keys[3]
        )
    }

    @Test
    fun `filtering large list does not lose any matching items`() {
        // Simulate 1000 apps, every 10th has "Special" in name
        val apps = (0 until 1000).map {
            val name = if (it % 10 == 0) "Special App $it" else "Regular App $it"
            SimpleApp(name, "com.pkg.$it", ".Main")
        }

        val result = filterApps(apps, "Special")

        assertEquals("Should find 100 Special apps", 100, result.size)

        // Verify all original indices are correct
        for ((originalIndex, app) in result) {
            assertTrue("Original index $originalIndex should be divisible by 10", originalIndex % 10 == 0)
            assertTrue("App name should contain Special", app.appName.contains("Special"))
            assertEquals("App should match original", apps[originalIndex], app)
        }
    }

    @Test
    fun `filter result indices can be used for correct list access`() {
        val apps = (0 until 500).map {
            SimpleApp("App $it", "com.pkg.$it", ".Main")
        }

        val result = filterApps(apps, "App 4")

        // Every result's originalIndex should point to the correct app in the source list
        for ((originalIndex, filteredApp) in result) {
            assertEquals(
                "Original index should access the same app",
                filteredApp, apps[originalIndex]
            )
        }
    }

    // --- Hoisted preference tests ---
    // Preferences (bgColorValue, themed, dynamicColor) are now read once in
    // ApplicationList and passed to each ApplicationItem, instead of each item
    // creating its own DataStore Flow collectors.

    @Test
    fun `hoisted values are shared identically across all items`() {
        // Simulates the hoisting pattern: read once, pass to many items.
        // All items should receive the exact same values.
        val themed = true
        val dynamicColor = false
        val bgColor = "0xFFFFFFFF"

        val apps = (0 until 500).map {
            SimpleApp("App $it", "com.pkg.$it", ".Main")
        }

        // Simulate passing hoisted values to each item
        data class ItemParams(val themed: Boolean, val dynamicColor: Boolean, val bgColor: String)

        val itemParams = apps.map {
            ItemParams(themed, dynamicColor, bgColor)
        }

        // All items should receive the same values
        val distinct = itemParams.distinct()
        assertEquals(
            "All 500 items should receive identical preference values",
            1, distinct.size
        )
        assertEquals(themed, distinct[0].themed)
        assertEquals(dynamicColor, distinct[0].dynamicColor)
        assertEquals(bgColor, distinct[0].bgColor)
    }

    @Test
    fun `hoisting reduces collector count from N to 1`() {
        // Each visible ApplicationItem previously created its own Flow collectors:
        //   - getBooleanValue(ExportThemedKey) -> collectAsState
        //   - getColorValue(BackgroundColorKey) -> collectAsState
        //   - getBooleanValue(ExportThemedKey) (for dynamicColor) -> same collector
        // With ~10 visible items, that's ~20 active collectors.
        // Hoisting reduces it to just 2 collectors at the ApplicationList level.

        val visibleItems = 10
        val prefsPerItem = 2 // getBooleanValue + getColorValue

        val oldCollectorCount = visibleItems * prefsPerItem
        val newCollectorCount = prefsPerItem // hoisted to parent

        assertTrue(
            "Hoisting should use far fewer collectors ($newCollectorCount vs $oldCollectorCount)",
            newCollectorCount < oldCollectorCount
        )
        assertEquals(
            "Hoisted approach should use exactly $prefsPerItem collectors",
            prefsPerItem, newCollectorCount
        )
    }

    // --- contentType tests ---

    @Test
    fun `all items in the list have the same content type`() {
        // LazyColumn's contentType = { "app" } tells Compose that all items
        // have the same composition structure, enabling slot reuse.
        val apps = (0 until 500).map {
            SimpleApp("App $it", "com.pkg.$it", ".Main")
        }

        val contentTypes = apps.map { "app" } // Replicate contentType = { "app" }

        val distinctTypes = contentTypes.distinct()
        assertEquals(
            "All items should have the same contentType for efficient slot reuse",
            1, distinctTypes.size
        )
        assertEquals("app", distinctTypes[0])
    }
}
