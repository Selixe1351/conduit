package com.selixe.conduit.cache

import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

class CacheTest {

    @Test
    fun `put and get`() {
        val cache = cache<String, Int>()
        cache.put("a", 1)

        assertEquals(1, cache.getIfPresent("a"))
        assertNull(cache.getIfPresent("b"))
    }

    @Test
    fun `evicts above max size`() {
        val cache = cache<Int, Int>(maxSize = 10)
        repeat(100) { cache.put(it, it) }
        cache.cleanUp()

        assertEquals(10, cache.estimatedSize())
    }

    @Test
    fun `applies expiration settings`() {
        val cache = cache<String, Int>(expireAfterWrite = 5.minutes, expireAfterAccess = 2.minutes)

        assertEquals(5.minutes.toJavaDuration(), cache.policy().expireAfterWrite().get().expiresAfter)
        assertEquals(2.minutes.toJavaDuration(), cache.policy().expireAfterAccess().get().expiresAfter)
    }

    @Test
    fun `loading cache loads once`() {
        val calls = AtomicInteger()
        val cache = loadingCache<String, Int> { calls.incrementAndGet(); it.length }

        assertEquals(3, cache.get("abc"))
        assertEquals(3, cache.get("abc"))
        assertEquals(1, calls.get())
    }

}
