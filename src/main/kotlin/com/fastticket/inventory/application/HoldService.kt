package com.fastticket.inventory.application

import com.fastticket.catalog.CatalogFacade
import com.fastticket.catalog.SectorKind
import com.fastticket.inventory.HoldCreated
import com.fastticket.inventory.HoldView
import com.fastticket.inventory.domain.AllocationResult
import com.fastticket.inventory.domain.BuyerEventRepository
import com.fastticket.inventory.domain.Hold
import com.fastticket.inventory.domain.HoldItem
import com.fastticket.inventory.domain.HoldRepository
import com.fastticket.inventory.domain.HoldStatus
import com.fastticket.inventory.domain.MAX_TICKETS_PER_BUYER
import com.fastticket.inventory.domain.SeatAllocator
import com.fastticket.shared.error.ApiException
import com.fastticket.shared.error.ErrorCodes
import com.fastticket.shared.error.ValidationException
import com.fastticket.shared.idempotency.IdempotencyClaim
import com.fastticket.shared.idempotency.IdempotencyStore
import com.fastticket.shared.idempotency.RequestHasher
import com.fastticket.shared.time.DbClock
import com.fastticket.shared.validation.Cpf
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.UUID

private const val SCOPE = "POST /holds"
private const val STATUS_ON_SALE = "ON_SALE"

class HoldNotFoundException : ApiException(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "Hold not found")

@Service
class HoldService(
    private val holds: HoldRepository,
    private val buyers: BuyerEventRepository,
    private val allocator: SeatAllocator,
    private val releaser: HoldReleaser,
    private val catalog: CatalogFacade,
    private val idempotency: IdempotencyStore,
    private val hasher: RequestHasher,
    private val clock: DbClock,
    private val json: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(cpf: Cpf, key: String, eventId: UUID, seatIds: List<UUID>): HoldCreated {
        val ids = seatIds.distinct().sorted()
        if (ids.size != seatIds.size || ids.size !in 1..MAX_TICKETS_PER_BUYER) {
            throw ValidationException("seat_ids must contain 1 to $MAX_TICKETS_PER_BUYER distinct seats")
        }
        val claim = idempotency.claim(key, SCOPE, hasher.hash(cpf, "$eventId:${ids.joinToString(",")}"))
        when (claim) {
            IdempotencyClaim.Acquired -> Unit
            IdempotencyClaim.InProgress ->
                throw ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS", "A request with this key is in progress")
            is IdempotencyClaim.Replay ->
                return HoldCreated(json.readValue(checkNotNull(claim.responseBody), HoldView::class.java), true)
        }

        val event = catalog.getEvent(eventId)
        if (event.status != STATUS_ON_SALE || event.salesOpenAt.isAfter(clock.now())) {
            throw ApiException(HttpStatus.CONFLICT, "EVENT_NOT_ON_SALE", "Event is not open for sales")
        }

        var reserved = buyers.increment(cpf, eventId, ids.size)
        val previous = holds.findActive(cpf, eventId)
        if (previous != null) {
            val due = previous.expiresAt?.isBefore(clock.now()) ?: true
            if (!due) throw ApiException(HttpStatus.CONFLICT, ErrorCodes.ACTIVE_HOLD_EXISTS, "Buyer already has an active hold for this event")
            releaser.release(previous.id, HoldStatus.EXPIRED)
            if (!reserved) reserved = buyers.increment(cpf, eventId, ids.size)
        }
        if (!reserved) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.TICKET_LIMIT_EXCEEDED, "Limit of $MAX_TICKETS_PER_BUYER tickets per event exceeded")
        }

        val seats = catalog.findSeats(ids).sortedBy { it.seatId }
        if (seats.size != ids.size || seats.any { it.eventId != eventId || it.sectorKind != SectorKind.NUMBERED }) {
            throw ValidationException("All seats must be numbered seats of the given event")
        }
        val items = seats.map { HoldItem(it.seatId, it.sectorId, it.basePrice) }
        val total = items.fold(BigDecimal.ZERO) { acc, i -> acc + i.unitPrice }
        val hold = holds.insert(Hold(UUID.randomUUID(), eventId, cpf, HoldStatus.ACTIVE, null, total, items))

        val allocation = allocator.allocate(hold.id, ids)
        if (allocation is AllocationResult.Conflict) {
            throw ApiException(
                HttpStatus.CONFLICT, ErrorCodes.SEATS_TAKEN, "Some seats are no longer available",
                mapOf("seat_ids" to allocation.takenSeatIds),
            )
        }

        val view = hold.toView()
        idempotency.complete(key, SCOPE, HttpStatus.CREATED.value(), json.writeValueAsString(view), hold.id)
        log.info("hold created id={} buyer={} seats={}", hold.id, cpf, ids.size)
        return HoldCreated(view, false)
    }

    @Transactional(readOnly = true)
    fun get(holdId: UUID, cpf: Cpf): HoldView = owned(holdId, cpf).toView()

    @Transactional
    fun release(holdId: UUID, cpf: Cpf): HoldView {
        val hold = owned(holdId, cpf)
        buyers.lock(cpf, hold.eventId)
        releaser.release(holdId, HoldStatus.RELEASED)
        val current = checkNotNull(holds.findById(holdId))
        if (current.status == HoldStatus.CONFIRMED || current.status == HoldStatus.CANCELLED) {
            throw ApiException(HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE", "Hold can no longer be released")
        }
        log.info("hold released id={} buyer={} status={}", holdId, cpf, current.status)
        return current.toView()
    }

    private fun owned(holdId: UUID, cpf: Cpf): Hold {
        val hold = holds.findById(holdId) ?: throw HoldNotFoundException()
        if (hold.buyerCpf != cpf) throw HoldNotFoundException()
        return hold
    }
}
