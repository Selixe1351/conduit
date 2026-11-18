package com.selixe.conduit

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

private val executor = Executors.newVirtualThreadPerTaskExecutor()

/**
 * Runs [block] on a virtual thread.
 *
 * Use it to keep blocking calls, such as database reads, off the calling thread.
 * If [block] throws, the returned future completes exceptionally.
 */
fun <T> async(block: () -> T): CompletableFuture<T> {
    return CompletableFuture.supplyAsync(block, executor)
}
