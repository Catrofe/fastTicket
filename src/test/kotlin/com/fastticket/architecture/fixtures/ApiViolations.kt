package com.fastticket.inventory.api

import com.fastticket.inventory.infra.FixtureInfra
import java.time.Clock

class FixtureApiViolations {
    val infra = FixtureInfra()
    val clock: Clock = Clock.systemUTC()
    fun millis(): Long = System.currentTimeMillis()
}
