package com.fastticket.checkout

import com.fastticket.inventory.InventoryFacade
import com.fastticket.jobs.HoldExpirationJob
import com.fastticket.payments.ChargeResult
import com.fastticket.support.InvariantsExtension
import com.fastticket.support.ScriptedGateway
import com.fastticket.support.ScriptedGatewayConfiguration
import com.fastticket.support.TestcontainersConfiguration
import com.fastticket.support.TicketingApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class, ScriptedGatewayConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(InvariantsExtension::class)
class CheckoutFlowIntegrationTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var gateway: ScriptedGateway
    @Autowired lateinit var inventory: InventoryFacade
    @Autowired lateinit var sweeper: HoldExpirationJob
    private lateinit var api: TicketingApi

    @BeforeEach
    fun setUp() {
        gateway.reset()
        api = TicketingApi(mvc, jdbc)
    }

    private fun code(r: org.springframework.test.web.servlet.MvcResult) = api.body(r).get("code").asString()

    @Test
    fun `declined keeps the hold active and a new key creates a new payment that can be approved`() {
        val hold = api.newHold()
        val declined = api.checkout(hold, "tok_declined")
        assertEquals(402, declined.response.status)
        assertEquals("PAYMENT_DECLINED", code(declined))
        assertEquals("ACTIVE", api.holdStatus(hold))

        val retry = api.checkout(hold, "tok_ok", key = api.newKey())
        assertEquals(201, retry.response.status)
        assertEquals(listOf("APPROVED", "DECLINED"), api.strings("SELECT status FROM payment WHERE hold_id = :id ORDER BY status", hold))
        assertEquals("CONFIRMED", api.holdStatus(hold))
    }

    @Test
    fun `unknown returns 202 and the sweeper does not expire the hold while the grace is active`() {
        val hold = api.newHold()
        val r = api.checkout(hold, "tok_timeout")
        assertEquals(202, r.response.status)
        api.expireNow(hold)

        sweeper.run()

        assertEquals("ACTIVE", api.holdStatus(hold))
        assertEquals(listOf("PENDING"), api.strings("SELECT status FROM payment WHERE hold_id = :id", hold))
        assertTrue(inventory.findDueHoldIds(1000).contains(hold))
    }

    @Test
    fun `approval after the hold expired creates a pending refund and returns 409`() {
        val hold = api.newHold()
        gateway.onCharge = { ref, _ ->
            api.agePayments(hold)
            api.expireNow(hold)
            check(inventory.expireDueHolds(listOf(hold)) == 1)
            ChargeResult.Approved("gw-$ref")
        }

        val r = api.checkout(hold, "tok_ok")

        assertEquals(409, r.response.status)
        assertEquals("PAYMENT_APPROVED_HOLD_EXPIRED", code(r))
        assertEquals("EXPIRED", api.holdStatus(hold))
        assertEquals(listOf("APPROVED"), api.strings("SELECT status FROM payment WHERE hold_id = :id", hold))
        assertEquals(
            listOf("PENDING:EXPIRED_AFTER_APPROVAL"),
            api.strings("SELECT r.status || ':' || r.reason FROM refund r JOIN payment p ON p.id = r.payment_id WHERE p.hold_id = :id", hold),
        )
        assertEquals(0, api.strings("""SELECT id::text FROM "order" WHERE hold_id = :id""", hold).size)
    }

    @Test
    fun `same idempotency key on hold and on checkout returns the same response without new effects`() {
        val event = api.seedEvent(4)
        val holdKey = api.newKey()
        val first = api.hold(event.eventId, event.seatIds.take(2), key = holdKey)
        val second = api.hold(event.eventId, event.seatIds.take(2), key = holdKey)
        assertEquals(201, first.response.status)
        assertEquals(201, second.response.status)
        assertEquals(api.body(first).get("hold_id").asString(), api.body(second).get("hold_id").asString())
        val holdId = UUID.fromString(api.body(first).get("hold_id").asString())
        assertEquals(1, jdbc.sql("SELECT count(*) FROM hold WHERE event_id = :e").param("e", event.eventId).query(Int::class.java).single())
        assertEquals(2, jdbc.sql("SELECT ticket_count FROM buyer_event WHERE event_id = :e").param("e", event.eventId).query(Int::class.java).single())

        val checkoutKey = api.newKey()
        val c1 = api.checkout(holdId, "tok_ok", checkoutKey)
        val c2 = api.checkout(holdId, "tok_ok", checkoutKey)
        assertEquals(201, c1.response.status)
        assertEquals(201, c2.response.status)
        assertEquals(api.body(c1).get("order_id").asString(), api.body(c2).get("order_id").asString())
        assertEquals(1, api.strings("SELECT status FROM payment WHERE hold_id = :id", holdId).size)
        assertEquals(1, gateway.transactionActive.size)
        assertEquals(1, api.strings("""SELECT id::text FROM "order" WHERE hold_id = :id""", holdId).size)
    }

    @Test
    fun `two different keys at the same time on one hold, one is rejected with PAYMENT_IN_PROGRESS`() {
        val hold = api.newHold()
        val inGateway = CountDownLatch(1)
        val release = CountDownLatch(1)
        gateway.onCharge = { ref, _ ->
            inGateway.countDown()
            check(release.await(30, TimeUnit.SECONDS))
            ChargeResult.Approved("gw-$ref")
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val firstCall = pool.submit<org.springframework.test.web.servlet.MvcResult> { api.checkout(hold, "tok_ok") }
            assertTrue(inGateway.await(30, TimeUnit.SECONDS))

            val second = api.checkout(hold, "tok_ok")
            assertEquals(409, second.response.status)
            assertEquals("PAYMENT_IN_PROGRESS", code(second))

            release.countDown()
            assertEquals(201, firstCall.get(30, TimeUnit.SECONDS).response.status)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
        assertEquals(listOf("APPROVED"), api.strings("SELECT status FROM payment WHERE hold_id = :id", hold))
        assertEquals("CONFIRMED", api.holdStatus(hold))
    }

    @Test
    fun `replay of an in-progress key while the first call is inside the gateway returns 202`() {
        val hold = api.newHold()
        val inGateway = CountDownLatch(1)
        val release = CountDownLatch(1)
        gateway.onCharge = { ref, _ ->
            inGateway.countDown()
            check(release.await(30, TimeUnit.SECONDS))
            ChargeResult.Approved("gw-$ref")
        }
        val key = api.newKey()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val firstCall = pool.submit<org.springframework.test.web.servlet.MvcResult> { api.checkout(hold, "tok_ok", key) }
            assertTrue(inGateway.await(30, TimeUnit.SECONDS))

            val replay = api.checkout(hold, "tok_ok", key)
            assertEquals(202, replay.response.status)
            assertEquals("PENDING", api.body(replay).get("status").asString())

            release.countDown()
            assertEquals(201, firstCall.get(30, TimeUnit.SECONDS).response.status)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `replay after a crash between tx1 and tx2 returns 202 with the pending payment`() {
        val hold = api.newHold()
        val key = api.newKey()
        gateway.onCharge = { _, _ -> throw AssertionError("process died") }
        runCatching { api.checkout(hold, "tok_ok", key) }
        gateway.reset()

        val paymentId = api.strings("SELECT id::text FROM payment WHERE hold_id = :id", hold).single()
        assertEquals(listOf("PENDING"), api.strings("SELECT status FROM payment WHERE hold_id = :id", hold))

        val replay = api.checkout(hold, "tok_ok", key)

        assertEquals(202, replay.response.status)
        assertEquals(paymentId, api.body(replay).get("payment_id").asString())
        assertEquals("PENDING", api.body(replay).get("status").asString())
        assertEquals(0, gateway.transactionActive.size)
        assertEquals("ACTIVE", api.holdStatus(hold))
    }
}
