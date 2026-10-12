package com.fastticket.catalog.api

import com.fastticket.catalog.SectorKind
import com.fastticket.catalog.domain.SeatStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class RowRequest(
    @field:NotBlank @field:Size(max = 10) val label: String,
    @field:Min(1) @field:Max(1000) val seats: Int,
)

data class SectorRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    val kind: SectorKind,
    @field:DecimalMin("0") @field:DecimalMax("100000") @field:Digits(integer = 6, fraction = 2) val basePrice: BigDecimal,
    @field:Min(1) @field:Max(100000) val capacity: Int? = null,
    @field:Size(min = 1, max = 500) @field:Valid val rows: List<RowRequest>? = null,
)

data class CreateEventRequest(
    @field:NotBlank @field:Size(max = 200) val name: String,
    val startsAt: Instant,
    val salesOpenAt: Instant,
    @field:Size(min = 1, max = 50) @field:Valid val sectors: List<SectorRequest>,
)

data class SeatDto(val id: UUID, val number: Int, val status: SeatStatus)

data class RowDto(val label: String, val seats: List<SeatDto>)

data class SectorMapDto(
    val sectorId: UUID,
    val name: String,
    val kind: SectorKind,
    val capacity: Int,
    val rows: List<RowDto>?,
)

data class SeatmapResponse(val eventId: UUID, val sectors: List<SectorMapDto>)
