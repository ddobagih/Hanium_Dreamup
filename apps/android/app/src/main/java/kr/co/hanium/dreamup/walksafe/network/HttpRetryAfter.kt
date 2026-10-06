package kr.co.hanium.dreamup.walksafe.network

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal fun parseHttpRetryAfterMs(
    header: String?,
    nowEpochMs: Long = System.currentTimeMillis(),
): Long? {
    val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.all { it in '0'..'9' }) {
        val seconds = value.toLongOrNull() ?: return MAX_SAFE_RETRY_DELAY_MS
        return if (seconds > MAX_SAFE_RETRY_DELAY_MS / 1_000L) {
            MAX_SAFE_RETRY_DELAY_MS
        } else {
            seconds * 1_000L
        }
    }
    val position = ParsePosition(0)
    val deadlineEpochMs = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("GMT")
        }
        .parse(value, position)?.time ?: return null
    if (position.index != value.length) return null
    if (deadlineEpochMs <= nowEpochMs) return 0L
    val delayMs = if (nowEpochMs < 0L && deadlineEpochMs > Long.MAX_VALUE + nowEpochMs) {
        Long.MAX_VALUE
    } else {
        deadlineEpochMs - nowEpochMs
    }
    return delayMs.coerceAtMost(MAX_SAFE_RETRY_DELAY_MS)
}

private const val MAX_SAFE_RETRY_DELAY_MS = Long.MAX_VALUE / 2L
