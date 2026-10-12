package com.fastticket.inventory.api

import com.fastticket.inventory.InventoryFacade
import com.fastticket.shared.error.ValidationException
import com.fastticket.shared.validation.BuyerCpf
import com.fastticket.shared.validation.Cpf
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

private const val MAX_KEY_LENGTH = 128

@RestController
class HoldController(private val inventory: InventoryFacade) {

    @PostMapping("/holds")
    fun create(
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @BuyerCpf cpf: Cpf,
        @Valid @RequestBody request: CreateHoldRequest,
    ): ResponseEntity<HoldResponse> {
        val idempotencyKey = key?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_KEY_LENGTH }
            ?: throw ValidationException("Header Idempotency-Key is required (1 to $MAX_KEY_LENGTH characters)")
        val created = inventory.createHold(cpf, idempotencyKey, request.eventId!!, request.seatIds!!.map { it!! })
        return ResponseEntity.status(HttpStatus.CREATED).body(created.hold.toResponse())
    }

    @GetMapping("/holds/{id}")
    fun get(@PathVariable id: UUID, @BuyerCpf cpf: Cpf): HoldResponse = inventory.getHold(id, cpf).toResponse()

    @DeleteMapping("/holds/{id}")
    fun release(@PathVariable id: UUID, @BuyerCpf cpf: Cpf): HoldResponse = inventory.release(id, cpf).toResponse()
}
