package com.soat.tech.challenge.oficina.execution.infrastructure.messaging

import com.soat.tech.challenge.oficina.execution.application.ExecutionEventPublisher
import com.soat.tech.challenge.oficina.execution.domain.model.Execution
import com.soat.tech.challenge.oficina.execution.domain.model.ExecutionStatus
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.core.MessagePropertiesBuilder
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/** Publishes execution events to the `oficina` exchange, reusing the saga correlation id. */
@Component
class RabbitExecutionEventPublisher(
    private val rabbit: RabbitTemplate,
    private val codec: EnvelopeCodec,
    private val clock: Clock,
) : ExecutionEventPublisher {

    override fun publishStatusChanged(execution: Execution) {
        require(execution.status == ExecutionStatus.IN_DIAGNOSIS || execution.status == ExecutionStatus.IN_EXECUTION) {
            "ExecutionStatusChanged only carries IN_DIAGNOSIS or IN_EXECUTION"
        }
        publish(
            MessageType.ExecutionStatusChanged,
            execution,
            ExecutionStatusChangedPayload(execution.workOrderId, execution.status.name),
        )
    }

    override fun publishFinished(execution: Execution) {
        val finishedAt = requireNotNull(execution.finishedAt) { "Execution is not finished" }
        publish(
            MessageType.ExecutionFinished,
            execution,
            ExecutionFinishedPayload(execution.workOrderId, finishedAt.toString()),
        )
    }

    private fun publish(type: MessageType, execution: Execution, payload: Any) {
        val messageId = UUID.randomUUID().toString()
        val correlationId = execution.correlationId ?: messageId
        val envelope = MessageEnvelope(
            messageId = messageId,
            sagaId = execution.workOrderId,
            type = type.name,
            occurredAt = clock.instant().toString(),
            correlationId = correlationId,
            payload = codec.toTree(payload),
        )
        val properties = MessagePropertiesBuilder.newInstance()
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .setContentEncoding("UTF-8")
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .setMessageId(messageId)
            .setType(type.name)
            .setHeader(CORRELATION_HEADER, correlationId)
            .build()
        rabbit.send(Topology.EXCHANGE, type.routingKey, Message(codec.encode(envelope), properties))
        log.info("message.published type={} sagaId={} messageId={}", type.name, execution.workOrderId, messageId)
    }

    companion object {
        const val CORRELATION_HEADER = "x-correlation-id"
        private val log = LoggerFactory.getLogger(RabbitExecutionEventPublisher::class.java)
    }
}
