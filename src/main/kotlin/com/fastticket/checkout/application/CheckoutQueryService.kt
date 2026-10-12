package com.fastticket.checkout.application

import com.fastticket.checkout.domain.Order
import com.fastticket.checkout.domain.OrderRepository
import com.fastticket.checkout.domain.Payment
import com.fastticket.checkout.domain.PaymentRepository
import com.fastticket.inventory.HoldView
import com.fastticket.inventory.InventoryFacade
import com.fastticket.shared.validation.Cpf
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class CheckoutStatus(val hold: HoldView, val payments: List<Payment>, val order: Order?)

@Service
class CheckoutQueryService(
    private val inventory: InventoryFacade,
    private val payments: PaymentRepository,
    private val orders: OrderRepository,
) {

    @Transactional(readOnly = true)
    fun status(holdId: UUID, cpf: Cpf): CheckoutStatus {
        val hold = inventory.getHold(holdId, cpf)
        return CheckoutStatus(hold, payments.findByHoldId(holdId), orders.findByHoldId(holdId))
    }
}
