package com.fastticket.support

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class InvariantCheckerTest @Autowired constructor(
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
) {

    @Test
    fun `empty database skips every invariant and stays clean`() {
        val results = InvariantChecker(jdbc).check()

        assertEquals(5, results.size)
        assertTrue(results.all { it.status == InvariantStatus.SKIPPED })
        InvariantChecker(jdbc).assertClean()
    }

    @Test
    fun `detects one seeded violation per invariant`() {
        val schema = "scratch_invariants"
        tx.executeWithoutResult { status ->
            jdbc.sql("CREATE SCHEMA $schema").update()
            jdbc.sql("SET LOCAL search_path TO $schema").update()
            listOf(
                "CREATE TABLE seat_allocation (seat_id int, status text)",
                "CREATE TABLE sector_inventory (sector_id int, capacity int, held int, sold int)",
                "CREATE TABLE lot (id int, qty int, held int, sold int)",
                "CREATE TABLE sector (id int, capacity int, half_held int, half_sold int)",
                "CREATE TABLE buyer_event (id int, ticket_count int)",
                "CREATE TABLE payment (id int, hold_id int, status text)",
                "CREATE TABLE \"order\" (id int, hold_id int)",
                "CREATE TABLE refund (id int, payment_id int)",
                "INSERT INTO seat_allocation VALUES (1,'HELD'),(1,'SOLD')",
                "INSERT INTO sector_inventory VALUES (1,10,6,6)",
                "INSERT INTO sector VALUES (1,10,3,2)",
                "INSERT INTO buyer_event VALUES (1,7)",
                "INSERT INTO payment VALUES (1,1,'APPROVED')",
            ).forEach { jdbc.sql(it).update() }

            val results = InvariantChecker(jdbc, schema).check()

            assertEquals(5, results.size)
            assertTrue(results.all { it.status == InvariantStatus.VIOLATED }, results.toString())
            assertFailsWith<IllegalStateException> { InvariantChecker(jdbc, schema).assertClean() }
            status.setRollbackOnly()
        }
    }
}
