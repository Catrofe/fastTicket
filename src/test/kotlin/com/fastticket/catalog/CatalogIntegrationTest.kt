package com.fastticket.catalog

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(InvariantsExtension::class)
class CatalogIntegrationTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var facade: CatalogFacade
    private val json = JsonMapper()

    private fun event(vararg sectors: String, name: String = "Show") =
        """{"name":"$name","startsAt":"2027-01-01T20:00:00Z","salesOpenAt":"2026-12-01T10:00:00Z","sectors":[${sectors.joinToString(",")}]}"""

    private fun numbered(name: String, vararg rows: Pair<String, Int>) =
        """{"name":"$name","kind":"NUMBERED","basePrice":150.50,"rows":[${rows.joinToString(",") { """{"label":"${it.first}","seats":${it.second}}""" }}]}"""

    private fun ga(name: String, capacity: Int) = """{"name":"$name","kind":"GA","basePrice":80,"capacity":$capacity}"""

    private fun create(body: String): String =
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated).andReturn().response.contentAsString

    private fun id(body: String, path: String = "id") = json.readTree(body).at("/" + path.replace(".", "/")).asString()

    private fun rejects(body: String, expected: Int) {
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().`is`(expected)).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `creates event with numbered and GA sectors and derived counters`() {
        val created = create(event(numbered("Pista Premium", "A" to 3, "B" to 2), ga("Pista", 101)))
        val eventId = UUID.fromString(id(created))
        assertEquals(5, jdbc.sql("SELECT count(*) FROM seat s JOIN sector c ON c.id = s.sector_id WHERE c.event_id = :e")
            .param("e", eventId).query(Int::class.java).single())
        val gaId = jdbc.sql("SELECT id FROM sector WHERE event_id = :e AND kind = 'GA'").param("e", eventId)
            .query(UUID::class.java).single()
        assertEquals(40, jdbc.sql("SELECT half_cap FROM sector_half_quota WHERE sector_id = :s").param("s", gaId).query(Int::class.java).single())
        assertEquals(101, jdbc.sql("SELECT capacity FROM sector_inventory WHERE sector_id = :s").param("s", gaId).query(Int::class.java).single())
        assertEquals(0, jdbc.sql("SELECT count(*) FROM sector_inventory si JOIN sector s ON s.id = si.sector_id WHERE s.event_id = :e AND s.kind = 'NUMBERED'")
            .param("e", eventId).query(Int::class.java).single())
    }

    @Test
    fun `reads event with sectors and returns 404 for unknown`() {
        val created = create(event(numbered("Camarote", "A" to 2), ga("Pista", 10), name = "Festival"))
        mvc.perform(get("/events/${id(created)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Festival"))
            .andExpect(jsonPath("$.status").value("ON_SALE"))
            .andExpect(jsonPath("$.sectors.length()").value(2))
        mvc.perform(get("/events/${UUID.randomUUID()}")).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"))
        mvc.perform(get("/events/not-a-uuid")).andExpect(status().isBadRequest)
    }

    @Test
    fun `seatmap reflects allocations and supports sector filter`() {
        val created = create(event(numbered("Camarote", "A" to 3), ga("Pista", 50)))
        val eventId = UUID.fromString(id(created))
        val seatIds = jdbc.sql("SELECT s.id FROM seat s JOIN sector c ON c.id = s.sector_id WHERE c.event_id = :e ORDER BY s.seat_number")
            .param("e", eventId).query(UUID::class.java).list().filterNotNull()
        val cpf = "52998224725"
        val holdId = UUID.randomUUID()
        jdbc.sql("INSERT INTO buyer_event (cpf, event_id, ticket_count) VALUES (:c, :e, 2)").param("c", cpf).param("e", eventId).update()
        jdbc.sql("INSERT INTO hold (id, event_id, buyer_cpf, status, expires_at, total_amount) VALUES (:h, :e, :c, 'ACTIVE', now() + interval '10 minutes', 301)")
            .param("h", holdId).param("e", eventId).param("c", cpf).update()
        jdbc.sql("INSERT INTO seat_allocation (id, seat_id, hold_id, status) VALUES (:i, :s, :h, 'HELD')")
            .param("i", UUID.randomUUID()).param("s", seatIds[0]).param("h", holdId).update()
        jdbc.sql("INSERT INTO seat_allocation (id, seat_id, hold_id, status) VALUES (:i, :s, :h, 'RELEASED')")
            .param("i", UUID.randomUUID()).param("s", seatIds[1]).param("h", holdId).update()
        try {
            val body = mvc.perform(get("/events/$eventId/seatmap")).andExpect(status().isOk)
                .andExpect(jsonPath("$.sectors.length()").value(2))
                .andReturn().response.contentAsString
            val sectors = json.readTree(body).get("sectors")
            val numberedSector = sectors.first { it.get("kind").asString() == "NUMBERED" }
            val statuses: List<String> = numberedSector.get("rows").get(0).get("seats").toList().map { it.get("status").asString() }
            assertEquals<List<String>>(listOf("HELD", "AVAILABLE", "AVAILABLE"), statuses)
            val gaSector = sectors.first { it.get("kind").asString() == "GA" }
            assertEquals(50, gaSector.get("capacity").asInt())
            assert(gaSector.get("rows") == null || gaSector.get("rows").isNull)

            val numberedId = numberedSector.get("sectorId").asString()
            mvc.perform(get("/events/$eventId/seatmap").param("sector", numberedId))
                .andExpect(status().isOk).andExpect(jsonPath("$.sectors.length()").value(1))
            mvc.perform(get("/events/$eventId/seatmap").param("sector", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("SECTOR_NOT_FOUND"))
            mvc.perform(get("/events/${UUID.randomUUID()}/seatmap")).andExpect(status().isNotFound)

            val infos = facade.findSeats(seatIds)
            assertEquals(3, infos.size)
            assertEquals(eventId, infos.first().eventId)
            assertEquals(SectorKind.NUMBERED, infos.first().sectorKind)
            assertEquals(0, infos.first().basePrice.compareTo(java.math.BigDecimal("150.50")))
            assertEquals(0, facade.findSeats(emptyList()).size)
        } finally {
            jdbc.sql("DELETE FROM seat_allocation WHERE hold_id = :h").param("h", holdId).update()
            jdbc.sql("DELETE FROM hold WHERE id = :h").param("h", holdId).update()
            jdbc.sql("DELETE FROM buyer_event WHERE cpf = :c AND event_id = :e").param("c", cpf).param("e", eventId).update()
        }
    }

    @Test
    fun `rejects invalid payloads`() {
        val ok = numbered("A", "A" to 1)
        rejects("""{"name":"","startsAt":"2027-01-01T20:00:00Z","salesOpenAt":"2026-12-01T10:00:00Z","sectors":[$ok]}""", 400)
        rejects(event(), 400)
        rejects(event(numbered("A", "A" to 0)), 400)
        rejects(event(numbered("A", "A" to 1001)), 400)
        rejects(event("""{"name":"G","kind":"GA","basePrice":-1,"capacity":10}"""), 400)
        rejects(event("""{"name":"G","kind":"GA","basePrice":10.999,"capacity":10}"""), 400)
        rejects(event("""{"name":"G","kind":"OTHER","basePrice":10,"capacity":10}"""), 400)
        rejects(event(ga("G", 0)), 400)
        rejects(event(ok).replace("\"name\":\"Show\"", "\"name\":\"${"x".repeat(201)}\""), 400)
        rejects(event(ok).replace("}]}", "}],\"extra\":1}"), 400)
        rejects(event(numbered("A", "A" to 1), numbered("A", "B" to 1)), 422)
        rejects(event(numbered("A", "A" to 1, "A" to 2)), 422)
        rejects("""{"name":"G","kind":"GA","basePrice":10,"capacity":10,"rows":[{"label":"A","seats":1}]}""".let { event(it) }, 422)
        rejects(event("""{"name":"N","kind":"NUMBERED","basePrice":10,"capacity":10}"""), 422)
        rejects(event("""{"name":"N","kind":"NUMBERED","basePrice":10,"capacity":10,"rows":[{"label":"A","seats":1}]}"""), 422)
        rejects(event(ok).replace("2026-12-01T10:00:00Z", "2027-02-01T10:00:00Z"), 422)
    }

    @Test
    fun `enforces the 100k seat limit and rolls back`() {
        val before = jdbc.sql("SELECT count(*) FROM event").query(Int::class.java).single()
        val tooMany = event(numbered("Big", *(1..101).map { "R$it" to 1000 }.toTypedArray()))
        rejects(tooMany, 422)
        rejects(event(numbered("S1", *(1..100).map { "R$it" to 1000 }.toTypedArray()), numbered("S2", "A" to 1)), 422)
        assertEquals(before, jdbc.sql("SELECT count(*) FROM event").query(Int::class.java).single())
    }

    @Test
    fun `accepts exactly 100k seats`() {
        val body = event(numbered("Max", *(1..100).map { "R$it" to 1000 }.toTypedArray()))
        val eventId = id(create(body))
        assertEquals(100_000, jdbc.sql("SELECT count(*) FROM seat s JOIN sector c ON c.id = s.sector_id WHERE c.event_id = :e")
            .param("e", UUID.fromString(eventId)).query(Int::class.java).single())
        mvc.perform(get("/events/$eventId/seatmap")).andExpect(status().isOk).andExpect(header().exists("Content-Type"))
    }
}
