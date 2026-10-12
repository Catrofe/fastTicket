package com.fastticket.shared.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CpfTest {

    @Test
    fun `accepts valid cpf with or without punctuation`() {
        assertEquals("52998224725", Cpf.parseOrNull("529.982.247-25")?.digits)
        assertNotNull(Cpf.parseOrNull("52998224725"))
    }

    @Test
    fun `rejects bad check digits, repeated digits, wrong length and null`() {
        listOf("52998224724", "11111111111", "123", "", "529982247256", null)
            .forEach { assertNull(Cpf.parseOrNull(it), "should reject $it") }
    }

    @Test
    fun `toString never exposes the full number`() {
        val text = Cpf.parseOrNull("52998224725").toString()
        assertEquals("***.***.***-25", text)
    }
}
