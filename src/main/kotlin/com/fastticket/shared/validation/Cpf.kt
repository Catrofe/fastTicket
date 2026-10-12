package com.fastticket.shared.validation

@JvmInline
value class Cpf private constructor(val digits: String) {

    override fun toString(): String = "***.***.***-${digits.takeLast(2)}"

    companion object {
        fun parseOrNull(raw: String?): Cpf? {
            val digits = raw?.filter { it.isDigit() } ?: return null
            if (digits.length != 11 || digits.all { it == digits[0] }) return null
            if (check(digits, 9) != digits[9].digitToInt() || check(digits, 10) != digits[10].digitToInt()) return null
            return Cpf(digits)
        }

        private fun check(digits: String, length: Int): Int {
            val sum = (0 until length).sumOf { digits[it].digitToInt() * (length + 1 - it) }
            val rest = sum * 10 % 11
            return if (rest == 10) 0 else rest
        }
    }
}
