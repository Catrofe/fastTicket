package com.fastticket.checkout.domain

import java.time.Duration
import java.util.UUID

sealed interface PendingInsertResult {
    data class Inserted(val payment: Payment) : PendingInsertResult
    data object PendingAlreadyExists : PendingInsertResult
}

interface PaymentRepository {
    fun insertPending(payment: Payment): PendingInsertResult
    fun resolvePending(id: UUID, to: PaymentStatus, gatewayRef: String? = null, declineReason: String? = null): Payment?
    fun findById(id: UUID): Payment?
    fun findByHoldId(holdId: UUID): List<Payment>
    fun existsRecentPending(holdId: UUID, grace: Duration): Boolean
}

interface OrderRepository {
    fun insertConfirmed(order: Order): Order?
    fun findById(id: UUID): Order?
    fun findByHoldId(holdId: UUID): Order?
}

interface RefundRepository {
    fun insertPending(refund: Refund): Refund?
    fun findByPaymentId(paymentId: UUID): Refund?
}
