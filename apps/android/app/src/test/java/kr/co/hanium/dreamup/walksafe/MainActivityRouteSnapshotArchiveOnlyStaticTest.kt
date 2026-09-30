package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 07.txt #39: the encrypted snapshot keeps the first route of a walk. It is an archive, never a
 * source for installing or resuming the active route after reroute or process recovery.
 */
class MainActivityRouteSnapshotArchiveOnlyStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun loadedSnapshotsOnlyScheduleExpiryAndCheckTheWalkNeverInstallAGuidanceRoute() {
        assertFalse(Regex("""[Ss]napshot\??\.route\b""").containsMatchIn(source))
        assertFalse(Regex("""[Ss]napshot\??\.destination\b""").containsMatchIn(source))
        assertEquals(1, Regex("""routeNavigator\.setRoute\(""").findAll(source).count())
        assertTrue(Regex("""routeNavigator\.setRoute\(\s*route,\s*destination = destination,""").containsMatchIn(source))

        val startup = source.substringAfter("routeSnapshotStore.expiryMetadata(routeSnapshotNowEpochMs)")
            .substringBefore("accountDeletionIntentFence =")
        assertTrue(startup.contains("scheduleEncryptedRouteSnapshotExpiry("))
        assertFalse(startup.contains("setRoute("))
        assertFalse(startup.contains("requestRoute("))
        assertFalse(startup.contains("currentDestination"))
    }
}
