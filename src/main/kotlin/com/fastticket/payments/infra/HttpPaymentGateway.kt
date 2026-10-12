package com.fastticket.payments.infra

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fastticket.payments.ChargeResult
import com.fastticket.payments.PaymentGateway
import com.fastticket.payments.RefundResult
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.http.HttpClient
import java.util.UUID

@Component
@EnableConfigurationProperties(GatewayProperties::class)
class HttpPaymentGateway(properties: GatewayProperties) : PaymentGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    private val client: RestClient = RestClient.builder()
        .baseUrl(properties.baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(properties.connectTimeout).build(),
            ).apply { setReadTimeout(properties.readTimeout) },
        )
        .defaultHeaders { if (properties.apiKey.isNotBlank()) it.setBearerAuth(properties.apiKey) }
        .build()

    override fun charge(reference: UUID, amount: BigDecimal, token: String): ChargeResult =
        safely("charge", reference, ChargeResult.Unknown) {
            client.post().uri("/charges")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("reference" to reference, "amount" to amount, "token" to token))
                .exchange { _, res -> toChargeResult(res.statusCode.value()) { res.bodyTo(GatewayBody::class.java) } }
        }

    override fun findCharge(reference: UUID): ChargeResult =
        safely("findCharge", reference, ChargeResult.Unknown) {
            client.get().uri("/charges/{ref}", reference)
                .exchange { _, res -> toChargeResult(res.statusCode.value()) { res.bodyTo(GatewayBody::class.java) } }
        }

    override fun refund(reference: UUID, paymentReference: UUID, amount: BigDecimal): RefundResult =
        safely("refund", reference, RefundResult.Unknown) {
            client.post().uri("/refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("reference" to reference, "payment_reference" to paymentReference, "amount" to amount))
                .exchange { _, res ->
                    val body = if (res.statusCode.is2xxSuccessful) res.bodyTo(GatewayBody::class.java) else null
                    val ref = body?.gatewayRef
                    if (body?.status == "DONE" && !ref.isNullOrBlank()) RefundResult.Done(ref) else RefundResult.Unknown
                }
        }

    private fun toChargeResult(status: Int, readBody: () -> GatewayBody?): ChargeResult = when (status) {
        in 200..299 -> {
            val body = readBody()
            when (body?.status) {
                "APPROVED" -> body.gatewayRef?.takeIf { it.isNotBlank() }?.let { ChargeResult.Approved(it) }
                    ?: ChargeResult.Unknown
                "DECLINED" -> ChargeResult.Declined(body.reason?.takeIf { it.isNotBlank() } ?: "DECLINED")
                else -> ChargeResult.Unknown
            }
        }
        in 400..499 -> ChargeResult.Declined("GATEWAY_REJECTED_$status")
        else -> ChargeResult.Unknown
    }

    @Suppress("TooGenericExceptionCaught")
    private fun <T : Any> safely(operation: String, reference: UUID, fallback: T, block: () -> T?): T =
        try {
            block() ?: fallback
        } catch (e: Exception) {
            log.warn("gateway {} unresolved reference={} cause={}", operation, reference, e.javaClass.simpleName)
            fallback
        }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GatewayBody(
        @param:JsonProperty("status") val status: String? = null,
        @param:JsonProperty("gateway_ref") val gatewayRef: String? = null,
        @param:JsonProperty("reason") val reason: String? = null,
    )
}
