package com.fastticket.migration

import com.fastticket.support.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Transactional
class CoreSchemaMigrationTest @Autowired constructor(private val jdbc: JdbcClient) {

    private val cpf = "12345678901"
    private fun id() = UUID.randomUUID()

    private fun exec(sql: String, vararg params: Any?) {
        var spec = jdbc.sql(sql)
        params.forEach { spec = spec.param(it) }
        spec.update()
    }

    private fun rejects(constraint: String, sql: String, vararg params: Any?) {
        val ex = assertFailsWith<DataIntegrityViolationException> { exec(sql, *params) }
        val message = generateSequence<Throwable>(ex) { it.cause }.joinToString(" ") { it.message.orEmpty() }
        assertTrue(message.contains(constraint), "expected violation of $constraint but got: $message")
    }

    private fun event(): UUID = id().also {
        exec("INSERT INTO event (id, name, starts_at, sales_open_at, status) VALUES (?, 'e', now(), now(), 'ON_SALE')", it)
    }

    private fun sector(eventId: UUID, kind: String = "NUMBERED", name: String = "s-${id()}"): UUID = id().also {
        exec("INSERT INTO sector (id, event_id, name, kind, capacity, base_price) VALUES (?, ?, ?, ?, 10, 100)", it, eventId, name, kind)
    }

    private fun seat(sectorId: UUID): UUID = id().also {
        exec("INSERT INTO seat (id, sector_id, row_label, seat_number) VALUES (?, ?, 'A', ?)", it, sectorId, (1..1_000_000).random())
    }

    private fun buyer(eventId: UUID, count: Int = 0) =
        exec("INSERT INTO buyer_event (cpf, event_id, ticket_count) VALUES (?, ?, ?)", cpf, eventId, count)

    private fun hold(eventId: UUID, status: String = "EXPIRED"): UUID = id().also {
        exec(
            "INSERT INTO hold (id, event_id, buyer_cpf, status, expires_at, total_amount) VALUES (?, ?, ?, ?, now(), 100)",
            it, eventId, cpf, status,
        )
    }

    private fun payment(holdId: UUID, status: String = "DECLINED"): UUID = id().also {
        exec("INSERT INTO payment (id, hold_id, method, amount, status) VALUES (?, ?, 'CREDIT', 100, ?)", it, holdId, status)
    }

    private fun holdWithBuyer(): Pair<UUID, UUID> {
        val e = event()
        buyer(e)
        return e to hold(e)
    }

    @Test
    fun `migrates a clean database creating every table`() {
        val tables = jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
            .query(String::class.java).list()
        val expected = listOf(
            "event", "sector", "seat", "sector_inventory", "lot", "sector_half_quota", "buyer_event", "hold",
            "hold_item", "seat_allocation", "order", "payment", "refund", "idempotency_key", "seat_version",
        )
        assertTrue(tables.containsAll(expected), "missing: ${expected - tables.toSet()}")
    }

    @Test
    fun `seat has no status column and event has no seatmap_version`() {
        val cols = jdbc.sql(
            "SELECT table_name || '.' || column_name FROM information_schema.columns WHERE table_schema = 'public'",
        ).query(String::class.java).list()
        assertTrue("seat.status" !in cols && "event.seatmap_version" !in cols && "buyer_event.active_hold_id" !in cols)
    }

    @Test
    fun `card token is never a column`() {
        val n = jdbc.sql(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND column_name ILIKE ANY (ARRAY['%token%','%card%'])",
        ).query(Int::class.java).single()
        assertEquals(0, n)
    }

    @Test
    fun `event status`() = rejects(
        "ck_event_status",
        "INSERT INTO event (id, name, starts_at, sales_open_at, status) VALUES (?, 'e', now(), now(), 'X')", id(),
    )

    @Test
    fun `event admission rate`() = rejects(
        "ck_event_admission_rate",
        "INSERT INTO event (id, name, starts_at, sales_open_at, status, admission_rate) VALUES (?, 'e', now(), now(), 'ON_SALE', 0)", id(),
    )

