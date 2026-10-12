package com.fastticket.support

import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

const val CPF_A = "52998224725"
const val CPF_B = "11144477735"

class TestEvent(val eventId: UUID, val seatIds: List<UUID>)

class TicketingApi(val mvc: MockMvc, val jdbc: JdbcClient) {
    val json = JsonMapper()

    fun newKey(): String = UUID.randomUUID().toString()

    fun seedEvent(seats: Int = 8, price: String = "10.50"): TestEvent {
        val eventId = UUID.randomUUID()
        val sectorId = UUID.randomUUID()
        jdbc.sql("INSERT INTO event (id, name, starts_at, sales_open_at, status) VALUES (:id, 'E', now() + interval '30 day', now() - interval '1 hour', 'ON_SALE')")
            .param("id", eventId).update()
        jdbc.sql("INSERT INTO sector (id, event_id, name, kind, capacity, base_price) VALUES (:id, :e, 'S', 'NUMBERED', :c, $price)")
            .param("id", sectorId).param("e", eventId).param("c", seats).update()
        val ids = (1..seats).map { UUID.randomUUID() }
        ids.forEachIndexed { i, id ->
            jdbc.sql("INSERT INTO seat (id, sector_id, row_label, seat_number) VALUES (:id, :s, 'A', :n)")
                .param("id", id).param("s", sectorId).param("n", i + 1).update()
        }
        return TestEvent(eventId, ids)
    }

    fun createEventBody(rows: Int, seatsPerRow: Int, name: String = "Show"): String =
        """{"name":"$name","startsAt":"2027-01-01T20:00:00Z","salesOpenAt":"2026-01-01T10:00:00Z","sectors":[{"name":"Pista Premium","kind":"NUMBERED","basePrice":150.50,"rows":[${
            (1..rows).joinToString(",") { """{"label":"R$it","seats":$seatsPerRow}""" }
        }]}]}"""

    fun createEvent(body: String): MvcResult =
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn()

    fun seatmap(eventId: String): MvcResult = mvc.perform(get("/events/$eventId/seatmap")).andReturn()

    fun hold(eventId: UUID, seats: List<UUID>, key: String = newKey(), cpf: String = CPF_A): MvcResult =
        mvc.perform(
            post("/holds").header("Idempotency-Key", key).header("X-Buyer-Cpf", cpf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"event_id":"$eventId","seat_ids":[${seats.joinToString(",") { "\"$it\"" }}]}"""),
        ).andReturn()

    fun newHold(seats: Int = 2, cpf: String = CPF_A): UUID {
        val event = seedEvent(seats)
        val result = hold(event.eventId, event.seatIds, cpf = cpf)
        check(result.response.status == 201) { "hold failed: ${result.response.contentAsString}" }
        return UUID.fromString(body(result).get("hold_id").asString())
    }

    fun checkout(holdId: UUID, token: String, key: String = newKey(), cpf: String = CPF_A): MvcResult =
        mvc.perform(
            post("/holds/$holdId/checkout").header("Idempotency-Key", key).header("X-Buyer-Cpf", cpf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"method":"CREDIT","token":"$token"}"""),
        ).andReturn()

    fun checkoutStatus(holdId: UUID, cpf: String = CPF_A): MvcResult =
        mvc.perform(get("/holds/$holdId/checkout").header("X-Buyer-Cpf", cpf)).andReturn()

    fun body(result: MvcResult): JsonNode = json.readTree(result.response.contentAsString)

    fun strings(sql: String, id: UUID): List<String> = jdbc.sql(sql).param("id", id).query(String::class.java).list().filterNotNull()

    fun holdStatus(holdId: UUID): String = strings("SELECT status FROM hold WHERE id = :id", holdId).single()

    fun expireNow(holdId: UUID) {
        jdbc.sql("UPDATE hold SET expires_at = now() - interval '1 minute' WHERE id = :id").param("id", holdId).update()
    }

    fun agePayments(holdId: UUID) {
        jdbc.sql("UPDATE payment SET created_at = now() - interval '1 hour' WHERE hold_id = :id").param("id", holdId).update()
    }
}

fun validCpf(seed: Int): String {
    val base = (seed + 100_000_000).toString().take(9).map { it - '0' }
    fun digit(digits: List<Int>): Int {
        val sum = digits.mapIndexed { i, d -> d * (digits.size + 1 - i) }.sum()
        val r = (sum * 10) % 11
        return if (r == 10) 0 else r
    }
    val d1 = digit(base)
    val d2 = digit(base + d1)
    return (base + d1 + d2).joinToString("")
}
