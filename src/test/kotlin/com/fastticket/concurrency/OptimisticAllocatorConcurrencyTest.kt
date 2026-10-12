package com.fastticket.concurrency

import com.fastticket.inventory.domain.AllocationResult
import com.fastticket.inventory.domain.SeatAllocator
import com.fastticket.support.validCpf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@TestPropertySource(properties = ["inventory.allocator.strategy=optimistic", POOL_SIZE_PROPERTY])
class OptimisticAllocatorConcurrencyTest : AbstractAllocatorConcurrencyTest() {

    @Autowired lateinit var allocator: SeatAllocator
    @Autowired lateinit var txManager: PlatformTransactionManager

    @Test
    fun `lost compare-and-set yields conflict`() {
        val tx = TransactionTemplate(txManager)
        val seat = api.seedEvent(1).seatIds[0]
        jdbc.sql("INSERT INTO seat_version (seat_id) VALUES (:id)").param("id", seat).update()
        val bumped = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val winner = pool.submit {
            tx.executeWithoutResult {
                jdbc.sql("UPDATE seat_version SET version = version + 1 WHERE seat_id = :id").param("id", seat).update()
                bumped.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        check(bumped.await(30, TimeUnit.SECONDS))
        val loser = pool.submit<AllocationResult?> { tx.execute { allocator.allocate(UUID.randomUUID(), listOf(seat)) } }
        try {
            Thread.sleep(300)
            release.countDown()

            assertEquals(AllocationResult.Conflict(listOf(seat)), loser.get(30, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            winner.get(30, TimeUnit.SECONDS)
            pool.shutdownNow()
        }
    }

    @Test
    fun `seeding versions is idempotent`() {
        val event = api.seedEvent(3)

        val first = api.hold(event.eventId, event.seatIds, cpf = validCpf(1))
        val second = api.hold(event.eventId, event.seatIds, cpf = validCpf(2))

        assertEquals(listOf(201, 409), listOf(first.response.status, second.response.status))
        assertEquals(3, jdbc.sql("SELECT count(*) FROM seat_version WHERE seat_id IN (:ids)").param("ids", event.seatIds).query(Int::class.java).single())
    }
}
