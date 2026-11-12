package com.soat.tech.challenge.oficina.execution.infrastructure.messaging

import com.soat.tech.challenge.oficina.execution.application.ExecutionApplicationService
import com.soat.tech.challenge.oficina.execution.application.ProcessedMessageStore
import com.soat.tech.challenge.oficina.execution.application.QueueExecutionCommand
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ExecutionCommandHandlerTest {

    private val service = mockk<ExecutionApplicationService>()
    private val processed = mockk<ProcessedMessageStore>(relaxed = true)
    private val codec = EnvelopeCodec()
    private val handler = ExecutionCommandHandler(service, processed, codec)

    private fun envelope(type: String = "StartExecution", payload: String = VALID_PAYLOAD, messageId: String = "m-1") =
        codec.decode(
            """{"messageId":"$messageId","sagaId":"wo-1","type":"$type","occurredAt":"2026-11-12T15:04:05Z",
               "correlationId":"corr-1","payload":$payload}""".toByteArray(),
        )

    @Test
    fun `StartExecution queues the execution and records the message`() {
        every { processed.isProcessed("m-1") } returns false
        val command = slot<QueueExecutionCommand>()
        every { service.queue(capture(command)) } returns true

        handler.handle(envelope())

        assertEquals("wo-1", command.captured.workOrderId)
        assertEquals("corr-1", command.captured.correlationId)
        assertEquals("Maria", command.captured.customerName)
        assertEquals(2, command.captured.parts.single().quantity)
        verify(exactly = 1) { processed.markProcessed("m-1", "StartExecution") }
    }

    @Test
    fun `repeated message is ignored without effect`() {
        every { processed.isProcessed("m-1") } returns true

        handler.handle(envelope())

        verify(exactly = 0) { service.queue(any()) }
        verify(exactly = 0) { processed.markProcessed(any(), any()) }
    }

    @Test
    fun `known type this service does not handle is acked without effect`() {
        every { processed.isProcessed(any()) } returns false

        handler.handle(envelope(type = "ExecutionFailed", payload = "{}"))
        handler.handle(envelope(type = "GenerateQuote", payload = "{}", messageId = "m-2"))

        verify(exactly = 0) { service.queue(any()) }
        verify(exactly = 0) { processed.markProcessed(any(), any()) }
    }

    @Test
    fun `unknown type is invalid`() {
        assertThrows<InvalidMessageException> { handler.handle(envelope(type = "Bogus", payload = "{}")) }
    }

    @Test
    fun `invalid payload is invalid and not recorded`() {
        every { processed.isProcessed(any()) } returns false

        assertThrows<InvalidMessageException> { handler.handle(envelope(payload = """{"workOrderId":"wo-1"}""")) }

        verify(exactly = 0) { processed.markProcessed(any(), any()) }
    }

    @Test
    fun `failure of the service propagates so the listener retries`() {
        every { processed.isProcessed(any()) } returns false
        every { service.queue(any()) } throws IllegalStateException("mongo down")

        assertThrows<IllegalStateException> { handler.handle(envelope()) }

        verify(exactly = 0) { processed.markProcessed(any(), any()) }
    }

    private companion object {
        const val VALID_PAYLOAD = """{"workOrderId":"wo-1","trackingCode":"T","customerName":"Maria",
            "vehicle":{"plate":"ABC1D23","brand":"Fiat","model":"Argo","year":2022},
            "services":[{"code":"TROCA_OLEO","quantity":1}],"parts":[{"code":"FILTRO_OLEO","quantity":2}]}"""
    }
}
