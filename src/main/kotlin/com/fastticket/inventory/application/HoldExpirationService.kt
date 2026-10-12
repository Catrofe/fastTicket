package com.fastticket.inventory.application

import com.fastticket.inventory.HoldExpiryVeto
import com.fastticket.inventory.domain.BuyerEventRepository
import com.fastticket.inventory.domain.HoldRepository
import com.fastticket.inventory.domain.HoldStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Service
class HoldExpirationService(
    private val holds: HoldRepository,
    private val buyers: BuyerEventRepository,
    private val releaser: HoldReleaser,
    private val vetoes: List<HoldExpiryVeto>,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    fun findDue(limit: Int): List<UUID> = holds.findDueIds(limit)

    fun expireAll(holdIds: List<UUID>): Int = holdIds.count { id ->
        try {
            tx.execute { expire(id) } == true
        } catch (e: Exception) {
            log.warn("hold expiration failed id={}", id, e)
            false
        }
    }

    private fun expire(id: UUID): Boolean {
        val candidate = holds.findById(id)?.takeIf { it.status == HoldStatus.ACTIVE } ?: return false
        if (!buyers.tryLock(candidate.buyerCpf, candidate.eventId)) return false
        val due = holds.lockDueById(id) ?: return false
        if (vetoes.any { it.vetoes(due.id) }) return false
        val released = releaser.release(due.id, HoldStatus.EXPIRED)
        if (released) log.info("hold expired id={}", id)
        return released
    }
}
