package com.soat.tech.challenge.oficina.execution.infrastructure.messaging

import com.fasterxml.jackson.databind.JsonNode

/** Names of the messaging contract shared by the three services (see the platform spec). */
object Topology {
    const val EXCHANGE = "oficina"
    const val DEAD_LETTER_EXCHANGE = "oficina.dlx"
    const val EXECUTION_COMMANDS = "execution.commands"
    const val OS_EVENTS = "os.events"
    const val EXECUTION_COMMANDS_BINDING = "command.execution.#"
    const val OS_EVENTS_BINDING = "event.#"

    fun deadLetterQueue(queue: String) = "$queue.dlq"
}

/** Every `type` in the contract, with its routing key. */
enum class MessageType(val routingKey: String) {
    GenerateQuote("command.billing.generate-quote"),
    RequestPayment("command.billing.request-payment"),
    CancelQuote("command.billing.cancel-quote"),
    RefundPayment("command.billing.refund-payment"),
    StartExecution("command.execution.start-execution"),
    QuoteGenerated("event.billing.quote-generated"),
    QuoteGenerationFailed("event.billing.quote-generation-failed"),
    QuoteApproved("event.billing.quote-approved"),
    QuoteRejected("event.billing.quote-rejected"),
    QuoteCancelled("event.billing.quote-cancelled"),
    PaymentApproved("event.billing.payment-approved"),
    PaymentRejected("event.billing.payment-rejected"),
    PaymentRefunded("event.billing.payment-refunded"),
    ExecutionStatusChanged("event.execution.status-changed"),
    ExecutionFinished("event.execution.finished"),
    ExecutionFailed("event.execution.failed");

    companion object {
        fun fromWireName(name: String): MessageType? = entries.firstOrNull { it.name == name }
    }
}

/** JSON body of every message. `occurredAt` is an ISO-8601 UTC instant. */
data class MessageEnvelope(
    val messageId: String,
    val sagaId: String,
    val type: String,
    val occurredAt: String,
    val correlationId: String?,
    val payload: JsonNode,
)

data class ItemPayload(val code: String, val quantity: Int)

data class VehiclePayload(val plate: String, val brand: String, val model: String, val year: Int)

data class StartExecutionPayload(
    val workOrderId: String,
    val trackingCode: String,
    val customerName: String,
    val vehicle: VehiclePayload,
    val services: List<ItemPayload> = emptyList(),
    val parts: List<ItemPayload> = emptyList(),
)

data class ExecutionStatusChangedPayload(val workOrderId: String, val status: String)

data class ExecutionFinishedPayload(val workOrderId: String, val finishedAt: String)

/** The message cannot be understood (bad JSON, missing fields, unknown type): goes straight to the DLQ. */
class InvalidMessageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
