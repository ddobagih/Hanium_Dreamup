package kr.co.hanium.dreamup.walksafe.device

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kr.co.hanium.dreamup.walksafe.inference.RuntimeBackend
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCalibrationFixtures
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidateOutcome
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidateQueueSnapshot
import kr.co.hanium.dreamup.walksafe.inference.RuntimeConfirmedMeasurement
import kr.co.hanium.dreamup.walksafe.inference.RuntimeFeatureScope
import kr.co.hanium.dreamup.walksafe.inference.RuntimeSelectionPolicy
import kr.co.hanium.dreamup.walksafe.inference.RuntimeSelectionStatus
import kr.co.hanium.dreamup.walksafe.inference.RuntimeTuningProfile
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import org.json.JSONObject
import java.security.MessageDigest
import java.util.WeakHashMap

enum class DeviceTuningRecheckReason {
    INCOMPLETE_COMPARISON, INCONCLUSIVE, ENVIRONMENT_INTERRUPTED,
    PERSISTENT_DEGRADATION, EXECUTION_RECOVERY,
}

data class DeviceTuningAttempt(
    val bindingHash: String,
    val startedAtEpochMs: Long,
    val startedAtElapsedMs: Long,
    val bootCount: Int?,
    val pendingReason: DeviceTuningRecheckReason?,
    val cursor: RuntimeCandidateQueueSnapshot?,
)

internal data class DeviceTuningClockReading(val epochMs: Long, val elapsedMs: Long, val bootCount: Int?)

/** Device-local preference, not a record of the currently active interpreter or login state.
 * Mutations are synchronous and must run on a worker after the caller's lease check.
 */
