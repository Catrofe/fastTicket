package com.fastticket.shared.idempotency

import com.fastticket.shared.validation.Cpf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RequestHasherTest {

    private val hasher = RequestHasher()
    private val a = Cpf.parseOrNull("52998224725")!!
    private val b = Cpf.parseOrNull("11144477735")!!

    @Test
    fun `is deterministic`() = assertEquals(hasher.hash(a, "{}"), hasher.hash(a, "{}"))

    @Test
    fun `depends on cpf and body`() {
        assertNotEquals(hasher.hash(a, "{}"), hasher.hash(b, "{}"))
        assertNotEquals(hasher.hash(a, "{}"), hasher.hash(a, "{\"x\":1}"))
    }
}
