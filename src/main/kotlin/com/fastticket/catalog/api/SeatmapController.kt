package com.fastticket.catalog.api

import com.fastticket.catalog.SectorKind
import com.fastticket.catalog.application.SeatmapService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class SeatmapController(private val seatmaps: SeatmapService) {

    @GetMapping("/events/{id}/seatmap")
    fun seatmap(@PathVariable id: UUID, @RequestParam(required = false) sector: UUID?): SeatmapResponse =
        SeatmapResponse(
            id,
            seatmaps.seatmap(id, sector).map { s ->
                SectorMapDto(
                    s.sectorId, s.name, s.kind, s.capacity,
                    if (s.kind == SectorKind.GA) null
                    else s.rows.map { r -> RowDto(r.label, r.seats.map { SeatDto(it.id, it.number, it.status) }) },
                )
            },
        )
}
