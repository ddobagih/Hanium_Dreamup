package kr.co.hanium.dreamup.walksafe.session.readiness

import java.util.Locale
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.network.GatewayEndpointPolicy
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.*

/** Keeps the actual capability decision's original clock/version, independently of reassessment. */
internal class StartupCapabilityReadinessEvidence(
    private val resolve: () -> WalkSafeStartupCapabilityDecision,
    private val currentVersion: () -> String,
    private val clock: () -> Long,
) {
    @Volatile private var decision: WalkSafeStartupCapabilityDecision? = null
    private var observedAtMs: Long? = null
    private var evidenceVersion: String? = null
    val currentDecision: WalkSafeStartupCapabilityDecision? get() = decision
    val originalObservedAtMs: Long? get() = observedAtMs
    val originalVersion: String? get() = evidenceVersion

    fun record(value: WalkSafeStartupCapabilityDecision) {
        decision = value
        observedAtMs = clock()
        evidenceVersion = currentVersion()
    }

    fun reusableOrRefresh(requested: WalkSafeStartupCapabilityDecision, captureStartedAt: Long): WalkSafeStartupCapabilityDecision {
        val previousObservedAt = observedAtMs
        return if (requested != decision || previousObservedAt == null || captureStartedAt < previousObservedAt ||
            captureStartedAt - previousObservedAt > WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS || evidenceVersion != currentVersion()) {
            resolve().also(::record)
        } else requested
    }

    fun supportingEvidence() = WalkSessionReadinessEvidence(
        source = "startup_capability:cached_probe_evaluation",
        observedAtElapsedRealtimeMs = checkNotNull(observedAtMs),
        maxAgeMs = WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS,
        sourceVersion = checkNotNull(evidenceVersion), requiredVersion = currentVersion(),
    )
}

internal class FirstRunReadinessProducer(
    private val complete: () -> Boolean,
    private val currentStage: () -> FirstRunOnboardingStage,
) {
    fun isComplete() = complete()
    fun evaluate(): Pair<WalkSessionReadinessStatus, String> = if (complete()) WalkSessionReadinessStatus.READY to ""
        else WalkSessionReadinessStatus.UNAVAILABLE to "first_run_onboarding_incomplete:" + currentStage().name.lowercase(Locale.US)
}

internal class PriorityWalkReadinessProducer(
    private val policy: () -> PriorityUserOnboardingPolicy,
    private val currentEnvironment: (WalkSafeStartupCapabilityDecision) -> PriorityUserSupportEnvironment,
    private val walkRuntimeActive: () -> Boolean,
    private val quickStart: () -> Boolean,
    private val currentActorId: () -> String?,
) {
    fun evaluate(decision: WalkSafeStartupCapabilityDecision): Pair<WalkSessionReadinessStatus, String> {
        val onboarding = policy().evaluate(currentEnvironment(decision), walkIsActive = walkRuntimeActive())
        return if ((quickStart() || onboarding.mayStartWalk) && currentActorId() != null) WalkSessionReadinessStatus.READY to ""
        else WalkSessionReadinessStatus.UNAVAILABLE to "priority_user_onboarding:" +
            (onboarding.walkBlockReason?.name?.lowercase(Locale.US) ?: "account_profile_unbound")
    }
}

/** The existing permission policy decides the required set, rather than copied boolean fields. */
internal class PermissionReadinessProducer(
    private val observe: () -> ObservedPermissionSnapshot,
    private val microphoneGranted: () -> Boolean,
) {
    fun evaluate(): Pair<WalkSessionReadinessStatus, String> {
        val observed = observe()
        val missing = WalkStartPermissionPolicy.required(activityRecognitionRequired = false).filterNot(observed::isGranted)
        return if (missing.isEmpty()) WalkSessionReadinessStatus.READY to ""
        else WalkSessionReadinessStatus.UNAVAILABLE to "required_walk_permissions_missing:" + missing.joinToString(",") { it.name.lowercase(Locale.US) }
    }
    fun voiceInput(decision: WalkSafeStartupCapabilityDecision): Pair<WalkSessionReadinessStatus, String> {
        if (!decision.allowsRequirement(WalkSafeStartupRequirement.MICROPHONE) || !decision.allowsRequirement(WalkSafeStartupRequirement.ON_DEVICE_STT)) return WalkSessionReadinessStatus.READY to ""
        return if (microphoneGranted()) WalkSessionReadinessStatus.READY to ""
        else WalkSessionReadinessStatus.UNAVAILABLE to "microphone_permission_missing"
    }
}

internal interface ReadinessFeatureAdmission {
    fun cameraEnabled(): Boolean
    fun cameraPermission(): Boolean
    fun locationEnabled(): Boolean
    fun locationPermission(): Boolean
    fun disabledFeatures(): Set<PostLoginDeviceCheckFeature>
    fun restrict(decision: WalkSafeStartupCapabilityDecision): WalkSafeStartupCapabilityDecision
}

