package com.soat.tech.challenge.oficina.execution.domain.model

import com.soat.tech.challenge.oficina.execution.domain.exception.InvalidStatusTransitionException
import java.time.Instant

data class VehicleInfo(val plate: String, val brand: String, val model: String, val year: Int)

data class ItemLine(val code: String, val quantity: Int)

/** One entry per status change, kept inside the execution document. */
data class ExecutionEvent(val type: String, val status: ExecutionStatus, val occurredAt: Instant)

/**
 * Execution of one work order. The state machine is explicit:
 * QUEUED -> IN_DIAGNOSIS -> IN_EXECUTION -> FINISHED, and any non-final status -> FAILED.
 * Each action validates the origin status.
 */
class Execution(
    val workOrderId: String,
    val trackingCode: String,
    val customerName: String,
    val vehicle: VehicleInfo,
    val services: List<ItemLine>,
    val parts: List<ItemLine>,
    /** Correlation id of the saga, reused in every event published for this execution. */
    val correlationId: String?,
    status: ExecutionStatus,
    val queuedAt: Instant,
    diagnosisStartedAt: Instant? = null,
    repairStartedAt: Instant? = null,
    finishedAt: Instant? = null,
    failedAt: Instant? = null,
    failureReason: String? = null,
    events: List<ExecutionEvent> = emptyList(),
    /** Optimistic-lock version of the stored document (null until first saved). */
    val version: Long? = null,
) {
    var status: ExecutionStatus = status
        private set
    var diagnosisStartedAt: Instant? = diagnosisStartedAt
        private set
    var repairStartedAt: Instant? = repairStartedAt
        private set
    var finishedAt: Instant? = finishedAt
        private set

    var failedAt: Instant? = failedAt
        private set
    var failureReason: String? = failureReason
        private set

    private val eventLog: MutableList<ExecutionEvent> = events.toMutableList()
    val events: List<ExecutionEvent> get() = eventLog

    fun startDiagnosis(now: Instant) {
        requireStatus(ExecutionStatus.QUEUED, "start diagnosis")
        status = ExecutionStatus.IN_DIAGNOSIS
        diagnosisStartedAt = now
        record("DiagnosisStarted", now)
    }

    fun startRepair(now: Instant) {
        requireStatus(ExecutionStatus.IN_DIAGNOSIS, "start repair")
        status = ExecutionStatus.IN_EXECUTION
        repairStartedAt = now
        record("RepairStarted", now)
    }

    fun finish(now: Instant) {
        requireStatus(ExecutionStatus.IN_EXECUTION, "finish")
        status = ExecutionStatus.FINISHED
        finishedAt = now
        record("ExecutionFinished", now)
    }

    fun fail(reason: String, now: Instant) {
        if (status !in FAILABLE) throw InvalidStatusTransitionException(status.name, "fail")
        status = ExecutionStatus.FAILED
        failedAt = now
        failureReason = reason
        record("ExecutionFailed", now)
    }

    private fun requireStatus(expected: ExecutionStatus, action: String) {
        if (status != expected) throw InvalidStatusTransitionException(status.name, action)
    }

    private fun record(type: String, now: Instant) {
        eventLog += ExecutionEvent(type, status, now)
    }

    companion object {
        private val FAILABLE = setOf(ExecutionStatus.QUEUED, ExecutionStatus.IN_DIAGNOSIS, ExecutionStatus.IN_EXECUTION)

        fun queue(
            workOrderId: String,
            trackingCode: String,
            customerName: String,
            vehicle: VehicleInfo,
            services: List<ItemLine>,
            parts: List<ItemLine>,
            correlationId: String?,
            now: Instant,
        ): Execution = Execution(
            workOrderId = workOrderId,
            trackingCode = trackingCode,
            customerName = customerName,
            vehicle = vehicle,
            services = services,
            parts = parts,
            correlationId = correlationId,
            status = ExecutionStatus.QUEUED,
            queuedAt = now,
            events = listOf(ExecutionEvent("ExecutionQueued", ExecutionStatus.QUEUED, now)),
        )
    }
}