    @Test
    fun `sector kind`() = rejects(
        "ck_sector_kind",
        "INSERT INTO sector (id, event_id, name, kind, capacity, base_price) VALUES (?, ?, 'a', 'X', 10, 1)", id(), event(),
    )

    @Test
    fun `sector capacity`() = rejects(
        "ck_sector_capacity",
        "INSERT INTO sector (id, event_id, name, kind, capacity, base_price) VALUES (?, ?, 'a', 'GA', 0, 1)", id(), event(),
    )

    @Test
    fun `sector base price`() = rejects(
        "ck_sector_base_price",
        "INSERT INTO sector (id, event_id, name, kind, capacity, base_price) VALUES (?, ?, 'a', 'GA', 1, -1)", id(), event(),
    )

    @Test
    fun `sector name unique per event`() {
        val e = event()
        sector(e, name = "dup")
        rejects("uq_sector_event_name", "INSERT INTO sector (id, event_id, name, kind, capacity, base_price) VALUES (?, ?, 'dup', 'GA', 1, 1)", id(), e)
    }

    @Test
    fun `sector requires event`() = rejects(
        "fk_sector_event",
        "INSERT INTO sector (id, event_id, name, kind, capacity, base_price) VALUES (?, ?, 'a', 'GA', 1, 1)", id(), id(),
    )

    @Test
    fun `seat position unique per sector`() {
        val s = sector(event())
        exec("INSERT INTO seat (id, sector_id, row_label, seat_number) VALUES (?, ?, 'A', 1)", id(), s)
        rejects("uq_seat_position", "INSERT INTO seat (id, sector_id, row_label, seat_number) VALUES (?, ?, 'A', 1)", id(), s)
    }

    @Test
    fun `seat requires sector`() = rejects(
        "fk_seat_sector", "INSERT INTO seat (id, sector_id, row_label, seat_number) VALUES (?, ?, 'A', 1)", id(), id(),
    )

    @Test
    fun `sector inventory cannot exceed capacity`() = rejects(
        "ck_sector_inventory_counters",
        "INSERT INTO sector_inventory (sector_id, capacity, held, sold) VALUES (?, 10, 6, 5)", sector(event()),
    )

    @Test
    fun `sector inventory rejects negative counters`() = rejects(
        "ck_sector_inventory_counters",
        "INSERT INTO sector_inventory (sector_id, capacity, held, sold) VALUES (?, 10, -1, 0)", sector(event()),
    )

    @Test
    fun `sector inventory requires sector`() = rejects(
        "fk_sector_inventory_sector",
        "INSERT INTO sector_inventory (sector_id, capacity) VALUES (?, 10)", id(),
    )

    @Test
    fun `lot cannot exceed qty`() = rejects(
        "ck_lot_counters",
        "INSERT INTO lot (id, sector_id, number, qty, held, sold, price, starts_at) VALUES (?, ?, 1, 10, 6, 5, 1, now())",
        id(), sector(event(), "GA"),
    )

    @Test
    fun `lot qty positive`() = rejects(
        "ck_lot_counters",
        "INSERT INTO lot (id, sector_id, number, qty, price, starts_at) VALUES (?, ?, 1, 0, 1, now())", id(), sector(event(), "GA"),
    )

    @Test
    fun `lot price`() = rejects(
        "ck_lot_price",
        "INSERT INTO lot (id, sector_id, number, qty, price, starts_at) VALUES (?, ?, 1, 1, -1, now())", id(), sector(event(), "GA"),
    )

    @Test
    fun `lot number unique per sector`() {
        val s = sector(event(), "GA")
        exec("INSERT INTO lot (id, sector_id, number, qty, price, starts_at) VALUES (?, ?, 1, 1, 1, now())", id(), s)
        rejects("uq_lot_sector_number", "INSERT INTO lot (id, sector_id, number, qty, price, starts_at) VALUES (?, ?, 1, 1, 1, now())", id(), s)
    }

    @Test
    fun `lot requires sector`() = rejects(
        "fk_lot_sector",
        "INSERT INTO lot (id, sector_id, number, qty, price, starts_at) VALUES (?, ?, 1, 1, 1, now())", id(), id(),
    )

