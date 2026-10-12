package com.fastticket.support

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.junit.jupiter.SpringExtension

class InvariantsExtension : AfterEachCallback {
    override fun afterEach(context: ExtensionContext) {
        val jdbc = SpringExtension.getApplicationContext(context).getBean(JdbcClient::class.java)
        InvariantChecker(jdbc).assertClean()
    }
}
