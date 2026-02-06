package com.kaanelloed.iconeration.apk

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kaanelloed.iconeration.data.AlchemiconPackDatabase
import com.kaanelloed.iconeration.data.DbApplication
import com.kaanelloed.iconeration.extension.toBase64
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for verifying memory-efficient icon saving.
 * These tests ensure the OOM fix works correctly on actual devices.
 */
@RunWith(AndroidJUnit4::class)
class IconSaveMemoryTest {

    private lateinit var db: AlchemiconPackDatabase

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AlchemiconPackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun cleanup() {
        db.close()
    }

    @Test
    fun bitmapCanBeRecycledAfterCompression() {
        // Create a test bitmap
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        assertFalse("Bitmap should not be recycled initially", bitmap.isRecycled)

        // Compress to bytes (simulating what createBitmapResource does)
        val bytes = bitmap.compress(Bitmap.CompressFormat.PNG, 100, java.io.ByteArrayOutputStream())
        assertTrue("Compression should succeed", bytes)

        // Recycle the bitmap
        bitmap.recycle()
        assertTrue("Bitmap should be recycled after compression", bitmap.isRecycled)
    }

    @Test
    fun batchedDatabaseInsertWorksCorrectly() {
        val dao = db.alchemiconPackDao()

        // Simulate 150 apps (3 batches with batch size of 50)
        val totalApps = 150
        val batchSize = ApplicationProvider.DB_SAVE_BATCH_SIZE

        dao.deleteAllApplications()

        // Process in batches like saveAlchemiconPack does
        val apps = (1..totalApps).map { i ->
            DbApplication(
                packageName = "com.test.app$i",
                activityName = ".MainActivity",
                isAdaptiveIcon = false,
                isXml = false,
                drawable = "testBase64Data$i"
            )
        }

        for (batch in apps.chunked(batchSize)) {
            dao.insertAll(batch)
        }

        // Verify all apps were saved
        val savedApps = dao.getAll()
        assertEquals("All $totalApps apps should be saved", totalApps, savedApps.size)
    }

    @Test
    fun batchedInsertPreservesData() {
        val dao = db.alchemiconPackDao()
        dao.deleteAllApplications()

        // Create test data with varied properties
        val testApps = listOf(
            DbApplication("com.app1", ".Activity1", true, true, "base64data1"),
            DbApplication("com.app2", ".Activity2", false, false, "base64data2"),
            DbApplication("com.app3", ".Activity3", true, false, "base64data3")
        )

        // Insert in a single batch
        dao.insertAll(testApps)

        // Verify data integrity
        val saved = dao.getAll()
        assertEquals(3, saved.size)

        val app1 = saved.find { it.packageName == "com.app1" }
        assertNotNull(app1)
        assertEquals(".Activity1", app1?.activityName)
        assertTrue(app1?.isAdaptiveIcon == true)
        assertTrue(app1?.isXml == true)
        assertEquals("base64data1", app1?.drawable)
    }

    @Test
    fun bitmapToBase64DoesNotLeakMemory() {
        val runtime = Runtime.getRuntime()

        // Force GC and get baseline memory
        System.gc()
        Thread.sleep(100)
        val baselineUsedMemory = runtime.totalMemory() - runtime.freeMemory()

        // Process multiple bitmaps in batches
        val batchSize = ApplicationProvider.DB_SAVE_BATCH_SIZE
        val numBatches = 3

        repeat(numBatches) { batchIndex ->
            // Create and process a batch of bitmaps
            repeat(batchSize) {
                val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                val base64 = bitmap.toBase64(Bitmap.CompressFormat.PNG, 100)
                bitmap.recycle()
                // base64 string will be garbage collected after this scope
            }

            // GC between batches like the fix does
            System.gc()
            Thread.sleep(50)
        }

        // Final GC
        System.gc()
        Thread.sleep(100)
        val finalUsedMemory = runtime.totalMemory() - runtime.freeMemory()

        // Memory growth should be minimal after processing
        // Allow for some variance but shouldn't be holding onto all bitmap data
        val memoryGrowthMB = (finalUsedMemory - baselineUsedMemory) / (1024.0 * 1024.0)

        // With proper cleanup, memory growth should be under 10MB
        // (accounting for VM overhead and other factors)
        assertTrue(
            "Memory growth ($memoryGrowthMB MB) should be minimal after batched processing",
            memoryGrowthMB < 10
        )
    }

    @Test
    fun multipleBatchInsertsDoNotDuplicateData() {
        val dao = db.alchemiconPackDao()
        dao.deleteAllApplications()

        // Insert first batch
        val batch1 = listOf(
            DbApplication("com.app1", ".Main", false, false, "data1"),
            DbApplication("com.app2", ".Main", false, false, "data2")
        )
        dao.insertAll(batch1)

        // Insert second batch
        val batch2 = listOf(
            DbApplication("com.app3", ".Main", false, false, "data3"),
            DbApplication("com.app4", ".Main", false, false, "data4")
        )
        dao.insertAll(batch2)

        // Verify no duplicates
        val all = dao.getAll()
        assertEquals(4, all.size)
        assertEquals(4, all.map { it.packageName }.distinct().size)
    }

    @Test
    fun emptyBatchHandledCorrectly() {
        val dao = db.alchemiconPackDao()
        dao.deleteAllApplications()

        // Insert empty list should not throw
        dao.insertAll(emptyList())

        val all = dao.getAll()
        assertTrue("Database should be empty after inserting empty list", all.isEmpty())
    }

    @Test
    fun deleteAllBeforeBatchInsertWorksCorrectly() {
        val dao = db.alchemiconPackDao()

        // Insert some initial data
        dao.insertAll(listOf(
            DbApplication("com.old1", ".Main", false, false, "old1"),
            DbApplication("com.old2", ".Main", false, false, "old2")
        ))
        assertEquals(2, dao.getAll().size)

        // Delete all and insert new data in batches (like saveAlchemiconPack does)
        dao.deleteAllApplications()

        val newApps = (1..5).map {
            DbApplication("com.new$it", ".Main", false, false, "new$it")
        }
        for (batch in newApps.chunked(2)) {
            dao.insertAll(batch)
        }

        // Verify only new data exists
        val all = dao.getAll()
        assertEquals(5, all.size)
        assertTrue(all.all { it.packageName.startsWith("com.new") })
    }
}
