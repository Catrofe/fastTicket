package com.fastticket.catalog.infra.persistence

import com.fastticket.catalog.SeatInfo
import com.fastticket.catalog.SectorKind
import com.fastticket.catalog.domain.Event
import com.fastticket.catalog.domain.EventRepository
import com.fastticket.catalog.domain.HALF_QUOTA_PERCENT
import com.fastticket.catalog.domain.Sector
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.PreparedStatement
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private const val BATCH_SIZE = 5000

private fun Instant.toDb(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

@Repository
class JdbcEventRepository(
    private val jdbc: JdbcClient,
    private val template: JdbcTemplate,
) : EventRepository {

    override fun save(event: Event) {
        jdbc.sql(
            "INSERT INTO event (id, name, starts_at, sales_open_at, status) VALUES (:id, :name, :startsAt, :salesOpenAt, :status)",
        )
            .param("id", event.id).param("name", event.name)
            .param("startsAt", event.startsAt.toDb()).param("salesOpenAt", event.salesOpenAt.toDb())
            .param("status", event.status)
            .update()
        event.sectors.forEach { insertSector(event, it) }
    }

    private fun insertSector(event: Event, sector: Sector) {
        jdbc.sql(
            "INSERT INTO sector (id, event_id, name, kind, capacity, base_price) VALUES (:id, :eventId, :name, :kind, :capacity, :basePrice)",
        )
            .param("id", sector.id).param("eventId", event.id).param("name", sector.name)
            .param("kind", sector.kind.name).param("capacity", sector.capacity).param("basePrice", sector.basePrice)
            .update()
        when (sector.kind) {
            SectorKind.NUMBERED -> insertSeats(sector)
            SectorKind.GA -> insertGaCounters(sector)
        }
    }

    private fun insertSeats(sector: Sector) {
        val seats = sector.rows.flatMap { row -> (1..row.seats).map { row.label to it } }
        template.batchUpdate(
            "INSERT INTO seat (id, sector_id, row_label, seat_number) VALUES (?, ?, ?, ?)",
            seats, BATCH_SIZE,
        ) { ps: PreparedStatement, seat: Pair<String, Int> ->
            ps.setObject(1, UUID.randomUUID())
            ps.setObject(2, sector.id)
            ps.setString(3, seat.first)
            ps.setInt(4, seat.second)
        }
    }

    private fun insertGaCounters(sector: Sector) {
        jdbc.sql("INSERT INTO sector_inventory (sector_id, capacity) VALUES (:id, :capacity)")
            .param("id", sector.id).param("capacity", sector.capacity).update()
        jdbc.sql("INSERT INTO sector_half_quota (sector_id, half_cap) VALUES (:id, :halfCap)")
            .param("id", sector.id).param("halfCap", sector.capacity * HALF_QUOTA_PERCENT / 100).update()
    }

    override fun findById(id: UUID): Event? {
        val event = jdbc.sql("SELECT id, name, starts_at, sales_open_at, status FROM event WHERE id = :id")
            .param("id", id)
            .query { rs, _ ->
                Event(
                    rs.getObject("id", UUID::class.java), rs.getString("name"),
                    rs.getObject("starts_at", OffsetDateTime::class.java).toInstant(),
                    rs.getObject("sales_open_at", OffsetDateTime::class.java).toInstant(),
                    rs.getString("status"), emptyList(),
                )
            }.optional().orElse(null) ?: return null
        val sectors = jdbc.sql("SELECT id, name, kind, capacity, base_price FROM sector WHERE event_id = :id ORDER BY name")
            .param("id", id)
            .query { rs, _ ->
                Sector(
                    rs.getObject("id", UUID::class.java), rs.getString("name"),
                    SectorKind.valueOf(rs.getString("kind")), rs.getInt("capacity"), rs.getBigDecimal("base_price"),
                )
            }.list()
        return event.copy(sectors = sectors)
    }

    override fun findSeats(ids: Collection<UUID>): List<SeatInfo> =
        jdbc.sql(
            """
            SELECT st.id, st.row_label, st.seat_number, sc.id AS sector_id, sc.name AS sector_name,
                   sc.kind, sc.event_id, sc.base_price
            FROM seat st JOIN sector sc ON sc.id = st.sector_id
            WHERE st.id IN (:ids)
            """.trimIndent(),
        )
            .param("ids", ids)
            .query { rs, _ ->
                SeatInfo(
                    rs.getObject("id", UUID::class.java), rs.getString("row_label"), rs.getInt("seat_number"),
                    rs.getObject("sector_id", UUID::class.java), rs.getString("sector_name"),
                    SectorKind.valueOf(rs.getString("kind")), rs.getObject("event_id", UUID::class.java),
                    rs.getBigDecimal("base_price"),
                )
            }.list()
}
