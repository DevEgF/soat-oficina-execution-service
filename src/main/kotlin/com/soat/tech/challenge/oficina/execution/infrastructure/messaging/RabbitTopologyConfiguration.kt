package com.soat.tech.challenge.oficina.execution.infrastructure.messaging

import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.core.TopicExchange
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Declared at startup by RabbitAdmin (idempotent). The execution service consumes
 * `execution.commands` and publishes to `os.events`, so it declares both, with their DLQs.
 * Queue arguments must match the other services exactly or the broker answers PRECONDITION_FAILED.
 */
@Configuration
class RabbitTopologyConfiguration {

    @Bean
    fun oficinaTopology(): Declarables {
        val exchange = TopicExchange(Topology.EXCHANGE, true, false)
        val deadLetterExchange = DirectExchange(Topology.DEAD_LETTER_EXCHANGE, true, false)

        val commands = queueWithDeadLetter(Topology.EXECUTION_COMMANDS)
        val events = queueWithDeadLetter(Topology.OS_EVENTS)
        val commandsDlq = deadLetterQueue(Topology.EXECUTION_COMMANDS)
        val eventsDlq = deadLetterQueue(Topology.OS_EVENTS)

        return Declarables(
            exchange,
            deadLetterExchange,
            commands,
            events,
            commandsDlq,
            eventsDlq,
            BindingBuilder.bind(commands).to(exchange).with(Topology.EXECUTION_COMMANDS_BINDING),
            BindingBuilder.bind(events).to(exchange).with(Topology.OS_EVENTS_BINDING),
            BindingBuilder.bind(commandsDlq).to(deadLetterExchange).with(commandsDlq.name),
            BindingBuilder.bind(eventsDlq).to(deadLetterExchange).with(eventsDlq.name),
        )
    }

    private fun queueWithDeadLetter(name: String): Queue =
        QueueBuilder.durable(name)
            .withArgument("x-dead-letter-exchange", Topology.DEAD_LETTER_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", Topology.deadLetterQueue(name))
            .build()

    private fun deadLetterQueue(queue: String): Queue =
        QueueBuilder.durable(Topology.deadLetterQueue(queue)).build()
}
