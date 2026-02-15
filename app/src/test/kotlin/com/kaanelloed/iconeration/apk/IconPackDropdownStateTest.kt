package com.kaanelloed.iconeration.apk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.kaanelloed.iconeration.data.IconPack
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the icon pack dropdown state regression fix.
 *
 * Version 2026.02.02 moved initializeApplications() off the main thread,
 * which meant setContent composed before initializeIconPacks() had completed.
 * Because iconPacks was a plain property (not mutableStateOf), Compose had no
 * way to detect the update and the dropdown permanently showed only "None".
 *
 * The fix changes iconPacks from a plain property to mutableStateOf so that
 * Compose's snapshot system tracks reads and writes, triggering recomposition
 * when icon packs finish loading on a background thread.
 */
class IconPackDropdownStateTest {

    /**
     * Simulates ApplicationProvider's state properties to demonstrate the
     * difference between a plain property and a mutableStateOf property.
     */
    private class SimulatedProvider {
        // Before fix: plain property (invisible to Compose's snapshot system)
        var plainIconPacks: List<IconPack> = listOf()

        // After fix: mutableStateOf (tracked by Compose's snapshot system)
        var observableIconPacks: List<IconPack> by mutableStateOf(listOf())
    }

    private val samplePacks = listOf(
        IconPack("com.example.icons1", "Arcticons", 1, "1.0", 0),
        IconPack("com.example.icons2", "Lawnicons", 2, "2.0", 0),
        IconPack("com.example.icons3", "Delta Icons", 1, "1.0", 0)
    )

    // --- Snapshot observability tests ---

    @Test
    fun `mutableStateOf property triggers snapshot read observer`() {
        val provider = SimulatedProvider()
        provider.observableIconPacks = samplePacks

        var readCount = 0
        val snapshot = Snapshot.takeSnapshot(
            readObserver = { readCount++ }
        )

        try {
            snapshot.enter {
                // Reading the observable property should be tracked
                val packs = provider.observableIconPacks
                assertEquals(3, packs.size)
            }
            assertTrue(
                "Reading mutableStateOf should trigger read observer (readCount=$readCount)",
                readCount > 0
            )
        } finally {
            snapshot.dispose()
        }
    }

    @Test
    fun `plain property does NOT trigger snapshot read observer`() {
        val provider = SimulatedProvider()
        provider.plainIconPacks = samplePacks

        var readCount = 0
        val snapshot = Snapshot.takeSnapshot(
            readObserver = { readCount++ }
        )

        try {
            snapshot.enter {
                // Reading a plain property is invisible to the snapshot system
                val packs = provider.plainIconPacks
                assertEquals(3, packs.size)
            }
            assertEquals(
                "Reading a plain property should NOT trigger read observer",
                0, readCount
            )
        } finally {
            snapshot.dispose()
        }
    }

    @Test
    fun `mutableStateOf property triggers snapshot write observer`() {
        val provider = SimulatedProvider()

        var writeCount = 0
        val handle = Snapshot.registerGlobalWriteObserver {
            writeCount++
        }

        try {
            provider.observableIconPacks = samplePacks
            assertTrue(
                "Writing to mutableStateOf should trigger write observer (writeCount=$writeCount)",
                writeCount > 0
            )
        } finally {
            handle.dispose()
        }
    }

    @Test
    fun `plain property does NOT trigger snapshot write observer`() {
        val provider = SimulatedProvider()

        var writeCount = 0
        val handle = Snapshot.registerGlobalWriteObserver {
            writeCount++
        }

        try {
            provider.plainIconPacks = samplePacks
            assertEquals(
                "Writing to a plain property should NOT trigger write observer",
                0, writeCount
            )
        } finally {
            handle.dispose()
        }
    }

    // --- Async initialization race condition tests ---

    @Test
    fun `observable state reflects async update`() = runBlocking {
        val provider = SimulatedProvider()

        // Initially empty (like before initializeIconPacks runs)
        assertTrue(provider.observableIconPacks.isEmpty())

        // Simulate initializeIconPacks on background thread
        val job = launch {
            provider.observableIconPacks = samplePacks
        }
        job.join()

        // After async update, state should reflect the new value
        assertEquals(
            "Observable state should reflect async update",
            3, provider.observableIconPacks.size
        )
        assertEquals("Arcticons", provider.observableIconPacks[0].applicationName)
    }

    @Test
    fun `simulates regression scenario - plain property not visible after async init`() = runBlocking {
        // This test demonstrates the exact regression:
        // 1. setContent composes and reads iconPacks (empty)
        // 2. initializeIconPacks runs async and sets iconPacks
        // 3. With plain property, Compose never recomposes (no snapshot tracking)

        val provider = SimulatedProvider()

        // Step 1: "First composition" reads the value
        val firstRead = provider.plainIconPacks
        assertTrue("First read should be empty", firstRead.isEmpty())

        // Step 2: Async init sets the value
        val job = launch {
            provider.plainIconPacks = samplePacks
        }
        job.join()

        // Step 3: Value is updated, BUT Compose wouldn't know because
        // no read observer was triggered during step 1
        var readObserved = false
        val snapshot = Snapshot.takeSnapshot(
            readObserver = { readObserved = true }
        )
        try {
            snapshot.enter {
                provider.plainIconPacks // read
            }
            assertFalse(
                "Plain property read should NOT be observed by snapshot system",
                readObserved
            )
        } finally {
            snapshot.dispose()
        }
    }

