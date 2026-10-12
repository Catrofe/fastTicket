package com.fastticket.jobs

import com.fastticket.inventory.InventoryFacade
import com.fastticket.shared.config.Profiles
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@Profile(Profiles.WORKER, Profiles.COMBINED)
@ConditionalOnProperty("jobs.enabled", havingValue = "true", matchIfMissing = true)
class HoldExpirationJob(
    private val inventory: InventoryFacade,
    @Value("\${jobs.hold-expiration.batch}") private val batch: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${jobs.hold-expiration.interval}")
    fun run() {
        try {
            while (true) {
                val due = inventory.findDueHoldIds(batch)
                if (due.isEmpty()) return
                val expired = inventory.expireDueHolds(due)
                if (expired > 0) log.info("expired {} holds", expired)
                if (due.size < batch || expired == 0) return
            }
        } catch (e: Exception) {
            log.error("hold expiration run failed", e)
        }
    }
}
