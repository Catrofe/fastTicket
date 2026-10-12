package com.fastticket.catalog.infra.persistence

import com.fastticket.catalog.SectorKind
import com.fastticket.catalog.domain.RowMap
import com.fastticket.catalog.domain.SeatState
import com.fastticket.catalog.domain.SeatStatus
import com.fastticket.catalog.domain.SeatmapQuery
import com.fastticket.catalog.domain.SectorMap
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

private data class SeatRowData(
    val sectorId: UUID,
    val sectorName: String,
    val kind: SectorKind,
    val capacity: Int,
    val seatId: UUID?,
    val rowLabel: String?,
    val seatNumber: Int?,
    val status: SeatStatus,
)

@Repository
class JdbcSeatmapQuery(private val jdbc: JdbcClient) : SeatmapQuery {

    override fun load(eventId: UUID, sectorId: UUID?): List<SectorMap>? {
        val exists = jdbc.sql("SELECT count(*) FROM event WHERE id = :id").param("id", eventId)
            .query(Long::class.java).single() > 0
        if (!exists) return null

        val filter = if (sectorId != null) " AND sc.id = :sectorId" else ""
        var statement = jdbc.sql(
            """
            SELECT sc.id AS sector_id, sc.name AS sector_name, sc.kind, sc.capacity,
                   st.id AS seat_id, st.row_label, st.seat_number,
                   CASE a.status WHEN 'HELD' THEN 'HELD' WHEN 'SOLD' THEN 'SOLD' ELSE 'AVAILABLE' END AS status
            FROM sector sc
            LEFT JOIN seat st ON st.sector_id = sc.id
            LEFT JOIN seat_allocation a ON a.seat_id = st.id AND a.status IN ('HELD', 'SOLD')
            WHERE sc.event_id = :eventId$filter
            ORDER BY sc.name, length(st.row_label), st.row_label, st.seat_number
            """.trimIndent(),
        ).param("eventId", eventId)
        if (sectorId != null) statement = statement.param("sectorId", sectorId)

        val rows = statement.query { rs, _ ->
            SeatRowData(
                rs.getObject("sector_id", UUID::class.java), rs.getString("sector_name"),
                SectorKind.valueOf(rs.getString("kind")), rs.getInt("capacity"),
                rs.getObject("seat_id", UUID::class.java), rs.getString("row_label"),
                rs.getInt("seat_number").takeIf { !rs.wasNull() },
                SeatStatus.valueOf(rs.getString("status")),
            )
        }.list()

        return rows.groupByTo(LinkedHashMap()) { it.sectorId }.values.map { sectorRows ->
            val first = sectorRows.first()
            val seatRows = sectorRows.filter { it.seatId != null }
                .groupByTo(LinkedHashMap()) { it.rowLabel!! }
                .map { (label, seats) -> RowMap(label, seats.map { SeatState(it.seatId!!, it.seatNumber!!, it.status) }) }
            SectorMap(first.sectorId, first.sectorName, first.kind, first.capacity, seatRows)
        }
    }
}
