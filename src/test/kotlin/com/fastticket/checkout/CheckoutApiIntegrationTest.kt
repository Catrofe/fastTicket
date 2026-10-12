package com.fastticket.checkout

import com.fastticket.payments.ChargeResult
import com.fastticket.payments.PaymentGateway
import com.fastticket.payments.RefundResult
import com.fastticket.support.InvariantsExtension
import com.fastticket.support.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@TestConfiguration(proxyBeanMethods = false)
class FakeGatewayConfiguration {
    @Bean
    @Primary
    fun fakeGateway(): FakeGateway = FakeGateway()
}

class FakeGateway : PaymentGateway {
    val transactionActive = CopyOnWriteArrayList<Boolean>()

    override fun charge(reference: UUID, amount: BigDecimal, token: String): ChargeResult {
        transactionActive += TransactionSynchronizationManager.isActualTransactionActive()
        return when (token) {
            "tok_ok" -> ChargeResult.Approved("gw-$reference")
            "tok_declined" -> ChargeResult.Declined("insufficient_funds")
            else -> ChargeResult.Unknown
        }
    }

    override fun findCharge(reference: UUID): ChargeResult = ChargeResult.Unknown
    override fun refund(reference: UUID, paymentReference: UUID, amount: BigDecimal): RefundResult = RefundResult.Unknown
}

