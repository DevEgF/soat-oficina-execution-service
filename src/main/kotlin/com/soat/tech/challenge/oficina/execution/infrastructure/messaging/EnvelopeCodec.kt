package com.soat.tech.challenge.oficina.execution.infrastructure.messaging

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Component

/**
 * Explicit (de)serialization of the envelope; no `__TypeId__` header is involved. Unknown JSON
 * fields are ignored, as the contract requires.
 */
@Component
class EnvelopeCodec {

    private val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    fun encode(envelope: MessageEnvelope): ByteArray = mapper.writeValueAsBytes(envelope)

    fun decode(body: ByteArray): MessageEnvelope {
        val envelope = try {
            mapper.readValue<MessageEnvelope>(body)
        } catch (e: Exception) {
            throw InvalidMessageException("Message body is not a valid envelope", e)
        }
        if (envelope.messageId.isBlank() || envelope.sagaId.isBlank() || envelope.type.isBlank()) {
            throw InvalidMessageException("Envelope has blank messageId, sagaId or type")
        }
        if (!envelope.payload.isObject) throw InvalidMessageException("Envelope payload must be an object")
        return envelope
    }

    fun <T> payload(envelope: MessageEnvelope, type: Class<T>): T =
        try {
            mapper.treeToValue(envelope.payload, type)
        } catch (e: Exception) {
            throw InvalidMessageException("Payload of ${envelope.type} is invalid", e)
        }

    fun toTree(value: Any): JsonNode = mapper.valueToTree(value)
}
