package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADD-MOTION-045-F1 / w21 AM43-F2: only the navigation owner publishes motion snapshots. RAW
 * metadata, ActivityOriginal upload admission and the status line use the read-only peek, and the
 * RAW path reads it only after the ACTIVE/epoch and RAW consent checks.
 */
class MainActivityMotionPeekStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun rawMetadataPeeksOnlyAfterAdmissionAndConsent() {
        val record = source.substringAfter("private fun recordRawCollectionDetectionMetadata(")
            .substringBefore("private fun revalidateRawCollectionRuntime(")
        assertFalse(record.contains("currentUserMotion("))
        val task = record.indexOf("executeRawCollectionTask {")
        val activeCheck = record.indexOf("context.walk.state != WalkSessionState.ACTIVE")
        val epochCheck = record.indexOf("context.walk.epoch != expectedWalkEpoch")
        val consent = record.indexOf("val motionCollectionAllowed = context.consentSession.currentConfirmationOrNull(")
        val peek = record.indexOf("positioningCoordinator.peekUserMotion(SystemClock.elapsedRealtime())")
        assertTrue(task in 0 until activeCheck)
        assertTrue(epochCheck > activeCheck)
        assertTrue(consent > epochCheck)
        assertTrue(peek > consent)
        assertEquals(1, Regex("""peekUserMotion\(""").findAll(record).count())
        val guarded = record.substring(consent, peek)
        assertTrue(guarded.contains("if (motionCollectionAllowed) {"))
    }

    @Test
    fun uploadAdmissionNoLongerReadsTheEstimatorAndStatusTextNeverPublishes() {
        // Owner decision 3(b) / ADD-MOTION-043: transfers start only after the walk, so no transfer
        // admission consults the motion estimator any more.
        assertFalse(source.contains("transferStationaryEvidenceConfirmedAt"))
        val status = functionBlock("private fun currentUserMotionStatusText()")
        assertTrue(status.contains("positioningCoordinator.peekUserMotion(nowMs)"))
        assertFalse(status.contains(".currentUserMotion("))
    }

    @Test
    fun theCoordinatorPeekDelegatesToTheNonPublishingEstimatorRead() {
        val coordinator = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/positioning/PositioningCoordinator.kt",
        ).readText()
        assertTrue(coordinator.contains(
            "fun peekUserMotion(nowElapsedRealtimeMs: Long): UserMotionStateSnapshot =\n" +
                "        userMotionEstimator.peek(nowElapsedRealtimeMs)",
        ))
        val estimator = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/positioning/UserMotionStateEstimator.kt",
        ).readText()
        val publish = estimator.substringAfter("        if (publish) {\n").substringBefore("        }\n")
        assertTrue(publish.contains("lastPublishedAtMs = nowMs"))
        assertTrue(publish.contains("stateSinceMs = stateSince"))
        assertTrue(publish.contains("publishedMovement = movement"))
        assertTrue(estimator.substringAfter("fun peek(nowMs: Long)").substringBefore("private fun evaluate(")
            .contains("evaluate(nowMs, publish = false)"))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val next = Regex("""\n    (private |internal |override |@VisibleForTesting|/\*\*|fun )""")
            .find(source, start + signature.length)?.range?.first ?: source.length
        return source.substring(start, next)
    }
}
