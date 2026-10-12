package com.fastticket.inventory.infra.persistence

import com.fastticket.inventory.domain.SeatAllocationRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcSeatAllocationRepository(private val jdbc: JdbcClient) : SeatAllocationRepository {

    override fun release(holdId: UUID): Int = move(holdId, "RELEASED")

    override fun confirm(holdId: UUID): Int = move(holdId, "SOLD")

    private fun move(holdId: UUID, to: String): Int =
        jdbc.sql("UPDATE seat_allocation SET status = :to, updated_at = now() WHERE hold_id = :holdId AND status = 'HELD'")
            .param("to", to).param("holdId", holdId)
            .update()
}
