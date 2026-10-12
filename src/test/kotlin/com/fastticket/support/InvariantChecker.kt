package com.fastticket.support

import org.springframework.jdbc.core.simple.JdbcClient

enum class InvariantStatus { OK, VIOLATED, SKIPPED }

data class InvariantResult(val name: String, val status: InvariantStatus, val rows: List<Map<String, Any?>> = emptyList())

class InvariantChecker(private val jdbc: JdbcClient, private val schema: String = "public") {

    private class Invariant(val name: String, val tables: List<String>, val sql: ((String) -> String) -> String)

    // TODO(phase 1): reconcile column names with the real schema.
    private val invariants = listOf(
        Invariant("inv1_one_active_allocation_per_seat", listOf("seat_allocation")) { t ->
            "SELECT seat_id FROM ${t("seat_allocation")} WHERE status IN ('HELD','SOLD') GROUP BY seat_id HAVING count(*) > 1"
        },
        Invariant("inv2_capacity_not_exceeded", listOf("sector_inventory", "lot")) { t ->
            """
            SELECT 'sector_inventory' AS source, sector_id::text AS id FROM ${t("sector_inventory")}
              WHERE held < 0 OR sold < 0 OR held + sold > capacity
            UNION ALL
            SELECT 'lot', id::text FROM ${t("lot")} WHERE held < 0 OR sold < 0 OR held + sold > qty
            """.trimIndent()
        },
        Invariant("inv3_half_quota_within_40_percent", listOf("sector")) { t ->
            "SELECT id FROM ${t("sector")} WHERE half_held + half_sold > floor(capacity * 0.4)"
        },
        Invariant("inv4_max_6_tickets_per_buyer_event", listOf("buyer_event")) { t ->
            "SELECT * FROM ${t("buyer_event")} WHERE ticket_count NOT BETWEEN 0 AND 6"
        },
        Invariant("inv5_approved_payment_has_order_or_refund", listOf("payment", "order", "refund")) { t ->
            """
            SELECT p.id FROM ${t("payment")} p
              WHERE p.status = 'APPROVED'
                AND NOT EXISTS (SELECT 1 FROM ${t("order")} o WHERE o.hold_id = p.hold_id)
                AND NOT EXISTS (SELECT 1 FROM ${t("refund")} r WHERE r.payment_id = p.id)
            """.trimIndent()
        },
    )

    fun check(): List<InvariantResult> = invariants.map { inv ->
        val missing = inv.tables.filterNot(::tableExists)
        when {
            missing.size == inv.tables.size -> InvariantResult(inv.name, InvariantStatus.SKIPPED)
            missing.isNotEmpty() -> InvariantResult(
                inv.name,
                InvariantStatus.VIOLATED,
                listOf(mapOf("missing_tables" to missing.joinToString(","))),
            )
            else -> {
                val rows = jdbc.sql(inv.sql(::qualified)).query().listOfRows()
                InvariantResult(inv.name, if (rows.isEmpty()) InvariantStatus.OK else InvariantStatus.VIOLATED, rows)
            }
        }
    }

    fun assertClean() {
        val violated = check().filter { it.status == InvariantStatus.VIOLATED }
        check(violated.isEmpty()) { "Invariants violated: $violated" }
    }

    private fun qualified(table: String) = "\"$schema\".\"$table\""

    private fun tableExists(table: String): Boolean =
        jdbc.sql("SELECT to_regclass(:name) IS NOT NULL")
            .param("name", qualified(table))
            .query(Boolean::class.java)
            .single()
}
