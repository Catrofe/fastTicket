package com.fastticket.shared.time

import com.fastticket.support.InvariantsExtension
import com.fastticket.support.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@ExtendWith(InvariantsExtension::class)
class DbClockTest @Autowired constructor(
    private val dbClock: DbClock,
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
) {

    @Test
    fun `advances inside a single transaction`() {
        val (first, second) = tx.execute {
            val a = dbClock.now()
            jdbc.sql("SELECT pg_sleep(0.05)").query().singleRow()
            val b = dbClock.now()
            a to b
        }!!

        assertTrue(second.isAfter(first), "clock_timestamp() must advance within a transaction")
    }

    @Test
    fun `is close to the JVM time`() {
        val delta = Duration.between(dbClock.now(), Instant.now()).abs()

        assertTrue(delta < Duration.ofSeconds(5))
    }
}
