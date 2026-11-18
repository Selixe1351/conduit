package com.selixe.conduit.cache

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Creates a local in-memory cache.
 *
 * @param maxSize maximum number of entries, unbounded when null
 * @param expireAfterWrite removes entries this long after they were written
 * @param expireAfterAccess removes entries this long after they were last read or written
 */
fun <K : Any, V : Any> cache(
    maxSize: Long? = null,
    expireAfterWrite: Duration? = null,
    expireAfterAccess: Duration? = null
): Cache<K, V> {
    return builder(maxSize, expireAfterWrite, expireAfterAccess).build()
}

/**
 * Creates a local in-memory cache that fills missing entries with [loader].
 *
 * [loader] runs at most once per missing key at a time and must not return null.
 */
fun <K : Any, V : Any> loadingCache(
    maxSize: Long? = null,
    expireAfterWrite: Duration? = null,
    expireAfterAccess: Duration? = null,
    loader: (K) -> V
): LoadingCache<K, V> {
    return builder(maxSize, expireAfterWrite, expireAfterAccess).build { key -> loader(key) }
}

private fun builder(
    maxSize: Long?,
    expireAfterWrite: Duration?,
    expireAfterAccess: Duration?
): Caffeine<Any, Any> {
    val builder = Caffeine.newBuilder()
    maxSize?.let { builder.maximumSize(it) }
    expireAfterWrite?.let { builder.expireAfterWrite(it.toJavaDuration()) }
    expireAfterAccess?.let { builder.expireAfterAccess(it.toJavaDuration()) }
    return builder
}
