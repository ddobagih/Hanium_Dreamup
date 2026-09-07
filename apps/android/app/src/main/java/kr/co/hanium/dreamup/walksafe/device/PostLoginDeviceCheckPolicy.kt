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

enum class PostLoginDeviceCheckFeature {
    OBSTACLE_DETECTION,
    METRIC_DISTANCE_GUIDANCE,
    LOCATION_GUIDANCE,
    HANDS_FREE_VOICE,
    VOICE_GUIDANCE,
    HAPTIC_FEEDBACK,
}

enum class PostLoginMetricDepthState {
    PENDING,
    SUPPORTED,
    AVAILABLE,
    EXPLICITLY_UNSUPPORTED,
    UNKNOWN,
    TIMED_OUT,
}

enum class PostLoginDeviceCheckFailure {
    BACKGROUNDED,
    MINIMUM_ANDROID_VERSION,
    REQUIRED_PERMISSION,
    VOICE_DISCLOSURE_NOT_PERSISTED,
    CORE_HARDWARE_OR_SERVICE,
    LOCATION_SERVICE_DISABLED,
    LOCATION_FIX_UNAVAILABLE,
    DEVICE_RESOURCE,
    DETECTOR_UNAVAILABLE,
    CAMERA_PIPELINE_UNAVAILABLE,
    KOREAN_TTS_UNAVAILABLE,
    WAKE_PHRASE_UNAVAILABLE,
    HAPTIC_UNAVAILABLE,
    DEPTH_UNKNOWN,
    DEPTH_TIMEOUT,
    CHECK_TIMEOUT,
    RESULT_SAVE_FAILED,
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
    val voiceDisclosure: PostLoginDeviceCheckSignal,
    val coreHardwareAndServices: PostLoginDeviceCheckSignal,
    val locationService: PostLoginDeviceCheckSignal,
    val locationFix: PostLoginDeviceCheckSignal,
    val deviceResources: PostLoginDeviceCheckSignal,
    val detector: PostLoginDeviceCheckSignal,
    val cameraPipeline: PostLoginDeviceCheckSignal,
    val koreanTextToSpeech: PostLoginDeviceCheckSignal,
    val wakePhraseRecognition: PostLoginDeviceCheckSignal,
    // Kept for existing callers; vibration is no longer a device-check requirement.
    val hapticFeedback: PostLoginDeviceCheckSignal = PostLoginDeviceCheckSignal.PENDING,
    val metricDepth: PostLoginMetricDepthState,
    // The runtime adapter classifies raw signals before evaluation. Raw UNAVAILABLE values are
    // progress details and must not silently become a whole-app failure.
    val unsupportedFeatures: Set<PostLoginDeviceCheckFeature> = emptySet(),
    val blockingFailure: PostLoginDeviceCheckFailure? = null,
)

