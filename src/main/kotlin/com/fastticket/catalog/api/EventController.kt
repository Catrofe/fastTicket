package com.fastticket.catalog.api

import com.fastticket.catalog.EventView
import com.fastticket.catalog.application.EventService
import com.fastticket.catalog.application.NewEvent
import com.fastticket.catalog.application.NewSector
import com.fastticket.catalog.domain.SeatRow
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
class EventController(private val events: EventService) {

    @PostMapping("/events")
    fun create(@Valid @RequestBody request: CreateEventRequest): ResponseEntity<EventView> {
        val created = events.create(
            NewEvent(
                request.name.trim(),
                request.startsAt,
                request.salesOpenAt,
                request.sectors.map { s ->
                    NewSector(s.name.trim(), s.kind, s.basePrice, s.capacity, s.rows?.map { SeatRow(it.label.trim(), it.seats) })
                },
            ),
        )
        return ResponseEntity.created(URI.create("/events/${created.id}")).body(created)
    }

    @GetMapping("/events/{id}")
    fun get(@PathVariable id: UUID): EventView = events.get(id)
}
