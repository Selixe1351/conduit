package com.selixe.conduit

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndReplaceOptions
import com.selixe.conduit.cache.cache
import com.selixe.conduit.redis.Redis
import org.bson.BsonDocumentReader
import org.bson.BsonDocumentWrapper
import org.bson.Document
import org.bson.codecs.DecoderContext
import org.bson.codecs.pojo.annotations.BsonId
import java.lang.System.Logger.Level
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.time.Duration

/**
 * A MongoDB collection with a cache that stays in sync across application instances.
 *
 * Reads are cached. Writes go to MongoDB first, then every instance drops the cached lookups
 * the write could affect, so the next read loads fresh data.
 *
 * Reads return [R]. Values can only be changed inside [update], which hands out the stored
 * class [T]. Objects returned by reads are copies, changing them does nothing.
 *
 * Writes made directly on [collection] are not synced, call [invalidateAll] after them.
 */
class Store<R : Any, T : R>(
    val collection: MongoCollection<T>,
    private val redis: Redis?,
    maxSize: Long,
    expireAfterWrite: Duration
) {

    private val logger = System.getLogger("Conduit")
    private val channel = "conduit:${collection.namespace.fullName}"
    private val documents = collection.withDocumentClass(Document::class.java)
    private val documentCodec = collection.codecRegistry.get(Document::class.java)
    private val valueCodec = collection.codecRegistry.get(collection.documentClass)
    private val idProperty = collection.documentClass.kotlin.primaryConstructor
        ?.parameters?.find { it.findAnnotation<BsonId>() != null }?.name
    private val cache = cache<String, List<Document>>(maxSize = maxSize, expireAfterWrite = expireAfterWrite)

    init {
        redis?.subscribe<Change>(channel, onSubscribe = { cache.invalidateAll() }) { apply(it) }
    }

    /** Returns the value with this id, or null. */
    fun get(id: Any): R? = findOne("_id", id)

    /** Returns every value in the collection. */
    fun all(): List<R> = cache.get(ALL) { documents.find().toList() }.map(::fromDocument)

    /** Returns every value where [property] equals [value], or contains it when it is a list. */
    fun <V> find(property: KProperty1<R, V>, value: V): List<R> = find(fieldName(property), value)

    /** Returns the first value where [property] equals [value], or null. */
    fun <V> findOne(property: KProperty1<R, V>, value: V): R? = find(property, value).firstOrNull()

    /** Returns the first value where the top-level [field] equals [value], or null. */
    fun findOne(field: String, value: Any?): R? = find(field, value).firstOrNull()

    /**
     * Returns every value where the top-level [field] equals [value].
     *
     * Nested fields such as `stats.kills` are rejected, query [collection] for those.
     */
    fun find(field: String, value: Any?): List<R> {
        require('.' !in field) { "Nested fields are not cached, use collection directly" }
        return cache.get(key(field, value)) { documents.find(Filters.eq(field, value)).toList() }.map(::fromDocument)
    }

    /** Inserts a new value. Fails if one with the same id exists. */
    fun insert(value: T) {
        collection.insertOne(value)
        changed(listOf(toDocument(value)))
    }

    /** Inserts [value], or replaces the stored value with the same id. */
    fun save(value: T) {
        val document = toDocument(value)
        val id = requireNotNull(document["_id"]) { "Value has no _id" }
        val before = documents.findOneAndReplace(Filters.eq("_id", id), document, FindOneAndReplaceOptions().upsert(true))
        changed(listOf(before, document))
    }

    /**
     * Loads the value with this id, lets [change] edit it, and saves it.
     *
     * If another instance changes the same value at the same time, the save is rejected and
     * [change] runs again on the fresh value, so no write is lost. Because of that, [change]
     * should only edit the value and not cause side effects.
     *
     * @return the saved value, or null when no value has this id
     */
    fun update(id: Any, change: (T) -> Unit): R? {
        repeat(MAX_ATTEMPTS) {
            val before = documents.find(Filters.eq("_id", id)).first() ?: return null
            val value = fromDocument(before)
            change(value)
            val after = toDocument(value)
            require(after["_id"] == before["_id"]) { "update must not change the _id" }

            val unchanged = Document("\$expr", Document("\$eq", listOf("\$\$ROOT", Document("\$literal", before))))
            if (documents.replaceOne(unchanged, after).matchedCount == 1L) {
                changed(listOf(before, after))
                return value
            }
        }
        error("Could not update $id after $MAX_ATTEMPTS attempts")
    }

    /** Deletes the value with this id. Returns false when there was none. */
    fun delete(id: Any): Boolean {
        val before = documents.findOneAndDelete(Filters.eq("_id", id)) ?: return false
        changed(listOf(before))
        return true
    }

    /** Clears the cache on every instance. Call it after writing to [collection] directly. */
    fun invalidateAll() {
        sync(Change(all = true))
    }

    private fun changed(documents: List<Document?>) {
        val keys = documents.filterNotNull().flatMap { document ->
            document.flatMap { (field, value) ->
                val elements = (value as? Collection<*>).orEmpty().map { key(field, it) }
                elements + key(field, value)
            }
        }
        sync(Change((keys + ALL).distinct()))
    }

    private fun sync(change: Change) {
        apply(change)
        try {
            redis?.publish(channel, change)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to sync change on '$channel'", e)
        }
    }

    private fun apply(change: Change) {
        if (change.all) cache.invalidateAll() else cache.invalidateAll(change.keys)
    }

    private fun fieldName(property: KProperty1<R, *>): String {
        return if (property.name == idProperty) "_id" else property.name
    }

    private fun toDocument(value: T): Document {
        val bson = BsonDocumentWrapper.asBsonDocument(value, collection.codecRegistry)
        return documentCodec.decode(BsonDocumentReader(bson), DecoderContext.builder().build())
    }

    private fun fromDocument(document: Document): T {
        val bson = document.toBsonDocument(Document::class.java, collection.codecRegistry)
        return valueCodec.decode(BsonDocumentReader(bson), DecoderContext.builder().build())
    }

    private fun key(field: String, value: Any?): String = "$field:$value"

    internal data class Change(val keys: List<String> = emptyList(), val all: Boolean = false)

    private companion object {
        const val ALL = "*"
        const val MAX_ATTEMPTS = 100
    }

}