    @Test
    fun `half quota cannot exceed cap`() = rejects(
        "ck_sector_half_quota_counters",
        "INSERT INTO sector_half_quota (sector_id, half_cap, half_held, half_sold) VALUES (?, 4, 3, 2)", sector(event(), "GA"),
    )

    @Test
    fun `half quota requires sector`() = rejects(
        "fk_sector_half_quota_sector", "INSERT INTO sector_half_quota (sector_id, half_cap) VALUES (?, 4)", id(),
    )

    @Test
    fun `buyer cpf must be 11 digits`() = rejects(
        "ck_buyer_event_cpf", "INSERT INTO buyer_event (cpf, event_id) VALUES ('123.456.789-01', ?)", event(),
    )

    @Test
    fun `buyer ticket count max 6`() = rejects(
        "ck_buyer_event_ticket_count", "INSERT INTO buyer_event (cpf, event_id, ticket_count) VALUES (?, ?, 7)", cpf, event(),
    )

    @Test
    fun `buyer ticket count not negative`() = rejects(
        "ck_buyer_event_ticket_count", "INSERT INTO buyer_event (cpf, event_id, ticket_count) VALUES (?, ?, -1)", cpf, event(),
    )

    @Test
    fun `buyer event primary key`() {
        val e = event()
        buyer(e)
        rejects("pk_buyer_event", "INSERT INTO buyer_event (cpf, event_id) VALUES (?, ?)", cpf, e)
    }

    @Test
    fun `buyer event requires event`() = rejects(
        "fk_buyer_event_event", "INSERT INTO buyer_event (cpf, event_id) VALUES (?, ?)", cpf, id(),
    )

    @Test
    fun `hold status`() {
        val e = event()
        buyer(e)
        rejects("ck_hold_status", "INSERT INTO hold (id, event_id, buyer_cpf, status, expires_at, total_amount) VALUES (?, ?, ?, 'X', now(), 1)", id(), e, cpf)
    }

    @Test
    fun `hold total amount`() {
        val e = event()
        buyer(e)
        rejects("ck_hold_total_amount", "INSERT INTO hold (id, event_id, buyer_cpf, status, expires_at, total_amount) VALUES (?, ?, ?, 'ACTIVE', now(), -1)", id(), e, cpf)
    }

    @Test
    fun `hold requires buyer_event`() = rejects(
        "fk_hold_buyer_event",
        "INSERT INTO hold (id, event_id, buyer_cpf, status, expires_at, total_amount) VALUES (?, ?, ?, 'ACTIVE', now(), 1)", id(), event(), cpf,
    )

    @Test
    fun `only one active hold per buyer and event`() {
        val e = event()
        buyer(e)
        hold(e, "ACTIVE")
        rejects("ux_one_active_hold", "INSERT INTO hold (id, event_id, buyer_cpf, status, expires_at, total_amount) VALUES (?, ?, ?, 'ACTIVE', now(), 1)", id(), e, cpf)
    }

    @Test
    fun `non active holds do not collide`() {
        val e = event()
        buyer(e)
        hold(e, "ACTIVE")
        hold(e, "EXPIRED")
        hold(e, "EXPIRED")
        hold(e, "CONFIRMED")
    }

    @Test
    fun `hold item numbered requires qty 1`() {
        val (e, h) = holdWithBuyer()
        val s = sector(e)
        rejects("ck_hold_item_shape", "INSERT INTO hold_item (id, hold_id, seat_id, sector_id, qty, unit_price) VALUES (?, ?, ?, ?, 2, 1)", id(), h, seat(s), s)
    }

    @Test
    fun `hold item numbered cannot carry a lot`() {
        val (e, h) = holdWithBuyer()
        val s = sector(e)
        rejects(
            "ck_hold_item_shape",
            "INSERT INTO hold_item (id, hold_id, seat_id, sector_id, lot_id, qty, unit_price) VALUES (?, ?, ?, ?, ?, 1, 1)",
            id(), h, seat(s), s, id(),
        )
    }

    @Test
    fun `hold item ga requires positive qty`() {
        val (e, h) = holdWithBuyer()
        rejects("ck_hold_item_shape", "INSERT INTO hold_item (id, hold_id, sector_id, qty, unit_price) VALUES (?, ?, ?, 0, 1)", id(), h, sector(e, "GA"))
    }

