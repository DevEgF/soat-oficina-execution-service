package com.soat.tech.challenge.oficina.execution.infrastructure.persistence

import com.soat.tech.challenge.oficina.execution.support.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExecutionDocumentTest {

    @Test
    fun `document round trip preserves the aggregate`() {
        val execution = Fixtures.queued().apply { startDiagnosis(Fixtures.NOW.plusSeconds(3)) }

        val document = execution.toDocument()
        val restored = document.toDomain()

        assertEquals("wo-1", document.id)
        assertEquals(execution.workOrderId, restored.workOrderId)
        assertEquals(execution.status, restored.status)
        assertEquals(execution.vehicle, restored.vehicle)
        assertEquals(execution.services, restored.services)
        assertEquals(execution.parts, restored.parts)
        assertEquals(execution.correlationId, restored.correlationId)
        assertEquals(execution.diagnosisStartedAt, restored.diagnosisStartedAt)
        assertEquals(execution.events, restored.events)
    }
}
