package kr.co.hanium.dreamup.walksafe.device

enum class PostLoginDeviceCheckState {
    NOT_RUN,
    REQUESTING_PERMISSIONS,
    RUNNING,
    FULL,
    LIMITED,
    FAIL,
}

enum class PostLoginDeviceCheckSignal {
    PENDING,
    READY,
    UNAVAILABLE,
}

enum class PostLoginMetricDepthState {
    PENDING,
    AVAILABLE,
    EXPLICITLY_UNSUPPORTED,
    UNKNOWN,
    TIMED_OUT,
}

enum class PostLoginDeviceCheckFailure {
    BACKGROUNDED,
    MINIMUM_ANDROID_VERSION,
    REQUIRED_PERMISSION,
    CORE_HARDWARE_OR_SERVICE,
    LOCATION_SERVICE_DISABLED,
    DEVICE_RESOURCE,
    DETECTOR_UNAVAILABLE,
    CAMERA_FALLBACK_UNAVAILABLE,
    DEPTH_UNKNOWN,
    DEPTH_TIMEOUT,
    SESSION_CHANGED,
}

data class PostLoginDeviceCheckBinding(
    val actorId: String,
    val sessionGeneration: Long,
    val attemptGeneration: Long,
)

data class PostLoginDeviceCheckObservation(
    val minimumAndroidVersion: PostLoginDeviceCheckSignal,
    val requiredPermissions: PostLoginDeviceCheckSignal,
    val coreHardwareAndServices: PostLoginDeviceCheckSignal,
    val locationService: PostLoginDeviceCheckSignal,
    val deviceResources: PostLoginDeviceCheckSignal,
    val detector: PostLoginDeviceCheckSignal,
    val metricDepth: PostLoginMetricDepthState,
    val cameraFallback: PostLoginDeviceCheckSignal,
)

data class PostLoginDeviceCheckSnapshot(
    val state: PostLoginDeviceCheckState,
    val actorId: String?,
    val sessionGeneration: Long?,
    val attemptGeneration: Long,
    val failure: PostLoginDeviceCheckFailure? = null,
) {
    val passesFeatureGate: Boolean
        get() = state == PostLoginDeviceCheckState.FULL ||
            state == PostLoginDeviceCheckState.LIMITED

    val bindingOrNull: PostLoginDeviceCheckBinding?
        get() {
            val actor = actorId ?: return null
            val session = sessionGeneration ?: return null
            if (attemptGeneration <= 0L) return null
            return PostLoginDeviceCheckBinding(actor, session, attemptGeneration)
        }
}

object PostLoginDeviceCheckPolicy {
    fun initial(): PostLoginDeviceCheckSnapshot = PostLoginDeviceCheckSnapshot(
        state = PostLoginDeviceCheckState.NOT_RUN,
        actorId = null,
        sessionGeneration = null,
        attemptGeneration = 0L,
    )

    fun bindSession(
        snapshot: PostLoginDeviceCheckSnapshot,
        actorId: String?,
        sessionGeneration: Long?,
    ): PostLoginDeviceCheckSnapshot {
        if (
            actorId != null &&
            sessionGeneration != null &&
            snapshot.actorId == actorId &&
            snapshot.sessionGeneration == sessionGeneration
        ) {
            return snapshot
        }
        return PostLoginDeviceCheckSnapshot(
            state = PostLoginDeviceCheckState.NOT_RUN,
            actorId = actorId,
            sessionGeneration = sessionGeneration,
            attemptGeneration = snapshot.attemptGeneration,
        )
    }

    /** This is the only transition that starts an attempt and must be called from a user action. */
    fun beginFromUserAction(
        snapshot: PostLoginDeviceCheckSnapshot,
        actorId: String,
        sessionGeneration: Long,
        foreground: Boolean,
    ): PostLoginDeviceCheckSnapshot {
        if (!foreground) {
            return fail(snapshot, PostLoginDeviceCheckFailure.BACKGROUNDED)
        }
        if (
            actorId.isBlank() ||
            snapshot.actorId != actorId ||
            snapshot.sessionGeneration != sessionGeneration ||
            snapshot.attemptGeneration == Long.MAX_VALUE
        ) {
            return snapshot.copy(
                state = PostLoginDeviceCheckState.FAIL,
                failure = PostLoginDeviceCheckFailure.SESSION_CHANGED,
            )
        }
        return snapshot.copy(
            state = PostLoginDeviceCheckState.REQUESTING_PERMISSIONS,
            attemptGeneration = snapshot.attemptGeneration + 1L,
            failure = null,
        )
    }

    fun beginRunning(
        snapshot: PostLoginDeviceCheckSnapshot,
        binding: PostLoginDeviceCheckBinding,
        foreground: Boolean,
    ): PostLoginDeviceCheckSnapshot {
        if (!isCurrent(snapshot, binding)) return snapshot
        if (!foreground) return fail(snapshot, PostLoginDeviceCheckFailure.BACKGROUNDED)
        if (snapshot.state != PostLoginDeviceCheckState.REQUESTING_PERMISSIONS) return snapshot
        return snapshot.copy(state = PostLoginDeviceCheckState.RUNNING, failure = null)
    }

