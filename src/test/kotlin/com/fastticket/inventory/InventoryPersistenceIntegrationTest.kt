package com.fastticket.inventory

import com.fastticket.inventory.domain.AllocationResult
import com.fastticket.inventory.domain.BuyerEventRepository
import com.fastticket.inventory.domain.Hold
import com.fastticket.inventory.domain.HoldItem
import com.fastticket.inventory.domain.HoldRepository
import com.fastticket.inventory.domain.HoldStatus
import com.fastticket.inventory.domain.SeatAllocationRepository
import com.fastticket.inventory.domain.SeatAllocator
import com.fastticket.shared.validation.Cpf
import com.fastticket.support.InvariantsExtension
import com.fastticket.support.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@ExtendWith(InvariantsExtension::class)
class InventoryPersistenceIntegrationTest {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var allocator: SeatAllocator
    @Autowired lateinit var holds: HoldRepository
    @Autowired lateinit var buyers: BuyerEventRepository
    @Autowired lateinit var allocations: SeatAllocationRepository

    private val cpfA = Cpf.parseOrNull("52998224725")!!
    private val cpfB = Cpf.parseOrNull("11144477735")!!

    private class Fixture(val eventId: UUID, val sectorId: UUID, val seatIds: List<UUID>)

    private fun fixture(seats: Int = 6): Fixture {
        val eventId = UUID.randomUUID()
        val sectorId = UUID.randomUUID()
        jdbc.sql("INSERT INTO event (id, name, starts_at, sales_open_at, status) VALUES (:id, 'E', now() + interval '30 day', now(), 'ON_SALE')")
            .param("id", eventId).update()
        jdbc.sql("INSERT INTO sector (id, event_id, name, kind, capacity, base_price) VALUES (:id, :e, 'S', 'NUMBERED', :c, 10)")
            .param("id", sectorId).param("e", eventId).param("c", seats).update()
        val ids = (1..seats).map { UUID.randomUUID() }
        ids.forEachIndexed { i, id ->
            jdbc.sql("INSERT INTO seat (id, sector_id, row_label, seat_number) VALUES (:id, :s, 'A', :n)")
                .param("id", id).param("s", sectorId).param("n", i + 1).update()
        }
        return Fixture(eventId, sectorId, ids)
    }

    private fun newHold(f: Fixture, cpf: Cpf, seatIds: List<UUID>): Hold {
        assertTrue(buyers.increment(cpf, f.eventId, seatIds.size))
        return holds.insert(
            Hold(
                UUID.randomUUID(), f.eventId, cpf, HoldStatus.ACTIVE, null, BigDecimal.TEN.multiply(BigDecimal(seatIds.size)),
                seatIds.map { HoldItem(it, f.sectorId, BigDecimal("10.00")) },
            ),
        )
    }

    private fun cleanUp(vararg h: Hold) = h.forEach {
        allocations.release(it.id)
        holds.transition(it.id, HoldStatus.ACTIVE, HoldStatus.RELEASED)
        buyers.decrement(it.buyerCpf, it.eventId, it.items.size)
    }

    @Test
    fun `second hold on a taken seat gets conflict and rollback leaves nothing behind`() {
        val f = fixture()
        val first = newHold(f, cpfA, listOf(f.seatIds[0], f.seatIds[1]))
        assertEquals(AllocationResult.Allocated, allocator.allocate(first.id, first.items.map { it.seatId }))
        val second = newHold(f, cpfB, listOf(f.seatIds[1], f.seatIds[2]))
        val result = tx.execute { status ->
            val r = allocator.allocate(second.id, second.items.map { it.seatId })
            if (r is AllocationResult.Conflict) status.setRollbackOnly()
            r
        }
        assertEquals(AllocationResult.Conflict(listOf(f.seatIds[1])), result)
        assertEquals(0, jdbc.sql("SELECT count(*) FROM seat_allocation WHERE hold_id = :h").param("h", second.id).query(Int::class.java).single())
        cleanUp(first, second)
    }

    @Test
    fun `seats are inserted in id order regardless of request order`() {
        val f = fixture()
        val hold = newHold(f, cpfA, f.seatIds)
        allocator.allocate(hold.id, f.seatIds.shuffled())
        val inserted = jdbc.sql("SELECT seat_id FROM seat_allocation WHERE hold_id = :h ORDER BY ctid")
            .param("h", hold.id).query(UUID::class.java).list()
        assertEquals(f.seatIds.sorted(), inserted)
        cleanUp(hold)
    }