class AndroidDeviceTuningProfileStore internal constructor(
    private val preferences: SharedPreferences,
    private val clock: () -> DeviceTuningClockReading,
    private val availableProcessors: () -> Int,
    private val isMainThread: () -> Boolean,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        {
            DeviceTuningClockReading(
                System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                kotlin.runCatching {
                    Settings.Global.getInt(context.applicationContext.contentResolver, Settings.Global.BOOT_COUNT)
                        .takeIf { it >= 0 }
                }.getOrNull(),
            )
        },
        { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) },
        { Looper.myLooper() == Looper.getMainLooper() },
    )

    // A failed commit can still replace SharedPreferences' memory. Keep the last accepted state.
    private val commitState = synchronized(commitStates) {
        commitStates.getOrPut(preferences) { CommitState() }
    }

    fun load(bindingHash: String): RuntimeTuningProfile? = read().profile?.takeIf {
        it.isValid(bindingHash, availableProcessors().coerceAtLeast(1)) && hasCurrentFixture(it)
    }

    fun loadAttempt(bindingHash: String): DeviceTuningAttempt? =
        read().attempt?.takeIf { it.bindingHash == bindingHash && validHash(bindingHash) }

    /** Only a CONFIRMED decision can issue a valid profile; partial attempts use markAttemptFinished. */
    fun save(profile: RuntimeTuningProfile): Boolean = synchronized(commitState) {
        if (!profile.isValid(profile.bindingHash, availableProcessors().coerceAtLeast(1)) || !hasCurrentFixture(profile)) return false
        val old = read()
        return write(old.copy(profile = profile), old)
    }

    /** Record before starting native work, so cancellation and process death also consume the retry. */
    fun markAttemptStarted(
        bindingHash: String,
        reason: DeviceTuningRecheckReason = DeviceTuningRecheckReason.INCOMPLETE_COMPARISON,
        cursor: RuntimeCandidateQueueSnapshot? = null,
    ): Boolean = synchronized(commitState) {
        val now = clock()
        if (!validHash(bindingHash) || !validClock(now)) return false
        val old = read()
        return write(old.copy(attempt = DeviceTuningAttempt(
            bindingHash, now.epochMs, now.elapsedMs, now.bootCount, reason, cursor,
        )), old)
    }

    /** Finish or schedule a necessary recheck without extending the original attempt's cooldown.
     * A queue of merely untested, low-priority values is not itself a pending reason.
     */
    fun markAttemptFinished(
        bindingHash: String,
        pendingReason: DeviceTuningRecheckReason?,
        cursor: RuntimeCandidateQueueSnapshot? = null,
    ): Boolean = synchronized(commitState) {
        val old = read()
        val attempt = old.attempt?.takeIf { it.bindingHash == bindingHash } ?: return false
        if (pendingReason == null && cursor != null) return false
        return write(old.copy(attempt = attempt.copy(pendingReason = pendingReason, cursor = cursor)), old)
    }

    /** Read-only eligibility. Main owns foreground, camera, walking and account flow leases. */
    fun needsRecheck(bindingHash: String, manual: Boolean = false): Boolean {
        if (!validHash(bindingHash)) return false
        if (manual) return true
        val record = read()
        val profile = record.profile?.takeIf {
            it.isValid(bindingHash, availableProcessors().coerceAtLeast(1)) && hasCurrentFixture(it)
        }
        val attempt = record.attempt?.takeIf { it.bindingHash == bindingHash }
        if (profile != null && (attempt == null || attempt.pendingReason == null)) return false
        if (attempt == null || attempt.pendingReason == DeviceTuningRecheckReason.EXECUTION_RECOVERY) return true
        return DeviceTuningRetryPolicy.canRetry(attempt, clock())
    }

    private fun read(): DeviceTuningRecord {
        commitState.previousRecord?.let { return it }
        val json = runCatching { preferences.getString(RECORD_KEY, null) }.getOrNull()
        return json?.let(DeviceTuningRecordCodec::decode) ?: DeviceTuningRecord()
    }

    private fun write(record: DeviceTuningRecord, old: DeviceTuningRecord): Boolean {
        if (isMainThread()) return false
        val encoded = DeviceTuningRecordCodec.encode(record) ?: return false
        commitState.previousRecord = old
        val saved = runCatching { preferences.edit().putString(RECORD_KEY, encoded).commit() }.getOrDefault(false)
        if (saved) commitState.previousRecord = null
        return saved
    }

    private class CommitState {
        @Volatile var previousRecord: DeviceTuningRecord? = null
    }

    private fun hasCurrentFixture(profile: RuntimeTuningProfile) =
        profile.fixtureVersion == RuntimeCalibrationFixtures.VERSION &&
            profile.fixtureHash == RuntimeCalibrationFixtures.MANIFEST_SHA256

    companion object {
        const val PREFERENCES_NAME = "walksafe_device_tuning"
        internal const val RECORD_KEY = "record"
        private val commitStates = WeakHashMap<SharedPreferences, CommitState>()
        // Keep this contract aligned with build dependencies and inference/preprocessing/tracking changes.
        const val RUNTIME_CONTRACT_VERSION =
            "device-calibration-walkmate21-20260912-v2;litert=1.4.0;gpu=1.4.0;arcore=1.54.0;opencv=4.13.0"
        const val INPUT_CONTRACT_VERSION = "fused-yuv-rgb-letterbox-float32-768-v1"

        /** Use the base asset config, never a candidate's runtime override. The loader verifies the
         * actual model bytes with assetMatches before a model is eligible for calibration.
         * GPU identification stays null unless an owner has actually read it; no device-name guesses.
         */
        @Suppress("UNUSED_PARAMETER")
        fun createBinding(
            context: Context,
            config: TwoModelRuntimeConfig,
            scope: RuntimeFeatureScope,
            gpuIdentity: String? = null,
        ): String? = DeviceTuningBinding.create(
            config, scope, listOf(System.getProperty("os.arch").orEmpty(),
                if (android.os.Process.is64Bit()) "process-64-bit" else "process-32-bit") + Build.SUPPORTED_ABIS,
            Build.FINGERPRINT,
            gpuIdentity, RUNTIME_CONTRACT_VERSION, INPUT_CONTRACT_VERSION, RuntimeSelectionPolicy.VERSION,
        )
    }
}

