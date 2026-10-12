package com.fastticket.e2e

import com.fastticket.support.InvariantsExtension
import com.fastticket.support.TestcontainersConfiguration
import com.fastticket.support.TicketingApi
import com.fastticket.support.WireMockContainer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import java.util.UUID
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(InvariantsExtension::class)
class PurchaseFlowTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    private lateinit var api: TicketingApi

    @BeforeEach
    fun setUp() {
        api = TicketingApi(mvc, jdbc)
    }

    private class Flow(val eventId: String, val sectorId: String, val seatIds: List<UUID>)

    private fun statuses(seatmap: JsonNode): List<String> =
        seatmap.get("sectors").get(0).get("rows").toList().flatMap { row -> row.get("seats").toList().map { it.get("status").asString() } }

    private fun registerAndPickSeats(count: Int): Flow {
        val created = api.createEvent(api.createEventBody(rows = 2, seatsPerRow = 3))
        assertEquals(201, created.response.status)
        val eventId = api.body(created).get("id").asString()
        val map = api.body(api.seatmap(eventId))
        assertEquals(List(6) { "AVAILABLE" }, statuses(map))
        val sector = map.get("sectors").get(0)
        val seats = sector.get("rows").toList().flatMap { row -> row.get("seats").toList().map { s -> UUID.fromString(s.get("id").asString()) } }
        return Flow(eventId, sector.get("sectorId").asString(), seats.take(count))
    }

    @Test
    fun `simple purchase from event registration to confirmed order`() {
        val flow = registerAndPickSeats(2)
        val hold = api.hold(UUID.fromString(flow.eventId), flow.seatIds)
        assertEquals(201, hold.response.status)
        val holdId = UUID.fromString(api.body(hold).get("hold_id").asString())
        assertEquals(0, java.math.BigDecimal("301.00").compareTo(api.body(hold).get("total_amount").decimalValue()))
        assertEquals(List(2) { "HELD" } + List(4) { "AVAILABLE" }, statuses(api.body(api.seatmap(flow.eventId))))

        val checkout = api.checkout(holdId, "tok_ok")
        assertEquals(201, checkout.response.status)
        val orderId = api.body(checkout).get("order_id").asString()

        assertEquals(List(2) { "SOLD" } + List(4) { "AVAILABLE" }, statuses(api.body(api.seatmap(flow.eventId))))
        val status = api.body(api.checkoutStatus(holdId))
        assertEquals("CONFIRMED", status.get("status").asString())
        assertEquals(orderId, status.get("order").get("order_id").asString())
        assertEquals("CONFIRMED", status.get("order").get("status").asString())
        assertEquals("APPROVED", status.get("payments").get(0).get("status").asString())
    }

    @Test
    fun `declined card returns 402 and the seats stay held for a new attempt`() {
        val flow = registerAndPickSeats(1)
        val holdId = UUID.fromString(api.body(api.hold(UUID.fromString(flow.eventId), flow.seatIds)).get("hold_id").asString())

        val declined = api.checkout(holdId, "tok_declined")
        assertEquals(402, declined.response.status)
        assertEquals("HELD", statuses(api.body(api.seatmap(flow.eventId))).first())

        assertEquals(201, api.checkout(holdId, "tok_ok").response.status)
    }

    @Test
    fun `gateway timeout and 5xx return 202 and never decline`() {
        listOf("tok_timeout", "tok_5xx").forEach { token ->
            val flow = registerAndPickSeats(1)
            val holdId = UUID.fromString(api.body(api.hold(UUID.fromString(flow.eventId), flow.seatIds)).get("hold_id").asString())

            val r = api.checkout(holdId, token)

            assertEquals(202, r.response.status, token)
            assertEquals("PENDING", api.body(r).get("status").asString())
            assertEquals("ACTIVE", api.holdStatus(holdId))
        }
    }

    companion object {
        private val wiremock = WireMockContainer()

        @JvmStatic
        @DynamicPropertySource
        fun gateway(registry: DynamicPropertyRegistry) {
            registry.add("gateway.base-url") { wiremock.also { if (!it.isRunning) it.start() }.baseUrl() }
            registry.add("gateway.api-key") { "test-key" }
        }
    }
}
