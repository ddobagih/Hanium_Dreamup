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
    // 시작·재개 진입 목적일 때만 기존 WalkStartPermissionPolicy로 확인한다.
    // 실행 중 권한 회수는 기존 기능별 처리로 유지한다.
    PERMISSIONS,
    CAMERA,
    LOCATION,
    METRIC_DISTANCE,
    MODEL,
    // 음성 입력 권한도 별도 시작·재개 검사와 실행 중 기능 제한으로 소비한다.
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
    val checkEntryPermissions: Boolean = false,
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
        if (checkEntryPermissions) add(WalkSessionReadinessRequirement.PERMISSIONS)
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

/** Original evidence used by a readiness producer; reassessment never extends its lifetime. */
data class WalkSessionReadinessEvidence(
    val source: String,
    val observedAtElapsedRealtimeMs: Long,
    val maxAgeMs: Long,
    val sourceVersion: String,
    val requiredVersion: String = sourceVersion,
) {
    init {
        require(source.isNotBlank()) { "Evidence source must not be blank" }
        require(observedAtElapsedRealtimeMs >= 0L) { "Evidence time must not be negative" }
        require(maxAgeMs >= 0L) { "Evidence validity must not be negative" }
        require(sourceVersion.isNotBlank() && requiredVersion.isNotBlank()) {
            "Evidence versions must not be blank"
        }
    }

    fun isStaleAt(nowElapsedRealtimeMs: Long): Boolean =
        sourceVersion != requiredVersion ||
            nowElapsedRealtimeMs < observedAtElapsedRealtimeMs ||
            nowElapsedRealtimeMs - observedAtElapsedRealtimeMs > maxAgeMs
}

data class WalkSessionReadinessObservation(
    val epoch: WalkRuntimeEpoch,
    val requirement: WalkSessionReadinessRequirement,
    val status: WalkSessionReadinessStatus,
    val reason: String = "",
    /**
     * S03-9/S14-12: monotonic time (the snapshot clock) at which a changing condition was measured
     * and how long that measurement stays valid. Null remains supported for static records and
     * callers that read a value at collection time. Producers retaining earlier measurements
     * preserve those measurements separately in [supportingEvidence].
     */
    val observedAtElapsedRealtimeMs: Long? = null,
    val maxAgeMs: Long? = null,
    val evidenceVersion: String? = null,
    val supportingEvidence: List<WalkSessionReadinessEvidence> = emptyList(),
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
        require((observedAtElapsedRealtimeMs == null) == (maxAgeMs == null)) {
            "A measured observation needs both its measurement time and its validity"
        }
        require(observedAtElapsedRealtimeMs == null || observedAtElapsedRealtimeMs >= 0L) {
            "observedAtElapsedRealtimeMs must not be negative"
        }
        require(maxAgeMs == null || maxAgeMs > 0L) { "maxAgeMs must be positive" }
        require(evidenceVersion == null || evidenceVersion.isNotBlank()) {
            "evidenceVersion must not be blank"
        }
    }

    /** A measurement taken after the assessment or older than its own validity is stale. */
    fun isStaleAt(assessedAtElapsedRealtimeMs: Long): Boolean {
        if (supportingEvidence.any { it.isStaleAt(assessedAtElapsedRealtimeMs) }) return true
        val observedAt = observedAtElapsedRealtimeMs ?: return false
        val maxAge = maxAgeMs ?: return true
        return observedAt > assessedAtElapsedRealtimeMs ||
            assessedAtElapsedRealtimeMs - observedAt > maxAge
    }
}

data class WalkSessionReadinessSnapshot(
    val epoch: WalkRuntimeEpoch,
    val plan: WalkSessionReadinessPlan,
    /**
     * Monotonic assessment time (Android `SystemClock.elapsedRealtime()`), used only for readiness
     * age checks. It is not a wall-clock timestamp and must not be compared with epoch times.
     */
    val assessedAtElapsedRealtimeMs: Long,
    val observations: Map<WalkSessionReadinessRequirement, WalkSessionReadinessObservation>,
) {
    init {
        require(assessedAtElapsedRealtimeMs >= 0) {
            "assessedAtElapsedRealtimeMs must not be negative"
        }
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

    /** Required READY evidence that is no longer current at [assessedAtElapsedRealtimeMs]. */
    val staleRequirements: Set<WalkSessionReadinessRequirement>
        get() = staleRequirementsAt(assessedAtElapsedRealtimeMs)

    fun staleRequirementsAt(nowElapsedRealtimeMs: Long): Set<WalkSessionReadinessRequirement> =
        plan.requiredRequirements.filterTo(linkedSetOf()) { requirement ->
            val observation = observations.getValue(requirement)
            observation.status == WalkSessionReadinessStatus.READY &&
                observation.isStaleAt(nowElapsedRealtimeMs)
        }

    val isReady: Boolean
        get() = isReadyAt(assessedAtElapsedRealtimeMs)

    fun isReadyAt(nowElapsedRealtimeMs: Long): Boolean =
        nowElapsedRealtimeMs >= assessedAtElapsedRealtimeMs &&
            plan.requiredRequirements.all { requirement ->
                observations.getValue(requirement).status == WalkSessionReadinessStatus.READY
            } && staleRequirementsAt(nowElapsedRealtimeMs).isEmpty()

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
        } + staleRequirements.map { "stale_observation:${it.name.lowercase()}" }
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

    fun build(assessedAtElapsedRealtimeMs: Long): WalkSessionReadinessSnapshot {
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
            assessedAtElapsedRealtimeMs = assessedAtElapsedRealtimeMs,
            observations = observations.toMap(),
        )
    }
}
