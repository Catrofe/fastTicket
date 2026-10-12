package com.fastticket.inventory.domain

import com.fastticket.inventory.infra.FixtureInfra
import java.time.Instant

class FixtureDomainViolations {
    val infra = FixtureInfra()
    fun now(): Instant = Instant.now()
}
