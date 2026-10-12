package com.fastticket.shared.idempotency

import com.fastticket.shared.error.ApiException
import com.fastticket.shared.error.ErrorCodes
import com.fastticket.support.InvariantsExtension
import com.fastticket.support.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@ExtendWith(InvariantsExtension::class)
class IdempotencyStoreTest @Autowired constructor(
    private val store: IdempotencyStore,
    private val tx: TransactionTemplate,
    private val jdbc: JdbcClient,
) {

    @AfterEach
    fun clean() {
        jdbc.sql("DELETE FROM idempotency_key").update()
    }

    @Test
    fun `first claim is acquired and replay returns the stored response`() {
        val resource = UUID.randomUUID()
        tx.executeWithoutResult {
            assertEquals(IdempotencyClaim.Acquired, store.claim("k1", "holds", "h1"))
            store.complete("k1", "holds", 201, """{"id":"x"}""", resource)
        }

        val replay = tx.execute { store.claim("k1", "holds", "h1") }

        assertIs<IdempotencyClaim.Replay>(replay)
        assertEquals(201, replay.responseStatus)
        assertTrue(replay.responseBody!!.contains("\"id\""))
        assertEquals(resource, replay.resourceId)
    }

    @Test
    fun `same key with a different hash is rejected without leaking the response`() {
        tx.executeWithoutResult {
            store.claim("k2", "holds", "hash-of-cpf-a")
            store.complete("k2", "holds", 201, """{"secret":true}""", null)
        }

        val e = assertFailsWith<ApiException> { tx.execute { store.claim("k2", "holds", "hash-of-cpf-b") } }

        assertEquals(ErrorCodes.IDEMPOTENCY_KEY_REUSED, e.code)
        assertEquals(422, e.status.value())
        assertTrue(!e.message!!.contains("secret"))
    }

    @Test
    fun `same key in another scope is independent`() {
        tx.executeWithoutResult { assertEquals(IdempotencyClaim.Acquired, store.claim("k3", "holds", "h")) }
        tx.executeWithoutResult { assertEquals(IdempotencyClaim.Acquired, store.claim("k3", "checkout", "h")) }
    }

    @Test
    fun `second concurrent claim waits for the first commit and reads its response`() {
        val firstClaimed = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit {
                tx.executeWithoutResult {
                    assertEquals(IdempotencyClaim.Acquired, store.claim("k4", "holds", "h"))
                    firstClaimed.countDown()
                    releaseFirst.await(10, TimeUnit.SECONDS)
                    store.complete("k4", "holds", 201, """{"id":"winner"}""", null)
                }
            }
            firstClaimed.await(10, TimeUnit.SECONDS)
            val second = pool.submit<IdempotencyClaim> { tx.execute { store.claim("k4", "holds", "h") }!! }

            Thread.sleep(500)
            assertTrue(!second.isDone, "second claim must block until the first commits")
            releaseFirst.countDown()
            first.get(10, TimeUnit.SECONDS)

            val replay = second.get(10, TimeUnit.SECONDS)
            assertIs<IdempotencyClaim.Replay>(replay)
            assertTrue(replay.responseBody!!.contains("winner"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `claim after a rolled back first attempt is acquired`() {
        runCatching {
            tx.executeWithoutResult {
                store.claim("k5", "holds", "h")
                error("boom")
            }
        }

        tx.executeWithoutResult { assertEquals(IdempotencyClaim.Acquired, store.claim("k5", "holds", "h")) }
    }
}