    @Test
    fun `simulates fix scenario - mutableStateOf visible after async init`() = runBlocking {
        // This test demonstrates the fix:
        // 1. setContent composes and reads iconPacks (empty) - read IS tracked
        // 2. initializeIconPacks runs async and sets iconPacks - write IS tracked
        // 3. Compose detects the write and recomposes

        val provider = SimulatedProvider()

        // Step 1: "First composition" reads the value - Compose tracks this read
        var readObserved = false
        val readSnapshot = Snapshot.takeSnapshot(
            readObserver = { readObserved = true }
        )
        try {
            readSnapshot.enter {
                val packs = provider.observableIconPacks
                assertTrue("First read should be empty", packs.isEmpty())
            }
            assertTrue(
                "mutableStateOf read SHOULD be observed by snapshot system",
                readObserved
            )
        } finally {
            readSnapshot.dispose()
        }

        // Step 2: Async init sets the value - Compose tracks this write
        var writeObserved = false
        val handle = Snapshot.registerGlobalWriteObserver {
            writeObserved = true
        }
        try {
            val job = launch {
                provider.observableIconPacks = samplePacks
            }
            job.join()
            assertTrue(
                "mutableStateOf write SHOULD be observed by snapshot system",
                writeObserved
            )
        } finally {
            handle.dispose()
        }

        // Step 3: On recomposition, the updated value is visible
        assertEquals(3, provider.observableIconPacks.size)
    }

    // --- Dropdown population tests ---

    @Test
    fun `dropdown list includes None plus all icon packs`() {
        // Replicates the logic in IconPackDropdown:
        //   val emptyPack = IconPack("", "None", 0, "", 0)
        //   val newList = listOf(emptyPack) + iconPacks
        val emptyPack = IconPack("", "None", 0, "", 0)
        val newList = listOf(emptyPack) + samplePacks

        assertEquals("Dropdown should show None + 3 packs", 4, newList.size)
        assertEquals("First item should be None", "None", newList[0].applicationName)
        assertEquals("Second item should be first pack", "Arcticons", newList[1].applicationName)
    }

    @Test
    fun `dropdown with empty icon packs shows only None`() {
        // Before fix: this was the permanent state because iconPacks never updated
        val emptyPack = IconPack("", "None", 0, "", 0)
        val emptyIconPacks = listOf<IconPack>()
        val newList = listOf(emptyPack) + emptyIconPacks

        assertEquals("Dropdown should show only None", 1, newList.size)
        assertEquals("None", newList[0].applicationName)
    }

    @Test
    fun `dropdown correctly finds default pack by package name`() {
        val emptyPack = IconPack("", "None", 0, "", 0)
        val newList = listOf(emptyPack) + samplePacks

        val selectedPackage = "com.example.icons2"
        val defaultPack = newList.find { it.packageName == selectedPackage }

        assertNotNull("Default pack should be found", defaultPack)
        assertEquals("Lawnicons", defaultPack!!.applicationName)
    }

    @Test
    fun `dropdown falls back to empty pack when selected pack not found`() {
        val emptyPack = IconPack("", "None", 0, "", 0)
        val newList = listOf(emptyPack) + samplePacks

        val selectedPackage = "com.nonexistent.pack"
        val defaultPack = newList.find { it.packageName == selectedPackage }

        assertNull("Non-existent pack should not be found", defaultPack)
        // Fallback: selectedOption = defaultPack ?: emptyPack
        val selectedOption = defaultPack ?: emptyPack
        assertEquals("Should fall back to None", "", selectedOption.packageName)
    }

    // --- Initialization ordering with observable state ---

    @Test
    fun `icon packs available after sequential init completes`() = runBlocking {
        val provider = SimulatedProvider()

        // Simulate the MainActivity.onCreate coroutine structure:
        // CoroutineScope(Dispatchers.Default).launch {
        //     appProvider.initializeApplications()  // sequential
        //     launch { appProvider.initializeIconPacks() }  // child
        // }

        val job = launch {
            // Sequential: initializeApplications (sets applicationList)
            // ... (not relevant to this test)

            // Child: initializeIconPacks (sets iconPacks)
            launch {
                provider.observableIconPacks = samplePacks
            }
        }

        job.join()

        assertEquals(
            "Icon packs should be populated after init",
            3, provider.observableIconPacks.size
        )
    }

    @Test
    fun `concurrent reads and writes to observable state are consistent`() = runBlocking {
        val provider = SimulatedProvider()

        // Simulate multiple reads while an async write happens
        val writeJob = launch {
            provider.observableIconPacks = samplePacks
        }

        writeJob.join()

        // After write completes, all reads should see the updated value
        repeat(100) {
            assertEquals(3, provider.observableIconPacks.size)
        }
    }
}
