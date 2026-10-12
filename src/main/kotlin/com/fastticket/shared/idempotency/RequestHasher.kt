package com.fastticket.shared.idempotency

import com.fastticket.shared.validation.Cpf
import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component
class RequestHasher {

    fun hash(cpf: Cpf, body: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${cpf.digits}\n$body".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
