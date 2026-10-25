package kr.co.hanium.dreamup.walksafe.session.permission

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckState
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityDecision
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionScope
import kr.co.hanium.dreamup.walksafe.session.*

/** Owns permission request leases and recovery state; existing runtime owners retain their state. */
internal class PermissionFlowController<L>(
    private val authority: PermissionFlowAuthority<L>,
    private val readiness: PermissionWalkReadiness,
    private val runtime: PermissionRuntimeActions,
    private val resultHandlers: PermissionResultHandlers,
    private val platform: PermissionPlatform,
    private val preferences: PermissionFlowPreferences,
    private val ui: PermissionFlowUi,
    private val requiredWalkPermissions: (ObservedPermissionSnapshot) -> List<String>,
    private val actorDigest: (ByteArray) -> String,
    private val requestCodeMin: Int,
    private val requestCodeMax: Int,
    private val newRecheckRequestId: () -> String = { UUID.randomUUID().toString() },
) {
    var walkSessionPermissionRequestInFlight = false
        private set
    var walkSessionPermissionRequestAttempted = false
        private set
    var walkSessionPermissionRequestCode: Int? = null
        private set
    private val permissionRequestLeases = mutableMapOf<Int, PermissionRequestLease<L>>()
    private val permissionRequestGenerationByPurpose = mutableMapOf<PermissionRequestPurpose, Long>()
    private var nextPermissionRequestCode = requestCodeMin
    var permissionRecoveryGate = PermissionRecoveryGate()
        private set
    var initialAppPermissionExitRequired = false
        private set
    var initialAppPermissionReRequestedThisLaunch = false
        private set

    fun observedPermission(permission: String): ObservedPermission? = permission.toObservedPermission()
    fun cancelRequest(requestCode: Int) { permissionRequestLeases.remove(requestCode) }
    fun ownsRequest(requestCode: Int) = permissionRequestLeases.containsKey(requestCode)
    fun clearRequestLeases() { permissionRequestLeases.clear() }
    fun clearPurposeGenerations() { permissionRequestGenerationByPurpose.clear() }
    fun restorePermissionRecoveryGateFromPrefs() { permissionRecoveryGate = preferences.restoreRecovery() }
    fun persistPermissionRecoveryGate() { preferences.persistRecovery(permissionRecoveryGate) }

    fun restoreInitialAppPermissionReRequestedThisLaunch(reRequested: Boolean) {
        initialAppPermissionReRequestedThisLaunch = reRequested
    }

    fun requestDevelopmentWalkPermissionsWithLease(permissions: Array<String>): Int? {
        walkSessionPermissionRequestInFlight = true
        walkSessionPermissionRequestCode = requestPermissionsWithLease(permissions, PermissionRequestPurpose.WALK_SESSION)
        return walkSessionPermissionRequestCode
    }

    fun requirePermissionRecoveryRecheckAfterPrivacyInspection() {
        if (permissionRecoveryGate.blocksAutomaticResourceStart) {
            permissionRecoveryGate = permissionRecoveryGate.recheckRequired()
            persistPermissionRecoveryGate()
        }
    }

    fun requestPermissionsWithLease(
        permissions: Array<String>,
        purpose: PermissionRequestPurpose,
    ): Int? {
        if (!firstRunPermissionRequestAllowed(purpose)) return null
        val postLoginBinding = if (purpose == PermissionRequestPurpose.POST_LOGIN_DEVICE_CHECK) {
            authority.postLoginSnapshot().bindingOrNull?.takeIf {
                authority.postLoginBindingLive(it)
            } ?: return null
        } else {
            null
        }
        val epoch = authority.lifecycleOrNull()!!.snapshot().epoch
        val requestedPermissions = permissions.mapNotNull { it.toObservedPermission() }.toSet()
        if (purpose == PermissionRequestPurpose.WALK_SESSION) {
            permissionRecoveryGate = permissionRecoveryGate.requestPending(requestedPermissions)
            persistPermissionRecoveryGate()
            runtime.blockFieldSessionRestore()
        }
        val generation = (permissionRequestGenerationByPurpose[purpose] ?: 0L) + 1L
        permissionRequestGenerationByPurpose[purpose] = generation
        var requestCode = nextPermissionRequestCode
        while (permissionRequestLeases.containsKey(requestCode)) {
            requestCode += 1
            if (requestCode > requestCodeMax) {
                requestCode = requestCodeMin
            }
            check(requestCode != nextPermissionRequestCode) {
                "No permission request code is available"
            }
        }
        nextPermissionRequestCode = requestCode + 1
        if (nextPermissionRequestCode > requestCodeMax) {
            nextPermissionRequestCode = requestCodeMin
        }
        permissionRequestLeases[requestCode] = PermissionRequestLease(
            purpose = purpose,
            epoch = epoch,
            generation = generation,
            firstRunLease = authority.firstRunLease(),
            requestedPermissions = requestedPermissions,
            requestedAndroidPermissions = permissions.toSet(),
            postLoginBinding = postLoginBinding,
        )
        try {
            platform.launchRequest(permissions, requestCode)
        } catch (error: RuntimeException) {
            permissionRequestLeases.remove(requestCode)
            if (purpose == PermissionRequestPurpose.WALK_SESSION) {
                enterPermissionRecoveryBarrier(
                    missingPermissions = requestedPermissions,
                    reason = "permission_request_failed",
                )
            }
            throw error
        }
        return requestCode
    }

    fun isPermissionRequestLeaseCurrent(
        lease: PermissionRequestLease<L>,
    ): Boolean {
        if (lease.purpose != PermissionRequestPurpose.INITIAL_APP_ENTRY) {
            if (!authority.firstRunLeaseCurrent(lease.firstRunLease)) return false
            if (!firstRunPermissionRequestAllowed(lease.purpose)) return false
        }
        if (
            permissionRequestGenerationByPurpose[lease.purpose] !=
            lease.generation
        ) return false
        if (lease.purpose == PermissionRequestPurpose.INITIAL_APP_ENTRY) {
            return authority.privacyInspectionComplete() &&
                !authority.privacyInspectionDestroyed()
        }
        if (lease.purpose == PermissionRequestPurpose.POST_LOGIN_DEVICE_CHECK) {
            return lease.postLoginBinding?.let(authority::postLoginBindingLive) == true
        }
        val snapshot = authority.lifecycleOrNull()!!.snapshot()
        if (snapshot.epoch != lease.epoch) return false
        return when (lease.purpose) {
            PermissionRequestPurpose.INITIAL_APP_ENTRY -> false
            PermissionRequestPurpose.POST_LOGIN_DEVICE_CHECK -> false
            PermissionRequestPurpose.METRIC_PREFLIGHT_CAMERA,
            PermissionRequestPurpose.WALK_SESSION,
            -> snapshot.isForeground &&
                snapshot.state in setOf(WalkSessionState.READY, WalkSessionState.PAUSED)
            PermissionRequestPurpose.RUNTIME_CAMERA,
            PermissionRequestPurpose.NAVIGATION,
            PermissionRequestPurpose.VOICE_COMMAND,
            -> authority.lifecycleOrNull()!!.isRuntimeEpochCurrent(lease.epoch)
        }
    }

    fun handleWalkSessionPermissionResult() {
        walkSessionPermissionRequestInFlight = false
        if (!authority.foreground()) return
        val decision = readiness.decision() ?: return
        val action = if (
            authority.lifecycleOrNull()!!.snapshot().state == WalkSessionState.PAUSED
        ) {
            WalkSessionAction.RESUME_WALK
        } else {
            WalkSessionAction.START_WALK
        }
        val missingRequired = readiness.missingRequired(action)
        val missing = readiness.missingForDecision(decision)
        runtime.refreshStartup()
        if (missingRequired.isNotEmpty()) {
            runtime.status(
                "필수 권한 필요",
                "보행 안내를 시작하지 않았습니다. 필요한 권한: " +
                    missingRequired.permissionGuidanceLabelsKo(),
            )
        } else if (missing.isNotEmpty()) {
            runtime.status(
                "일부 기능 권한 없음",
                "허용된 기능으로 계속합니다. 사용할 때 다시 확인할 권한: " +
                    missing.permissionGuidanceLabelsKo(),
            )
        }
    }

    fun firstRunPermissionRequestAllowed(
        purpose: PermissionRequestPurpose,
    ): Boolean = when (purpose) {
        PermissionRequestPurpose.INITIAL_APP_ENTRY ->
            authority.foreground() &&
                authority.privacyInspectionComplete() &&
                !authority.privacyInspectionDestroyed()
        PermissionRequestPurpose.POST_LOGIN_DEVICE_CHECK ->
            authority.postLoginSnapshot().state ==
                PostLoginDeviceCheckState.REQUESTING_PERMISSIONS &&
                authority.firstRunSnapshot().flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 &&
                authority.firstRunSnapshot().stage in setOf(
                    FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION,
                    FirstRunOnboardingStage.DEVICE_CHECK,
                    FirstRunOnboardingStage.FP004_TRAINING,
                    FirstRunOnboardingStage.COMPLETE,
                )
        PermissionRequestPurpose.METRIC_PREFLIGHT_CAMERA ->
            authority.metricPreflightAllowed()
        PermissionRequestPurpose.WALK_SESSION,
        PermissionRequestPurpose.RUNTIME_CAMERA,
        PermissionRequestPurpose.NAVIGATION,
        PermissionRequestPurpose.VOICE_COMMAND,
        -> authority.firstRunComplete()
    }

    fun requiredPostLoginDeviceCheckPermissions(): List<String> = buildList {
        if (!platform.cameraGranted()) add(Manifest.permission.CAMERA)
        if (!platform.preciseLocationGranted()) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (!platform.microphoneGranted()) add(Manifest.permission.RECORD_AUDIO)
        if (
            platform.sdkInt >= Build.VERSION_CODES.Q &&
            !platform.activityRecognitionGranted()
        ) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (
            platform.sdkInt >= Build.VERSION_CODES.TIRAMISU &&
            !platform.notificationGranted()
        ) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.distinct()

    fun requestedPostLoginDeviceCheckPermissions(): List<String> =
        if (
            preferences.ready() &&
            preferences.initialCompleted()
        ) {
            emptyList()
        } else {
            requiredPostLoginDeviceCheckPermissions()
        }

    /** True means the request callback ran; it does not confirm an OS dialog or granted permission. */
    fun requestSelectedWalkSessionPermissions(
        observedPermissions: () -> ObservedPermissionSnapshot,
        missingPermissionsForObservedSnapshot: (ObservedPermissionSnapshot) -> List<String>,
        requestPermissions: (Array<String>) -> Int?,
    ): Boolean {
        if (walkSessionPermissionRequestInFlight || walkSessionPermissionRequestAttempted) return false
        val requestable = missingPermissionsForObservedSnapshot(observedPermissions()).distinct()
        if (requestable.isEmpty()) return false
        walkSessionPermissionRequestAttempted = true
        walkSessionPermissionRequestInFlight = true
        walkSessionPermissionRequestCode = try {
            requestPermissions(requestable.toTypedArray())
        } catch (error: RuntimeException) {
            walkSessionPermissionRequestInFlight = false
            throw error
        }
        return true
    }

    fun maybeRequestMissingWalkSessionPermissions(
        decision: WalkSafeStartupCapabilityDecision,
    ) {
        if (
            !authority.firstRunComplete() ||
            !decision.mayConfirmAndStart ||
            authority.currentActorId() == null
        ) return
        requestSelectedWalkSessionPermissions(
            observedPermissions = ::currentObservedPermissionSnapshot,
            missingPermissionsForObservedSnapshot = requiredWalkPermissions,
            requestPermissions = { permissions ->
                requestPermissionsWithLease(permissions, PermissionRequestPurpose.WALK_SESSION)
            },
        )
    }

    fun ensureNavigationPermissions(requireActivityRecognition: Boolean) {
        val missing = mutableListOf<String>()
        if (!platform.preciseLocationGranted()) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (
            platform.sdkInt >= Build.VERSION_CODES.Q &&
            requireActivityRecognition &&
            !platform.activityRecognitionGranted()
        ) {
            runtime.navigationStatus(
                "navigation=activity_recognition_permission_missing step_tracking_limited",
            )
        }
        if (missing.isEmpty()) return
        requestPermissionsWithLease(
            missing.toTypedArray(),
            PermissionRequestPurpose.NAVIGATION,
        )
    }

    fun currentObservedPermissionSnapshot(): ObservedPermissionSnapshot =
        ObservedPermissionSnapshot(
            cameraGranted = platform.cameraGranted(),
            preciseLocationGranted = platform.preciseLocationGranted(),
            microphoneGranted = platform.microphoneGranted(),
            activityRecognitionGranted = platform.activityRecognitionGranted(),
        )

    // S02-14: FP-014-DETAIL-01 makes camera, precise location and microphone the OS permissions of
    // every walk start and resume; in-use revocation still stops only the dependent features.
    fun requiredStartWalkObservedPermissions(): Set<ObservedPermission> =
        WalkStartPermissionPolicy.required(activityRecognitionRequired = false)

    fun String.toObservedPermission(): ObservedPermission? = when (this) {
        Manifest.permission.CAMERA -> ObservedPermission.CAMERA
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        -> ObservedPermission.PRECISE_LOCATION
        Manifest.permission.RECORD_AUDIO -> ObservedPermission.MICROPHONE
        Manifest.permission.ACTIVITY_RECOGNITION -> ObservedPermission.ACTIVITY_RECOGNITION
        else -> null
    }

    fun enterPermissionRecoveryBarrier(
        missingPermissions: Set<ObservedPermission>,
        reason: String,
    ) {
        runtime.invalidateSpeechStorage()
        permissionRecoveryGate = permissionRecoveryGate.blocked(missingPermissions)
        persistPermissionRecoveryGate()
        runtime.blockFieldSessionRestore()
        showPermissionDenialPanel(
            missingPermissions = missingPermissions,
            reason = reason,
        )
    }

    fun currentPermissionRecoveryBinding(recheckRequestId: String): PermissionRecoveryBinding? {
        if (authority.lifecycleOrNull() == null) return null
        val actorId = authority.currentActorId() ?: return null
        val gateway = GatewaySessionProcessCoordinator.snapshot()
        val gatewaySession = gateway.session ?: return null
        if (
            gateway.storageBlocked || gateway.deletionRecoveryOnly ||
            gatewaySession.sessionScope != GatewaySessionScope.GENERAL ||
            !authority.gatewaySessionReady(gatewaySession)
        ) return null
        return PermissionRecoveryBinding(
            actorBindingSha256 = actorDigest(actorId.toByteArray(Charsets.UTF_8)),
            gatewaySessionGeneration = gateway.generation,
            walkEpoch = authority.lifecycleOrNull()!!.snapshot().epoch,
            recheckRequestId = recheckRequestId,
        )
    }

    fun invalidatePermissionRecoveryScopeIfChanged() {
        if (!permissionRecoveryGate.blocksAutomaticResourceStart) return
        val binding = permissionRecoveryGate.recheckBinding ?: return
        if (currentPermissionRecoveryBinding(binding.recheckRequestId) == binding) return
        val invalidated = permissionRecoveryGate.recheckRequired()
        if (invalidated != permissionRecoveryGate) {
            permissionRecoveryGate = invalidated
            persistPermissionRecoveryGate()
        }
    }

    fun beginPermissionRecoveryRecheck(): PermissionRecoveryBinding? {
        val existing = permissionRecoveryGate.recheckBinding
        if (
            existing != null &&
            permissionRecoveryGate.state == PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME &&
            currentPermissionRecoveryBinding(existing.recheckRequestId) == existing
        ) return existing
        val binding = currentPermissionRecoveryBinding(newRecheckRequestId()) ?: return null
        permissionRecoveryGate = permissionRecoveryGate.recheckRequired(binding)
        return binding
    }

    fun completePermissionRecoveryRecheckIfPossible() {
        if (!permissionRecoveryGate.blocksAutomaticResourceStart) return
        invalidatePermissionRecoveryScopeIfChanged()
        val observed = currentObservedPermissionSnapshot()
        val missing = requiredStartWalkObservedPermissions()
            .filterNot(observed::isGranted)
            .toSet()
        if (missing.isNotEmpty()) {
            permissionRecoveryGate = permissionRecoveryGate.blocked(missing)
            persistPermissionRecoveryGate()
            updatePermissionRecoveryUi()
            return
        }
        val decision = readiness.decision() ?: run {
            updatePermissionRecoveryUi()
            return
        }
        val recheckBinding = beginPermissionRecoveryRecheck() ?: run {
            updatePermissionRecoveryUi()
            return
        }
        val allPrerequisitesReady = permissionRecoveryPrerequisitesReady(decision) &&
            currentPermissionRecoveryBinding(recheckBinding.recheckRequestId) == recheckBinding
        permissionRecoveryGate = permissionRecoveryGate.completeFullRecheck(
            observed = observed,
            requiredPermissions = requiredStartWalkObservedPermissions(),
            allPrerequisitesReady = allPrerequisitesReady,
            resultBinding = recheckBinding,
        )
        persistPermissionRecoveryGate()
        if (
            permissionRecoveryGate.state ==
            PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME
        ) {
            renderAwaitingExplicitResumeControl()
        }
        updatePermissionRecoveryUi()
    }

    fun updatePermissionRecoveryUi() {
        if (!ui.panelReady()) return
        invalidatePermissionRecoveryScopeIfChanged()
        when (permissionRecoveryGate.state) {
            PermissionRecoveryGateState.CLEAR,
            PermissionRecoveryGateState.REQUEST_PENDING,
            -> ui.hidePanel()
            PermissionRecoveryGateState.BLOCKED,
            PermissionRecoveryGateState.SETTINGS_PENDING,
            -> showPermissionDenialPanel(
                missingPermissions = permissionRecoveryGate.affectedPermissions,
                reason = "permission_recovery_blocked",
            )
            PermissionRecoveryGateState.RECHECK_REQUIRED -> ui.renderProgress(awaitingResume = false)
            PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME -> ui.renderProgress(awaitingResume = true)
        }
    }

    fun showPermissionDenialPanel(
        missingPermissions: Set<ObservedPermission>,
        reason: String,
        requestExplanation: PermissionExplanation? = null,
    ) {
        if (!ui.panelReady()) return
        val message = (requestExplanation ?: PermissionExplanationPolicy.withoutResult(
            missingPermissions.mapTo(mutableSetOf()) { permission ->
                when (permission) {
                    ObservedPermission.CAMERA -> PermissionExplanationKind.CAMERA
                    ObservedPermission.PRECISE_LOCATION -> PermissionExplanationKind.PRECISE_LOCATION
                    ObservedPermission.MICROPHONE -> PermissionExplanationKind.MICROPHONE
                    ObservedPermission.ACTIVITY_RECOGNITION ->
                        PermissionExplanationKind.ACTIVITY_RECOGNITION
                }
            },
            coarseLocationCurrentlyGranted = platform.coarseLocationGranted(),
        )).let { PermissionExplanationText.messageKo(it,
            walkState = if (authority.lifecycleOrNull() != null) authority.lifecycleOrNull()!!.snapshot().state else null,
        ) }
        ui.renderDenial(message)
    }

    /** Shared by the automatic recheck and the explicit resume tap so both use one predicate. */
    fun permissionRecoveryPrerequisitesReady(
        decision: WalkSafeStartupCapabilityDecision?,
    ): Boolean {
        if (decision == null) return false
        return authority.firstRunComplete() &&
            authority.currentActorId() != null &&
            readiness.gatewayReady() &&
            decision.mayConfirmAndStart &&
            readiness.blockReason(decision) == null
    }

    fun resumePermissionRecoveryFromExplicitUserAction(): Boolean {
        if (
            permissionRecoveryGate.state !=
            PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME
        ) {
            return false
        }
        // The awaiting state may predate this tap. Accept it only against the permissions
        // observed now and the same prerequisite predicate the automatic recheck used.
        val confirmedBinding = permissionRecoveryGate.recheckBinding
        val currentBinding = confirmedBinding?.let { currentPermissionRecoveryBinding(it.recheckRequestId) }
        val acknowledged = if (confirmedBinding == null || currentBinding == null) {
            permissionRecoveryGate.recheckRequired()
        } else {
            permissionRecoveryGate.acknowledgeExplicitResume(
                observed = currentObservedPermissionSnapshot(),
                requiredPermissions = requiredStartWalkObservedPermissions(),
                currentBinding = currentBinding,
            )
        }
        permissionRecoveryGate = if (
            acknowledged.state == PermissionRecoveryGateState.CLEAR &&
            !permissionRecoveryPrerequisitesReady(readiness.decision())
        ) {
            acknowledged.recheckRequired()
        } else {
            acknowledged
        }
        persistPermissionRecoveryGate()
        if (permissionRecoveryGate.blocksAutomaticResourceStart) {
            completePermissionRecoveryRecheckIfPossible()
            runtime.status(
                "권한 재개 보류",
                "재개를 누른 시점의 권한, 로그인 또는 시작 준비 상태가 달라 보행 기능을 시작하지 않았습니다. 상태를 다시 확인하세요.",
            )
            return true
        }
        runtime.startAfterPermissionRecovery()
        return true
    }

    fun applyObservedPermissionStateChange(reason: String) {
        if (authority.lifecycleOrNull() == null) return
        val sessionSnapshot = authority.lifecycleOrNull()!!.snapshot()
        if (!platform.cameraGranted()) {
            runtime.cancelCameraHandoff()
            runtime.stopDepth()
            runtime.stopCameraFallback()
        }
        if (!platform.preciseLocationGranted() || !readiness.locationServiceEnabled()) {
            runtime.cancelDestinationLocation()
            runtime.clearReportLocationRequest()
            runtime.stopLocation()
            if (sessionSnapshot.state == WalkSessionState.ACTIVE && runtime.routeActive()) {
                runtime.cancelRouteRequest()
                runtime.resetRoute()
                runtime.navigationStatus("navigation=stopped location_unavailable reason=$reason")
            }
        }
        if (!platform.notificationGranted()) {
            runtime.stopHandsFree()
        }
        if (!platform.microphoneGranted()) {
            runtime.stopHandsFree()
            runtime.cancelVoiceRecognition()
        }
        if (!platform.activityRecognitionGranted()) {
            runtime.cancelReportDrain()
            runtime.stopSteps()
        }
        if (
            sessionSnapshot.state in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED) &&
            PermissionDependencyPolicy.evaluate(
                currentObservedPermissionSnapshot(),
            ).requiresWholeWalkSafetyStop
        ) {
            runtime.safetyStop("core_guidance_permissions_unavailable")
            enterPermissionRecoveryBarrier(
                missingPermissions = setOf(
                    ObservedPermission.CAMERA,
                    ObservedPermission.PRECISE_LOCATION,
                ),
                reason = reason,
            )
            return
        }
        // S03-6: refresh first. A lost camera or location safety-stops an active walk there, and the
        // "나머지 기능은 계속" limitation notice below must not contradict that stop.
        if (readiness.startupProbeReady()) runtime.refreshStartup()
        val stateAfterRefresh = authority.lifecycleOrNull()!!.snapshot().state
        if (
            sessionSnapshot.state in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED) &&
            stateAfterRefresh in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED) &&
            readiness.hasDeviceRestrictions()
        ) {
            val stoppedOnly = setOfNotNull(
                ObservedPermission.CAMERA.takeIf { !platform.cameraGranted() },
                ObservedPermission.PRECISE_LOCATION.takeIf { !platform.preciseLocationGranted() },
                ObservedPermission.MICROPHONE.takeIf { !platform.microphoneGranted() },
                ObservedPermission.ACTIVITY_RECOGNITION.takeIf {
                    !platform.activityRecognitionGranted()
                },
            )
            if (stoppedOnly.isNotEmpty()) showPermissionDenialPanel(stoppedOnly, reason)
            runtime.status(
                "일부 기능 제한",
                "제한 기능: ${readiness.disabledFeatureText()}. " +
                    "해당 기능만 중지하고 나머지 기능은 계속 사용합니다.",
            )
        } else if (
            sessionSnapshot.state in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED) &&
            !readiness.hasDeviceRestrictions() &&
            !permissionRecoveryGate.blocksAutomaticResourceStart &&
            ui.panelReady()
        ) {
            ui.hidePanel()
        }
        if (readiness.startupProbeReady()) {
            // The refresh above may have safety-stopped the walk (S03-6); read the state again.
            if (authority.lifecycleOrNull()!!.snapshot().state == WalkSessionState.ACTIVE) {
                if (!readiness.cameraAnalysisEnabled()) {
                    runtime.activateMounting()
                    runtime.applyEnvironment()
                    runtime.startWithoutCamera()
                } else {
                    runtime.applyEnvironment()
                    runtime.startNavigationServices()
                    runtime.startHandsFreeIfAllowed()
                }
            }
        }
        runtime.startLocationIfAllowed()
    }

    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val lease = permissionRequestLeases.remove(requestCode) ?: return
        if (
            lease.purpose == PermissionRequestPurpose.WALK_SESSION &&
            walkSessionPermissionRequestCode == requestCode
        ) {
            walkSessionPermissionRequestCode = null
            walkSessionPermissionRequestInFlight = false
        }
        if (!isPermissionRequestLeaseCurrent(lease)) return
        val requestExplanation = if (
            lease.purpose == PermissionRequestPurpose.WALK_SESSION &&
            permissions.size == grantResults.size
        ) {
            val answers = permissions.indices.mapNotNull { index ->
                val permission = permissions[index]
                if (permission !in lease.requestedAndroidPermissions) return@mapNotNull null
                val kind = when (permission) {
                    Manifest.permission.CAMERA -> PermissionExplanationKind.CAMERA
                    Manifest.permission.ACCESS_FINE_LOCATION -> PermissionExplanationKind.PRECISE_LOCATION
                    Manifest.permission.ACCESS_COARSE_LOCATION -> PermissionExplanationKind.APPROXIMATE_LOCATION
                    Manifest.permission.RECORD_AUDIO -> PermissionExplanationKind.MICROPHONE
                    Manifest.permission.ACTIVITY_RECOGNITION -> PermissionExplanationKind.ACTIVITY_RECOGNITION
                    else -> null
                }
                kind?.let {
                    PermissionRequestAnswer(
                        permission = it,
                        granted = grantResults[index] == PackageManager.PERMISSION_GRANTED,
                        rationaleRecommended =
                            grantResults[index] != PackageManager.PERMISSION_GRANTED &&
                                platform.rationaleRecommended(permission),
                    )
                }
            }
            PermissionExplanationPolicy.afterRequest(
                answers = answers,
                coarseLocationCurrentlyGranted = platform.coarseLocationGranted(),
            )
        } else {
            null
        }
        if (lease.purpose == PermissionRequestPurpose.WALK_SESSION) {
            permissionRecoveryGate = if (permissionRecoveryGate.recheckBinding != null) {
                permissionRecoveryGate.recheckRequired()
            } else {
                PermissionRecoveryGate()
            }
            persistPermissionRecoveryGate()
        }
        when (lease.purpose) {
            PermissionRequestPurpose.INITIAL_APP_ENTRY ->
                handleInitialAppEntryPermissionResult()
            PermissionRequestPurpose.POST_LOGIN_DEVICE_CHECK ->
                lease.postLoginBinding?.let(resultHandlers::postLogin)
            PermissionRequestPurpose.METRIC_PREFLIGHT_CAMERA ->
                resultHandlers.metricCamera()
            PermissionRequestPurpose.WALK_SESSION -> handleWalkSessionPermissionResult()
            PermissionRequestPurpose.RUNTIME_CAMERA -> resultHandlers.runtimeCamera()
            PermissionRequestPurpose.NAVIGATION -> resultHandlers.navigation()
            PermissionRequestPurpose.VOICE_COMMAND -> resultHandlers.voice()
        }
        applyObservedPermissionStateChange(
            "permission_result_${lease.purpose.name.lowercase(Locale.US)}",
        )
        if (requestExplanation != null) {
            showPermissionDenialPanel(
                missingPermissions = emptySet(),
                reason = "permission_result_walk_session",
                requestExplanation = requestExplanation,
            )
        }
    }


    fun requestInitialAppEntryPermissionsIfNeeded() {
        if (!preferences.ready()) return
        if (permissionRequestLeases.values.any { it.purpose == PermissionRequestPurpose.INITIAL_APP_ENTRY }) return
        if (preferences.initialStarted()) {
            initialAppPermissionReRequestedThisLaunch = true
            handleInitialAppEntryPermissionResult()
            return
        }
        val missing = requiredPostLoginDeviceCheckPermissions()
        if (missing.isEmpty()) {
            preferences.markInitialCompleted()
            clearInitialAppPermissionLimitationsIfResolved()
            return
        }
        if (preferences.initialCompleted() && initialAppPermissionReRequestedThisLaunch) {
            showInitialAppPermissionLimitations(missing.toSet())
            return
        }
        launchInitialAppEntryPermissionRequest(missing)
    }

    fun launchInitialAppEntryPermissionRequest(missing: List<String>) {
        initialAppPermissionReRequestedThisLaunch = true
        val attemptSaved = preferences.markInitialStarted()
        if (!attemptSaved) {
            runtime.status("앱 권한 요청 준비 실패", "권한 요청 상태를 저장하지 못했습니다. 앱을 다시 열어 확인하세요.")
            return
        }
        runtime.status("앱 권한 확인", "앱 사용에 필요한 Android 권한을 요청합니다. 필수 권한을 모두 허용해야 앱을 사용할 수 있습니다.")
        val requestCode = runCatching {
            requestPermissionsWithLease(missing.toTypedArray(), PermissionRequestPurpose.INITIAL_APP_ENTRY)
        }.getOrNull()
        if (requestCode == null) {
            preferences.clearInitialStarted()
            runtime.status("앱 권한 요청 실패", "권한 요청을 시작하지 못했습니다. 앱을 다시 열어 확인하세요.")
        }
    }

    fun handleInitialAppEntryPermissionResult() {
        val missingAtResult = requiredPostLoginDeviceCheckPermissions()
        if (missingAtResult.isNotEmpty()) {
            preferences.markInitialCompleted()
            showInitialAppPermissionLimitations(missingAtResult.toSet())
            return
        }
        val saved = preferences.markInitialCompleted()
        if (!saved) {
            runtime.status("권한 확인 저장 실패", "권한 확인 상태를 저장하지 못해 다음 실행에서 다시 확인할 수 있습니다.")
            return
        }
        val missing = requiredPostLoginDeviceCheckPermissions()
        if (missing.isEmpty()) {
            clearInitialAppPermissionLimitationsIfResolved()
            runtime.status("앱 권한 확인 완료", "필요한 Android 권한이 모두 허용되었습니다.")
            return
        }
        showInitialAppPermissionLimitations(missing.toSet())
    }

    fun clearInitialAppPermissionLimitationsIfResolved() {
        if (requiredPostLoginDeviceCheckPermissions().isNotEmpty()) return
        val wasBlocked = initialAppPermissionExitRequired
        initialAppPermissionExitRequired = false
        ui.dismissRequiredExit()
        if (wasBlocked) runtime.renderMain()
    }

    fun showInitialAppPermissionLimitations(missing: Set<String>) = showRequiredAppPermissionExit(missing)

    fun showRequiredAppPermissionExit(missing: Set<String>) {
        if (missing.isEmpty() || platform.finishing() || platform.destroyed()) return
        if (platform.debugExitBypass()) {
            initialAppPermissionExitRequired = false
            runtime.navigationStatus("permissions=missing features_waiting count=${missing.size}")
            return
        }
        initialAppPermissionExitRequired = true
        runtime.cancelPrewalk()
        if (readiness.runtimeActive()) runtime.safetyStop("required_app_permission_denied")
        if (ui.requiredExitShowing()) return
        ui.showRequiredExit(missing)
    }

    fun onPermissionDenialConfirmed() {
        when (permissionRecoveryGate.state) {
            PermissionRecoveryGateState.BLOCKED -> {
                runtime.blockFieldSessionRestore()
                permissionRecoveryGate = if (permissionRecoveryGate.recheckBinding != null) permissionRecoveryGate.recheckRequired() else PermissionRecoveryGate()
                persistPermissionRecoveryGate()
                ui.hidePanel()
                runtime.refreshStartup()
                runtime.status("일부 기능 제한", "권한이 필요한 기능만 제한하고 나머지 앱 기능을 계속 사용합니다.")
            }
            PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME -> {
                ui.hidePanel()
                renderAwaitingExplicitResumeControl()
                ui.postResumeFocus { permissionRecoveryGate.state == PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME }
                runtime.status("권한 재개 대기", "차단 상태를 유지합니다. 보행 안내 시작 또는 재개를 다시 선택하세요.")
            }
            PermissionRecoveryGateState.RECHECK_REQUIRED -> {
                if (!authority.foreground() || !authority.privacyInspectionComplete() ||
                    authority.privacyInspectionDestroyed()) return
                completePermissionRecoveryRecheckIfPossible()
                if (permissionRecoveryGate.state == PermissionRecoveryGateState.RECHECK_REQUIRED) {
                    runtime.recheckPrewalkReadiness()
                }
            }
            PermissionRecoveryGateState.SETTINGS_PENDING,
            PermissionRecoveryGateState.REQUEST_PENDING,
            PermissionRecoveryGateState.CLEAR -> ui.hidePanel()
        }
    }

    fun renderAwaitingExplicitResumeControl() = ui.renderResumeControl()

    fun prepareForAppSettings() {
        val missing = requiredStartWalkObservedPermissions().filterNot(currentObservedPermissionSnapshot()::isGranted).toSet()
        if (missing.isNotEmpty() || permissionRecoveryGate.blocksAutomaticResourceStart) {
            if (!permissionRecoveryGate.blocksAutomaticResourceStart) enterPermissionRecoveryBarrier(missing, "settings_requested")
            permissionRecoveryGate = permissionRecoveryGate.settingsPending()
            persistPermissionRecoveryGate()
        }
    }

    private fun List<String>.permissionGuidanceLabelsKo(): String = filterNot {
        it == Manifest.permission.ACCESS_COARSE_LOCATION && platform.coarseLocationGranted()
    }.joinToString(", ") { PermissionExplanationText.permissionLabelKo(it) }

}
