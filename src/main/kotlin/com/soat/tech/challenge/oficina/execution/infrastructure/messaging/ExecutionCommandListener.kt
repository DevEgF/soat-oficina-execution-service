package com.soat.tech.challenge.oficina.execution.infrastructure.messaging

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.amqp.AmqpRejectAndDontRequeueException
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

/**
 * Consumer of `execution.commands`. Receives raw bytes and decodes the envelope explicitly.
 * Messages that cannot be understood are rejected without requeue (straight to the DLQ, no retry);
 * any other failure propagates to the listener retry (3 attempts) and then to the DLQ.
 */
@Component
class ExecutionCommandListener(
    private val codec: EnvelopeCodec,
    private val handler: ExecutionCommandHandler,
) {

    @RabbitListener(queues = [Topology.EXECUTION_COMMANDS])
    fun onMessage(message: Message) {
        val envelope = try {
            codec.decode(message.body)
        } catch (e: InvalidMessageException) {
            log.warn("message.rejected reason={} messageId={}", e.message, message.messageProperties.messageId)
            throw AmqpRejectAndDontRequeueException(e.message ?: "invalid message", e)
        }
        envelope.correlationId?.let { MDC.put(CORRELATION_MDC_KEY, it) }
        try {
            handler.handle(envelope)
        } catch (e: InvalidMessageException) {
            log.warn("message.rejected reason={} messageId={}", e.message, envelope.messageId)
            throw AmqpRejectAndDontRequeueException(e.message ?: "invalid message", e)
        } finally {
            MDC.remove(CORRELATION_MDC_KEY)
        }
    }

    private companion object {
        /** Same key the CorrelationIdFilter uses for HTTP requests. */
        const val CORRELATION_MDC_KEY = "requestId"
        val log = LoggerFactory.getLogger(ExecutionCommandListener::class.java)
    }
}