    @Test
    fun `concurrent allocations in opposite order do not deadlock and exactly one wins`() {
        val f = fixture()
        val h1 = newHold(f, cpfA, f.seatIds.take(3))
        val h2 = newHold(f, cpfB, f.seatIds.take(3))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val results = listOf(h1 to f.seatIds.take(3), h2 to f.seatIds.take(3).reversed()).map { (hold, seats) ->
            pool.submit<AllocationResult?> {
                start.await()
                tx.execute { status ->
                    val r = allocator.allocate(hold.id, seats)
                    if (r is AllocationResult.Conflict) status.setRollbackOnly()
                    r
                }
            }
        }
        start.countDown()
        val outcomes = results.map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, outcomes.count { it == AllocationResult.Allocated })
        assertEquals(1, outcomes.count { it is AllocationResult.Conflict })
        cleanUp(h1, h2)
    }

    @Test
    fun `ticket limit of six is enforced and decrement frees quota`() {
        val f = fixture()
        assertTrue(buyers.increment(cpfA, f.eventId, 4))
        assertFalse(buyers.increment(cpfA, f.eventId, 3))
        assertTrue(buyers.increment(cpfA, f.eventId, 2))
        assertFalse(buyers.increment(cpfA, f.eventId, 1))
        assertFalse(buyers.increment(cpfA, f.eventId, 7))
        assertTrue(buyers.decrement(cpfA, f.eventId, 6))
        assertFalse(buyers.decrement(cpfA, f.eventId, 1))
        assertTrue(buyers.increment(cpfA, f.eventId, 1))
        buyers.decrement(cpfA, f.eventId, 1)
    }

    @Test
    fun `concurrent upserts for the same cpf never exceed six`() {
        val f = fixture()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(10)
        val futures = (1..10).map {
            pool.submit<Boolean> { start.await(); buyers.increment(cpfA, f.eventId, 1) }
        }
        start.countDown()
        val granted = futures.count { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(6, granted)
        assertEquals(6, jdbc.sql("SELECT ticket_count FROM buyer_event WHERE cpf = :c AND event_id = :e")
            .param("c", cpfA.digits).param("e", f.eventId).query(Int::class.java).single())
        buyers.decrement(cpfA, f.eventId, 6)
    }

    @Test
    fun `hold expires_at uses ttl and round trips with items`() {
        val f = fixture()
        val before = Instant.now()
        val hold = newHold(f, cpfA, listOf(f.seatIds[0]))
        val expiresAt = assertNotNull(hold.expiresAt)
        assertTrue(Duration.between(before, expiresAt) in Duration.ofMinutes(9)..Duration.ofMinutes(11))
        val loaded = assertNotNull(holds.findById(hold.id))
        assertEquals(hold.items, loaded.items)
        assertEquals(hold.id, holds.findActive(cpfA, f.eventId)?.id)
        assertEquals(hold.id, tx.execute { holds.lockById(hold.id) }?.id)
        assertFalse(holds.findDueIds(10).contains(hold.id))
        cleanUp(hold)
    }

    @Test
    fun `release and confirm move only HELD allocations`() {
        val f = fixture()
        val h1 = newHold(f, cpfA, f.seatIds.take(2))
        val h2 = newHold(f, cpfB, f.seatIds.drop(2).take(1))
        allocator.allocate(h1.id, h1.items.map { it.seatId })
        allocator.allocate(h2.id, h2.items.map { it.seatId })
        assertEquals(2, allocations.confirm(h1.id))
        assertEquals(0, allocations.release(h1.id))
        assertEquals(1, allocations.release(h2.id))
        assertTrue(holds.transition(h1.id, HoldStatus.ACTIVE, HoldStatus.CONFIRMED))
        assertFalse(holds.transition(h1.id, HoldStatus.ACTIVE, HoldStatus.EXPIRED))
        cleanUp(h2)
        assertEquals(AllocationResult.Allocated, newHold(f, cpfB, f.seatIds.drop(2).take(1)).let {
            allocator.allocate(it.id, it.items.map { i -> i.seatId }).also { _ -> cleanUp(it) }
        })
    }
}
