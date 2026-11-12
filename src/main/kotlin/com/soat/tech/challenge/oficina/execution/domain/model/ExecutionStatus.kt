package com.soat.tech.challenge.oficina.execution.domain.model

enum class ExecutionStatus {
    QUEUED,
    IN_DIAGNOSIS,
    IN_EXECUTION,
    FINISHED,

    /** Reserved for the compensation flow; nothing moves an execution here yet. */
    FAILED,
}
