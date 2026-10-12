package com.fastticket.shared.error

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProblemDetailHandler {

    @ExceptionHandler(ApiException::class)
    fun handle(e: ApiException): ResponseEntity<ProblemDetail> = problem(e.status, e.code, e.detail, e.extras)

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentNotValidException::class)
    fun handleMalformed(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, "Malformed or invalid request body")

    private fun problem(status: HttpStatus, code: String, detail: String, extras: Map<String, Any> = emptyMap()): ResponseEntity<ProblemDetail> {
        val body = ProblemDetail.forStatusAndDetail(status, detail)
        body.title = status.reasonPhrase
        body.setProperty("code", code)
        extras.forEach { (k, v) -> body.setProperty(k, v) }
        return ResponseEntity.status(status).body(body)
    }
}
