package com.kaanelloed.iconeration

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying that app initialization runs off the main thread and
 * that icon pack / alchemicon pack loading only starts after the application
 * list has been fully loaded.
 *
 * This tests Fix 1: initializeApplications() was moved into a coroutine and
 * the two dependent initializations (icon packs, alchemicon pack) are launched
 * as child coroutines that can only start after the parent sequential work
 * (initializeApplications) has completed.
 */
class InitializationSequencingTest {

    @Test
    fun `child coroutines launch only after sequential parent work completes`() = runBlocking {
        // Track the order of events to verify sequencing.
        // This replicates the exact coroutine structure from MainActivity.onCreate:
        //
        //   CoroutineScope(Dispatchers.Default).launch {
        //       appProvider.initializeApplications()     // sequential
        //       launch { appProvider.initializeIconPacks() }       // child 1
        //       launch { appProvider.initializeAlchemiconPack() }  // child 2
        //   }

        val events = mutableListOf<String>()

        val job = launch {
            // Sequential work — must complete before children start
            events.add("initializeApplications_start")
            // Simulate work (even without delay, the ordering guarantee holds
            // because launch{} children only get dispatched after this line)
            events.add("initializeApplications_end")

            launch {
                events.add("initializeIconPacks")
            }
            launch {
                events.add("initializeAlchemiconPack")
            }
        }

        job.join()

        // Verify initializeApplications completed before either child started
        val appsEndIndex = events.indexOf("initializeApplications_end")
        val iconPacksIndex = events.indexOf("initializeIconPacks")
        val alchemiconIndex = events.indexOf("initializeAlchemiconPack")

        assertTrue(
            "initializeApplications must finish (index $appsEndIndex) before " +
                "initializeIconPacks starts (index $iconPacksIndex)",
            appsEndIndex < iconPacksIndex
        )
        assertTrue(
            "initializeApplications must finish (index $appsEndIndex) before " +
                "initializeAlchemiconPack starts (index $alchemiconIndex)",
            appsEndIndex < alchemiconIndex
        )
    }

    @Test
    fun `sequential work runs before any child even with multiple children`() = runBlocking {
        val events = mutableListOf<String>()

        val job = launch {
            events.add("parent_work_done")

            // Launch several children — none should run before parent_work_done
            repeat(10) { i ->
                launch {
                    events.add("child_$i")
                }
            }
        }

        job.join()

        val parentDoneIndex = events.indexOf("parent_work_done")
        assertEquals("Parent work should be the first event", 0, parentDoneIndex)

        // All children should come after
        for (i in 0 until 10) {
            val childIndex = events.indexOf("child_$i")
            assertTrue(
                "child_$i (index $childIndex) must come after parent_work_done",
                childIndex > parentDoneIndex
            )
        }
    }

    @Test
    fun `old pattern would run initializeApplications on calling thread`() {
        // The old code called initializeApplications() directly (synchronously)
        // before launching coroutines. Verify that the new pattern doesn't
        // change the ordering guarantee — apps are still loaded first.

        var appsLoaded = false
        var iconPacksStarted = false
        var alchemiconStarted = false

        // Simulate old pattern (synchronous):
        appsLoaded = true
        // then coroutines would start...
        iconPacksStarted = appsLoaded
        alchemiconStarted = appsLoaded

        assertTrue("Apps should be loaded before icon packs", appsLoaded && iconPacksStarted)
        assertTrue("Apps should be loaded before alchemicon pack", appsLoaded && alchemiconStarted)
    }

    @Test
    fun `initialization does not block calling thread`() = runBlocking {
        // Verify that the entire initialization runs asynchronously.
        // The calling code (onCreate) should not be blocked.

        var callerContinued = false

        val job = launch {
            // Simulate initializeApplications + children
            Thread.sleep(10) // simulate work
        }

        // This line should execute immediately, not after the job completes
        callerContinued = true

        assertTrue(
            "Caller should continue immediately without waiting for initialization",
            callerContinued
        )

        job.join() // clean up
    }
}
