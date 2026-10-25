package kr.co.hanium.dreamup.walksafe.runtime.metric

import kr.co.hanium.dreamup.walksafe.depth.allowsRuntimeDepthGapProcessing
import kr.co.hanium.dreamup.walksafe.depth.assessRuntimeDepthFrame
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckBinding
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckPolicy
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckState
import kr.co.hanium.dreamup.walksafe.device.PostLoginMetricDepthState
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricDepthSupport
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricFrameEvidence
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricPreflightPolicy
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricPreflightReason
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricPreflightResult
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricPreflightSession
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricPreflightStatus
import kr.co.hanium.dreamup.walksafe.device.WALKSAFE_LIMITED_DISTANCE_NOTICE_KO
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

/** Owns metric qualification/history and its exact preflight authority; never native resources. */
internal class RuntimeMetricSessionController<L, Q, S : Any>(
    private val runtimeMetricStateLock: Any,
    private val scope: RuntimeMetricScope,
    private val native: RuntimeMetricNativePort<Q, S>,
    private val device: RuntimeMetricDeviceChecks<L>,
    private val effects: RuntimeMetricEffects,
    private val nonePurpose: Q,
    private val preflightPurpose: Q,
    private val runtimePurpose: Q,
    private val staleTimeoutMs: Long,
    private val elapsedRealtimeMs: () -> Long,
) {
    private sealed class PreflightOwner<L>(
        val kind: RuntimeMetricPreflightOwner,
        val binding: PostLoginDeviceCheckBinding,
    ) {
        abstract fun isCurrent(device: RuntimeMetricDeviceChecks<L>): Boolean
    }

    private class DeviceCheckOwner<L>(binding: PostLoginDeviceCheckBinding, private val firstRunLease: L?) :
        PreflightOwner<L>(RuntimeMetricPreflightOwner.DEVICE_CHECK, binding) {
        // A missing lease keeps the original permission UI flow but never grants native authority.
        override fun isCurrent(device: RuntimeMetricDeviceChecks<L>): Boolean =
            firstRunLease?.let(device::firstRunLeaseCurrent) == true && device.firstRunAllowsPreflight
    }

    private class StoredRefreshOwner<L>(binding: PostLoginDeviceCheckBinding) :
        PreflightOwner<L>(RuntimeMetricPreflightOwner.STORED_DEPTH_REFRESH, binding) {
        override fun isCurrent(device: RuntimeMetricDeviceChecks<L>): Boolean = device.storedRefreshCurrent(binding)
    }

    /** One attempt's admission, permission continuation and frame terminal; owns no native resource. */
    private class MetricPreflightAttempt<L>(
        val generation: Long,
        val lifecycleGeneration: Int,
        private val owner: PreflightOwner<L>? = null,
        private var permissionPending: Boolean = false,
    ) {
        private var bindingRevoked = false
        var arGeneration = 0L
            private set
        @Volatile var evaluator: RuntimeMetricPreflightSession? = null
            private set
        @Volatile private var terminalDispatchedGeneration = 0L

        val kind get() = owner?.kind ?: RuntimeMetricPreflightOwner.NONE
        val binding get() = owner?.binding?.takeUnless { bindingRevoked }
        fun ownerCurrent(device: RuntimeMetricDeviceChecks<L>) = owner?.isCurrent(device) == true
        fun revokeBinding() { bindingRevoked = true }
        fun consumePermission(): Boolean {
            if (!permissionPending) return false
            permissionPending = false
            return true
        }

        fun publish(evaluator: RuntimeMetricPreflightSession, arGeneration: Long) {
            this.arGeneration = arGeneration
            this.evaluator = evaluator
            terminalDispatchedGeneration = 0L
        }

        fun claimTerminal(resultGeneration: Long): Boolean {
            if (terminalDispatchedGeneration == resultGeneration) return false
            terminalDispatchedGeneration = resultGeneration
            return true
        }

        fun retired(retainEvaluator: Boolean = false): MetricPreflightAttempt<L> =
            MetricPreflightAttempt<L>(generation + 1L, lifecycleGeneration).also {
                // The support-only path never owns native cleanup. Preserve its diagnostic fields
                // until the original native invalidation/publication boundary clears them.
                if (retainEvaluator) {
                    it.arGeneration = arGeneration
                    it.evaluator = evaluator
                    it.terminalDispatchedGeneration = terminalDispatchedGeneration
                }
            }
    }

    private var metricDistanceCapabilityOverride: Boolean? = null
    @Volatile
    private var metricPreflightAttempt = MetricPreflightAttempt<L>(0L, 0)
    @Volatile
    private var runtimeMetricOutputAllowed = false
    @Volatile
    private var runtimeMetricActivationSession: RuntimeMetricPreflightSession? = null
    private var runtimeMetricFrameDiagnosticAtMs = 0L
    @Volatile
    private var runtimeMetricLastValidFrameAtMs = 0L
    @Volatile
    private var runtimeMetricLastFrameTimestampNanos = 0L
    @Volatile
    private var runtimeVisualDepthFrameTracking = false
    private var runtimeMetricInitialNavigationStartPending = false

    val capabilityOverride get() = metricDistanceCapabilityOverride
    val preflightGeneration get() = metricPreflightAttempt.generation
    val preflightLifecycleGeneration get() = metricPreflightAttempt.lifecycleGeneration
    val preflightBinding get() = metricPreflightAttempt.binding
    val preflightOwner get() = metricPreflightAttempt.kind
    val preflightEvaluator get() = metricPreflightAttempt.evaluator
    val preflightArGeneration get() = metricPreflightAttempt.arGeneration
    val activationEvaluator get() = runtimeMetricActivationSession
    val outputAllowed get() = runtimeMetricOutputAllowed
    val lastValidFrameAtMs get() = runtimeMetricLastValidFrameAtMs
    val lastFrameTimestampNanos get() = runtimeMetricLastFrameTimestampNanos
    val visualFrameTracking get() = runtimeVisualDepthFrameTracking
    val initialNavigationStartPending get() = runtimeMetricInitialNavigationStartPending
    val lastFrameDiagnosticAtMs get() = runtimeMetricFrameDiagnosticAtMs

    fun restoreCapability(available: Boolean?) { metricDistanceCapabilityOverride = available }
    fun clearPreflightBinding() { metricPreflightAttempt.revokeBinding() }
    fun revokeOutput() { runtimeMetricOutputAllowed = false }
    fun noteFrameDiagnostic(observedAtMs: Long) { runtimeMetricFrameDiagnosticAtMs = observedAtMs }

    fun beginStoredDepthRefresh(binding: PostLoginDeviceCheckBinding) {
        val attempt = MetricPreflightAttempt(metricPreflightAttempt.generation + 1L, scope.feedbackGeneration,
            StoredRefreshOwner<L>(binding))
        metricPreflightAttempt = attempt
        runtimeMetricOutputAllowed = false
        runtimeMetricInitialNavigationStartPending = false
        effects.startNativePreflight(attempt.generation, attempt.lifecycleGeneration)
    }

    /** Caller holds the shared native publication lock; evaluator was constructed at the original clock. */
    fun publishPreflightEvaluator(evaluator: RuntimeMetricPreflightSession, arGeneration: Long) {
        metricPreflightAttempt.publish(evaluator, arGeneration)
        runtimeMetricOutputAllowed = false
        runtimeMetricInitialNavigationStartPending = false
        runtimeMetricLastFrameTimestampNanos = 0L
    }

    /** Called at the original point between retained native ownership and native detach. */
    fun invalidateEvidence() {
        metricPreflightAttempt = metricPreflightAttempt.retired()
        runtimeMetricActivationSession = null
        runtimeMetricOutputAllowed = false
        runtimeMetricInitialNavigationStartPending = false
        runtimeMetricLastValidFrameAtMs = 0L
        runtimeMetricLastFrameTimestampNanos = 0L
    }

    fun retireRuntimeEvidence() {
        runtimeMetricOutputAllowed = false
        runtimeMetricInitialNavigationStartPending = false
        runtimeMetricActivationSession = null
        runtimeMetricLastValidFrameAtMs = 0L
        runtimeMetricLastFrameTimestampNanos = 0L
    }

    fun observePreflightFrame(expectedArGeneration: Long, expectedEpoch: WalkRuntimeEpoch,
        observeFrameEvidence: (Long) -> RuntimeMetricFrameEvidence) {
        val attempt = metricPreflightAttempt
        val evaluator = attempt.evaluator ?: return
        if (native.purpose != preflightPurpose || native.generation != expectedArGeneration ||
            evaluator.generation != attempt.generation ||
            scope.lifecycle.snapshot().epoch != expectedEpoch) return
        val result = evaluator.observe(observeFrameEvidence(evaluator.generation))
        if (result.status != RuntimeMetricPreflightStatus.IN_PROGRESS &&
            attempt.claimTerminal(result.generation)) {
            val lifecycleGeneration = attempt.lifecycleGeneration
            effects.postToUi {
                finishRuntimeMetricPreflight(result, expectedArGeneration, lifecycleGeneration)
            }
        }
    }

    fun expirePreflightIfCurrent(generation: Long, lifecycleGeneration: Int) {
        val attempt = metricPreflightAttempt
        val active = attempt.evaluator
        if (active != null && active.generation == generation && native.purpose == preflightPurpose) {
            val result = active.expire(elapsedRealtimeMs())
            if (result.status != RuntimeMetricPreflightStatus.IN_PROGRESS) {
                finishRuntimeMetricPreflight(result, attempt.arGeneration, lifecycleGeneration)
            }
        }
    }

    fun canBeginRuntimeMetricPreflight(): Boolean {
        if (native.cleanupPending) return false
        if (!device.probeAvailable || !scope.foreground) return false
        if (!device.firstRunAllowsPreflight) return false
        if (
            device.cameraResourceConflict
        ) return false
        val walkSession = scope.lifecycle.snapshot()
        if (
            !walkSession.isForeground ||
            walkSession.state !in setOf(WalkSessionState.READY, WalkSessionState.PAUSED)
        ) return false
        if (native.purpose != nonePurpose || native.sessionPresent) return false
        if (device.fieldSessionActive) return false
        val input = device.startupInput()
        val coreAvailable = listOf(
            input.androidVersionSupported,
            input.cameraAvailable,
        ).all { it == true }
        val effectiveMetricDistance = metricDistanceCapabilityOverride ?: input.metricDistanceAvailable
        return coreAvailable && effectiveMetricDistance == null
    }

    fun beginRuntimeMetricPreflight() {
        if (!canBeginRuntimeMetricPreflight()) return
        val binding = device.snapshot.bindingOrNull ?: return
        if (!device.bindingCurrent(binding)) return
        device.setPreflightStarted(true)
        val owner = DeviceCheckOwner<L>(binding, device.firstRunLease())
        effects.clearStartupConfirmation()
        val attempt = MetricPreflightAttempt(metricPreflightAttempt.generation + 1L, scope.feedbackGeneration,
            owner, permissionPending = true)
        metricPreflightAttempt = attempt
        runtimeMetricOutputAllowed = false
        runtimeMetricInitialNavigationStartPending = false
        effects.refreshStartupUi()
        if (!effects.cameraPermission) {
            effects.requestCameraPermission()
            return
        }
        attempt.consumePermission()
        effects.startNativePreflight(attempt.generation, attempt.lifecycleGeneration)
    }

    fun finishDeviceMetricDepthSupportCheck(
        generation: Long,
        lifecycleGeneration: Int,
        depthSupport: RuntimeMetricDepthSupport,
        diagnosticReason: String,
    ) {
        val attempt = metricPreflightAttempt
        if (!isRuntimeMetricPreflightAttemptCurrent(generation, lifecycleGeneration) ||
            metricPreflightAttempt !== attempt) return
        val completedBinding = attempt.binding ?: return
        val completedOwner = attempt.kind
        if (
            completedOwner != RuntimeMetricPreflightOwner.DEVICE_CHECK &&
            completedOwner != RuntimeMetricPreflightOwner.STORED_DEPTH_REFRESH
        ) return
        val supportsMetricDistance = when (depthSupport) {
            RuntimeMetricDepthSupport.SUPPORTED -> true
            RuntimeMetricDepthSupport.UNSUPPORTED -> false
            RuntimeMetricDepthSupport.UNKNOWN -> null
        }
        val supportState = when (depthSupport) {
            RuntimeMetricDepthSupport.SUPPORTED -> PostLoginMetricDepthState.SUPPORTED
            RuntimeMetricDepthSupport.UNSUPPORTED -> PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED
            RuntimeMetricDepthSupport.UNKNOWN -> PostLoginMetricDepthState.UNKNOWN
        }
        metricPreflightAttempt = attempt.retired(retainEvaluator = true)
        device.setPreflightStarted(false)
        runtimeMetricOutputAllowed = false
        runtimeMetricInitialNavigationStartPending = false
        effects.info(
            "WalkSafeDepthCheck",
            "owner=$completedOwner support=$depthSupport evidence=DEPTH_API_SUPPORT_ONLY " +
                "reason=$diagnosticReason",
        )
        if (completedOwner == RuntimeMetricPreflightOwner.STORED_DEPTH_REFRESH) {
            if (!device.completeStoredRefresh(supportsMetricDistance, completedBinding)) {
                device.restoreDepth(PostLoginMetricDepthState.UNKNOWN)
                metricDistanceCapabilityOverride = null
            }
        } else {
            device.restoreDepth(supportState)
            metricDistanceCapabilityOverride = supportsMetricDistance
            device.continueDeviceCheck(completedBinding)
        }
        val detail = when (depthSupport) {
            RuntimeMetricDepthSupport.SUPPORTED ->
                "Depth API 지원을 확인했습니다. 실제 거리 안내는 사용 중 유효한 거리 데이터가 확보된 경우에만 제공합니다."
            RuntimeMetricDepthSupport.UNSUPPORTED ->
                "이 기기는 Depth API를 지원하지 않습니다. 미터 단위 거리 안내가 제한됩니다."
            RuntimeMetricDepthSupport.UNKNOWN ->
                "Depth API 지원을 확인하지 못했습니다. 카메라 권한과 Google Play Services for AR 설치·업데이트 상태를 확인해 주세요."
        }
        effects.status("Depth API 지원 확인", detail)
        effects.refreshStartupUi()
    }

    fun finishRuntimeMetricPreflightWithoutSession(
        generation: Long,
        lifecycleGeneration: Int,
        depthSupport: RuntimeMetricDepthSupport,
    ) {
        if (!isRuntimeMetricPreflightAttemptCurrent(generation, lifecycleGeneration)) return
        val evaluator = RuntimeMetricPreflightSession(
            generation = generation,
            startedAtElapsedRealtimeMs = elapsedRealtimeMs(),
            depthSupport = depthSupport,
            profile = scope.profile,
        )
        val result = if (depthSupport == RuntimeMetricDepthSupport.UNKNOWN) {
            evaluator.failTransiently(elapsedRealtimeMs())
        } else {
            evaluator.result()
        }
        finishRuntimeMetricPreflight(
            result,
            expectedArSessionGeneration = null,
            expectedLifecycleGeneration = lifecycleGeneration,
        )
    }

    fun isRuntimeMetricPreflightAttemptCurrent(
        generation: Long,
        lifecycleGeneration: Int,
    ): Boolean {
        val attempt = metricPreflightAttempt
        val binding = attempt.binding ?: return false
        return attempt.ownerCurrent(device) &&
            device.bindingLive(binding) &&
            generation == attempt.generation && metricPreflightAttempt === attempt &&
            lifecycleGeneration == scope.feedbackGeneration &&
            lifecycleGeneration == attempt.lifecycleGeneration &&
            scope.foreground &&
            !scope.finishing &&
            !scope.destroyed
    }

    fun finishRuntimeMetricPreflight(
        result: RuntimeMetricPreflightResult,
        expectedArSessionGeneration: Long?,
        expectedLifecycleGeneration: Int,
    ) {
        val attempt = metricPreflightAttempt
        val completedBinding = attempt.binding ?: return
        var completedOwner = RuntimeMetricPreflightOwner.NONE
        var acquisitionDiagnostics = "not_started"
        val currentSession = synchronized(runtimeMetricStateLock) {
            if (metricPreflightAttempt !== attempt ||
                !isRuntimeMetricPreflightAttemptCurrent(result.generation, expectedLifecycleGeneration)) return
            if (
                expectedArSessionGeneration != null &&
                (
                    native.purpose != preflightPurpose ||
                        native.generation != expectedArSessionGeneration
                    )
            ) {
                return
            }
            completedOwner = attempt.kind
            acquisitionDiagnostics = native.preflightDiagnostics()
            val ownedSession = native.detachCompletedPreflight()
            metricPreflightAttempt = attempt.retired()
            runtimeMetricActivationSession = null
            runtimeMetricOutputAllowed = false
            runtimeMetricInitialNavigationStartPending = false
            runtimeMetricLastValidFrameAtMs = 0L
            runtimeMetricLastFrameTimestampNanos = 0L
            ownedSession
        }
        native.finishDetachedPreflight(currentSession)

        if (completedOwner == RuntimeMetricPreflightOwner.STORED_DEPTH_REFRESH) {
            effects.info(
                "WalkSafeDepthCheck",
                "owner=$completedOwner status=${result.status} reason=${result.reason} " +
                    "distinct=${result.distinctFrameCount} passing=${result.passingFrameCount} " +
                    "spanMs=${result.observationSpanMs} $acquisitionDiagnostics",
            )
            val saved = device.completeStoredRefresh(result.metricDistanceAvailable, completedBinding)
            if (saved) {
                val profileMatch = device.profileMatch()
                device.persistResult(result, profileMatch)
                val detail = if (result.status == RuntimeMetricPreflightStatus.AVAILABLE) {
                    "저장된 다른 기기 점검 결과는 유지하고 미터 거리 기능만 사용할 수 있도록 갱신했습니다."
                } else {
                    "저장된 다른 기기 점검 결과는 유지하고 미터 거리 기능만 제한 상태로 갱신했습니다."
                }
                effects.status("기기 거리 기능 자동 갱신 완료", detail)
            }
            effects.refreshStartupUi()
            return
        }
        if (completedOwner != RuntimeMetricPreflightOwner.DEVICE_CHECK) return
        metricDistanceCapabilityOverride = result.metricDistanceAvailable
        device.setPreflightStarted(false)
        if (
            device.snapshot.state == PostLoginDeviceCheckState.RUNNING &&
            PostLoginDeviceCheckPolicy.isCurrent(device.snapshot, completedBinding)
        ) {
            device.restoreDepth(when (result.status) {
                RuntimeMetricPreflightStatus.AVAILABLE -> PostLoginMetricDepthState.AVAILABLE
                RuntimeMetricPreflightStatus.UNSUPPORTED ->
                    PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED
                RuntimeMetricPreflightStatus.UNKNOWN -> if (
                    result.reason == RuntimeMetricPreflightReason.TIMEOUT
                ) {
                    PostLoginMetricDepthState.TIMED_OUT
                } else {
                    PostLoginMetricDepthState.UNKNOWN
                }
                RuntimeMetricPreflightStatus.IN_PROGRESS -> PostLoginMetricDepthState.PENDING
            })
            device.continueDeviceCheck(completedBinding)
        }
        val profileMatch = device.profileMatch()
        device.persistResult(result, profileMatch)
        effects.refreshStartupUi()
        val detail = when (result.status) {
            RuntimeMetricPreflightStatus.AVAILABLE ->
                "휴대폰 모델 목록이 아니라 실제 거리 프레임으로 미터 단위 측정 기능을 확인했습니다."
            RuntimeMetricPreflightStatus.UNSUPPORTED -> WALKSAFE_LIMITED_DISTANCE_NOTICE_KO
            RuntimeMetricPreflightStatus.UNKNOWN,
            RuntimeMetricPreflightStatus.IN_PROGRESS,
            -> "거리 기능을 확정할 수 없어 시작을 차단했습니다. 기기 상태를 확인한 뒤 다시 검사하세요."
        }
        effects.status("기기 거리 기능 확인 완료", detail)
    }

    fun retainDeviceDepthApiCapability(
        reason: String,
        expectedBinding: PostLoginDeviceCheckBinding?,
        previousCapability: Boolean?,
    ): Boolean? {
        // API support is not foreground authority. The walk gate checks foreground and the
        // published onboarding snapshot separately; that publication follows a stage change.
        val complete = device.onboardingMayEnterWalk
        val bindingCurrent = expectedBinding != null && device.snapshot.passesFeatureGate &&
            device.snapshot.bindingOrNull == expectedBinding &&
            device.bindingLive(expectedBinding)
        val retained = kr.co.hanium.dreamup.walksafe.device.retainedMetricDepthCapabilityAfterLifecycle(
            reason = reason,
            previousCapability = previousCapability,
            depthState = device.depthState,
            onboardingComplete = complete,
            deviceCheckCurrent = bindingCurrent,
            cameraChecksDeferred = device.cameraChecksDeferred,
            supportRefreshPending = device.storedRefreshPending,
        )
        if (effects.debug && reason in setOf("app_paused", "first_run_stage_changed")) {
            effects.debug("WalkSafeDepthCheck", "event=API_SUPPORT_RETENTION reason=$reason " +
                "previous=$previousCapability state=${device.depthState} complete=$complete " +
                "binding_current=$bindingCurrent deferred=${device.cameraChecksDeferred} " +
                "refresh_pending=${device.storedRefreshPending} retained=$retained")
        }
        return retained
    }

    fun handleMetricPreflightCameraPermissionResult() {
        val attempt = metricPreflightAttempt
        if (!attempt.consumePermission()) return
        if (!isRuntimeMetricPreflightAttemptCurrent(attempt.generation, attempt.lifecycleGeneration)) return
        if (effects.cameraPermission) {
            effects.startNativePreflight(attempt.generation, attempt.lifecycleGeneration)
        } else {
            effects.refreshStartupUi()
            val detail = if (effects.explainCameraPermission) {
                "실제 미터 거리 기능을 확인하려면 카메라 권한이 필요합니다."
            } else {
                "Android 앱 설정에서 카메라 권한을 허용한 뒤 다시 확인하세요."
            }
            effects.status("카메라 권한 필요", detail)
        }
    }

    fun handleRuntimeMetricFrameFailure(
        status: String,
        detail: String,
        expectedPurpose: Q,
        expectedArSessionGeneration: Long,
        expectedWalkEpoch: WalkRuntimeEpoch,
    ) {
        if (
            native.purpose != expectedPurpose ||
            native.generation != expectedArSessionGeneration ||
            scope.lifecycle.snapshot().epoch != expectedWalkEpoch
        ) return
        runtimeMetricOutputAllowed = false
        runtimeVisualDepthFrameTracking = false
        if (
            expectedPurpose == runtimePurpose &&
            scope.onboardingComplete
        ) {
            effects.cameraFrameUnavailable(expectedWalkEpoch, elapsedRealtimeMs())
        }
        val attempt = metricPreflightAttempt
        val expectedLifecycleGeneration = attempt.lifecycleGeneration
        when (expectedPurpose) {
            preflightPurpose -> {
                val active = attempt.evaluator ?: return
                val result = active.failTransiently(elapsedRealtimeMs())
                if (!attempt.claimTerminal(result.generation)) return
                effects.postToUi {
                    finishRuntimeMetricPreflight(
                        result,
                        expectedArSessionGeneration = expectedArSessionGeneration,
                        expectedLifecycleGeneration = expectedLifecycleGeneration,
                    )
                }
            }
            runtimePurpose -> effects.postToUi {
                effects.runtimeMetricLost(expectedArSessionGeneration)
            }
            nonePurpose -> effects.postToUi { effects.status(status, detail) }
        }
    }

    fun currentRuntimeMetricOutputAllowsWork(
        expectedGeneration: Long? = null,
        observeRuntimeAr: () -> RuntimeMetricArObservation<Q> = native::observe,
    ): Boolean = synchronized(runtimeMetricStateLock) {
        scope.onboardingComplete &&
            scope.runtimeActive &&
            scope.foreground &&
            scope.cameraOutputsAllowed &&
            scope.mountingOutputsAllowed &&
            observeRuntimeAr().allowsMetricLease(runtimePurpose, expectedGeneration) &&
            runtimeMetricOutputAllowed && !native.cleanupPending &&
            scope.metricDistanceAllowed == true &&
            scope.startupConfirmed
    }

    /** Allows only current visual observations through a short depth gap, never metric consumers. */
    fun currentRuntimeVisualDepthProcessingAllowsWork(
        lease: RuntimeMetricArObservation<Q>,
        frameTimestampNanos: Long,
        tracking: Boolean,
    ): Boolean = synchronized(runtimeMetricStateLock) {
        if (!tracking || frameTimestampNanos <= 0L ||
            frameTimestampNanos != runtimeMetricLastFrameTimestampNanos || !native.observe().matchesLease(lease)
        ) return@synchronized false
        if (currentRuntimeMetricOutputAllowsWork(lease.generation)) return@synchronized true
        scope.onboardingComplete &&
            scope.runtimeActive &&
            scope.foreground &&
            scope.cameraOutputsAllowed &&
            scope.mountingOutputsAllowed &&
            native.purpose == runtimePurpose &&
            native.sessionPresent && native.providerPresent &&
            runtimeMetricActivationSession == null &&
            scope.metricDistanceAllowed == true &&
            scope.startupConfirmed &&
            allowsRuntimeDepthGapProcessing(
                frameTimestampNanos = frameTimestampNanos,
                latestFrameTimestampNanos = runtimeMetricLastFrameTimestampNanos,
                observedAtElapsedRealtimeMs = elapsedRealtimeMs(),
                lastValidFrameAtElapsedRealtimeMs = runtimeMetricLastValidFrameAtMs,
                tracking = tracking,
            )
    }

    /** Native publication calls this with its newly issued generation and original start clock. */
    fun beginRuntimeMetricActivation(generation: Long, startedAtElapsedRealtimeMs: Long) {
        synchronized(runtimeMetricStateLock) {
            runtimeMetricOutputAllowed = false
            runtimeMetricInitialNavigationStartPending = true
            runtimeMetricLastValidFrameAtMs = startedAtElapsedRealtimeMs
            runtimeMetricLastFrameTimestampNanos = 0L
            runtimeMetricActivationSession = RuntimeMetricPreflightSession(
                generation = generation,
                startedAtElapsedRealtimeMs = startedAtElapsedRealtimeMs,
                depthSupport = RuntimeMetricDepthSupport.SUPPORTED,
                profile = scope.profile,
            )
            runtimeMetricFrameDiagnosticAtMs = 0L
        }
    }

    fun updateRuntimeMetricFrameEvidence(
        expectedArSessionGeneration: Long,
        expectedWalkEpoch: WalkRuntimeEpoch,
        acquisitionFailure: kr.co.hanium.dreamup.walksafe.depth.DepthAcquisitionFailureReason? = null,
        observeRuntimeAr: () -> RuntimeMetricArObservation<Q> = native::observe,
        observeRuntimeAdmission: () -> Boolean = { scope.runtimeActive && scope.startupConfirmed },
        observeFrameEvidence: () -> RuntimeMetricFrameEvidence,
    ) {
        val acquisitionFatal = acquisitionFailure?.stopsImmediately == true
        val activation = synchronized(runtimeMetricStateLock) {
            if (
                !observeRuntimeAdmission() ||
                !scope.lifecycle.isRuntimeEpochCurrent(expectedWalkEpoch) ||
                scope.metricDistanceAllowed != true ||
                !observeRuntimeAr().hasPurposeAndGeneration(runtimePurpose, expectedArSessionGeneration) ||
                !scope.foreground
            ) {
                runtimeMetricOutputAllowed = false
                runtimeVisualDepthFrameTracking = false
                return
            }
            runtimeMetricActivationSession
        }
        val frameEvidence = observeFrameEvidence()
        if (frameEvidence.generation != expectedArSessionGeneration) {
            synchronized(runtimeMetricStateLock) {
                runtimeMetricOutputAllowed = false
                runtimeVisualDepthFrameTracking = false
            }
            return
        }
        val observedAtElapsedRealtimeMs = frameEvidence.observedAtElapsedRealtimeMs
        if (activation != null) {
            val result = activation.observe(frameEvidence)
            var activated = false
            var lost = false
            synchronized(runtimeMetricStateLock) {
                val stillCurrent =
                    observeRuntimeAdmission() &&
                        observeRuntimeAr().hasPurposeAndGeneration(runtimePurpose, expectedArSessionGeneration) &&
                        runtimeMetricActivationSession === activation &&
                        scope.foreground &&
                        scope.metricDistanceAllowed == true
                if (!stillCurrent) {
                    runtimeMetricOutputAllowed = false
                } else if (result.status == RuntimeMetricPreflightStatus.AVAILABLE && !acquisitionFatal) {
                    runtimeMetricOutputAllowed = frameEvidence.tracking &&
                        frameEvidence.metricDepthAvailable &&
                        frameEvidence.validMetricSamplesInRange >=
                        RuntimeMetricPreflightPolicy.MIN_VALID_SAMPLES_PER_PASSING_FRAME
                    runtimeMetricActivationSession = null
                    runtimeMetricLastFrameTimestampNanos = frameEvidence.frameTimestampNanos
                    if (runtimeMetricOutputAllowed) {
                        runtimeMetricLastValidFrameAtMs = observedAtElapsedRealtimeMs
                        activated = runtimeMetricInitialNavigationStartPending
                    }
                    runtimeVisualDepthFrameTracking = frameEvidence.tracking && runtimeMetricOutputAllowed
                } else {
                    runtimeMetricOutputAllowed = false
                    lost = result.status == RuntimeMetricPreflightStatus.UNKNOWN || acquisitionFatal
                }
            }
            if (activated) {
                effects.postToUi {
                    startInitialNavigationServicesIfCurrent(expectedArSessionGeneration, expectedWalkEpoch)
                }
            } else if (lost) {
                if (effects.debug) runCatching { effects.debug("WalkSafeDepthCheck",
                    "event=METRIC_QUALIFICATION_REJECTED reason=${result.reason.name} " +
                        "failure=${acquisitionFailure?.name ?: "NONE"} tracking=${frameEvidence.tracking} " +
                        "samples=${frameEvidence.validMetricSamplesInRange}") }
                if (result.reason == RuntimeMetricPreflightReason.TIMEOUT && !acquisitionFatal) {
                    retainRuntimeMetricSessionAfterOrdinaryLoss(expectedArSessionGeneration, expectedWalkEpoch,
                        observedAtElapsedRealtimeMs, observeRuntimeAr, observeRuntimeAdmission, activation)
                } else {
                    effects.postToUi { effects.runtimeMetricLost(expectedArSessionGeneration) }
                }
            }
            return
        }

        val samplesAreValid = frameEvidence.tracking &&
            frameEvidence.metricDepthAvailable &&
            frameEvidence.validMetricSamplesInRange >=
            RuntimeMetricPreflightPolicy.MIN_VALID_SAMPLES_PER_PASSING_FRAME
        var shouldHandleLoss = false
        var ordinaryDepthGap = false
        var shouldStartNavigation = false
        synchronized(runtimeMetricStateLock) {
            val stillCurrent =
                observeRuntimeAr().hasPurposeAndGeneration(runtimePurpose, expectedArSessionGeneration) &&
                    observeRuntimeAdmission() &&
                    runtimeMetricActivationSession == null &&
                    scope.foreground &&
                    scope.metricDistanceAllowed == true
            if (!stillCurrent) {
                runtimeMetricOutputAllowed = false
                runtimeVisualDepthFrameTracking = false
                return
            }
            val assessment = assessRuntimeDepthFrame(
                frameTimestampNanos = frameEvidence.frameTimestampNanos,
                previousFrameTimestampNanos = runtimeMetricLastFrameTimestampNanos,
                observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
                lastValidFrameAtElapsedRealtimeMs = runtimeMetricLastValidFrameAtMs,
                samplesAreValid = samplesAreValid,
                staleTimeoutMs = staleTimeoutMs,
                acquisitionFailure = acquisitionFailure,
            )
            ordinaryDepthGap = assessment.fatalAcquisitionFailure == null &&
                frameEvidence.frameTimestampNanos >= runtimeMetricLastFrameTimestampNanos &&
                observedAtElapsedRealtimeMs >= runtimeMetricLastValidFrameAtMs
            if (assessment.isNewFrame) {
                runtimeMetricLastFrameTimestampNanos = frameEvidence.frameTimestampNanos
            }
            shouldStartNavigation = assessment.outputAllowed && runtimeMetricInitialNavigationStartPending
            runtimeMetricOutputAllowed = assessment.outputAllowed
            runtimeVisualDepthFrameTracking = frameEvidence.tracking && frameEvidence.frameTimestampNanos > 0L &&
                frameEvidence.frameTimestampNanos == runtimeMetricLastFrameTimestampNanos
            if (assessment.refreshesLastValidTime) {
                runtimeMetricLastValidFrameAtMs = observedAtElapsedRealtimeMs
            }
            shouldHandleLoss = assessment.shouldHandleLoss
            if (effects.debug && shouldHandleLoss) runCatching { effects.debug("WalkSafeDepthCheck",
                "event=METRIC_FRAME_REJECTED failure=${acquisitionFailure?.name ?: "NONE"} " +
                    "last_valid_age_ms=${observedAtElapsedRealtimeMs - runtimeMetricLastValidFrameAtMs} " +
                    "frame_new=${assessment.isNewFrame} samples_ok=$samplesAreValid " +
                    "tracking=${frameEvidence.tracking}") }
        }
        if (!runtimeMetricOutputAllowed) effects.clearReportFrame("runtime_metric_frame_invalid")
        if (shouldStartNavigation) {
            effects.postToUi {
                startInitialNavigationServicesIfCurrent(expectedArSessionGeneration, expectedWalkEpoch)
            }
        }
        if (shouldHandleLoss) {
            if (ordinaryDepthGap) {
                retainRuntimeMetricSessionAfterOrdinaryLoss(expectedArSessionGeneration, expectedWalkEpoch,
                    observedAtElapsedRealtimeMs, observeRuntimeAr, observeRuntimeAdmission)
            } else {
                effects.postToUi { effects.runtimeMetricLost(expectedArSessionGeneration) }
            }
        }
    }

    /** A missing metric image retires metric evidence, not the owned camera or walking route. */
    fun retainRuntimeMetricSessionAfterOrdinaryLoss(
        expectedArSessionGeneration: Long,
        expectedWalkEpoch: WalkRuntimeEpoch,
        lossObservedAtElapsedRealtimeMs: Long,
        observeRuntimeAr: () -> RuntimeMetricArObservation<Q> = native::observe,
        observeRuntimeAdmission: () -> Boolean = { scope.runtimeActive && scope.startupConfirmed },
        expectedActivation: RuntimeMetricPreflightSession? = null,
    ): Boolean {
        synchronized(runtimeMetricStateLock) {
            if (!observeRuntimeAdmission() || !scope.foreground ||
                !scope.lifecycle.isRuntimeEpochCurrent(expectedWalkEpoch) ||
                !observeRuntimeAr().allowsMetricLease(runtimePurpose, expectedArSessionGeneration) ||
                native.cleanupPending ||
                scope.metricDistanceAllowed != true ||
                (expectedActivation != null && runtimeMetricActivationSession !== expectedActivation) ||
                lossObservedAtElapsedRealtimeMs < runtimeMetricLastValidFrameAtMs
            ) return false
            runtimeMetricOutputAllowed = false
            runtimeVisualDepthFrameTracking = false
            val activation = runtimeMetricActivationSession
            if (activation?.result()?.reason == RuntimeMetricPreflightReason.TIMEOUT) {
                // A timed-out evaluator is terminal. The next current frame uses the live gate.
                runtimeMetricActivationSession = null
            }
        }
        effects.clearReportFrame("runtime_metric_waiting_for_depth")
        return true
    }

    fun startInitialNavigationServicesIfCurrent(expectedArSessionGeneration: Long, expectedWalkEpoch: WalkRuntimeEpoch) {
        synchronized(runtimeMetricStateLock) {
            if (
                !runtimeMetricInitialNavigationStartPending ||
                !scope.runtimeActive || !scope.foreground ||
                !scope.lifecycle.isRuntimeEpochCurrent(expectedWalkEpoch) ||
                !native.observe().allowsMetricLease(runtimePurpose, expectedArSessionGeneration) ||
                native.cleanupPending || !scope.startupConfirmed
            ) return
            effects.startNavigationServices()
            runtimeMetricInitialNavigationStartPending = false
        }
    }
}
