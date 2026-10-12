package com.fastticket.inventory.api

import com.fastticket.inventory.infra.FixtureInfra
import java.time.Clock

class FixtureApiViolations {
    val infra = FixtureInfra()
    val clock: Clock = Clock.systemUTC()
    val date = java.util.Date()
    val calendar: java.util.Calendar = java.util.Calendar.getInstance()
    fun millis(): Long = System.currentTimeMillis()
}
