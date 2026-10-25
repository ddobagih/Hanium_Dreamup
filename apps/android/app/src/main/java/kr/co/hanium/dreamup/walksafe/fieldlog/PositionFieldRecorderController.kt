package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.POSITION_TRACE_MOUNT
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.POSITION_TRACE_SOURCE_KIND
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.POSITION_TRACE_TIMEBASE
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

/** Activity-observed facts; local storage scope and recording leases belong to the controller. */
internal data class FieldRecordAuthority(
    val foreground: Boolean,
    val walkRuntimeActive: Boolean,
    val fineLocationGranted: Boolean,
    val walkEpoch: WalkRuntimeEpoch?,
    val gatewayGeneration: Long,
    val exportAuthentication: FieldExportAuthentication? = null,
)

/** Supplied only after the Activity has checked account deletion, session and permission policies. */
internal data class FieldExportAuthentication(
    val walkSessionId: String,
    val actorId: String,
    val gatewayGeneration: Long,
)

internal data class PositionFieldStartRequest(
    val routeId: String,
    val exactCoordinateExportConfirmed: Boolean,
    val chestCalibrationConfirmed: Boolean,
    val deviceModel: String,
    val androidApi: Int,
)

internal data class PositionFieldCollectionBinding(
    val walkEpoch: WalkRuntimeEpoch,
    val localScopeId: String,
)

internal data class PositionFieldControls(
    val active: Boolean,
    val storageBlocked: Boolean,
    val canExport: Boolean,
)

internal data class PositionFieldMessage(val text: String, val storageFailure: Boolean = false)

/** Sensor and route acceptance decisions are made by their existing owners before constructing this. */
internal data class PositionFieldGnssSample(
    val measurementElapsedRealtimeNs: Long,
    val measurementUtcEpochMs: Long,
    val rawPosition: PositionTraceCoordinate,
    val filteredPosition: PositionTraceCoordinate,
    val matchedPosition: PositionTraceCoordinate?,
    val accuracyMeters: Double?,
    val speedMetersPerSecond: Double?,
    val bearingDegrees: Double?,
    val gnss: PositionGnssTrace,
    val stepProfile: PositionStepProfileTrace,
    val heading: PositionHeadingTrace,
    val stationary: PositionStationaryTrace,
)

internal data class PositionFieldMotionSample(
    val measurementElapsedRealtimeMs: Long,
    val filteredPosition: PositionTraceCoordinate?,
    val matchedPosition: PositionTraceCoordinate?,
    val routeMatchEvaluated: Boolean,
    val stepProfile: PositionStepProfileTrace,
    val heading: PositionHeadingTrace,
    val stationary: PositionStationaryTrace,
)

internal interface PositionFieldRecorderStore {
    fun start(metadata: PositioningTraceStartMetadata, binding: RecorderBinding): PositionFieldSessionLease?
    fun stop(lease: PositionFieldSessionLease): Boolean
    fun purge(): Boolean
    fun list(binding: RecorderBinding): List<PositionFieldSessionSummary>
    fun append(lease: PositionFieldSessionLease, record: PositioningTraceRecord): Boolean
    fun markCheckpoint(lease: PositionFieldSessionLease, checkpoint: PositioningTraceCheckpoint): Boolean
    fun export(sessionId: String, binding: RecorderBinding, output: OutputStream): PositionFieldExportResult
}

internal class PositionFieldSessionRecorderStore(
    private val recorder: PositionFieldSessionRecorder,
) : PositionFieldRecorderStore {
    override fun start(metadata: PositioningTraceStartMetadata, binding: RecorderBinding) =
        recorder.start(metadata, binding)
    override fun stop(lease: PositionFieldSessionLease) = recorder.stop(lease)
    override fun purge() = recorder.purge()
    override fun list(binding: RecorderBinding) = recorder.list(binding)
    override fun append(lease: PositionFieldSessionLease, record: PositioningTraceRecord) =
        recorder.append(lease, record)
    override fun markCheckpoint(lease: PositionFieldSessionLease, checkpoint: PositioningTraceCheckpoint) =
        recorder.markCheckpoint(lease, checkpoint)
    override fun export(sessionId: String, binding: RecorderBinding, output: OutputStream) =
        recorder.export(sessionId, binding, output)
}

