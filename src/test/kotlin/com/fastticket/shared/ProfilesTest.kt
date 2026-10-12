package com.fastticket.shared

import com.fastticket.support.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ProfilesTest @Autowired constructor(private val env: Environment) {

    @Test
    fun `combined activates api and worker`() {
        assertTrue(env.matchesProfiles("combined"))
        assertTrue(env.matchesProfiles("api"))
        assertTrue(env.matchesProfiles("worker"))
    }
}
