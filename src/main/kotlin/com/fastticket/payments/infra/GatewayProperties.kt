package com.fastticket.payments.infra

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("gateway")
data class GatewayProperties(
    val baseUrl: String,
    val apiKey: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(1),
    val readTimeout: Duration = Duration.ofSeconds(3),
)
