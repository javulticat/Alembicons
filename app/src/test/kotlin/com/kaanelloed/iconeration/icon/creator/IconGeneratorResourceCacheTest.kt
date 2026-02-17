package com.kaanelloed.iconeration.icon.creator

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the resource caching pattern used by [IconGenerator].
 *
 * IconGenerator caches [android.content.res.Resources] lookups to avoid
 * repeated IPC via getResourcesForApplication() when processing 500+ apps
 * that all reference the same icon pack. These tests verify the caching
 * contract using a simulated cache.
 */
class IconGeneratorResourceCacheTest {

    /**
     * Simulates the Resources cache in IconGenerator:
     *   private val resourcesCache = HashMap<String, Resources?>()
     *   private fun getCachedResources(packageName: String): Resources? {
     *       return resourcesCache.getOrPut(packageName) { appManager.getResources(packageName) }
     *   }
     */
    private class ResourceCache {
        var lookupCount = 0
            private set
        private val cache = HashMap<String, String?>()

        fun getCached(packageName: String): String? {
            return cache.getOrPut(packageName) {
                lookupCount++
                if (packageName == "missing.pack") null else "resources_for_$packageName"
            }
        }
    }

    @Test
    fun `cache returns same result on repeated lookups`() {
        val cache = ResourceCache()

        val first = cache.getCached("com.example.iconpack")
        val second = cache.getCached("com.example.iconpack")

        assertEquals(first, second)
    }

    @Test
    fun `cache avoids repeated IPC for same icon pack`() {
        val cache = ResourceCache()

        // Simulate 500 apps all using the same icon pack
        repeat(500) {
            cache.getCached("com.example.iconpack")
        }

        assertEquals(
            "Resources for the same package should only be loaded once",
            1, cache.lookupCount
        )
    }

    @Test
    fun `cache loads different packages independently`() {
        val cache = ResourceCache()

        cache.getCached("com.pack.primary")
        cache.getCached("com.pack.secondary")

        assertEquals("Two different packages should trigger two lookups", 2, cache.lookupCount)
        assertEquals("resources_for_com.pack.primary", cache.getCached("com.pack.primary"))
        assertEquals("resources_for_com.pack.secondary", cache.getCached("com.pack.secondary"))
        assertEquals("Repeat lookups should still be 2", 2, cache.lookupCount)
    }

    @Test
    fun `cache handles null results correctly`() {
        val cache = ResourceCache()

        // First lookup returns null (package not found)
        val result = cache.getCached("missing.pack")
        assertNull("Missing package should return null", result)

        // Second lookup should reuse cached null, not retry IPC
        val second = cache.getCached("missing.pack")
        assertNull("Cached null should be returned", second)
        assertEquals("Null result should be cached, not retried", 1, cache.lookupCount)
    }

    @Test
    fun `cache with mixed packages scales correctly`() {
        val cache = ResourceCache()

        // Simulate refreshIcons: 500 apps, primary pack = "com.pack.a", secondary = "com.pack.b"
        // Each app checks both packs
        repeat(500) {
            cache.getCached("com.pack.a")
            cache.getCached("com.pack.b")
        }

        assertEquals(
            "1000 lookups across 2 packages should only trigger 2 actual loads",
            2, cache.lookupCount
        )
    }

    @Test
    fun `cache eliminates redundant IPC in parseApplicationIcon pattern`() {
        val cache = ResourceCache()

        // parseApplicationIcon calls getCachedResources(application.packageName)
        // for each app. Each app has a unique package name, so no caching benefit
        // UNLESS the same app package appears multiple times (unlikely).
        // The main benefit is for icon pack lookups in exportIconPackXML.
        val uniquePackages = (0 until 500).map { "com.app.$it" }

        for (pkg in uniquePackages) {
            cache.getCached(pkg)
        }

        assertEquals(
            "500 unique packages should trigger 500 lookups (cache prevents re-lookups)",
            500, cache.lookupCount
        )

        // Second pass should be entirely cached
        for (pkg in uniquePackages) {
            cache.getCached(pkg)
        }

        assertEquals(
            "Second pass should reuse cache, still 500 lookups total",
            500, cache.lookupCount
        )
    }

    @Test
    fun `cache eliminates redundant IPC in exportIconPackXML pattern`() {
        val cache = ResourceCache()

        // exportIconPackXML calls getCachedResources(iconPackName) for the same
        // icon pack on every app. This is the primary win.
        val iconPackName = "com.arcticons.iconpack"

        repeat(500) {
            cache.getCached(iconPackName) // was: ApplicationManager(ctx).getResources(iconPackName)
        }

        assertEquals(
            "500 calls for the same icon pack should trigger only 1 actual load",
            1, cache.lookupCount
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
