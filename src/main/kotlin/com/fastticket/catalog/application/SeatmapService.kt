package com.fastticket.catalog.application

import com.fastticket.catalog.domain.SectorMap
import com.fastticket.catalog.domain.SeatmapQuery
import com.fastticket.shared.error.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

class SectorNotFoundException : ApiException(HttpStatus.NOT_FOUND, "SECTOR_NOT_FOUND", "Sector not found")

@Service
class SeatmapService(private val query: SeatmapQuery) {

    @Transactional(readOnly = true)
    fun seatmap(eventId: UUID, sectorId: UUID?): List<SectorMap> {
        val sectors = query.load(eventId, sectorId) ?: throw EventNotFoundException()
        if (sectorId != null && sectors.isEmpty()) throw SectorNotFoundException()
        return sectors
    }
}
