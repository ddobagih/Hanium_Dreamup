package kr.co.hanium.dreamup.walksafe.session

data class WalkRuntimeEpoch(
    val walkSessionId: String,
    val recoveryGeneration: Long,
) {
    init {
        require(walkSessionId.isNotBlank()) { "walkSessionId must not be blank" }
        require(recoveryGeneration >= 0) { "recoveryGeneration must not be negative" }
    }
}

enum class WalkSessionAction {
    START_WALK,
    RESUME_WALK,
    DESTINATION_SEARCH,
    ROUTE_REQUEST,
    REPORT_TRANSMISSION,
}

enum class WalkSessionReadinessRequirement {
    FIRST_RUN_ONBOARDING,
    PRIORITY_USER_ONBOARDING,
    OFFICIAL_ENVIRONMENT,
    PHONE_MOUNTING,
    PERMISSIONS,
    CAMERA,
    LOCATION,
    METRIC_DISTANCE,
    MODEL,
    VOICE_INPUT,
    VOICE_OUTPUT,
    ROUTE_SERVICE,
    GATEWAY,
    DEVICE_RESOURCES,
}

enum class WalkSessionReadinessStatus {
    READY,
    PENDING,
    UNAVAILABLE,
    NOT_REQUIRED,
}

data class WalkSessionReadinessPlan(
    val action: WalkSessionAction,
    val mode: WalkSessionMode,
) {
    val requiredRequirements: Set<WalkSessionReadinessRequirement> = buildSet {
        add(WalkSessionReadinessRequirement.FIRST_RUN_ONBOARDING)
        when (action) {
            WalkSessionAction.START_WALK -> {
                addWalkRuntimeRequirements()
                add(WalkSessionReadinessRequirement.VOICE_OUTPUT)
            }

            WalkSessionAction.RESUME_WALK -> {
                addWalkRuntimeRequirements()
                add(WalkSessionReadinessRequirement.VOICE_OUTPUT)
            }

            WalkSessionAction.DESTINATION_SEARCH -> {
                add(WalkSessionReadinessRequirement.ROUTE_SERVICE)
                add(WalkSessionReadinessRequirement.GATEWAY)
            }

            WalkSessionAction.ROUTE_REQUEST -> {
                add(WalkSessionReadinessRequirement.LOCATION)
                add(WalkSessionReadinessRequirement.VOICE_OUTPUT)
                add(WalkSessionReadinessRequirement.ROUTE_SERVICE)
                add(WalkSessionReadinessRequirement.GATEWAY)
                add(WalkSessionReadinessRequirement.DEVICE_RESOURCES)
            }

            WalkSessionAction.REPORT_TRANSMISSION -> {
                add(WalkSessionReadinessRequirement.LOCATION)
                add(WalkSessionReadinessRequirement.GATEWAY)
                add(WalkSessionReadinessRequirement.DEVICE_RESOURCES)
            }
        }
    }

    private fun MutableSet<WalkSessionReadinessRequirement>.addWalkRuntimeRequirements() {
        add(WalkSessionReadinessRequirement.PRIORITY_USER_ONBOARDING)
        add(WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT)
        add(WalkSessionReadinessRequirement.PHONE_MOUNTING)
        add(WalkSessionReadinessRequirement.CAMERA)
        add(WalkSessionReadinessRequirement.LOCATION)
        add(WalkSessionReadinessRequirement.MODEL)
        add(WalkSessionReadinessRequirement.GATEWAY)
        add(WalkSessionReadinessRequirement.DEVICE_RESOURCES)
        if (mode != WalkSessionMode.DISTANCE_LIMITED) {
            add(WalkSessionReadinessRequirement.METRIC_DISTANCE)
        }
    }

    fun requires(requirement: WalkSessionReadinessRequirement): Boolean =
        requirement in requiredRequirements
}

