package kr.co.hanium.dreamup.walksafe.inference

import java.security.MessageDigest

/**
 * Exact identity of one running detector instance (14 #33): the model bytes and configuration that
 * were verified before the native load, the class list that the loaded models output, the execution
 * path, and the process-local load generation. Two instances with the same bundle version but
 * different model bytes, configuration, delegate, or generation are different identities.
 */
data class RunningDetectorIdentity(
    val bundleVersion: String,
    val modelKey: String,
    /** Model key -> artifact sha256 that [TwoModelRuntimeConfig.assetIntegrityFailure] checked. */
    val modelArtifacts: Map<String, String>,
    val configSha256: String,
    val classListSha256: String,
    /** Delegate that executed the sample inference on this instance (after any CPU fallback). */
    val activeDelegate: String,
    val fallbackUsed: Boolean,
    val generation: Long,
) {
    init {
        require(bundleVersion.isNotBlank() && bundleVersion == bundleVersion.trim())
        require(modelKey.isNotBlank() && modelKey == modelKey.trim())
        require(modelArtifacts.isNotEmpty())
        require(modelArtifacts.keys.all { it.isNotBlank() && it == it.trim() })
        require(modelArtifacts.values.all(SHA256_PATTERN::matches))
        require(SHA256_PATTERN.matches(configSha256))
        require(SHA256_PATTERN.matches(classListSha256))
        require(activeDelegate in ModelRuntimeOptions.SUPPORTED_DELEGATES)
        require(generation >= 0L)
    }

    /** One value for every loaded model file: the single hash, or a domain hash of all of them. */
    val modelSetSha256: String
        get() = modelArtifacts.values.singleOrNull() ?: sha256Hex(
            "walksafe.running-detector.model-set.v1\n" +
                modelArtifacts.toSortedMap().entries.joinToString("\n") { (key, hash) -> "$key=$hash" },
        )

    companion object {
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

        /**
         * Null (fail closed) when the running models cannot be named exactly: unknown model key,
         * a model entry without a pinned artifact hash, a malformed configuration hash, or no observed
         * execution delegate.
         */
        fun of(
            config: TwoModelRuntimeConfig?,
            configSha256: String?,
            modelKey: String?,
            activeDelegate: String?,
            fallbackUsed: Boolean,
            generation: Long,
        ): RunningDetectorIdentity? = runCatching {
            val runtimeConfig = requireNotNull(config)
            val models = when (requireNotNull(modelKey)) {
                TwoModelRuntimeConfig.UNIFIED_MODEL_KEY -> listOf(requireNotNull(runtimeConfig.unifiedWalksafe))
                TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY -> listOf(
                    requireNotNull(runtimeConfig.customTactile),
                    requireNotNull(runtimeConfig.cocoGeneral),
                )
                else -> error("unknown running model key")
            }
            RunningDetectorIdentity(
                bundleVersion = runtimeConfig.bundleVersion,
                modelKey = modelKey,
                modelArtifacts = models.associate { it.key to requireNotNull(it.artifactSha256) },
                configSha256 = requireNotNull(configSha256),
                classListSha256 = classListSha256(models),
                activeDelegate = requireNotNull(activeDelegate),
                fallbackUsed = fallbackUsed,
                generation = generation,
            )
        }.getOrNull()

        /** Order-sensitive: a reordered class list changes the class ids the parser emits. */
        fun classListSha256(models: List<ModelRuntimeConfig>): String = sha256Hex(
            "walksafe.running-detector.class-list.v1\n" +
                models.joinToString("\n") { model -> model.key + ":" + model.classes.joinToString(",") },
        )

        private fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

/**
 * Which load stages were actually observed for one instance. They are different stages (14 #33):
 * the asset hash check and interpreter creation happen in [TfliteAndroidFrameDetector.createWithStatus];
 * the sample inference is one fixed in-app calibration image run on that exact instance.
 */
data class RunningDetectorLoadEvidence(
    val assetIntegrityVerified: Boolean,
    val interpreterLoaded: Boolean,
    val sampleInferencePassed: Boolean,
) {
    val passed: Boolean
        get() = assetIntegrityVerified && interpreterLoaded && sampleInferencePassed
}

/** Verification token for one instance; it never outlives a replacement of that instance. */
class VerifiedRunningDetector internal constructor(
    val identity: RunningDetectorIdentity,
    private val instance: Any,
) {
    internal fun isInstance(candidate: Any?): Boolean = candidate === instance
}

/**
 * S14-33-F1: verification state of one running instance. PENDING is bounded by
 * [RunningDetectorVerificationGate.maxAttempts] and [RunningDetectorVerificationGate.pendingDeadlineMs];
 * NONE means no verification was started for that instance.
 */
enum class RunningDetectorVerificationState {
    NONE,
    PENDING,
    VERIFIED,
    FAILED,
}

/** What the caller does after one sample-inference attempt (S14-33-F1). */
enum class RunningDetectorVerificationStep {
    VERIFIED,
    RETRY,
    FAILED,
    /** The attempt belongs to a replaced instance or an already finished verification. */
    STALE,
}

/**
 * Output gate evidence for the detector that is running now (FP-019 design_rules[5], FP-039).
 * [verify] replaces any earlier token, so a reload, delegate swap, rollback, or close that issues a
 * new generation invalidates the previous verification even when the bundle version is unchanged.
 * S14-33-F1: [begin] starts a bounded verification of one instance. A failed sample inference is
 * retried until [maxAttempts]; the last failed attempt, an executor rejection ([fail]) or waiting
 * past [pendingDeadlineMs] ([expire]) ends in FAILED, so "checking" never lasts indefinitely.
 */
class RunningDetectorVerificationGate(
    /** Sample-inference attempts per instance before the verification fails. */
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /** Wait between a failed attempt and the next one. */
    val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    /** Longest time one instance may stay PENDING, queueing and retries included. */
    val pendingDeadlineMs: Long = DEFAULT_PENDING_DEADLINE_MS,
) {
    init {
        require(maxAttempts >= 1)
        require(retryDelayMs >= 0L)
        require(pendingDeadlineMs > 0L)
    }

    @Volatile
    private var verified: VerifiedRunningDetector? = null

    private class Attempt(val instance: Any, val startedAtMs: Long) {
        var failures = 0
        var failed = false
    }

    /** Guarded by this gate. Cleared once a token is issued. */
    private var attempt: Attempt? = null

    /** Starts the verification of [instance]; any earlier token or attempt is dropped. */
    @Synchronized
    fun begin(instance: Any, nowMs: Long) {
        verified = null
        attempt = Attempt(instance, nowMs)
    }

    /** Records one sample-inference attempt of the instance passed to [begin]. */
    @Synchronized
    fun recordAttempt(
        instance: Any,
        identity: RunningDetectorIdentity?,
        evidence: RunningDetectorLoadEvidence,
        nowMs: Long,
    ): RunningDetectorVerificationStep {
        val current = attempt
        if (current == null || current.instance !== instance || current.failed) {
            return RunningDetectorVerificationStep.STALE
        }
        if (verify(instance, identity, evidence) != null) {
            attempt = null
            return RunningDetectorVerificationStep.VERIFIED
        }
        current.failures += 1
        if (current.failures >= maxAttempts || overdue(current, nowMs)) {
            current.failed = true
            return RunningDetectorVerificationStep.FAILED
        }
        return RunningDetectorVerificationStep.RETRY
    }

    /** Fails a PENDING verification of [instance] (e.g. rejected work); true when this call failed it. */
    @Synchronized
    fun fail(instance: Any): Boolean {
        val current = attempt
        if (current == null || current.instance !== instance || current.failed) return false
        verified = null
        current.failed = true
        return true
    }

    /** True when [instance] was still PENDING past [pendingDeadlineMs]; it is FAILED afterwards. */
    @Synchronized
    fun expire(instance: Any, nowMs: Long): Boolean {
        val current = attempt
        if (current == null || current.instance !== instance || current.failed || !overdue(current, nowMs)) {
            return false
        }
        verified = null
        current.failed = true
        return true
    }

    @Synchronized
    fun state(instance: Any?): RunningDetectorVerificationState {
        if (instance == null) return RunningDetectorVerificationState.NONE
        if (verified?.isInstance(instance) == true) return RunningDetectorVerificationState.VERIFIED
        val current = attempt
        return when {
            current == null || current.instance !== instance -> RunningDetectorVerificationState.NONE
            current.failed -> RunningDetectorVerificationState.FAILED
            else -> RunningDetectorVerificationState.PENDING
        }
    }

    /** A clock that went backwards is treated as overdue (fail closed). */
    private fun overdue(current: Attempt, nowMs: Long): Boolean =
        nowMs < current.startedAtMs || nowMs - current.startedAtMs >= pendingDeadlineMs

    /** Issues a token only when every load stage passed; otherwise the gate stays closed. */
    fun verify(
        instance: Any,
        identity: RunningDetectorIdentity?,
        evidence: RunningDetectorLoadEvidence,
    ): VerifiedRunningDetector? {
        val token = if (identity != null && evidence.passed) {
            VerifiedRunningDetector(identity, instance)
        } else {
            null
        }
        verified = token
        return token
    }

    fun invalidate() {
        verified = null
    }

    fun current(): VerifiedRunningDetector? = verified

    /** The live check used by output gates: same instance, same generation, same config bytes. */
    fun allowsOutput(instance: Any?, generation: Long, configSha256: String?): Boolean {
        val token = verified ?: return false
        return token.isInstance(instance) &&
            token.identity.generation == generation &&
            token.identity.configSha256 == configSha256
    }

    /** Exact identity comparison (model bytes, class list, delegate, fallback, generation). */
    fun allowsOutput(instance: Any?, identity: RunningDetectorIdentity?): Boolean {
        val token = verified ?: return false
        return identity != null && token.isInstance(instance) && token.identity == identity
    }

    companion object {
        /** Conservative bounds until device measurements exist (not a field-approved profile). */
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_RETRY_DELAY_MS = 1_000L
        /** Same bound as the post-login camera pipeline check (MainActivity 15 s). */
        const val DEFAULT_PENDING_DEADLINE_MS = 15_000L
    }
}