internal interface ReadinessDetector {
    data class ModelState(val available: Boolean, val loadInFlight: Boolean, val failureReason: String?)
    fun ensureLoadedAfterCameraGate()
    fun isAvailable(): Boolean
    fun loadFailureReason(): String?
    fun modelState(): ModelState = ModelState(isAvailable(), false, loadFailureReason())
    fun modelKey(): String?
}

/** Composes original FeatureReadiness and detector admission, observing only the requested branch. */
internal class FeatureReadinessProducer(
    private val admission: ReadinessFeatureAdmission,
    private val detector: ReadinessDetector,
) {
    fun cameraEnabled() = admission.cameraEnabled()
    fun prepareDetector(decision: WalkSafeStartupCapabilityDecision, action: WalkSessionAction, firstRunComplete: () -> Boolean): WalkSafeStartupCapabilityDecision {
        var effective = decision
        if (firstRunComplete() && action in setOf(WalkSessionAction.START_WALK, WalkSessionAction.RESUME_WALK) &&
            admission.cameraEnabled() && admission.cameraPermission() &&
            WalkSafeStartupRequirement.CAMERA !in effective.pendingRequirements && WalkSafeStartupRequirement.CAMERA !in effective.unavailableRequirements) {
            val before = admission.disabledFeatures()
            detector.ensureLoadedAfterCameraGate()
            if (admission.disabledFeatures() != before) effective = admission.restrict(effective)
        }
        return effective
    }
    fun camera(decision: WalkSafeStartupCapabilityDecision) = WalkSessionFeatureReadiness.camera(
        guidanceEnabled = admission.cameraEnabled(), permissionGranted = admission.cameraPermission(),
        capability = startupRequirementCheck(decision, setOf(WalkSafeStartupRequirement.CAMERA)),
    )
    fun location(decision: WalkSafeStartupCapabilityDecision) = WalkSessionFeatureReadiness.location(
        guidanceEnabled = admission.locationEnabled(), permissionGranted = admission.locationPermission(),
        capability = startupRequirementCheck(decision, setOf(WalkSafeStartupRequirement.GPS)),
    )
    fun model(allowPreparationPending: Boolean = false): Pair<WalkSessionReadinessStatus, String> {
        val state = detector.modelState()
        return WalkSessionFeatureReadiness.model(admission.cameraEnabled(), state.available, state.failureReason,
            preparing = allowPreparationPending && state.loadInFlight && admission.cameraPermission())
    }
    fun modelKey() = detector.modelKey()
}

internal interface ReadinessResourceSource {
    fun retryFailedMonitoring()
    fun snapshot(): WalkSessionDeviceResourceSnapshot
    fun recordDiagnostic(source: String, resources: WalkSessionDeviceResourceSnapshot)
}

internal interface OfficialReadinessSource {
    fun assessment(epoch: WalkRuntimeEpoch): OfficialEnvironmentAssessment
    fun readiness(epoch: WalkRuntimeEpoch, assessment: OfficialEnvironmentAssessment?): Pair<WalkSessionReadinessStatus, String>
}

internal interface MountingReadinessSource {
    fun assessment(epoch: WalkRuntimeEpoch): PhoneMountingAssessment
    fun readiness(epoch: WalkRuntimeEpoch, assessment: PhoneMountingAssessment?): Pair<WalkSessionReadinessStatus, String>
}

internal class EndpointReadinessProducer(private val debug: Boolean, val origin: String) {
    fun evaluate(): Pair<WalkSessionReadinessStatus, String> {
        val ready = if (debug) GatewayEndpointPolicy.debugOriginOrNull(origin) != null
            else GatewayEndpointPolicy.approvedReleaseOriginOrNull(raw = origin, approvedOrigin = origin) != null
        return if (ready) WalkSessionReadinessStatus.READY to ""
        else WalkSessionReadinessStatus.UNAVAILABLE to "route_service_endpoint_unavailable"
    }
}

internal class GatewayReadinessProducer(private val currentActorReady: () -> Boolean) {
    fun evaluate(): Pair<WalkSessionReadinessStatus, String> = if (currentActorReady()) WalkSessionReadinessStatus.READY to ""
        else WalkSessionReadinessStatus.UNAVAILABLE to "gateway_session_unavailable"
    /** A separate actual domain read at the original metadata point, not the status read above. */
    fun evidenceVersion() = "gateway_session:${GatewaySessionProcessCoordinator.snapshot().generation}"
}