data class PostLoginDeviceCheckSnapshot(
    val state: PostLoginDeviceCheckState,
    val actorId: String?,
    val sessionGeneration: Long?,
    val attemptGeneration: Long,
    val failure: PostLoginDeviceCheckFailure? = null,
    val disabledFeatures: Set<PostLoginDeviceCheckFeature> = emptySet(),
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
        if (
            snapshot.state == PostLoginDeviceCheckState.REQUESTING_PERMISSIONS ||
            snapshot.state == PostLoginDeviceCheckState.RUNNING ||
            snapshot.state == PostLoginDeviceCheckState.FULL ||
            snapshot.state == PostLoginDeviceCheckState.LIMITED
        ) return snapshot
        if (!foreground) {
            return fail(snapshot, PostLoginDeviceCheckFailure.BACKGROUNDED)
        }
        if (
            actorId.isBlank() ||
            snapshot.actorId != actorId ||
            snapshot.sessionGeneration != sessionGeneration ||
            snapshot.attemptGeneration == Long.MAX_VALUE
        ) {
            return fail(snapshot, PostLoginDeviceCheckFailure.SESSION_CHANGED)
        }
        return snapshot.copy(
            state = PostLoginDeviceCheckState.REQUESTING_PERMISSIONS,
            attemptGeneration = snapshot.attemptGeneration + 1L,
            failure = null,
            disabledFeatures = emptySet(),
        )
    }

    fun beginRunning(
        snapshot: PostLoginDeviceCheckSnapshot,
        binding: PostLoginDeviceCheckBinding,
        foreground: Boolean,
    ): PostLoginDeviceCheckSnapshot {
        if (!isCurrent(snapshot, binding)) return snapshot
        if (snapshot.state != PostLoginDeviceCheckState.REQUESTING_PERMISSIONS) return snapshot
        if (!foreground) return fail(snapshot, PostLoginDeviceCheckFailure.BACKGROUNDED)
        return snapshot.copy(
            state = PostLoginDeviceCheckState.RUNNING,
            failure = null,
            disabledFeatures = emptySet(),
        )
    }

    fun evaluate(
        snapshot: PostLoginDeviceCheckSnapshot,
        binding: PostLoginDeviceCheckBinding,
        foreground: Boolean,
        observation: PostLoginDeviceCheckObservation,
    ): PostLoginDeviceCheckSnapshot {
        if (!isCurrent(snapshot, binding)) return snapshot
        if (snapshot.state != PostLoginDeviceCheckState.RUNNING) return snapshot
        if (!foreground) return fail(snapshot, PostLoginDeviceCheckFailure.BACKGROUNDED)

        observation.blockingFailure?.let { return fail(snapshot, it) }
        if (observation.requiredPermissions == PostLoginDeviceCheckSignal.UNAVAILABLE) {
            return fail(snapshot, PostLoginDeviceCheckFailure.REQUIRED_PERMISSION)
        }
        if (observation.wakePhraseRecognition == PostLoginDeviceCheckSignal.UNAVAILABLE) {
            return fail(snapshot, PostLoginDeviceCheckFailure.WAKE_PHRASE_UNAVAILABLE)
        }
        if (hasPendingAutomaticProbe(observation)) return snapshot

        val disabledFeatures = observation.unsupportedFeatures.toSet()
        return if (disabledFeatures.isEmpty()) {
            snapshot.copy(
                state = PostLoginDeviceCheckState.FULL,
                failure = null,
                disabledFeatures = emptySet(),
            )
        } else {
            snapshot.copy(
                state = PostLoginDeviceCheckState.LIMITED,
                failure = null,
                disabledFeatures = disabledFeatures,
            )
        }
    }

    fun timeout(
        snapshot: PostLoginDeviceCheckSnapshot,
        binding: PostLoginDeviceCheckBinding,
        foreground: Boolean,
    ): PostLoginDeviceCheckSnapshot {
        if (!isCurrent(snapshot, binding)) return snapshot
        if (snapshot.state != PostLoginDeviceCheckState.RUNNING) return snapshot
        if (!foreground) return fail(snapshot, PostLoginDeviceCheckFailure.BACKGROUNDED)
        return fail(snapshot, PostLoginDeviceCheckFailure.CHECK_TIMEOUT)
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
            snapshot.state == PostLoginDeviceCheckState.FAIL ||
            snapshot.state == PostLoginDeviceCheckState.FULL ||
            snapshot.state == PostLoginDeviceCheckState.LIMITED
        ) return snapshot
        return fail(snapshot, PostLoginDeviceCheckFailure.BACKGROUNDED)
    }

    fun invalidatePassedGate(
        snapshot: PostLoginDeviceCheckSnapshot,
        failure: PostLoginDeviceCheckFailure,
    ): PostLoginDeviceCheckSnapshot =
        if (snapshot.passesFeatureGate) {
            fail(snapshot, failure)
        } else {
            snapshot
        }

    fun isCurrent(
        snapshot: PostLoginDeviceCheckSnapshot,
        binding: PostLoginDeviceCheckBinding,
    ): Boolean =
        snapshot.actorId == binding.actorId &&
            snapshot.sessionGeneration == binding.sessionGeneration &&
            snapshot.attemptGeneration == binding.attemptGeneration

    // Wake-phrase recognition is explicit attempt evidence.
    // Location quality is still measured again by the live prewalk environment gate.
    private fun hasPendingAutomaticProbe(
        observation: PostLoginDeviceCheckObservation,
    ): Boolean = listOf(
        observation.minimumAndroidVersion,
        observation.requiredPermissions,
        observation.voiceDisclosure,
        observation.coreHardwareAndServices,
        observation.locationService,
        observation.deviceResources,
        observation.detector,
        observation.cameraPipeline,
        observation.koreanTextToSpeech,
        observation.wakePhraseRecognition,
    ).any { it == PostLoginDeviceCheckSignal.PENDING } ||
        observation.metricDepth == PostLoginMetricDepthState.PENDING

    private fun fail(
        snapshot: PostLoginDeviceCheckSnapshot,
        failure: PostLoginDeviceCheckFailure,
    ): PostLoginDeviceCheckSnapshot = snapshot.copy(
        state = PostLoginDeviceCheckState.FAIL,
        failure = failure,
        disabledFeatures = emptySet(),
    )
}