internal object DeviceTuningBinding {
    fun create(
        config: TwoModelRuntimeConfig,
        scope: RuntimeFeatureScope,
        abis: List<String>,
        osFingerprint: String,
        gpuIdentity: String?,
        runtimeVersion: String,
        inputVersion: String,
        policyVersion: String,
        fixtureVersion: String = RuntimeCalibrationFixtures.VERSION,
        fixtureHash: String = RuntimeCalibrationFixtures.MANIFEST_SHA256,
    ): String? = runCatching {
        val model = requireNotNull(config.unifiedWalksafe)
        require(config.primaryModelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY && model.enabled)
        require(validHash(model.artifactSha256.orEmpty()))
        require(model.inputSize == 768 && !model.runtime.gpuPrecisionLossAllowed)
        require(model.classes.isNotEmpty() && model.classes.distinct().size == model.classes.size)
        require(model.classes.all(::validText) && model.allowlist.all(::validText))
        require(model.thresholds.isNotEmpty() && model.thresholds.keys.all(::validText))
        require(model.thresholds.values.all { it.isFinite() && it in 0f..1f })
        require(model.runtime.numThreads > 0)
        require(abis.isNotEmpty() && abis.all(::validText))
        require(listOf(config.bundleVersion, osFingerprint, runtimeVersion, inputVersion, policyVersion).all(::validText))
        require(gpuIdentity == null || validText(gpuIdentity))
        require(validText(fixtureVersion) && validHash(fixtureHash))
        val fields = buildList {
            add(config.bundleVersion); add(config.primaryModelKey); add(config.fallbackModelKey.orEmpty())
            add(model.key); add(model.asset); add(model.artifactSha256!!); add(model.inputSize.toString())
            add(inputVersion); add(runtimeVersion); add(policyVersion); add(scope.name)
            add(model.outputFormat.configValue); add(model.outputTensorShape().joinToString(","))
            add(model.nmsIouThreshold.toString()); add(model.maxDetections.toString())
            add(fixtureVersion); add(fixtureHash)
            add(osFingerprint); add(gpuIdentity.orEmpty())
            add(abis.size.toString()); addAll(abis)
            add(model.classes.size.toString()); addAll(model.classes)
            add(model.allowlist.size.toString()); addAll(model.allowlist.sorted())
            add(model.thresholds.size.toString())
            model.thresholds.toSortedMap().forEach { (key, value) -> add(key); add(value.toString()) }
            add(model.runtime.delegate); add(model.runtime.numThreads.toString())
            add(model.runtime.fallbackToCpu.toString()); add(model.runtime.gpuPrecisionLossAllowed.toString())
            add(model.runtime.gpuSerializationCacheEnabled.toString())
        }
        require(fields.all { it.length <= 4096 })
        val canonical = fields.joinToString("") { "${it.length}:$it" }
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrNull()
}

internal object DeviceTuningRetryPolicy {
    const val RETRY_INTERVAL_MS = 24L * 60 * 60 * 1000

    fun canRetry(attempt: DeviceTuningAttempt, now: DeviceTuningClockReading): Boolean {
        if (!validClock(now)) return false
        val sameBoot = now.bootCount != null && now.bootCount == attempt.bootCount
        if (sameBoot && now.elapsedMs >= attempt.startedAtElapsedMs) {
            return now.elapsedMs - attempt.startedAtElapsedMs >= RETRY_INTERVAL_MS
        }
        // With no boot counter, a plausible continuous monotonic clock is preferable to wall time.
        if (now.bootCount == null && attempt.bootCount == null && now.elapsedMs >= attempt.startedAtElapsedMs) {
            return now.elapsedMs - attempt.startedAtElapsedMs >= RETRY_INTERVAL_MS
        }
        if (now.epochMs >= attempt.startedAtEpochMs) {
            return now.epochMs - attempt.startedAtEpochMs >= RETRY_INTERVAL_MS
        }
        // Reboot plus a rolled-back wall clock must not leave a future timestamp blocking forever.
        return now.elapsedMs >= RETRY_INTERVAL_MS
    }
}

internal data class DeviceTuningRecord(
    val profile: RuntimeTuningProfile? = null,
    val attempt: DeviceTuningAttempt? = null,
)

/** Only this canonical schema is accepted. Re-encoding rejects duplicate/unknown keys, coercions,
 * non-JSON syntax, trailing data and missing fields even on Android's permissive JSONObject parser.
 */
internal object DeviceTuningRecordCodec {
    private const val SCHEMA = 1
    private const val MAX_LENGTH = 262_144

