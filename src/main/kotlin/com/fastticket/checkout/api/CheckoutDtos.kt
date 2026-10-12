package com.fastticket.checkout.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fastticket.checkout.application.CheckoutStatus
import com.fastticket.checkout.domain.PaymentMethod
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CheckoutRequest(
    @field:NotNull val method: PaymentMethod?,
    @field:NotBlank @field:Size(max = 256) val token: String?,
) {
    override fun toString(): String = "CheckoutRequest(method=$method)"
}

data class OrderCreatedResponse(
    @get:JsonProperty("order_id") val orderId: UUID,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PaymentPendingResponse(
    @get:JsonProperty("payment_id") val paymentId: UUID,
    val status: String,
    @get:JsonProperty("order_id") val orderId: UUID?,
)

data class PaymentResponse(
    @get:JsonProperty("payment_id") val paymentId: UUID,
    val method: String,
    val amount: BigDecimal,
    val status: String,
)

data class OrderResponse(
    @get:JsonProperty("order_id") val orderId: UUID,
    val status: String,
    @get:JsonProperty("total_amount") val totalAmount: BigDecimal,
)

data class HoldCheckoutResponse(
    @get:JsonProperty("hold_id") val holdId: UUID,
    val status: String,
    @get:JsonProperty("expires_at") val expiresAt: Instant,
    @get:JsonProperty("total_amount") val totalAmount: BigDecimal,
    val payments: List<PaymentResponse>,
    val order: OrderResponse?,
)

fun CheckoutStatus.toResponse() = HoldCheckoutResponse(
    hold.id, hold.status, hold.expiresAt, hold.totalAmount,
    payments.map { PaymentResponse(it.id, it.method.name, it.amount, it.status.name) },
    order?.let { OrderResponse(it.id, it.status.name, it.totalAmount) },
)
