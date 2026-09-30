package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** S09-9 (originals/09.txt:34, 계측): every step/ZUPT snapshot's late handling reaches the field log. */
class MainActivityPositioningLateMotionStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun lateMotionIsRecordedForReplayedAndRefusedStepsAndZupts() {
        val apply = source.substringAfter("private fun applyPositioningMotionSnapshot(")
            .substringBefore("private fun handlePositioningUnavailable(")
        val record = apply.indexOf("snapshot.motionUpdate?.lateMotionEventFieldsOrNull()?.let { fields ->")
        val rejectedReturn = apply.indexOf("PedestrianMotionUpdateDisposition.HARD_REJECTED")
        assertTrue(record in 0 until rejectedReturn)
        assertTrue(apply.contains("fieldSessionLog.recordEvent(\"pdr_late_motion\", fields)"))
        // Step and ZUPT snapshots both go through this function.
        assertTrue(source.contains("zuptSnapshot?.let { applyPositioningMotionSnapshot(it, binding) }"))
        assertTrue(source.contains("applyPositioningMotionSnapshot(snapshot, binding)"))
        val allowed = File("src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/FieldSessionLog.kt").readText()
            .substringAfter("val ALLOWED_EVENT_FIELDS = setOf(").substringBefore("\n        )\n")
        listOf("\"result\"", "\"reason\"", "\"measurement_lag_ms\"", "\"replayed_event_count\"").forEach {
            assertTrue(it, allowed.contains(it))
        }
    }
}
