package com.fastticket.concurrency

import com.fastticket.support.InvariantsExtension
import com.fastticket.support.RaceOutcome
import com.fastticket.support.TestEvent
import com.fastticket.support.TestcontainersConfiguration
import com.fastticket.support.TicketingApi
import com.fastticket.support.race
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals

const val POOL_SIZE_PROPERTY = "spring.datasource.hikari.maximum-pool-size=60"

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(InvariantsExtension::class)
abstract class AbstractAllocatorConcurrencyTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    protected lateinit var api: TicketingApi

    @BeforeEach
    fun setUpApi() {
        api = TicketingApi(mvc, jdbc)
    }

    private fun count(sql: String, eventId: UUID): Int =
        jdbc.sql(sql).param("e", eventId).query(Int::class.java).single()

    /** Every loser got a clean 409, every winner owns exactly the seats it asked for, and nothing else leaked. */
    protected fun assertAllOrNothing(event: TestEvent, requests: List<List<UUID>>, outcome: RaceOutcome) {
        val results = outcome.results
        val errors = results.filter { !it.won && !it.seatsTaken }
        assertEquals(emptyList(), errors.map { it.status to it.body }, "only 201 or 409 SEATS_TAKEN are acceptable")

        val winnerSeats = results.indices.filter { results[it].won }.sumOf { requests[it].size }
        assertEquals(results.count { it.won }, count("SELECT count(*) FROM hold WHERE event_id = :e", event.eventId))
        assertEquals(
            winnerSeats,
            count(
                "SELECT count(*) FROM seat_allocation a JOIN seat s ON s.id = a.seat_id JOIN sector c ON c.id = s.sector_id WHERE c.event_id = :e AND a.status = 'HELD'",
                event.eventId,
            ),
        )
        assertEquals(winnerSeats, count("SELECT coalesce(sum(ticket_count), 0) FROM buyer_event WHERE event_id = :e", event.eventId))
        assertEquals(
            0,
            count(
                """
                SELECT count(*) FROM hold h WHERE h.event_id = :e AND
                  (SELECT count(*) FROM seat_allocation a WHERE a.hold_id = h.id AND a.status = 'HELD')
                    <> (SELECT count(*) FROM hold_item i WHERE i.hold_id = h.id)
                """.trimIndent(),
                event.eventId,
            ),
        )
    }

    @Test
    fun `100 threads on one seat yield exactly one winner`() {
        val event = api.seedEvent(1)
        val requests = List(100) { listOf(event.seatIds[0]) }

        val outcome = api.race(event.eventId, requests)

        assertEquals(1, outcome.results.count { it.won })
        assertEquals(99, outcome.results.count { it.seatsTaken })
        assertAllOrNothing(event, requests, outcome)
    }

    @Test
    fun `overlapping multi-seat requests in opposite order yield one complete hold and no partial one`() {
        repeat(15) {
            val event = api.seedEvent(4)
            val requests = listOf(event.seatIds, event.seatIds.reversed())

            val outcome = api.race(event.eventId, requests)

            assertEquals(1, outcome.results.count { it.won })
            assertAllOrNothing(event, requests, outcome)
        }
    }

    @Test
    fun `partially overlapping requests leave the loser with no allocation`() {
        repeat(15) {
            val event = api.seedEvent(5)
            val s = event.seatIds
            val requests = listOf(s.subList(0, 3), s.subList(2, 5))

            val outcome = api.race(event.eventId, requests)

            assertEquals(1, outcome.results.count { it.won })
            assertAllOrNothing(event, requests, outcome)
        }
    }

    @Test
    fun `100 overlapping multi-seat requests each fully succeed or leave nothing`() {
        repeat(3) { round ->
            val event = api.seedEvent(6)
            val random = Random(round)
            val requests = List(100) { event.seatIds.shuffled(random).take(random.nextInt(2, 5)) }

            val outcome = api.race(event.eventId, requests)

            assertAllOrNothing(event, requests, outcome)
            assertEquals(true, outcome.results.any { it.won })
        }
    }

    @Test
    fun `disjoint requests all succeed`() {
        val event = api.seedEvent(100)
        val requests = event.seatIds.map { listOf(it) }

        val outcome = api.race(event.eventId, requests)

        assertEquals(100, outcome.results.count { it.won })
        assertAllOrNothing(event, requests, outcome)
    }
}
