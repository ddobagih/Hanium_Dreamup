package kr.co.hanium.dreamup.walksafe.network

import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import java.io.File
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S03-22 follow-up: an abandoned start's lease can be the same lease a later start of the same
 * walk receives (ALREADY_ACTIVE), so the controller must report while it still tracks that walk.
 */
class GatewayWalkAuthorityTracksWalkTest {
    @Test
    fun pendingStartTracksOnlyItsOwnWalkUntilCancelled() {
        val controller = GatewayWalkAuthorityController(elapsedClock = { 1_000L })
        val epoch = WalkRuntimeEpoch("walk-a", 0L)
        assertFalse(controller.tracksWalk("walk-a"))
        val operation = controller.beginStart(epoch)
        assertNotNull(operation)
        assertTrue(controller.tracksWalk("walk-a"))
        assertFalse(controller.tracksWalk("walk-b"))
        controller.cancel(operation!!)
        assertFalse(controller.tracksWalk("walk-a"))
    }

    @Test
    fun abandonedLeaseEndIsSkippedWhileTheWalkIsStillTracked() {
        val source = sequenceOf(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
            File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        ).first { it.isFile }.readText()
        val function = MainActivityRefactorSource.function(source, "fun endAbandonedGatewayWalkStartLeaseBestEffort(").replace(Regex("\\s+"), "")
        val guard = function.indexOf("if(authority.tracksWalk(lease.walkId))return@execute")
        val end = function.indexOf("requests.end(")
        assertTrue(guard >= 0 && end > guard)
    }
}
