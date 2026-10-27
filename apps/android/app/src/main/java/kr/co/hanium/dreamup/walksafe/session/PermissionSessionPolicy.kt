package kr.co.hanium.dreamup.walksafe.session

import kr.co.hanium.dreamup.walksafe.network.MobileNetworkPreference

enum class AuthenticationState {
    SIGNED_OUT,
    REAUTHENTICATION_REQUIRED,
    ACTIVE,
}

data class PermissionSessionSnapshot(
    val actorId: String? = null,
    val authentication: AuthenticationState = AuthenticationState.SIGNED_OUT,
    val rawCollectionConsentGranted: Boolean = false,
    val automaticReportConsentGranted: Boolean = false,
    val mobileNetworkPreference: MobileNetworkPreference = MobileNetworkPreference.WIFI_ONLY,
    val trainingReuseConsentGranted: Boolean = false,
) {
    init {
        require((authentication == AuthenticationState.SIGNED_OUT) == (actorId == null)) {
            "A signed-out state must not retain an actor, and an account state must identify one"
        }
    }

    val mayUseProtectedServerFeature: Boolean
        get() = authentication == AuthenticationState.ACTIVE

    val mayCreateAutomaticReport: Boolean
        get() = authentication == AuthenticationState.ACTIVE && automaticReportConsentGranted

    fun mayReuseForTraining(kind: TrainingSourceKind): Boolean = TrainingReuseConsentRule.allows(
        kind = kind,
        rawCollectionConsentGranted = rawCollectionConsentGranted,
        trainingReuseConsentGranted = trainingReuseConsentGranted,
    )
}

/** Material that may become a model-training candidate; each kind has its own consent rule. */
enum class TrainingSourceKind {
    /** Walk raw/diagnostic material: only exists under raw collection consent (FP-013). */
    WALK_RAW_DIAGNOSTIC,

    /** Report original: needs training consent separate from report processing (FP-031 design_rules[3]). */
    REPORT_ORIGINAL,
}

/**
 * Single training-reuse truth table shared by [PermissionSessionSnapshot] and
 * [IntegratedConsentSelections] (S02-22). Training consent is always required; walk raw material
 * additionally requires raw collection consent, a report original does not. The server still makes
 * the final per-item promotion decision; this only keeps the two Android views identical.
 */
object TrainingReuseConsentRule {
    fun allows(
        kind: TrainingSourceKind,
        rawCollectionConsentGranted: Boolean,
        trainingReuseConsentGranted: Boolean,
    ): Boolean = trainingReuseConsentGranted && when (kind) {
        TrainingSourceKind.WALK_RAW_DIAGNOSTIC -> rawCollectionConsentGranted
        TrainingSourceKind.REPORT_ORIGINAL -> true
    }
}

/** Keeps account, consent, and transfer decisions independent from Android permission ownership. */
class PermissionSessionPolicy(
    initial: PermissionSessionSnapshot = PermissionSessionSnapshot(),
) {
    private val lock = Any()
    private var current = initial

    fun snapshot(): PermissionSessionSnapshot = synchronized(lock) { current }

    fun isAuthenticatedFor(actorId: String?): Boolean = synchronized(lock) {
        current.authentication == AuthenticationState.ACTIVE &&
            current.actorId == actorId?.trim()?.takeIf(String::isNotEmpty)
    }

    fun rememberActor(actorId: String): PermissionSessionSnapshot = update {
        val owner = normalizedActor(actorId)
        it.forActor(owner).copy(
            actorId = owner,
            authentication = AuthenticationState.REAUTHENTICATION_REQUIRED,
        )
    }

    fun authenticated(actorId: String): PermissionSessionSnapshot = update {
        val owner = normalizedActor(actorId)
        it.forActor(owner).copy(
            actorId = owner,
            authentication = AuthenticationState.ACTIVE,
        )
    }

    fun explicitLogout(): PermissionSessionSnapshot = update {
        PermissionSessionSnapshot(mobileNetworkPreference = it.mobileNetworkPreference)
    }

    fun authenticationExpired(): PermissionSessionSnapshot = update {
        if (it.actorId == null) {
            it
        } else {
            it.copy(authentication = AuthenticationState.REAUTHENTICATION_REQUIRED)
        }
    }

    fun setRawCollectionConsent(granted: Boolean): PermissionSessionSnapshot = update {
        it.copy(rawCollectionConsentGranted = granted)
    }

    fun setAutomaticReportConsent(granted: Boolean): PermissionSessionSnapshot = update {
        it.copy(automaticReportConsentGranted = granted)
    }

    fun setMobileNetworkPreference(
        preference: MobileNetworkPreference,
    ): PermissionSessionSnapshot = update {
        it.copy(mobileNetworkPreference = preference)
    }

    fun setTrainingReuseConsent(granted: Boolean): PermissionSessionSnapshot = update {
        it.copy(trainingReuseConsentGranted = granted)
    }

    fun applyIntegratedConsentSelections(
        selections: IntegratedConsentSelections,
    ): PermissionSessionSnapshot = update {
        it.copy(
            rawCollectionConsentGranted = selections.rawSourceCollection,
            automaticReportConsentGranted = selections.automaticReporting,
            mobileNetworkPreference = if (selections.mobileNetworkTransfer) {
                MobileNetworkPreference.ALLOW_CELLULAR
            } else {
                MobileNetworkPreference.WIFI_ONLY
            },
            trainingReuseConsentGranted = selections.trainingReuse,
        )
    }

    private fun update(
        transition: (PermissionSessionSnapshot) -> PermissionSessionSnapshot,
    ): PermissionSessionSnapshot = synchronized(lock) {
        transition(current).also { current = it }
    }

    private fun normalizedActor(actorId: String): String =
        actorId.trim().also { require(it.isNotEmpty()) { "actorId must not be blank" } }

    private fun PermissionSessionSnapshot.forActor(actorId: String): PermissionSessionSnapshot =
        if (this.actorId == actorId) this else PermissionSessionSnapshot(
            mobileNetworkPreference = mobileNetworkPreference,
        )
}

