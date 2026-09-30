package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S14-10: the activity shows causes added to a stop it already owns, without repeating cleanup. */
class MainActivityRuntimeSafetyCauseUpdateStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun coordinatorCauseChangesReachTheOwnedStopStatusOnly() {
        val construction = source.substringAfter("private val walkRuntimeSafetyCoordinator = WalkRuntimeSafetyCoordinator(")
            .substringBefore("\n    )")
        assertTrue(construction.contains("onStopCausesChanged = ::onWalkRuntimeSafetyStopCausesChanged"))

        val handler = source.substringAfter("private fun onWalkRuntimeSafetyStopCausesChanged(")
            .substringBefore("private fun onWalkRuntimeSafetyStop(")
        // Posted (never run inline) so a stop delivery queued earlier sets the cleanup epoch first.
        assertTrue(handler.contains("reportCleanupCallbackHandler.post {"))
        assertFalse(handler.contains("runOnUiThread"))
        assertTrue(handler.contains("walkRuntimeSafetyCoordinator.latchedStop() ?: return@post"))
        assertTrue(handler.contains("if (walkRuntimeSafetyStopCleanupEpoch != stop.epoch) return@post"))
        assertTrue(handler.contains("\"navigation=runtime_safety_stopped reason=runtime_safety_coordinator:\$causes\""))
        assertFalse(handler.contains("performWalkSessionSafetyStopCleanup("))
        assertFalse(handler.contains("transitionWalkSession("))
        // The stop delivery re-reads the latch after owning the epoch, so a cause added
        // between the coordinator snapshot and this runnable is still shown.
        val delivery = source.substringAfter("private fun onWalkRuntimeSafetyStop(\n")
            .substringBefore("private fun performWalkSessionSafetyStopCleanup(")
        val epochSet = delivery.indexOf("walkRuntimeSafetyStopCleanupEpoch = stop.epoch")
        val reread = delivery.indexOf("walkRuntimeSafetyCoordinator.latchedStop()")
        assertTrue(epochSet >= 0 && reread > epochSet)
        assertTrue(delivery.contains("it.epoch == stop.epoch && it.causes != stop.causes"))
        assertTrue(delivery.contains("?.let(::onWalkRuntimeSafetyStopCausesChanged)"))
    }

    @Test
    fun causeUpdateStatusIsNotReadAsARouteFailure() {
        val presentation = source.substringAfter("val routeFailed = routeStatus.firstOrNull() in setOf(")
            .substringBefore(")")
        assertTrue(presentation.contains("\"navigation=safety_stopped\""))
        assertFalse(presentation.contains("navigation=runtime_safety_stopped"))
    }
}
