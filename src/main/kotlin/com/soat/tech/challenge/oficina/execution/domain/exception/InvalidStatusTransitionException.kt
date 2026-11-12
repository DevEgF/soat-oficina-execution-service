package com.soat.tech.challenge.oficina.execution.domain.exception

/** Raised when an action is not allowed from the current status; answered with 409. */
class InvalidStatusTransitionException(currentStatus: String, action: String) :
    DomainException("Cannot $action when the execution is $currentStatus")
