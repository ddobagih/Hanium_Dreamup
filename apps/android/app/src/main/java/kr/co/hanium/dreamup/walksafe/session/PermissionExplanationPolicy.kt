package kr.co.hanium.dreamup.walksafe.session

/** UI-only permission identities; feature admission remains in PermissionSessionPolicy. */
enum class PermissionExplanationKind {
    CAMERA,
    PRECISE_LOCATION,
    APPROXIMATE_LOCATION,
    MICROPHONE,
    ACTIVITY_RECOGNITION,
}

data class PermissionRequestAnswer(
    val permission: PermissionExplanationKind,
    val granted: Boolean,
    val rationaleRecommended: Boolean,
)

enum class PermissionLocationState {
    NOT_APPLICABLE,
    APPROXIMATE_ONLY,
    NO_LOCATION,
}

enum class PermissionReRequestability {
    NOT_EVALUATED,
    RATIONALE_RECOMMENDED,
    NO_RATIONALE_SIGNAL,
}

data class PermissionExplanation(
    val unavailablePermissions: Set<PermissionExplanationKind>,
    val locationState: PermissionLocationState,
    val reRequestabilityByPermission: Map<PermissionExplanationKind, PermissionReRequestability>,
    val requestResultReceived: Boolean,
)

object PermissionExplanationPolicy {
    fun withoutResult(
        missingPermissions: Set<PermissionExplanationKind>,
        coarseLocationCurrentlyGranted: Boolean,
    ): PermissionExplanation = PermissionExplanation(
        unavailablePermissions = missingPermissions,
        locationState = locationState(missingPermissions, coarseLocationCurrentlyGranted),
        reRequestabilityByPermission = missingPermissions.associateWith {
            PermissionReRequestability.NOT_EVALUATED
        },
        requestResultReceived = false,
    )

    fun afterRequest(
        answers: List<PermissionRequestAnswer>,
        coarseLocationCurrentlyGranted: Boolean,
    ): PermissionExplanation? {
        if (answers.isEmpty()) return null
        val byPermission = answers.associateBy(PermissionRequestAnswer::permission)
        val fineGranted = byPermission[PermissionExplanationKind.PRECISE_LOCATION]?.granted == true
        val coarseGranted =
            byPermission[PermissionExplanationKind.APPROXIMATE_LOCATION]?.granted == true ||
                coarseLocationCurrentlyGranted
        val unavailable = byPermission.values
            .filterNot(PermissionRequestAnswer::granted)
            .map(PermissionRequestAnswer::permission)
            .toMutableSet()
        if (fineGranted || coarseGranted) {
            unavailable.remove(PermissionExplanationKind.APPROXIMATE_LOCATION)
        }
        if (unavailable.isEmpty()) return null
        return PermissionExplanation(
            unavailablePermissions = unavailable,
            locationState = locationState(unavailable, coarseGranted),
            reRequestabilityByPermission = unavailable.associateWith { permission ->
                if (byPermission[permission]?.rationaleRecommended == true) {
                    PermissionReRequestability.RATIONALE_RECOMMENDED
                } else {
                    PermissionReRequestability.NO_RATIONALE_SIGNAL
                }
            },
            requestResultReceived = true,
        )
    }

    private fun locationState(
        unavailable: Set<PermissionExplanationKind>,
        coarseGranted: Boolean,
    ): PermissionLocationState = when {
        PermissionExplanationKind.PRECISE_LOCATION in unavailable && coarseGranted ->
            PermissionLocationState.APPROXIMATE_ONLY
        PermissionExplanationKind.PRECISE_LOCATION in unavailable ||
            PermissionExplanationKind.APPROXIMATE_LOCATION in unavailable ->
            PermissionLocationState.NO_LOCATION
        else -> PermissionLocationState.NOT_APPLICABLE
    }
}