internal class WalkReadinessProducers(
    val firstRun: FirstRunReadinessProducer,
    private val priority: PriorityWalkReadinessProducer,
    private val permissions: PermissionReadinessProducer,
    val features: FeatureReadinessProducer,
    val endpoint: EndpointReadinessProducer,
    val gateway: GatewayReadinessProducer,
    private val official: OfficialReadinessSource,
    private val mounting: MountingReadinessSource,
) {
    fun evaluate(requirement: WalkSessionReadinessRequirement, decision: WalkSafeStartupCapabilityDecision,
        epoch: WalkRuntimeEpoch, officialAssessment: OfficialEnvironmentAssessment?, mountingAssessment: PhoneMountingAssessment?,
        resources: WalkSessionDeviceResourceSnapshot,
        allowModelPreparationPending: Boolean = false): Pair<WalkSessionReadinessStatus, String> = when (requirement) {
        WalkSessionReadinessRequirement.FIRST_RUN_ONBOARDING -> firstRun.evaluate()
        WalkSessionReadinessRequirement.PRIORITY_USER_ONBOARDING -> priority.evaluate(decision)
        WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT -> official.readiness(epoch, officialAssessment)
        WalkSessionReadinessRequirement.PHONE_MOUNTING -> mounting.readiness(epoch, mountingAssessment)
        WalkSessionReadinessRequirement.PERMISSIONS -> permissions.evaluate()
        WalkSessionReadinessRequirement.CAMERA -> features.camera(decision)
        WalkSessionReadinessRequirement.LOCATION -> features.location(decision)
        WalkSessionReadinessRequirement.METRIC_DISTANCE -> startupRequirementCheck(decision, setOf(WalkSafeStartupRequirement.METRIC_DISTANCE, WalkSafeStartupRequirement.APPROVED_DEVICE_PROFILE))
        WalkSessionReadinessRequirement.MODEL -> features.model(allowModelPreparationPending)
        WalkSessionReadinessRequirement.VOICE_INPUT -> permissions.voiceInput(decision)
        WalkSessionReadinessRequirement.VOICE_OUTPUT -> startupRequirementCheck(decision, setOf(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS))
        WalkSessionReadinessRequirement.ROUTE_SERVICE -> endpoint.evaluate()
        WalkSessionReadinessRequirement.GATEWAY -> gateway.evaluate()
        WalkSessionReadinessRequirement.DEVICE_RESOURCES -> combineReadinessChecks(
            startupRequirementCheck(decision, setOf(WalkSafeStartupRequirement.ANDROID_VERSION)), resources.readinessStatus to resources.reason)
    }
}

internal fun WalkSafeStartupCapabilityDecision.allowsRequirement(requirement: WalkSafeStartupRequirement): Boolean =
    tier != WalkSafeStartupCapabilityTier.BLOCKED && requirement !in unavailableRequirements && requirement !in pendingRequirements

internal fun WalkSafeStartupCapabilityDecision.effectiveWalkSessionMode(): WalkSessionMode = when {
    tier == WalkSafeStartupCapabilityTier.BLOCKED -> WalkSessionMode.UNAVAILABLE
    !allowsRequirement(WalkSafeStartupRequirement.METRIC_DISTANCE) -> WalkSessionMode.DISTANCE_LIMITED
    else -> WalkSessionMode.FULL
}

internal fun startupRequirementCheck(
        decision: WalkSafeStartupCapabilityDecision,
        requirements: Set<WalkSafeStartupRequirement>,
    ): Pair<WalkSessionReadinessStatus, String> {
        val unavailable = decision.unavailableRequirements.filter(requirements::contains)
        if (unavailable.isNotEmpty()) {
            return WalkSessionReadinessStatus.UNAVAILABLE to
                "startup_unavailable:${unavailable.joinToString(",") { it.name }}"
        }
        val pending = decision.pendingRequirements.filter(requirements::contains)
        if (pending.isNotEmpty()) {
            return WalkSessionReadinessStatus.PENDING to
                "startup_pending:${pending.joinToString(",") { it.name }}"
        }
        return WalkSessionReadinessStatus.READY to ""
    }

internal fun combineReadinessChecks(
        vararg checks: Pair<WalkSessionReadinessStatus, String>,
    ): Pair<WalkSessionReadinessStatus, String> {
        val unavailable = checks.filter { it.first == WalkSessionReadinessStatus.UNAVAILABLE }
        if (unavailable.isNotEmpty()) {
            return WalkSessionReadinessStatus.UNAVAILABLE to
                unavailable.map(Pair<WalkSessionReadinessStatus, String>::second)
                    .filter(String::isNotBlank)
                    .joinToString(",")
        }
        val pending = checks.filter { it.first == WalkSessionReadinessStatus.PENDING }
        if (pending.isNotEmpty()) {
            return WalkSessionReadinessStatus.PENDING to
                pending.map(Pair<WalkSessionReadinessStatus, String>::second)
                    .filter(String::isNotBlank)
                    .joinToString(",")
        }
        return WalkSessionReadinessStatus.READY to
            checks.map(Pair<WalkSessionReadinessStatus, String>::second)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(",")
    }
