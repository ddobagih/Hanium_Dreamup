package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * transfer-F3 (w24 review): the reason an earlier walk's unsealed segment was kept or deleted at
 * the next admission is counted (16-17 #13), and the post-walk batch writes the counters to the
 * internal field log at every exit (never to the user).
 */
class RawStaleSegmentOutcomeTest {
    @Test
    fun aProcessEndedWalksSegmentSealedAtTheNextAdmissionIsCounted() {
        val rig = PostWalkRawRig()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_1))
        // The process ends during walk 1: no ENDED ran; the next walk's first window admits a segment.
        rig.restartProcess()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_2))
        val counters = rig.coordinator.outcomeCountersSnapshot()
        assertEquals(1L, counters["segment_sealed_at_walk_end"])
        assertNull(counters["segment_discarded_at_walk_end"])
        assertEquals(listOf(NP_WALK_1), rig.store.readyManifests(NP_OWNER).map { it.walkSessionId })
    }

    @Test
    fun anUnsealableRemainderDeletedAtTheNextAdmissionIsCounted() {
        val rig = PostWalkRawRig()
        assertTrue(
            rig.store.open(
                collectionId = "123e4567-e89b-42d3-a456-426614174778",
                owner = NP_OWNER,
                walkSessionId = NP_WALK_1,
                consentReceiptSha256 = NP_CONSENT_SHA,
                capturedStartedAtEpochMs = rig.nowEpochMs - 1_000L,
                rawConsentGranted = true,
                walkState = RawWalkState.ACTIVE,
            ),
        )
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_2))
        val counters = rig.coordinator.outcomeCountersSnapshot()
        assertEquals(1L, counters["segment_discarded_at_walk_end"])
        assertNull(counters["segment_sealed_at_walk_end"])
        assertTrue(rig.store.readyManifests(NP_OWNER).isEmpty())
    }

    @Test
    fun theStoreReportsNoStaleOutcomeWhenThereIsNone() {
        val rig = PostWalkRawRig()
        val admission = rig.store.admitNewSegmentReportingStale(NP_WALK_1, NP_CONSENT_SHA)
        assertEquals(RawSegmentAdmission(RawCapacityAdmission.ALLOWED, null), admission)
        assertEquals(RawCapacityAdmission.ALLOWED, rig.store.admitNewSegment(NP_WALK_1, NP_CONSENT_SHA))
    }

    @Test
    fun everyPostWalkBatchExitWritesTheCountersToTheInternalFieldLogOnly() {
        val source = listOf(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
            File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
            File("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        ).first(File::isFile).readText()
        val upload = source.substringAfter("private fun scheduleRawCollectionPostWalkUpload()")
            .substringBefore("private fun schedulePostWalkTransfers()")
        val batch = upload.substringAfter("try {").substringBefore("} finally {")
        assertTrue(batch.contains("rawCollectionRuntimeCoordinator.startNextUpload {"))
        assertTrue(batch.contains("return@executeRawCollectionTask"))
        assertTrue(
            upload.substringAfter("} finally {").trimStart()
                .startsWith("recordRawCollectionOutcomesIfChanged()"),
        )
        val helper = source.substringAfter("private fun recordRawCollectionOutcomesIfChanged()")
            .substringBefore("private fun handleWalkSessionForegroundReturn()")
        // The walk-end branch keeps the first "raw_collection_outcomes" literal of the file.
        val transition = source.indexOf("private fun handleRawCollectionTransition(")
        assertTrue(source.indexOf("\"raw_collection_outcomes\"") > transition)
        assertTrue(helper.contains("fieldSessionLog.recordEvent(\"raw_collection_outcomes\", outcomes)"))
        assertTrue(helper.contains("if (outcomes == lastLoggedRawCollectionOutcomes) return"))
        listOf("speakInteraction(", "updateStatus(", "Toast.", "announceFor").forEach { output ->
            assertFalse(output, (upload + helper).contains(output))
        }
    }
}
