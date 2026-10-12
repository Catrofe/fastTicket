package com.fastticket.checkout

import com.fastticket.support.InvariantsExtension
import com.fastticket.support.ScriptedGateway
import com.fastticket.support.ScriptedGatewayConfiguration
import com.fastticket.support.TestcontainersConfiguration
import com.fastticket.support.TicketingApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class, ScriptedGatewayConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(InvariantsExtension::class)
class NoTransactionDuringGatewayTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var gateway: ScriptedGateway
    @Autowired lateinit var txManager: PlatformTransactionManager
    private lateinit var api: TicketingApi

    @BeforeEach
    fun setUp() {
        gateway.reset()
        api = TicketingApi(mvc, jdbc)
    }

    @Test
    fun `gateway is called without an active transaction for approved, declined and unknown outcomes`() {
        listOf("tok_ok", "tok_declined", "tok_timeout").forEach { token ->
            gateway.transactionActive.clear()
            api.checkout(api.newHold(), token)
            assertEquals(listOf(false), gateway.transactionActive.toList(), "token $token")
        }
    }

    @Test
    fun `probe detects an active transaction when the gateway is called inside one`() {
        TransactionTemplate(txManager).executeWithoutResult {
            gateway.charge(UUID.randomUUID(), BigDecimal.ONE, "tok_ok")
        }
        assertEquals(listOf(true), gateway.transactionActive.toList())
    }
}