enum class ObservedPermission {
    CAMERA,
    PRECISE_LOCATION,
    MICROPHONE,
    ACTIVITY_RECOGNITION,
}

data class ObservedPermissionSnapshot(
    val cameraGranted: Boolean,
    val preciseLocationGranted: Boolean,
    val microphoneGranted: Boolean,
    val activityRecognitionGranted: Boolean,
) {
    fun isGranted(permission: ObservedPermission): Boolean = when (permission) {
        ObservedPermission.CAMERA -> cameraGranted
        ObservedPermission.PRECISE_LOCATION -> preciseLocationGranted
        ObservedPermission.MICROPHONE -> microphoneGranted
        ObservedPermission.ACTIVITY_RECOGNITION -> activityRecognitionGranted
    }
}

object WalkStartPermissionPolicy {
    fun required(activityRecognitionRequired: Boolean): Set<ObservedPermission> = buildSet {
        add(ObservedPermission.CAMERA)
        add(ObservedPermission.PRECISE_LOCATION)
        add(ObservedPermission.MICROPHONE)
        if (activityRecognitionRequired) add(ObservedPermission.ACTIVITY_RECOGNITION)
    }

    fun missing(
        observed: ObservedPermissionSnapshot,
        activityRecognitionRequired: Boolean,
    ): Set<ObservedPermission> =
        required(activityRecognitionRequired).filterNot(observed::isGranted).toSet()
}

enum class PermissionRecoveryGateState {
    CLEAR,
    REQUEST_PENDING,
    BLOCKED,
    SETTINGS_PENDING,
    RECHECK_REQUIRED,
    AWAITING_EXPLICIT_RESUME,
}

/** Identity of the full recheck whose result the user is being asked to acknowledge. */
data class PermissionRecoveryBinding(
    val actorBindingSha256: String,
    val gatewaySessionGeneration: Long,
    val walkEpoch: WalkRuntimeEpoch,
    val recheckRequestId: String,
) {
    init {
        require(actorBindingSha256.matches(Regex("[0-9a-f]{64}"))) {
            "actorBindingSha256 must be a SHA-256 digest"
        }
        require(gatewaySessionGeneration >= 0L) { "gatewaySessionGeneration must not be negative" }
        require(recheckRequestId.isNotBlank()) { "recheckRequestId must not be blank" }
    }
}

