package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.File
import kr.co.hanium.dreamup.walksafe.MetadataCaptureLogEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersistentFieldSessionLogTest {
    private val testFieldAead = TestFieldAead()
    private val fieldAead = testFieldAead.aead

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun persistsRateLimitedTelemetryAndResumesActiveSessionAfterRestart() {
        val root = temporaryFolder.newFolder("field_sessions")
        var now = 1_000L
        val first = logger(root, now = { now })

        assertEquals("field-test-session", first.start())
        first.appendTelemetry(entry(1L), runtime())
        now = 1_500L
        first.appendTelemetry(entry(2L), runtime())
        now = 2_000L
        first.appendTelemetry(entry(3L), runtime())
        first.close()

        now = 2_500L
        val resumed = logger(root, now = { now })
        assertTrue(resumed.isActive())
        assertEquals("field-test-session", resumed.activeSessionId())
        now = 3_000L
        resumed.stop()

        val sessionDirectory = File(root, "field-test-session")
        val manifest = readManifest(root, "field-test-session")
        assertEquals("android.field_session.v1", manifest.getString("schema_version"))
        assertEquals("completed", manifest.getString("status"))
        assertFalse(manifest.getJSONObject("privacy").getBoolean("exact_coordinates"))
        assertEquals("apk-test-sha256", manifest.getJSONObject("provenance").getString("apk_sha256"))
        assertEquals("config-test-sha256", manifest.getJSONObject("provenance").getString("model_config_sha256"))
        assertEquals("0123456789abcdef0123456789abcdef01234567", manifest.getJSONObject("provenance").getString("source_commit"))
        val records = readRecords(sessionDirectory)
        assertEquals(2, records.count { it.optString("record_type") == "telemetry" })
        assertTrue(records.any { it.optString("event_name") == "session_resumed_after_app_restart" })
        assertTrue(records.any { it.optString("event_name") == "session_stopped" })
        val allText = records.joinToString("\n")
        assertFalse(allText.contains("latitude", ignoreCase = true))
        assertFalse(allText.contains("longitude", ignoreCase = true))
        assertFalse(allText.contains("reporter_user_id", ignoreCase = true))
        assertFalse(allText.contains("image_bytes", ignoreCase = true))
        val rawArtifacts = sessionDirectory.walkTopDown()
            .filter(File::isFile)
            .joinToString("\n") { it.readText() }
        assertFalse(rawArtifacts.contains("navigation=gps_trusted"))
        assertFalse(rawArtifacts.contains("session_stopped"))
        assertFalse(rawArtifacts.contains(deviceInfo().sourceCommit))
        assertFalse(File(root, "active_session.txt").exists())
    }

    @Test
    fun blocksPersistedRestoreUntilAUserConfirmedFreshStart() {
        val root = temporaryFolder.newFolder("restore_blocked_sessions")
        var now = 4_000L
        val first = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            nowMillis = { now },
            idFactory = { "field-before-permission-block" },
        )
        assertEquals("field-before-permission-block", first.start())
        first.appendTelemetry(entry(1L), runtime())

        now = 5_000L
        assertTrue(first.blockActiveSessionRestore())
        assertFalse(first.isActive())
        assertFalse(File(root, "active_session.txt").exists())
        assertTrue(File(root, "active_session_restore_blocked.txt").isFile)

        now = 6_000L
        val restarted = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            nowMillis = { now },
            idFactory = { "field-after-user-confirmation" },
        )
        assertFalse(restarted.isActive())
        assertNull(restarted.start())
        restarted.appendTelemetry(entry(2L), runtime())
        assertEquals("field-after-user-confirmation", restarted.startAfterUserConfirmation())
        assertTrue(restarted.isActive())
        assertFalse(File(root, "active_session_restore_blocked.txt").exists())

        val blockedManifest = readManifest(root, "field-before-permission-block")
        assertEquals("completed", blockedManifest.getString("status"))
        assertEquals(
            1,
            readRecords(File(root, "field-before-permission-block"))
                .count { it.optString("record_type") == "telemetry" },
        )
    }

    @Test
    fun userConfirmedStartDoesNotBypassRawOrAccountDeletionBlocks() {
        val root = temporaryFolder.newFolder("restore_and_privacy_blocks")
        var nextId = 0
        val log = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            idFactory = {
                nextId += 1
                "field-privacy-$nextId"
            },
        )
        assertEquals("field-privacy-1", log.start())
        assertTrue(log.blockActiveSessionRestore())

        assertTrue(log.blockForRawSourceWithdrawal())
        assertNull(log.startAfterUserConfirmation())
        assertTrue(log.resetRawSourceAfterConfirmedConsent())
        assertNull(log.start())

        assertTrue(log.blockForAccountDeletion())
        assertNull(log.startAfterUserConfirmation())
        assertTrue(log.purgeAll())
        assertTrue(log.resetForNewEnrollment())
        assertNull(log.start())
        assertEquals("field-privacy-2", log.startAfterUserConfirmation())
    }

    @Test
    fun sharedFenceStopsASecondLoggerFromAppendingWithCachedActiveState() {
        val root = temporaryFolder.newFolder("multiple_logger_fence")
        var now = 8_000L
        val first = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            nowMillis = { now },
            idFactory = { "field-shared-before" },
            telemetryIntervalMs = 0L,
        )
        val preexisting = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            nowMillis = { now },
            idFactory = { "field-must-not-replace-active-pointer" },
            telemetryIntervalMs = 0L,
        )
        assertEquals("field-shared-before", first.start())
        assertNull(preexisting.start())
        val stale = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            nowMillis = { now },
            idFactory = { "field-shared-after" },
            telemetryIntervalMs = 0L,
        )
        assertTrue(stale.isActive())
        first.appendTelemetry(entry(1L), runtime())

        now = 9_000L
        assertTrue(first.blockActiveSessionRestore())
        stale.appendTelemetry(entry(2L), runtime())
        stale.appendCameraNonMetricSample(cameraSample(elapsedRealtimeMs = 9_000L))

        assertFalse(stale.isActive())
        assertNull(stale.start())
        val records = readRecords(File(root, "field-shared-before"))
        assertEquals(1, records.count { it.optString("record_type") == "telemetry" })
        assertFalse(records.any { it.optString("event_name") == "camera_non_metric_inference_sample" })
    }

    @Test
    fun temporaryRestoreMarkerAloneFailsClosedAfterAWriteCrash() {
        val root = temporaryFolder.newFolder("temporary_restore_marker")
        val first = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            idFactory = { "field-before-temp-marker" },
        )
        assertEquals("field-before-temp-marker", first.start())
        first.close()
        File(root, "active_session_restore_blocked.txt.tmp").writeText("blocked\n")

        val restarted = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            idFactory = { "field-after-temp-marker" },
        )
        assertFalse(restarted.isActive())
        assertNull(restarted.start())
        assertTrue(File(root, "active_session_restore_blocked.txt.tmp").exists())
        assertEquals("field-after-temp-marker", restarted.startAfterUserConfirmation())
        assertFalse(File(root, "active_session_restore_blocked.txt.tmp").exists())
    }

    @Test
    fun cleanupFailureKeepsRestoreMarkerAndRejectsConfirmedStart() {
        val root = temporaryFolder.newFolder("restore_cleanup_failure")
        val marker = File(root, "active_session_restore_blocked.txt")
        marker.writeText("blocked\n")
        val pointer = File(root, "active_session.txt")
        assertTrue(pointer.mkdir())
        File(pointer, "undeletable-child").writeText("keep")

        val log = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            idFactory = { "field-must-not-start" },
        )
        assertNull(log.startAfterUserConfirmation())
        assertTrue(marker.exists())
        assertTrue(pointer.exists())
        assertFalse(log.isActive())
    }

    @Test
    fun accountDeletionFenceSurvivesPurgeAndProcessRestart() {
        val root = temporaryFolder.newFolder("deletion_purge_restart")
        val first = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            idFactory = { "field-before-deletion" },
        )
        assertEquals("field-before-deletion", first.start())
        assertTrue(first.purgeAll())
        assertTrue(File(root, "account_deletion_blocked.txt").isFile)

        val restarted = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            idFactory = { "field-after-deletion" },
        )
        assertNull(restarted.start())
        assertNull(restarted.startAfterUserConfirmation())
        assertTrue(restarted.resetForNewEnrollment())
        assertFalse(File(root, "account_deletion_blocked.txt").exists())
        assertFalse(File(root, "raw_source_collection_blocked.txt").exists())
        assertFalse(File(root, "field_log_crypto_blocked.txt").exists())
        assertFalse(File(root, "field_key_purge_verified.txt").exists())
        assertEquals("field-after-deletion", restarted.start())
    }

    @Test
    fun newEnrollmentResetRequiresAnExactSolePurgeMarker() {
        listOf("missing", "malformed", "unknown").forEach { case ->
            val root = temporaryFolder.newFolder("reset_requires_purge_$case")
            val log = PersistentFieldSessionLog(
                rootDirectory = root,
                aead = fieldAead,
                deviceInfo = deviceInfo(),
                idFactory = { "field-reset-$case" },
            )
            assertEquals("field-reset-$case", log.start())
            assertTrue(log.blockForAccountDeletion())
            when (case) {
                "malformed" -> File(root, "field_key_purge_verified.txt").writeText("wrong\n")
                "unknown" -> {
                    File(root, "field_key_purge_verified.txt").writeText("verified\n")
                    File(root, "unexpected.encrypted").writeText("old-account")
                }
            }

            assertFalse(log.resetForNewEnrollment())

            assertEquals("field-log=blocked:account-deletion", log.statusText())
            assertTrue(File(root, "field_log_crypto_blocked.txt").isFile)
            assertNull(log.start())
        }
    }

    @Test
    fun atomicWritesFsyncTheContainingDirectoryAfterRename() {
        val root = temporaryFolder.newFolder("atomic_parent_fsync")
        val syncedDirectories = mutableListOf<File>()
        val log = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            idFactory = { "field-fsync" },
            directorySync = { directory ->
                syncedDirectories += directory.canonicalFile
                true
            },
        )

        assertEquals("field-fsync", log.start())

        assertTrue(syncedDirectories.contains(root.canonicalFile))
        assertTrue(
            syncedDirectories.contains(File(root, "field-fsync").canonicalFile),
        )
    }

    @Test
    fun postRenameDirectoryFsyncFailureFailsClosed() {
        val root = temporaryFolder.newFolder("atomic_parent_fsync_failure")
        var failNextSync = true
        val log = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            idFactory = { "field-fsync-failure" },
            directorySync = {
                if (failNextSync) {
                    failNextSync = false
                    false
                } else {
                    true
                }
            },
        )

        assertNull(log.start())

        assertEquals("field-log=blocked:crypto", log.statusText())
        assertTrue(File(root, "field_log_crypto_blocked.txt").isFile)
        assertFalse(File(root, "active_session.txt").exists())
    }

    @Test
    fun directFenceWriteReportsDirectoryFsyncFailure() {
        val root = temporaryFolder.newFolder("direct_fence_fsync_failure")
        var failDirectorySync = false
        val log = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            directorySync = { !failDirectorySync },
        )
        failDirectorySync = true

        assertFalse(log.blockActiveSessionRestore())

        assertTrue(File(root, "active_session_restore_blocked.txt").isFile)
        assertNull(log.start())
    }

    @Test
    fun purgeFsyncsParentAfterRootDeletionAndRecreation() {
        val root = temporaryFolder.newFolder("purge_parent_fsync")
        val rootParent = requireNotNull(root.parentFile).canonicalFile
        val syncedDirectories = mutableListOf<File>()
        val log = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            idFactory = { "field-before-purge-fsync" },
            directorySync = { directory ->
                syncedDirectories += directory.canonicalFile
                true
            },
        )
        assertEquals("field-before-purge-fsync", log.start())
        syncedDirectories.clear()

        assertTrue(log.purgeAll())

        assertTrue(
            syncedDirectories.count { it == rootParent } >= 2,
        )
        assertTrue(File(root, "account_deletion_blocked.txt").isFile)
    }

    @Test
    fun purgeFailsWhenRootParentDirectoryFsyncFails() {
        val root = temporaryFolder.newFolder("purge_parent_fsync_failure")
        val rootParent = requireNotNull(root.parentFile).canonicalFile
        var failParentSync = false
        val log = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            idFactory = { "field-before-purge-fsync-failure" },
            directorySync = { directory ->
                !(failParentSync && directory.canonicalFile == rootParent)
            },
        )
        assertEquals("field-before-purge-fsync-failure", log.start())
        failParentSync = true

        assertFalse(log.purgeAll())
    }

    @Test
    fun failedKeyDestructionNeverPublishesPurgeProofOrAllowsReset() {
        val root = temporaryFolder.newFolder("purge_key_delete_failure")
        val crypto = TestFieldAead()
        val log = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = crypto.aead,
            deviceInfo = deviceInfo(),
            idFactory = { "field-old-key" },
        )
        assertEquals("field-old-key", log.start())
        crypto.failNextDelete()

        assertFalse(log.purgeAll())

        assertFalse(File(root, "field_key_purge_verified.txt").exists())
        assertTrue(File(root, "field_log_crypto_blocked.txt").isFile)
        assertTrue(crypto.hasKey())
        assertFalse(log.resetForNewEnrollment())
        assertNull(log.start())
    }

    @Test
    fun freshKeyFailureCanRetryWhileVerifiedPurgeProofRemains() {
        val root = temporaryFolder.newFolder("fresh_key_retry")
        val crypto = TestFieldAead()
        val log = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = crypto.aead,
            deviceInfo = deviceInfo(),
            idFactory = { "field-after-fresh-retry" },
        )
        assertTrue(log.purgeAll())
        crypto.failNextFreshCreate()

        assertFalse(log.resetForNewEnrollment())
        assertTrue(File(root, "field_key_purge_verified.txt").isFile)
        assertTrue(File(root, "field_log_crypto_blocked.txt").isFile)

        assertTrue(log.resetForNewEnrollment())
        assertEquals("field-after-fresh-retry", log.start())
    }

    @Test
    fun constructorKeepsInitialPrivacyBlocksWhenFencePersistenceFails() {
        val accountRoot = temporaryFolder.newFile("account-root-is-file")
        val accountBlocked = PersistentFieldSessionLog(
            rootDirectory = accountRoot,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            initiallyBlockedForAccountDeletion = true,
        )
        assertNull(accountBlocked.start())
        assertNull(accountBlocked.startAfterUserConfirmation())

        val rawRoot = temporaryFolder.newFile("raw-root-is-file")
        val rawBlocked = PersistentFieldSessionLog(
            rootDirectory = rawRoot,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            initiallyBlockedForRawSourceCollection = true,
        )
        assertNull(rawBlocked.start())
        assertNull(rawBlocked.startAfterUserConfirmation())
    }

    @Test
    fun runtimeRestoreBlockStaysMonotonicWhenFencePersistenceFails() {
        val root = temporaryFolder.newFolder("runtime_restore_persist_failure")
        val log = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            idFactory = { "field-must-remain-blocked" },
        )
        assertTrue(root.deleteRecursively())
        root.writeText("not-a-directory")

        assertFalse(log.blockActiveSessionRestore())
        assertNull(log.start())
        assertFalse(log.isActive())
    }

    @Test
    fun runtimePrivacyBlocksStayMonotonicWhenFencePersistenceFails() {
        val rawRoot = temporaryFolder.newFolder("runtime_raw_persist_failure")
        val rawBlocked = PersistentFieldSessionLog(
            rootDirectory = rawRoot,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            idFactory = { "field-raw-must-remain-blocked" },
        )
        assertTrue(rawRoot.deleteRecursively())
        rawRoot.writeText("not-a-directory")
        assertFalse(rawBlocked.blockForRawSourceWithdrawal())
        assertNull(rawBlocked.start())
        assertNull(rawBlocked.startAfterUserConfirmation())

        val accountRoot = temporaryFolder.newFolder("runtime_account_persist_failure")
        val accountBlocked = PersistentFieldSessionLog(
            rootDirectory = accountRoot,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            idFactory = { "field-account-must-remain-blocked" },
        )
        assertTrue(accountRoot.deleteRecursively())
        accountRoot.writeText("not-a-directory")
        assertFalse(accountBlocked.blockForAccountDeletion())
        assertNull(accountBlocked.start())
        assertNull(accountBlocked.startAfterUserConfirmation())
    }

    @Test
    fun rotatesJsonlSegmentsBeforeTheyGrowPastConfiguredBoundary() {
        val root = temporaryFolder.newFolder("rotating_sessions")
        var now = 10_000L
        val log = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            nowMillis = { now++ },
            idFactory = { "field-rotation" },
            maxSegmentBytes = 250L,
            telemetryIntervalMs = 0L,
        )

        log.start()
        repeat(4) { index -> log.appendTelemetry(entry(index.toLong()), runtime()) }
        log.stop()

        val sessionDirectory = File(root, "field-rotation")
        val segments = sessionDirectory.listFiles { file -> file.name.matches(Regex("records-\\d{4}\\.jsonl")) }
            .orEmpty()
        assertTrue(segments.size >= 2)
        val manifest = readManifest(root, "field-rotation")
        assertEquals(segments.size, manifest.getInt("record_segment_count"))
        val records = readRecords(sessionDirectory)
        assertEquals(records.size.toLong(), manifest.getLong("record_count"))
        assertTrue(manifest.getString("record_tail_sha256").matches(Regex("[0-9a-f]{64}")))
        assertTrue(records.isNotEmpty())
    }

    @Test
    fun splitsAnActiveSessionWhenInstalledApkProvenanceChanges() {
        val root = temporaryFolder.newFolder("upgraded_sessions")
        var now = 20_000L
        val old = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            nowMillis = { now },
            idFactory = { "field-before-upgrade" },
        )
        old.start()

        now = 21_000L
        val upgraded = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo().copy(apkSha256 = "new-apk-sha256"),
            nowMillis = { now },
            idFactory = { "field-after-upgrade" },
        )

        assertTrue(upgraded.isActive())
        assertEquals("field-after-upgrade", upgraded.activeSessionId())
        val oldManifest = readManifest(root, "field-before-upgrade")
        assertEquals("completed", oldManifest.getString("status"))
        assertEquals("app_or_model_provenance_changed", oldManifest.getString("termination_reason"))
        val newManifest = readManifest(root, "field-after-upgrade")
        assertEquals("new-apk-sha256", newManifest.getJSONObject("provenance").getString("apk_sha256"))
    }

    @Test
    fun boundsPerSessionStorageAndReportsTheLimit() {
        val root = temporaryFolder.newFolder("bounded_sessions")
        var now = 30_000L
        val log = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            nowMillis = { now++ },
            idFactory = { "field-bounded" },
            telemetryIntervalMs = 0L,
            maxSessionBytes = 700L,
        )

        log.start()
        repeat(20) { index -> log.appendTelemetry(entry(index.toLong()), runtime()) }

        assertTrue(log.statusText().contains("storage=limit"))
        val bytes = File(root, "field-bounded")
            .listFiles { file -> file.name.matches(Regex("records-\\d{4}\\.jsonl")) }
            .orEmpty()
            .sumOf(File::length)
        assertTrue(bytes <= 700L)
    }

    @Test
    fun preservesCameraNonMetricEvidenceFieldsThroughTheEventAllowlist() {
        val root = temporaryFolder.newFolder("camera_non_metric_events")
        var now = 35_000L
        val log = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            nowMillis = { now++ },
            idFactory = { "field-camera-non-metric" },
        )

        log.start()
        log.recordEvent(
            "camera_non_metric_session_started",
            mapOf(
                "loaded_model" to "unified_walksafe",
                "model_fallback_used" to false,
                "metric" to false,
                "reports_allowed" to false,
                "reason" to "debug_forced_supported",
                "state" to "SUPPORTED_INSTALLED",
            ),
        )
        log.recordEvent(
            "camera_non_metric_frame_analyzed",
            mapOf(
                "loaded_model" to "unified_walksafe",
                "model_fallback_used" to false,
                "metric" to false,
                "reports_allowed" to false,
                "state" to "detector_succeeded",
            ),
        )
        log.recordEvent(
            "camera_non_metric_advisory_emitted",
            mapOf(
                "direction" to "CENTER",
                "loaded_model" to "legacy_two_model",
                "model_fallback_used" to true,
                "metric" to false,
                "tmap_authoritative" to true,
                "reports_allowed" to false,
            ),
        )
        log.stop()

        val records = readRecords(File(root, "field-camera-non-metric"))
        val startFields = records.first {
            it.optString("event_name") == "camera_non_metric_session_started"
        }.getJSONObject("fields")
        assertEquals("unified_walksafe", startFields.getString("loaded_model"))
        assertFalse(startFields.getBoolean("model_fallback_used"))
        assertFalse(startFields.getBoolean("metric"))
        assertFalse(startFields.getBoolean("reports_allowed"))
        assertEquals("debug_forced_supported", startFields.getString("reason"))
        assertEquals("SUPPORTED_INSTALLED", startFields.getString("state"))
        val frameFields = records.first {
            it.optString("event_name") == "camera_non_metric_frame_analyzed"
        }.getJSONObject("fields")
        assertEquals("unified_walksafe", frameFields.getString("loaded_model"))
        assertFalse(frameFields.getBoolean("model_fallback_used"))
        assertFalse(frameFields.getBoolean("metric"))
        assertFalse(frameFields.getBoolean("reports_allowed"))
        assertEquals("detector_succeeded", frameFields.getString("state"))
        val advisoryFields = records.first {
            it.optString("event_name") == "camera_non_metric_advisory_emitted"
        }.getJSONObject("fields")
        assertEquals("CENTER", advisoryFields.getString("direction"))
        assertEquals("legacy_two_model", advisoryFields.getString("loaded_model"))
        assertTrue(advisoryFields.getBoolean("model_fallback_used"))
        assertFalse(advisoryFields.getBoolean("metric"))
        assertTrue(advisoryFields.getBoolean("tmap_authoritative"))
        assertFalse(advisoryFields.getBoolean("reports_allowed"))
    }

    @Test
    fun persistsCameraNonMetricInferenceSamplesAtMostOncePerSecond() {
        val root = temporaryFolder.newFolder("camera_non_metric_samples")
        var now = 40_000L
        val log = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            nowMillis = { now },
            idFactory = { "field-camera-non-metric-samples" },
        )
        val sample = CameraNonMetricFieldSample(
            elapsedRealtimeMs = 100_000L,
            inferenceMs = 480L,
            detectionCount = 2,
            capabilityTier = "CAMERA_IMU_NON_METRIC",
            cameraPermissionGranted = true,
            cameraFallbackRunning = true,
            detectorAvailable = true,
            imuFresh = true,
            tmapRouteActive = true,
        )

        log.start()
        log.appendCameraNonMetricSample(sample)
        now += 5_000L
        log.appendCameraNonMetricSample(sample.copy(elapsedRealtimeMs = 100_999L, inferenceMs = 490L))
        now += 1_000L
        log.appendCameraNonMetricSample(
            sample.copy(elapsedRealtimeMs = 101_000L, inferenceMs = 500L, detectionCount = 1),
        )
        log.stop()

        val samples = readRecords(File(root, "field-camera-non-metric-samples"))
            .filter { it.optString("event_name") == "camera_non_metric_inference_sample" }
        assertEquals(2, samples.size)
        val first = samples.first().getJSONObject("fields")
        assertEquals(100_000L, first.getLong("elapsed_realtime_ms"))
        assertEquals(480L, first.getLong("inference_ms"))
        assertEquals(2, first.getInt("detection_count"))
        assertEquals("CAMERA_IMU_NON_METRIC", first.getString("capability_tier"))
        assertTrue(first.getBoolean("camera_permission_granted"))
        assertTrue(first.getBoolean("camera_fallback_running"))
        assertTrue(first.getBoolean("detector_available"))
        assertTrue(first.getBoolean("imu_fresh"))
        assertTrue(first.getBoolean("tmap_route_active"))
        assertFalse(first.getBoolean("metric"))
        assertFalse(first.getBoolean("reports_allowed"))
        assertEquals("detector_succeeded", first.getString("state"))
        val last = samples.last().getJSONObject("fields")
        assertEquals(101_000L, last.getLong("elapsed_realtime_ms"))
        assertEquals(500L, last.getLong("inference_ms"))
    }

    @Test
    fun restoresCameraSampleRateLimitAcrossActiveSessionProcessRestart() {
        val root = temporaryFolder.newFolder("camera_non_metric_restart")
        var now = 50_000L
        val first = logger(root, now = { now })
        val sample = cameraSample(elapsedRealtimeMs = 100_000L)

        first.start()
        first.appendCameraNonMetricSample(sample)
        first.close()

        now += 500L
        val resumed = logger(root, now = { now })
        resumed.appendCameraNonMetricSample(sample.copy(elapsedRealtimeMs = 100_500L, inferenceMs = 490L))
        resumed.appendCameraNonMetricSample(sample.copy(elapsedRealtimeMs = 101_000L, inferenceMs = 500L))
        resumed.stop()

        val elapsedValues = cameraSampleRecords(File(root, "field-test-session"))
            .map { it.getJSONObject("fields").getLong("elapsed_realtime_ms") }
        assertEquals(listOf(100_000L, 101_000L), elapsedValues)
    }

    @Test
    fun failsClosedWhenRestoredCameraSampleClockRollsBack() {
        val root = temporaryFolder.newFolder("camera_non_metric_restart_rollback")
        var now = 60_000L
        val first = logger(root, now = { now })
        val sample = cameraSample(elapsedRealtimeMs = 100_000L)
        first.start()
        first.appendCameraNonMetricSample(sample)
        first.appendCameraNonMetricSample(sample.copy(elapsedRealtimeMs = 101_000L))
        first.close()

        testFieldAead.rewriteRecords(File(root, "field-test-session")) { record ->
            if (
                record.optString("event_name") == "camera_non_metric_inference_sample" &&
                record.getJSONObject("fields").getLong("elapsed_realtime_ms") == 101_000L
            ) {
                record.getJSONObject("fields").put("elapsed_realtime_ms", 99_000L)
            }
            record
        }

        now += 2_000L
        val resumed = logger(root, now = { now })
        assertEquals("field-log=blocked:crypto", resumed.statusText())
        resumed.appendCameraNonMetricSample(sample.copy(elapsedRealtimeMs = 102_000L))
        resumed.stop()

        assertEquals(2, cameraSampleRecords(File(root, "field-test-session")).size)
    }

    @Test
    fun failsClosedWhenRestoredCameraSampleRecordIsCorrupt() {
        val root = temporaryFolder.newFolder("camera_non_metric_restart_corrupt")
        var now = 70_000L
        val first = logger(root, now = { now })
        val sample = cameraSample(elapsedRealtimeMs = 100_000L)
        first.start()
        first.appendCameraNonMetricSample(sample)
        first.close()

        val segment = File(root, "field-test-session/records-0001.jsonl")
        segment.appendText("{corrupt\n")

        now += 2_000L
        val resumed = logger(root, now = { now })
        assertEquals("field-log=blocked:crypto", resumed.statusText())
        resumed.appendCameraNonMetricSample(sample.copy(elapsedRealtimeMs = 102_000L))
        resumed.stop()

        assertEquals(1, cameraSampleRecords(File(root, "field-test-session")).size)
        assertTrue(File(root, "field_log_crypto_blocked.txt").isFile)
    }

    @Test
    fun failsClosedWhenTheLastAuthenticatedRecordLineIsDeleted() {
        val root = temporaryFolder.newFolder("field_tail_line_deleted")
        var now = 75_000L
        val first = logger(root, now = { now })
        first.start()
        first.appendTelemetry(entry(1L), runtime())
        first.close()

        val segment = File(root, "field-test-session/records-0001.jsonl")
        val retainedLines = segment.readLines().dropLast(1)
        segment.writeText(retainedLines.joinToString(separator = "\n", postfix = "\n"))

        now += 1_000L
        val resumed = logger(root, now = { now })

        assertEquals("field-log=blocked:crypto", resumed.statusText())
        assertNull(resumed.start())
        assertTrue(File(root, "field_log_crypto_blocked.txt").isFile)
    }

    @Test
    fun failsClosedWhenTheLastAuthenticatedRecordSegmentIsDeleted() {
        val root = temporaryFolder.newFolder("field_tail_segment_deleted")
        var now = 77_000L
        val first = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            nowMillis = { now },
            idFactory = { "field-tail-segment" },
            maxSegmentBytes = 1L,
            telemetryIntervalMs = 0L,
        )
        first.start()
        first.appendTelemetry(entry(1L), runtime())
        first.close()

        val directory = File(root, "field-tail-segment")
        val lastSegment = directory
            .listFiles { file -> file.name.matches(Regex("records-\\d{4}\\.jsonl")) }
            .orEmpty()
            .maxByOrNull(File::getName)
        assertTrue(requireNotNull(lastSegment).delete())

        now += 1_000L
        val resumed = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            nowMillis = { now },
            idFactory = { "field-after-tail-delete" },
            maxSegmentBytes = 1L,
            telemetryIntervalMs = 0L,
        )

        assertEquals("field-log=blocked:crypto", resumed.statusText())
        assertNull(resumed.start())
        assertTrue(File(root, "field_log_crypto_blocked.txt").isFile)
    }

    @Test
    fun missingFieldKeyBlocksRestoreWithoutCreatingAReplacementKey() {
        val root = temporaryFolder.newFolder("field_missing_key")
        var now = 80_000L
        val first = logger(root, now = { now })
        first.start()
        first.close()
        testFieldAead.deleteKey()

        now += 1_000L
        val resumed = logger(root, now = { now })

        assertEquals("field-log=blocked:crypto", resumed.statusText())
        assertNull(resumed.start())
        assertTrue(File(root, "field_log_crypto_blocked.txt").isFile)
        assertFalse(testFieldAead.hasKey())
    }

    @Test
    fun removesOldestCompletedSessionsBeyondRetentionCount() {
        val root = temporaryFolder.newFolder("retained_sessions")
        fun completed(id: String, endedAtMs: Long) {
            testFieldAead.writeManifest(
                root,
                id,
                JSONObject()
                    .put("schema_version", "android.field_session.v1")
                    .put("session_id", id)
                    .put("status", "completed")
                    .put("ended_at_epoch_ms", endedAtMs)
                    .put("record_segment_count", 0),
            )
        }
        completed("field-old", 10_000L)
        completed("field-new", 20_000L)

        PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            nowMillis = { 30_000L },
            idFactory = { "field-next" },
            maxRetainedSessions = 1,
            maxSessionAgeMs = 100_000L,
        )

        assertFalse(File(root, "field-old").exists())
        assertTrue(File(root, "field-new").isDirectory)
    }

    @Test
    fun expiresActiveSessionAndPointerAtTheAbsoluteRetentionCap() {
        val root = temporaryFolder.newFolder("expired_active_session")
        var now = 1_000L
        val first = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            nowMillis = { now },
            idFactory = { "field-expired-active" },
            maxActiveSessionAgeMs = 500L,
        )
        first.start()
        first.close()

        now = 1_500L
        val restored = PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            nowMillis = { now },
            idFactory = { "field-next" },
            maxActiveSessionAgeMs = 500L,
        )

        assertFalse(restored.isActive())
        assertFalse(File(root, "active_session.txt").exists())
        assertFalse(File(root, "field-expired-active").exists())
    }

    private fun logger(root: File, now: () -> Long): PersistentFieldSessionLog {
        return PersistentFieldSessionLog(
            rootDirectory = root,
            aead = fieldAead,
            deviceInfo = deviceInfo(),
            nowMillis = now,
            idFactory = { "field-test-session" },
        )
    }

    private fun cameraSample(elapsedRealtimeMs: Long) = CameraNonMetricFieldSample(
        elapsedRealtimeMs = elapsedRealtimeMs,
        inferenceMs = 480L,
        detectionCount = 2,
        capabilityTier = "CAMERA_IMU_NON_METRIC",
        cameraPermissionGranted = true,
        cameraFallbackRunning = true,
        detectorAvailable = true,
        imuFresh = true,
        tmapRouteActive = true,
    )

    private fun cameraSampleRecords(sessionDirectory: File): List<JSONObject> =
        readRecords(sessionDirectory)
        .filter { it.optString("event_name") == "camera_non_metric_inference_sample" }

    private fun readManifest(root: File, sessionId: String): JSONObject =
        testFieldAead.readManifest(root, sessionId)

    private fun readRecords(sessionDirectory: File): List<JSONObject> =
        testFieldAead.readRecords(sessionDirectory)

    private fun deviceInfo() = FieldSessionDeviceInfo(
        model = "Test Device",
        androidVersion = "16",
        appVersionName = "0.1.0",
        sourceCommit = "0123456789abcdef0123456789abcdef01234567",
        apkSha256 = "apk-test-sha256",
        modelConfigSha256 = "config-test-sha256",
    )

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
