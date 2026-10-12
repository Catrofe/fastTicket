package com.fastticket.catalog.application

import com.fastticket.catalog.CatalogFacade
import com.fastticket.catalog.EventView
import com.fastticket.catalog.SeatInfo
import com.fastticket.catalog.domain.EventRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class CatalogFacadeImpl(
    private val eventService: EventService,
    private val events: EventRepository,
) : CatalogFacade {

    override fun getEvent(id: UUID): EventView = eventService.get(id)

    override fun findSeats(seatIds: Collection<UUID>): List<SeatInfo> =
        if (seatIds.isEmpty()) emptyList() else events.findSeats(seatIds.toSet())
}
