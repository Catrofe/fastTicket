package com.fastticket.catalog.application

import com.fastticket.catalog.EventView
import com.fastticket.catalog.SectorKind
import com.fastticket.catalog.SectorView
import com.fastticket.catalog.domain.Event
import com.fastticket.catalog.domain.EventRepository
import com.fastticket.catalog.domain.MAX_SEATS_PER_EVENT
import com.fastticket.catalog.domain.STATUS_ON_SALE
import com.fastticket.catalog.domain.SeatRow
import com.fastticket.catalog.domain.Sector
import com.fastticket.shared.error.ApiException
import com.fastticket.shared.error.ValidationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class EventNotFoundException : ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "Event not found")

data class NewSector(
    val name: String,
    val kind: SectorKind,
    val basePrice: BigDecimal,
    val capacity: Int?,
    val rows: List<SeatRow>?,
)

data class NewEvent(
    val name: String,
    val startsAt: Instant,
    val salesOpenAt: Instant,
    val sectors: List<NewSector>,
)

@Service
class EventService(private val events: EventRepository) {

    @Transactional
    fun create(command: NewEvent): EventView {
        if (command.salesOpenAt.isAfter(command.startsAt)) {
            throw ValidationException("salesOpenAt must not be after startsAt")
        }
        if (command.sectors.map { it.name }.toSet().size != command.sectors.size) {
            throw ValidationException("Sector names must be unique within the event")
        }
        val sectors = command.sectors.map(::toSector)
        val totalSeats = sectors.filter { it.kind == SectorKind.NUMBERED }.sumOf { it.capacity.toLong() }
        if (totalSeats > MAX_SEATS_PER_EVENT) {
            throw ValidationException("An event can have at most $MAX_SEATS_PER_EVENT numbered seats")
        }
        val event = Event(UUID.randomUUID(), command.name, command.startsAt, command.salesOpenAt, STATUS_ON_SALE, sectors)
        events.save(event)
        return event.toView()
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): EventView = (events.findById(id) ?: throw EventNotFoundException()).toView()

    private fun toSector(s: NewSector): Sector = when (s.kind) {
        SectorKind.GA -> {
            if (s.capacity == null || !s.rows.isNullOrEmpty()) {
                throw ValidationException("GA sector '${s.name}' requires capacity and no rows")
            }
            Sector(UUID.randomUUID(), s.name, s.kind, s.capacity, s.basePrice)
        }
        SectorKind.NUMBERED -> {
            val rows = s.rows
            if (s.capacity != null || rows.isNullOrEmpty()) {
                throw ValidationException("NUMBERED sector '${s.name}' requires rows and no capacity")
            }
            if (rows.map { it.label }.toSet().size != rows.size) {
                throw ValidationException("Row labels must be unique within sector '${s.name}'")
            }
            Sector(UUID.randomUUID(), s.name, s.kind, rows.sumOf { it.seats }, s.basePrice, rows)
        }
    }

    private fun Event.toView() = EventView(
        id, name, startsAt, salesOpenAt, status,
        sectors.map { SectorView(it.id, it.name, it.kind, it.capacity, it.basePrice) },
    )
}
