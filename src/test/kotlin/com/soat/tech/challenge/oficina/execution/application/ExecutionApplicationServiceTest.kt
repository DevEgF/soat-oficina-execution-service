package com.soat.tech.challenge.oficina.execution.application

import com.soat.tech.challenge.oficina.execution.domain.exception.InvalidStatusTransitionException
import com.soat.tech.challenge.oficina.execution.domain.exception.NotFoundException
import com.soat.tech.challenge.oficina.execution.domain.model.ExecutionStatus
import com.soat.tech.challenge.oficina.execution.domain.model.ItemLine
import com.soat.tech.challenge.oficina.execution.domain.model.VehicleInfo
import com.soat.tech.challenge.oficina.execution.support.Fixtures
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.ZoneOffset

class ExecutionApplicationServiceTest {

    private val repository = mockk<ExecutionRepository>()
    private val publisher = mockk<ExecutionEventPublisher>(relaxed = true)
    private val clock = Clock.fixed(Fixtures.NOW.plusSeconds(60), ZoneOffset.UTC)
    private val service = ExecutionApplicationService(repository, publisher, clock)

    private val command = QueueExecutionCommand(
        workOrderId = "wo-1",
        trackingCode = "TRK-1",
        customerName = "Maria",
        vehicle = VehicleInfo("ABC1D23", "Fiat", "Argo", 2022),
        services = listOf(ItemLine("TROCA_OLEO", 1)),
        parts = emptyList(),
        correlationId = "corr-1",
    )

    @Test
    fun `queue inserts a QUEUED execution with the correlation id`() {
        every { repository.insertIfAbsent(any()) } answers {
            val saved = firstArg<com.soat.tech.challenge.oficina.execution.domain.model.Execution>()
            assertEquals(ExecutionStatus.QUEUED, saved.status)
            assertEquals("corr-1", saved.correlationId)
            assertEquals(clock.instant(), saved.queuedAt)
            true
        }

        assertTrue(service.queue(command))
    }

    @Test
    fun `queue reports false when the work order is already queued`() {
        every { repository.insertIfAbsent(any()) } returns false

        assertFalse(service.queue(command))
    }

    @Test
    fun `startDiagnosis saves then publishes status changed`() {
        val execution = Fixtures.queued()
        every { repository.findByWorkOrderId("wo-1") } returns execution
        every { repository.save(execution) } returns execution

        val result = service.startDiagnosis("wo-1")

        assertEquals(ExecutionStatus.IN_DIAGNOSIS, result.status)
        verify(exactly = 1) { publisher.publishStatusChanged(execution) }
    }

    @Test
    fun `startRepair and finish publish the matching events`() {
        val execution = Fixtures.queued().apply { startDiagnosis(Fixtures.NOW) }
        every { repository.findByWorkOrderId("wo-1") } returns execution
        every { repository.save(execution) } returns execution

        service.startRepair("wo-1")
        service.finish("wo-1")

        assertEquals(ExecutionStatus.FINISHED, execution.status)
        verify(exactly = 1) { publisher.publishStatusChanged(execution) }
        verify(exactly = 1) { publisher.publishFinished(execution) }
    }

    @Test
    fun `fail saves then publishes the failure`() {
        val execution = Fixtures.queued()
        every { repository.findByWorkOrderId("wo-1") } returns execution
        every { repository.save(execution) } returns execution

        val result = service.fail("wo-1", "no parts")

        assertEquals(ExecutionStatus.FAILED, result.status)
        assertEquals("no parts", result.failureReason)
        verify(exactly = 1) { publisher.publishFailed(execution) }
    }

    @Test
    fun `fail from a final status neither saves nor publishes`() {
        every { repository.findByWorkOrderId("wo-1") } returns Fixtures.queued().apply { fail("x", Fixtures.NOW) }

        assertThrows<InvalidStatusTransitionException> { service.fail("wo-1", "again") }

        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { publisher.publishFailed(any()) }
    }

    @Test
    fun `invalid transition neither saves nor publishes`() {
        every { repository.findByWorkOrderId("wo-1") } returns Fixtures.queued()

        assertThrows<InvalidStatusTransitionException> { service.finish("wo-1") }

        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { publisher.publishFinished(any()) }
    }

    @Test
    fun `unknown work order is not found`() {
        every { repository.findByWorkOrderId("x") } returns null

        assertThrows<NotFoundException> { service.get("x") }
        assertThrows<NotFoundException> { service.startDiagnosis("x") }
        assertThrows<NotFoundException> { service.fail("x", "reason") }
    }

    @Test
    fun `list delegates the status filter`() {
        every { repository.findAll(ExecutionStatus.QUEUED) } returns listOf(Fixtures.queued())

        assertEquals(1, service.list(ExecutionStatus.QUEUED).size)
    }
}
