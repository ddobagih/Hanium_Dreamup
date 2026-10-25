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
        val acquisition = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteAcquisitionController.kt").readText()
        val archive = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteArchiveController.kt").readText()
        MainActivityRefactorSource.composition(source, "routeAcquisitionController")
        MainActivityRefactorSource.composition(source, "routeArchiveController")
        listOf(source, acquisition, archive).forEach { owner ->
            assertFalse(Regex("""[Ss]napshot\??\.route\b""").containsMatchIn(owner))
            assertFalse(Regex("""[Ss]napshot\??\.destination\b""").containsMatchIn(owner))
        }
        assertEquals(1, Regex("""routeNavigator\.setRoute\(""").findAll(acquisition).count())
        assertTrue(Regex("""routeNavigator\.setRoute\(\s*route,\s*destination = destination,""").containsMatchIn(acquisition))
        val startup = MainActivityRefactorSource.member(archive, "initialize")
        assertTrue(startup.contains("lane.attach { factory().also { routeSnapshotStore = it } }"))
        assertTrue(startup.contains("result?.metadata?.let"))
        assertTrue(startup.contains("scheduleEncryptedRouteSnapshotExpiry("))
        assertFalse(startup.contains("setRoute("))
        assertFalse(startup.contains("requestRoute("))
        assertFalse(startup.contains("currentDestination"))
    }
}
