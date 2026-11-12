package com.soat.tech.challenge.oficina.execution.infrastructure.persistence

import jakarta.annotation.PostConstruct
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component

/**
 * Spring Boot leaves auto-index-creation off, so the indexes the design depends on (unique
 * workOrderId, queue order) are created explicitly at startup. Creation is idempotent.
 */
@Component
class MongoIndexInitializer(private val mongo: MongoTemplate) {

    @PostConstruct
    fun ensureIndexes() {
        val ops = mongo.indexOps(EXECUTIONS_COLLECTION)
        ops.createIndex(Index().on("workOrderId", Sort.Direction.ASC).unique().named("ux_workOrderId"))
        ops.createIndex(Index().on("status", Sort.Direction.ASC).on("queuedAt", Sort.Direction.ASC).named("ix_status_queuedAt"))
    }
}
