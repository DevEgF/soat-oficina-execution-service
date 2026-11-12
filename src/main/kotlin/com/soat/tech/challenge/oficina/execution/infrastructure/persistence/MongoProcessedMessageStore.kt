package com.soat.tech.challenge.oficina.execution.infrastructure.persistence

import com.soat.tech.challenge.oficina.execution.application.ProcessedMessageStore
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Repository
import java.time.Clock

@Repository
class MongoProcessedMessageStore(
    private val mongo: MongoTemplate,
    private val clock: Clock,
) : ProcessedMessageStore {

    override fun isProcessed(messageId: String): Boolean =
        mongo.exists(
            Query(Criteria.where("_id").`is`(messageId)),
            PROCESSED_MESSAGES_COLLECTION,
        )

    override fun markProcessed(messageId: String, type: String) {
        try {
            mongo.insert(ProcessedMessageDocument(messageId, type, clock.instant()))
        } catch (_: DuplicateKeyException) {
            // A concurrent delivery of the same message already recorded it.
        }
    }
}
