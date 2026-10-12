package com.fastticket.catalog.domain

import com.fastticket.catalog.SectorKind
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

const val STATUS_ON_SALE = "ON_SALE"
const val MAX_SEATS_PER_EVENT = 100_000
const val HALF_QUOTA_PERCENT = 40

data class SeatRow(val label: String, val seats: Int)

data class Sector(
    val id: UUID,
    val name: String,
    val kind: SectorKind,
    val capacity: Int,
    val basePrice: BigDecimal,
    val rows: List<SeatRow> = emptyList(),
)

data class Event(
    val id: UUID,
    val name: String,
    val startsAt: Instant,
    val salesOpenAt: Instant,
    val status: String,
    val sectors: List<Sector>,
)
