package com.fastticket.inventory

import com.fastticket.support.InvariantsExtension
import com.fastticket.support.TestcontainersConfiguration
import com.fastticket.support.TicketingApi
import com.fastticket.support.validCpf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(InvariantsExtension::class)
class ConcurrencySmokeIntegrationTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    private lateinit var api: TicketingApi
    private val cpfSeed = AtomicInteger(1000)

    @BeforeEach
    fun setUp() {
        api = TicketingApi(mvc, jdbc)
    }

    private fun race(eventId: UUID, requests: List<List<UUID>>): List<Pair<Int, String>> {
        val pool = Executors.newFixedThreadPool(requests.size)
        val ready = CountDownLatch(requests.size)
        val start = CountDownLatch(1)
        try {
            val futures = requests.map { seats ->
                val cpf = validCpf(cpfSeed.incrementAndGet())
                pool.submit<Pair<Int, String>> {
                    ready.countDown()
                    check(start.await(30, TimeUnit.SECONDS))
                    val r = api.hold(eventId, seats, cpf = cpf)
                    r.response.status to r.response.contentAsString
                }
            }
            check(ready.await(30, TimeUnit.SECONDS))
            start.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun allocationsOf(holdId: String) =
        jdbc.sql("SELECT count(*) FROM seat_allocation WHERE hold_id = :h AND status = 'HELD'").param("h", UUID.fromString(holdId)).query(Int::class.java).single()

    @ParameterizedTest(name = "{0} threads on the same seat")
    @ValueSource(ints = [2, 5, 10])
    fun `exactly one winner when threads compete for the same seat`(threads: Int) {
        val event = api.seedEvent(1)

        val results = race(event.eventId, List(threads) { listOf(event.seatIds[0]) })

        assertEquals(1, results.count { it.first == 201 })
        assertEquals(threads - 1, results.count { it.first == 409 && it.second.contains("SEATS_TAKEN") })
        assertEquals(1, jdbc.sql("SELECT count(*) FROM seat_allocation WHERE seat_id = :s AND status = 'HELD'").param("s", event.seatIds[0]).query(Int::class.java).single())
        assertEquals(1, jdbc.sql("SELECT count(*) FROM buyer_event WHERE event_id = :e").param("e", event.eventId).query(Int::class.java).single())
    }

    @Test
    fun `overlapping multi-seat requests in opposite order yield one complete hold and no partial one`() {
        repeat(15) {
            val event = api.seedEvent(4)
            val s = event.seatIds

            val results = race(event.eventId, listOf(s, s.reversed()))

            assertEquals(listOf(201, 409), results.map { it.first }.sorted())
            val winner = api.json.readTree(results.first { it.first == 201 }.second).get("hold_id").asString()
            assertEquals(4, allocationsOf(winner))
            assertEquals(4, jdbc.sql("SELECT count(*) FROM seat_allocation sa JOIN seat st ON st.id = sa.seat_id JOIN sector sc ON sc.id = st.sector_id WHERE sc.event_id = :e").param("e", event.eventId).query(Int::class.java).single())
        }
    }

    @Test
    fun `partially overlapping requests leave the loser with no allocation`() {
        repeat(15) {
            val event = api.seedEvent(5)
            val s = event.seatIds

            val results = race(event.eventId, listOf(s.subList(0, 3), s.subList(2, 5)))

            assertEquals(listOf(201, 409), results.map { it.first }.sorted())
            val winner = api.json.readTree(results.first { it.first == 201 }.second).get("hold_id").asString()
            assertEquals(3, allocationsOf(winner))
            assertEquals(3, jdbc.sql("SELECT count(*) FROM seat_allocation sa JOIN seat st ON st.id = sa.seat_id JOIN sector sc ON sc.id = st.sector_id WHERE sc.event_id = :e").param("e", event.eventId).query(Int::class.java).single())
            assertEquals(1, jdbc.sql("SELECT count(*) FROM hold WHERE event_id = :e").param("e", event.eventId).query(Int::class.java).single())
        }
    }
}
