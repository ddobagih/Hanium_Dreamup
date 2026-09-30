package kr.co.hanium.dreamup.walksafe.device

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 14 #39: per-feature space admission and internal-only holds; the walk is never involved. */
class FeatureStorageAllowanceTest {
    @Test
    fun theNextWriteNeedsItsMaximumSizePlusOneTempCopy() {
        assertEquals(2_048L, FeatureStorageAllowance.projectedWriteBytes(1_024L))
        assertEquals(Long.MAX_VALUE, FeatureStorageAllowance.projectedWriteBytes(Long.MAX_VALUE))
        assertNull(FeatureStorageAllowance.holdReason(2_048L, 1_024L))
        assertEquals(
            FeatureStorageHoldReason.INSUFFICIENT_ALLOCATABLE_SPACE,
            FeatureStorageAllowance.holdReason(2_047L, 1_024L),
        )
    }

    @Test
    fun unknownOrNegativeSpaceIsNeverTreatedAsEnough() {
        assertEquals(FeatureStorageHoldReason.ALLOCATABLE_SPACE_UNKNOWN, FeatureStorageAllowance.holdReason(null, 1L))
        assertEquals(FeatureStorageHoldReason.ALLOCATABLE_SPACE_UNKNOWN, FeatureStorageAllowance.holdReason(-1L, 1L))
    }

    @Test
    fun holdsAreClosedPerFeatureCountersWithoutUserText() {
        val holds = FeatureStorageHolds()
        holds.record(StorageDependentFeature.RAW_COLLECTION, FeatureStorageHoldReason.IO_FAILURE)
        holds.record(StorageDependentFeature.RAW_COLLECTION, FeatureStorageHoldReason.IO_FAILURE)
        assertEquals(FeatureStorageHoldReason.IO_FAILURE, holds.currentHold(StorageDependentFeature.RAW_COLLECTION))
        assertNull(holds.currentHold(StorageDependentFeature.AUTOMATIC_REPORT_CANDIDATE))
        assertEquals(mapOf("storage_hold_raw_collection_io_failure" to 2L), holds.snapshot())
        holds.clear(StorageDependentFeature.RAW_COLLECTION)
        assertNull(holds.currentHold(StorageDependentFeature.RAW_COLLECTION))
        assertEquals(2L, holds.count(StorageDependentFeature.RAW_COLLECTION, FeatureStorageHoldReason.IO_FAILURE))
    }

    @Test
    fun protectedRecordsAreNotHoldableFeaturesAndProductionWiringUsesTheOsAllocatableBytes() {
        // Explicit reports, consent, deletion and security records are not in the holdable set.
        assertEquals(
            setOf("RAW_COLLECTION", "AUTOMATIC_REPORT_CANDIDATE"),
            StorageDependentFeature.entries.map { it.name }.toSet(),
        )
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/device/FeatureStorageAllowance.kt").readText()
        assertTrue(source.contains("manager.getAllocatableBytes(manager.getUuidForPath(directory))"))
        val main = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        assertTrue(main.contains("allocatableBytes = AndroidAllocatableBytes(applicationContext, applicationContext.filesDir),"))
        assertTrue(main.contains("allocatableBytes = AndroidAllocatableBytes(applicationContext, noBackupRoot),"))
        // A held or failed RAW capture never drives the walk lifecycle.
        val capture = main.substringAfter("rawCollectionRuntimeCoordinator.captureMetadataBatch(")
            .substringBefore("private fun revalidateRawCollectionRuntime(")
        assertFalse(capture.contains("transitionWalkSession("))
        assertFalse(capture.contains("SafetyStop"))
        assertFalse(Regex("""val\s+\w+\s*=\s*rawCollectionRuntimeCoordinator\.captureMetadataBatch\(""").containsMatchIn(main))
    }
}