@Import(TestcontainersConfiguration::class, FakeGatewayConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(InvariantsExtension::class)
class CheckoutApiIntegrationTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var gateway: FakeGateway
    @Autowired lateinit var inventory: com.fastticket.inventory.InventoryFacade
    private val json = JsonMapper()
    private val cpfA = "52998224725"
    private val cpfB = "11144477735"

    private fun newHold(cpf: String = cpfA): UUID {
        val eventId = UUID.randomUUID()
        val sectorId = UUID.randomUUID()
        jdbc.sql("INSERT INTO event (id, name, starts_at, sales_open_at, status) VALUES (:id, 'E', now() + interval '30 day', now() - interval '1 hour', 'ON_SALE')")
            .param("id", eventId).update()
        jdbc.sql("INSERT INTO sector (id, event_id, name, kind, capacity, base_price) VALUES (:id, :e, 'S', 'NUMBERED', 2, 10.50)")
            .param("id", sectorId).param("e", eventId).update()
        val seats = (1..2).map { UUID.randomUUID() }
        seats.forEachIndexed { i, id ->
            jdbc.sql("INSERT INTO seat (id, sector_id, row_label, seat_number) VALUES (:id, :s, 'A', :n)")
                .param("id", id).param("s", sectorId).param("n", i + 1).update()
        }
        val r = mvc.perform(
            post("/holds").header("Idempotency-Key", UUID.randomUUID().toString()).header("X-Buyer-Cpf", cpf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"event_id":"$eventId","seat_ids":["${seats[0]}","${seats[1]}"]}"""),
        ).andExpect(status().isCreated).andReturn()
        return UUID.fromString(json.readTree(r.response.contentAsString).get("hold_id").asString())
    }

    private fun checkout(holdId: UUID, token: String, key: String = UUID.randomUUID().toString(), cpf: String = cpfA): MvcResult =
        mvc.perform(
            post("/holds/$holdId/checkout").header("Idempotency-Key", key).header("X-Buyer-Cpf", cpf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"method":"CREDIT","token":"$token"}"""),
        ).andReturn()

    private fun str(sql: String, id: UUID) = jdbc.sql(sql).param("id", id).query(String::class.java).list()

    @Test
    fun `approved payment confirms hold and creates order, replay returns same order`() {
        val hold = newHold()
        val key = UUID.randomUUID().toString()
        val r = checkout(hold, "tok_ok", key)
        assertEquals(201, r.response.status)
        val orderId = json.readTree(r.response.contentAsString).get("order_id").asString()
        assertEquals(listOf("CONFIRMED"), str("SELECT status FROM hold WHERE id = :id", hold))
        assertEquals(listOf("SOLD", "SOLD"), str("SELECT status FROM seat_allocation WHERE hold_id = :id", hold))
        assertEquals(listOf("APPROVED"), str("SELECT status FROM payment WHERE hold_id = :id", hold))
        assertEquals(listOf(orderId), str("""SELECT id::text FROM "order" WHERE hold_id = :id""", hold))

        val replay = checkout(hold, "tok_ok", key)
        assertEquals(201, replay.response.status)
        assertEquals(orderId, json.readTree(replay.response.contentAsString).get("order_id").asString())
        assertEquals(1, str("SELECT status FROM payment WHERE hold_id = :id", hold).size)

        mvc.perform(get("/holds/$hold/checkout").header("X-Buyer-Cpf", cpfA)).andExpect(status().isOk)
            .andExpect(jsonPath("$.order.order_id").value(orderId))
            .andExpect(jsonPath("$.payments[0].status").value("APPROVED"))
        assertFalse(gateway.transactionActive.any { it })
    }

    @Test
    fun `declined keeps hold active and allows a new attempt`() {
        val hold = newHold()
        val key = UUID.randomUUID().toString()
        val r = checkout(hold, "tok_declined", key)
        assertEquals(402, r.response.status)
        assertEquals("PAYMENT_DECLINED", json.readTree(r.response.contentAsString).get("code").asString())
        assertEquals(listOf("ACTIVE"), str("SELECT status FROM hold WHERE id = :id", hold))
        assertEquals(listOf("DECLINED"), str("SELECT status FROM payment WHERE hold_id = :id", hold))
        assertEquals(402, checkout(hold, "tok_declined", key).response.status)
        assertEquals(1, str("SELECT status FROM payment WHERE hold_id = :id", hold).size)
        assertEquals(201, checkout(hold, "tok_ok").response.status)
    }

    @Test
    fun `unknown gateway result returns 202 and keeps payment pending`() {
        val hold = newHold()
        val key = UUID.randomUUID().toString()
        val r = checkout(hold, "tok_timeout", key)
        assertEquals(202, r.response.status)
        val paymentId = json.readTree(r.response.contentAsString).get("payment_id").asString()
        assertEquals("PENDING", json.readTree(r.response.contentAsString).get("status").asString())
        assertEquals(listOf("PENDING"), str("SELECT status FROM payment WHERE hold_id = :id", hold))
        assertEquals(listOf("ACTIVE"), str("SELECT status FROM hold WHERE id = :id", hold))

        val replay = checkout(hold, "tok_timeout", key)
        assertEquals(202, replay.response.status)
        assertEquals(paymentId, json.readTree(replay.response.contentAsString).get("payment_id").asString())

        val other = checkout(hold, "tok_ok")
        assertEquals(409, other.response.status)
        assertEquals("PAYMENT_IN_PROGRESS", json.readTree(other.response.contentAsString).get("code").asString())
    }

    @Test
    fun `expired hold returns 409, is expired by the lazy path and the key is replayed`() {
        val hold = newHold()
        jdbc.sql("UPDATE hold SET expires_at = now() - interval '1 minute' WHERE id = :id").param("id", hold).update()
        val key = UUID.randomUUID().toString()
        val r = checkout(hold, "tok_ok", key)
        assertEquals(409, r.response.status)
        assertEquals("HOLD_EXPIRED", json.readTree(r.response.contentAsString).get("code").asString())
        assertEquals(listOf("EXPIRED"), str("SELECT status FROM hold WHERE id = :id", hold))
        assertEquals(0, str("SELECT status FROM payment WHERE hold_id = :id", hold).size)
        assertEquals(409, checkout(hold, "tok_ok", key).response.status)
    }

    @Test
    fun `other buyer gets 404 and leaves no idempotency trace`() {
        val hold = newHold()
        val key = UUID.randomUUID().toString()
        assertEquals(404, checkout(hold, "tok_ok", key, cpfB).response.status)
        assertEquals(404, checkout(UUID.randomUUID(), "tok_ok", cpf = cpfA).response.status)
        assertEquals(0, jdbc.sql("SELECT count(*) FROM idempotency_key WHERE key = :k").param("k", key).query(Int::class.java).single())
        assertEquals(listOf("ACTIVE"), str("SELECT status FROM hold WHERE id = :id", hold))
        mvc.perform(get("/holds/$hold/checkout").header("X-Buyer-Cpf", cpfB)).andExpect(status().isNotFound)
    }

    @Test
    fun `missing key and invalid body are rejected`() {
        val hold = newHold()
        mvc.perform(
            post("/holds/$hold/checkout").header("X-Buyer-Cpf", cpfA).contentType(MediaType.APPLICATION_JSON)
                .content("""{"method":"CREDIT","token":"tok_ok"}"""),
        ).andExpect(status().isUnprocessableEntity)
        mvc.perform(
            post("/holds/$hold/checkout").header("Idempotency-Key", "k").header("X-Buyer-Cpf", cpfA)
                .contentType(MediaType.APPLICATION_JSON).content("""{"method":"PIX","token":""}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `pending payment within grace vetoes expiration`() {
        val hold = newHold()
        assertEquals(202, checkout(hold, "tok_timeout").response.status)
        jdbc.sql("UPDATE hold SET expires_at = now() - interval '1 minute' WHERE id = :id").param("id", hold).update()
        assertEquals(0, inventory.expireDueHolds(listOf(hold)))
        assertEquals(listOf("ACTIVE"), str("SELECT status FROM hold WHERE id = :id", hold))
        jdbc.sql("UPDATE payment SET created_at = now() - interval '1 hour' WHERE hold_id = :id").param("id", hold).update()
        assertEquals(1, inventory.expireDueHolds(listOf(hold)))
    }
}
