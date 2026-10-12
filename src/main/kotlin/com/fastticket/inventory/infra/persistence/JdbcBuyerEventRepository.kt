package com.fastticket.inventory.infra.persistence

import com.fastticket.inventory.domain.BuyerEventRepository
import com.fastticket.inventory.domain.MAX_TICKETS_PER_BUYER
import com.fastticket.shared.validation.Cpf
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcBuyerEventRepository(private val jdbc: JdbcClient) : BuyerEventRepository {

    override fun increment(cpf: Cpf, eventId: UUID, count: Int): Boolean {
        if (count !in 1..MAX_TICKETS_PER_BUYER) return false
        return jdbc.sql(
            """
            INSERT INTO buyer_event (cpf, event_id, ticket_count) VALUES (:cpf, :eventId, :n)
            ON CONFLICT (cpf, event_id) DO UPDATE SET ticket_count = buyer_event.ticket_count + :n
            WHERE buyer_event.ticket_count + :n <= :max
            RETURNING ticket_count
            """.trimIndent(),
        )
            .param("cpf", cpf.digits).param("eventId", eventId).param("n", count).param("max", MAX_TICKETS_PER_BUYER)
            .query(Int::class.java).list().isNotEmpty()
    }

    override fun decrement(cpf: Cpf, eventId: UUID, count: Int): Boolean =
        jdbc.sql(
            """
            UPDATE buyer_event SET ticket_count = ticket_count - :n
            WHERE cpf = :cpf AND event_id = :eventId AND ticket_count >= :n
            """.trimIndent(),
        )
            .param("cpf", cpf.digits).param("eventId", eventId).param("n", count)
            .update() == 1

    override fun lock(cpf: Cpf, eventId: UUID) {
        jdbc.sql("SELECT 1 FROM buyer_event WHERE cpf = :cpf AND event_id = :eventId FOR UPDATE")
            .param("cpf", cpf.digits).param("eventId", eventId)
            .query(Int::class.java).list()
    }

    override fun tryLock(cpf: Cpf, eventId: UUID): Boolean =
        jdbc.sql("SELECT 1 FROM buyer_event WHERE cpf = :cpf AND event_id = :eventId FOR UPDATE SKIP LOCKED")
            .param("cpf", cpf.digits).param("eventId", eventId)
            .query(Int::class.java).list().isNotEmpty()
}
