package com.fastticket.inventory

import com.fastticket.shared.validation.Cpf
import com.fastticket.support.InvariantsExtension
import com.fastticket.support.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestConfiguration(proxyBeanMethods = false)
class ToggleVetoConfig {
    @Bean
    fun toggleVeto(): ToggleVeto = ToggleVeto()
}

class ToggleVeto : HoldExpiryVeto {
    val active = AtomicBoolean(false)
    override fun vetoes(holdId: UUID) = active.get()
}

@Import(TestcontainersConfiguration::class, ToggleVetoConfig::class)
@SpringBootTest(properties = ["jobs.enabled=false"])
@ExtendWith(InvariantsExtension::class)
class HoldExpirationIntegrationTest {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var inventory: InventoryFacade
    @Autowired lateinit var veto: ToggleVeto

    private val cpfs = listOf("52998224725", "11144477735", "39053344705", "86288366757", "12345678909", "01234567890")
        .mapNotNull { Cpf.parseOrNull(it) }

    private class Fixture(val eventId: UUID, val seatIds: List<UUID>)

    private fun fixture(seats: Int): Fixture {
        val eventId = UUID.randomUUID()
        val sectorId = UUID.randomUUID()
        jdbc.sql("INSERT INTO event (id, name, starts_at, sales_open_at, status) VALUES (:id, 'E', now() + interval '30 day', now() - interval '1 hour', 'ON_SALE')")
            .param("id", eventId).update()
        jdbc.sql("INSERT INTO sector (id, event_id, name, kind, capacity, base_price) VALUES (:id, :e, 'S', 'NUMBERED', :c, 10)")
            .param("id", sectorId).param("e", eventId).param("c", seats).update()
        val ids = (1..seats).map { UUID.randomUUID() }
        ids.forEachIndexed { i, id ->
            jdbc.sql("INSERT INTO seat (id, sector_id, row_label, seat_number) VALUES (:id, :s, 'A', :n)")
                .param("id", id).param("s", sectorId).param("n", i + 1).update()
        }
        return Fixture(eventId, ids)
    }

    private fun overdueHold(f: Fixture, cpf: Cpf, seats: List<UUID>): UUID {
        val id = inventory.createHold(cpf, UUID.randomUUID().toString(), f.eventId, seats).hold.id
        jdbc.sql("UPDATE hold SET expires_at = clock_timestamp() - interval '1 minute' WHERE id = :id").param("id", id).update()
        return id
    }

    private fun status(id: UUID) = jdbc.sql("SELECT status FROM hold WHERE id = :id").param("id", id).query(String::class.java).single()

    private fun ticketCount(cpf: Cpf, f: Fixture) =
        jdbc.sql("SELECT ticket_count FROM buyer_event WHERE cpf = :c AND event_id = :e")
            .param("c", cpf.digits).param("e", f.eventId).query(Int::class.java).single()

    private fun allocations(id: UUID, state: String) =
        jdbc.sql("SELECT count(*) FROM seat_allocation WHERE hold_id = :h AND status = :s")
            .param("h", id).param("s", state).query(Int::class.java).single()

    @Test
    fun `sweeper expires due holds releasing seats and quota`() {
        val f = fixture(4)
        val id = overdueHold(f, cpfs[0], f.seatIds.take(3))
        val live = inventory.createHold(cpfs[1], UUID.randomUUID().toString(), f.eventId, f.seatIds.drop(3)).hold.id
        assertTrue(inventory.findDueHoldIds(500).contains(id))
        assertTrue(!inventory.findDueHoldIds(500).contains(live))
        assertEquals(1, inventory.expireDueHolds(listOf(id, live)))
        assertEquals("EXPIRED", status(id))
        assertEquals("ACTIVE", status(live))
        assertEquals(3, allocations(id, "RELEASED"))
        assertEquals(0, ticketCount(cpfs[0], f))
        assertEquals(0, inventory.expireDueHolds(listOf(id)))
        inventory.release(live, cpfs[1])
    }

    @Test
    fun `veto prevents expiration`() {
        val f = fixture(2)
        val id = overdueHold(f, cpfs[0], f.seatIds)
        veto.active.set(true)
        try {
            assertEquals(0, inventory.expireDueHolds(listOf(id)))
            assertEquals("ACTIVE", status(id))
        } finally {
            veto.active.set(false)
        }
        assertEquals(1, inventory.expireDueHolds(listOf(id)))
        assertEquals("EXPIRED", status(id))
    }

    @Test
    fun `concurrent replicas never release twice nor deadlock`() {
        val f = fixture(12)
        val ids = (0 until 6).map { overdueHold(f, cpfs[it], f.seatIds.drop(it * 2).take(2)) }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val results = listOf(ids, ids.reversed()).map { batch ->
            pool.submit<Int> { start.await(); inventory.expireDueHolds(batch) }
        }
        start.countDown()
        val total = results.sumOf { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(total <= ids.size)
        inventory.expireDueHolds(ids)
        ids.forEach { assertEquals("EXPIRED", status(it)) }
        cpfs.forEach { assertEquals(0, ticketCount(it, f)) }
    }
}
