package com.fastticket.inventory

import java.util.UUID

fun interface HoldExpiryVeto {
    fun vetoes(holdId: UUID): Boolean
}
