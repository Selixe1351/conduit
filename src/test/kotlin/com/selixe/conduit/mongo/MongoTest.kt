package com.selixe.conduit.mongo

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import org.bson.codecs.pojo.annotations.BsonId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Testcontainers(disabledWithoutDocker = true)
class MongoTest {

    data class PlayerData(
        @param:BsonId val uuid: UUID,
        val name: String,
        val balance: Long
    )

    companion object {
        @Container
        @JvmStatic
        val container = MongoDBContainer("mongo:7")
    }

    private lateinit var mongo: Mongo
    private lateinit var players: MongoCollection<PlayerData>

    @BeforeEach
    fun connect() {
        mongo = Mongo(container.connectionString, "test")
        players = mongo.collection("players")
        players.deleteMany(Filters.empty())
    }

    @AfterEach
    fun close() {
        mongo.close()
    }

    @Test
    fun `insert and find data class`() {
        val player = PlayerData(UUID.randomUUID(), "Steve", 1000)
        players.insertOne(player)

        assertEquals(player, players.findOne("_id", player.uuid))
    }

    @Test
    fun `missing document returns null`() {
        assertNull(players.findOne("_id", UUID.randomUUID()))
    }

    @Test
    fun `updateAndGet returns document after update`() {
        val uuid = UUID.randomUUID()
        players.insertOne(PlayerData(uuid, "Steve", 1000))

        val updated = players.updateAndGet(Filters.eq("_id", uuid), Updates.inc("balance", 500L))

        assertEquals(1500, updated?.balance)
    }

    @Test
    fun `updateAndGet returns null when filter does not match`() {
        val uuid = UUID.randomUUID()
        players.insertOne(PlayerData(uuid, "Steve", 100))

        val updated = players.updateAndGet(
            Filters.and(Filters.eq("_id", uuid), Filters.gte("balance", 500L)),
            Updates.inc("balance", -500L)
        )

        assertNull(updated)
        assertEquals(100, players.findOne("_id", uuid)?.balance)
    }

    @Test
    fun `uuid is stored as standard binary`() {
        val uuid = UUID.randomUUID()
        players.insertOne(PlayerData(uuid, "Steve", 0))

        val raw = mongo.database.getCollection("players").find().first()!!

        assertEquals(uuid, raw.get("_id", UUID::class.java))
    }

}