    @Test
    fun `hold item unit price`() {
        val (e, h) = holdWithBuyer()
        val s = sector(e)
        rejects("ck_hold_item_unit_price", "INSERT INTO hold_item (id, hold_id, seat_id, sector_id, unit_price) VALUES (?, ?, ?, ?, -1)", id(), h, seat(s), s)
    }

    @Test
    fun `hold item requires hold`() {
        val s = sector(event())
        rejects("fk_hold_item_hold", "INSERT INTO hold_item (id, hold_id, seat_id, sector_id, unit_price) VALUES (?, ?, ?, ?, 1)", id(), id(), seat(s), s)
    }

    @Test
    fun `seat allocation status`() {
        val (e, h) = holdWithBuyer()
        rejects("ck_seat_allocation_status", "INSERT INTO seat_allocation (id, seat_id, hold_id, status) VALUES (?, ?, ?, 'X')", id(), seat(sector(e)), h)
    }

    @Test
    fun `seat allows a single held or sold allocation`() {
        val (e, h) = holdWithBuyer()
        val seatId = seat(sector(e))
        exec("INSERT INTO seat_allocation (id, seat_id, hold_id, status) VALUES (?, ?, ?, 'HELD')", id(), seatId, h)
        rejects("ux_seat_active", "INSERT INTO seat_allocation (id, seat_id, hold_id, status) VALUES (?, ?, ?, 'SOLD')", id(), seatId, h)
    }

    @Test
    fun `released allocations do not block a seat`() {
        val (e, h) = holdWithBuyer()
        val seatId = seat(sector(e))
        exec("INSERT INTO seat_allocation (id, seat_id, hold_id, status) VALUES (?, ?, ?, 'RELEASED')", id(), seatId, h)
        exec("INSERT INTO seat_allocation (id, seat_id, hold_id, status) VALUES (?, ?, ?, 'RELEASED')", id(), seatId, h)
        exec("INSERT INTO seat_allocation (id, seat_id, hold_id, status) VALUES (?, ?, ?, 'HELD')", id(), seatId, h)
    }

    @Test
    fun `seat allocation requires seat`() {
        val (_, h) = holdWithBuyer()
        rejects("fk_seat_allocation_seat", "INSERT INTO seat_allocation (id, seat_id, hold_id, status) VALUES (?, ?, ?, 'HELD')", id(), id(), h)
    }

    @Test
    fun `seat allocation requires hold`() {
        val (e, _) = holdWithBuyer()
        rejects("fk_seat_allocation_hold", "INSERT INTO seat_allocation (id, seat_id, hold_id, status) VALUES (?, ?, ?, 'HELD')", id(), seat(sector(e)), id())
    }

    @Test
    fun `order status`() {
        val (_, h) = holdWithBuyer()
        rejects("ck_order_status", "INSERT INTO \"order\" (id, hold_id, status, total_amount) VALUES (?, ?, 'X', 1)", id(), h)
    }

    @Test
    fun `order total amount`() {
        val (_, h) = holdWithBuyer()
        rejects("ck_order_total_amount", "INSERT INTO \"order\" (id, hold_id, status, total_amount) VALUES (?, ?, 'CONFIRMED', -1)", id(), h)
    }

    @Test
    fun `one order per hold`() {
        val (_, h) = holdWithBuyer()
        exec("INSERT INTO \"order\" (id, hold_id, status, total_amount) VALUES (?, ?, 'CONFIRMED', 1)", id(), h)
        rejects("uq_order_hold", "INSERT INTO \"order\" (id, hold_id, status, total_amount) VALUES (?, ?, 'CONFIRMED', 1)", id(), h)
    }

    @Test
    fun `order requires hold`() = rejects(
        "fk_order_hold", "INSERT INTO \"order\" (id, hold_id, status, total_amount) VALUES (?, ?, 'CONFIRMED', 1)", id(), id(),
    )

    @Test
    fun `payment method`() {
        val (_, h) = holdWithBuyer()
        rejects("ck_payment_method", "INSERT INTO payment (id, hold_id, method, amount, status) VALUES (?, ?, 'PIX', 1, 'PENDING')", id(), h)
    }