internal interface PositionFieldScopeStorage {
    fun localScopeId(): String?
    fun purgePending(): Boolean
    fun purgeMarkerExists(): Boolean
    fun persistPurgeMarker(): Boolean
    fun storePurgePending(pending: Boolean): Boolean
    fun storeScope(scope: String, clearPurgePending: Boolean): Boolean
    fun clearPurgeMarker(): Boolean
}

/** The platform adapter binds a single SAF destination and owns stream closure and deletion. */
internal interface PositionFieldExportDestination {
    fun write(export: (OutputStream) -> PositionFieldExportResult?): PositionFieldExportResult?
    fun deleteOrTruncate(): Boolean
}

/** Owns the debug recorder lifecycle, durable purge fence, trace timebase and pending SAF authority. */
internal class PositionFieldRecorderController(
    private val enabled: Boolean,
    private val scopeStorage: PositionFieldScopeStorage,
    private val recorderFactory: () -> PositionFieldRecorderStore,
    private val elapsedRealtimeMs: () -> Long,
    private val elapsedRealtimeNs: () -> Long,
    private val scopeIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val onSessionCleared: () -> Unit = {},
    private val onControlsChanged: () -> Unit = {},
    private val onRecordingStopped: () -> Unit = {},
    private val onMessage: (PositionFieldMessage) -> Unit = {},
) {
    private var recorder: PositionFieldRecorderStore? = null
    var storageBlocked: Boolean = false
        private set
    private var localScopeId: String? = null
    private var recording: Recording? = null
    private var checkpointOrdinal = 0
    private var lastTraceElapsedNs = -1L
    private var gnssAnchorElapsedNs: Long? = null
    private var gnssAnchorUtcEpochMs: Long? = null
    private var lastMotionTelemetryAtMs: Long? = null
    private var exportGeneration = 0L
    private var pendingExport: PendingExport? = null

    private data class Recording(
        val lease: PositionFieldSessionLease,
        val walkEpoch: WalkRuntimeEpoch,
        val gatewayGeneration: Long,
        val localScopeId: String,
        val startedAtElapsedMs: Long,
    )

    private data class ExportAuthority(
        val localScopeId: String,
        val authentication: FieldExportAuthentication,
    )

    private data class PendingExport(
        val sessionId: String,
        val generation: Long,
        val binding: RecorderBinding,
        val authority: ExportAuthority,
    )

    val explicitChestConfirmed: Boolean get() = recording != null
    val hasPendingExport: Boolean get() = pendingExport != null

    fun initialize() {
        if (!enabled || recorder != null) return
        val purgePending = scopeStorage.purgePending() || scopeStorage.purgeMarkerExists()
        storageBlocked = purgePending
        val storedScope = scopeStorage.localScopeId()?.takeIf(::isCanonicalScope)
        localScopeId = storedScope
        recorder = runCatching(recorderFactory).getOrNull()
        if (recorder == null) {
            storageBlocked = true
            return
        }
        if (purgePending) {
            val recoveryDurability = PositionFieldPurgeDurability(
                markerFenceStored = scopeStorage.purgeMarkerExists(),
                preferenceFenceStored = scopeStorage.purgePending(),
            )
            if (!completePendingPurge(recoveryDurability)) {
                failure("이전 위치 평가 기록 삭제가 완료되지 않아 저장소 접근을 차단했습니다.")
            }
            return
        }
        if (storedScope != null) {
            storageBlocked = false
            return
        }
        val generated = scopeIdFactory()
        if (!scopeStorage.storeScope(generated, clearPurgePending = false)) {
            storageBlocked = true
            return
        }
        localScopeId = generated
        storageBlocked = false
    }

    fun start(request: PositionFieldStartRequest, authority: () -> FieldRecordAuthority): Boolean {
        if (!enabled) return false
        if (storageBlocked) {
            failure("위치 평가 기록 저장소가 차단되어 시작할 수 없습니다.")
            return false
        }
        val store = recorder ?: return false
        val observedAuthority = authority()
        if (!observedAuthority.foreground || !observedAuthority.walkRuntimeActive || !observedAuthority.fineLocationGranted) {
            message("활성 보행과 정확한 위치 권한이 있을 때만 위치 평가 기록을 시작할 수 있습니다.")
            return false
        }
        if (!request.exactCoordinateExportConfirmed) {
            message("정확한 좌표가 평문으로 내보내진다는 경고를 먼저 확인하세요.")
            return false
        }
        if (!request.chestCalibrationConfirmed) {
            message("가슴 장착과 figure-8 자력계 보정 완료를 먼저 확인하세요.")
            return false
        }
        val routeId = runCatching { UUID.fromString(request.routeId.trim()).toString() }.getOrNull()
        if (routeId == null) {
            message("위치 평가용 opaque route UUID를 입력하세요.")
            return false
        }
        val walkEpoch = observedAuthority.walkEpoch ?: return false
        val scope = localScopeId ?: return false
        val metadata = PositioningTraceStartMetadata(
            routeId = routeId,
            scenario = "FIELD_SURVEY",
            environment = "MIXED",
            direction = "FORWARD",
            deviceModel = request.deviceModel.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
                .ifBlank { "android_device" },
            androidApi = request.androidApi,
            mount = POSITION_TRACE_MOUNT,
            sourceKind = POSITION_TRACE_SOURCE_KIND,
            syntheticContractOnly = false,
            timebase = POSITION_TRACE_TIMEBASE,
        )
        val lease = store.start(metadata, RecorderBinding(scope, walkEpoch.recoveryGeneration))
        if (lease == null) {
            message("위치 평가 기록을 시작하지 못했습니다.")
            return false
        }
        recording = Recording(lease, walkEpoch, observedAuthority.gatewayGeneration, scope, elapsedRealtimeMs())
        resetTraceState()
        return true
    }

    fun collectionBindingOrNull(authority: FieldRecordAuthority): PositionFieldCollectionBinding? {
        if (!enabled || storageBlocked) return null
        val active = recording ?: return null
        if (!authority.foreground || !authority.walkRuntimeActive || !authority.fineLocationGranted ||
            authority.walkEpoch != active.walkEpoch || authority.gatewayGeneration != active.gatewayGeneration ||
            localScopeId == null || localScopeId != active.localScopeId
        ) return null
        return PositionFieldCollectionBinding(active.walkEpoch, active.localScopeId)
    }

    fun currentLeaseOrNull(authority: FieldRecordAuthority): PositionFieldSessionLease? {
        if (!enabled || storageBlocked) return null
        val active = recording ?: return null
        if (collectionBindingOrNull(authority) != null) return active.lease
        stop()
        return null
    }

    fun stop(): Boolean {
        val active = recording ?: return true
        if (recorder?.stop(active.lease) != true) {
            failure("위치 평가 기록 종료에 실패했습니다. 다시 종료하거나 전체 삭제를 시도하세요.")
            onControlsChanged()
            return false
        }
        clearRecording()
        onControlsChanged()
        onRecordingStopped()
        return true
    }

    fun purgeAndRotate(): Boolean {
        if (!enabled) return true
        storageBlocked = true
        val markerStored = scopeStorage.persistPurgeMarker()
        val pendingStored = scopeStorage.storePurgePending(true)
        val durability = PositionFieldPurgeDurability(
            markerFenceStored = markerStored,
            preferenceFenceStored = pendingStored,
        )
        if (!durability.mayAttemptPurge) {
            failure("위치 평가 기록 삭제 상태를 영속화하지 못해 계정 전환을 중단했습니다.")
            onControlsChanged()
            return false
        }
        return completePendingPurge(durability)
    }

    private fun completePendingPurge(initialDurability: PositionFieldPurgeDurability): Boolean {
        storageBlocked = true
        var durability = initialDurability
        val purged = recorder?.purge() == true
        durability = durability.copy(purgeSucceeded = purged)
        if (!purged) {
            failure("위치 평가 기록 삭제가 완료되지 않았습니다. 저장소 접근을 차단했습니다.")
            onControlsChanged()
            return false
        }
        clearRecording()
        pendingExport = null
        onControlsChanged()
        exportGeneration += 1L
        val replacement = scopeIdFactory()
        val stored = scopeStorage.storeScope(replacement, clearPurgePending = true)
        durability = durability.copy(replacementScopeStored = stored)
        if (!stored) {
            failure("위치 평가 기록은 삭제했지만 새 로컬 범위를 저장하지 못해 기능을 차단했습니다.")
            onControlsChanged()
            return false
        }
        val markerCleared = scopeStorage.clearPurgeMarker()
        durability = durability.copy(markerCleared = markerCleared)
        if (!durability.mayUnblock) {
            scopeStorage.storePurgePending(true)
            failure("위치 평가 기록 삭제 표식을 해제하지 못해 기능을 차단했습니다.")
            onControlsChanged()
            return false
        }
        localScopeId = replacement
        storageBlocked = false
        onControlsChanged()
        return true
    }

    fun beginExport(
        walkEpoch: () -> WalkRuntimeEpoch?,
        exportAuthentication: () -> FieldExportAuthentication?,
    ): String? {
        if (!enabled) return null
        if (storageBlocked) {
            failure("삭제 미완료 상태라 위치 평가 기록을 내보낼 수 없습니다.")
            return null
        }
        if (pendingExport != null) return null
        val binding = recorderBindingOrNull(walkEpoch) ?: run {
            failure("현재 보행 세션에 결속된 위치 평가 기록만 내보낼 수 있습니다.")
            return null
        }
        val summaries = runCatching { recorder?.list(binding) }.getOrElse {
            storageBlocked = true
            failure("위치 평가 기록 목록을 확인하지 못했습니다.")
            return null
        }
        val summary = summaries?.firstOrNull { it.status == PositionFieldSessionStatus.COMPLETED }
        if (summary == null) {
            message("내보낼 완료된 위치 평가 세션이 없습니다.")
            return null
        }
        val exportAuthority = exportAuthorityOrNull(exportAuthentication()) ?: run {
            message("같은 로그인 계정에서 위치 기록을 내보내세요.")
            return null
        }
        val generation = ++exportGeneration
        pendingExport = PendingExport(summary.sessionId, generation, binding, exportAuthority)
        onControlsChanged()
        return summary.sessionId
    }

    fun exportLaunchFailed() {
        pendingExport = null
        onControlsChanged()
        message("위치 평가 기록 내보내기를 시작하지 못했습니다.")
    }

    fun handleExportResult(
        accepted: Boolean,
        destination: PositionFieldExportDestination?,
        exportAuthentication: () -> FieldExportAuthentication?,
    ) {
        val pending = pendingExport
        pendingExport = null
        onControlsChanged()
        if (!accepted) {
            destination?.let(::cleanDestination)
            return
        }
        if (destination == null) return
        if (storageBlocked) {
            cleanDestination(destination)
            return
        }
        if (pending == null) {
            cleanDestination(destination)
            return
        }
        val currentAuthority = exportAuthorityOrNull(exportAuthentication())
        val currentBinding = pending?.binding
        if (currentBinding == null || currentAuthority == null || currentAuthority != pending.authority ||
            currentBinding.localAccountScopeId != currentAuthority.localScopeId
        ) {
            cleanDestination(destination)
            return
        }
        // Revalidate the captured binding even if the runtime epoch is no longer active after SAF.
        val latestCompletedSessionId = runCatching { recorder?.list(currentBinding) }.getOrElse {
            storageBlocked = true
            cleanDestination(destination)
            failure("위치 평가 기록 목록을 다시 확인하지 못했습니다.")
            return
        }?.firstOrNull { it.status == PositionFieldSessionStatus.COMPLETED }?.sessionId
        if (pending.generation != exportGeneration || pending.sessionId != latestCompletedSessionId) {
            cleanDestination(destination)
            return
        }
        val result = runCatching {
            destination.write { output -> recorder?.export(pending.sessionId, currentBinding, output) }
        }.getOrNull()
        if (result == null || !result.success || result.partialDestinationMustBeDeleted) {
            cleanDestination(destination)
            message("위치 평가 기록 내보내기에 실패했습니다.")
            return
        }
        message("위치 평가 기록을 선택한 문서에 내보냈습니다.")
    }

    fun markCheckpoint(
        authority: FieldRecordAuthority,
        stationaryState: PositionStationaryState,
        stationarySinceMs: Long?,
    ) {
        val lease = currentLeaseOrNull(authority) ?: return
        val nowMs = elapsedRealtimeMs()
        val sessionStartedAtMs = recording?.startedAtElapsedMs
        val stationaryDurationMs = stationarySinceMs?.let { nowMs - it } ?: 0L
        if (stationaryState != PositionStationaryState.STATIONARY || sessionStartedAtMs == null ||
            stationarySinceMs == null || stationarySinceMs < sessionStartedAtMs ||
            stationaryDurationMs < CHECKPOINT_STATIONARY_MS
        ) {
            message("1.5초 이상 완전히 정지한 뒤 기준점을 기록하세요.")
            return
        }
        val ordinal = checkpointOrdinal
        val measurementElapsedRealtimeNs = elapsedRealtimeNs().coerceAtLeast(0L)
        val measurementUtcEpochMs = utcEpochMsFor(measurementElapsedRealtimeNs)
        val checkpoint = PositioningTraceCheckpoint(
            elapsedRealtimeNs = nextTraceElapsedNs(measurementElapsedRealtimeNs),
            measurementElapsedRealtimeNs = measurementElapsedRealtimeNs,
            measurementUtcEpochMs = measurementUtcEpochMs,
            source = if (measurementUtcEpochMs == null) PositionTraceSource.CHECKPOINT_MONOTONIC_ONLY
                else PositionTraceSource.CHECKPOINT_GNSS_ANCHORED,
            checkpointId = String.format(Locale.US, "CP%03d", ordinal + 1),
            ordinal = ordinal + 1,
            stationaryState = stationaryState,
            stationaryDurationMs = stationaryDurationMs,
        )
        if (recorder?.markCheckpoint(lease, checkpoint) == true) {
            checkpointOrdinal += 1
            message("${checkpoint.checkpointId} 기준점을 기록했습니다.")
        } else {
            message("기준점을 기록하지 못했습니다.")
        }
    }

    fun controls(walkEpoch: () -> WalkRuntimeEpoch?): PositionFieldControls {
        val active = recording != null
        val binding = recorderBindingOrNull(walkEpoch)
        val hasCompletedSession = binding != null && runCatching {
            recorder?.list(binding)?.any { it.status == PositionFieldSessionStatus.COMPLETED } == true
        }.getOrDefault(false)
        return PositionFieldControls(active, storageBlocked,
            !active && !storageBlocked && pendingExport == null && hasCompletedSession)
    }

    fun appendGnss(authority: () -> FieldRecordAuthority, sample: PositionFieldGnssSample) {
        if (sample.measurementElapsedRealtimeNs < 0L || sample.measurementUtcEpochMs < 0L) return
        // One session mapping prevents a replayed sensor timestamp from changing its UTC rounding.
        if (gnssAnchorElapsedNs == null || gnssAnchorUtcEpochMs == null) {
            gnssAnchorElapsedNs = sample.measurementElapsedRealtimeNs
            gnssAnchorUtcEpochMs = sample.measurementUtcEpochMs
        }
        appendRecord(authority, PositioningTraceRecord(
            elapsedRealtimeNs = nextTraceElapsedNs(sample.measurementElapsedRealtimeNs),
            measurementElapsedRealtimeNs = sample.measurementElapsedRealtimeNs,
            measurementUtcEpochMs = sample.measurementUtcEpochMs,
            source = PositionTraceSource.GNSS,
            rawPosition = sample.rawPosition,
            filteredPosition = sample.filteredPosition,
            matchedPosition = sample.matchedPosition,
            accuracyMeters = sample.accuracyMeters,
            speedMetersPerSecond = sample.speedMetersPerSecond,
            bearingDegrees = sample.bearingDegrees,
            gnss = sample.gnss,
            stepProfile = sample.stepProfile,
            heading = sample.heading,
            stationary = sample.stationary,
        ))
    }

    fun appendMotion(authority: () -> FieldRecordAuthority, sample: PositionFieldMotionSample) {
        val measurementElapsedRealtimeNs = sample.measurementElapsedRealtimeMs
            .takeIf { it >= 0L && it <= Long.MAX_VALUE / 1_000_000L }?.times(1_000_000L) ?: return
        val measurementUtcEpochMs = utcEpochMsFor(measurementElapsedRealtimeNs)
        appendRecord(authority, PositioningTraceRecord(
            elapsedRealtimeNs = nextTraceElapsedNs(measurementElapsedRealtimeNs),
            measurementElapsedRealtimeNs = measurementElapsedRealtimeNs,
            measurementUtcEpochMs = measurementUtcEpochMs,
            source = if (measurementUtcEpochMs == null) PositionTraceSource.SENSOR_MONOTONIC_ONLY
                else PositionTraceSource.SENSOR_GNSS_ANCHORED,
            filteredPosition = sample.filteredPosition,
            matchedPosition = sample.matchedPosition,
            routeMatchEvaluated = true.takeIf { sample.routeMatchEvaluated },
            stepProfile = sample.stepProfile,
            heading = sample.heading,
            stationary = sample.stationary,
        ))
    }

    fun shouldAppendMotionTelemetry(authority: FieldRecordAuthority, nowMs: Long, force: Boolean): Boolean {
        val telemetryDue = lastMotionTelemetryAtMs?.let { nowMs - it >= 1_000L } ?: true
        if (collectionBindingOrNull(authority) == null || (!force && !telemetryDue)) return false
        lastMotionTelemetryAtMs = nowMs
        return true
    }

    fun resetMotionTelemetry() { lastMotionTelemetryAtMs = null }

    private fun appendRecord(authority: () -> FieldRecordAuthority, record: PositioningTraceRecord) {
        if (!enabled || storageBlocked || recording == null) return
        val lease = currentLeaseOrNull(authority()) ?: return
        if (recorder?.append(lease, record) != true) {
            val stopped = stop()
            failure(if (stopped) {
                "위치 기록 저장에 실패해 기록을 종료했습니다. 완료된 기록을 내보내고 저장 공간을 확인하세요."
            } else {
                "위치 기록 저장과 종료에 실패했습니다. 기록 종료를 다시 시도하세요."
            })
        }
    }

    private fun recorderBindingOrNull(walkEpoch: () -> WalkRuntimeEpoch?): RecorderBinding? {
        if (!enabled || storageBlocked) return null
        val scope = localScopeId ?: return null
        val epoch = walkEpoch() ?: return null
        return RecorderBinding(scope, epoch.recoveryGeneration)
    }

    private fun exportAuthorityOrNull(authentication: FieldExportAuthentication?): ExportAuthority? {
        if (!enabled || storageBlocked) return null
        val scope = localScopeId ?: return null
        if (authentication == null) return null
        return ExportAuthority(scope, authentication)
    }

    private fun nextTraceElapsedNs(observedElapsedRealtimeNs: Long): Long {
        val nowNs = elapsedRealtimeNs().coerceAtLeast(0L)
        val next = maxOf(nowNs, observedElapsedRealtimeNs.coerceAtLeast(0L), lastTraceElapsedNs + 1L)
        lastTraceElapsedNs = next
        return next
    }

    private fun utcEpochMsFor(measurementElapsedRealtimeNs: Long): Long? {
        val anchorElapsedNs = gnssAnchorElapsedNs ?: return null
        val anchorUtcEpochMs = gnssAnchorUtcEpochMs ?: return null
        val deltaNs = runCatching { Math.subtractExact(measurementElapsedRealtimeNs, anchorElapsedNs) }
            .getOrNull() ?: return null
        val deltaMs = Math.floorDiv(deltaNs, 1_000_000L)
        return runCatching { Math.addExact(anchorUtcEpochMs, deltaMs) }.getOrNull()?.takeIf { it >= 0L }
    }

    private fun clearRecording() {
        recording = null
        resetTraceState()
        onSessionCleared()
    }

    private fun resetTraceState() {
        checkpointOrdinal = 0
        lastTraceElapsedNs = -1L
        gnssAnchorElapsedNs = null
        gnssAnchorUtcEpochMs = null
    }

    private fun cleanDestination(destination: PositionFieldExportDestination) {
        if (!destination.deleteOrTruncate()) {
            failure("내보내기 대상에 정확한 위치가 남아 있을 수 있습니다. 문서 제공자에서 직접 삭제하세요.")
        }
    }

    private fun message(text: String) = onMessage(PositionFieldMessage(text))
    private fun failure(text: String) = onMessage(PositionFieldMessage(text, storageFailure = true))
    private fun isCanonicalScope(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private companion object {
        const val CHECKPOINT_STATIONARY_MS = 1_500L
    }
}
