package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S14-47 nullable resource evidence wiring; not a device query or immediate-cleanup proof. */
class MainActivityResourceSafetyObservationStaticTest {
    private val source = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first { it.isFile }.readText()

    @Test
    fun runtimeObservationKeepsUnknownResourcesAndOriginalQueryTimeThroughTheMapper() {
        val binding = MainActivityRefactorSource.composition(source, "startupResourceSafetyAdmission")
        val observe = MainActivityRefactorSource.function(source, "private fun observeWalkRuntimeResourceSafety(").replace(Regex("\\s+"), "")
        assertTrue(binding.contains("override fun resourceSnapshot() = walkSessionResourceProbe.snapshot()"))
        assertTrue(binding.contains("override fun currentRuntimeEpoch() = walkSessionLifecycle.currentRuntimeEpochOrNull()"))
        assertTrue(binding.contains("coordinator = { walkRuntimeSafetyCoordinator }"))
        assertTrue(binding.contains("clock = SystemClock::elapsedRealtime"))

        assertTrue(observe.contains("currentResources.toRuntimeSafetyObservation("))
        assertTrue(observe.contains("epoch=runtimeEpoch"))
        assertTrue(observe.contains("valcheckedAt=currentResources.checkedAtElapsedRealtimeMs"))
        assertTrue(observe.contains("checkedAt.size!=WalkSessionResourceQuery.entries.size"))
        assertTrue(observe.contains("checkedAt.values.any{it<0L||it>receivedAtMs}"))
        assertTrue(observe.contains("valobservedAtMs=checkedAt.values.minOrNull()?:returnfalse"))
        assertTrue(observe.contains("observedAtElapsedRealtimeMs=observedAtMs"))
        assertFalse("missing source time cannot become a new healthy proof", observe.contains("?:receivedAtMs"))
        assertTrue(observe.contains("receivedAtElapsedRealtimeMs=receivedAtMs"))
        listOf("batteryNotLow", "privateStorageAboveSystemLow", "thermalBelowCritical").forEach {
            assertFalse("unknown $it must not become false-critical", observe.contains("currentResources.$it==false"))
        }
        assertTrue(observe.contains("WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED"))
        assertTrue(observe.contains("WalkRuntimeSafetyDisposition.IGNORED_OBSERVATION"))
    }
}
