package com.fastticket.catalog.domain

import com.fastticket.catalog.SeatInfo
import com.fastticket.catalog.SectorKind
import java.util.UUID

enum class SeatStatus { AVAILABLE, HELD, SOLD }

data class SeatState(val id: UUID, val number: Int, val status: SeatStatus)

data class RowMap(val label: String, val seats: List<SeatState>)

data class SectorMap(
    val sectorId: UUID,
    val name: String,
    val kind: SectorKind,
    val capacity: Int,
    val rows: List<RowMap>,
)

interface EventRepository {
    fun save(event: Event)
    fun findById(id: UUID): Event?
    fun findSeats(ids: Collection<UUID>): List<SeatInfo>
}

interface SeatmapQuery {
    fun load(eventId: UUID, sectorId: UUID?): List<SectorMap>?
}
