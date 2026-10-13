package com.fastticket.concurrency

import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["inventory.allocator.strategy=conditional"])
class ConditionalAllocatorBenchmark : AbstractAllocatorBenchmark("conditional")

@TestPropertySource(properties = ["inventory.allocator.strategy=pessimistic"])
class PessimisticAllocatorBenchmark : AbstractAllocatorBenchmark("pessimistic")
