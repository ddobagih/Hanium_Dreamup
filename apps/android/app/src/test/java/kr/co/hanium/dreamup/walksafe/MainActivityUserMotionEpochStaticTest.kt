package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Main wiring only; UserMotionEpochBoundaryTest and UserMotionStepCounterEpochTest exercise the real reducers. */
class MainActivityUserMotionEpochStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test fun bindingUsesActualBootCountAndSeparateProcessTokenWithoutMovingTheSameOwnersBoundary() {
        val binding = MainActivityRefactorSource.function(source, "private fun bindPositioningUserMotionEpoch(")
        assertTrue(binding.contains("actorGeneration = binding.owner.gatewayGeneration"))
        assertTrue(binding.contains("walkEpoch = requireNotNull(scope.lifecycle).snapshot().epoch"))
        assertTrue(binding.contains("bootId = userMotionBootId"))
        assertTrue(source.contains("reportCooldownBootCount()?.let { \"android-boot-count:\$it\" }"))
        assertTrue(binding.contains("processInstanceId = USER_MOTION_PROCESS_INSTANCE_ID"))
        val previous = binding.indexOf("val previousOwner = binding.coordinator.currentUserMotionOwner()")
        val changed = binding.indexOf("val ownerChanged = previousOwner != owner")
        val begin = binding.indexOf("if (ownerChanged && !binding.coordinator.beginUserMotionEpoch(owner, notBeforeElapsedRealtimeMs)) return false")
        val same = binding.indexOf("if (!ownerChanged) return false")
        val boundary = binding.indexOf("binding.counterBoundaryEstablished = false")
        assertTrue(previous >= 0 && changed > previous && begin > changed)
        assertTrue(same > begin && boundary > same)
        assertFalse(binding.contains("coordinator.reset()"))
        assertTrue(binding.indexOf("pedestrianMotionTracker?.close()") < binding.indexOf("pedestrianMotionTracker = null"))
        assertTrue(MainActivityRefactorSource.function(source, "private fun transitionWalkSession(").contains("transition.previous.epoch != transition.current.epoch"))
        assertTrue(function("private fun resetWalkTransientStateForNewWalk(").contains("bindPositioningUserMotionEpoch(binding, SystemClock.elapsedRealtime())"))
        val wiring = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(wiring.contains("override val processInstanceId get() = USER_MOTION_PROCESS_INSTANCE_ID"))
        assertTrue(wiring.contains("override val bootId get() = reportCooldownBootCount()"))
    }

    @Test fun queuedStepsCaptureOwnerBeforePostingAndSensorsCaptureOwnerBeforeRegistration() {
        val producer = source.substringAfter("onStepEvent = { event ->").substringBefore("earthOrientationTracker =")
        assertTrue(source.contains("onStepEvent = { event ->"))
        assertTrue(producer.indexOf("val motionOwner =") < producer.indexOf("runOnUiThread {"))
        assertTrue(producer.contains("handlePositioningStepEvent(event, binding, receivedAtMs, motionOwner)"))
        val step = MainActivityRefactorSource.function(source, "private fun handlePositioningStepEvent(")
        val gate = step.indexOf("isUserMotionInputCurrent(event.timestampMs, motionOwner)")
        assertTrue(gate >= 0 && gate < step.indexOf("pedestrianMotionTracker?.recordStep"))
        assertTrue(step.contains("expectedMotionOwner = motionOwner"))
        val sensor = MainActivityRefactorSource.function(source, "private fun startPositioningObservationSources(")
        assertTrue(sensor.indexOf("val motionOwner =") < sensor.indexOf("sources.pedestrianTracker motion@"))
        assertTrue(sensor.contains("binding.coordinator.currentUserMotionOwner() != motionOwner"))
        assertEquals(2, "expectedMotionOwner = motionOwner".toRegex().findAll(sensor).count())
        val androidSources = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/AndroidNavigationSources.kt").readText()
        val tracker = MainActivityRefactorSource.member(androidSources, "pedestrianTracker")
        assertTrue(tracker.contains("AndroidPedestrianMotionTracker(context, onDecision = onMotion)"))
        val sourcesFactory = MainActivityRefactorSource.factory(source, "createNavigationAndroidSources")
        assertTrue(sourcesFactory.contains("AndroidNavigationSources("))
        assertTrue(sourcesFactory.contains("context = this,"))
        assertTrue(MainActivityRefactorSource.factory(source, "createNavigationSessions").contains("sources = { createNavigationAndroidSources() }"))
        val positioning = MainActivityRefactorSource.composition(source, "positioningSessionController")
        assertTrue(positioning.contains("val sources = positions.sources()"))
        assertTrue(positioning.contains("PositioningSessionController(run, scope, sources, mounting,"))
    }

    @Test fun rawGpsIsGatedBeforeCachingAndOldSnapshotsCannotOverwriteCurrentStationaryState() {
        val gps = NavigationPolicySource.heading(source)
        val gate = gps.indexOf("isUserMotionInputCurrent(elapsedRealtimeMs, motionOwner)")
        assertTrue(gate >= 0 && gate < gps.indexOf("latestGpsMotionInputs = inputs"))
        assertTrue(gps.contains("expectedMotionOwner = motionOwner"))
        val apply = MainActivityRefactorSource.function(source, "private fun applyUserMotionState(")
        assertTrue(apply.indexOf("snapshot.owner != positioningCoordinator.currentUserMotionOwner()") < apply.indexOf("latestPositionStationaryState ="))
        assertTrue(apply.contains("UserMotionStateReason.OWNER_MISMATCH"))
        assertTrue(apply.contains("UserMotionStateReason.GENERATION_MISMATCH"))
    }

    private fun function(marker: String): String {
        val start = source.indexOf(marker)
        require(start >= 0)
        var depth = 0
        for (index in source.indexOf('{', start) until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(start, index + 1)
            }
        }
        error("missing function: $marker")
    }
}
