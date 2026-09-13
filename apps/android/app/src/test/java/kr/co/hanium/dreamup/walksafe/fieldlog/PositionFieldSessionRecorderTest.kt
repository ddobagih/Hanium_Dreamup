package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.POSITION_TRACE_EXPORT_SCHEMA_VERSION
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.POSITION_TRACE_MOUNT
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.POSITION_TRACE_SOURCE_KIND
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.POSITION_TRACE_TIMEBASE
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositionGnssRisk
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositionGnssTrace
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositionHeadingSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositionHeadingTrace
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositionStationaryState
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositionStationaryTrace
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositionStepProfileTrace
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositionTraceCoordinate
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositionTraceSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositioningTraceCheckpoint
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositioningTraceRecord
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositioningTraceStartMetadata
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PositionFieldSessionRecorderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exportsUnavailableReplayedMatchAsExplicitEvaluationWithoutInventingCoordinates() {
        val fixture = fixture("replayed_match")
        val lease = requireNotNull(fixture.recorder.start(metadata(), binding(17L)))
        fixture.elapsedNs = 2_000L
        assertTrue(fixture.recorder.append(lease, fullRecord(fixture.elapsedNs).copy(
            source = PositionTraceSource.SENSOR_GNSS_ANCHORED,
            rawPosition = null,
            matchedPosition = null,
            routeMatchEvaluated = true,
        )))
        fixture.elapsedNs = 3_000L
        fixture.nowMs = 2_000L
        assertTrue(fixture.recorder.stop(lease))
        val output = ByteArrayOutputStream()
        assertEquals(PositionFieldExportStatus.EXPORTED,
            fixture.recorder.export(lease.sessionId, binding(17L), output).status)
        val record = JSONObject(output.toString(Charsets.UTF_8.name()).lineSequence().drop(1).first())
        assertTrue(record.getBoolean("route_match_evaluated"))
        assertFalse(record.has("matched_position"))
        assertFalse(record.has("raw_position"))
        assertEquals("sensor_gnss_anchored", record.getString("source"))
    }

    @Test
    fun exportsCanonicalHeaderRecordsAndHashedFooterWithoutScope() {
        val fixture = fixture("canonical_export")
        val lease = requireNotNull(fixture.recorder.start(metadata(), binding(17L)))
        fixture.elapsedNs = 2_000L
        assertTrue(fixture.recorder.append(lease, fullRecord(fixture.elapsedNs)))
        fixture.elapsedNs = 3_000L
        assertTrue(fixture.recorder.markCheckpoint(lease, checkpoint(fixture.elapsedNs, 1)))
        fixture.elapsedNs = 4_000L
        fixture.nowMs = 2_000L
        assertTrue(fixture.recorder.stop(lease))

        val rawArtifacts = fixture.storageDirectory.walkTopDown()
            .filter(File::isFile)
            .joinToString("\n") { it.readText() }
        assertFalse(rawArtifacts.contains("latitude_deg"))
        assertFalse(rawArtifacts.contains("survey-point-01"))
        assertFalse(rawArtifacts.contains(SCOPE_ID))

        val output = ByteArrayOutputStream()
        val result = fixture.recorder.export(lease.sessionId, binding(17L), output)
        assertEquals(PositionFieldExportStatus.EXPORTED, result.status)
        assertEquals(2L, result.recordCount)
        assertFalse(result.partialDestinationMustBeDeleted)
        val text = output.toString(Charsets.UTF_8.name())
        val rawLines = text.trimEnd('\n').lines()
        assertEquals(4, rawLines.size)
        rawLines.forEach { line -> assertEquals(line.trim(), line) }
        val lines = rawLines.map(::JSONObject)

        val header = lines[0]
        assertEquals(POSITION_TRACE_EXPORT_SCHEMA_VERSION, header.getString("schema_version"))
        assertEquals("header", header.getString("record_type"))
        assertEquals(lease.sessionId, header.getString("session_id"))
        assertEquals(ROUTE_ID, header.getString("route_id"))
        assertEquals(POSITION_TRACE_MOUNT, header.getString("mount"))
        assertEquals(POSITION_TRACE_SOURCE_KIND, header.getString("source_kind"))
        assertFalse(header.getBoolean("synthetic_contract_only"))
        assertEquals(POSITION_TRACE_TIMEBASE, header.getString("timebase"))

        val position = lines[1]
        assertEquals("position_sample", position.getString("record_type"))
        assertEquals(1L, position.getLong("seq"))
        assertEquals(2_000L, position.getLong("measurement_elapsed_realtime_ns"))
        assertEquals(1_700_000_000_000L, position.getLong("measurement_utc_epoch_ms"))
        assertEquals("gnss", position.getString("source"))
        assertEquals(37.501, position.getJSONObject("raw_position").getDouble("latitude_deg"), 0.0)
        assertEquals("magnetic_rotation_vector", position.getJSONObject("heading").getString("source"))
        assertFalse(position.has("scope_sha256"))
        assertFalse(position.has("generation"))
        assertFalse(position.has("walk_epoch"))

        val checkpoint = lines[2]
        assertEquals("checkpoint_mark", checkpoint.getString("record_type"))
        assertEquals(2L, checkpoint.getLong("seq"))
        assertEquals("survey-point-01", checkpoint.getString("checkpoint_id"))
        assertEquals(1, checkpoint.getInt("ordinal"))
        assertEquals("stationary", checkpoint.getString("stationary_state"))
        assertEquals(1_500L, checkpoint.getLong("stationary_duration_ms"))
        assertEquals("checkpoint_gnss_anchored", checkpoint.getString("source"))

        val footer = lines[3]
        assertEquals("footer", footer.getString("record_type"))
        assertEquals(2L, footer.getLong("record_count"))
        val hashedBytes = rawLines.dropLast(1).joinToString("\n", postfix = "\n").toByteArray()
        assertEquals(sha256Hex(hashedBytes), footer.getString("content_sha256"))
        assertFalse(text.contains("scope_sha256"))
        assertFalse(text.contains(SCOPE_ID))
        assertEquals(
            File("../../../tests/fixtures/positioning_trace_contract_v2.jsonl").readText(),
            text,
        )
    }

    @Test
    fun startRequiresValidExplicitMetadataAndRandomStyleBinding() {
        val fixture = fixture("metadata_fence")
        assertNull(fixture.recorder.start(metadata().copy(mount = "CHEST"), binding(1L)))
        assertNull(fixture.recorder.start(metadata().copy(routeId = "route-home"), binding(1L)))
        assertNull(fixture.recorder.start(metadata(), RecorderBinding("actor@example.com", 1L)))
        assertNull(fixture.recorder.start(metadata().copy(syntheticContractOnly = true), binding(1L)))
        assertNotNull(fixture.recorder.start(metadata(), binding(1L)))
    }

    @Test
    fun rejectsStaleScopeGenerationWalkEpochCheckpointAndTime() {
        val ids = ArrayDeque(listOf(SESSION_ONE, SESSION_TWO))
        val generations = ArrayDeque(listOf(101L, 102L))
        val fixture = fixture(
            "lease_fence",
            idFactory = { ids.removeFirst() },
            generationFactory = { generations.removeFirst() },
        )
        val first = fixture.recorder.start(metadata(), binding(9L))!!
        fixture.elapsedNs = 2_000L
        assertTrue(fixture.recorder.append(first, recordAt(2_000L)))
        fixture.elapsedNs = 3_000L
        assertTrue(fixture.recorder.stop(first))
        val second = fixture.recorder.start(metadata(), binding(9L))!!
        fixture.elapsedNs = 4_000L
        assertFalse(fixture.recorder.append(first, recordAt(4_000L)))
        assertFalse(fixture.recorder.append(second.copy(localAccountScopeSha256 = "0".repeat(64)), recordAt(4_000L)))
        assertFalse(fixture.recorder.append(second.copy(generation = 101L), recordAt(4_000L)))
        assertFalse(fixture.recorder.append(second.copy(walkEpoch = 10L), recordAt(4_000L)))
        assertFalse(fixture.recorder.markCheckpoint(second, checkpoint(4_000L, 2)))
        assertTrue(fixture.recorder.append(second, recordAt(4_000L)))
        assertFalse(fixture.recorder.append(second, recordAt(4_000L)))
    }

    @Test
    fun restartInterruptsActiveSessionAndNeverResumesIt() {
        val root = temporaryFolder.newFolder("crash_no_resume")
        val crypto = TestFieldAead().aead
        var now = 1_000L
        var elapsed = 1_000L
        val first = recorder(root, crypto, { now }, { elapsed }, { SESSION_ONE }, { 51L })
        val oldLease = first.start(metadata(), binding(4L))!!
        elapsed = 2_000L
        assertTrue(first.append(oldLease, recordAt(elapsed)))

        now = 2_000L
        elapsed = 3_000L
        val restarted = recorder(root, crypto, { now }, { elapsed }, { SESSION_TWO }, { 52L })
        assertEquals(PositionFieldSessionStatus.INTERRUPTED, restarted.list(binding(4L)).single().status)
        assertEquals(
            PositionFieldExportStatus.NOT_COMPLETED,
            restarted.export(oldLease.sessionId, binding(4L), ByteArrayOutputStream()).status,
        )
        assertFalse(first.append(oldLease, recordAt(3_000L)))
        assertNotNull(restarted.start(metadata(), binding(5L)))
    }

    @Test
    fun epochOrMonotonicRollbackInterruptsActiveSession() {
        val epochFixture = fixture("epoch_rollback")
        val epochLease = epochFixture.recorder.start(metadata(), binding(1L))!!
        epochFixture.nowMs = 999L
        epochFixture.elapsedNs = 2_000L
        assertFalse(epochFixture.recorder.append(epochLease, recordAt(2_000L)))
        assertEquals(PositionFieldSessionStatus.INTERRUPTED, epochFixture.recorder.list(binding(1L)).single().status)

        val monotonicFixture = fixture("monotonic_rollback")
        monotonicFixture.elapsedNs = 2_000L
        val monotonicLease = monotonicFixture.recorder.start(metadata(), binding(1L))!!
        monotonicFixture.elapsedNs = 1_999L
        assertFalse(monotonicFixture.recorder.append(monotonicLease, recordAt(1_999L)))
        assertEquals(PositionFieldSessionStatus.INTERRUPTED, monotonicFixture.recorder.list(binding(1L)).single().status)
    }

    @Test
    fun restartDoesNotExposeSessionsAcrossScopeOrRecoveryGeneration() {
        val root = temporaryFolder.newFolder("binding_restart")
        val crypto = TestFieldAead().aead
        var now = 1_000L
        var elapsed = 1_000L
        val first = recorder(root, crypto, { now }, { elapsed }, { SESSION_ONE }, { 61L })
        val allowed = binding(7L)
        val lease = requireNotNull(first.start(metadata(), allowed))
        elapsed = 2_000L
        assertTrue(first.append(lease, recordAt(elapsed)))
        now = 2_000L
        elapsed = 3_000L
        assertTrue(first.stop(lease))

        val restarted = recorder(root, crypto, { now }, { elapsed }, { SESSION_TWO }, { 62L })
        val otherScope = RecorderBinding(OTHER_SCOPE_ID, 7L)
        assertTrue(restarted.list(otherScope).isEmpty())
        assertEquals(
            PositionFieldExportStatus.NOT_FOUND,
            restarted.export(lease.sessionId, otherScope, ByteArrayOutputStream()).status,
        )
        assertTrue(restarted.list(binding(8L)).isEmpty())
        assertEquals(
            PositionFieldExportStatus.NOT_FOUND,
            restarted.export(lease.sessionId, binding(8L), ByteArrayOutputStream()).status,
        )
        assertEquals(lease.sessionId, restarted.list(allowed).single().sessionId)
        assertTrue(restarted.export(lease.sessionId, allowed, ByteArrayOutputStream()).success)
    }

    @Test
    fun purgeDurabilityRequiresAFenceAndEveryCompletionStage() {
        val noDurableFence = PositionFieldPurgeDurability(
            markerFenceStored = false,
            preferenceFenceStored = false,
            purgeSucceeded = false,
        )
        assertFalse(noDurableFence.mayAttemptPurge)
        assertFalse(noDurableFence.mayUnblock)

        val purgeFailed = PositionFieldPurgeDurability(
            markerFenceStored = true,
            preferenceFenceStored = false,
            purgeSucceeded = false,
        )
        assertTrue(purgeFailed.mayAttemptPurge)
        assertFalse(purgeFailed.mayUnblock)

        val replacementCommitFailed = PositionFieldPurgeDurability(
            markerFenceStored = false,
            preferenceFenceStored = true,
            purgeSucceeded = true,
            replacementScopeStored = false,
            markerCleared = true,
        )
        assertFalse(replacementCommitFailed.mayUnblock)

        val complete = replacementCommitFailed.copy(replacementScopeStored = true)
        assertTrue(complete.mayUnblock)
    }

    @Test
    fun removesMissingOrCorruptManifestDirectories() {
        val fixture = fixture("invalid_artifacts")
        val missing = File(fixture.storageDirectory, SESSION_TWO).apply { mkdirs() }
        val corrupt = File(fixture.storageDirectory, SESSION_THREE).apply { mkdirs() }
        File(corrupt, "manifest.aead").writeText("not-json")
        assertTrue(fixture.recorder.list(binding(1L)).isEmpty())
        assertFalse(missing.exists())
        assertFalse(corrupt.exists())
    }

    @Test
    fun enforcesCountAgeAndStorageCaps() {
        val ids = ArrayDeque(listOf(SESSION_ONE, SESSION_TWO, SESSION_THREE, SESSION_FOUR))
        val fixture = fixture(
            "retention",
            idFactory = { ids.removeFirst() },
            generationFactory = { 71L + ids.size },
            maxSessionCount = 2,
            maxSessionAgeMs = 100L,
            maxStorageBytes = 8L * 1_024L,
        )
        repeat(3) { index ->
            val lease = fixture.recorder.start(metadata(), binding(1L))!!
            fixture.elapsedNs += 1_000L
            assertTrue(fixture.recorder.append(lease, recordAt(fixture.elapsedNs)))
            fixture.elapsedNs += 1_000L
            assertTrue(fixture.recorder.stop(lease))
            fixture.nowMs += 10L
        }
        assertEquals(2, fixture.recorder.list(binding(1L)).size)
        assertFalse(fixture.recorder.list(binding(1L)).any { it.sessionId == SESSION_ONE })
        fixture.nowMs += 101L
        assertTrue(fixture.recorder.list(binding(1L)).isEmpty())

        val lease = fixture.recorder.start(metadata(), binding(99L))!!
        var accepted = 0
        while (accepted < 100) {
            fixture.elapsedNs += 1_000L
            if (!fixture.recorder.append(lease, fullRecord(fixture.elapsedNs))) break
            accepted += 1
        }
        assertTrue(accepted in 1..99)
        assertTrue(fixture.storageDirectory.walkTopDown().filter(File::isFile).sumOf(File::length) <= 8L * 1_024L)
    }

    @Test
    fun failedExportFlagsPartialDestinationAndPurgeInvalidatesOnlyAfterDelete() {
        val ids = ArrayDeque(listOf(SESSION_ONE, SESSION_TWO))
        val fixture = fixture("export_failure", idFactory = { ids.removeFirst() })
        val completed = fixture.recorder.start(metadata(), binding(1L))!!
        fixture.elapsedNs = 2_000L
        assertTrue(fixture.recorder.append(completed, recordAt(fixture.elapsedNs)))
        fixture.elapsedNs = 3_000L
        assertTrue(fixture.recorder.stop(completed))
        val sourceFilesBefore = fixture.storageDirectory.walkTopDown().filter(File::isFile).count()
        val failingOutput = object : OutputStream() {
            override fun write(value: Int) = throw IOException("expected")
        }
        val failed = fixture.recorder.export(completed.sessionId, binding(1L), failingOutput)
        assertEquals(PositionFieldExportStatus.DESTINATION_WRITE_FAILED, failed.status)
        assertTrue(failed.partialDestinationMustBeDeleted)
        assertEquals(sourceFilesBefore, fixture.storageDirectory.walkTopDown().filter(File::isFile).count())
        assertTrue(fixture.recorder.export(completed.sessionId, binding(1L), ByteArrayOutputStream()).success)
        assertTrue(fixture.recorder.delete(completed.sessionId))

        val active = fixture.recorder.start(metadata(), binding(2L))!!
        assertFalse(fixture.recorder.delete(active.sessionId))
        assertTrue(fixture.recorder.purge())
        assertTrue(fixture.recorder.list(binding(2L)).isEmpty())
        fixture.elapsedNs = 4_000L
        assertFalse(fixture.recorder.append(active, recordAt(4_000L)))
    }

    private fun fixture(
        name: String,
        idFactory: () -> String = { SESSION_ONE },
        generationFactory: () -> Long = { 11L },
        maxSessionAgeMs: Long = 1_000_000L,
        maxSessionCount: Int = 20,
        maxStorageBytes: Long = 32L * 1_024L * 1_024L,
    ): Fixture {
        val fixture = Fixture(temporaryFolder.newFolder(name))
        fixture.recorder = recorder(
            fixture.root,
            TestFieldAead().aead,
            { fixture.nowMs },
            { fixture.elapsedNs },
            idFactory,
            generationFactory,
            maxSessionAgeMs,
            maxSessionCount,
            maxStorageBytes,
        )
        return fixture
    }

    private fun recorder(
        root: File,
        aead: kr.co.hanium.dreamup.walksafe.security.LocalAead,
        now: () -> Long,
        elapsed: () -> Long,
        idFactory: () -> String,
        generationFactory: () -> Long,
        maxSessionAgeMs: Long = 1_000_000L,
        maxSessionCount: Int = 20,
        maxStorageBytes: Long = 32L * 1_024L * 1_024L,
    ) = PositionFieldSessionRecorder(
        noBackupRootDirectory = root,
        aead = aead,
        elapsedRealtimeNsClock = elapsed,
        nowMillis = now,
        sessionIdFactory = idFactory,
        generationFactory = generationFactory,
        maxSessionAgeMs = maxSessionAgeMs,
        maxSessionCount = maxSessionCount,
        maxStorageBytes = maxStorageBytes,
    )

    private fun metadata() = PositioningTraceStartMetadata(
        routeId = ROUTE_ID,
        scenario = "open_sky",
        environment = "outdoor",
        direction = "forward",
        deviceModel = "SM-G981N",
        androidApi = 36,
        mount = POSITION_TRACE_MOUNT,
        sourceKind = POSITION_TRACE_SOURCE_KIND,
        syntheticContractOnly = false,
        timebase = POSITION_TRACE_TIMEBASE,
    )

    private fun binding(walkEpoch: Long) = RecorderBinding(SCOPE_ID, walkEpoch)

    private fun checkpoint(elapsed: Long, ordinal: Int) = PositioningTraceCheckpoint(
        elapsedRealtimeNs = elapsed,
        measurementElapsedRealtimeNs = elapsed,
        measurementUtcEpochMs = 1_700_000_000_000L,
        source = PositionTraceSource.CHECKPOINT_GNSS_ANCHORED,
        checkpointId = "survey-point-01",
        ordinal = ordinal,
        stationaryState = PositionStationaryState.STATIONARY,
        stationaryDurationMs = 1_500L,
    )

    private fun recordAt(elapsed: Long) = PositioningTraceRecord(
        elapsedRealtimeNs = elapsed,
        measurementElapsedRealtimeNs = elapsed,
        measurementUtcEpochMs = null,
        source = PositionTraceSource.SENSOR_MONOTONIC_ONLY,
        rawPosition = PositionTraceCoordinate(37.5, 127.0),
    )

    private fun fullRecord(elapsed: Long) = PositioningTraceRecord(
        elapsedRealtimeNs = elapsed,
        measurementElapsedRealtimeNs = elapsed,
        measurementUtcEpochMs = 1_700_000_000_000L,
        source = PositionTraceSource.GNSS,
        rawPosition = PositionTraceCoordinate(37.501, 127.001),
        filteredPosition = PositionTraceCoordinate(37.502, 127.002),
        matchedPosition = PositionTraceCoordinate(37.503, 127.003),
        accuracyMeters = 3.2,
        speedMetersPerSecond = 1.1,
        bearingDegrees = 92.0,
        gnss = PositionGnssTrace(true, 4, 31.5, PositionGnssRisk.LOW, 2.4),
        stepProfile = PositionStepProfileTrace(12, true, 0.68, 0.66, 8),
        heading = PositionHeadingTrace(91.0, PositionHeadingSource.MAGNETIC_ROTATION_VECTOR),
        stationary = PositionStationaryTrace(true, PositionStationaryState.STATIONARY, true),
    )

    private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class Fixture(val root: File) {
        var nowMs = 1_000L
        var elapsedNs = 1_000L
        lateinit var recorder: PositionFieldSessionRecorder
        val storageDirectory get() = File(root, "position_field_sessions_v1")
    }

    private companion object {
        const val ROUTE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val SCOPE_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val OTHER_SCOPE_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val SESSION_ONE = "11111111-1111-4111-8111-111111111111"
        const val SESSION_TWO = "22222222-2222-4222-8222-222222222222"
        const val SESSION_THREE = "33333333-3333-4333-8333-333333333333"
        const val SESSION_FOUR = "44444444-4444-4444-8444-444444444444"
    }
}
