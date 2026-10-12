package com.fastticket.inventory.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.fastticket.inventory.HoldView
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CreateHoldRequest(
    @field:NotNull @param:JsonProperty("event_id") val eventId: UUID?,
    @field:NotNull @field:Size(min = 1, max = 6) @param:JsonProperty("seat_ids") val seatIds: List<@NotNull UUID?>?,
)

data class HoldItemResponse(
    @get:JsonProperty("seat_id") val seatId: UUID,
    @get:JsonProperty("sector_id") val sectorId: UUID,
    @get:JsonProperty("unit_price") val unitPrice: BigDecimal,
)

data class HoldResponse(
    @get:JsonProperty("hold_id") val holdId: UUID,
    @get:JsonProperty("event_id") val eventId: UUID,
    val status: String,
    @get:JsonProperty("expires_at") val expiresAt: Instant,
    @get:JsonProperty("total_amount") val totalAmount: BigDecimal,
    val items: List<HoldItemResponse>,
)

fun HoldView.toResponse() = HoldResponse(
    id, eventId, status, expiresAt, totalAmount,
    items.map { HoldItemResponse(it.seatId, it.sectorId, it.unitPrice) },
)
