package kr.co.hanium.dreamup.walksafe.security

import android.icu.text.IDNA
import java.util.Locale

/** Same non-transitional UTS #46 and URL numeric-host identity as the account Gateway. */
internal object AccountEmailNormalizer {
    private val local = Regex("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*$")
    private val domainLabel = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val idna by lazy {
        IDNA.getUTS46Instance(IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ or IDNA.NONTRANSITIONAL_TO_ASCII)
    }

    fun canonicalOrNull(value: String): String? {
        if (value != value.trim() || value.length !in 3..254 || value.count { it == '@' } != 1) return null
        val name = value.substringBefore('@')
        val rawDomain = value.substringAfter('@')
        if (name.length !in 1..64 || !local.matches(name) || rawDomain.endsWith('.') ||
            rawDomain.any { it <= ' ' || it == '\u007f' || it in "%/\\?#:" }) return null
        val info = IDNA.Info()
        val ascii = runCatching { idna.nameToASCII(rawDomain, StringBuilder(), info).toString() }
            .getOrNull() ?: return null
        if (info.hasErrors() || ascii.endsWith('.')) return null
        val domain = numericHostOrNull(ascii.lowercase(Locale.ROOT)) ?: return null
        val labels = domain.split('.')
        if (domain.length !in 1..253 || name.length + domain.length + 1 > 254 || labels.size < 2 ||
            labels.any { !domainLabel.matches(it) || (!it.startsWith("xn--") && it.drop(2).take(2) == "--") }
        ) return null
        return "$name@$domain"
    }

    // The Gateway already uses Node URL host rules. Preserve existing numeric account identifiers.
    private fun numericHostOrNull(domain: String): String? {
        val parts = domain.split('.')
        val last = parts.last()
        if (!last.all { it in '0'..'9' } && !Regex("0x[0-9a-f]*").matches(last)) return domain
        if (parts.size > 4) return null
        val numbers = parts.map { part ->
            val (digits, radix) = when {
                part.startsWith("0x") -> part.drop(2) to 16
                part.length > 1 && part.startsWith('0') -> part.drop(1) to 8
                else -> part to 10
            }
            if (digits.isEmpty()) 0L else digits.toLongOrNull(radix) ?: return null
        }
        if (numbers.dropLast(1).any { it !in 0..255 } ||
            numbers.last() !in 0 until (1L shl (8 * (5 - numbers.size)))) return null
        val address = numbers.dropLast(1).foldIndexed(numbers.last()) { index, value, number ->
            value + (number shl (8 * (3 - index)))
        }
        return (3 downTo 0).joinToString(".") { ((address shr (8 * it)) and 255).toString() }
    }
}
