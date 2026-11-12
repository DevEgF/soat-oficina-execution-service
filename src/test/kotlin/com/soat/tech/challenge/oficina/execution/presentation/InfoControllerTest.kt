package com.soat.tech.challenge.oficina.execution.presentation

import com.soat.tech.challenge.oficina.execution.infrastructure.AppEnvironment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.info.BuildProperties
import java.util.Properties

class InfoControllerTest {

    @Test
    fun `reports service environment and build version`() {
        val beans = StaticListableBeanFactory().apply {
            addBean("buildProperties", BuildProperties(Properties().apply { setProperty("version", "1.2.3") }))
        }

        val info = InfoController("execution-service", AppEnvironment("hml"), beans.getBeanProvider(BuildProperties::class.java)).info()

        assertEquals(ServiceInfoResponse("execution-service", "hml", "1.2.3"), info)
    }

    @Test
    fun `reports unknown version when build info is absent`() {
        val info = InfoController(
            "execution-service",
            AppEnvironment("prod"),
            StaticListableBeanFactory().getBeanProvider(BuildProperties::class.java),
        ).info()

        assertEquals(ServiceInfoResponse("execution-service", "prod", "unknown"), info)
    }
}
