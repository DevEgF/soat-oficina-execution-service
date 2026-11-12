package com.soat.tech.challenge.oficina.execution.presentation

import com.soat.tech.challenge.oficina.execution.application.ExecutionApplicationService
import com.soat.tech.challenge.oficina.execution.domain.model.Execution
import com.soat.tech.challenge.oficina.execution.domain.model.ExecutionStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class VehicleResponse(val plate: String, val brand: String, val model: String, val year: Int)

data class ItemResponse(val code: String, val quantity: Int)

data class ExecutionEventResponse(val type: String, val status: ExecutionStatus, val occurredAt: Instant)

data class ExecutionResponse(
    val workOrderId: String,
    val trackingCode: String,
    val status: ExecutionStatus,
    val customerName: String,
    val vehicle: VehicleResponse,
    val services: List<ItemResponse>,
    val parts: List<ItemResponse>,
    val queuedAt: Instant,
    val diagnosisStartedAt: Instant?,
    val repairStartedAt: Instant?,
    val finishedAt: Instant?,
    val events: List<ExecutionEventResponse>,
)

fun Execution.toResponse() = ExecutionResponse(
    workOrderId = workOrderId,
    trackingCode = trackingCode,
    status = status,
    customerName = customerName,
    vehicle = VehicleResponse(vehicle.plate, vehicle.brand, vehicle.model, vehicle.year),
    services = services.map { ItemResponse(it.code, it.quantity) },
    parts = parts.map { ItemResponse(it.code, it.quantity) },
    queuedAt = queuedAt,
    diagnosisStartedAt = diagnosisStartedAt,
    repairStartedAt = repairStartedAt,
    finishedAt = finishedAt,
    events = events.map { ExecutionEventResponse(it.type, it.status, it.occurredAt) },
)

@RestController
@RequestMapping("/api/executions")
@Tag(name = "Executions", description = "Execution queue, diagnosis, repair and finish")
class ExecutionController(private val service: ExecutionApplicationService) {

    @GetMapping
    @Operation(summary = "Lists executions in queue order (oldest first), optionally by status")
    fun list(@RequestParam(required = false) status: ExecutionStatus?): List<ExecutionResponse> =
        service.list(status).map { it.toResponse() }

    @GetMapping("/{workOrderId}")
    @Operation(summary = "Gets the execution of a work order")
    fun get(@PathVariable workOrderId: String): ExecutionResponse = service.get(workOrderId).toResponse()

    @PostMapping("/{workOrderId}/diagnosis/start")
    @Operation(summary = "QUEUED -> IN_DIAGNOSIS; publishes ExecutionStatusChanged")
    fun startDiagnosis(@PathVariable workOrderId: String): ExecutionResponse =
        service.startDiagnosis(workOrderId).toResponse()

    @PostMapping("/{workOrderId}/repair/start")
    @Operation(summary = "IN_DIAGNOSIS -> IN_EXECUTION; publishes ExecutionStatusChanged")
    fun startRepair(@PathVariable workOrderId: String): ExecutionResponse =
        service.startRepair(workOrderId).toResponse()

    @PostMapping("/{workOrderId}/finish")
    @Operation(summary = "IN_EXECUTION -> FINISHED; publishes ExecutionFinished")
    fun finish(@PathVariable workOrderId: String): ExecutionResponse =
        service.finish(workOrderId).toResponse()
}
