package com.soat.tech.challenge.oficina.execution.infrastructure.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.Exchange
import org.springframework.amqp.core.Queue

class RabbitTopologyConfigurationTest {

    private val declarables = RabbitTopologyConfiguration().oficinaTopology()
    private val queues = declarables.getDeclarablesByType(Queue::class.java).associateBy { it.name }
    private val exchanges = declarables.getDeclarablesByType(Exchange::class.java).associateBy { it.name }
    private val bindings = declarables.getDeclarablesByType(Binding::class.java)

    @Test
    fun `declares the two durable exchanges`() {
        assertEquals("topic", exchanges.getValue("oficina").type)
        assertEquals("direct", exchanges.getValue("oficina.dlx").type)
        assertTrue(exchanges.values.all { it.isDurable && !it.isAutoDelete })
    }

    @Test
    fun `work queues carry the dead letter arguments and no queue type or ttl`() {
        listOf("execution.commands", "os.events").forEach { name ->
            val queue = queues.getValue(name)
            assertTrue(queue.isDurable)
            assertFalse(queue.isExclusive)
            assertFalse(queue.isAutoDelete)
            assertEquals(
                mapOf("x-dead-letter-exchange" to "oficina.dlx", "x-dead-letter-routing-key" to "$name.dlq"),
                queue.arguments,
            )
        }
    }

    @Test
    fun `dead letter queues are durable without arguments`() {
        listOf("execution.commands.dlq", "os.events.dlq").forEach { name ->
            assertTrue(queues.getValue(name).isDurable)
            assertTrue(queues.getValue(name).arguments.isEmpty())
        }
    }

    @Test
    fun `bindings follow the spec`() {
        val actual = bindings.map { Triple(it.exchange, it.destination, it.routingKey) }.toSet()
        assertEquals(
            setOf(
                Triple("oficina", "execution.commands", "command.execution.#"),
                Triple("oficina", "os.events", "event.#"),
                Triple("oficina.dlx", "execution.commands.dlq", "execution.commands.dlq"),
                Triple("oficina.dlx", "os.events.dlq", "os.events.dlq"),
            ),
            actual,
        )
    }
}
