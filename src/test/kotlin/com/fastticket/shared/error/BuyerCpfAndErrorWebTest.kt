package com.fastticket.shared.error

import com.fastticket.shared.validation.BuyerCpf
import com.fastticket.shared.validation.BuyerCpfArgumentResolver
import com.fastticket.shared.validation.Cpf
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

class BuyerCpfAndErrorWebTest {

    @RestController
    class Probe {
        @GetMapping("/probe")
        fun probe(@BuyerCpf cpf: String): String = cpf.takeLast(2)

        @GetMapping("/conflict")
        fun conflict(): String = throw ApiException(HttpStatus.CONFLICT, ErrorCodes.SEATS_TAKEN, "taken")
    }

    private val mvc = MockMvcBuilders.standaloneSetup(Probe())
        .setCustomArgumentResolvers(BuyerCpfArgumentResolver())
        .setControllerAdvice(ProblemDetailHandler())
        .build()

    @Test
    fun `valid header is resolved`() {
        mvc.perform(get("/probe").header("X-Buyer-Cpf", "529.982.247-25"))
            .andExpect(status().isOk).andExpect(content().string("25"))
    }

    @Test
    fun `missing or invalid header yields 422 VALIDATION_ERROR`() {
        mvc.perform(get("/probe"))
            .andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        mvc.perform(get("/probe").header("X-Buyer-Cpf", "11111111111"))
            .andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `api exception becomes problem detail with code`() {
        mvc.perform(get("/conflict"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SEATS_TAKEN"))
            .andExpect(jsonPath("$.detail").value("taken"))
    }
}
