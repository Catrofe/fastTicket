package com.fastticket.checkout.application

import com.fastticket.checkout.domain.PaymentRepository
import com.fastticket.inventory.HoldExpiryVeto
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

@Component
class PendingPaymentExpiryVeto(
    private val payments: PaymentRepository,
    @Value("\${checkout.pending-grace}") private val grace: Duration,
) : HoldExpiryVeto {

    override fun vetoes(holdId: UUID): Boolean = payments.existsRecentPending(holdId, grace)
}
