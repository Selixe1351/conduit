package com.selixe.conduit.mongo

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import org.bson.UuidRepresentation
import org.bson.conversions.Bson
import java.io.Closeable

/**
 * MongoDB connection to a single database.
 *
 * Kotlin data classes are mapped automatically. UUIDs are stored in the standard binary format.
 */
class Mongo(uri: String, databaseName: String) : Closeable {

    val client: MongoClient = MongoClients.create(
        MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(uri))
            .uuidRepresentation(UuidRepresentation.STANDARD)
            .build()
    )

    val database: MongoDatabase = client.getDatabase(databaseName)

    /** Returns the collection [name] mapped to [T]. */
    inline fun <reified T : Any> collection(name: String): MongoCollection<T> {
        return database.getCollection(name, T::class.java)
    }

    override fun close() {
        client.close()
    }

}

/** Returns the first document matching [filter], or null. */
fun <T : Any> MongoCollection<T>.findOne(filter: Bson): T? {
    return find(filter).first()
}

/** Returns the first document where [field] equals [value], or null. */
fun <T : Any> MongoCollection<T>.findOne(field: String, value: Any?): T? {
    return findOne(Filters.eq(field, value))
}

/** Returns every document where [field] equals [value]. */
fun <T : Any> MongoCollection<T>.findAll(field: String, value: Any?): List<T> {
    return find(Filters.eq(field, value)).toList()
}

/**
 * Atomically applies [update] to the first document matching [filter].
 *
 * @return the document after the update, or null when nothing matched
 */
fun <T : Any> MongoCollection<T>.updateAndGet(filter: Bson, update: Bson, upsert: Boolean = false): T? {
    return findOneAndUpdate(
        filter,
        update,
        FindOneAndUpdateOptions()
            .upsert(upsert)
            .returnDocument(ReturnDocument.AFTER)
    )
}