data class WalkSessionReadinessObservation(
    val epoch: WalkRuntimeEpoch,
    val requirement: WalkSessionReadinessRequirement,
    val status: WalkSessionReadinessStatus,
    val reason: String = "",
) {
    init {
        require(
            status !in setOf(
                WalkSessionReadinessStatus.PENDING,
                WalkSessionReadinessStatus.UNAVAILABLE,
            ) || reason.isNotBlank(),
        ) {
            "A pending or unavailable observation must explain its reason"
        }
    }
}

data class WalkSessionReadinessSnapshot(
    val epoch: WalkRuntimeEpoch,
    val plan: WalkSessionReadinessPlan,
    val assessedAtEpochMs: Long,
    val observations: Map<WalkSessionReadinessRequirement, WalkSessionReadinessObservation>,
) {
    init {
        require(assessedAtEpochMs >= 0) { "assessedAtEpochMs must not be negative" }
        require(observations.keys == WalkSessionReadinessRequirement.entries.toSet()) {
            "A readiness snapshot must include every requirement"
        }
        observations.forEach { (requirement, observation) ->
            require(observation.epoch == epoch) {
                "Every readiness observation must use the snapshot epoch"
            }
            require(observation.requirement == requirement) {
                "Readiness observation key must match its requirement"
            }
            require(
                plan.requires(requirement) ==
                    (observation.status != WalkSessionReadinessStatus.NOT_REQUIRED),
            ) {
                "Readiness status must match the action requirement plan"
            }
        }
    }

    val isReady: Boolean
        get() = plan.requiredRequirements.all { requirement ->
            observations.getValue(requirement).status == WalkSessionReadinessStatus.READY
        }

    val hasUnavailableRequirement: Boolean
        get() = plan.requiredRequirements.any { requirement ->
            observations.getValue(requirement).status == WalkSessionReadinessStatus.UNAVAILABLE
        }

    val blockingReasons: List<String>
        get() = plan.requiredRequirements.mapNotNull { requirement ->
            observations.getValue(requirement)
                .takeIf {
                    it.status == WalkSessionReadinessStatus.PENDING ||
                        it.status == WalkSessionReadinessStatus.UNAVAILABLE
                }
                ?.reason
                ?.takeIf(String::isNotBlank)
        }
}

data class WalkSessionConfirmationToken(
    val readiness: WalkSessionReadinessSnapshot,
) {
    val epoch: WalkRuntimeEpoch
        get() = readiness.epoch
}

class WalkSessionReadinessCollector(
    val epoch: WalkRuntimeEpoch,
    val plan: WalkSessionReadinessPlan,
) {
    private val observations =
        linkedMapOf<WalkSessionReadinessRequirement, WalkSessionReadinessObservation>()

    fun record(observation: WalkSessionReadinessObservation): WalkSessionReadinessCollector {
        require(observation.epoch == epoch) {
            "Readiness observations from different walk epochs cannot be combined"
        }
        require(
            plan.requires(observation.requirement) ==
                (observation.status != WalkSessionReadinessStatus.NOT_REQUIRED),
        ) {
            "Readiness observation does not match the action requirement plan"
        }
        require(observations.putIfAbsent(observation.requirement, observation) == null) {
            "A readiness requirement can be recorded only once"
        }
        return this
    }

    fun build(assessedAtEpochMs: Long): WalkSessionReadinessSnapshot {
        val missing = plan.requiredRequirements - observations.keys
        check(missing.isEmpty()) {
            "Missing required readiness observations: ${missing.joinToString()}"
        }
        WalkSessionReadinessRequirement.entries
            .filterNot(observations::containsKey)
            .forEach { requirement ->
                observations[requirement] = WalkSessionReadinessObservation(
                    epoch = epoch,
                    requirement = requirement,
                    status = WalkSessionReadinessStatus.NOT_REQUIRED,
                )
            }
        return WalkSessionReadinessSnapshot(
            epoch = epoch,
            plan = plan,
            assessedAtEpochMs = assessedAtEpochMs,
            observations = observations.toMap(),
        )
    }
}
