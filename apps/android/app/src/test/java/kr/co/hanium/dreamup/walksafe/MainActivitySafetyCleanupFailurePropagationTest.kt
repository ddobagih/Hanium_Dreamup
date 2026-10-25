package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Static production connections; native sensor/AR failures are not executed by these tests. */
class MainActivitySafetyCleanupFailurePropagationTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun stoppedEpochOwnershipIsRecordedBeforeResourceRebindingAndUiEffects() {
        val transition = MainActivityRefactorSource.function(source, "private fun transitionWalkSession(")
        val handled = transition.indexOf("lifecycle().handle(event, effects::beforePublication)")
        val owner = transition.indexOf("recordSafetyStopTransition(transition)")
        val resources = transition.indexOf("effects.onEpochChanged()")
        val ui = transition.indexOf("effects.hidePermissionDenial()")
        val binding = MainActivityRefactorSource.composition(source, "walkSessionTransitionController")
        assertTrue(binding.contains("lifecycle = { walkSessionLifecycle }"))
        assertTrue(binding.contains("walkStopDelivery.recordSafetyStopTransitionIfOwned(transition)"))
        assertTrue(binding.contains("bindPositioningUserMotionEpoch(binding, SystemClock.elapsedRealtime())"))
        assertTrue(binding.contains("permissionDenialPanel.visibility = View.GONE"))
        assertTrue(handled >= 0 && owner > handled)
        assertTrue("The stopped successor must remain owned if later sensor cleanup throws", owner < resources)
        assertTrue("UI failure must not lose the already stopped successor", owner < ui)
    }

    @Test
    fun locationStopFailureReachesTheSafetyCheckpointWithoutAbortingOrdinaryCleanup() {
        val outputs = MainActivityRefactorSource.function(source, "private fun cancelWalkSessionOutputsInternal(")
        val stage = outputs.substringAfter("val tailComplete =").substringBefore("stage(\"step_tracking\")")
        // Pending native Task is IN_PROGRESS; the actual synchronous tail and native result
        // are combined by the production async bridge instead of manufacturing a failure.
        val binding = MainActivityRefactorSource.composition(source, "walkSessionCleanupController")
        assertTrue(binding.contains("override fun locationCleanupSucceeded() = lastLocationCleanupFailure == null"))
        assertTrue(binding.contains("this@MainActivity.stopLocationUpdates()"))
        assertTrue(binding.contains("this@MainActivity.runLocationStopCleanupCheckpoint(checkpoints, onResult, stopTail)"))
        assertTrue(stage.contains("runLocationStopCleanupCheckpoint("))
        assertTrue(stage.contains("stopLocationUpdates()"))
        assertTrue(stage.contains("navigation.locationCleanupSucceeded()"))
        val bridge = MainActivityRefactorSource.function(source, "internal fun runLocationStopCleanupCheckpoint")
        assertTrue(bridge.contains("fusedLocationCancellations.runLocationStopCleanupCheckpoint("))
        val cancellationSource = ReportStaticSourceInspector.read(
            "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/location/FusedLocationCancellationRegistry.kt",
        )
        val checkpoint = ReportStaticSourceInspector.functionBlock(cancellationSource, "internal fun runLocationStopCleanupCheckpoint")
        assertTrue(checkpoint.contains("checkpoints.beginAsync(key)"))
        assertTrue(checkpoint.contains("awaitFusedLocationCancellation"))
        assertTrue(checkpoint.contains("tailSucceeded && removed"))
        assertTrue(checkpoint.contains("checkpoints.finishAsync(key, succeeded)"))
        val stopSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/safety/WalkRuntimeStopDeliveryController.kt").readText()
        val acknowledgement = MainActivityRefactorSource.member(stopSource, "onWalkRuntimeSafetyStop")
        assertTrue(MainActivityRefactorSource.property(source, "walkStopDelivery").contains("WalkRuntimeStopDeliveryController("))
        assertTrue(MainActivityRefactorSource.member(source, "onWalkRuntimeSafetyStop").contains("walkStopDelivery.onWalkRuntimeSafetyStop(stop, completion)"))
        assertTrue(acknowledgement.contains("cameraReleased == null || locationReleased == null"))
        assertTrue(acknowledgement.contains("synchronousComplete && cameraReleased == true && locationReleased == true"))
        val cleanup = MainActivityRefactorSource.function(source, "private fun stopLocationUpdatesInternal")
        assertTrue(cleanup.contains("return lastLocationCleanupFailure == null"))
    }

    @Test
    fun positionFieldStopFailureIsNotDiscardedByTheSafetyCleanupStage() {
        val outputs = MainActivityRefactorSource.function(source, "private fun cancelWalkSessionOutputsInternal(")
        val fieldStage = outputs.substringAfter("stage(\"position_field\") {").substringBefore("stage(\"location_stop\")")
        assertTrue(fieldStage.contains("val stopped = navigation.stopPositionFieldSession()"))
        assertTrue(fieldStage.contains("if (checkpoints != null) check(stopped)"))
        assertTrue(MainActivityRefactorSource.composition(source, "walkSessionCleanupController").contains("override fun stopPositionFieldSession() = stopActivePositionFieldSession()"))
    }
}
