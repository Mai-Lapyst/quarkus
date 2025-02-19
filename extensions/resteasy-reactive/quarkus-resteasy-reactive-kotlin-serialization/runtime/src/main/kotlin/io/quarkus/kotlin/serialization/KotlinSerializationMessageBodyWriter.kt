package io.quarkus.kotlin.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializer
import org.jboss.resteasy.reactive.common.providers.serialisers.JsonMessageBodyWriterUtil
import org.jboss.resteasy.reactive.server.providers.serialisers.json.JsonMessageServerBodyWriterUtil
import org.jboss.resteasy.reactive.server.spi.ServerMessageBodyWriter.AllWriteableMessageBodyWriter
import org.jboss.resteasy.reactive.server.spi.ServerRequestContext
import java.io.OutputStream
import java.lang.reflect.Type
import javax.inject.Inject
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.MultivaluedMap

@Produces("application/json", "application/*+json", "text/json")
@OptIn(ExperimentalSerializationApi::class)
class KotlinSerializationMessageBodyWriter(@Inject var json: Json) : AllWriteableMessageBodyWriter() {
    override fun writeTo(
        o: Any, type: Class<*>, genericType: Type, annotations: Array<Annotation>, mediaType: MediaType,
        httpHeaders: MultivaluedMap<String, Any>, entityStream: OutputStream
    ) {
        JsonMessageBodyWriterUtil.setContentTypeIfNecessary(httpHeaders)
        if (o is String) { // YUK: done in order to avoid adding extra quotes...
            entityStream.write(o.toByteArray())
        } else {
            json.encodeToStream(o, entityStream)
        }
    }

    override fun writeResponse(o: Any, genericType: Type, context: ServerRequestContext) {
        JsonMessageServerBodyWriterUtil.setContentTypeIfNecessary(context)
        val originalStream = context.orCreateOutputStream
        val stream: OutputStream = NoopCloseAndFlushOutputStream(originalStream)

        if (o is String) { // YUK: done in order to avoid adding extra quotes...
            stream.write(o.toByteArray())
        } else {
            val encodeToString = json.encodeToString(json.serializersModule.serializer(genericType), o)
            stream.write(encodeToString.toByteArray())
        }
        // we don't use try-with-resources because that results in writing to the http output without the exception mapping coming into play
        originalStream.close()
    }

    private class NoopCloseAndFlushOutputStream(private val delegate: OutputStream) : OutputStream() {
        override fun flush() {}
        override fun close() {}
        override fun write(b: Int) {
            delegate.write(b)
        }

        override fun write(b: ByteArray) {
            delegate.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
        }
    }
}