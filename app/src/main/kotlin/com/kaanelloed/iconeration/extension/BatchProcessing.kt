package com.kaanelloed.iconeration.extension

/**
 * Processes items in batches with a [System.gc] hint between each batch.
 *
 * This is the standard pattern for bulk operations that create many short-lived
 * objects (bitmaps, Base64 strings, etc.) across 500+ items. Processing everything
 * in a single tight loop can cause OOM because the GC can't keep up with the
 * allocation rate. Batching gives the GC an opportunity to reclaim intermediates.
 *
 * Used by [ApplicationProvider.refreshIcons], [ApplicationProvider.saveAlchemiconPack],
 * and [IconPackBuilder.buildAndSign].
 */
inline fun <T> Iterable<T>.forEachBatch(batchSize: Int, action: (batch: List<T>) -> Unit) {
    for (batch in chunked(batchSize)) {
        action(batch)
        System.gc()
    }
}

/**
 * Like [forEachBatch] but provides the start index of each batch within the
 * original list. Uses [List.subList] (a view) instead of [chunked] to avoid
 * allocating intermediate sublists.
 *
 * Used by [ApplicationProvider.refreshIcons] which needs absolute indices
 * to map generated icons back to their position in the application list.
 */
inline fun <T> List<T>.forEachBatchIndexed(batchSize: Int, action: (startIndex: Int, batch: List<T>) -> Unit) {
    for (batchStart in indices step batchSize) {
        val batch = subList(batchStart, minOf(batchStart + batchSize, size))
        action(batchStart, batch)
        System.gc()
    }
}
