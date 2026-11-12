package com.soat.tech.challenge.oficina.execution.infrastructure.messaging

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.MDC
import org.springframework.amqp.AmqpRejectAndDontRequeueException
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties

class ExecutionCommandListenerTest {

    private val handler = mockk<ExecutionCommandHandler>(relaxed = true)
    private val listener = ExecutionCommandListener(EnvelopeCodec(), handler)

    private fun message(body: String) = Message(body.toByteArray(), MessageProperties())

    private val valid = """{"messageId":"m","sagaId":"s","type":"StartExecution","occurredAt":"t","correlationId":"corr-9","payload":{}}"""

    @Test
    fun `undecodable body is rejected without requeue`() {
        assertThrows<AmqpRejectAndDontRequeueException> { listener.onMessage(message("{ nope")) }

        verify(exactly = 0) { handler.handle(any()) }
    }

    @Test
    fun `invalid message found by the handler is rejected without requeue`() {
        every { handler.handle(any()) } throws InvalidMessageException("unknown type")

        assertThrows<AmqpRejectAndDontRequeueException> { listener.onMessage(message(valid)) }
        assertNull(MDC.get("requestId"))
    }

    @Test
    fun `correlation id is in the MDC while handling and removed after`() {
        var seen: String? = null
        every { handler.handle(any()) } answers { seen = MDC.get("requestId") }

        listener.onMessage(message(valid))

        assertEquals("corr-9", seen)
        assertNull(MDC.get("requestId"))
    }

    @Test
    fun `other failures propagate for the listener retry`() {
        every { handler.handle(any()) } throws IllegalStateException("boom")

        assertThrows<IllegalStateException> { listener.onMessage(message(valid)) }
    }
}