data class PermissionRecoveryGate(
    val state: PermissionRecoveryGateState = PermissionRecoveryGateState.CLEAR,
    val affectedPermissions: Set<ObservedPermission> = emptySet(),
    val recheckBinding: PermissionRecoveryBinding? = null,
) {
    val blocksAutomaticResourceStart: Boolean
        get() = state != PermissionRecoveryGateState.CLEAR

    /** Main navigation can recover without certifying walking readiness or clearing this gate. */
    fun blocksInteraction(
        inWalkArea: Boolean,
        observed: ObservedPermissionSnapshot,
        requiredPermissions: Set<ObservedPermission>,
    ): Boolean = blocksAutomaticResourceStart &&
        (inWalkArea || PermissionDependencyPolicy.missing(requiredPermissions, observed).isNotEmpty())

    fun requestPending(
        requestedPermissions: Set<ObservedPermission>,
        binding: PermissionRecoveryBinding? = recheckBinding,
    ): PermissionRecoveryGate = copy(
        state = PermissionRecoveryGateState.REQUEST_PENDING,
        affectedPermissions = requestedPermissions,
        recheckBinding = binding,
    )

    fun blocked(
        missingPermissions: Set<ObservedPermission>,
        binding: PermissionRecoveryBinding? = recheckBinding,
    ): PermissionRecoveryGate = copy(
        state = PermissionRecoveryGateState.BLOCKED,
        affectedPermissions = missingPermissions,
        recheckBinding = binding,
    )

    fun settingsPending(): PermissionRecoveryGate = copy(
        state = PermissionRecoveryGateState.SETTINGS_PENDING,
    )

    fun recheckRequired(
        binding: PermissionRecoveryBinding? = recheckBinding,
    ): PermissionRecoveryGate = copy(
        state = PermissionRecoveryGateState.RECHECK_REQUIRED,
        recheckBinding = binding,
    )

    fun completeFullRecheck(
        observed: ObservedPermissionSnapshot,
        requiredPermissions: Set<ObservedPermission>,
        allPrerequisitesReady: Boolean,
        resultBinding: PermissionRecoveryBinding? = null,
    ): PermissionRecoveryGate {
        // A late or unscoped result must not replace the request this gate currently owns.
        if (recheckBinding != resultBinding) return recheckRequired()
        val missing = PermissionDependencyPolicy.missing(requiredPermissions, observed)
        return when {
            missing.isNotEmpty() -> blocked(missing)
            allPrerequisitesReady -> PermissionRecoveryGate(
                state = PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME,
                recheckBinding = resultBinding,
            )
            else -> copy(
                state = PermissionRecoveryGateState.RECHECK_REQUIRED,
                affectedPermissions = emptySet(),
            )
        }
    }

    fun acknowledgeExplicitResume(): PermissionRecoveryGate =
        if (state == PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME) {
            if (recheckBinding != null) recheckRequired() else PermissionRecoveryGate()
        } else {
            this
        }

    /**
     * Accepts the user's explicit resume only against a permission observation taken at that
     * moment. An awaiting state computed earlier never releases resources when a required
     * permission is missing now; it returns to the blocked recovery state instead. A bound
     * recheck additionally requires the same account, session, walk and request identity.
     */
    fun acknowledgeExplicitResume(
        observed: ObservedPermissionSnapshot,
        requiredPermissions: Set<ObservedPermission>,
        currentBinding: PermissionRecoveryBinding? = null,
    ): PermissionRecoveryGate {
        if (state != PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME) return this
        if (recheckBinding != currentBinding) return recheckRequired()
        val missing = PermissionDependencyPolicy.missing(requiredPermissions, observed)
        return if (missing.isNotEmpty()) blocked(missing) else PermissionRecoveryGate()
    }

    companion object {
        fun restoredBlocked(
            affectedPermissions: Set<ObservedPermission>,
        ): PermissionRecoveryGate = PermissionRecoveryGate(
            state = PermissionRecoveryGateState.BLOCKED,
            affectedPermissions = affectedPermissions,
        )
    }
}

enum class PermissionDependentFeature {
    CAMERA_HAZARD_GUIDANCE,
    METRIC_DISTANCE,
    ROUTE_NAVIGATION,
    VOICE_COMMAND,
    STEP_TRACKING,
    REPORT_TRANSMISSION,
}

data class PermissionDependencyDecision(
    val stoppedFeatures: Set<PermissionDependentFeature>,
    val requiresWholeWalkSafetyStop: Boolean,
)

object PermissionDependencyPolicy {
    fun missing(
        required: Set<ObservedPermission>,
        observed: ObservedPermissionSnapshot,
    ): Set<ObservedPermission> = required.filterNot(observed::isGranted).toSet()

    fun evaluate(observed: ObservedPermissionSnapshot): PermissionDependencyDecision {
        val stopped = buildSet {
            if (!observed.cameraGranted) {
                add(PermissionDependentFeature.CAMERA_HAZARD_GUIDANCE)
                add(PermissionDependentFeature.METRIC_DISTANCE)
                add(PermissionDependentFeature.REPORT_TRANSMISSION)
            }
            if (!observed.preciseLocationGranted) {
                add(PermissionDependentFeature.ROUTE_NAVIGATION)
                add(PermissionDependentFeature.REPORT_TRANSMISSION)
            }
            if (!observed.microphoneGranted) {
                add(PermissionDependentFeature.VOICE_COMMAND)
            }
            if (!observed.activityRecognitionGranted) {
                add(PermissionDependentFeature.STEP_TRACKING)
            }
        }
        return PermissionDependencyDecision(
            stoppedFeatures = stopped,
            requiresWholeWalkSafetyStop =
                !observed.cameraGranted && !observed.preciseLocationGranted,
        )
    }
}
