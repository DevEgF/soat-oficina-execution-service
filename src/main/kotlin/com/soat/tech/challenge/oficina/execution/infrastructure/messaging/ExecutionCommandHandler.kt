package com.soat.tech.challenge.oficina.execution.infrastructure.messaging

import com.soat.tech.challenge.oficina.execution.application.ExecutionApplicationService
import com.soat.tech.challenge.oficina.execution.application.ProcessedMessageStore
import com.soat.tech.challenge.oficina.execution.application.QueueExecutionCommand
import com.soat.tech.challenge.oficina.execution.domain.model.ItemLine
import com.soat.tech.challenge.oficina.execution.domain.model.VehicleInfo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Dispatches a decoded envelope by `type`. Idempotency: the processed messageId is recorded after
 * the effect; the effect itself is idempotent (unique workOrderId), so a crash between the two
 * only causes a harmless repeat. No multi-document transaction is needed.
 */
@Component
class ExecutionCommandHandler(
    private val service: ExecutionApplicationService,
    private val processed: ProcessedMessageStore,
    private val codec: EnvelopeCodec,
) {

    fun handle(envelope: MessageEnvelope) {
        val type = MessageType.fromWireName(envelope.type)
            ?: throw InvalidMessageException("Unknown message type ${envelope.type}")
        log.info("message.consumed type={} sagaId={} messageId={}", envelope.type, envelope.sagaId, envelope.messageId)

        if (processed.isProcessed(envelope.messageId)) {
            log.info("message.duplicate type={} sagaId={} messageId={}", envelope.type, envelope.sagaId, envelope.messageId)
            return
        }
        when (type) {
            MessageType.StartExecution -> startExecution(envelope)
            else -> {
                // Contract types this service does not handle (commands of other services, day-13 messages).
                log.warn("message.unhandled type={} sagaId={} messageId={}", envelope.type, envelope.sagaId, envelope.messageId)
                return
            }
        }
        processed.markProcessed(envelope.messageId, envelope.type)
    }

    private fun startExecution(envelope: MessageEnvelope) {
        val payload = codec.payload(envelope, StartExecutionPayload::class.java)
        service.queue(
            QueueExecutionCommand(
                workOrderId = payload.workOrderId,
                trackingCode = payload.trackingCode,
                customerName = payload.customerName,
                vehicle = VehicleInfo(payload.vehicle.plate, payload.vehicle.brand, payload.vehicle.model, payload.vehicle.year),
                services = payload.services.map { ItemLine(it.code, it.quantity) },
                parts = payload.parts.map { ItemLine(it.code, it.quantity) },
                correlationId = envelope.correlationId,
            ),
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(ExecutionCommandHandler::class.java)
    }
}
