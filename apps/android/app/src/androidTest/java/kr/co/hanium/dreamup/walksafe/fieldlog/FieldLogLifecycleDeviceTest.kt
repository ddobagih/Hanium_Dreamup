package kr.co.hanium.dreamup.walksafe.fieldlog

import android.os.Bundle
import android.os.Build
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.MetadataCaptureLogEntry
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual private files / Keystore. All measurements, IDs and clock values are synthetic fixtures. */
class FieldLogLifecycleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun policy(id: String) = AeadKeyPolicy("walksafe.instrumentation.field.$id.v", 1, setOf(1))
    private fun log(root: File, cipher: AndroidKeyStoreAead, now: () -> Long) = PersistentFieldSessionLog(
        rootDirectory = root, deviceInfo = FieldSessionDeviceInfo(Build.MODEL, Build.VERSION.RELEASE,
            "synthetic-test", "0123456789abcdef0123456789abcdef01234567"), aead = cipher,
        nowMillis = now, idFactory = { "field-device-fixture" })
    private fun cleanup(root: File, id: String) {
        assertTrue(AndroidKeyStoreAead(policy(id)).destroyKnownVersions())
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = policy(id).alias(1)
        keyStore.deleteEntry("$alias.generation")
        assertFalse(keyStore.containsAlias(alias))
        assertTrue(!root.exists() || root.deleteRecursively())
    }
    private fun fixture(block: (File, AndroidKeyStoreAead) -> Unit) {
        val id = UUID.randomUUID().toString()
        val root = File(context.noBackupFilesDir.canonicalFile, "field-device-$id")
        try { block(root, AndroidKeyStoreAead(policy(id))) } finally { cleanup(root, id) }
    }
    private fun fileState(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }
    }
    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })

    @Test fun encryptedStatsRemainUsefulWithoutPlaintextArchive() = fixture { root, cipher ->
        var now = 10_000L
        log(root, cipher) { now }.use { logger ->
            assertNotNull(logger.start())
            listOf(20L, 40L, 90L, 150L, 900L).forEachIndexed { index, delay ->
                now += 1_000
                logger.appendTelemetry(entry(index.toLong()).copy(detectDurationMs = delay,
                    staleReason = if (index == 4) "detector_stale" else null), runtime())
            }
            logger.recordEvent("field_heartbeat", mapOf("state" to "RENDERING_STOPPED", "gap_duration_ms" to 3_500L))
            now += 1_000
            logger.stop()
            val summary = checkNotNull(logger.lastSessionStatsSummary())
            assertEquals(5, summary.telemetryCount)
            assertEquals(1, summary.staleTelemetryCount)
            assertEquals(900L, summary.detectorLatencyMaxMs)
            assertEquals(listOf(1,1,1,1,0,0,1), summary.detectorLatencyHistogram)
            assertEquals(3_500L, summary.longestObservationGapMs)
            assertTrue(summary.coversWholeSession)
            assertEquals(FieldSessionAnalysisState.ANALYZABLE, logger.analysisState())
            val json = summary.toJson().toString()
            for (secret in listOf("latitude","longitude","destination","class_name","person","bbox","heading"))
                assertFalse(secret, json.contains(secret, true))
            val archive = root.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
            for (secret in listOf("navigation=gps_trusted", "detector_latency_ms_histogram", "person", "session_stopped"))
                assertFalse("Plaintext must not be stored: $secret", archive.contains(secret))
            report("FIELD_STATS real_keystore=true telemetry=5 stale=1 max_ms=900 encrypted=true synthetic_metrics=true")
        }
    }

    @Test fun withdrawalPersistsAndReconsentCannotRestoreOldSummary() = fixture { root, cipher ->
        var now = 1_000L
        log(root, cipher) { now }.use { first ->
            first.start();first.appendTelemetry(entry(1).copy(detectDurationMs=30), runtime())
            now += 1_000;first.stop()
            assertNotNull(first.lastSessionStatsSummary())
            assertTrue(first.blockForRawSourceWithdrawal())
            assertNull(first.lastSessionStatsSummary())
        }
        log(root, cipher) { now }.use { reopened ->
            assertNull(reopened.start())
            val before = fileState(root)
            reopened.recordEvent("must_not_append")
            assertEquals(before, fileState(root))
            assertTrue(reopened.resetRawSourceAfterConfirmedConsent())
            assertNull(reopened.lastSessionStatsSummary())
            assertEquals(FieldSessionAnalysisState.ARCHIVE_ONLY, reopened.analysisState())
        }
        report("FIELD_WITHDRAWAL persistent_fence=true stale_summary_not_restored=true synthetic_consent_boundary=true")
    }

    @Test fun deletionAdmissionBlocksConcurrentLateWritesAndPersistentRestore() = fixture { root, cipher ->
        log(root, cipher) { 10_000 }.use { logger ->
            assertNotNull(logger.start())
            val before = fileState(root)
            logger.blockNewProcessingForAccountDeletion()
            assertEquals(before, fileState(root))
            val worker = Executors.newFixedThreadPool(4)
            try {
                (1..4).map { worker.submit { repeat(100) { logger.recordEvent("must_not_append") } } }
                    .forEach { it.get(10, TimeUnit.SECONDS) }
            } finally { worker.shutdownNow() }
            assertEquals(before, fileState(root))
            assertTrue(logger.blockForAccountDeletion())
        }
        log(root, cipher) { 11_000 }.use { reopened ->
            assertFalse(reopened.isActive());assertNull(reopened.start())
            val before = fileState(root);reopened.recordEvent("must_not_append")
            assertEquals(before, fileState(root))
            assertTrue(reopened.purgeAll())
            assertTrue(reopened.resetForNewEnrollment())
            assertNotNull(reopened.startAfterUserConfirmation());reopened.stop()
        }
        report("FIELD_DELETE late_writes=400 unchanged_after_admission=true durable_restore_block=true isolated_purge_and_reset=true")
    }

    @Test fun processRestartDoesNotPromotePartialStatsToWholeSession() {
        val args = InstrumentationRegistry.getArguments()
        val phase = checkNotNull(args.getString("fieldProbePhase"))
        val run = checkNotNull(args.getString("fieldProbeRun"))
        require(run.matches(Regex("[a-zA-Z0-9-]{8,64}")))
        val base = File(context.noBackupFilesDir.canonicalFile, "field-restart-$run")
        val root = File(base, "logs")
        val marker = File(base, "test-process.json")
        val cipher = AndroidKeyStoreAead(policy(run))
        when (phase) {
            "write" -> {
                assertFalse("Never reuse an existing test folder", base.exists());check(base.mkdirs())
                try {
                    log(root, cipher) { 1_000 }.use { logger ->
                        assertNotNull(logger.start());logger.appendTelemetry(entry(1).copy(detectDurationMs=30), runtime())
                    }
                    marker.writeText(JSONObject().put("writePid", Process.myPid()).toString())
                } catch (error: Throwable) { cleanup(base,run);throw error }
            }
            "resume" -> {
                val metadata = JSONObject(marker.readText());assertNotEquals(metadata.getInt("writePid"), Process.myPid())
                var now = 5_000L
                log(root,cipher) { now }.use { logger ->
                    assertTrue(logger.isActive());assertNull(logger.lastSessionStatsSummary())
                    logger.appendTelemetry(entry(2).copy(detectDurationMs=60),runtime());now += 1_000;logger.stop()
                    val summary = checkNotNull(logger.lastSessionStatsSummary())
                    assertFalse(summary.coversWholeSession);assertEquals(1,summary.telemetryCount)
                    assertEquals(FieldSessionAnalysisState.ANALYZABLE, logger.analysisState())
                }
                marker.writeText(metadata.put("resumePid",Process.myPid()).toString())
            }
            "completed" -> try {
                val metadata = JSONObject(marker.readText());assertNotEquals(metadata.getInt("resumePid"),Process.myPid())
                log(root,cipher) { 7_000 }.use { logger ->
                    assertFalse(logger.isActive());assertNull(logger.lastSessionStatsSummary())
                    assertEquals(FieldSessionAnalysisState.ARCHIVE_ONLY, logger.analysisState())
                    assertTrue(logger.purgeAll())
                }
            } finally { cleanup(base,run) }
            else -> error("Unknown phase")
        }
        report("FIELD_RESTART phase=$phase pid=${Process.myPid()} actual_process=true synthetic_clock=true")
    }

    private fun runtime() = FieldRuntimeSnapshot(
        stepCount = 12,
        stepLengthM = 0.65f,
        routeActive = true,
        navigationState = "navigation=gps_trusted",
        deviceGateAllowsAlerts = true,
        deviceGateAllowsReports = false,
        reportCandidateState = "reportCandidate=blocked:no_reportable_class",
        trustedLocationAvailable = true,
        locationAccuracyM = 4.5f,
        locationAgeMs = 200L,
        headingDeg = 90f,
    )

    private fun entry(frameTimestampMs: Long) = MetadataCaptureLogEntry(
        frameTimestampMs = frameTimestampMs,
        detectorFrameTimestampMs = frameTimestampMs,
        detectorAgeMs = 0L,
        detectorModelKey = "unified_walksafe",
        detectionCount = 1,
        detectionsUsedForDepth = true,
        staleReason = null,
        topDetectionClassName = "person",
        topDetectionConfidence = 0.8f,
        topDetectionBbox = null,
        bestDepthClassName = "person",
        bestDepthSource = "ARCORE_RAW_DEPTH",
        bestDepthMedianM = 1.2f,
        bestDepthValidSampleCount = 10,
        bestDepthValidSampleRatio = 0.7f,
        bestDepthBbox = null,
    )
}
