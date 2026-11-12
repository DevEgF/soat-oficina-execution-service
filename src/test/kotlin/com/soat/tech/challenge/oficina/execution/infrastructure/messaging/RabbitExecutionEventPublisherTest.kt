package com.soat.tech.challenge.oficina.execution.infrastructure.messaging

import com.soat.tech.challenge.oficina.execution.support.Fixtures
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.rabbit.core.RabbitTemplate
import java.time.Clock
import java.time.ZoneOffset

class RabbitExecutionEventPublisherTest {

    private val rabbit = mockk<RabbitTemplate>(relaxed = true)
    private val codec = EnvelopeCodec()
    private val clock = Clock.fixed(Fixtures.NOW, ZoneOffset.UTC)
    private val publisher = RabbitExecutionEventPublisher(rabbit, codec, clock)

    @Test
    fun `status changed is published with envelope, headers and stored correlation id`() {
        val execution = Fixtures.queued(correlationId = "corr-7").apply { startDiagnosis(Fixtures.NOW.plusSeconds(5)) }
        val message = slot<Message>()

        publisher.publishStatusChanged(execution)

        verify { rabbit.send("oficina", "event.execution.status-changed", capture(message)) }
        val props = message.captured.messageProperties
        val envelope = codec.decode(message.captured.body)
        assertEquals("application/json", props.contentType)
        assertEquals(MessageDeliveryMode.PERSISTENT, props.deliveryMode)
        assertEquals(envelope.messageId, props.messageId)
        assertEquals("ExecutionStatusChanged", props.type)
        assertEquals("corr-7", props.getHeader<String>("x-correlation-id"))
        assertEquals("corr-7", envelope.correlationId)
        assertEquals("wo-1", envelope.sagaId)
        assertEquals("IN_DIAGNOSIS", envelope.payload["status"].asText())
        assertEquals("2026-11-12T15:00:00Z", envelope.occurredAt)
    }

    @Test
    fun `finished is published with finishedAt`() {
        val execution = Fixtures.queued().apply {
            startDiagnosis(Fixtures.NOW); startRepair(Fixtures.NOW); finish(Fixtures.NOW.plusSeconds(9))
        }
        val message = slot<Message>()

        publisher.publishFinished(execution)

        verify { rabbit.send("oficina", "event.execution.finished", capture(message)) }
        val payload = codec.decode(message.captured.body).payload
        assertEquals("wo-1", payload["workOrderId"].asText())
        assertEquals("2026-11-12T15:00:09Z", payload["finishedAt"].asText())
    }

    @Test
    fun `missing correlation id falls back to the message id`() {
        val execution = Fixtures.queued(correlationId = null).apply { startDiagnosis(Fixtures.NOW) }
        val message = slot<Message>()

        publisher.publishStatusChanged(execution)

        verify { rabbit.send(any<String>(), any<String>(), capture(message)) }
        assertNotNull(codec.decode(message.captured.body).correlationId)
    }

    @Test
    fun `refuses to publish events the status does not allow`() {
        assertThrows<IllegalArgumentException> { publisher.publishStatusChanged(Fixtures.queued()) }
        assertThrows<IllegalArgumentException> { publisher.publishFinished(Fixtures.queued()) }
    }
}
