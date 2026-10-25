package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositionGnssTrace
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositionHeadingTrace
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositionStationaryState
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositionStationaryTrace
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositionStepProfileTrace
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositionTraceCoordinate
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositionTraceSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositioningTraceCheckpoint
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositioningTraceRecord
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositioningTraceStartMetadata
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionFieldRecorderControllerTest {
    @Test
    fun disabledControllerNeverOpensStorageOrCollects() {
        val fixture = Fixture(enabled = false)
        fixture.controller.initialize()
        assertEquals(0, fixture.recorderFactories)
        assertFalse(fixture.controller.start(fixture.request) { error("disabled start must not read Activity authority") })
        assertTrue(fixture.controller.purgeAndRotate())
        assertTrue(fixture.storage.events.isEmpty())
        assertNull(fixture.controller.collectionBindingOrNull(fixture.authority))
    }

    @Test
    fun startRetainsTheExplicitConsentAndForegroundPermissionGates() {
        val fixture = Fixture()
        fixture.controller.initialize()
        assertFalse(fixture.controller.start(fixture.request.copy(exactCoordinateExportConfirmed = false), { fixture.authority }))
        assertFalse(fixture.controller.start(fixture.request.copy(chestCalibrationConfirmed = false), { fixture.authority }))
        assertFalse(fixture.controller.start(fixture.request, { fixture.authority.copy(foreground = false) }))
        assertFalse(fixture.controller.start(fixture.request, { fixture.authority.copy(fineLocationGranted = false) }))
        assertFalse(fixture.controller.start(fixture.request, { fixture.authority.copy(walkRuntimeActive = false) }))
        assertFalse(fixture.controller.start(fixture.request.copy(routeId = "invalid"), { fixture.authority }))
        assertEquals(0, fixture.store.startCalls)
        assertTrue(fixture.controller.start(fixture.request, { fixture.authority }))
        assertEquals("FIELD_SURVEY", fixture.store.startMetadata?.scenario)
        assertEquals("MIXED", fixture.store.startMetadata?.environment)
        assertEquals("FORWARD", fixture.store.startMetadata?.direction)
        assertEquals("Phone_Model", fixture.store.startMetadata?.deviceModel)
        assertEquals(7L, fixture.store.startBinding?.walkEpoch)
        assertEquals(SCOPE, fixture.store.startBinding?.localAccountScopeId)
    }

    @Test
    fun stopFailureRetainsLeaseAndChestConfirmationUntilARealStopSucceeds() {
        val fixture = started()
        val lease = fixture.controller.currentLeaseOrNull(fixture.authority)
        fixture.store.stopSucceeds = false
        assertFalse(fixture.controller.stop())
        assertEquals(lease, fixture.controller.currentLeaseOrNull(fixture.authority))
        assertTrue(fixture.controller.explicitChestConfirmed)
        assertEquals(0, fixture.sessionCleared)
        fixture.store.stopSucceeds = true
        assertTrue(fixture.controller.stop())
        assertFalse(fixture.controller.explicitChestConfirmed)
        assertNull(fixture.controller.currentLeaseOrNull(fixture.authority))
        assertEquals(1, fixture.sessionCleared)
        assertEquals(1, fixture.recordingStopped)
    }

    @Test
    fun collectionOwnerRejectsGatewayEpochAndForegroundChangesAndStaleLeaseStops() {
        val changes: List<(FieldRecordAuthority) -> FieldRecordAuthority> = listOf(
            { it.copy(foreground = false) },
            { it.copy(walkRuntimeActive = false) },
            { it.copy(fineLocationGranted = false) },
            { it.copy(walkEpoch = it.walkEpoch?.copy(recoveryGeneration = 8L)) },
            { it.copy(gatewayGeneration = 21L) },
        )
        changes.forEach { change ->
            val fixture = started()
            val stale = change(fixture.authority)
            assertNull(fixture.controller.collectionBindingOrNull(stale))
            assertEquals(0, fixture.store.stopCalls)
            assertNull(fixture.controller.currentLeaseOrNull(stale))
            assertEquals(1, fixture.store.stopCalls)
        }
    }

    @Test
    fun neitherDurableFenceSucceededPreventsActualPurgeAndBlocksAccountTransition() {
        val fixture = started()
        fixture.storage.markerWriteSucceeds = false
        fixture.storage.pendingWriteSucceeds = false
        assertFalse(fixture.controller.purgeAndRotate())
        assertEquals(listOf("marker", "pending:true"), fixture.storage.events)
        assertEquals(0, fixture.store.purgeCalls)
        assertTrue(fixture.controller.storageBlocked)
        assertTrue(fixture.controller.controls { fixture.authority.walkEpoch }.active)
    }

    @Test
    fun markerBeforePreferenceFenceAllowsPurgeEvenWhenPendingPreferenceCommitFails() {
        val fixture = started()
        fixture.storage.pendingWriteSucceeds = false
        assertTrue(fixture.controller.purgeAndRotate())
        assertEquals("marker", fixture.storage.events[0])
        assertEquals("pending:true", fixture.storage.events[1])
        assertEquals(1, fixture.store.purgeCalls)
        assertFalse(fixture.controller.storageBlocked)
        assertEquals(REPLACEMENT_SCOPE, fixture.storage.scope)
        assertFalse(fixture.controller.controls { fixture.authority.walkEpoch }.active)
    }

    @Test
    fun failedActualPurgeKeepsLeaseOldScopeAndDurableFence() {
        val fixture = started()
        fixture.store.purgeSucceeds = false
        assertFalse(fixture.controller.purgeAndRotate())
        assertEquals(1, fixture.store.purgeCalls)
        assertEquals(SCOPE, fixture.storage.scope)
        assertTrue(fixture.storage.marker)
        assertTrue(fixture.storage.pending)
        assertTrue(fixture.controller.storageBlocked)
        assertTrue(fixture.controller.controls { fixture.authority.walkEpoch }.active)
        assertEquals(0, fixture.sessionCleared)
    }

    @Test
    fun successfulPurgeWithFailedScopeCommitKeepsBlockedAndInvalidatesPendingExport() {
        val fixture = readyForExport()
        fixture.storage.scopeWriteSucceeds = false
        assertFalse(fixture.controller.purgeAndRotate())
        assertEquals(1, fixture.store.purgeCalls)
        assertTrue(fixture.controller.storageBlocked)
        assertEquals(SCOPE, fixture.storage.scope)
        val destination = Destination()
        fixture.controller.handleExportResult(true, destination) { fixture.authority.exportAuthentication }
        assertEquals(1, destination.cleanupCalls)
        assertEquals(0, fixture.store.exportCalls)
        assertTrue(fixture.messages.any { it.storageFailure && it.text.contains("기록은 삭제했지만") })
    }

    @Test
    fun unclearedMarkerKeepsPurgePendingAndRestartRecoversBeforeAllowingAccess() {
        val fixture = started()
        fixture.storage.markerClearSucceeds = false
        assertFalse(fixture.controller.purgeAndRotate())
        assertTrue(fixture.storage.pending)
        assertTrue(fixture.storage.marker)
        assertTrue(fixture.controller.storageBlocked)
        fixture.storage.markerClearSucceeds = true
        val restarted = fixture.newController()
        restarted.initialize()
        assertEquals(2, fixture.store.purgeCalls)
        assertFalse(restarted.storageBlocked)
        assertFalse(fixture.storage.pending)
        assertFalse(fixture.storage.marker)
    }

    @Test
    fun canceledAndUnboundSafResultsCleanTheirDestinationsWithoutExport() {
        val fixture = readyForExport()
        val canceled = Destination()
        fixture.controller.handleExportResult(false, canceled) { fixture.authority.exportAuthentication }
        assertEquals(1, canceled.cleanupCalls)
        val duplicate = Destination()
        fixture.controller.handleExportResult(true, duplicate) { fixture.authority.exportAuthentication }
        assertEquals(1, duplicate.cleanupCalls)
        assertEquals(0, fixture.store.exportCalls)
    }

    @Test
    fun changedActorGatewayOrWalkSessionRejectsSafResultWithoutWritingCoordinates() {
        val changes: List<(FieldExportAuthentication) -> FieldExportAuthentication> = listOf(
            { it.copy(actorId = "another-actor") },
            { it.copy(gatewayGeneration = 21L) },
            { it.copy(walkSessionId = "another-walk") },
        )
        changes.forEach { change ->
            val fixture = readyForExport()
            val destination = Destination()
            val current = fixture.authority.copy(exportAuthentication = change(fixture.authentication))
            fixture.controller.handleExportResult(true, destination) { current.exportAuthentication }
            assertEquals(1, destination.cleanupCalls)
            assertEquals(0, destination.writeCalls)
            assertEquals(0, fixture.store.exportCalls)
        }
    }

    @Test
    fun newerCompletedSessionRejectsOldSafSelection() {
        val fixture = readyForExport()
        fixture.store.completedSessionId = "6a6d1b9c-6ac2-4b66-8a2a-1c2d56789012"
        val destination = Destination()
        fixture.controller.handleExportResult(true, destination) { fixture.authority.exportAuthentication }
        assertEquals(1, destination.cleanupCalls)
        assertEquals(0, fixture.store.exportCalls)
    }

    @Test
    fun originalCapturedBindingExportsAfterRuntimeEpochBecomesInactive() {
        val fixture = readyForExport()
        val destination = Destination()
        val inactive = fixture.authority.copy(foreground = false, walkRuntimeActive = false, walkEpoch = null)
        fixture.controller.handleExportResult(true, destination) { inactive.exportAuthentication }
        assertEquals(1, fixture.store.exportCalls)
        assertEquals(RecorderBinding(SCOPE, 7L), fixture.store.exportBinding)
        assertEquals(0, destination.cleanupCalls)
        assertTrue(fixture.messages.last().text.contains("내보냈습니다"))
    }

    @Test
    fun failedOrPartialWriteAndCleanupFailurePreserveVisibleFailure() {
        val fixture = readyForExport()
        fixture.store.exportResult = PositionFieldExportResult(
            PositionFieldExportStatus.DESTINATION_WRITE_FAILED, 0L, true,
        )
        val destination = Destination(cleanupSucceeds = false)
        fixture.controller.handleExportResult(true, destination) { fixture.authority.exportAuthentication }
        assertEquals(1, destination.writeCalls)
        assertEquals(1, destination.cleanupCalls)
        assertTrue(fixture.messages.any { it.storageFailure && it.text.contains("정확한 위치가 남아") })
        assertTrue(fixture.messages.last().text.contains("내보내기에 실패"))
    }

    @Test
    fun listFailureBlocksStorageAndCleansTheSelectedDestination() {
        val fixture = readyForExport()
        fixture.store.listFails = true
        val destination = Destination()
        fixture.controller.handleExportResult(true, destination) { fixture.authority.exportAuthentication }
        assertTrue(fixture.controller.storageBlocked)
        assertEquals(1, destination.cleanupCalls)
        assertEquals(0, fixture.store.exportCalls)
    }

    @Test
    fun canceledOrMissingSafResultDoesNotReadAccountAuthority() {
        val fixture = readyForExport()
        fixture.controller.handleExportResult(false, Destination()) { error("canceled result must not read account authority") }
        assertFalse(fixture.controller.hasPendingExport)
        fixture.controller.handleExportResult(true, Destination()) { error("unbound result must not read account authority") }
        assertEquals(0, fixture.store.exportCalls)
        assertNotNull(fixture.controller.beginExport({ fixture.authority.walkEpoch }, { fixture.authentication }))
        fixture.controller.handleExportResult(true, null) { error("missing destination must not read account authority") }
        assertFalse(fixture.controller.hasPendingExport)
    }

    @Test
    fun pendingOrBlockedExportDefersEpochAndAccountAuthorityReads() {
        val fixture = readyForExport()
        assertTrue(fixture.controller.hasPendingExport)
        assertNull(fixture.controller.beginExport({ error("pending export must not read epoch") },
            { error("pending export must not read authentication") }))
        fixture.store.purgeSucceeds = false
        assertFalse(fixture.controller.purgeAndRotate())
        assertFalse(fixture.controller.start(fixture.request) { error("blocked start must not read Activity authority") })
        fixture.controller.controls { error("blocked controls must not read epoch") }
        assertNull(fixture.controller.beginExport({ error("blocked export must not read epoch") },
            { error("blocked export must not read authentication") }))
        fixture.controller.handleExportResult(true, Destination()) { error("blocked result must not read authentication") }
    }

    @Test
    fun traceWithoutLeaseDoesNotReadActivityAuthority() {
        val fixture = Fixture()
        fixture.controller.initialize()
        fixture.controller.appendMotion({ error("no lease must not read Activity authority") }, motion(1L))
        fixture.controller.appendGnss({ error("no lease must not read Activity authority") }, gnss(1L, 1L))
        assertTrue(fixture.store.records.isEmpty())
        assertEquals(0, fixture.store.stopCalls)
    }

    @Test
    fun utcAnchorIsStableAcrossReplayedGnssAndTraceWriteTimesRemainMonotonic() {
        val fixture = started()
        fixture.controller.appendMotion({ fixture.authority }, motion(10L))
        fixture.controller.appendGnss({ fixture.authority }, gnss(10_500_000L, 1_000L))
        fixture.controller.appendGnss({ fixture.authority }, gnss(11_500_000L, 9_000L))
        fixture.controller.appendMotion({ fixture.authority }, motion(10L))
        val records = fixture.store.records
        assertEquals(PositionTraceSource.SENSOR_MONOTONIC_ONLY, records[0].source)
        assertNull(records[0].measurementUtcEpochMs)
        assertEquals(9_000L, records[2].measurementUtcEpochMs)
        assertEquals(PositionTraceSource.SENSOR_GNSS_ANCHORED, records[3].source)
        assertEquals(999L, records[3].measurementUtcEpochMs)
        assertEquals(10_000_000L, records[3].measurementElapsedRealtimeNs)
        records.zipWithNext().forEach { (first, second) -> assertTrue(first.elapsedRealtimeNs < second.elapsedRealtimeNs) }
    }

    @Test
    fun checkpointRejectsStationaryEvidenceBeforeStartAndAdvancesOrdinalOnlyOnSuccess() {
        val fixture = started()
        fixture.nowMs = 3_000L
        fixture.nowNs = 3_000_000_000L
        fixture.controller.markCheckpoint(fixture.authority, PositionStationaryState.STATIONARY, 999L)
        assertTrue(fixture.store.checkpoints.isEmpty())
        fixture.controller.markCheckpoint(fixture.authority, PositionStationaryState.CANDIDATE, 1_000L)
        assertTrue(fixture.store.checkpoints.isEmpty())
        fixture.store.checkpointSucceeds = false
        fixture.controller.markCheckpoint(fixture.authority, PositionStationaryState.STATIONARY, 1_000L)
        fixture.store.checkpointSucceeds = true
        fixture.controller.markCheckpoint(fixture.authority, PositionStationaryState.STATIONARY, 1_000L)
        fixture.controller.markCheckpoint(fixture.authority, PositionStationaryState.STATIONARY, 1_000L)
        assertEquals(listOf(1, 1, 2), fixture.store.checkpoints.map { it.ordinal })
        assertEquals(listOf("CP001", "CP001", "CP002"), fixture.store.checkpoints.map { it.checkpointId })
        assertEquals(PositionTraceSource.CHECKPOINT_MONOTONIC_ONLY, fixture.store.checkpoints.last().source)
    }

    @Test
    fun appendFailureStopsRecordingAndTelemetryThrottleResetsExplicitly() {
        val fixture = started()
        assertTrue(fixture.controller.shouldAppendMotionTelemetry(fixture.authority, 1_000L, false))
        assertFalse(fixture.controller.shouldAppendMotionTelemetry(fixture.authority, 1_100L, false))
        assertTrue(fixture.controller.shouldAppendMotionTelemetry(fixture.authority, 1_100L, true))
        fixture.controller.resetMotionTelemetry()
        assertTrue(fixture.controller.shouldAppendMotionTelemetry(fixture.authority, 1_100L, false))
        fixture.store.appendSucceeds = false
        fixture.controller.appendMotion({ fixture.authority }, motion(1L))
        assertEquals(1, fixture.store.stopCalls)
        assertNull(fixture.controller.currentLeaseOrNull(fixture.authority))
        assertTrue(fixture.messages.last().text.contains("기록을 종료했습니다"))
    }

    private fun started(): Fixture = Fixture().also {
        it.controller.initialize()
        assertTrue(it.controller.start(it.request, { it.authority }))
    }

    private fun readyForExport(): Fixture = started().also {
        assertTrue(it.controller.stop())
        assertNotNull(it.controller.beginExport({ it.authority.walkEpoch }, { it.authority.exportAuthentication }))
    }

    private class Fixture(enabled: Boolean = true) {
        val storage = ScopeStorage()
        val store = Store()
        val messages = mutableListOf<PositionFieldMessage>()
        var nowMs = 1_000L
        var nowNs = 1_000_000_000L
        var recorderFactories = 0
        var sessionCleared = 0
        var recordingStopped = 0
        val authentication = FieldExportAuthentication("walk", "actor", 20L)
        val authority = FieldRecordAuthority(true, true, true, WalkRuntimeEpoch("walk", 7L), 20L, authentication)
        val request = PositionFieldStartRequest(ROUTE, true, true, "Phone Model", 35)
        val controller = newController(enabled)

        fun newController(enabled: Boolean = true) = PositionFieldRecorderController(
            enabled = enabled,
            scopeStorage = storage,
            recorderFactory = { recorderFactories += 1; store },
            elapsedRealtimeMs = { nowMs },
            elapsedRealtimeNs = { nowNs },
            scopeIdFactory = { REPLACEMENT_SCOPE },
            onSessionCleared = { sessionCleared += 1 },
            onRecordingStopped = { recordingStopped += 1 },
            onMessage = messages::add,
        )
    }

    private class ScopeStorage : PositionFieldScopeStorage {
        var scope: String? = SCOPE
        var pending = false
        var marker = false
        var markerWriteSucceeds = true
        var pendingWriteSucceeds = true
        var scopeWriteSucceeds = true
        var markerClearSucceeds = true
        val events = mutableListOf<String>()
        override fun localScopeId() = scope
        override fun purgePending() = pending
        override fun purgeMarkerExists() = marker
        override fun persistPurgeMarker(): Boolean {
            events += "marker"
            if (markerWriteSucceeds) marker = true
            return markerWriteSucceeds
        }
        override fun storePurgePending(pending: Boolean): Boolean {
            events += "pending:$pending"
            if (pendingWriteSucceeds) this.pending = pending
            return pendingWriteSucceeds
        }
        override fun storeScope(scope: String, clearPurgePending: Boolean): Boolean {
            events += "scope:$clearPurgePending"
            if (scopeWriteSucceeds) {
                this.scope = scope
                if (clearPurgePending) pending = false
            }
            return scopeWriteSucceeds
        }
        override fun clearPurgeMarker(): Boolean {
            events += "clear-marker"
            if (markerClearSucceeds) marker = false
            return markerClearSucceeds
        }
    }

    private class Store : PositionFieldRecorderStore {
        var startCalls = 0
        var stopCalls = 0
        var purgeCalls = 0
        var exportCalls = 0
        var stopSucceeds = true
        var purgeSucceeds = true
        var appendSucceeds = true
        var checkpointSucceeds = true
        var listFails = false
        var startMetadata: PositioningTraceStartMetadata? = null
        var startBinding: RecorderBinding? = null
        var exportBinding: RecorderBinding? = null
        var completedSessionId: String? = null
        var exportResult = PositionFieldExportResult(PositionFieldExportStatus.EXPORTED, 0L, false)
        val records = mutableListOf<PositioningTraceRecord>()
        val checkpoints = mutableListOf<PositioningTraceCheckpoint>()
        override fun start(metadata: PositioningTraceStartMetadata, binding: RecorderBinding): PositionFieldSessionLease {
            startCalls += 1
            startMetadata = metadata
            startBinding = binding
            return PositionFieldSessionLease(SESSION, "hash", 55L, binding.walkEpoch)
        }
        override fun stop(lease: PositionFieldSessionLease): Boolean {
            stopCalls += 1
            if (stopSucceeds) completedSessionId = lease.sessionId
            return stopSucceeds
        }
        override fun purge(): Boolean { purgeCalls += 1; if (purgeSucceeds) completedSessionId = null; return purgeSucceeds }
        override fun list(binding: RecorderBinding): List<PositionFieldSessionSummary> {
            check(!listFails) { "list failed" }
            return completedSessionId?.let {
                listOf(PositionFieldSessionSummary(it, PositionFieldSessionStatus.COMPLETED, ROUTE, binding.walkEpoch, 1L, 2L, 0L))
            } ?: emptyList()
        }
        override fun append(lease: PositionFieldSessionLease, record: PositioningTraceRecord): Boolean {
            records += record
            return appendSucceeds
        }
        override fun markCheckpoint(lease: PositionFieldSessionLease, checkpoint: PositioningTraceCheckpoint): Boolean {
            checkpoints += checkpoint
            return checkpointSucceeds
        }
        override fun export(sessionId: String, binding: RecorderBinding, output: OutputStream): PositionFieldExportResult {
            exportCalls += 1
            exportBinding = binding
            output.write("coordinates".toByteArray())
            return exportResult
        }
    }

    private class Destination(private val cleanupSucceeds: Boolean = true) : PositionFieldExportDestination {
        var writeCalls = 0
        var cleanupCalls = 0
        override fun write(export: (OutputStream) -> PositionFieldExportResult?): PositionFieldExportResult? {
            writeCalls += 1
            return ByteArrayOutputStream().use(export)
        }
        override fun deleteOrTruncate(): Boolean { cleanupCalls += 1; return cleanupSucceeds }
    }

    private fun motion(ms: Long) = PositionFieldMotionSample(
        ms, null, null, true, PositionStepProfileTrace(), PositionHeadingTrace(), PositionStationaryTrace(),
    )

    private fun gnss(ns: Long, utcMs: Long) = PositionFieldGnssSample(
        ns, utcMs, PositionTraceCoordinate(37.0, 127.0), PositionTraceCoordinate(37.0, 127.0),
        null, null, null, null, PositionGnssTrace(), PositionStepProfileTrace(),
        PositionHeadingTrace(), PositionStationaryTrace(),
    )

    private companion object {
        const val SCOPE = "cf01f611-5d2d-40f8-9c78-0c2d56789012"
        const val REPLACEMENT_SCOPE = "df01f611-5d2d-40f8-9c78-0c2d56789012"
        const val ROUTE = "aa01f611-5d2d-40f8-9c78-0c2d56789012"
        const val SESSION = "bb01f611-5d2d-40f8-9c78-0c2d56789012"
    }
}