    fun encode(record: DeviceTuningRecord): String? = runCatching {
        record.profile?.let {
            require(it.isValid(it.bindingHash, Int.MAX_VALUE) && validText(it.fixtureVersion))
        }
        record.attempt?.let(::validateAttempt)
        objectJson("schema" to SCHEMA, "profile" to record.profile?.let(::profileJson),
            "attempt" to record.attempt?.let(::attemptJson)).text.also { require(it.length <= MAX_LENGTH) }
    }.getOrNull()

    fun decode(text: String): DeviceTuningRecord? = runCatching {
        require(text.length in 1..MAX_LENGTH)
        val root = JSONObject(text)
        require(root.int("schema") == SCHEMA)
        val record = DeviceTuningRecord(
            root.nullableObject("profile")?.let(::readProfile),
            root.nullableObject("attempt")?.let(::readAttempt),
        )
        require(encode(record) == text)
        record
    }.getOrNull()

    private fun profileJson(p: RuntimeTuningProfile) = objectJson(
        "bindingHash" to p.bindingHash, "candidate" to candidateJson(p.candidate),
        "featureScope" to p.featureScope.name, "fixtureVersion" to p.fixtureVersion,
        "fixtureHash" to p.fixtureHash, "policyVersion" to p.policyVersion,
        "measuredAtEpochMs" to p.measuredAtEpochMs,
        "measurement" to p.measurement.let { m -> objectJson(
            "bindingHash" to m.bindingHash, "featureScope" to m.featureScope.name,
            "actualBackend" to m.actualBackend.name, "comparedPairCount" to m.comparedPairCount,
            "confirmedPairCount" to m.confirmedPairCount, "baselineMedianMs" to m.baselineMedianMs,
            "selectedMedianMs" to m.selectedMedianMs, "uncertaintyMs" to m.uncertaintyMs,
            "fixtureVersion" to m.fixtureVersion, "fixtureHash" to m.fixtureHash,
            "liveValidCompletions" to m.liveValidCompletions,
            "liveObservationDurationMs" to m.liveObservationDurationMs, "policyVersion" to m.policyVersion,
        ) },
    )

    private fun readProfile(p: JSONObject): RuntimeTuningProfile {
        val m = p.getJSONObject("measurement")
        return RuntimeTuningProfile(
            p.string("bindingHash"), readCandidate(p.getJSONObject("candidate")),
            enumValueOf(p.string("featureScope")), p.string("fixtureVersion"), p.string("fixtureHash"),
            p.string("policyVersion"), p.long("measuredAtEpochMs"), RuntimeConfirmedMeasurement(
                m.string("bindingHash"), enumValueOf(m.string("featureScope")), enumValueOf(m.string("actualBackend")),
                m.int("comparedPairCount"), m.int("confirmedPairCount"), m.double("baselineMedianMs"),
                m.double("selectedMedianMs"), m.double("uncertaintyMs"), m.string("fixtureVersion"),
                m.string("fixtureHash"), m.int("liveValidCompletions"), m.long("liveObservationDurationMs"),
                m.string("policyVersion"),
            ),
        )
    }

    private fun attemptJson(a: DeviceTuningAttempt) = objectJson(
        "bindingHash" to a.bindingHash, "startedAtEpochMs" to a.startedAtEpochMs,
        "startedAtElapsedMs" to a.startedAtElapsedMs, "bootCount" to a.bootCount,
        "pendingReason" to a.pendingReason?.name, "cursor" to a.cursor?.let(::queueJson),
    )

    private fun readAttempt(a: JSONObject) = DeviceTuningAttempt(
        a.string("bindingHash"), a.long("startedAtEpochMs"), a.long("startedAtElapsedMs"),
        if (a.isNull("bootCount")) null else a.int("bootCount"),
        if (a.isNull("pendingReason")) null else enumValueOf<DeviceTuningRecheckReason>(a.string("pendingReason")),
        a.nullableObject("cursor")?.let(::readQueue),
    )

    private fun candidateJson(c: RuntimeCandidate) = objectJson("backend" to c.backend.name, "numThreads" to c.numThreads)
    private fun readCandidate(c: JSONObject) = RuntimeCandidate(enumValueOf(c.string("backend")), c.int("numThreads"))

