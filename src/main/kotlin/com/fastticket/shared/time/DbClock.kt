package com.fastticket.shared.time

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime

@Component
class DbClock(private val jdbc: JdbcClient) {

    fun now(): Instant =
        jdbc.sql("SELECT clock_timestamp()")
            .query(OffsetDateTime::class.java)
            .single()
            .toInstant()
}
