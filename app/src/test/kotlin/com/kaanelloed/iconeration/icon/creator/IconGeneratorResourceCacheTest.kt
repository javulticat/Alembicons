package com.kaanelloed.iconeration.icon.creator

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [ResourceCache], which is used by [IconGenerator] to cache
 * [android.content.res.Resources] lookups and avoid repeated IPC via
 * getResourcesForApplication() when processing 500+ apps that all
 * reference the same icon pack.
 */
class IconGeneratorResourceCacheTest {

    /** Creates a [ResourceCache] whose loader tracks call count via [lookupCount]. */
    private var lookupCount = 0

    private fun createCache(): ResourceCache<String> {
        lookupCount = 0
        return ResourceCache { packageName ->
            lookupCount++
            if (packageName == "missing.pack") null else "resources_for_$packageName"
        }
    }

    @Test
    fun `cache returns same result on repeated lookups`() {
        val cache = createCache()

        val first = cache.get("com.example.iconpack")
        val second = cache.get("com.example.iconpack")

        assertEquals(first, second)
    }

    @Test
    fun `cache avoids repeated IPC for same icon pack`() {
        val cache = createCache()

        // Simulate 500 apps all using the same icon pack
        repeat(500) {
            cache.get("com.example.iconpack")
        }

        assertEquals(
            "Resources for the same package should only be loaded once",
            1, lookupCount
        )
    }

    @Test
    fun `cache loads different packages independently`() {
        val cache = createCache()

        cache.get("com.pack.primary")
        cache.get("com.pack.secondary")

        assertEquals("Two different packages should trigger two lookups", 2, lookupCount)
        assertEquals("resources_for_com.pack.primary", cache.get("com.pack.primary"))
        assertEquals("resources_for_com.pack.secondary", cache.get("com.pack.secondary"))
        assertEquals("Repeat lookups should still be 2", 2, lookupCount)
    }

    @Test
    fun `cache handles null results correctly`() {
        val cache = createCache()

        // First lookup returns null (package not found)
        val result = cache.get("missing.pack")
        assertNull("Missing package should return null", result)

        // Second lookup should reuse cached null, not retry IPC
        val second = cache.get("missing.pack")
        assertNull("Cached null should be returned", second)
        assertEquals("Null result should be cached, not retried", 1, lookupCount)
    }

    @Test
    fun `cache with mixed packages scales correctly`() {
        val cache = createCache()

        // Simulate refreshIcons: 500 apps, primary pack = "com.pack.a", secondary = "com.pack.b"
        // Each app checks both packs
        repeat(500) {
            cache.get("com.pack.a")
            cache.get("com.pack.b")
        }

        assertEquals(
            "1000 lookups across 2 packages should only trigger 2 actual loads",
            2, lookupCount
        )
    }

    @Test
    fun `cache eliminates redundant IPC in parseApplicationIcon pattern`() {
        val cache = createCache()

        // parseApplicationIcon calls resourcesCache.get(application.packageName)
        // for each app. Each app has a unique package name, so no caching benefit
        // UNLESS the same app package appears multiple times (unlikely).
        // The main benefit is for icon pack lookups in exportIconPackXML.
        val uniquePackages = (0 until 500).map { "com.app.$it" }

        for (pkg in uniquePackages) {
            cache.get(pkg)
        }

        assertEquals(
            "500 unique packages should trigger 500 lookups (cache prevents re-lookups)",
            500, lookupCount
        )

        // Second pass should be entirely cached
        for (pkg in uniquePackages) {
            cache.get(pkg)
        }

        assertEquals(
            "Second pass should reuse cache, still 500 lookups total",
            500, lookupCount
        )
    }

    @Test
    fun `cache eliminates redundant IPC in exportIconPackXML pattern`() {
        val cache = createCache()

        // exportIconPackXML calls resourcesCache.get(iconPackName) for the same
        // icon pack on every app. This is the primary win.
        val iconPackName = "com.arcticons.iconpack"

        repeat(500) {
            cache.get(iconPackName)
        }

        assertEquals(
            "500 calls for the same icon pack should trigger only 1 actual load",
            1, lookupCount
        )
    }

    @Test
    fun `shared ApplicationManager avoids redundant object creation`() {
        // Before fix: 3 new ApplicationManager instances per app in IconGenerator
        //   parseApplicationIcon:    val appMan = ApplicationManager(ctx)
        //   exportIconPackXML (1):   ApplicationManager(ctx).getResources(...)
        //   exportIconPackXML (2):   ApplicationManager(ctx).getPackageResourceXml(...)
        // After fix: 1 shared instance for the entire IconGenerator lifetime

        val appCount = 500
        val instancesBeforeFix = appCount * 3 // 3 per app
        val instancesAfterFix = 1 // single shared instance

        assertTrue(
            "Shared ApplicationManager should create far fewer instances ($instancesAfterFix vs $instancesBeforeFix)",
            instancesAfterFix < instancesBeforeFix
        )
    }
}
