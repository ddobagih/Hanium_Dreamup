package kr.co.hanium.dreamup.walksafe.network

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class HttpRetryAfterTest {
    @Test fun parsesDeltaAndStrictHttpDateAndCapsOverflow() {
        val now = Instant.parse("2026-10-11T00:00:00Z").toEpochMilli()
        assertEquals(120_000L, parseHttpRetryAfterMs("120", now))
        assertEquals(120_000L, parseHttpRetryAfterMs("Sun, 11 Oct 2026 00:02:00 GMT", now))
        assertEquals(0L, parseHttpRetryAfterMs("Sun, 11 Oct 2026 00:00:00 GMT", now))
        assertEquals(Long.MAX_VALUE / 2L, parseHttpRetryAfterMs("9".repeat(100), now))
        for (header in listOf("-1", "1.5", "Sun, 99 Oct 2026 00:02:00 GMT",
            "Sun, 11 Oct 2026 00:02:00 GMT suffix", "")) assertNull(header, parseHttpRetryAfterMs(header, now))
    }
}
