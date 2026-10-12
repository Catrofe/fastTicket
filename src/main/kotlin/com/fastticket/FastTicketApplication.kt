package com.fastticket

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class FastTicketApplication

fun main(args: Array<String>) {
    runApplication<FastTicketApplication>(*args)
}
