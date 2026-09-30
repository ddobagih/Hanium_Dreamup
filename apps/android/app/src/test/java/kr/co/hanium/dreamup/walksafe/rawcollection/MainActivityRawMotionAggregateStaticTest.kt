package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-045: the aggregate is fed only after the ACTIVE/epoch check and with RAW consent. */
class MainActivityRawMotionAggregateStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun motionStatesAreAggregatedOnlyInsideTheRawAdmissionWindow() {
        val record = source.substringAfter("private fun recordRawCollectionDetectionMetadata(")
            .substringBefore("private fun revalidateRawCollectionRuntime(")
        // ADD-MOTION-045-F1: no publishing read at all; a read-only peek after the ACTIVE/epoch
        // check and only when RAW consent allows collection.
        assertTrue(!record.contains("currentUserMotion("))
        val activeCheck = record.indexOf("context.walk.state != WalkSessionState.ACTIVE")
        val consent = record.indexOf(
            "val motionCollectionAllowed = context.consentSession.currentConfirmationOrNull(\n" +
                "                setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION),\n" +
                "            ) != null",
        )
        val peek = record.indexOf("positioningCoordinator.peekUserMotion(SystemClock.elapsedRealtime())")
        val feed = record.indexOf("rawRuntimeWindow.recordMotion(")
        assertTrue(activeCheck >= 0)
        assertTrue(consent > activeCheck)
        assertTrue(feed > consent)
        assertTrue(peek > feed)
        assertTrue(record.substring(feed).startsWith(
            "rawRuntimeWindow.recordMotion(\n" +
                "                if (motionCollectionAllowed) {\n" +
                "                    runCatching { positioningCoordinator.peekUserMotion(SystemClock.elapsedRealtime()) }.getOrNull()\n" +
                "                } else {\n" +
                "                    null\n" +
                "                },\n" +
                "                collectionAllowed = motionCollectionAllowed,",
        ))
        // A pause, end, or withdrawal clears the window, which also drops the motion aggregate.
        val accumulator = File("src/main/java/kr/co/hanium/dreamup/walksafe/rawcollection/RawRuntimeWindowAccumulator.kt").readText()
        assertTrue(accumulator.substringAfter("fun resetAll()").contains("motion.reset()"))
        assertTrue(accumulator.contains("motion = motion.summary(),"))
    }
}
