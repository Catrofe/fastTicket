package com.fastticket.catalog

import com.fastticket.checkout.FixtureCheckoutTarget
import com.fastticket.pricing.FixturePricingTarget
import org.springframework.data.redis.FixtureRedisTemplate

class FixtureCatalogViolations {
    val checkout = FixtureCheckoutTarget()
    val pricing = FixturePricingTarget()
    val redis = FixtureRedisTemplate()
}
