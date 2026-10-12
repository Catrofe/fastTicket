package com.fastticket.checkout

import com.fastticket.checkout.domain.Order
import com.fastticket.checkout.domain.OrderRepository
import com.fastticket.checkout.domain.OrderStatus
import com.fastticket.checkout.domain.Payment
import com.fastticket.checkout.domain.PaymentMethod
import com.fastticket.checkout.domain.PaymentRepository
import com.fastticket.checkout.domain.PaymentStatus
import com.fastticket.checkout.domain.PendingInsertResult
import com.fastticket.checkout.domain.Refund
import com.fastticket.checkout.domain.RefundReason
import com.fastticket.checkout.domain.RefundRepository
import com.fastticket.checkout.domain.RefundStatus
import com.fastticket.inventory.domain.Hold
import com.fastticket.inventory.domain.HoldRepository
import com.fastticket.inventory.domain.HoldStatus
import com.fastticket.shared.validation.Cpf
import com.fastticket.support.InvariantsExtension
import com.fastticket.support.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@ExtendWith(InvariantsExtension::class)
class CheckoutPersistenceIntegrationTest {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var holds: HoldRepository
    @Autowired lateinit var payments: PaymentRepository
    @Autowired lateinit var orders: OrderRepository
    @Autowired lateinit var refunds: RefundRepository

    private val cpf = Cpf.parseOrNull("52998224725")!!
    private val createdHolds = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() {
        createdHolds.forEach {
            jdbc.sql("DELETE FROM refund WHERE payment_id IN (SELECT id FROM payment WHERE hold_id = :h)").param("h", it).update()
            jdbc.sql("""DELETE FROM "order" WHERE hold_id = :h""").param("h", it).update()
            jdbc.sql("DELETE FROM payment WHERE hold_id = :h").param("h", it).update()
        }
        createdHolds.clear()
    }

    private fun newHold(): Hold {
        val eventId = UUID.randomUUID()
        jdbc.sql("INSERT INTO event (id, name, starts_at, sales_open_at, status) VALUES (:id, 'E', now() + interval '30 day', now(), 'ON_SALE')")
            .param("id", eventId).update()
        jdbc.sql("INSERT INTO buyer_event (cpf, event_id, ticket_count) VALUES (:cpf, :e, 0)")
            .param("cpf", cpf.digits).param("e", eventId).update()
        return holds.insert(Hold(UUID.randomUUID(), eventId, cpf, HoldStatus.ACTIVE, null, BigDecimal("10.00"), emptyList()))
            .also { createdHolds += it.id }
    }

    private fun pending(holdId: UUID) = Payment(UUID.randomUUID(), holdId, PaymentMethod.CREDIT, BigDecimal("10.00"), PaymentStatus.PENDING)

    private fun insertedPayment(holdId: UUID): Payment =
        assertIs<PendingInsertResult.Inserted>(payments.insertPending(pending(holdId))).payment

    @Test
    fun `insert pending round trips and second pending for same hold is a typed signal`() {
        val hold = newHold()
        val first = insertedPayment(hold.id)
        assertEquals(PaymentStatus.PENDING, first.status)
        assertNotNull(first.createdAt)
        assertEquals(first, payments.findById(first.id))
        assertEquals(PendingInsertResult.PendingAlreadyExists, payments.insertPending(pending(hold.id)))
        assertEquals(listOf(first.id), payments.findByHoldId(hold.id).map { it.id })
    }

    @Test
    fun `conditional update applies once and returns null when already applied`() {
        val hold = newHold()
        val p = insertedPayment(hold.id)
        val approved = assertNotNull(payments.resolvePending(p.id, PaymentStatus.APPROVED, gatewayRef = "gw-1"))
        assertEquals(PaymentStatus.APPROVED, approved.status)
        assertEquals("gw-1", approved.gatewayRef)
        assertNotNull(approved.approvedAt)
        assertNull(payments.resolvePending(p.id, PaymentStatus.DECLINED, declineReason = "x"))
        assertEquals(PaymentStatus.APPROVED, payments.findById(p.id)?.status)
    }

    @Test
    fun `declined records reason and frees the pending slot`() {
        val hold = newHold()
        val p = insertedPayment(hold.id)
        val declined = assertNotNull(payments.resolvePending(p.id, PaymentStatus.DECLINED, declineReason = "insufficient_funds"))
        assertEquals("insufficient_funds", declined.declineReason)
        assertNull(declined.approvedAt)
        assertIs<PendingInsertResult.Inserted>(payments.insertPending(pending(hold.id)))
        assertEquals(2, payments.findByHoldId(hold.id).size)
    }

    @Test
    fun `recent pending detection honours the grace window`() {
        val hold = newHold()
        assertFalse(payments.existsRecentPending(hold.id, Duration.ofMinutes(2)))
        val p = insertedPayment(hold.id)
        assertTrue(payments.existsRecentPending(hold.id, Duration.ofMinutes(2)))
        jdbc.sql("UPDATE payment SET created_at = now() - interval '5 minutes' WHERE id = :id").param("id", p.id).update()
        assertFalse(payments.existsRecentPending(hold.id, Duration.ofMinutes(2)))
        assertTrue(payments.existsRecentPending(hold.id, Duration.ofMinutes(10)))
        payments.resolvePending(p.id, PaymentStatus.DECLINED, declineReason = "x")
        assertFalse(payments.existsRecentPending(hold.id, Duration.ofMinutes(10)))
    }

    @Test
    fun `order is unique per hold`() {
        val hold = newHold()
        val p = insertedPayment(hold.id)
        payments.resolvePending(p.id, PaymentStatus.APPROVED, gatewayRef = "gw")
        val order = assertNotNull(orders.insertConfirmed(Order(UUID.randomUUID(), hold.id, OrderStatus.CONFIRMED, BigDecimal("10.00"))))
        assertEquals(OrderStatus.CONFIRMED, order.status)
        assertNotNull(order.confirmedAt)
        assertNull(orders.insertConfirmed(Order(UUID.randomUUID(), hold.id, OrderStatus.CONFIRMED, BigDecimal("10.00"))))
        assertEquals(order, orders.findByHoldId(hold.id))
        assertEquals(order, orders.findById(order.id))
    }

    @Test
    fun `refund is unique per payment`() {
        val hold = newHold()
        val p = insertedPayment(hold.id)
        payments.resolvePending(p.id, PaymentStatus.APPROVED, gatewayRef = "gw")
        val refund = assertNotNull(
            refunds.insertPending(Refund(UUID.randomUUID(), p.id, BigDecimal("10.00"), RefundStatus.PENDING, RefundReason.EXPIRED_AFTER_APPROVAL)),
        )
        assertEquals(RefundStatus.PENDING, refund.status)
        assertNull(refunds.insertPending(Refund(UUID.randomUUID(), p.id, BigDecimal("10.00"), RefundStatus.PENDING, RefundReason.CANCELLED)))
        assertEquals(refund, refunds.findByPaymentId(p.id))
    }
}
