package com.fastticket.shared.idempotency

import java.util.UUID

sealed interface IdempotencyClaim {
    data object Acquired : IdempotencyClaim
    data object InProgress : IdempotencyClaim
    data class Replay(val responseStatus: Int, val responseBody: String?, val resourceId: UUID?) : IdempotencyClaim
}
