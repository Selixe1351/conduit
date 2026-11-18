package com.selixe.conduit.redis

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPubSub
import redis.clients.jedis.RedisClient
import java.io.Closeable
import java.lang.System.Logger.Level
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Redis connection for typed publish and subscribe.
 *
 * Messages are sent as JSON. All subscriptions share one connection that reconnects
 * automatically and resubscribes every channel. Handlers run on that connection's
 * background thread, so keep them short.
 */
class Redis(private val uri: String) : Closeable {

    val client: RedisClient = RedisClient.create(URI(uri))

    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val logger = System.getLogger("Conduit")
    private val lock = Any()
    private val handlers = ConcurrentHashMap<String, CopyOnWriteArrayList<Handler>>()

    private var listener: Listener? = null
    private var connection: Jedis? = null

    @Volatile
    private var closed = false

    private val thread = Thread(::listen, "conduit-redis").apply {
        isDaemon = true
        start()
    }

    /**
     * Sends [message] as JSON to every subscriber of [channel].
     *
     * @return the number of subscribers that received it
     */
    fun publish(channel: String, message: Any): Long {
        return client.publish(channel, mapper.writeValueAsString(message))
    }

    /**
     * Calls [handler] with every message on [channel], read as [T].
     *
     * [onSubscribe] runs each time the subscription is confirmed, including after a reconnect.
     * Messages published while disconnected are lost, so use it to refresh any state that
     * depends on them. Close the returned value to unsubscribe.
     */
    inline fun <reified T> subscribe(
        channel: String,
        noinline onSubscribe: () -> Unit = {},
        noinline handler: (T) -> Unit
    ): Closeable {
        return subscribe(channel, jacksonTypeRef<T>(), onSubscribe, handler)
    }

    /** Same as the reified [subscribe], with the message type given as a [TypeReference]. */
    fun <T> subscribe(
        channel: String,
        type: TypeReference<T>,
        onSubscribe: () -> Unit = {},
        handler: (T) -> Unit
    ): Closeable {
        require(channel != INTERNAL_CHANNEL) { "Channel '$channel' is reserved" }

        val entry = Handler(onSubscribe) { handler(mapper.readValue(it, type)) }

        synchronized(lock) {
            check(!closed) { "Redis is closed" }
            val list = handlers.computeIfAbsent(channel) { CopyOnWriteArrayList() }
            list.add(entry)
            if (list.size == 1) safely { listener?.subscribe(channel) }
        }

        return Closeable {
            synchronized(lock) {
                val list = handlers[channel] ?: return@Closeable
                if (list.remove(entry) && list.isEmpty()) {
                    handlers.remove(channel)
                    safely { listener?.unsubscribe(channel) }
                }
            }
        }
    }

    /** Stops every subscription and closes both connections. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            handlers.clear()
            safely { connection?.close() }
        }
        thread.interrupt()
        thread.join(5000)
        client.close()
    }

    private fun listen() {
        while (!closed) {
            try {
                Jedis(URI(uri)).use { jedis ->
                    synchronized(lock) {
                        if (closed) return
                        connection = jedis
                    }
                    jedis.subscribe(Listener(), INTERNAL_CHANNEL)
                }
            } catch (e: Exception) {
                if (!closed) logger.log(Level.WARNING, "Redis disconnected, retrying in 3s: ${e.message}")
            }

            synchronized(lock) {
                listener = null
                connection = null
            }

            try {
                Thread.sleep(3000)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun safely(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Redis handler failed", e)
        }
    }

    private inner class Listener : JedisPubSub() {

        override fun onSubscribe(channel: String, subscribedChannels: Int) {
            if (channel == INTERNAL_CHANNEL) {
                synchronized(lock) {
                    listener = this
                    if (handlers.isNotEmpty()) subscribe(*handlers.keys.toTypedArray())
                }
            } else {
                handlers[channel]?.forEach { safely(it.onSubscribe) }
            }
        }

        override fun onMessage(channel: String, message: String) {
            handlers[channel]?.forEach { safely { it.onMessage(message) } }
        }

    }

    private class Handler(val onSubscribe: () -> Unit, val onMessage: (String) -> Unit)

    private companion object {
        const val INTERNAL_CHANNEL = "conduit:internal"
    }

}