    @Test
    fun `payment amount positive`() {
        val (_, h) = holdWithBuyer()
        rejects("ck_payment_amount", "INSERT INTO payment (id, hold_id, method, amount, status) VALUES (?, ?, 'CREDIT', 0, 'PENDING')", id(), h)
    }

    @Test
    fun `payment status`() {
        val (_, h) = holdWithBuyer()
        rejects("ck_payment_status", "INSERT INTO payment (id, hold_id, method, amount, status) VALUES (?, ?, 'CREDIT', 1, 'X')", id(), h)
    }

    @Test
    fun `payment requires hold`() = rejects(
        "fk_payment_hold", "INSERT INTO payment (id, hold_id, method, amount, status) VALUES (?, ?, 'CREDIT', 1, 'PENDING')", id(), id(),
    )

    @Test
    fun `only one pending payment per hold`() {
        val (_, h) = holdWithBuyer()
        payment(h, "PENDING")
        rejects("ux_payment_pending_per_hold", "INSERT INTO payment (id, hold_id, method, amount, status) VALUES (?, ?, 'DEBIT', 1, 'PENDING')", id(), h)
    }

    @Test
    fun `non pending payments do not collide`() {
        val (_, h) = holdWithBuyer()
        payment(h, "DECLINED")
        payment(h, "DECLINED")
        payment(h, "PENDING")
    }

    @Test
    fun `refund amount positive`() {
        val (_, h) = holdWithBuyer()
        rejects("ck_refund_amount", "INSERT INTO refund (id, payment_id, amount, status, reason) VALUES (?, ?, 0, 'PENDING', 'CANCELLED')", id(), payment(h))
    }

    @Test
    fun `refund status`() {
        val (_, h) = holdWithBuyer()
        rejects("ck_refund_status", "INSERT INTO refund (id, payment_id, amount, status, reason) VALUES (?, ?, 1, 'X', 'CANCELLED')", id(), payment(h))
    }

    @Test
    fun `refund reason`() {
        val (_, h) = holdWithBuyer()
        rejects("ck_refund_reason", "INSERT INTO refund (id, payment_id, amount, status, reason) VALUES (?, ?, 1, 'PENDING', 'X')", id(), payment(h))
    }

    @Test
    fun `one refund per payment`() {
        val (_, h) = holdWithBuyer()
        val p = payment(h)
        exec("INSERT INTO refund (id, payment_id, amount, status, reason) VALUES (?, ?, 1, 'PENDING', 'CANCELLED')", id(), p)
        rejects("ux_refund_payment", "INSERT INTO refund (id, payment_id, amount, status, reason) VALUES (?, ?, 1, 'PENDING', 'CANCELLED')", id(), p)
    }

    @Test
    fun `refund requires payment`() = rejects(
        "fk_refund_payment", "INSERT INTO refund (id, payment_id, amount, status, reason) VALUES (?, ?, 1, 'PENDING', 'CANCELLED')", id(), id(),
    )

    @Test
    fun `seat version requires seat`() = rejects(
        "fk_seat_version_seat", "INSERT INTO seat_version (seat_id) VALUES (?)", id(),
    )

    @Test
    fun `seat version is not negative`() = rejects(
        "ck_seat_version_version", "INSERT INTO seat_version (seat_id, version) VALUES (?, -1)", seat(sector(event())),
    )

    @Test
    fun `idempotency status`() = rejects(
        "ck_idempotency_key_status", "INSERT INTO idempotency_key (key, scope, request_hash, status) VALUES ('k', 's', 'h', 'X')",
    )

    @Test
    fun `idempotency key unique per scope`() {
        exec("INSERT INTO idempotency_key (key, scope, request_hash, status) VALUES ('k', 's', 'h', 'DONE')")
        exec("INSERT INTO idempotency_key (key, scope, request_hash, status) VALUES ('k', 's2', 'h', 'DONE')")
        rejects("pk_idempotency_key", "INSERT INTO idempotency_key (key, scope, request_hash, status) VALUES ('k', 's', 'h2', 'DONE')")
    }
}
