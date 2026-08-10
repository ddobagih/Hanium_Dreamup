package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldSessionAccountDeletionPrivacyFenceTest {
    private val fieldAead = TestFieldAead().aead

    @Test
    fun deletionMarkerRestartRestoresNoActiveSessionAndAppendsNothing() {
        val root = Files.createTempDirectory("field-log-delete-restart").toFile()
        val ids = AtomicInteger()
        val first = logger(root, ids)
        assertNotNull(first.start())
        first.close()

        val restarted = logger(
            root = root,
            ids = ids,
            initiallyBlockedForAccountDeletion = true,
        )
        assertFalse(restarted.isActive())
        assertNull(restarted.start())
        val before = fileState(root)
        restarted.appendCameraNonMetricSample(cameraSample())
        restarted.recordEvent("must_not_append")
        assertEquals(before, fileState(root))
        root.deleteRecursively()
    }

    @Test
    fun runtimeDeletionBlockImmediatelyClosesActiveSession() {
        val root = Files.createTempDirectory("field-log-delete-runtime").toFile()
        val logger = logger(root, AtomicInteger())
        assertNotNull(logger.start())

        assertTrue(logger.blockForAccountDeletion())
        assertFalse(logger.isActive())
        assertNull(logger.activeSessionId())
        assertNull(logger.start())
        val before = fileState(root)
        logger.appendCameraNonMetricSample(cameraSample())
        logger.recordEvent("must_not_append")
        assertEquals(before, fileState(root))
        root.deleteRecursively()
    }

    @Test
    fun inMemoryDeletionAdmissionPerformsNoWriteAndLinearizesConcurrentWriters() {
        val root = Files.createTempDirectory("field-log-delete-admission").toFile()
        val logger = logger(root, AtomicInteger())
        assertNotNull(logger.start())
        val beforeAdmission = fileState(root)
        logger.blockNewProcessingForAccountDeletion()
        assertEquals(beforeAdmission, fileState(root))
        assertFalse(File(root, "account_deletion_blocked.txt").exists())

        val executor = Executors.newFixedThreadPool(4)
        try {
            val blocked = fileState(root)
            val postBlockWriters = List(4) {
                executor.submit {
                    repeat(100) { logger.recordEvent("must_not_append") }
                }
            }
            postBlockWriters.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(blocked, fileState(root))
            assertFalse(logger.isActive())
            assertNull(logger.start())
        } finally {
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

    @Test
    fun durableCleanupAfterInMemoryAdmissionBlocksRestart() {
        val root = Files.createTempDirectory("field-log-delete-admission-restart").toFile()
        val ids = AtomicInteger()
        val first = logger(root, ids)
        assertNotNull(first.start())

        first.blockNewProcessingForAccountDeletion()
        assertFalse(File(root, "account_deletion_blocked.txt").exists())
        assertTrue(first.blockForAccountDeletion())
        assertTrue(File(root, "account_deletion_blocked.txt").isFile)

        val restarted = logger(root, ids)
        assertFalse(restarted.isActive())
        assertNull(restarted.start())
        val before = fileState(root)
        restarted.recordEvent("must_not_append")
        restarted.appendCameraNonMetricSample(cameraSample())
        assertEquals(before, fileState(root))
        root.deleteRecursively()
    }

    @Test
    fun runtimeDeletionBlockSurvivesStopIoFailureAndStillAllowsPurge() {
        val root = Files.createTempDirectory("field-log-delete-stop-failure").toFile()
        val logger = logger(root, AtomicInteger())
        val sessionId = requireNotNull(logger.start())
        val eventPath = File(File(root, sessionId), "records-0001.jsonl")
        assertTrue(eventPath.delete())
        assertTrue(eventPath.mkdir())

        assertTrue(logger.blockForAccountDeletion())
        assertFalse(logger.isActive())
        assertNull(logger.activeSessionId())
        assertNull(logger.start())
        val before = fileState(root)
        logger.appendCameraNonMetricSample(cameraSample())
        logger.recordEvent("must_not_append")
        assertEquals(before, fileState(root))

        assertTrue(logger.purgeAll())
        assertFalse(logger.isActive())
        assertNull(logger.start())
        root.deleteRecursively()
    }

    @Test
    fun rawSourceWithdrawalClosesAndBlocksUntilConfirmedReset() {
        val root = Files.createTempDirectory("field-log-raw-withdrawal").toFile()
        val ids = AtomicInteger()
        val logger = logger(root, ids)
        assertNotNull(logger.start())

        assertTrue(logger.blockForRawSourceWithdrawal())
        assertFalse(logger.isActive())
        assertNull(logger.start())
        val before = fileState(root)
        logger.appendCameraNonMetricSample(cameraSample())
        logger.recordEvent("must_not_append")
        assertEquals(before, fileState(root))

        assertTrue(logger.resetRawSourceAfterConfirmedConsent())
        assertNotNull(logger.start())
        root.deleteRecursively()
    }

    private fun logger(
        root: File,
        ids: AtomicInteger,
        initiallyBlockedForAccountDeletion: Boolean = false,
    ): PersistentFieldSessionLog =
        PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = FieldSessionDeviceInfo(
                model = "test-model",
                androidVersion = "test-android",
                appVersionName = "test-app",
                sourceCommit = "test-commit",
            ),
            nowMillis = { 1_000L },
            idFactory = { "field-test-${ids.incrementAndGet()}" },
            telemetryIntervalMs = 0L,
            initiallyBlockedForAccountDeletion =
                initiallyBlockedForAccountDeletion,
        )

    private fun cameraSample(): CameraNonMetricFieldSample =
        CameraNonMetricFieldSample(
            elapsedRealtimeMs = 1_000L,
            inferenceMs = 5L,
            detectionCount = 1,
            capabilityTier = "FULL",
            cameraPermissionGranted = true,
            cameraFallbackRunning = false,
            detectorAvailable = true,
            imuFresh = true,
            tmapRouteActive = false,
        )

    private fun fileState(root: File): Map<String, Long> =
        root.walkTopDown()
            .filter(File::isFile)
            .associate { file -> file.relativeTo(root).path to file.length() }
}
