package com.selixe.conduit

import com.selixe.conduit.mongo.Mongo
import com.selixe.conduit.redis.Redis
import java.io.Closeable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Entry point that connects to MongoDB and, optionally, Redis.
 *
 * Without [redisUri] stores still work, but their caches are local to this process
 * and [publish] / [subscribe] throw.
 *
 * Create one per application and [close] it on shutdown.
 */
class Conduit(mongoUri: String, database: String, redisUri: String? = null) : Closeable {

    val mongo = Mongo(mongoUri, database)
    val redis = redisUri?.let(::Redis)

    /**
     * Returns a [Store] for the collection [name], where reads and writes use the same type [T].
     *
     * @param maxSize maximum number of cached lookups
     * @param expireAfterWrite how long a cached lookup lives, a safety net for missed sync messages
     */
    inline fun <reified T : Any> store(
        name: String,
        maxSize: Long = 10_000,
        expireAfterWrite: Duration = 5.minutes
    ): Store<T, T> {
        return Store(mongo.collection(name), redis, maxSize, expireAfterWrite)
    }

    /**
     * Returns a [Store] for the collection [name] whose reads return the read-only type [R].
     *
     * [T] is the stored class that implements [R]. It is only handed out inside [Store.update],
     * so data cannot be changed by accident.
     */
    @JvmName("readOnlyStore")
    inline fun <R : Any, reified T : R> store(
        name: String,
        maxSize: Long = 10_000,
        expireAfterWrite: Duration = 5.minutes
    ): Store<R, T> {
        return Store(mongo.collection(name), redis, maxSize, expireAfterWrite)
    }

    /**
     * Sends [message] as JSON to every subscriber of [channel] on every server.
     *
     * @return the number of subscribers that received it
     */
    fun publish(channel: String, message: Any): Long {
        return requireRedis().publish(channel, message)
    }

    /**
     * Calls [handler] for every message published on [channel].
     *
     * [handler] runs on a background thread. Close the returned value to stop receiving.
     */
    inline fun <reified T> subscribe(channel: String, noinline handler: (T) -> Unit): Closeable {
        return requireRedis().subscribe(channel, handler = handler)
    }

    @PublishedApi
    internal fun requireRedis(): Redis {
        return checkNotNull(redis) { "Conduit was created without a Redis URI" }
    }

    /** Closes the Redis and MongoDB connections. */
    override fun close() {
        redis?.close()
        mongo.close()
    }

}
