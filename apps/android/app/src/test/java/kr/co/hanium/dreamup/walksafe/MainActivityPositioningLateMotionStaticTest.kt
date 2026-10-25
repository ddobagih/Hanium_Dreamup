package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** S09-9 (originals/09.txt:34, 계측): every step/ZUPT snapshot's late handling reaches the field log. */
class MainActivityPositioningLateMotionStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun lateMotionIsRecordedForReplayedAndRefusedStepsAndZupts() {
        val apply = MainActivityRefactorSource.function(source, "private fun applyPositioningMotionSnapshot(")
        val record = apply.indexOf("snapshot.motionUpdate?.lateMotionEventFieldsOrNull()?.let { fields ->")
        val rejectedReturn = apply.indexOf("PedestrianMotionUpdateDisposition.HARD_REJECTED")
        assertTrue(record in 0 until rejectedReturn)
        assertTrue(apply.contains("diagnostics.record(\"pdr_late_motion\", fields)"))
        // Step and ZUPT snapshots both go through this function.
        assertTrue(MainActivityRefactorSource.function(source, "private fun startPositioningObservationSources(").contains("zuptSnapshot?.let { applyPositioningMotionSnapshot(it, binding) }"))
        assertTrue(MainActivityRefactorSource.function(source, "private fun handlePositioningStepEvent(").contains("applyPositioningMotionSnapshot(snapshot, binding)"))
        val allowed = File("src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/FieldSessionLog.kt").readText()
            .substringAfter("val ALLOWED_EVENT_FIELDS = setOf(").substringBefore("\n        )\n")
        listOf("\"result\"", "\"reason\"", "\"measurement_lag_ms\"", "\"replayed_event_count\"").forEach {
            assertTrue(it, allowed.contains(it))
        }
    }
}
