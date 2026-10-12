package com.fastticket.inventory.domain

import java.util.UUID

sealed interface AllocationResult {
    data object Allocated : AllocationResult
    data class Conflict(val takenSeatIds: List<UUID>) : AllocationResult
}

interface SeatAllocator {
    fun allocate(holdId: UUID, seatIds: Collection<UUID>): AllocationResult
}
