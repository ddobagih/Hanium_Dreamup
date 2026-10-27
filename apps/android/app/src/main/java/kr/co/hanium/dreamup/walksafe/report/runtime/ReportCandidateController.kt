package kr.co.hanium.dreamup.walksafe.report.runtime

import java.util.Locale
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.ReportFrameCaptureFacts
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.report.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.device.DeviceGateState
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.TactileSnapshotFrameResult
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.device.WalkSessionDeviceResourceSnapshot

import kr.co.hanium.dreamup.walksafe.report.EXPLICIT_REPORT_DISCLOSURE_VALIDITY_MS
import kr.co.hanium.dreamup.walksafe.report.EXPLICIT_REPORT_CONFIRMATION_VALIDITY_MS
/** Frozen per-report input and explicit confirmation lifecycle; frame/SDK/UI owners stay peers. */
internal class ReportCandidateController(
    private val integratedConsentSession: IntegratedConsentSession,
    private val permissions: () -> PermissionSessionPolicy,
    private val reportPrivacyConsentSession: ReportPrivacyConsentSession,
    private val attempts: () -> AndroidReportAttemptStore,
    private val queueEnabled: Boolean,
    private val disclosureSummary: String,
    private val queue: Queue,
    private val authority: Authority,
    private val frames: FrameEvidence,
    private val models: Models,
    private val resources: Resources,
    private val location: Location,
    private val ui: Ui,
    private val frameStateLock: Any,
    private val runtimeMetricStateLock: Any,
    private val wallClockMs: () -> Long,
    private val elapsedRealtimeMs: () -> Long,
    private val imageValidator: (ByteArray) -> Boolean = AndroidReportJpegValidator::isDecodable,
) {
    interface Queue {
        fun enqueue(expectedReporterActorId: String, metadataUtf8: ByteArray, imageJpeg: ByteArray,
            priority: ReportQueuePriority, walkSessionId: String, consentReceiptSha256: String, accountGeneration: Long?): QueuedReport?
    }
    interface Authority {
        fun sensitiveStorageBlocked(): Boolean = false
        fun reportPermissionsAllowWork(): Boolean
        fun currentRuntimeEpochOrNull(): WalkRuntimeEpoch?
        fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch): Boolean
        fun currentReporterUserId(): String?
        fun walkSafetyOutputsAllowed(): Boolean
        fun requireReporterUserId(reason: String): Boolean
        fun approvedQueueOriginOrNull(origin: String): String?
    }
    interface FrameEvidence {
        fun isCurrentFrameGeneration(generation: Int, expectedWalkEpoch: WalkRuntimeEpoch?): Boolean
        fun runtimeMetricAllowsWork(expectedGeneration: Long? = null): Boolean
        fun runtimeMetricGeneration(): Long
        fun buildDeviceGateState(bestOutput: TrackedObjectDepth?, staleReason: String?, expectedRuntimeMetricGeneration: Long): DeviceGateState
        fun buildFrozenReportCandidateInput(output: TrackedObjectDepth, trustedLocation: TrustedLocation, modelKey: String,
            sourceModel: String, threshold: Float, capturedAtMs: Long, requestElapsedRealtimeMs: Long, nowMs: Long,
            trigger: String, reporterId: String, gateState: DeviceGateState, reportImage: ByteArray?,
            frozenReportCapture: Pair<DetectionSnapshot, TactileSnapshotFrameResult>?): AndroidReportCandidateInput
    }
    interface Models {
        fun loadedReportModel(): String?
        fun runtimeConfig(): TwoModelRuntimeConfig?
        fun sourceModel(modelKey: String): String?
        fun threshold(modelKey: String, className: String): Float?
    }
    fun interface Resources { fun current(): WalkSessionDeviceResourceSnapshot }
    interface Location {
        fun freshTrustedLocationOrNull(nowElapsedRealtimeMs: Long? = null): TrustedLocation?
        fun ensurePermission()
        fun startServicesIfNeeded()
    }
    interface Ui {
        fun runOnUiThread(action: () -> Unit)
        fun status(message: String)
        fun speakInteraction(message: String)
        fun renderDisclosure(armed: Boolean)
        fun renderIdle(message: String? = null)
        fun deliverDisclosureAfterDraw(onDelivered: () -> Unit): Boolean
        fun speakDisclosure(message: String, onCompleted: () -> Unit, onFailed: () -> Unit, onCancelled: () -> Unit): NavigationSpeechDispatchResult
        fun postDelayed(action: () -> Unit, delayMs: Long)
    }

    enum class ExplicitReportRequestSource(val reportTrigger: String) {
        ON_SCREEN(AndroidReportCandidatePolicy.TRIGGER_ON_SCREEN),
        VOICE(AndroidReportCandidatePolicy.TRIGGER_VOICE),
    }
    private val permissionSessionPolicy get() = permissions()
    private val reportCandidatePolicy = AndroidReportCandidatePolicy()
    private val explicitReportConfirmationPolicy = ExplicitReportConfirmationPolicy()
    private var explicitReportConfirmationTimeoutGeneration = 0L
    private val reportLocationStartScheduled = AtomicBoolean(false)
    @Volatile private var latestReportCandidateStatus = "reportCandidate=blocked"
    @Volatile private var latestExplicitReportOutput: TrackedObjectDepth? = null
    private var latestExplicitReportCaptureValidation: Pair<DetectionSnapshot, TactileSnapshotFrameResult>? = null
    @Volatile private var latestExplicitReportImage: ByteArray? = null
    private var latestReportCaptureFacts: ReportFrameCaptureFacts? = null
    @Volatile private var latestExplicitReportGateState: DeviceGateState? = null
    @Volatile private var latestExplicitReportCapturedAtMs = 0L
    @Volatile private var latestExplicitReportUnmeasuredDamageCapturedAtMs = 0L
    @Volatile private var latestExplicitReportConflictingStateCapturedAtMs = 0L
    private fun runOnUiThread(action: () -> Unit) = ui.runOnUiThread(action)
    fun status(): String = latestReportCandidateStatus
    fun setStatus(status: String) { latestReportCandidateStatus = status }
    fun resetLocationStartSchedule() { reportLocationStartScheduled.set(false) }
    fun clearCaptureValidation() { latestExplicitReportCaptureValidation = null }
    fun clearFrameCapture() {
        latestExplicitReportOutput = null
        latestExplicitReportCaptureValidation = null
        latestExplicitReportImage = null
        latestExplicitReportGateState = null
        latestExplicitReportCapturedAtMs = 0L
        latestReportCaptureFacts = null
    }
    fun clearFrameState(reason: String) = synchronized(frameStateLock) {
        clearFrameCapture()
        latestReportCandidateStatus = "reportCandidate=blocked:$reason"
    }
    fun cancelExplicitReportIfOpen(): Boolean {
        if (!explicitReportConfirmationPolicy.invalidate()) return false
        invalidatePendingExplicitReport()
        return true
    }

    fun publishCurrentFrameReportState(
        frameGeneration: Int,
        expectedRuntimeMetricGeneration: Long,
        automaticReportOutput: TrackedObjectDepth?,
        reportGateState: DeviceGateState,
        explicitReportOutput: TrackedObjectDepth?,
        explicitReportGateState: DeviceGateState,
        reportImage: ByteArray?,
        nowMs: Long,
        capturedAtMs: Long,
        expectedWalkEpoch: WalkRuntimeEpoch? = null,
        explicitReportDamageWithoutDepth: Boolean = false,
        explicitReportConflictingState: Boolean = false,
        frozenReportCapture: Pair<DetectionSnapshot, TactileSnapshotFrameResult>? = null,
        reportCaptureFacts: ReportFrameCaptureFacts? = null,
    ): Boolean = synchronized(frameStateLock) frameLock@ {
        if (!frames.isCurrentFrameGeneration(frameGeneration, expectedWalkEpoch)) {
            return@frameLock false
        }
        synchronized(runtimeMetricStateLock) runtimeLock@ {
            if (!frames.runtimeMetricAllowsWork(expectedRuntimeMetricGeneration)) {
                return@runtimeLock false
            }
            latestReportCaptureFacts = reportCaptureFacts
            latestReportCandidateStatus = prepareReportCandidate(
                reportOutput = automaticReportOutput,
                gateState = reportGateState,
                reportImage = reportImage,
                nowMs = nowMs,
                capturedAtMs = capturedAtMs,
                frozenReportCapture = frozenReportCapture,
            )
            latestExplicitReportOutput = explicitReportOutput
            latestExplicitReportCaptureValidation = frozenReportCapture
            latestExplicitReportImage = reportImage
            latestExplicitReportGateState = explicitReportGateState
            latestExplicitReportCapturedAtMs = capturedAtMs
            // Every reset clears the capture time, which also retires this marker.
            latestExplicitReportUnmeasuredDamageCapturedAtMs =
                if (explicitReportDamageWithoutDepth) capturedAtMs else 0L
            latestExplicitReportConflictingStateCapturedAtMs =
                if (explicitReportConflictingState) capturedAtMs else 0L
            true
        }
    }

    /**
     * Report creation is fail-closed: session consent, damage class, metric depth, device gate,
     * model provenance, trusted GPS, local reporter id and a captured JPEG must all remain available.
     */
    private fun prepareReportCandidate(
        reportOutput: TrackedObjectDepth?,
        gateState: DeviceGateState,
        reportImage: ByteArray?,
        nowMs: Long,
        capturedAtMs: Long,
        trigger: String = "auto",
        frozenReportCapture: Pair<DetectionSnapshot, TactileSnapshotFrameResult>? = null,
        explicitRequest: Boolean = false,
        explicitConfirmationContext: ExplicitReportConfirmationContext? = null,
        explicitConfirmationRequestedAtElapsedRealtimeMs: Long? = null,
    ): String {
        if (authority.sensitiveStorageBlocked()) return "reportCandidate=blocked:sensitive_storage"
        if (!authority.reportPermissionsAllowWork()) {
            return "reportCandidate=blocked:permission_unavailable"
        }
        val expectedWalkEpoch = authority.currentRuntimeEpochOrNull()
        if (expectedWalkEpoch == null) {
            return "reportCandidate=blocked:walk_session_inactive"
        }
        if (!reportPrivacyConsentSession.isGranted()) {
            return "reportCandidate=blocked:consent_confirmation_required"
        }
        if (
            !explicitRequest &&
            (
                !permissionSessionPolicy.snapshot().mayCreateAutomaticReport ||
                    !integratedConsentSession.isAllowed(
                        IntegratedConsentItem.AUTOMATIC_REPORTING,
                    )
            )
        ) {
            return "reportCandidate=blocked:automatic_report_consent_required"
        }
        if (
            !explicitRequest &&
            run {
                GatewayCapacityProcessState.fenceSessionGeneration(
                    GatewaySessionProcessCoordinator.snapshot().generation,
                )
                !GatewayCapacityProcessState.admission(
                    java.time.Instant.ofEpochMilli(nowMs),
                ).automaticReportCandidateAllowed
            }
        ) {
            return "reportCandidate=blocked:gateway_capacity"
        }
        val reporterId = authority.currentReporterUserId() ?: return "reportCandidate=blocked:login_required"
        val output = reportOutput ?: return "reportCandidate=blocked:no_depth_object"
        if (!AndroidReportCandidatePolicy.isReportableDamageClass(output.className)) {
            return "reportCandidate=blocked:not_reportable_class"
        }
        val loadedReportModel = models.loadedReportModel()
            ?: return "reportCandidate=blocked:model_unavailable"
        val modelKey = models.runtimeConfig()?.modelKeyForReportClass(output.className)
            ?.takeIf { loadedReportModel == models.runtimeConfig()?.primaryModelKey }
            ?: loadedReportModel
        if (modelKey !in AndroidReportCandidatePolicy.ALLOWED_MODEL_KEYS) {
            return "reportCandidate=blocked:model_not_allowed"
        }
        val sourceModel = models.sourceModel(modelKey) ?: return "reportCandidate=blocked:source_model_missing"
        val threshold = models.threshold(modelKey, output.className) ?: return "reportCandidate=blocked:threshold_missing"
        if (!gateState.reportCandidatesAllowed) {
            return "reportCandidate=blocked:device_gate"
        }
        val deviceResources = resources.current()
        if (deviceResources.readinessStatus != WalkSessionReadinessStatus.READY) {
            return "reportCandidate=blocked:device_resources_${deviceResources.readinessStatus.name.lowercase(Locale.US)}"
        }
        val requestElapsedRealtimeMs = elapsedRealtimeMs()
        val capture = latestReportCaptureFacts
        val gateway = GatewaySessionProcessCoordinator.snapshot()
        if (capture == null || capture.walkEpoch != expectedWalkEpoch ||
            capture.reporterActorId != reporterId || capture.accountGeneration != gateway.session?.backendAccountGeneration ||
            capture.capturedAtElapsedRealtimeMs != frozenReportCapture?.first?.capturedAtElapsedRealtimeMs ||
            capture.capturedAtElapsedRealtimeMs < 0L || requestElapsedRealtimeMs < capture.capturedAtElapsedRealtimeMs
        ) return "reportCandidate=blocked:capture_context_changed"
        val trustedLocation = capture.trustedLocation
        if (trustedLocation == null) {
            if (explicitRequest) {
                location.ensurePermission()
                location.startServicesIfNeeded()
                ui.speakInteraction("위치 정보가 필요합니다.")
            } else if (reportLocationStartScheduled.compareAndSet(false, true)) {
                runOnUiThread {
                    location.ensurePermission()
                    location.startServicesIfNeeded()
                }
            }
            return "reportCandidate=blocked:gps_missing"
        }
        if (reportImage == null || !imageValidator(reportImage)) return "reportCandidate=blocked:image_undecodable"
        val evaluation = reportCandidatePolicy.evaluate(
            frames.buildFrozenReportCandidateInput(
                output = output,
                trustedLocation = trustedLocation,
                modelKey = modelKey,
                sourceModel = sourceModel,
                threshold = threshold,
                capturedAtMs = capturedAtMs,
                requestElapsedRealtimeMs = requestElapsedRealtimeMs,
                nowMs = nowMs,
                trigger = trigger,
                reporterId = reporterId,
                gateState = gateState,
                reportImage = reportImage,
                frozenReportCapture = frozenReportCapture,
            ).copy(capturedAtElapsedRealtimeMs = capture.capturedAtElapsedRealtimeMs),
        )
        return when (evaluation) {
            // Fixed reason token only; no image, coordinate or metadata value enters the status.
            is AndroidReportCandidateResult.Rejected ->
                "reportCandidate=blocked:candidate_${evaluation.reason.diagnosticCode}"
            is AndroidReportCandidateResult.Ready -> processReportCandidate(
                candidate = evaluation.candidate,
                reportImage = reportImage,
                explicitRequest = explicitRequest,
                expectedWalkEpoch = expectedWalkEpoch,
                expectedReporterActorId = reporterId,
                capturedAtMs = capturedAtMs,
                explicitConfirmationContext = explicitConfirmationContext,
                explicitConfirmationRequestedAtElapsedRealtimeMs =
                    explicitConfirmationRequestedAtElapsedRealtimeMs,
            )
        }
    }

    private fun processReportCandidate(
        candidate: AndroidReportCandidate,
        reportImage: ByteArray?,
        explicitRequest: Boolean = false,
        expectedWalkEpoch: WalkRuntimeEpoch,
        expectedReporterActorId: String,
        capturedAtMs: Long,
        explicitConfirmationContext: ExplicitReportConfirmationContext?,
        explicitConfirmationRequestedAtElapsedRealtimeMs: Long?,
    ): String {
        if (authority.sensitiveStorageBlocked()) return "reportCandidate=blocked:sensitive_storage"
        if (!authority.reportPermissionsAllowWork()) {
            return "reportCandidate=blocked:permission_unavailable"
        }
        if (!authority.isRuntimeEpochCurrent(expectedWalkEpoch)) {
            return "reportCandidate=blocked:walk_session_changed"
        }
        if (authority.currentReporterUserId() != expectedReporterActorId) {
            return "reportCandidate=blocked:reporter_changed"
        }
        if (!authority.walkSafetyOutputsAllowed()) {
            return "reportCandidate=blocked:environment_changed"
        }
        if (!reportPrivacyConsentSession.isGranted()) {
            return "reportCandidate=blocked:consent_confirmation_required"
        }
        val consentConfirmation =
            integratedConsentSession.currentConfirmationOrNull()
                ?: return "reportCandidate=blocked:consent_confirmation_required"
        if (!explicitRequest && !consentConfirmation.selections.automaticReporting) {
            return "reportCandidate=blocked:automatic_report_consent_required"
        }
        // The server receipt keeps its old selection until a withdrawal is confirmed.
        // Automatic creation follows the effective consent, including local withdrawal.
        if (
            !explicitRequest &&
            !integratedConsentSession.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING)
        ) {
            return "reportCandidate=blocked:automatic_report_consent_required"
        }
        val imageJpeg = reportImage ?: return "reportCandidate=blocked:no_report_image"
        val automaticAttemptLease = if (explicitRequest) {
            null
        } else {
            val cooldownScope = automaticReportCooldownScopeOrNull(candidate.metadata)
                ?: return "reportCandidate=blocked:cooldown_scope_invalid"
            when (
                val admission = attempts().acquire(
                    scope = cooldownScope,
                    nowMs = wallClockMs(),
                    bypassAutomaticCooldown = false,
                )
            ) {
                is AndroidReportAttemptResult.Allowed -> admission.lease
                is AndroidReportAttemptResult.Blocked ->
                    return "reportCandidate=blocked:" +
                        admission.reason.name.lowercase(Locale.US)
            }
        }
        val metadataUtf8 = candidate.metadata.toString().toByteArray(Charsets.UTF_8)
        if (explicitConfirmationContext != null) {
            val requestedAt = explicitConfirmationRequestedAtElapsedRealtimeMs
            if (requestedAt == null) {
                metadataUtf8.fill(0)
                return "reportCandidate=blocked:confirmation_clock_missing"
            }
            if (
                !explicitRequest ||
                explicitConfirmationContext.walkSessionId != expectedWalkEpoch.walkSessionId ||
                explicitConfirmationContext.recoveryGeneration !=
                expectedWalkEpoch.recoveryGeneration ||
                explicitConfirmationContext.reporterId != expectedReporterActorId ||
                explicitConfirmationContext.consentReceiptSha256 !=
                consentConfirmation.backendConsentReceiptSha256 ||
                explicitConfirmationContext.gatewaySessionGeneration !=
                GatewaySessionProcessCoordinator.snapshot().generation
            ) {
                metadataUtf8.fill(0)
                return "reportCandidate=blocked:confirmation_context_changed"
            }
            val frozen = try {
                ExplicitReportFrozenPayload.freeze(
                    context = explicitConfirmationContext,
                    capturedAtEpochMs = capturedAtMs,
                    metadataUtf8 = metadataUtf8,
                    imageJpeg = imageJpeg,
                )
            } finally {
                metadataUtf8.fill(0)
            } ?: return "reportCandidate=blocked:explicit_payload_freeze_failed"
            explicitReportConfirmationPolicy.stage(frozen, requestedAt)
            return "reportCandidate=awaiting:explicit_transfer_confirmation"
        }
        val priority = if (explicitRequest) {
            ReportQueuePriority.EXPLICIT
        } else {
            ReportQueuePriority.AUTOMATIC
        }
        val queued = try {
            queue.enqueue(
                expectedReporterActorId = expectedReporterActorId,
                metadataUtf8 = metadataUtf8,
                imageJpeg = imageJpeg,
                priority = priority,
                walkSessionId = expectedWalkEpoch.walkSessionId,
                consentReceiptSha256 =
                    consentConfirmation.backendConsentReceiptSha256,
                accountGeneration = GatewaySessionProcessCoordinator.snapshot()
                    .session?.backendAccountGeneration,
            )
        } finally {
            metadataUtf8.fill(0)
            automaticAttemptLease?.let(attempts()::release)
        }
        if (queued == null) {
            return "reportCandidate=queue_disabled_or_rejected"
        }
        val queuedStatus = queued.use { "reportCandidate=queued id=${it.payload.reportId.take(12)}" }
        if (explicitRequest) ui.speakInteraction(kr.co.hanium.dreamup.walksafe.report.ExplicitReportSubmissionNotice.SAVED_NOT_SUBMITTED_KO)
        return queuedStatus
    }

    fun requestExplicitReport(source: ExplicitReportRequestSource) {
        if (!ensureExplicitReportAuthorityPreconditions()) {
            invalidatePendingExplicitReport()
            return
        }
        val context = currentExplicitReportConfirmationContextOrNull()
        if (context == null) {
            invalidatePendingExplicitReport()
            latestReportCandidateStatus = "reportCandidate=blocked:gateway_authority_unavailable"
            ui.status(latestReportCandidateStatus)
            ui.speakInteraction("현재 계정의 테스트 서버 로그인 상태를 확인한 뒤 다시 신고해 주세요.")
            return
        }
        val nowElapsedRealtimeMs = elapsedRealtimeMs()
        val confirmed = explicitReportConfirmationPolicy.consumeIfConfirmed(
            context = context,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        )
        if (confirmed != null) {
            explicitReportConfirmationTimeoutGeneration += 1L
            submitFrozenExplicitReportAfterConfirmation(confirmed)
            return
        }
        if (explicitReportConfirmationPolicy.isAwaitingDisclosure(context)) {
            latestReportCandidateStatus =
                "reportCandidate=awaiting:explicit_transfer_disclosure"
            ui.status(latestReportCandidateStatus)
            return
        }
        val captureRejection = explicitReportCaptureRejectionOrNull()
        if (captureRejection != null) {
            invalidatePendingExplicitReport()
            kr.co.hanium.dreamup.walksafe.report.ExplicitReportRejectionNotice
                .messageFor(captureRejection)?.let { message ->
                    ui.speakInteraction(message)
                    ui.renderIdle(message)
                }
            return
        }
        val status = prepareExplicitReportCandidateIfCurrent(
            source = source,
            nowMs = wallClockMs(),
            confirmationContext = context,
            requestedAtElapsedRealtimeMs = nowElapsedRealtimeMs,
        )
        latestReportCandidateStatus = status
        ui.status(status)
        if (status == "reportCandidate=awaiting:explicit_transfer_confirmation") {
            beginExplicitReportConfirmationDisclosure(context, source)
        } else {
            val rejectionMessage = if (status == "reportCandidate=blocked:conflicting_tactile_state") {
                "점자블록의 손상 상태가 불확실해 신고를 저장하지 않았습니다. 손상 부위를 다시 확인한 뒤 신고해 주세요."
            } else {
                kr.co.hanium.dreamup.walksafe.report.ExplicitReportRejectionNotice.messageFor(status)
            }
            rejectionMessage?.let { message ->
                // S12-17: one sentence for speech, the last-result line and TalkBack (live region).
                ui.speakInteraction(message)
                ui.renderIdle(message)
            }
        }
    }

    private fun ensureExplicitReportAuthorityPreconditions(): Boolean {
        if (authority.sensitiveStorageBlocked()) {
            latestReportCandidateStatus = "reportCandidate=blocked:sensitive_storage"
            ui.status(latestReportCandidateStatus)
            ui.speakInteraction("기기의 안전한 저장소를 확인할 수 없어 신고를 진행하지 않았습니다. 앱을 다시 실행해 주세요.")
            return false
        }
        if (!queueEnabled) {
            val message = "현재 앱 빌드에서는 신고 저장 기능을 사용할 수 없습니다."
            latestReportCandidateStatus = "reportCandidate=blocked:queue_disabled"
            ui.status(latestReportCandidateStatus)
            ui.speakInteraction(message)
            return false
        }
        if (!authority.reportPermissionsAllowWork()) {
            latestReportCandidateStatus = "reportCandidate=blocked:permission_unavailable"
            ui.status(latestReportCandidateStatus)
            ui.speakInteraction("카메라와 정확한 위치 권한을 확인한 뒤 다시 신고해 주세요.")
            return false
        }
        if (!authority.requireReporterUserId("login_required_explicit_report")) return false
        if (
            !reportPrivacyConsentSession.isGranted() ||
            integratedConsentSession.currentConfirmationOrNull() == null
        ) {
            latestReportCandidateStatus =
                "reportCandidate=blocked:consent_confirmation_required trigger=voice"
            ui.status(latestReportCandidateStatus)
            ui.speakInteraction("현재 계정의 신고 처리 확인을 서버에서 확인해야 직접 신고할 수 있습니다.")
            return false
        }
        if (currentExplicitReportConfirmationContextOrNull() == null) {
            latestReportCandidateStatus = "reportCandidate=blocked:gateway_authority_unavailable"
            ui.status(latestReportCandidateStatus)
            ui.speakInteraction("현재 계정의 테스트 서버 로그인 상태를 확인한 뒤 다시 신고해 주세요.")
            return false
        }
        return true
    }

    private fun explicitReportCaptureRejectionOrNull(): String? {
        if (!frames.runtimeMetricAllowsWork()) {
            val status = kr.co.hanium.dreamup.walksafe.report.ExplicitReportRejectionNotice
                .STATUS_METRIC_UNAVAILABLE
            clearFrameState("runtime_metric_unavailable")
            ui.status(status)
            return status
        }
        if (location.freshTrustedLocationOrNull() == null) {
            location.ensurePermission()
            location.startServicesIfNeeded()
            val status = "reportCandidate=blocked:gps_missing trigger=voice"
            latestReportCandidateStatus = status
            ui.status(status)
            ui.speakInteraction("위치 정보가 필요합니다.")
            return status
        }
        return null
    }

    private fun currentExplicitReportConfirmationContextOrNull():
        ExplicitReportConfirmationContext? {
        if (authority.sensitiveStorageBlocked()) return null
        val epoch = authority.currentRuntimeEpochOrNull() ?: return null
        val reporter = authority.currentReporterUserId() ?: return null
        val confirmation = integratedConsentSession.currentConfirmationOrNull() ?: return null
        if (!reportPrivacyConsentSession.isGranted()) return null
        val gateway = GatewaySessionProcessCoordinator.snapshot()
        val session = gateway.session ?: return null
        if (
            gateway.storageBlocked ||
            gateway.deletionRecoveryOnly ||
            session.sessionScope != GatewaySessionScope.GENERAL ||
            !session.isUsableFor(reporter) ||
            authority.approvedQueueOriginOrNull(session.gatewayBaseUrl) !=
            session.gatewayBaseUrl
        ) return null
        return ExplicitReportConfirmationContext(
            walkSessionId = epoch.walkSessionId,
            recoveryGeneration = epoch.recoveryGeneration,
            reporterId = reporter,
            consentReceiptSha256 = confirmation.backendConsentReceiptSha256,
            gatewaySessionGeneration = gateway.generation,
        )
    }

    private fun submitFrozenExplicitReportAfterConfirmation(
        confirmed: ConfirmedExplicitReportPayload,
    ) {
        val status = try {
            if (
                !ensureExplicitReportAuthorityPreconditions() ||
                !authority.walkSafetyOutputsAllowed() ||
                currentExplicitReportConfirmationContextOrNull() != confirmed.context
            ) {
                "reportCandidate=blocked:confirmation_context_changed"
            } else {
                confirmed.useExactBytes { metadataUtf8, imageJpeg ->
                    val queueGateway = GatewaySessionProcessCoordinator.snapshot()
                    if (queueGateway.generation != confirmed.context.gatewaySessionGeneration ||
                        queueGateway.session?.actorId != confirmed.context.reporterId
                    ) return@useExactBytes "reportCandidate=blocked:confirmation_context_changed"
                    queue.enqueue(
                        expectedReporterActorId = confirmed.context.reporterId,
                        metadataUtf8 = metadataUtf8,
                        imageJpeg = imageJpeg,
                        priority = ReportQueuePriority.EXPLICIT,
                        walkSessionId = confirmed.context.walkSessionId,
                        consentReceiptSha256 = confirmed.context.consentReceiptSha256,
                        accountGeneration = queueGateway.session?.backendAccountGeneration,
                    )?.use { queued ->
                        "reportCandidate=queued id=${queued.payload.reportId.take(12)}"
                    } ?: "reportCandidate=queue_disabled_or_rejected"
                }
            }
        } finally {
            confirmed.close()
        }
        // S05-34: saved on the phone is not "accepted"; the server result is announced later.
        val savedNotice = if (status.startsWith("reportCandidate=queued")) {
            kr.co.hanium.dreamup.walksafe.report.ExplicitReportSubmissionNotice.SAVED_NOT_SUBMITTED_KO
        } else {
            null
        }
        ui.renderIdle(savedNotice)
        latestReportCandidateStatus = status
        ui.status(status)
        if (savedNotice != null) {
            ui.speakInteraction(savedNotice)
        } else if (status == "reportCandidate=blocked:confirmation_context_changed") {
            ui.speakInteraction("신고 확인 중 상태가 바뀌어 저장하지 않았습니다. 다시 신고해 주세요.")
        }
    }

    private fun prepareExplicitReportCandidateIfCurrent(
        source: ExplicitReportRequestSource,
        nowMs: Long,
        confirmationContext: ExplicitReportConfirmationContext,
        requestedAtElapsedRealtimeMs: Long,
    ): String =
        synchronized(frameStateLock) frameLock@ {
            synchronized(runtimeMetricStateLock) runtimeLock@ {
                if (!frames.runtimeMetricAllowsWork()) {
                    return@runtimeLock "reportCandidate=blocked:runtime_metric_unavailable"
                }
                val output = latestExplicitReportOutput
                val unmeasuredDamage = latestExplicitReportCapturedAtMs > 0L &&
                    latestExplicitReportUnmeasuredDamageCapturedAtMs == latestExplicitReportCapturedAtMs
                val conflictingState = latestExplicitReportCapturedAtMs > 0L &&
                    latestExplicitReportConflictingStateCapturedAtMs == latestExplicitReportCapturedAtMs
                prepareReportCandidate(
                    reportOutput = output,
                    gateState = latestExplicitReportGateState ?: frames.buildDeviceGateState(
                        bestOutput = output,
                        staleReason = null,
                        expectedRuntimeMetricGeneration = frames.runtimeMetricGeneration(),
                    ),
                    reportImage = latestExplicitReportImage,
                    nowMs = nowMs,
                    capturedAtMs = latestExplicitReportCapturedAtMs.takeIf { it > 0L } ?: nowMs,
                    trigger = source.reportTrigger,
                    frozenReportCapture = latestExplicitReportCaptureValidation,
                    explicitRequest = true,
                    explicitConfirmationContext = confirmationContext,
                    explicitConfirmationRequestedAtElapsedRealtimeMs =
                        requestedAtElapsedRealtimeMs,
                ).let { status ->
                    if (status == "reportCandidate=blocked:no_depth_object" && conflictingState) {
                        "reportCandidate=blocked:conflicting_tactile_state"
                    } else {
                        kr.co.hanium.dreamup.walksafe.report.ExplicitReportRejectionNotice.explicitStatus(status, unmeasuredDamage)
                    }
                }
            }
        }

    private fun beginExplicitReportConfirmationDisclosure(
        context: ExplicitReportConfirmationContext,
        source: ExplicitReportRequestSource,
    ) {
        ui.renderDisclosure(armed = false)
        val generation = ++explicitReportConfirmationTimeoutGeneration
        scheduleExplicitReportDisclosureTimeout(generation)
        val delivered = {
            runOnUiThread {
                if (
                    generation != explicitReportConfirmationTimeoutGeneration ||
                    currentExplicitReportConfirmationContextOrNull() != context ||
                    !explicitReportConfirmationPolicy.arm(
                        context,
                        elapsedRealtimeMs(),
                    )
                ) {
                    return@runOnUiThread
                }
                ui.renderDisclosure(armed = true)
                latestReportCandidateStatus =
                    "reportCandidate=awaiting:explicit_transfer_confirmation"
                ui.status(latestReportCandidateStatus)
                scheduleExplicitReportConfirmationTimeout()
            }
        }
        val failed = {
            runOnUiThread {
                if (
                    generation == explicitReportConfirmationTimeoutGeneration &&
                    explicitReportConfirmationPolicy.invalidateIfContext(context)
                ) {
                    explicitReportConfirmationTimeoutGeneration += 1L
                    latestReportCandidateStatus =
                        "reportCandidate=blocked:confirmation_disclosure_failed"
                    ui.status(latestReportCandidateStatus)
                    ui.renderIdle(
                        "신고 전송 안내를 전달하지 못해 저장하지 않았습니다. 다시 신고해 주세요.",
                    )
                }
            }
        }
        val accepted = if (source == ExplicitReportRequestSource.ON_SCREEN) {
            ui.deliverDisclosureAfterDraw(delivered)
        } else {
            ui.speakDisclosure(
                message = disclosureSummary,
                onCompleted = delivered,
                onFailed = failed,
                onCancelled = {
                    runOnUiThread {
                        if (generation == explicitReportConfirmationTimeoutGeneration &&
                            explicitReportConfirmationPolicy.invalidateIfContext(context)) {
                            explicitReportConfirmationTimeoutGeneration += 1L
                            ui.renderIdle("신고 안내가 취소되었습니다. 다시 신고해 주세요.")
                        }
                    }
                },
            ) == NavigationSpeechDispatchResult.ACCEPTED
        }
        if (!accepted) failed()
    }

    private fun scheduleExplicitReportDisclosureTimeout(generation: Long) {
        ui.postDelayed(
            {
                if (
                    generation == explicitReportConfirmationTimeoutGeneration &&
                    explicitReportConfirmationPolicy.invalidateExpired(
                        elapsedRealtimeMs(),
                    )
                ) {
                    explicitReportConfirmationTimeoutGeneration += 1L
                    latestReportCandidateStatus =
                        "reportCandidate=blocked:confirmation_disclosure_expired"
                    ui.status(latestReportCandidateStatus)
                    ui.renderIdle(
                        "신고 전송 안내 시간이 지나 저장하지 않았습니다. 다시 신고해 주세요.",
                    )
                }
            },
            EXPLICIT_REPORT_DISCLOSURE_VALIDITY_MS + 1L,
        )
    }

    private fun scheduleExplicitReportConfirmationTimeout() {
        val generation = ++explicitReportConfirmationTimeoutGeneration
        ui.postDelayed(
            {
                if (
                    generation == explicitReportConfirmationTimeoutGeneration &&
                    explicitReportConfirmationPolicy.invalidateExpired(
                        elapsedRealtimeMs(),
                    )
                ) {
                    explicitReportConfirmationTimeoutGeneration += 1L
                    latestReportCandidateStatus =
                        "reportCandidate=blocked:confirmation_expired"
                    ui.status(latestReportCandidateStatus)
                    ui.renderIdle(
                        "30초 확인 시간이 지나 저장하지 않았습니다. 다시 신고해 주세요.",
                    )
                }
            },
            EXPLICIT_REPORT_CONFIRMATION_VALIDITY_MS + 1L,
        )
    }

    fun invalidatePendingExplicitReport() {
        explicitReportConfirmationTimeoutGeneration += 1L
        explicitReportConfirmationPolicy.invalidate()
        ui.renderIdle()
    }
}
