package com.soat.tech.challenge.oficina.execution.domain

import com.soat.tech.challenge.oficina.execution.domain.exception.InvalidStatusTransitionException
import com.soat.tech.challenge.oficina.execution.domain.model.ExecutionStatus
import com.soat.tech.challenge.oficina.execution.support.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ExecutionTest {

    private val t1 = Fixtures.NOW.plusSeconds(10)
    private val t2 = Fixtures.NOW.plusSeconds(20)
    private val t3 = Fixtures.NOW.plusSeconds(30)

    @Test
    fun `queue creates a QUEUED execution with its first event`() {
        val execution = Fixtures.queued()

        assertEquals(ExecutionStatus.QUEUED, execution.status)
        assertEquals(Fixtures.NOW, execution.queuedAt)
        assertNull(execution.diagnosisStartedAt)
        assertEquals(listOf("ExecutionQueued"), execution.events.map { it.type })
    }

    @Test
    fun `happy path walks QUEUED to FINISHED recording dates and events`() {
        val execution = Fixtures.queued()

        execution.startDiagnosis(t1)
        execution.startRepair(t2)
        execution.finish(t3)

        assertEquals(ExecutionStatus.FINISHED, execution.status)
        assertEquals(t1, execution.diagnosisStartedAt)
        assertEquals(t2, execution.repairStartedAt)
        assertEquals(t3, execution.finishedAt)
        assertEquals(
            listOf(ExecutionStatus.QUEUED, ExecutionStatus.IN_DIAGNOSIS, ExecutionStatus.IN_EXECUTION, ExecutionStatus.FINISHED),
            execution.events.map { it.status },
        )
    }

    @Test
    fun `startDiagnosis only from QUEUED`() {
        val execution = Fixtures.queued().apply { startDiagnosis(t1) }

        val error = assertThrows<InvalidStatusTransitionException> { execution.startDiagnosis(t2) }
        assertEquals("Cannot start diagnosis when the execution is IN_DIAGNOSIS", error.message)
    }

    @Test
    fun `startRepair only from IN_DIAGNOSIS`() {
        assertThrows<InvalidStatusTransitionException> { Fixtures.queued().startRepair(t1) }
    }

    @Test
    fun `finish only from IN_EXECUTION`() {
        val execution = Fixtures.queued().apply { startDiagnosis(t1) }

        assertThrows<InvalidStatusTransitionException> { execution.finish(t2) }
        assertEquals(ExecutionStatus.IN_DIAGNOSIS, execution.status)
    }

    @Test
    fun `finished execution accepts no further action`() {
        val execution = Fixtures.queued().apply { startDiagnosis(t1); startRepair(t2); finish(t3) }

        assertThrows<InvalidStatusTransitionException> { execution.startDiagnosis(t3) }
        assertThrows<InvalidStatusTransitionException> { execution.startRepair(t3) }
        assertThrows<InvalidStatusTransitionException> { execution.finish(t3) }
    }
}
