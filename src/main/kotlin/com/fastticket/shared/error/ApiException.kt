package com.fastticket.shared.error

import org.springframework.http.HttpStatus

open class ApiException(
    val status: HttpStatus,
    val code: String,
    val detail: String,
    val extras: Map<String, Any> = emptyMap(),
) : RuntimeException("$code: $detail")

object ErrorCodes {
    const val SEATS_TAKEN = "SEATS_TAKEN"
    const val ACTIVE_HOLD_EXISTS = "ACTIVE_HOLD_EXISTS"
    const val TICKET_LIMIT_EXCEEDED = "TICKET_LIMIT_EXCEEDED"
    const val HOLD_EXPIRED = "HOLD_EXPIRED"
    const val PAYMENT_DECLINED = "PAYMENT_DECLINED"
    const val PAYMENT_IN_PROGRESS = "PAYMENT_IN_PROGRESS"
    const val PAYMENT_APPROVED_HOLD_EXPIRED = "PAYMENT_APPROVED_HOLD_EXPIRED"
    const val IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED"
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
}

class ValidationException(detail: String) :
    ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.VALIDATION_ERROR, detail)
