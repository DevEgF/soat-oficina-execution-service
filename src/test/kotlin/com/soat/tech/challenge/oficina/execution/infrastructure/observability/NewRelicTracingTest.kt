package com.soat.tech.challenge.oficina.execution.infrastructure.observability

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/** Without the agent the New Relic API is a no-op: the adapter must be safe to call. */
class NewRelicTracingTest {

    private val tracing = NewRelicTracing()

    @Test
    fun `accepting trace headers without the agent does nothing`() {
        assertDoesNotThrow {
            tracing.acceptTraceHeaders(mapOf("traceparent" to "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"))
            tracing.acceptTraceHeaders(emptyMap())
        }
    }

    @Test
    fun `naming and attributes without the agent do nothing`() {
        assertDoesNotThrow {
            tracing.nameTransaction("Message", "execution.commands/StartExecution")
            tracing.addAttributes(mapOf("sagaId" to "s"))
        }
    }
}
