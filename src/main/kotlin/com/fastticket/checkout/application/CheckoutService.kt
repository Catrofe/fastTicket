package com.fastticket.checkout.application

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
import com.fastticket.inventory.HoldLock
import com.fastticket.inventory.InventoryFacade
import com.fastticket.payments.ChargeResult
import com.fastticket.payments.PaymentGateway
import com.fastticket.shared.error.ApiException
import com.fastticket.shared.error.ErrorCodes
import com.fastticket.shared.idempotency.IdempotencyClaim
import com.fastticket.shared.idempotency.IdempotencyStore
import com.fastticket.shared.idempotency.RequestHasher
import com.fastticket.shared.validation.Cpf
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

sealed interface CheckoutOutcome {
    data class Confirmed(val orderId: UUID, val paymentId: UUID) : CheckoutOutcome
    data class Pending(val paymentId: UUID, val status: PaymentStatus, val orderId: UUID? = null) : CheckoutOutcome
}

class HoldNotFoundException : ApiException(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "Hold not found")

internal data class StoredResponse(
    val orderId: UUID? = null,
    val paymentId: UUID? = null,
    val code: String? = null,
    val detail: String? = null,
)

private sealed interface Step {
    data class Done(val outcome: CheckoutOutcome) : Step
    data class Failed(val error: ApiException) : Step
    data class Charge(val payment: Payment) : Step

    fun resolve(): CheckoutOutcome = when (this) {
        is Done -> outcome
        is Failed -> throw error
        is Charge -> error("unresolved charge step")
    }
}

