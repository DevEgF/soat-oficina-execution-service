package com.soat.tech.challenge.oficina.execution.infrastructure.observability

import com.newrelic.api.agent.ConcurrentHashMapHeaders
import com.newrelic.api.agent.HeaderType
import com.newrelic.api.agent.NewRelic
import com.newrelic.api.agent.TransportType
import org.springframework.stereotype.Component

/** New Relic implementation. Without the Java agent attached the API calls do nothing. */
@Component
class NewRelicTracing : MessageTracing {

    override fun acceptTraceHeaders(headers: Map<String, String>) {
        if (headers.isEmpty()) return
        val inbound = ConcurrentHashMapHeaders.build(HeaderType.MESSAGE)
        headers.forEach { (name, value) -> inbound.setHeader(name, value) }
        NewRelic.getAgent().transaction.acceptDistributedTraceHeaders(TransportType.AMQP, inbound)
    }

    override fun nameTransaction(category: String, name: String) {
        NewRelic.setTransactionName(category, name)
    }

    override fun addAttributes(attributes: Map<String, String>) {
        attributes.forEach { (key, value) -> NewRelic.addCustomParameter(key, value) }
    }
}
