package com.selixe.conduit.redis

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import redis.clients.jedis.Jedis
import redis.clients.jedis.args.ClientType
import redis.clients.jedis.params.ClientKillParams
import java.net.URI
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Testcontainers(disabledWithoutDocker = true)
class RedisTest {

    data class BalanceUpdate(val uuid: UUID, val balance: Long)

    companion object {
        @Container
        @JvmStatic
        val container: GenericContainer<*> = GenericContainer("redis:7").withExposedPorts(6379)
    }

    private lateinit var redis: Redis
    private lateinit var uri: String

    @BeforeEach
    fun connect() {
        uri = "redis://${container.host}:${container.firstMappedPort}"
        redis = Redis(uri)
    }

    @AfterEach
    fun close() {
        redis.close()
    }

    @Test
    fun `publish and receive typed message`() {
        val received = LinkedBlockingQueue<BalanceUpdate>()
        redis.subscribe<BalanceUpdate>("balances") { received.add(it) }
        awaitSubscribers("balances", 1)

        val update = BalanceUpdate(UUID.randomUUID(), 1500)
        redis.publish("balances", update)

        assertEquals(update, received.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `generic message types keep their element type`() {
        val received = LinkedBlockingQueue<List<BalanceUpdate>>()
        redis.subscribe<List<BalanceUpdate>>("batch") { received.add(it) }
        awaitSubscribers("batch", 1)

        val updates = listOf(BalanceUpdate(UUID.randomUUID(), 1), BalanceUpdate(UUID.randomUUID(), 2))
        redis.publish("batch", updates)

        assertEquals(updates, received.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `closed subscription stops receiving`() {
        val received = LinkedBlockingQueue<String>()
        val subscription = redis.subscribe<String>("chat") { received.add(it) }
        awaitSubscribers("chat", 1)

        subscription.close()
        awaitSubscribers("chat", 0)
        redis.publish("chat", "hello")

        assertNull(received.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `failing handler does not break other handlers`() {
        val received = LinkedBlockingQueue<String>()
        redis.subscribe<String>("chat") { error("boom") }
        redis.subscribe<String>("chat") { received.add(it) }
        awaitSubscribers("chat", 1)

        redis.publish("chat", "first")
        redis.publish("chat", "second")

        assertEquals("first", received.poll(5, TimeUnit.SECONDS))
        assertEquals("second", received.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `resubscribes after connection is killed`() {
        val received = LinkedBlockingQueue<String>()
        redis.subscribe<String>("chat") { received.add(it) }
        awaitSubscribers("chat", 1)

        admin { it.clientKill(ClientKillParams().type(ClientType.PUBSUB)) }
        awaitSubscribers("chat", 0)
        awaitSubscribers("chat", 1)

        redis.publish("chat", "back")

        assertEquals("back", received.poll(5, TimeUnit.SECONDS))
    }

    private fun <T> admin(action: (Jedis) -> T): T = Jedis(URI(uri)).use(action)

    private fun awaitSubscribers(channel: String, count: Long) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val current = admin { it.pubsubNumSub(channel)[channel] ?: 0L }
            if (current == count) return
            Thread.sleep(50)
        }
        error("Channel '$channel' never reached $count subscribers")
    }

}
