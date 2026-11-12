package com.soat.tech.challenge.oficina.execution.infrastructure.persistence

import com.soat.tech.challenge.oficina.execution.application.ExecutionRepository
import com.soat.tech.challenge.oficina.execution.domain.model.Execution
import com.soat.tech.challenge.oficina.execution.domain.model.ExecutionStatus
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Repository

@Repository
class MongoExecutionRepository(private val mongo: MongoTemplate) : ExecutionRepository {

    override fun findByWorkOrderId(workOrderId: String): Execution? =
        mongo.findOne(Query(Criteria.where("workOrderId").`is`(workOrderId)), ExecutionDocument::class.java)
            ?.toDomain()

    override fun findAll(status: ExecutionStatus?): List<Execution> {
        val query = Query().with(Sort.by(Sort.Direction.ASC, "queuedAt", "workOrderId"))
        status?.let { query.addCriteria(Criteria.where("status").`is`(it)) }
        return mongo.find(query, ExecutionDocument::class.java).map { it.toDomain() }
    }

    override fun insertIfAbsent(execution: Execution): Boolean =
        try {
            mongo.insert(execution.toDocument())
            true
        } catch (_: DuplicateKeyException) {
            false
        }

    override fun save(execution: Execution): Execution =
        mongo.save(execution.toDocument()).toDomain()
}
