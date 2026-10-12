package com.fastticket.inventory.application

import com.fastticket.inventory.HoldItemView
import com.fastticket.inventory.HoldView
import com.fastticket.inventory.domain.Hold

internal fun Hold.toView() = HoldView(
    id, eventId, status.name, checkNotNull(expiresAt), totalAmount,
    items.map { HoldItemView(it.seatId, it.sectorId, it.unitPrice) },
)
