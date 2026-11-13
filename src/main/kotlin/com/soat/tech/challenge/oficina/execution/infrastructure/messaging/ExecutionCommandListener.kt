package com.soat.tech.challenge.oficina.execution.infrastructure.messaging

import com.newrelic.api.agent.Trace
import com.soat.tech.challenge.oficina.execution.infrastructure.observability.MessageTracing
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
 *
 * Each message runs in its own New Relic transaction, linked to the trace carried by the AMQP
 * headers, so whatever is published while handling it continues the same distributed trace.
 */
@Component
class ExecutionCommandListener(
    private val codec: EnvelopeCodec,
    private val handler: ExecutionCommandHandler,
    private val tracing: MessageTracing,
) {

    @Trace(dispatcher = true)
    @RabbitListener(queues = [Topology.EXECUTION_COMMANDS])
    fun onMessage(message: Message) {
        tracing.acceptTraceHeaders(traceHeaders(message))
        val queue = message.messageProperties.consumerQueue ?: Topology.EXECUTION_COMMANDS
        val envelope = try {
            codec.decode(message.body)
        } catch (e: InvalidMessageException) {
            tracing.nameTransaction(TRANSACTION_CATEGORY, "$queue/invalid")
            log.warn("message.rejected reason={} messageId={}", e.message, message.messageProperties.messageId)
            throw AmqpRejectAndDontRequeueException(e.message ?: "invalid message", e)
        }
        tracing.nameTransaction(TRANSACTION_CATEGORY, "$queue/${envelope.type}")
        tracing.addAttributes(
            buildMap {
                put("sagaId", envelope.sagaId)
                put("messageType", envelope.type)
                put("messageId", envelope.messageId)
                envelope.correlationId?.let { put("correlationId", it) }
            },
        )
        envelope.correlationId?.let { MDC.put(CORRELATION_MDC_KEY, it) }
        MDC.put("sagaId", envelope.sagaId)
        MDC.put("messageType", envelope.type)
        MDC.put("messageId", envelope.messageId)
        try {
            handler.handle(envelope)
        } catch (e: InvalidMessageException) {
            log.warn("message.rejected reason={} messageId={}", e.message, envelope.messageId)
            throw AmqpRejectAndDontRequeueException(e.message ?: "invalid message", e)
        } finally {
            MDC.remove(CORRELATION_MDC_KEY)
            MDC.remove("sagaId")
            MDC.remove("messageType")
            MDC.remove("messageId")
        }
    }

    private fun traceHeaders(message: Message): Map<String, String> =
        message.messageProperties.headers
            .filterKeys { it in TRACE_HEADERS }
            .mapNotNull { (name, value) -> value?.toString()?.let { name to it } }
            .toMap()

    private companion object {
        /** Same key the CorrelationIdFilter uses for HTTP requests. */
        const val CORRELATION_MDC_KEY = "requestId"
        const val TRANSACTION_CATEGORY = "Message"
        val TRACE_HEADERS = setOf("traceparent", "tracestate", "newrelic")
        val log = LoggerFactory.getLogger(ExecutionCommandListener::class.java)
    }
}
