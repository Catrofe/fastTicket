package com.fastticket.concurrency

import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["inventory.allocator.strategy=conditional", POOL_SIZE_PROPERTY])
class ConditionalAllocatorConcurrencyTest : AbstractAllocatorConcurrencyTest()
