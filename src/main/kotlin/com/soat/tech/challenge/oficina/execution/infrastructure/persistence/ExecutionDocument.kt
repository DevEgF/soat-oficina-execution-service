package com.soat.tech.challenge.oficina.execution.infrastructure.persistence

import com.soat.tech.challenge.oficina.execution.domain.model.Execution
import com.soat.tech.challenge.oficina.execution.domain.model.ExecutionEvent
import com.soat.tech.challenge.oficina.execution.domain.model.ExecutionStatus
import com.soat.tech.challenge.oficina.execution.domain.model.ItemLine
import com.soat.tech.challenge.oficina.execution.domain.model.VehicleInfo
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

const val EXECUTIONS_COLLECTION = "execucoes"
const val PROCESSED_MESSAGES_COLLECTION = "processed_messages"

data class VehicleDocument(val plate: String = "", val brand: String = "", val model: String = "", val year: Int = 0)

data class ItemDocument(val code: String = "", val quantity: Int = 0)

data class EventDocument(
    val type: String = "",
    val status: ExecutionStatus = ExecutionStatus.QUEUED,
    val occurredAt: Instant = Instant.EPOCH,
)

/** One document per work order: _id is the work order id and workOrderId also has a unique index. */
@Document(EXECUTIONS_COLLECTION)
data class ExecutionDocument(
    @Id val id: String = "",
    @Version val version: Long? = null,
    val workOrderId: String = "",
    val trackingCode: String = "",
    val customerName: String = "",
    val vehicle: VehicleDocument = VehicleDocument(),
    val services: List<ItemDocument> = emptyList(),
    val parts: List<ItemDocument> = emptyList(),
    val status: ExecutionStatus = ExecutionStatus.QUEUED,
    val correlationId: String? = null,
    val queuedAt: Instant = Instant.EPOCH,
    val diagnosisStartedAt: Instant? = null,
    val repairStartedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val failedAt: Instant? = null,
    val failureReason: String? = null,
    val events: List<EventDocument> = emptyList(),
)

/** `_id` is the messageId of the consumed message. */
@Document(PROCESSED_MESSAGES_COLLECTION)
data class ProcessedMessageDocument(
    @Id val id: String = "",
    val type: String = "",
    val processedAt: Instant = Instant.EPOCH,
)

fun Execution.toDocument() = ExecutionDocument(
    id = workOrderId,
    version = version,
    workOrderId = workOrderId,
    trackingCode = trackingCode,
    customerName = customerName,
    vehicle = VehicleDocument(vehicle.plate, vehicle.brand, vehicle.model, vehicle.year),
    services = services.map { ItemDocument(it.code, it.quantity) },
    parts = parts.map { ItemDocument(it.code, it.quantity) },
    status = status,
    correlationId = correlationId,
    queuedAt = queuedAt,
    diagnosisStartedAt = diagnosisStartedAt,
    repairStartedAt = repairStartedAt,
    finishedAt = finishedAt,
    failedAt = failedAt,
    failureReason = failureReason,
    events = events.map { EventDocument(it.type, it.status, it.occurredAt) },
)

fun ExecutionDocument.toDomain() = Execution(
    workOrderId = workOrderId,
    trackingCode = trackingCode,
    customerName = customerName,
    vehicle = VehicleInfo(vehicle.plate, vehicle.brand, vehicle.model, vehicle.year),
    services = services.map { ItemLine(it.code, it.quantity) },
    parts = parts.map { ItemLine(it.code, it.quantity) },
    correlationId = correlationId,
    status = status,
    queuedAt = queuedAt,
    diagnosisStartedAt = diagnosisStartedAt,
    repairStartedAt = repairStartedAt,
    finishedAt = finishedAt,
    failedAt = failedAt,
    failureReason = failureReason,
    events = events.map { ExecutionEvent(it.type, it.status, it.occurredAt) },
    version = version,
)
