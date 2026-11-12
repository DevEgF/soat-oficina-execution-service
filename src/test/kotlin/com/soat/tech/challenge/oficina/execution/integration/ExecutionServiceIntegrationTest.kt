package com.soat.tech.challenge.oficina.execution.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.soat.tech.challenge.oficina.execution.support.TestJwt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.TopicExchange
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.http.MediaType
import java.util.UUID
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Full context against real MongoDB and RabbitMQ containers.
 * Properties are inlined so they win over APP_* variables exported in the developer shell.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = [
        "app.environment=hml",
        "app.jwt.secret=${TestJwt.RAW_SECRET}",
    ],
)
@ActiveProfiles("test")
@Testcontainers
class ExecutionServiceIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var rabbit: RabbitTemplate

    @Autowired
    private lateinit var admin: RabbitAdmin

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    @Test
    fun `health is UP with database and broker in readiness`() {
        val health = get("/actuator/health")
        assertEquals(200, health.statusCode())
        assertEquals("UP", body(health)["status"].asText())

        val readiness = get("/actuator/health/readiness")
        assertEquals(200, readiness.statusCode())
        val components = body(readiness)["components"]
        assertEquals("UP", components["mongo"]["status"].asText())
        assertEquals("UP", components["rabbit"]["status"].asText())
        assertEquals(200, get("/actuator/health/liveness").statusCode())
    }

    @Test
    fun `api info requires a bearer token`() {
        assertEquals(401, get("/api/info").statusCode())
        assertEquals(401, get("/api/info", TestJwt.token(environment = "prod")).statusCode())
    }

    @Test
    fun `api info answers with a valid HS256 token`() {
        val response = get("/api/info", TestJwt.token())

        assertEquals(200, response.statusCode())
        val info = body(response)
        assertEquals("execution-service", info["service"].asText())
        assertEquals("hml", info["environment"].asText())
        assertEquals("0.0.1", info["version"].asText())
        assertNotNull(response.headers().firstValue("X-Correlation-Id").orElse(null))
    }

    @Test
    fun `openapi document is public`() {
        val response = get("/v3/api-docs")

        assertEquals(200, response.statusCode())
        assertTrue(body(response)["paths"].has("/api/info"))
    }

    @Test
    fun `routes outside the public and api prefixes are denied`() {
        assertEquals(401, get("/internal").statusCode())
        assertEquals(403, get("/internal", TestJwt.token()).statusCode())
    }


    @BeforeEach
    fun bindTestQueue() {
        // Plays the OS Service: a queue bound to event.# next to the real os.events queue.
        admin.declareQueue(Queue(TEST_EVENTS, true, false, false))
        admin.declareBinding(BindingBuilder.bind(Queue(TEST_EVENTS)).to(TopicExchange("oficina")).with("event.#"))
        admin.purgeQueue(TEST_EVENTS, false)
        admin.purgeQueue("execution.commands.dlq", false)
    }

    @Test
    fun `topology is declared with dead letter queues and indexes exist`() {
        val commands = admin.getQueueProperties("execution.commands")
        assertNotNull(commands)
        assertNotNull(admin.getQueueProperties("os.events"))
        assertNotNull(admin.getQueueProperties("execution.commands.dlq"))
        assertNotNull(admin.getQueueProperties("os.events.dlq"))

        val indexes = mongoTemplate.indexOps("execucoes").indexInfo.associateBy { it.name }
        assertTrue(indexes.getValue("ux_workOrderId").isUnique)
    }

    @Test
    fun `saga happy path from StartExecution to ExecutionFinished`() {
        val workOrderId = UUID.randomUUID().toString()
        val correlationId = "corr-$workOrderId".take(40)
        publishStartExecution(workOrderId, correlationId = correlationId)

        val queued = awaitExecution(workOrderId)
        assertEquals("QUEUED", queued["status"].asText())
        assertEquals("Maria Silva", queued["customerName"].asText())
        assertEquals("ABC1D23", queued["vehicle"]["plate"].asText())
        assertEquals("FILTRO_OLEO", queued["parts"][0]["code"].asText())
        assertEquals("ExecutionQueued", queued["events"][0]["type"].asText())
        assertTrue(listExecutions("QUEUED").any { it["workOrderId"].asText() == workOrderId })
        assertFalse(listExecutions("FINISHED").any { it["workOrderId"].asText() == workOrderId })

        val diagnosis = post("/api/executions/$workOrderId/diagnosis/start")
        assertEquals(200, diagnosis.statusCode())
        assertEquals("IN_DIAGNOSIS", body(diagnosis)["status"].asText())
        val repair = post("/api/executions/$workOrderId/repair/start")
        assertEquals("IN_EXECUTION", body(repair)["status"].asText())
        val finish = post("/api/executions/$workOrderId/finish")
        assertEquals(200, finish.statusCode())
        assertEquals("FINISHED", body(finish)["status"].asText())
        assertNotNull(body(finish)["finishedAt"].asText())
        assertEquals(4, body(get("/api/executions/$workOrderId", TestJwt.token()))["events"].size())

        val published = (1..3).map { receiveEvent() }
        assertEquals(listOf("ExecutionStatusChanged", "ExecutionStatusChanged", "ExecutionFinished"), published.map { it["type"].asText() })
        assertEquals(listOf("IN_DIAGNOSIS", "IN_EXECUTION"), published.take(2).map { it["payload"]["status"].asText() })
        assertNotNull(published[2]["payload"]["finishedAt"])
        published.forEach {
            assertEquals(workOrderId, it["sagaId"].asText())
            assertEquals(workOrderId, it["payload"]["workOrderId"].asText())
            assertEquals(correlationId, it["correlationId"].asText())
            assertNotNull(it["messageId"])
        }
        assertNull(rabbit.receive(TEST_EVENTS, 300))
    }

    @Test
    fun `repeated StartExecution does not duplicate the execution`() {
        val workOrderId = UUID.randomUUID().toString()
        val messageId = UUID.randomUUID().toString()
        publishStartExecution(workOrderId, messageId = messageId)
        awaitExecution(workOrderId)
        post("/api/executions/$workOrderId/diagnosis/start")

        // Same messageId, then a new messageId for the same work order: neither may touch the state.
        publishStartExecution(workOrderId, messageId = messageId)
        publishStartExecution(workOrderId, messageId = UUID.randomUUID().toString())
        val marker = UUID.randomUUID().toString()
        publishStartExecution(marker)
        awaitExecution(marker)

        assertEquals(1, listExecutions(null).count { it["workOrderId"].asText() == workOrderId })
        assertEquals("IN_DIAGNOSIS", body(get("/api/executions/$workOrderId", TestJwt.token()))["status"].asText())
        assertEquals(1L, mongoTemplate.count(
            org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").`is`(messageId),
            ),
            "processed_messages",
        ))
    }

    @Test
    fun `invalid transitions answer 409 and unknown work order 404`() {
        val workOrderId = UUID.randomUUID().toString()
        publishStartExecution(workOrderId)
        awaitExecution(workOrderId)

        assertEquals(409, post("/api/executions/$workOrderId/repair/start").statusCode())
        assertEquals(409, post("/api/executions/$workOrderId/finish").statusCode())
        assertEquals(200, post("/api/executions/$workOrderId/diagnosis/start").statusCode())
        assertEquals(409, post("/api/executions/$workOrderId/diagnosis/start").statusCode())

        assertEquals(404, post("/api/executions/${UUID.randomUUID()}/diagnosis/start").statusCode())
        assertEquals(404, get("/api/executions/${UUID.randomUUID()}", TestJwt.token()).statusCode())
        assertEquals(401, get("/api/executions").statusCode())
        // Only the one valid transition published an event.
        assertEquals("IN_DIAGNOSIS", receiveEvent()["payload"]["status"].asText())
        assertNull(rabbit.receive(TEST_EVENTS, 300))
    }

    @Test
    fun `queue lists oldest first`() {
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        publishStartExecution(first)
        awaitExecution(first)
        Thread.sleep(20)
        publishStartExecution(second)
        awaitExecution(second)

        val ids = listExecutions("QUEUED").map { it["workOrderId"].asText() }
        assertTrue(ids.indexOf(first) in 0 until ids.indexOf(second))
    }

    @Test
    fun `invalid JSON and unknown type go straight to the dead letter queue`() {
        rabbit.send("oficina", "command.execution.start-execution", rawMessage("{ this is not json".toByteArray()))
        val unknown = envelopeJson(UUID.randomUUID().toString(), "NoSuchType", "wo-x", "{}", "c")
        rabbit.send("oficina", "command.execution.start-execution", rawMessage(unknown.toByteArray()))
        val missingFields = envelopeJson(UUID.randomUUID().toString(), "StartExecution", "wo-y", """{"workOrderId":"wo-y"}""", "c")
        rabbit.send("oficina", "command.execution.start-execution", rawMessage(missingFields.toByteArray()))

        val dead = (1..3).map { rabbit.receive("execution.commands.dlq", 10_000) }
        dead.forEach { assertNotNull(it) }
        assertEquals(
            setOf("{ this is not json", unknown, missingFields),
            dead.map { String(it!!.body) }.toSet(),
        )
        assertEquals(0, rabbit.receive("execution.commands", 300)?.let { 1 } ?: 0)
    }

    private fun rawMessage(bytes: ByteArray): Message {
        val props = MessageProperties()
        props.contentType = MessageProperties.CONTENT_TYPE_JSON
        return Message(bytes, props)
    }

    private fun envelopeJson(messageId: String, type: String, sagaId: String, payload: String, correlationId: String) =
        """{"messageId":"$messageId","sagaId":"$sagaId","type":"$type","occurredAt":"2026-11-12T15:04:05.123Z","correlationId":"$correlationId","payload":$payload}"""

    private fun publishStartExecution(
        workOrderId: String,
        messageId: String = UUID.randomUUID().toString(),
        correlationId: String = "corr-test",
    ) {
        val payload = """{"workOrderId":"$workOrderId","trackingCode":"TRK-$workOrderId","customerName":"Maria Silva",
            "vehicle":{"plate":"ABC1D23","brand":"Fiat","model":"Argo","year":2022},
            "services":[{"code":"TROCA_OLEO","quantity":1}],"parts":[{"code":"FILTRO_OLEO","quantity":2}]}"""
        val message = rawMessage(envelopeJson(messageId, "StartExecution", workOrderId, payload, correlationId).toByteArray())
        message.messageProperties.messageId = messageId
        message.messageProperties.type = "StartExecution"
        rabbit.send("oficina", "command.execution.start-execution", message)
    }

    private fun awaitExecution(workOrderId: String): JsonNode {
        repeat(100) {
            val response = get("/api/executions/$workOrderId", TestJwt.token())
            if (response.statusCode() == 200) return body(response)
            Thread.sleep(100)
        }
        throw AssertionError("execution $workOrderId was not created")
    }

    private fun listExecutions(status: String?): List<JsonNode> {
        val query = status?.let { "?status=$it" } ?: ""
        val response = get("/api/executions$query", TestJwt.token())
        assertEquals(200, response.statusCode())
        return body(response).toList()
    }

    private fun receiveEvent(): JsonNode {
        val message = rabbit.receive(TEST_EVENTS, 10_000)
        assertNotNull(message, "expected an event on $TEST_EVENTS")
        assertEquals(MediaType.APPLICATION_JSON_VALUE, message!!.messageProperties.contentType)
        return json.readTree(message.body)
    }

    private fun post(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .header("Authorization", "Bearer ${TestJwt.token()}")
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String, token: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET()
        token?.let { request.header("Authorization", "Bearer $it") }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun body(response: HttpResponse<String>): JsonNode = json.readTree(response.body())

    companion object {
        const val TEST_EVENTS = "test.os-events"

        // MongoDB 8.0 refuses to start on Linux kernels 6.19 to 7.0.13 (SERVER-121912), which is
        // what the GitHub-hosted runners ship. Leaving rseq to glibc keeps TCMalloc off it.
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo: MongoDBContainer = MongoDBContainer("mongo:8.0.34")
            .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1")

        @Container
        @ServiceConnection
        @JvmStatic
        val rabbit = RabbitMQContainer("rabbitmq:4.2.9-alpine")
    }
}
