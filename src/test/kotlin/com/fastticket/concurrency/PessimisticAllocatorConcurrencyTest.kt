package com.fastticket.concurrency

import com.fastticket.inventory.domain.AllocationResult
import com.fastticket.inventory.domain.SeatAllocator
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

@TestPropertySource(properties = ["inventory.allocator.strategy=pessimistic", POOL_SIZE_PROPERTY])
class PessimisticAllocatorConcurrencyTest : AbstractAllocatorConcurrencyTest() {

    @Autowired lateinit var allocator: SeatAllocator
    @Autowired lateinit var txManager: PlatformTransactionManager

    @Test
    fun `lock failure reports only the contended seats and leaves the transaction usable`() {
        val tx = TransactionTemplate(txManager)
        val (seat, free) = api.seedEvent(2).seatIds
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val holder = pool.submit {
            tx.executeWithoutResult {
                jdbc.sql("SELECT id FROM seat WHERE id = :id FOR NO KEY UPDATE").param("id", seat).query(UUID::class.java).list()
                locked.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        check(locked.await(30, TimeUnit.SECONDS))
        try {
            val (result, probe) = checkNotNull(
                tx.execute {
                    allocator.allocate(UUID.randomUUID(), listOf(seat, free)) to jdbc.sql("SELECT 1").query(Int::class.java).single()
                },
            )

            assertEquals(AllocationResult.Conflict(listOf(seat)), result)
            assertEquals(1, probe)
        } finally {
            release.countDown()
            holder.get(30, TimeUnit.SECONDS)
            pool.shutdownNow()
        }
    }
}