    private fun queueJson(q: RuntimeCandidateQueueSnapshot) = objectJson(
        "availableProcessors" to q.availableProcessors, "baseline" to candidateJson(q.baseline),
        "gpuSupported" to q.gpuSupported, "initialGpuThreads" to q.initialGpuThreads,
        "preferredBackend" to q.preferredBackend?.name, "cpuAnchor" to q.cpuAnchor, "gpuAnchor" to q.gpuAnchor,
        "cpuCursor" to q.cpuCursor, "gpuCursor" to q.gpuCursor, "initialGpuVisited" to q.initialGpuVisited,
        "baselineVisited" to q.baselineVisited, "nextAlternatingBackend" to q.nextAlternatingBackend.name,
        "outcomes" to Json(q.outcomes.joinToString(",", "[", "]") {
            objectJson("candidate" to candidateJson(it.candidate), "status" to it.status.name).text
        }),
    )

    private fun readQueue(q: JSONObject): RuntimeCandidateQueueSnapshot {
        val outcomes = q.getJSONArray("outcomes")
        return RuntimeCandidateQueueSnapshot(
            q.int("availableProcessors"), readCandidate(q.getJSONObject("baseline")), q.boolean("gpuSupported"),
            q.int("initialGpuThreads"), if (q.isNull("preferredBackend")) null else enumValueOf<RuntimeBackend>(q.string("preferredBackend")),
            q.int("cpuAnchor"), q.int("gpuAnchor"), q.int("cpuCursor"), q.int("gpuCursor"),
            q.boolean("initialGpuVisited"), q.boolean("baselineVisited"), enumValueOf(q.string("nextAlternatingBackend")),
            List(outcomes.length()) { index -> outcomes.getJSONObject(index).let {
                RuntimeCandidateOutcome(readCandidate(it.getJSONObject("candidate")), enumValueOf(it.string("status")))
            } },
        )
    }

    private fun validateAttempt(a: DeviceTuningAttempt) {
        require(validHash(a.bindingHash))
        require(validClock(DeviceTuningClockReading(a.startedAtEpochMs, a.startedAtElapsedMs, a.bootCount)))
        require(a.pendingReason != null || a.cursor == null)
        a.cursor?.let { q ->
            val n = q.availableProcessors
            require(n > 0)
            require(listOf(q.baseline.numThreads, q.initialGpuThreads, q.cpuAnchor, q.gpuAnchor).all { it in 1..n })
            require(q.cpuCursor in 1..n && q.gpuCursor in 1..n)
            require(q.outcomes.all { it.candidate.numThreads in 1..n })
            require(q.outcomes.map { it.candidate }.distinct().size == q.outcomes.size)
            require(q.gpuSupported || (q.baseline.backend != RuntimeBackend.GPU &&
                q.preferredBackend != RuntimeBackend.GPU && q.outcomes.none { it.candidate.backend == RuntimeBackend.GPU }))
        }
    }

    private data class Json(val text: String)
    private fun objectJson(vararg entries: Pair<String, Any?>) = Json(entries.joinToString(",", "{", "}") {
        JSONObject.quote(it.first) + ":" + when (val value = it.second) {
            null -> "null"
            is Json -> value.text
            is String -> JSONObject.quote(value)
            is Boolean, is Int, is Long -> value.toString()
            is Double -> { require(value.isFinite()); value.toString() }
            else -> error("Unsupported tuning value")
        }
    })

    private fun JSONObject.string(key: String) = (get(key) as? String)?.takeIf(::validText)
        ?: error("Invalid tuning string")
    private fun JSONObject.long(key: String): Long = when (val value = get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("Invalid tuning integer")
    }
    private fun JSONObject.int(key: String): Int = long(key).also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
    private fun JSONObject.double(key: String) = (get(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
        ?: error("Invalid tuning number")
    private fun JSONObject.boolean(key: String) = get(key) as? Boolean ?: error("Invalid tuning boolean")
    private fun JSONObject.nullableObject(key: String): JSONObject? {
        require(has(key))
        return if (isNull(key)) null else getJSONObject(key)
    }
}

private fun validHash(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
private fun validText(value: String) = value.isNotBlank() && value == value.trim() && value.length <= 4096
private fun validClock(value: DeviceTuningClockReading) =
    value.epochMs > 0 && value.elapsedMs >= 0 && (value.bootCount == null || value.bootCount >= 0)
