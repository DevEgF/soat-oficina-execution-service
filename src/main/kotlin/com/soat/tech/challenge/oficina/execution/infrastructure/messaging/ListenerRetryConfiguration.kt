package com.soat.tech.challenge.oficina.execution.infrastructure.messaging

import org.springframework.amqp.AmqpRejectAndDontRequeueException
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The retry attempts come from `spring.rabbitmq.listener.simple.retry.*`. Messages the listener
 * already rejected on purpose (invalid JSON, unknown type) are never retried: they go straight to the DLQ.
 */
@Configuration
class ListenerRetryConfiguration {

    @Bean
    fun skipRetryForRejectedMessages(): RabbitListenerRetrySettingsCustomizer =
        RabbitListenerRetrySettingsCustomizer { settings ->
            settings.exceptionPredicate = java.util.function.Predicate { error -> !isDeliberateRejection(error) }
        }

    companion object {
        fun isDeliberateRejection(error: Throwable): Boolean =
            generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }
                .take(10)
                .any { it is AmqpRejectAndDontRequeueException }
    }
}
