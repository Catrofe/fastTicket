package com.fastticket.inventory

import com.fastticket.support.InvariantsExtension
import com.fastticket.support.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(InvariantsExtension::class)
class HoldApiIntegrationTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    private val json = JsonMapper()
    private val cpfA = "52998224725"
    private val cpfB = "11144477735"

    private class Fixture(val eventId: UUID, val seatIds: List<UUID>)

    private fun fixture(seats: Int = 8, salesOpen: String = "now() - interval '1 hour'", status: String = "ON_SALE"): Fixture {
        val eventId = UUID.randomUUID()
        val sectorId = UUID.randomUUID()
        jdbc.sql("INSERT INTO event (id, name, starts_at, sales_open_at, status) VALUES (:id, 'E', now() + interval '30 day', $salesOpen, :st)")
            .param("id", eventId).param("st", status).update()
        jdbc.sql("INSERT INTO sector (id, event_id, name, kind, capacity, base_price) VALUES (:id, :e, 'S', 'NUMBERED', :c, 10.50)")
            .param("id", sectorId).param("e", eventId).param("c", seats).update()
        val ids = (1..seats).map { UUID.randomUUID() }
        ids.forEachIndexed { i, id ->
            jdbc.sql("INSERT INTO seat (id, sector_id, row_label, seat_number) VALUES (:id, :s, 'A', :n)")
                .param("id", id).param("s", sectorId).param("n", i + 1).update()
        }
        return Fixture(eventId, ids)
    }

    private fun hold(f: Fixture, seats: List<UUID>, key: String = UUID.randomUUID().toString(), cpf: String = cpfA): MvcResult =
        mvc.perform(
            post("/holds").header("Idempotency-Key", key).header("X-Buyer-Cpf", cpf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"event_id":"${f.eventId}","seat_ids":[${seats.joinToString(",") { "\"$it\"" }}]}"""),
        ).andReturn()

    private fun holdId(r: MvcResult) = json.readTree(r.response.contentAsString).get("hold_id").asString()

    private fun count(sql: String, vararg p: Pair<String, Any>) =
        jdbc.sql(sql).apply { p.forEach { param(it.first, it.second) } }.query(Int::class.java).single()

    @Test
    fun `creates hold with frozen price and allocations`() {
        val f = fixture()
        val r = hold(f, f.seatIds.take(2))
        assertEquals(201, r.response.status)
        val body = json.readTree(r.response.contentAsString)
        assertEquals(0, java.math.BigDecimal("21.00").compareTo(body.get("total_amount").decimalValue()))
        assertEquals(2, body.get("items").size())
        val id = UUID.fromString(body.get("hold_id").asString())
        assertEquals(2, count("SELECT count(*) FROM seat_allocation WHERE hold_id = :h AND status = 'HELD'", "h" to id))
        assertEquals(2, count("SELECT ticket_count FROM buyer_event WHERE cpf = :c AND event_id = :e", "c" to cpfA, "e" to f.eventId))
        mvc.perform(get("/holds/$id").header("X-Buyer-Cpf", cpfA)).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
    }

    @Test
    fun `taken seat returns 409 with ids and leaves no partial effect`() {
        val f = fixture()
        assertEquals(201, hold(f, listOf(f.seatIds[0]), cpf = cpfB).response.status)
        val key = UUID.randomUUID().toString()
        mvc.perform(
            post("/holds").header("Idempotency-Key", key).header("X-Buyer-Cpf", cpfA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"event_id":"${f.eventId}","seat_ids":["${f.seatIds[1]}","${f.seatIds[0]}"]}"""),
        ).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("SEATS_TAKEN"))
            .andExpect(jsonPath("$.seat_ids[0]").value(f.seatIds[0].toString()))
        assertEquals(0, count("SELECT count(*) FROM hold WHERE buyer_cpf = :c AND event_id = :e", "c" to cpfA, "e" to f.eventId))
        assertEquals(0, count("SELECT count(*) FROM buyer_event WHERE cpf = :c AND event_id = :e", "c" to cpfA, "e" to f.eventId))
        assertEquals(0, count("SELECT count(*) FROM idempotency_key WHERE key = :k", "k" to key))
        assertEquals(201, hold(f, listOf(f.seatIds[1]), key = key).response.status)
    }

    @Test
    fun `release frees quota so buyer can hold again, then active hold blocks`() {
        val f = fixture(8)
        val a = hold(f, f.seatIds.take(3))
        assertEquals(201, a.response.status)
        mvc.perform(delete("/holds/${holdId(a)}").header("X-Buyer-Cpf", cpfA)).andExpect(status().isOk)
        assertEquals(201, hold(f, f.seatIds.subList(3, 6)).response.status)
        val r = hold(f, f.seatIds.subList(6, 8))
        assertEquals(409, r.response.status)
    }

    @Test
    fun `seven seats rejected and limit exceeded returns 422`() {
        val f = fixture(8)
        assertEquals(400, hold(f, f.seatIds.take(7)).response.status)
        assertEquals(201, hold(f, f.seatIds.take(4)).response.status)
        jdbc.sql("UPDATE hold SET status = 'CONFIRMED' WHERE buyer_cpf = :c AND event_id = :e").param("c", cpfA).param("e", f.eventId).update()
        jdbc.sql("UPDATE seat_allocation SET status = 'SOLD' WHERE hold_id IN (SELECT id FROM hold WHERE event_id = :e)").param("e", f.eventId).update()
        mvc.perform(
            post("/holds").header("Idempotency-Key", "k-${UUID.randomUUID()}").header("X-Buyer-Cpf", cpfA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"event_id":"${f.eventId}","seat_ids":["${f.seatIds[4]}","${f.seatIds[5]}","${f.seatIds[6]}"]}"""),
        ).andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("TICKET_LIMIT_EXCEEDED"))
        assertEquals(4, count("SELECT ticket_count FROM buyer_event WHERE cpf = :c AND event_id = :e", "c" to cpfA, "e" to f.eventId))
    }

    @Test
    fun `existing valid hold returns 409 ACTIVE_HOLD_EXISTS`() {
        val f = fixture()
        assertEquals(201, hold(f, listOf(f.seatIds[0])).response.status)
        mvc.perform(
            post("/holds").header("Idempotency-Key", "k-${UUID.randomUUID()}").header("X-Buyer-Cpf", cpfA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"event_id":"${f.eventId}","seat_ids":["${f.seatIds[1]}"]}"""),
        ).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ACTIVE_HOLD_EXISTS"))
        assertEquals(1, count("SELECT ticket_count FROM buyer_event WHERE cpf = :c AND event_id = :e", "c" to cpfA, "e" to f.eventId))
    }

    @Test
    fun `due unswept previous hold is lazily expired and seats are reusable`() {
        val f = fixture()
        val old = hold(f, f.seatIds.take(4))
        val oldId = UUID.fromString(holdId(old))
        jdbc.sql("UPDATE hold SET expires_at = clock_timestamp() - interval '1 second' WHERE id = :id").param("id", oldId).update()
        val r = hold(f, f.seatIds.subList(2, 5))
        assertEquals(201, r.response.status)
        assertEquals("EXPIRED", jdbc.sql("SELECT status FROM hold WHERE id = :id").param("id", oldId).query(String::class.java).single())
        assertEquals(4, count("SELECT count(*) FROM seat_allocation WHERE hold_id = :h AND status = 'RELEASED'", "h" to oldId))
        assertEquals(3, count("SELECT ticket_count FROM buyer_event WHERE cpf = :c AND event_id = :e", "c" to cpfA, "e" to f.eventId))
    }

    @Test
    fun `replay with same key returns same hold without side effects`() {
        val f = fixture()
        val key = UUID.randomUUID().toString()
        val first = hold(f, listOf(f.seatIds[1], f.seatIds[0]), key)
        val second = hold(f, listOf(f.seatIds[0], f.seatIds[1]), key)
        assertEquals(201, second.response.status)
        assertEquals(first.response.contentAsString, second.response.contentAsString)
        assertEquals(1, count("SELECT count(*) FROM hold WHERE event_id = :e", "e" to f.eventId))
        assertEquals(2, count("SELECT ticket_count FROM buyer_event WHERE cpf = :c AND event_id = :e", "c" to cpfA, "e" to f.eventId))
    }

    @Test
    fun `same key with different body returns 422`() {
        val f = fixture()
        val key = UUID.randomUUID().toString()
        hold(f, listOf(f.seatIds[0]), key)
        mvc.perform(
            post("/holds").header("Idempotency-Key", key).header("X-Buyer-Cpf", cpfA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"event_id":"${f.eventId}","seat_ids":["${f.seatIds[1]}"]}"""),
        ).andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
    }

    @Test
    fun `same key from another cpf is rejected and leaks nothing`() {
        val f = fixture()
        val key = UUID.randomUUID().toString()
        hold(f, listOf(f.seatIds[0]), key)
        val r = hold(f, listOf(f.seatIds[0]), key, cpfB)
        assertEquals(422, r.response.status)
        assertEquals(false, r.response.contentAsString.contains("hold_id"))
    }

    @Test
    fun `get and delete of another cpf are denied`() {
        val f = fixture()
        val id = holdId(hold(f, listOf(f.seatIds[0])))
        mvc.perform(get("/holds/$id").header("X-Buyer-Cpf", cpfB)).andExpect(status().isNotFound)
        mvc.perform(delete("/holds/$id").header("X-Buyer-Cpf", cpfB)).andExpect(status().isNotFound)
        assertEquals("ACTIVE", jdbc.sql("SELECT status FROM hold WHERE id = :id").param("id", UUID.fromString(id)).query(String::class.java).single())
    }

    @Test
    fun `delete releases hold, allocations and counter`() {
        val f = fixture()
        val id = holdId(hold(f, f.seatIds.take(2)))
        mvc.perform(delete("/holds/$id").header("X-Buyer-Cpf", cpfA)).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("RELEASED"))
        assertEquals(0, count("SELECT ticket_count FROM buyer_event WHERE cpf = :c AND event_id = :e", "c" to cpfA, "e" to f.eventId))
        assertEquals(2, count("SELECT count(*) FROM seat_allocation WHERE hold_id = :h AND status = 'RELEASED'", "h" to UUID.fromString(id)))
        mvc.perform(delete("/holds/$id").header("X-Buyer-Cpf", cpfA)).andExpect(status().isOk)
        assertEquals(201, hold(f, f.seatIds.take(2)).response.status)
    }

    @Test
    fun `validates headers, event state and seat ownership`() {
        val f = fixture()
        mvc.perform(post("/holds").header("X-Buyer-Cpf", cpfA).contentType(MediaType.APPLICATION_JSON)
            .content("""{"event_id":"${f.eventId}","seat_ids":["${f.seatIds[0]}"]}"""))
            .andExpect(status().isUnprocessableEntity)
        mvc.perform(post("/holds").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON)
            .content("""{"event_id":"${f.eventId}","seat_ids":["${f.seatIds[0]}"]}"""))
            .andExpect(status().isUnprocessableEntity)
        val closed = fixture(status = "CLOSED")
        assertEquals(409, hold(closed, listOf(closed.seatIds[0])).response.status)
        val future = fixture(salesOpen = "now() + interval '1 day'")
        assertEquals(409, hold(future, listOf(future.seatIds[0])).response.status)
        val other = fixture()
        assertEquals(422, hold(f, listOf(other.seatIds[0])).response.status)
        assertEquals(422, hold(f, listOf(f.seatIds[0], f.seatIds[0])).response.status)
        assertEquals(0, count("SELECT count(*) FROM hold WHERE event_id = :e", "e" to f.eventId))
    }
}
