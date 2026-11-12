package com.soat.tech.challenge.oficina.execution.infrastructure.messaging

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.amqp.AmqpRejectAndDontRequeueException
import org.springframework.boot.retry.RetryPolicySettings

class ListenerRetryConfigurationTest {

    @Test
    fun `deliberate rejections are not retried, other failures are`() {
        val settings = RetryPolicySettings()
        ListenerRetryConfiguration().skipRetryForRejectedMessages().customize(settings)
        val shouldRetry = settings.exceptionPredicate!!

        assertFalse(shouldRetry.test(AmqpRejectAndDontRequeueException("bad")))
        assertFalse(shouldRetry.test(RuntimeException("wrapped", AmqpRejectAndDontRequeueException("bad"))))
        assertTrue(shouldRetry.test(IllegalStateException("mongo down")))
    }
}
