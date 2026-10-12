package com.fastticket.payments

import java.math.BigDecimal
import java.util.UUID

interface PaymentGateway {
    fun charge(reference: UUID, amount: BigDecimal, token: String): ChargeResult
    fun findCharge(reference: UUID): ChargeResult
    fun refund(reference: UUID, paymentReference: UUID, amount: BigDecimal): RefundResult
}

sealed interface ChargeResult {
    data class Approved(val gatewayRef: String) : ChargeResult
    data class Declined(val reason: String) : ChargeResult
    data object Unknown : ChargeResult
}

sealed interface RefundResult {
    data class Done(val gatewayRef: String) : RefundResult
    data object Unknown : RefundResult
}
