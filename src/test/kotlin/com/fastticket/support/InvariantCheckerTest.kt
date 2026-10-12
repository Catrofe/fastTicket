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

    private val schema = "scratch_invariants"

    private val ddl = listOf(
        "CREATE TABLE seat_allocation (seat_id int, status text)",
        "CREATE TABLE sector_inventory (sector_id int, capacity int, held int, sold int)",
        "CREATE TABLE lot (id int, qty int, held int, sold int)",
        "CREATE TABLE sector (id int, capacity int, half_held int, half_sold int)",
        "CREATE TABLE buyer_event (id int, ticket_count int)",
        "CREATE TABLE payment (id int, hold_id int, status text)",
        "CREATE TABLE \"order\" (id int, hold_id int)",
        "CREATE TABLE refund (id int, payment_id int)",
    )

    private val cleanRows = listOf(
        "INSERT INTO seat_allocation VALUES (1,'HELD'),(2,'SOLD')",
        "INSERT INTO sector_inventory VALUES (1,10,3,3)",
        "INSERT INTO lot VALUES (1,10,3,3)",
        "INSERT INTO sector VALUES (1,10,2,2)",
        "INSERT INTO buyer_event VALUES (1,6)",
        "INSERT INTO payment VALUES (1,1,'APPROVED')",
        "INSERT INTO \"order\" VALUES (1,1)",
    )

    private val violations = mapOf(
        "inv1_one_active_allocation_per_seat" to "INSERT INTO seat_allocation VALUES (1,'SOLD')",
        "inv2_capacity_not_exceeded" to "UPDATE lot SET held = 20",
        "inv3_half_quota_within_40_percent" to "UPDATE sector SET half_held = 5",
        "inv4_max_6_tickets_per_buyer_event" to "UPDATE buyer_event SET ticket_count = 7",
        "inv5_approved_payment_has_order_or_refund" to "DELETE FROM \"order\"",
    )

    private fun inScratchSchema(block: () -> Unit) = tx.executeWithoutResult { status ->
        jdbc.sql("CREATE SCHEMA $schema").update()
        jdbc.sql("SET LOCAL search_path TO public").update()
        block()
        status.setRollbackOnly()
    }

    private fun run(sql: List<String>) = sql.forEach {
        jdbc.sql(it.replace("CREATE TABLE ", "CREATE TABLE $schema.")
            .replace(Regex("(INSERT INTO|UPDATE|DELETE FROM) "), "$1 $schema.")).update()
    }

    @Test
    fun `empty database skips every invariant and stays clean`() {
        val results = InvariantChecker(jdbc).check()

        assertEquals(5, results.size)
        assertTrue(results.all { it.status == InvariantStatus.SKIPPED })
        InvariantChecker(jdbc).assertClean()
    }

    @Test
    fun `populated tables without violations are OK`() = inScratchSchema {
        run(ddl + cleanRows)

        val results = InvariantChecker(jdbc, schema).check()

        assertTrue(results.all { it.status == InvariantStatus.OK }, results.toString())
        InvariantChecker(jdbc, schema).assertClean()
    }

    @Test
    fun `each violation is detected by its own invariant only`() {
        violations.forEach { (expected, violation) ->
            inScratchSchema {
                run(ddl + cleanRows + violation)

                val results = InvariantChecker(jdbc, schema).check()

                val violated = results.filter { it.status == InvariantStatus.VIOLATED }
                assertEquals(listOf(expected), violated.map { it.name }, results.toString())
                assertTrue(violated.single().rows.isNotEmpty())
                assertFailsWith<IllegalStateException> { InvariantChecker(jdbc, schema).assertClean() }
            }
        }
    }

    @Test
    fun `partially present tables fail instead of being skipped`() = inScratchSchema {
        run(listOf("CREATE TABLE sector_inventory (sector_id int, capacity int, held int, sold int)"))

        val result = InvariantChecker(jdbc, schema).check()
            .single { it.name == "inv2_capacity_not_exceeded" }

        assertEquals(InvariantStatus.VIOLATED, result.status)
        assertEquals("lot", result.rows.single()["missing_tables"])
    }
}
