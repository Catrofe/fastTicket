package com.fastticket.checkout.api

import com.fastticket.checkout.application.CheckoutOutcome
import com.fastticket.checkout.application.CheckoutQueryService
import com.fastticket.checkout.application.CheckoutService
import com.fastticket.shared.error.ValidationException
import com.fastticket.shared.validation.BuyerCpf
import com.fastticket.shared.validation.Cpf
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

private const val MAX_KEY_LENGTH = 128

@RestController
class CheckoutController(
    private val checkout: CheckoutService,
    private val query: CheckoutQueryService,
) {

    @PostMapping("/holds/{id}/checkout")
    fun checkout(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @BuyerCpf cpf: Cpf,
        @Valid @RequestBody request: CheckoutRequest,
    ): ResponseEntity<Any> {
        val idempotencyKey = key?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_KEY_LENGTH }
            ?: throw ValidationException("Header Idempotency-Key is required (1 to $MAX_KEY_LENGTH characters)")
        return when (val outcome = checkout.checkout(cpf, idempotencyKey, id, request.method!!, request.token!!)) {
            is CheckoutOutcome.Confirmed -> ResponseEntity.status(HttpStatus.CREATED).body(OrderCreatedResponse(outcome.orderId))
            is CheckoutOutcome.Pending -> ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(PaymentPendingResponse(outcome.paymentId, outcome.status.name, outcome.orderId))
        }
    }

    @GetMapping("/holds/{id}/checkout")
    fun status(@PathVariable id: UUID, @BuyerCpf cpf: Cpf): HoldCheckoutResponse = query.status(id, cpf).toResponse()
}
