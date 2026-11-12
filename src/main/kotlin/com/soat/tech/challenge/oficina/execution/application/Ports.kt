package com.soat.tech.challenge.oficina.execution.application

import com.soat.tech.challenge.oficina.execution.domain.model.Execution
import com.soat.tech.challenge.oficina.execution.domain.model.ExecutionStatus

interface ExecutionRepository {
    fun findByWorkOrderId(workOrderId: String): Execution?

    /** Oldest first (queue order). */
    fun findAll(status: ExecutionStatus?): List<Execution>

    /** Inserts a new execution; returns false when one already exists for the work order. */
    fun insertIfAbsent(execution: Execution): Boolean

    /** Updates with optimistic locking; a concurrent change raises OptimisticLockingFailureException. */
    fun save(execution: Execution): Execution
}

interface ProcessedMessageStore {
    fun isProcessed(messageId: String): Boolean
    fun markProcessed(messageId: String, type: String)
}

interface ExecutionEventPublisher {
    fun publishStatusChanged(execution: Execution)
    fun publishFinished(execution: Execution)
}
