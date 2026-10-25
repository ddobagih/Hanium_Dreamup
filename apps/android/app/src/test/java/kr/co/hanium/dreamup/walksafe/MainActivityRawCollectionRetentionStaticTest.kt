package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1617-9 / S1617-16: the RAW collection coordinator must check walk leases on the same
 * sleep-inclusive clock that produced their deadlines, and expired local RAW data is pruned at
 * process start instead of waiting for the next walk. Source wiring only.
 */
class MainActivityRawCollectionRetentionStaticTest {
    private val main = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first { it.isFile }.readText().replace(Regex("\\s+"), "")
    private val coordinator = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/rawcollection/RawCollectionRuntimeCoordinator.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/rawcollection/RawCollectionRuntimeCoordinator.kt"),
    ).first { it.isFile }.readText().replace(Regex("\\s+"), "")

    @Test
    fun productionCoordinatorUsesTheLeaseDeadlineClock() {
        val creation = main.substringAfter("rawCollectionRuntimeCoordinator=privacyStartupResources.acquireOnWorker(create={RawCollectionRuntimeCoordinator(")
            .substringBefore("initializeRawCollectionTransferController()")
        val directory = creation.indexOf("File(noBackupRoot.canonicalFile,\"raw_collections\")")
        val clock = creation.indexOf("elapsedRealtimeMs=SystemClock::elapsedRealtime,")
        // S14-39: the OS allocatable bytes of the same volume gate new RAW writes.
        val allocatable = creation.indexOf("allocatableBytes=AndroidAllocatableBytes(applicationContext,noBackupRoot)")
        assertTrue(directory >= 0)
        assertTrue(clock > directory)
        assertTrue(allocatable > clock)
        assertFalse(
            "the public constructor must not default to an uptime clock",
            coordinator.contains("elapsedRealtimeMs={System.nanoTime()/1_000_000L}"),
        )
    }

    @Test
    fun expiredRawDataIsPrunedOncePerProcessStart() {
        val creation = main.indexOf("rawCollectionRuntimeCoordinator=privacyStartupResources.acquireOnWorker(create={RawCollectionRuntimeCoordinator(")
        val prune = main.indexOf("executeRawCollectionTask{rawCollectionRuntimeCoordinator.pruneExpired()}")
        assertTrue(creation >= 0 && prune > creation)
        assertTrue(coordinator.contains("funpruneExpired():Int=try{store.pruneExpired()}"))
    }
}
