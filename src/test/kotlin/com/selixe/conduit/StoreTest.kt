package com.selixe.conduit

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import redis.clients.jedis.Jedis
import redis.clients.jedis.args.ClientType
import redis.clients.jedis.params.ClientKillParams
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class StoreTest {

    enum class Rank { DEFAULT, VIP }

    data class Stats(var kills: Int = 0, var deaths: Int = 0)

    data class Punishment(val type: String, val reason: String)

    data class Profile(
        @Identifier val uuid: UUID,
        var name: String,
        var rank: Rank = Rank.DEFAULT,
        var coins: Long = 0,
        val stats: Stats = Stats(),
        val friends: MutableList<UUID> = mutableListOf(),
        val punishments: MutableList<Punishment> = mutableListOf(),
        val cooldowns: MutableMap<String, Instant> = mutableMapOf(),
        var nickname: String? = null,
        val firstJoin: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    )

    interface IAccount {
        val uuid: UUID
        val coins: Long
        val punishments: List<Punishment>
    }

    data class Account(
        @Identifier override val uuid: UUID,
        override var coins: Long = 0,
        override val punishments: MutableList<Punishment> = mutableListOf()
    ) : IAccount

    data class Order(@Identifier val id: UUID, val userId: UUID, val product: String)

    companion object {
        @Container
        @JvmStatic
        val mongoContainer = MongoDBContainer("mongo:7")

        @Container
        @JvmStatic
        val redisContainer: GenericContainer<*> = GenericContainer("redis:7").withExposedPorts(6379)
    }

    private val redisUri get() = "redis://${redisContainer.host}:${redisContainer.firstMappedPort}"

    private lateinit var instanceA: Conduit
    private lateinit var instanceB: Conduit
    private lateinit var profilesA: Store<Profile, Profile>
    private lateinit var profilesB: Store<Profile, Profile>

    @BeforeEach
    fun connect() {
        val database = "test-${UUID.randomUUID()}"
        instanceA = Conduit(mongoContainer.connectionString, database, redisUri)
        instanceB = Conduit(mongoContainer.connectionString, database, redisUri)
        profilesA = instanceA.store<Profile>("profiles")
        profilesB = instanceB.store<Profile>("profiles")
        awaitSubscribers(profilesA, 2)
    }

    @AfterEach
    fun close() {
        instanceA.close()
        instanceB.close()
    }

    @Test
    fun `update on one instance is seen by the other`() {
        val uuid = UUID.randomUUID()
        profilesA.insert(Profile(uuid, "Steve"))
        assertEquals(0, profilesB.get(uuid)?.coins)

        profilesA.update(uuid) {
            it.coins += 100
            it.stats.kills++
            it.punishments.add(Punishment("MUTE", "Spam"))
        }

        awaitUntil { profilesB.get(uuid)?.coins == 100L }
        val profile = profilesB.get(uuid)!!
        assertEquals(1, profile.stats.kills)
        assertEquals(listOf(Punishment("MUTE", "Spam")), profile.punishments)
    }

    @Test
    fun `full profile round trips`() {
        val uuid = UUID.randomUUID()
        val profile = Profile(uuid, "Steve", nickname = "S")
        profile.friends.add(UUID.randomUUID())
        profile.cooldowns["kit"] = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        profilesA.insert(profile)

        assertEquals(profile, profilesB.get(uuid))
    }

    @Test
    fun `find by field sees changes from other instance`() {
        val uuid = UUID.randomUUID()
        profilesA.insert(Profile(uuid, "Steve"))
        assertEquals(uuid, profilesB.findOne(Profile::name, "Steve")?.uuid)
        assertEquals(uuid, profilesB.findOne(Profile::uuid, uuid)?.uuid)

        profilesA.update(uuid) { it.name = "Alex" }

        awaitUntil { profilesB.findOne(Profile::name, "Steve") == null }
        assertEquals(uuid, profilesB.findOne(Profile::name, "Alex")?.uuid)
    }

    @Test
    fun `list fields match single values`() {
        val friend = UUID.randomUUID()
        assertTrue(profilesB.find("friends", friend).isEmpty())

        val profile = Profile(UUID.randomUUID(), "Steve")
        profile.friends.add(friend)
        profilesA.insert(profile)

        awaitUntil { profilesB.find("friends", friend).size == 1 }
    }

    @Test
    fun `all is cached and synced`() {
        assertTrue(profilesB.all().isEmpty())

        profilesA.insert(Profile(UUID.randomUUID(), "Steve"))

        awaitUntil { profilesB.all().size == 1 }
    }

    @Test
    fun `updates from many threads are not lost`() {
        val uuid = UUID.randomUUID()
        profilesA.insert(Profile(uuid, "Steve"))

        val threads = listOf(profilesA, profilesB).flatMap { store ->
            List(4) { Thread { repeat(25) { store.update(uuid) { it.coins++ } } } }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertEquals(200, profilesA.collection.find(Filters.eq("_id", uuid)).first()?.coins)
    }

    @Test
    fun `changing a returned object without update does nothing`() {
        val uuid = UUID.randomUUID()
        profilesA.insert(Profile(uuid, "Steve"))

        profilesA.get(uuid)!!.coins = 999

        assertEquals(0, profilesA.get(uuid)?.coins)
    }

    @Test
    fun `update of missing value returns null`() {
        assertNull(profilesA.update(UUID.randomUUID()) { it.coins = 1 })
    }

    @Test
    fun `delete and save are seen by the other instance`() {
        val uuid = UUID.randomUUID()
        profilesA.save(Profile(uuid, "Steve"))
        awaitUntil { profilesB.get(uuid) != null }

        profilesA.save(Profile(uuid, "Alex"))
        awaitUntil { profilesB.get(uuid)?.name == "Alex" }

        assertTrue(profilesA.delete(uuid))
        assertFalse(profilesA.delete(uuid))
        awaitUntil { profilesB.get(uuid) == null }
    }

    @Test
    fun `missing fields use default values`() {
        val uuid = UUID.randomUUID()
        profilesA.collection.withDocumentClass(Document::class.java)
            .insertOne(Document("_id", uuid).append("name", "Steve"))

        val profile = profilesA.get(uuid)

        assertEquals(Rank.DEFAULT, profile?.rank)
        assertEquals(Stats(), profile?.stats)
    }

    @Test
    fun `direct collection writes sync after invalidateAll`() {
        val uuid = UUID.randomUUID()
        profilesA.insert(Profile(uuid, "Steve"))
        awaitUntil { profilesB.get(uuid) != null }

        profilesA.collection.updateOne(Filters.eq("_id", uuid), Updates.set("coins", 50L))
        profilesA.invalidateAll()

        awaitUntil { profilesB.get(uuid)?.coins == 50L }
    }

    @Test
    fun `cache is cleared after redis reconnects`() {
        val uuid = UUID.randomUUID()
        profilesA.collection.insertOne(Profile(uuid, "Steve", coins = 1000))
        assertEquals(1000, profilesB.get(uuid)?.coins)

        Jedis(URI(redisUri)).use { it.clientKill(ClientKillParams().type(ClientType.PUBSUB)) }
        awaitSubscribers(profilesA, 0)
        profilesA.collection.updateOne(Filters.eq("_id", uuid), Updates.set("coins", 2000L))
        awaitSubscribers(profilesA, 2)

        awaitUntil { profilesB.get(uuid)?.coins == 2000L }
    }

    @Test
    fun `works without redis`() {
        Conduit(mongoContainer.connectionString, "test-${UUID.randomUUID()}").use { conduit ->
            val profiles = conduit.store<Profile>("profiles")
            val uuid = UUID.randomUUID()

            profiles.insert(Profile(uuid, "Steve"))
            profiles.update(uuid) { it.coins += 15 }

            assertEquals(15, profiles.get(uuid)?.coins)
        }
    }

    @Test
    fun `publish and subscribe through conduit`() {
        val received = LinkedBlockingQueue<Punishment>()
        instanceB.subscribe<Punishment>("kick") { received.add(it) }
        awaitUntil { Jedis(URI(redisUri)).use { it.pubsubNumSub("kick")["kick"] ?: 0L } == 1L }

        instanceA.publish("kick", Punishment("BAN", "Cheating"))

        assertEquals(Punishment("BAN", "Cheating"), received.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `publish without redis fails clearly`() {
        Conduit(mongoContainer.connectionString, "test").use { conduit ->
            assertThrows<IllegalStateException> { conduit.publish("kick", "x") }
        }
    }

    @Test
    fun `read only store returns the interface and edits through update`() {
        val accounts: Store<IAccount, Account> = instanceA.store<IAccount, Account>("accounts")
        val uuid = UUID.randomUUID()
        accounts.insert(Account(uuid))

        val updated: IAccount? = accounts.update(uuid) {
            it.coins += 100
            it.punishments.add(Punishment("BAN", "Cheating"))
        }

        val account: IAccount = accounts.findOne(IAccount::uuid, uuid)!!
        assertEquals(100, updated?.coins)
        assertEquals(100, account.coins)
        assertEquals(1, accounts.get(uuid)?.punishments?.size)
    }

    @Test
    fun `identifier is stored as _id`() {
        val uuid = UUID.randomUUID()
        profilesA.insert(Profile(uuid, "Steve"))

        val raw = profilesA.collection.withDocumentClass(Document::class.java).find().first()!!

        assertEquals(uuid, raw["_id"])
        assertNull(raw["uuid"])
    }

    @Test
    fun `references to another store are synced`() {
        val ordersA = instanceA.store<Order>("orders")
        val ordersB = instanceB.store<Order>("orders")
        awaitSubscribers(ordersA, 2)

        val userId = UUID.randomUUID()
        assertTrue(ordersB.find(Order::userId, userId).isEmpty())

        ordersA.insert(Order(UUID.randomUUID(), userId, "book"))
        ordersA.insert(Order(UUID.randomUUID(), userId, "pen"))

        awaitUntil { ordersB.find(Order::userId, userId).size == 2 }
        assertEquals(1, ordersB.find(Order::product, "pen").size)
    }

    @Test
    fun `nested fields are rejected`() {
        assertThrows<IllegalArgumentException> { profilesA.find("stats.kills", 1) }
    }

    private fun awaitSubscribers(store: Store<*, *>, count: Long) {
        val channel = "conduit:${store.collection.namespace.fullName}"
        awaitUntil { Jedis(URI(redisUri)).use { it.pubsubNumSub(channel)[channel] ?: 0L } == count }
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        error("Condition not met within 10 seconds")
    }

}
