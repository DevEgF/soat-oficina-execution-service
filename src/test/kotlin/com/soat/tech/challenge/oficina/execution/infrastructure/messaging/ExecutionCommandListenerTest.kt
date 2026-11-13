package com.soat.tech.challenge.oficina.execution.infrastructure.messaging

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.soat.tech.challenge.oficina.execution.infrastructure.observability.MessageTracing
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
    private val tracing = mockk<MessageTracing>(relaxed = true)
    private val listener = ExecutionCommandListener(EnvelopeCodec(), handler, tracing)

    private fun message(body: String, headers: Map<String, Any> = emptyMap()) =
        Message(body.toByteArray(), MessageProperties().also { props ->
            props.consumerQueue = "execution.commands"
            headers.forEach { (k, v) -> props.setHeader(k, v) }
        })

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

    @Test
    fun `trace headers of the message are handed to the tracing adapter before anything else`() {
        val headers = mapOf("traceparent" to "00-abc-def-01", "tracestate" to "ts", "x-correlation-id" to "ignored")

        listener.onMessage(message(valid, headers))

        verify { tracing.acceptTraceHeaders(mapOf("traceparent" to "00-abc-def-01", "tracestate" to "ts")) }
    }

    @Test
    fun `transaction is named after queue and type and carries the saga attributes`() {
        listener.onMessage(message(valid))

        verify { tracing.nameTransaction("Message", "execution.commands/StartExecution") }
        verify {
            tracing.addAttributes(
                mapOf("sagaId" to "s", "messageType" to "StartExecution", "messageId" to "m", "correlationId" to "corr-9"),
            )
        }
    }

    @Test
    fun `saga keys are in the MDC while handling and removed after`() {
        val seen = mutableMapOf<String, String?>()
        every { handler.handle(any()) } answers {
            listOf("sagaId", "messageType", "messageId").forEach { seen[it] = MDC.get(it) }
        }

        listener.onMessage(message(valid))

        assertEquals(mapOf("sagaId" to "s", "messageType" to "StartExecution", "messageId" to "m"), seen)
        listOf("sagaId", "messageType", "messageId").forEach { assertNull(MDC.get(it)) }
    }

    @Test
    fun `undecodable message still gets a named transaction`() {
        assertThrows<AmqpRejectAndDontRequeueException> { listener.onMessage(message("{ nope")) }

        verify { tracing.nameTransaction("Message", "execution.commands/invalid") }
    }
}
