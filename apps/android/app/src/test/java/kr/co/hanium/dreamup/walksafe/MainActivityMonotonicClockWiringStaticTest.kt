package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S03-10/S03-3/S03-24: readiness age and walk-lease expiry are compared only against the clock
 * that produced them. MainActivity stamps readiness and lease deadlines with
 * SystemClock.elapsedRealtime(), so both consumers must be wired to the same clock.
 */
class MainActivityMonotonicClockWiringStaticTest {
    private val source = sourceFile("MainActivity.kt").readText()
    private val readinessSource = sourceFile("session/WalkSessionReadiness.kt").readText()

    @Test
    fun walkSessionReadinessAgeUsesTheElapsedRealtimeClockThatStampsIt() {
        assertTrue(
            source.contains(
                "walkSessionLifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)",
            ),
        )
        assertTrue(
            source.contains("collector.build(assessedAtElapsedRealtimeMs = SystemClock.elapsedRealtime())"),
        )
        assertFalse(source.contains("walkSessionLifecycle = WalkSessionLifecycle()"))
        assertFalse(source.contains("assessedAtEpochMs"))
        assertTrue(readinessSource.contains("val assessedAtElapsedRealtimeMs: Long"))
        assertFalse(readinessSource.contains("assessedAtEpochMs"))
    }

    @Test
    fun walkLeaseExpiryUsesTheDeadlineClockIncludingDeepSleep() {
        assertTrue(
            Regex(
                """private val gatewayWalkAuthorityController = GatewayWalkAuthorityController\(\s*""" +
                    """elapsedClock = SystemClock::elapsedRealtime,\s*\)""",
            ).containsMatchIn(source),
        )
        val leaseClockArguments = Regex("""localNowElapsedMs = ([^,\n]+)""")
            .findAll(source)
            .map { it.groupValues[1].trim() }
            .toList()
        assertEquals(2, leaseClockArguments.size)
        assertTrue(leaseClockArguments.all { it == "SystemClock.elapsedRealtime()" })
        assertTrue(source.contains("nowElapsedMs = { SystemClock.elapsedRealtime() }"))
    }

    private fun sourceFile(relative: String): File = listOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/$relative"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/$relative"),
        File("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/$relative"),
    ).first(File::isFile)
}
