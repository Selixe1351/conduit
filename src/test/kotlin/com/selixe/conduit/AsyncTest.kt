package com.selixe.conduit

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AsyncTest {

    @Test
    fun `runs on a virtual thread`() {
        val thread = async { Thread.currentThread() }.get(5, TimeUnit.SECONDS)

        assertTrue(thread.isVirtual)
    }

    @Test
    fun `returns the result`() {
        assertEquals(42, async { 21 * 2 }.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `propagates exceptions`() {
        val error = assertThrows<ExecutionException> {
            async { error("boom") }.get(5, TimeUnit.SECONDS)
        }

        assertEquals("boom", error.cause?.message)
    }

}
