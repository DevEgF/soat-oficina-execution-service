package com.soat.tech.challenge.oficina.execution.infrastructure.observability

/**
 * Distributed tracing hooks used while a message is processed. Keeps the New Relic API out of the
 * rest of the code and lets tests substitute it.
 */
interface MessageTracing {
    /** Links the current transaction to the trace carried by the message headers (W3C and New Relic). */
    fun acceptTraceHeaders(headers: Map<String, String>)

    fun nameTransaction(category: String, name: String)

    fun addAttributes(attributes: Map<String, String>)
}
