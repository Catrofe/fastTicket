package com.fastticket.inventory.domain

import com.fastticket.shared.validation.Cpf
import java.util.UUID

interface HoldRepository {
    fun insert(hold: Hold): Hold
    fun findById(id: UUID): Hold?
    fun lockById(id: UUID): Hold?
    fun lockDueById(id: UUID): Hold?
    fun findActive(cpf: Cpf, eventId: UUID): Hold?
    fun findDueIds(limit: Int): List<UUID>
    fun transition(id: UUID, from: HoldStatus, to: HoldStatus): Boolean
}

interface BuyerEventRepository {
    fun increment(cpf: Cpf, eventId: UUID, count: Int): Boolean
    fun decrement(cpf: Cpf, eventId: UUID, count: Int): Boolean
    fun lock(cpf: Cpf, eventId: UUID)
    fun tryLock(cpf: Cpf, eventId: UUID): Boolean
}

interface SeatAllocationRepository {
    fun release(holdId: UUID): Int
    fun confirm(holdId: UUID): Int
}
