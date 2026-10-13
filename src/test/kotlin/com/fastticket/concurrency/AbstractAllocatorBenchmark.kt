package com.fastticket.concurrency

import com.fastticket.support.InvariantsExtension
import com.fastticket.support.RaceOutcome
import com.fastticket.support.TestcontainersConfiguration
import com.fastticket.support.TicketingApi
import com.fastticket.support.race
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.util.UUID
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.assertEquals

private const val THREADS = 100
private const val WARMUP_ROUNDS = 1
private const val MEASURED_ROUNDS = 5

private class Round(val p50: Double, val p95: Double, val p99: Double, val conflictPct: Double, val throughput: Double, val errors: Int, val winners: Int)

/** Nearest-rank percentile: the smallest value with at least p of the samples at or below it. */
private fun percentile(sortedMs: List<Double>, p: Double): Double =
    sortedMs[(ceil(p * sortedMs.size).toInt() - 1).coerceIn(0, sortedMs.size - 1)]

private fun round(outcome: RaceOutcome): Round {
    val ms = outcome.results.map { it.nanos / 1_000_000.0 }.sorted()
    val winners = outcome.results.count { it.won }
    return Round(
        p50 = percentile(ms, 0.50), p95 = percentile(ms, 0.95), p99 = percentile(ms, 0.99),
        conflictPct = 100.0 * outcome.results.count { it.seatsTaken } / outcome.results.size,
        throughput = winners / (outcome.wallNanos / 1_000_000_000.0),
        errors = outcome.results.count { !it.won && !it.seatsTaken },
        winners = winners,
    )
}

private fun List<Double>.median(): Double = sorted()[size / 2]

@Tag("benchmark")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(InvariantsExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = [POOL_SIZE_PROPERTY])
abstract class AbstractAllocatorBenchmark(private val strategy: String) {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    private lateinit var api: TicketingApi
    private val report = linkedMapOf<String, Map<String, Any>>()

    @BeforeEach
    fun setUpApi() {
        api = TicketingApi(mvc, jdbc)
    }

    private fun scenario(name: String, seats: Int, requests: (List<UUID>, Random) -> List<List<UUID>>) {
        val rounds = (1..WARMUP_ROUNDS + MEASURED_ROUNDS).map { n ->
            val event = api.seedEvent(seats)
            val outcome = api.race(event.eventId, requests(event.seatIds, Random(n)))
            round(outcome)
        }.drop(WARMUP_ROUNDS)

        assertEquals(0, rounds.sumOf { it.errors }, "$strategy/$name: only 201 or 409 SEATS_TAKEN are acceptable")
        assertEquals(true, rounds.all { it.winners >= 1 })
        report[name] = mapOf(
            "threads" to THREADS,
            "rounds" to rounds.size,
            "p50_ms" to rounds.map { it.p50 }.median(),
            "p95_ms" to rounds.map { it.p95 }.median(),
            "p99_ms" to rounds.map { it.p99 }.median(),
            "conflict_pct" to rounds.map { it.conflictPct }.median(),
            "holds_per_sec" to rounds.map { it.throughput }.median(),
            "errors" to rounds.sumOf { it.errors },
        )
    }

    private fun subset(seats: List<UUID>, random: Random) = seats.shuffled(random).take(random.nextInt(1, 5))

    @Test
    fun `A no contention`() = scenario("A_no_contention", THREADS) { seats, _ -> seats.map { listOf(it) } }

    @Test
    fun `B hot seat`() = scenario("B_hot_seat", 1) { seats, _ -> List(THREADS) { listOf(seats[0]) } }

    @Test
    fun `C overlapping multi-seat`() = scenario("C_overlapping_multi_seat", 6) { seats, r ->
        List(THREADS) { seats.shuffled(r).take(r.nextInt(2, 5)) }
    }

    @Test
    fun `D sweep 20 seats`() = scenario("D_sweep_20_seats", 20) { seats, r -> List(THREADS) { subset(seats, r) } }

    @Test
    fun `D sweep 100 seats`() = scenario("D_sweep_100_seats", 100) { seats, r -> List(THREADS) { subset(seats, r) } }

    @Test
    fun `D sweep 1000 seats`() = scenario("D_sweep_1000_seats", 1000) { seats, r -> List(THREADS) { subset(seats, r) } }

    @AfterAll
    fun writeReport() {
        val dir = File("build/reports/allocator-benchmark").apply { mkdirs() }
        File(dir, "$strategy.json").writeText(JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report))
    }
}
