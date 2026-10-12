package com.fastticket.shared.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig
