package com.fastticket.inventory

import com.fastticket.jobs.HoldExpirationJob
import com.fastticket.shared.validation.Cpf
import com.fastticket.support.InvariantsExtension
import com.fastticket.support.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["inventory.hold-ttl=2s", "jobs.hold-expiration.interval=1s"])
@ExtendWith(InvariantsExtension::class)
class HoldExpirationJobIntegrationTest {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var inventory: InventoryFacade

    @Test
    fun `abandoned hold is released within 30 seconds after expires_at`() {
        val eventId = UUID.randomUUID()
        val sectorId = UUID.randomUUID()
        val seatId = UUID.randomUUID()
        jdbc.sql("INSERT INTO event (id, name, starts_at, sales_open_at, status) VALUES (:id, 'E', now() + interval '30 day', now() - interval '1 hour', 'ON_SALE')")
            .param("id", eventId).update()
        jdbc.sql("INSERT INTO sector (id, event_id, name, kind, capacity, base_price) VALUES (:id, :e, 'S', 'NUMBERED', 1, 10)")
            .param("id", sectorId).param("e", eventId).update()
        jdbc.sql("INSERT INTO seat (id, sector_id, row_label, seat_number) VALUES (:id, :s, 'A', 1)")
            .param("id", seatId).param("s", sectorId).update()
        val cpf = Cpf.parseOrNull("52998224725")!!

        val hold = inventory.createHold(cpf, UUID.randomUUID().toString(), eventId, listOf(seatId)).hold
        val deadline = Instant.now().plus(Duration.ofSeconds(60))
        var status = "ACTIVE"
        while (status == "ACTIVE" && Instant.now().isBefore(deadline)) {
            Thread.sleep(250)
            status = jdbc.sql("SELECT status FROM hold WHERE id = :id").param("id", hold.id).query(String::class.java).single()
        }
        val releasedAfter = Duration.between(hold.expiresAt, Instant.now())
        assertEquals("EXPIRED", status)
        assertTrue(releasedAfter < Duration.ofSeconds(30), "released $releasedAfter after expires_at")
    }
}

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@ActiveProfiles("api")
class HoldExpirationJobApiProfileTest {

    @Autowired lateinit var ctx: ApplicationContext

    @Test
    fun `job is absent in api profile`() {
        assertEquals(0, ctx.getBeansOfType(HoldExpirationJob::class.java).size)
    }
}

@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["jobs.enabled=false"])
class HoldExpirationJobDisabledTest {

    @Autowired lateinit var ctx: ApplicationContext

    @Test
    fun `job is absent when jobs enabled is false`() {
        assertEquals(0, ctx.getBeansOfType(HoldExpirationJob::class.java).size)
    }
}
