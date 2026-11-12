package com.soat.tech.challenge.oficina.execution.application

import com.soat.tech.challenge.oficina.execution.domain.exception.NotFoundException
import com.soat.tech.challenge.oficina.execution.domain.model.Execution
import com.soat.tech.challenge.oficina.execution.domain.model.ExecutionStatus
import com.soat.tech.challenge.oficina.execution.domain.model.ItemLine
import com.soat.tech.challenge.oficina.execution.domain.model.VehicleInfo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock

data class QueueExecutionCommand(
    val workOrderId: String,
    val trackingCode: String,
    val customerName: String,
    val vehicle: VehicleInfo,
    val services: List<ItemLine>,
    val parts: List<ItemLine>,
    val correlationId: String?,
)

/**
 * Use cases of the execution. MongoDB runs standalone (no multi-document transaction), so every
 * write is a single-document operation and events are published after the document is stored.
 */
@Service
class ExecutionApplicationService(
    private val repository: ExecutionRepository,
    private val publisher: ExecutionEventPublisher,
    private val clock: Clock,
) {

    /** Returns false when the work order is already queued (a repeated command changes nothing). */
    fun queue(command: QueueExecutionCommand): Boolean {
        val execution = Execution.queue(
            workOrderId = command.workOrderId,
            trackingCode = command.trackingCode,
            customerName = command.customerName,
            vehicle = command.vehicle,
            services = command.services,
            parts = command.parts,
            correlationId = command.correlationId,
            now = clock.instant(),
        )
        val created = repository.insertIfAbsent(execution)
        if (!created) log.info("execution.already-queued workOrderId={}", command.workOrderId)
        return created
    }

    fun list(status: ExecutionStatus?): List<Execution> = repository.findAll(status)

    fun get(workOrderId: String): Execution = find(workOrderId)

    fun startDiagnosis(workOrderId: String): Execution {
        val execution = find(workOrderId)
        execution.startDiagnosis(clock.instant())
        return repository.save(execution).also(publisher::publishStatusChanged)
    }

    fun startRepair(workOrderId: String): Execution {
        val execution = find(workOrderId)
        execution.startRepair(clock.instant())
        return repository.save(execution).also(publisher::publishStatusChanged)
    }

    fun finish(workOrderId: String): Execution {
        val execution = find(workOrderId)
        execution.finish(clock.instant())
        return repository.save(execution).also(publisher::publishFinished)
    }

    private fun find(workOrderId: String): Execution =
        repository.findByWorkOrderId(workOrderId)
            ?: throw NotFoundException("Execution not found for work order $workOrderId")

    private companion object {
        val log = LoggerFactory.getLogger(ExecutionApplicationService::class.java)
    }
}
