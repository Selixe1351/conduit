package com.selixe.conduit.mongo

import com.selixe.conduit.Identifier
import org.bson.BsonDocument
import org.bson.BsonDocumentReader
import org.bson.BsonDocumentWriter
import org.bson.BsonReader
import org.bson.BsonWriter
import org.bson.codecs.BsonDocumentCodec
import org.bson.codecs.Codec
import org.bson.codecs.DecoderContext
import org.bson.codecs.EncoderContext
import org.bson.codecs.configuration.CodecProvider
import org.bson.codecs.configuration.CodecRegistry
import org.bson.codecs.kotlin.DataClassCodecProvider
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor

internal class IdentifierCodecProvider : CodecProvider {

    private val dataClasses = DataClassCodecProvider()

    override fun <T> get(clazz: Class<T>, registry: CodecRegistry): Codec<T>? {
        val field = identifierField(clazz) ?: return null
        @Suppress("UNCHECKED_CAST")
        val codec = dataClasses.get(clazz as Class<Any>, registry) as Codec<T>? ?: return null
        return IdentifierCodec(codec, field)
    }

}

internal fun identifierField(clazz: Class<*>): String? {
    if (!clazz.kotlin.isData) return null
    return clazz.kotlin.primaryConstructor?.parameters?.find { it.findAnnotation<Identifier>() != null }?.name
}

private class IdentifierCodec<T>(private val codec: Codec<T>, private val field: String) : Codec<T> {

    private val documentCodec = BsonDocumentCodec()

    override fun getEncoderClass(): Class<T> = codec.encoderClass

    override fun encode(writer: BsonWriter, value: T, context: EncoderContext) {
        val document = BsonDocument()
        codec.encode(BsonDocumentWriter(document), value, context)

        val renamed = BsonDocument()
        document.remove(field)?.let { renamed["_id"] = it }
        renamed.putAll(document)
        documentCodec.encode(writer, renamed, context)
    }

    override fun decode(reader: BsonReader, context: DecoderContext): T {
        val document = documentCodec.decode(reader, context)

        val renamed = BsonDocument()
        document.forEach { (key, value) -> renamed[if (key == "_id") field else key] = value }
        return codec.decode(BsonDocumentReader(renamed), context)
    }

}