@Service
class CheckoutService(
    private val payments: PaymentRepository,
    private val orders: OrderRepository,
    private val refunds: RefundRepository,
    private val inventory: InventoryFacade,
    private val gateway: PaymentGateway,
    private val idempotency: IdempotencyStore,
    private val hasher: RequestHasher,
    private val json: JsonMapper,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)

    fun checkout(cpf: Cpf, key: String, holdId: UUID, method: PaymentMethod, token: String): CheckoutOutcome {
        val scope = "POST /holds/$holdId/checkout"
        val hash = hasher.hash(cpf, "$holdId:${method.name}")
        val first = checkNotNull(tx.execute { begin(cpf, key, scope, hash, holdId, method) })
        if (first !is Step.Charge) return first.resolve()

        val result = charge(first.payment, token)
        return when (result) {
            ChargeResult.Unknown -> {
                log.info("payment outcome unknown id={} hold={}", first.payment.id, holdId)
                CheckoutOutcome.Pending(first.payment.id, PaymentStatus.PENDING)
            }
            is ChargeResult.Declined -> checkNotNull(tx.execute { applyDeclined(first.payment, key, scope, result) }).resolve()
            is ChargeResult.Approved -> checkNotNull(tx.execute { status -> applyApproved(first.payment, key, scope, result, status) }).resolve()
        }
    }

    private fun begin(cpf: Cpf, key: String, scope: String, hash: String, holdId: UUID, method: PaymentMethod): Step {
        when (val claim = idempotency.claim(key, scope, hash)) {
            IdempotencyClaim.Acquired -> Unit
            IdempotencyClaim.InProgress -> return inProgress(key, scope)
            is IdempotencyClaim.Replay -> return replay(claim)
        }
        return when (val lock = inventory.lockHoldForPayment(holdId, cpf)) {
            is HoldLock.Ready -> when (val inserted = payments.insertPending(Payment(UUID.randomUUID(), holdId, method, lock.hold.totalAmount, PaymentStatus.PENDING))) {
                is PendingInsertResult.Inserted -> {
                    idempotency.attachResource(key, scope, inserted.payment.id)
                    log.info("payment pending id={} hold={} buyer={}", inserted.payment.id, holdId, cpf)
                    Step.Charge(inserted.payment)
                }
                PendingInsertResult.PendingAlreadyExists ->
                    throw ApiException(HttpStatus.CONFLICT, ErrorCodes.PAYMENT_IN_PROGRESS, "A payment for this hold is already in progress")
            }
            HoldLock.Expired -> fail(key, scope, HttpStatus.CONFLICT, ErrorCodes.HOLD_EXPIRED, "Hold is no longer active", null)
            HoldLock.NotFound, HoldLock.NotOwner -> throw HoldNotFoundException()
        }
    }

    private fun inProgress(key: String, scope: String): Step {
        val paymentId = idempotency.findResource(key, scope)
            ?: throw ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS", "A request with this key is in progress")
        return Step.Done(current(paymentId))
    }

    private fun replay(claim: IdempotencyClaim.Replay): Step {
        val stored = json.readValue(checkNotNull(claim.responseBody), StoredResponse::class.java)
        return if (claim.responseStatus == HttpStatus.CREATED.value()) {
            Step.Done(CheckoutOutcome.Confirmed(checkNotNull(stored.orderId), checkNotNull(stored.paymentId)))
        } else {
            Step.Failed(ApiException(HttpStatus.valueOf(claim.responseStatus), checkNotNull(stored.code), checkNotNull(stored.detail)))
        }
    }

    private fun charge(payment: Payment, token: String): ChargeResult =
        try {
            gateway.charge(payment.id, payment.amount, token)
        } catch (e: RuntimeException) {
            log.warn("gateway call failed payment={} cause={}", payment.id, e.javaClass.simpleName)
            ChargeResult.Unknown
        }

    private fun applyDeclined(payment: Payment, key: String, scope: String, result: ChargeResult.Declined): Step {
        payments.resolvePending(payment.id, PaymentStatus.DECLINED, declineReason = result.reason)
            ?: return Step.Done(current(payment.id))
        log.info("payment declined id={} hold={}", payment.id, payment.holdId)
        return fail(key, scope, HttpStatus.PAYMENT_REQUIRED, ErrorCodes.PAYMENT_DECLINED, "Payment was declined", payment.id)
    }

    private fun applyApproved(
        payment: Payment,
        key: String,
        scope: String,
        result: ChargeResult.Approved,
        status: org.springframework.transaction.TransactionStatus,
    ): Step {
        val confirmed = inventory.confirmHold(payment.holdId)
        if (payments.resolvePending(payment.id, PaymentStatus.APPROVED, gatewayRef = result.gatewayRef) == null) {
            status.setRollbackOnly()
            return Step.Done(current(payment.id))
        }
        if (confirmed) {
            val order = checkNotNull(orders.insertConfirmed(Order(UUID.randomUUID(), payment.holdId, OrderStatus.CONFIRMED, payment.amount))) {
                "order already exists for hold ${payment.holdId}"
            }
            idempotency.complete(key, scope, HttpStatus.CREATED.value(), json.writeValueAsString(StoredResponse(orderId = order.id, paymentId = payment.id)), payment.id)
            log.info("order confirmed id={} payment={} hold={}", order.id, payment.id, payment.holdId)
            return Step.Done(CheckoutOutcome.Confirmed(order.id, payment.id))
        }
        refunds.insertPending(Refund(UUID.randomUUID(), payment.id, payment.amount, RefundStatus.PENDING, RefundReason.EXPIRED_AFTER_APPROVAL))
        log.warn("payment approved but hold not active id={} hold={}", payment.id, payment.holdId)
        return fail(
            key, scope, HttpStatus.CONFLICT, ErrorCodes.PAYMENT_APPROVED_HOLD_EXPIRED,
            "Payment was approved after the hold expired; a refund was scheduled", payment.id,
        )
    }

    private fun fail(key: String, scope: String, status: HttpStatus, code: String, detail: String, paymentId: UUID?): Step {
        idempotency.complete(key, scope, status.value(), json.writeValueAsString(StoredResponse(paymentId = paymentId, code = code, detail = detail)), paymentId)
        return Step.Failed(ApiException(status, code, detail))
    }

    private fun current(paymentId: UUID): CheckoutOutcome.Pending {
        val payment = checkNotNull(payments.findById(paymentId)) { "payment $paymentId not found" }
        val orderId = if (payment.status == PaymentStatus.APPROVED) orders.findByHoldId(payment.holdId)?.id else null
        return CheckoutOutcome.Pending(payment.id, payment.status, orderId)
    }
}