    fun evaluate(
        snapshot: PostLoginDeviceCheckSnapshot,
        binding: PostLoginDeviceCheckBinding,
        foreground: Boolean,
        observation: PostLoginDeviceCheckObservation,
    ): PostLoginDeviceCheckSnapshot {
        if (!isCurrent(snapshot, binding)) return snapshot
        if (!foreground) return fail(snapshot, PostLoginDeviceCheckFailure.BACKGROUNDED)
        if (snapshot.state != PostLoginDeviceCheckState.RUNNING) return snapshot

        firstUnavailable(observation)?.let { return fail(snapshot, it) }
        when (observation.metricDepth) {
            PostLoginMetricDepthState.UNKNOWN ->
                return fail(snapshot, PostLoginDeviceCheckFailure.DEPTH_UNKNOWN)
            PostLoginMetricDepthState.TIMED_OUT ->
                return fail(snapshot, PostLoginDeviceCheckFailure.DEPTH_TIMEOUT)
            else -> Unit
        }
        if (hasPendingCoreSignal(observation)) return snapshot

        return when (observation.metricDepth) {
            PostLoginMetricDepthState.PENDING -> snapshot
            PostLoginMetricDepthState.AVAILABLE ->
                snapshot.copy(state = PostLoginDeviceCheckState.FULL, failure = null)
            PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED -> when (observation.cameraFallback) {
                PostLoginDeviceCheckSignal.PENDING -> snapshot
                PostLoginDeviceCheckSignal.READY ->
                    snapshot.copy(state = PostLoginDeviceCheckState.LIMITED, failure = null)
                PostLoginDeviceCheckSignal.UNAVAILABLE ->
                    fail(snapshot, PostLoginDeviceCheckFailure.CAMERA_FALLBACK_UNAVAILABLE)
            }
            PostLoginMetricDepthState.UNKNOWN,
            PostLoginMetricDepthState.TIMED_OUT,
            -> error("Terminal depth states were handled before pending core signals")
        }
    }

    fun onBackground(
        snapshot: PostLoginDeviceCheckSnapshot,
        binding: PostLoginDeviceCheckBinding?,
        ownsPermissionDialog: Boolean,
    ): PostLoginDeviceCheckSnapshot {
        if (binding == null || !isCurrent(snapshot, binding)) return snapshot
        if (
            snapshot.state == PostLoginDeviceCheckState.REQUESTING_PERMISSIONS &&
            ownsPermissionDialog
        ) {
            return snapshot
        }
        if (
            snapshot.state == PostLoginDeviceCheckState.NOT_RUN ||
            snapshot.state == PostLoginDeviceCheckState.FAIL
        ) return snapshot
        return fail(snapshot, PostLoginDeviceCheckFailure.BACKGROUNDED)
    }

    fun isCurrent(
        snapshot: PostLoginDeviceCheckSnapshot,
        binding: PostLoginDeviceCheckBinding,
    ): Boolean =
        snapshot.actorId == binding.actorId &&
            snapshot.sessionGeneration == binding.sessionGeneration &&
            snapshot.attemptGeneration == binding.attemptGeneration

    private fun firstUnavailable(
        observation: PostLoginDeviceCheckObservation,
    ): PostLoginDeviceCheckFailure? = when {
        observation.minimumAndroidVersion == PostLoginDeviceCheckSignal.UNAVAILABLE ->
            PostLoginDeviceCheckFailure.MINIMUM_ANDROID_VERSION
        observation.requiredPermissions == PostLoginDeviceCheckSignal.UNAVAILABLE ->
            PostLoginDeviceCheckFailure.REQUIRED_PERMISSION
        observation.coreHardwareAndServices == PostLoginDeviceCheckSignal.UNAVAILABLE ->
            PostLoginDeviceCheckFailure.CORE_HARDWARE_OR_SERVICE
        observation.locationService == PostLoginDeviceCheckSignal.UNAVAILABLE ->
            PostLoginDeviceCheckFailure.LOCATION_SERVICE_DISABLED
        observation.deviceResources == PostLoginDeviceCheckSignal.UNAVAILABLE ->
            PostLoginDeviceCheckFailure.DEVICE_RESOURCE
        observation.detector == PostLoginDeviceCheckSignal.UNAVAILABLE ->
            PostLoginDeviceCheckFailure.DETECTOR_UNAVAILABLE
        else -> null
    }

    private fun hasPendingCoreSignal(
        observation: PostLoginDeviceCheckObservation,
    ): Boolean = listOf(
        observation.minimumAndroidVersion,
        observation.requiredPermissions,
        observation.coreHardwareAndServices,
        observation.locationService,
        observation.deviceResources,
        observation.detector,
    ).any { it == PostLoginDeviceCheckSignal.PENDING }

    private fun fail(
        snapshot: PostLoginDeviceCheckSnapshot,
        failure: PostLoginDeviceCheckFailure,
    ): PostLoginDeviceCheckSnapshot = snapshot.copy(
        state = PostLoginDeviceCheckState.FAIL,
        failure = failure,
    )
}
