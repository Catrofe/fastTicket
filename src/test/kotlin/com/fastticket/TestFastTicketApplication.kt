package com.fastticket

import com.fastticket.support.TestcontainersConfiguration
import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
    fromApplication<FastTicketApplication>().with(TestcontainersConfiguration::class).run(*args)
}
