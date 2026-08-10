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
        get() = rawCollectionConsentGranted && automaticReportConsentGranted

    val mayReuseForTraining: Boolean
        get() = rawCollectionConsentGranted && trainingReuseConsentGranted
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
        it.copy(
            actorId = normalizedActor(actorId),
            authentication = AuthenticationState.REAUTHENTICATION_REQUIRED,
        )
    }

    fun authenticated(actorId: String): PermissionSessionSnapshot = update {
        it.copy(
            actorId = normalizedActor(actorId),
            authentication = AuthenticationState.ACTIVE,
        )
    }

    fun explicitLogout(): PermissionSessionSnapshot = update {
        it.copy(actorId = null, authentication = AuthenticationState.SIGNED_OUT)
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

data class PermissionRecoveryGate(
    val state: PermissionRecoveryGateState = PermissionRecoveryGateState.CLEAR,
    val affectedPermissions: Set<ObservedPermission> = emptySet(),
) {
    val blocksAutomaticResourceStart: Boolean
        get() = state != PermissionRecoveryGateState.CLEAR

    fun requestPending(
        requestedPermissions: Set<ObservedPermission>,
    ): PermissionRecoveryGate = copy(
        state = PermissionRecoveryGateState.REQUEST_PENDING,
        affectedPermissions = requestedPermissions,
    )

    fun blocked(
        missingPermissions: Set<ObservedPermission>,
    ): PermissionRecoveryGate = copy(
        state = PermissionRecoveryGateState.BLOCKED,
        affectedPermissions = missingPermissions,
    )

    fun settingsPending(): PermissionRecoveryGate = copy(
        state = PermissionRecoveryGateState.SETTINGS_PENDING,
    )

    fun recheckRequired(): PermissionRecoveryGate = copy(
        state = PermissionRecoveryGateState.RECHECK_REQUIRED,
    )

    fun completeFullRecheck(
        observed: ObservedPermissionSnapshot,
        activityRecognitionRequired: Boolean,
        allPrerequisitesReady: Boolean,
    ): PermissionRecoveryGate {
        val missing = WalkStartPermissionPolicy.missing(
            observed = observed,
            activityRecognitionRequired = activityRecognitionRequired,
        )
        return when {
            missing.isNotEmpty() -> blocked(missing)
            allPrerequisitesReady -> PermissionRecoveryGate(
                state = PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME,
            )
            else -> copy(
                state = PermissionRecoveryGateState.RECHECK_REQUIRED,
                affectedPermissions = emptySet(),
            )
        }
    }

    fun acknowledgeExplicitResume(): PermissionRecoveryGate =
        if (state == PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME) {
            PermissionRecoveryGate()
        } else {
            this
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
                add(PermissionDependentFeature.REPORT_TRANSMISSION)
            }
            if (!observed.preciseLocationGranted) {
                add(PermissionDependentFeature.METRIC_DISTANCE)
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
                !observed.cameraGranted || !observed.preciseLocationGranted,
        )
    }
}
