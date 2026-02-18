package com.kaanelloed.iconeration.icon.creator

/**
 * A cache that stores nullable results keyed by [String], correctly distinguishing
 * between "not yet looked up" and "looked up but returned null".
 *
 * Unlike [HashMap.getOrPut], which cannot cache null values (it uses `get()` internally
 * and cannot distinguish an absent key from a key mapped to null), this class uses
 * [HashMap.containsKey] to ensure null results are cached and the [loader] is invoked
 * at most once per key.
 *
 * @param V the type of cached values
 * @param loader function invoked on cache miss to produce the value for a given key
 */
class ResourceCache<V>(private val loader: (String) -> V?) {
    private val cache = HashMap<String, V?>()

    fun get(key: String): V? {
        if (cache.containsKey(key)) {
            return cache[key]
        }
        val result = loader(key)
        cache[key] = result
        return result
    }
}
