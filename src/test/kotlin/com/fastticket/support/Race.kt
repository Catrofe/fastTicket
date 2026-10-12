package com.fastticket.support

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RaceResult(val status: Int, val body: String, val nanos: Long) {
    val won get() = status == 201
    val seatsTaken get() = status == 409 && body.contains("SEATS_TAKEN")
}

class RaceOutcome(val results: List<RaceResult>, val wallNanos: Long)

private val cpfSeed = AtomicInteger(1000)

/** Fires one hold request per element of [requests] at the same instant, each from its own buyer. */
fun TicketingApi.race(eventId: UUID, requests: List<List<UUID>>): RaceOutcome {
    val pool = Executors.newFixedThreadPool(requests.size)
    val ready = CountDownLatch(requests.size)
    val start = CountDownLatch(1)
    try {
        val futures = requests.map { seats ->
            val cpf = validCpf(cpfSeed.incrementAndGet())
            pool.submit<RaceResult> {
                ready.countDown()
                check(start.await(60, TimeUnit.SECONDS))
                val t0 = System.nanoTime()
                val r = hold(eventId, seats, cpf = cpf)
                RaceResult(r.response.status, r.response.contentAsString, System.nanoTime() - t0)
            }
        }
        check(ready.await(60, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        start.countDown()
        val results = futures.map { it.get(120, TimeUnit.SECONDS) }
        return RaceOutcome(results, System.nanoTime() - t0)
    } finally {
        pool.shutdownNow()
    }
}
