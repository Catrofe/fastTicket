package com.fastticket.catalog

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class SectorKind { NUMBERED, GA }

data class SectorView(
    val id: UUID,
    val name: String,
    val kind: SectorKind,
    val capacity: Int,
    val basePrice: BigDecimal,
)

data class EventView(
    val id: UUID,
    val name: String,
    val startsAt: Instant,
    val salesOpenAt: Instant,
    val status: String,
    val sectors: List<SectorView>,
)

data class SeatInfo(
    val seatId: UUID,
    val rowLabel: String,
    val seatNumber: Int,
    val sectorId: UUID,
    val sectorName: String,
    val sectorKind: SectorKind,
    val eventId: UUID,
    val basePrice: BigDecimal,
)

interface CatalogFacade {
    fun getEvent(id: UUID): EventView
    fun findSeats(seatIds: Collection<UUID>): List<SeatInfo>
}
