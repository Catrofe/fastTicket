package com.fastticket.support

import com.fastticket.payments.ChargeResult
import com.fastticket.payments.PaymentGateway
import com.fastticket.payments.RefundResult
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class ScriptedGateway : PaymentGateway {
    val transactionActive = CopyOnWriteArrayList<Boolean>()

    @Volatile
    var onCharge: (UUID, String) -> ChargeResult = { ref, token -> defaultResult(ref, token) }

    fun reset() {
        transactionActive.clear()
        onCharge = { ref, token -> defaultResult(ref, token) }
    }

    override fun charge(reference: UUID, amount: BigDecimal, token: String): ChargeResult {
        transactionActive += org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
        return onCharge(reference, token)
    }

    override fun findCharge(reference: UUID): ChargeResult = ChargeResult.Unknown
    override fun refund(reference: UUID, paymentReference: UUID, amount: BigDecimal): RefundResult = RefundResult.Unknown

    private fun defaultResult(ref: UUID, token: String): ChargeResult = when (token) {
        "tok_ok" -> ChargeResult.Approved("gw-$ref")
        "tok_declined" -> ChargeResult.Declined("insufficient_funds")
        else -> ChargeResult.Unknown
    }
}

@TestConfiguration(proxyBeanMethods = false)
class ScriptedGatewayConfiguration {
    @Bean
    @Primary
    fun scriptedGateway(): ScriptedGateway = ScriptedGateway()
}
