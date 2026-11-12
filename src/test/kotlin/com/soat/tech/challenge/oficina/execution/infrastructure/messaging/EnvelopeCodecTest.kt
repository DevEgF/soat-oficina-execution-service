package com.soat.tech.challenge.oficina.execution.infrastructure.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EnvelopeCodecTest {

    private val codec = EnvelopeCodec()

    private val startExecutionJson = """
        {"messageId":"m-1","sagaId":"wo-1","type":"StartExecution","occurredAt":"2026-11-12T15:04:05.123Z",
         "correlationId":"corr-1","unknownField":42,
         "payload":{"workOrderId":"wo-1","trackingCode":"T","customerName":"Maria","extra":true,
           "vehicle":{"plate":"ABC1D23","brand":"Fiat","model":"Argo","year":2022},
           "services":[{"code":"TROCA_OLEO","quantity":1}],"parts":[]}}
    """.trimIndent()

    @Test
    fun `decodes the envelope ignoring unknown fields and maps the payload`() {
        val envelope = codec.decode(startExecutionJson.toByteArray())
        val payload = codec.payload(envelope, StartExecutionPayload::class.java)

        assertEquals("m-1", envelope.messageId)
        assertEquals("corr-1", envelope.correlationId)
        assertEquals("ABC1D23", payload.vehicle.plate)
        assertEquals(listOf(ItemPayload("TROCA_OLEO", 1)), payload.services)
    }

    @Test
    fun `round trips through encode and decode`() {
        val original = codec.decode(startExecutionJson.toByteArray())

        assertEquals(original, codec.decode(codec.encode(original)))
    }

    @Test
    fun `correlation id is optional on reading`() {
        val json = """{"messageId":"m","sagaId":"s","type":"X","occurredAt":"t","payload":{}}"""

        assertNull(codec.decode(json.toByteArray()).correlationId)
    }

    @Test
    fun `invalid bodies are rejected`() {
        listOf(
            "not json",
            "[]",
            """{"messageId":"m"}""",
            """{"messageId":" ","sagaId":"s","type":"X","occurredAt":"t","payload":{}}""",
            """{"messageId":"m","sagaId":"s","type":"X","occurredAt":"t","payload":"text"}""",
        ).forEach { body ->
            assertThrows<InvalidMessageException>(body) { codec.decode(body.toByteArray()) }
        }
    }

    @Test
    fun `payload missing required fields is invalid`() {
        val envelope = codec.decode(
            """{"messageId":"m","sagaId":"s","type":"StartExecution","occurredAt":"t","payload":{"workOrderId":"s"}}""".toByteArray(),
        )

        assertThrows<InvalidMessageException> { codec.payload(envelope, StartExecutionPayload::class.java) }
    }

    @Test
    fun `message types resolve by wire name`() {
        assertEquals(MessageType.StartExecution, MessageType.fromWireName("StartExecution"))
        assertNull(MessageType.fromWireName("Nope"))
        assertEquals("event.execution.status-changed", MessageType.ExecutionStatusChanged.routingKey)
    }
}
