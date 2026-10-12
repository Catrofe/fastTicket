package com.fastticket.shared.idempotency

import com.fastticket.shared.error.ApiException
import com.fastticket.shared.error.ErrorCodes
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class IdempotencyStore(private val jdbc: JdbcClient) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun claim(key: String, scope: String, requestHash: String): IdempotencyClaim {
        val inserted = jdbc.sql(
            """
            INSERT INTO idempotency_key (key, scope, request_hash, status)
            VALUES (:key, :scope, :hash, 'IN_PROGRESS')
            ON CONFLICT (key, scope) DO NOTHING
            """.trimIndent(),
        ).param("key", key).param("scope", scope).param("hash", requestHash).update()
        if (inserted == 1) return IdempotencyClaim.Acquired

        val existing = jdbc.sql(
            """
            SELECT request_hash, status, response_status, response_body::text AS response_body, resource_id
            FROM idempotency_key WHERE key = :key AND scope = :scope
            """.trimIndent(),
        ).param("key", key).param("scope", scope).query { rs, _ ->
            Stored(
                rs.getString("request_hash"),
                rs.getString("status"),
                rs.getObject("response_status") as Int?,
                rs.getString("response_body"),
                rs.getObject("resource_id", UUID::class.java),
            )
        }.single()

        if (existing.requestHash != requestHash) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ErrorCodes.IDEMPOTENCY_KEY_REUSED,
                "Idempotency-Key was already used with a different request",
            )
        }
        return if (existing.status == "DONE" && existing.responseStatus != null) {
            IdempotencyClaim.Replay(existing.responseStatus, existing.responseBody, existing.resourceId)
        } else {
            IdempotencyClaim.InProgress
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun complete(key: String, scope: String, responseStatus: Int, responseBody: String?, resourceId: UUID?) {
        jdbc.sql(
            """
            UPDATE idempotency_key
            SET status = 'DONE', response_status = :status, response_body = CAST(:body AS jsonb), resource_id = :resource
            WHERE key = :key AND scope = :scope
            """.trimIndent(),
        ).param("status", responseStatus).param("body", responseBody).param("resource", resourceId)
            .param("key", key).param("scope", scope).update()
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun attachResource(key: String, scope: String, resourceId: UUID) {
        jdbc.sql("UPDATE idempotency_key SET resource_id = :resource WHERE key = :key AND scope = :scope AND status = 'IN_PROGRESS'")
            .param("resource", resourceId).param("key", key).param("scope", scope).update()
    }

    fun findResource(key: String, scope: String): UUID? =
        jdbc.sql("SELECT resource_id FROM idempotency_key WHERE key = :key AND scope = :scope")
            .param("key", key).param("scope", scope)
            .query { rs, _ -> rs.getObject("resource_id", UUID::class.java) }.optional().orElse(null)

    private data class Stored(
        val requestHash: String,
        val status: String,
        val responseStatus: Int?,
        val responseBody: String?,
        val resourceId: UUID?,
    )
}
