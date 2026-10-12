package com.fastticket.payments.infra

import com.fastticket.payments.ChargeResult
import com.fastticket.payments.RefundResult
import com.fastticket.support.WireMockContainer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpPaymentGatewayTest {

    private val wiremock = WireMockContainer()
    private lateinit var gateway: HttpPaymentGateway

    @BeforeAll
    fun start() {
        wiremock.start()
        gateway = HttpPaymentGateway(
            GatewayProperties(
                baseUrl = wiremock.baseUrl(),
                apiKey = "test-key",
                connectTimeout = Duration.ofSeconds(1),
                readTimeout = Duration.ofSeconds(2),
            ),
        )
    }

    @AfterAll
    fun stop() = wiremock.stop()

    private val amount = BigDecimal("100.00")

    @Test
    fun `tok_ok is approved with the gateway reference`() {
        val ref = UUID.randomUUID()
        assertEquals(ChargeResult.Approved("gw-$ref"), gateway.charge(ref, amount, "tok_ok"))
    }

    @Test
    fun `tok_declined is declined with the reason`() {
        assertEquals(
            ChargeResult.Declined("INSUFFICIENT_FUNDS"),
            gateway.charge(UUID.randomUUID(), amount, "tok_declined"),
        )
    }

    @Test
    fun `tok_5xx is unknown`() {
        assertEquals(ChargeResult.Unknown, gateway.charge(UUID.randomUUID(), amount, "tok_5xx"))
    }

    @Test
    fun `tok_timeout is unknown after the read timeout`() {
        val started = System.nanoTime()
        assertEquals(ChargeResult.Unknown, gateway.charge(UUID.randomUUID(), amount, "tok_timeout"))
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5))
    }

    @Test
    fun `unmatched token yields 404 and is declined`() {
        assertIs<ChargeResult.Declined>(gateway.charge(UUID.randomUUID(), amount, "tok_other"))
    }

    @Test
    fun `unreachable gateway is unknown`() {
        val down = HttpPaymentGateway(GatewayProperties(baseUrl = "http://127.0.0.1:1"))
        assertEquals(ChargeResult.Unknown, down.charge(UUID.randomUUID(), amount, "tok_ok"))
    }

    @Test
    fun `findCharge and refund map approved and done`() {
        val ref = UUID.randomUUID()
        assertEquals(ChargeResult.Approved("gw-$ref"), gateway.findCharge(ref))
        assertEquals(RefundResult.Done("gwr-$ref"), gateway.refund(ref, UUID.randomUUID(), amount))
    }
}
