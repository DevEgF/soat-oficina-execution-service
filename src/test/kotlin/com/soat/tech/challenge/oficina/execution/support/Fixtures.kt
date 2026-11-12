package com.soat.tech.challenge.oficina.execution.support

import com.soat.tech.challenge.oficina.execution.domain.model.Execution
import com.soat.tech.challenge.oficina.execution.domain.model.ItemLine
import com.soat.tech.challenge.oficina.execution.domain.model.VehicleInfo
import java.time.Instant

object Fixtures {
    val NOW: Instant = Instant.parse("2026-11-12T15:00:00Z")

    fun queued(workOrderId: String = "wo-1", correlationId: String? = "corr-1", now: Instant = NOW) =
        Execution.queue(
            workOrderId = workOrderId,
            trackingCode = "TRK-1",
            customerName = "Maria Silva",
            vehicle = VehicleInfo("ABC1D23", "Fiat", "Argo", 2022),
            services = listOf(ItemLine("TROCA_OLEO", 1)),
            parts = listOf(ItemLine("FILTRO_OLEO", 2)),
            correlationId = correlationId,
            now = now,
        )
}
