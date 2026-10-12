package com.fastticket.inventory.infra.persistence

import com.fastticket.inventory.domain.Hold
import com.fastticket.inventory.domain.HoldItem
import com.fastticket.inventory.domain.HoldRepository
import com.fastticket.inventory.domain.HoldStatus
import com.fastticket.shared.validation.Cpf
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

private const val COLUMNS = "id, event_id, buyer_cpf, status, expires_at, total_amount"

@Repository
class JdbcHoldRepository(
    private val jdbc: JdbcClient,
    @Value("\${inventory.hold-ttl}") private val ttl: Duration,
) : HoldRepository {

    override fun insert(hold: Hold): Hold {
        val expiresAt = jdbc.sql(
            """
            INSERT INTO hold (id, event_id, buyer_cpf, status, expires_at, total_amount)
            VALUES (:id, :eventId, :cpf, :status, clock_timestamp() + make_interval(secs => :ttlSeconds), :total)
            RETURNING expires_at
            """.trimIndent(),
        )
            .param("id", hold.id).param("eventId", hold.eventId).param("cpf", hold.buyerCpf.digits)
            .param("status", hold.status.name).param("ttlSeconds", ttl.toMillis() / 1000.0)
            .param("total", hold.totalAmount)
            .query(OffsetDateTime::class.java).single().toInstant()
        hold.items.forEach { insertItem(hold.id, it) }
        return hold.copy(expiresAt = expiresAt)
    }

    private fun insertItem(holdId: UUID, item: HoldItem) {
        jdbc.sql(
            """
            INSERT INTO hold_item (id, hold_id, seat_id, sector_id, qty, unit_price, half_price)
            VALUES (:id, :holdId, :seatId, :sectorId, 1, :unitPrice, :half)
            """.trimIndent(),
        )
            .param("id", UUID.randomUUID()).param("holdId", holdId).param("seatId", item.seatId)
            .param("sectorId", item.sectorId).param("unitPrice", item.unitPrice).param("half", item.halfPrice)
            .update()
    }

    override fun findById(id: UUID): Hold? = load("SELECT $COLUMNS FROM hold WHERE id = :id", id)

    override fun lockById(id: UUID): Hold? = load("SELECT $COLUMNS FROM hold WHERE id = :id FOR UPDATE", id)

    override fun lockDueById(id: UUID): Hold? = load(
        "SELECT $COLUMNS FROM hold WHERE id = :id AND status = 'ACTIVE' AND expires_at < clock_timestamp() FOR UPDATE", id,
    )

    private fun load(sql: String, id: UUID): Hold? =
        jdbc.sql(sql).param("id", id).query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun findActive(cpf: Cpf, eventId: UUID): Hold? =
        jdbc.sql("SELECT $COLUMNS FROM hold WHERE buyer_cpf = :cpf AND event_id = :eventId AND status = 'ACTIVE'")
            .param("cpf", cpf.digits).param("eventId", eventId)
            .query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun findDueIds(limit: Int): List<UUID> =
        jdbc.sql(
            """
            SELECT id FROM hold WHERE status = 'ACTIVE' AND expires_at < clock_timestamp()
            ORDER BY expires_at LIMIT :limit
            """.trimIndent(),
        )
            .param("limit", limit).query { rs, _ -> rs.getObject("id", UUID::class.java) }.list()

    override fun transition(id: UUID, from: HoldStatus, to: HoldStatus): Boolean =
        jdbc.sql("UPDATE hold SET status = :to, updated_at = now() WHERE id = :id AND status = :from")
            .param("id", id).param("from", from.name).param("to", to.name)
            .update() == 1

    private fun items(holdId: UUID): List<HoldItem> =
        jdbc.sql("SELECT seat_id, sector_id, unit_price, half_price FROM hold_item WHERE hold_id = :id AND seat_id IS NOT NULL ORDER BY seat_id")
            .param("id", holdId)
            .query { rs, _ ->
                HoldItem(
                    rs.getObject("seat_id", UUID::class.java), rs.getObject("sector_id", UUID::class.java),
                    rs.getBigDecimal("unit_price"), rs.getBoolean("half_price"),
                )
            }.list()

    private fun map(rs: ResultSet): Hold {
        val id = rs.getObject("id", UUID::class.java)
        return Hold(
            id, rs.getObject("event_id", UUID::class.java),
            checkNotNull(Cpf.parseOrNull(rs.getString("buyer_cpf"))) { "invalid stored cpf" },
            HoldStatus.valueOf(rs.getString("status")),
            rs.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
            rs.getBigDecimal("total_amount"), items(id),
        )
    }
}
