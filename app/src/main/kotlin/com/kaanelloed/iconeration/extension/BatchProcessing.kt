package com.kaanelloed.iconeration.extension

/**
 * Standard batch size for all bulk operations (icon generation, icon pack
 * building, database saves). Limits peak memory to ~50 intermediate objects
 * per batch, with [System.gc] hints between batches.
 */
const val BATCH_SIZE = 50

/**
 * Processes list items in batches with a [System.gc] hint after each batch.
 *
 * This is the standard pattern for bulk operations that create many short-lived
 * objects (bitmaps, Base64 strings, etc.) across 500+ items. Processing everything
 * in a single tight loop can cause OOM because the GC can't keep up with the
 * allocation rate. Batching gives the GC an opportunity to reclaim intermediates.
 *
 * Uses [List.subList] (a view) to avoid allocating intermediate sublists.
 * The [startIndex] parameter provides the absolute index of the first item
 * in each batch; callers that don't need it can ignore it with `_`.
 *
 * Used by [ApplicationProvider.refreshIcons], [ApplicationProvider.saveAlchemiconPack],
 * and [IconPackBuilder.buildAndSign].
 */
inline fun <T> List<T>.forEachBatch(batchSize: Int = BATCH_SIZE, action: (startIndex: Int, batch: List<T>) -> Unit) {
    for (batchStart in indices step batchSize) {
        val batch = subList(batchStart, minOf(batchStart + batchSize, size))
        action(batchStart, batch)
        System.gc()
    }
}
