package kr.co.hanium.dreamup.walksafe.inference.composite

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Looper
import kr.co.hanium.dreamup.walksafe.device.AndroidDeviceTuningProfileStore
import kr.co.hanium.dreamup.walksafe.inference.RuntimeBackend
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCalibrationFixtures
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import kr.co.hanium.dreamup.walksafe.inference.TwoModelClassMap
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamModelContract
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.WeakHashMap

/** One atomic sample-measured choice, independent of physical camera/ARCore/depth admission. */
class AndroidJointRuntimeChoiceStore internal constructor(
    private val preferences: SharedPreferences,
    private val bindingFor: (TwoModelRuntimeConfig) -> String?,
    private val availableProcessors: () -> Int,
    private val isMainThread: () -> Boolean,
    private val thermalStatusSupported: () -> Boolean = { false },
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        { config -> createBinding(context.applicationContext, config) },
        { Runtime.getRuntime().availableProcessors() },
        { Looper.myLooper() == Looper.getMainLooper() },
        { Build.VERSION.SDK_INT >= 29 },
    )

    private val state = synchronized(states) { states.getOrPut(preferences) { State() } }

    /** Model/device/workload compatibility only; no physical camera quality is inferred. */
    fun load(config: TwoModelRuntimeConfig): ValidatedJointRuntimeChoice? = synchronized(state) {
        if (state.invalidated) return null
        val choice = JointRuntimeChoiceCodec.decode(read()) ?: return null
        val binding = bindingFor(config) ?: return null
        choice.takeIf { it.isValid(binding, availableProcessors()) && thermalCompatible(it) }
    }

    /** Caller verifies its attempt and the trial process's release before save and again before adoption. */
    fun save(config: TwoModelRuntimeConfig, choice: ValidatedJointRuntimeChoice): Boolean {
        if (isMainThread()) return false
        return synchronized(state) {
            val binding = bindingFor(config) ?: return false
            if (!choice.isValid(binding, availableProcessors()) || !thermalCompatible(choice)) return false
            val encoded = JointRuntimeChoiceCodec.encode(choice) ?: return false
            // Snapshot the last successful commit, not SharedPreferences' possibly uncommitted memory.
            val previous = read()
            val previousChoice = JointRuntimeChoiceCodec.decode(previous)
            if (previousChoice?.measurementId == choice.measurementId) {
                // A measurement is immutable. An identical retry is idempotent, never a new check;
                // neither changed evidence nor a failed invalidation can resurrect this ID.
                return !state.invalidated && previous == encoded
            }
            val saved = runCatching { preferences.edit().putString(RECORD_KEY, encoded).commit() }.getOrDefault(false)
            if (saved) {
                state.loaded = true
                state.text = encoded
                state.invalidated = false
            }
            saved
        }
    }

    /** Only this runtime record is retired. A failed removal remains unusable in this process. */
    fun invalidate(): Boolean {
        if (isMainThread()) return false
        return synchronized(state) {
            read()
            state.invalidated = true
            val saved = runCatching { preferences.edit().remove(RECORD_KEY).commit() }.getOrDefault(false)
            if (saved) { state.loaded = true; state.text = null }
            saved
        }
    }

    /** A retired check may only retire the record it wrote, never a newer check's choice. */
    fun invalidateIfMatches(choice: ValidatedJointRuntimeChoice): Boolean {
        if (isMainThread()) return false
        return synchronized(state) {
            val expected = JointRuntimeChoiceCodec.encode(choice) ?: return false
            if (read() != expected) return false
            invalidate()
        }
    }

    private fun read(): String? {
        if (!state.loaded) {
            state.text = runCatching { preferences.getString(RECORD_KEY, null) }.getOrNull()
            state.loaded = true
        }
        return state.text
    }

    private fun thermalCompatible(choice: ValidatedJointRuntimeChoice): Boolean =
        !thermalStatusSupported() || choice.evidence.thermalStatus >= 0

    private class State { var loaded = false; var text: String? = null; var invalidated = false }

    companion object {
        // Keep this existing private namespace; schema 2 rejects old live records without migrating them.
        const val PREFERENCES_NAME = "walksafe_joint_runtime_choice_v1"
        private const val RECORD_KEY = "choice"
        private val states = WeakHashMap<SharedPreferences, State>()

        @Suppress("UNUSED_PARAMETER")
        fun createBinding(context: Context, config: TwoModelRuntimeConfig): String? = JointFixtureBinding.create(
            config, Build.FINGERPRINT, Build.HARDWARE, Build.SUPPORTED_ABIS.toList(),
            android.os.Process.is64Bit(), System.getProperty("os.arch").orEmpty(),
            Runtime.getRuntime().availableProcessors(),
        )
    }
}

/** Pure binding used by both the parent and the isolated fixture worker. No camera scope is invented. */
internal object JointFixtureBinding {
    private const val PRIMARY_INPUT = "fixed-argb-bilinear-rgb-f32-768-v1"
    private const val AUXILIARY_INPUT = "fast768-rgb-uint8-opencv413-bilinear-half-even-pad114-nhwc-f32-div255-v1"
    private const val OUTPUT_GATES = "primary-calibration-output-gate-v1;fast-raw-abs1e-4-rel1e-3-mask-match-v1"
    private const val SCHEDULE = "fixture-rgb-submit-model-preprocess-native-postprocess-single-flight-v1"

    fun create(config: TwoModelRuntimeConfig, osFingerprint: String, hardware: String, abis: List<String>,
        process64Bit: Boolean, processArchitecture: String, processors: Int,
        runtimeContract: String = AndroidDeviceTuningProfileStore.RUNTIME_CONTRACT_VERSION,
        fixtureVersion: String = RuntimeCalibrationFixtures.VERSION,
        fixtureHash: String = RuntimeCalibrationFixtures.MANIFEST_SHA256,
    ): String? = runCatching {
        val model = requireNotNull(config.unifiedWalksafe)
        require(config.primaryModelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY && model.enabled)
        require(config.personSupplement?.enabled != true)
        require(JointRuntimeChoicePolicy.validHash(model.artifactSha256.orEmpty()))
        require(model.inputSize == 768 && !model.runtime.gpuPrecisionLossAllowed && model.runtime.numThreads > 0)
        require(model.outputTensorShape().contentEquals(intArrayOf(1, 300, 6)))
        val sharedScooterIds = model.classes == TwoModelClassMap.walkmate26Classes &&
            !model.outputFormat.isRaw && model.classIdThresholds.keys.containsAll(listOf(9, 10))
        require(model.classes.isNotEmpty() && (model.classes.distinct().size == model.classes.size || sharedScooterIds))
        require(model.classes.all(::validText) && model.allowlist.all(::validText))
        require(model.thresholds.isNotEmpty() && model.thresholds.keys.all(::validText))
        require(model.thresholds.values.all { it.isFinite() && it in 0f..1f })
        require(model.classIdThresholds.keys.all { it in model.classes.indices } &&
            model.classIdThresholds.values.all { it.isFinite() && it in 0f..1f })
        require(processors > 0 && abis.isNotEmpty() && abis.all(::validText))
        require(listOf(config.bundleVersion, model.key, model.asset, osFingerprint, hardware,
            processArchitecture, runtimeContract, fixtureVersion).all(::validText))
        require(JointRuntimeChoicePolicy.validHash(fixtureHash))
        val fields = buildList {
            add(JointRuntimeChoicePolicy.VERSION); add(JointRuntimeMeasurementKind.SAMPLE_IMAGES_TWO_MODEL.name)
            add(config.bundleVersion); add(config.primaryModelKey); add(config.fallbackModelKey.orEmpty())
            add(model.key); add(model.asset); add(model.artifactSha256!!); add(model.inputSize.toString())
            add(PRIMARY_INPUT); add(AUXILIARY_INPUT); add(OUTPUT_GATES); add(SCHEDULE); add(runtimeContract)
            add(model.outputFormat.configValue); add(model.outputTensorShape().joinToString(","))
            add(model.nmsIouThreshold.toString()); add(model.maxDetections.toString())
            add(model.classes.size.toString()); addAll(model.classes)
            add(model.allowlist.size.toString()); addAll(model.allowlist.sorted())
            add(model.thresholds.size.toString())
            model.thresholds.toSortedMap().forEach { (key, value) -> add(key); add(value.toString()) }
            add(model.classIdThresholds.size.toString())
            model.classIdThresholds.toSortedMap().forEach { (id, value) -> add(id.toString()); add(value.toString()) }
            // These are the base asset options, never a selected candidate's overrides.
            add(model.runtime.delegate); add(model.runtime.numThreads.toString())
            add(model.runtime.fallbackToCpu.toString()); add(model.runtime.gpuPrecisionLossAllowed.toString())
            add(model.runtime.gpuSerializationCacheEnabled.toString())
            add(FastSamModelContract.MODEL_ID); add(FastSamModelContract.SHA256)
            add(fixtureVersion); add(fixtureHash); addAll(JointRuntimeChoicePolicy.REQUIRED_FIXTURE_IDS.sorted())
            add(osFingerprint); add(hardware); add(abis.size.toString()); addAll(abis); add(process64Bit.toString())
            add(processArchitecture); add(processors.toString())
        }
        require(fields.all { it.length <= 4096 })
        MessageDigest.getInstance("SHA-256").digest(fields.joinToString("") { "${it.length}:$it" }
            .toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }.getOrNull()

    private fun validText(value: String) = value.isNotBlank() && value.length <= 4096
}

/** Bounded canonical JSON for the atomic preference and sample-worker scalar IPC. */
internal object JointRuntimeChoiceCodec {
    const val MAX_EVIDENCE_JSON_BYTES = 65_536
    private const val MAX_RECORD_JSON_BYTES = MAX_EVIDENCE_JSON_BYTES + 4096
    private const val SCHEMA = 2

    fun encode(choice: ValidatedJointRuntimeChoice): String? = runCatching {
        require(JointRuntimeChoicePolicy.validMeasurementId(choice.measurementId))
        val measured = evidence(choice.evidence).also { checkLength(it.toString(), MAX_EVIDENCE_JSON_BYTES) }
        JSONObject().put("schema", SCHEMA).put("policy", choice.policyVersion)
            .put("measurementKind", choice.measurementKind.name).put("measurementId", choice.measurementId)
            .put("binding", choice.bindingHash)
            .put("candidate", JSONObject().put("primary", runtime(choice.candidate.primary))
                .put("auxiliary", runtime(choice.candidate.auxiliary))
                .put("primaryIntervalMs", choice.candidate.primaryIntervalMs)
                .put("auxiliaryIntervalMs", choice.candidate.auxiliaryIntervalMs))
            .put("evidence", measured).toString().also { checkLength(it, MAX_RECORD_JSON_BYTES) }
    }.getOrNull()

    fun decode(text: String?): ValidatedJointRuntimeChoice? = runCatching {
        val root = JSONObject(requireNotNull(text).also { checkLength(it, MAX_RECORD_JSON_BYTES) })
        require(int(root, "schema") == SCHEMA)
        val candidate = root.getJSONObject("candidate")
        val choice = ValidatedJointRuntimeChoice(string(root, "binding"),
            JointRuntimeCandidate(readRuntime(candidate.getJSONObject("primary")), readRuntime(candidate.getJSONObject("auxiliary")),
                long(candidate, "primaryIntervalMs"), long(candidate, "auxiliaryIntervalMs")),
            readEvidence(root.getJSONObject("evidence")),
            enumValueOf<JointRuntimeMeasurementKind>(string(root, "measurementKind")), string(root, "policy"),
            string(root, "measurementId"))
        require(encode(choice) == text)
        choice
    }.getOrNull()

    fun encodeEvidence(value: JointFixtureRuntimeEvidence): String? = runCatching {
        evidence(value).toString().also { checkLength(it, MAX_EVIDENCE_JSON_BYTES) }
    }.getOrNull()

    fun decodeEvidence(text: String?): JointFixtureRuntimeEvidence? = runCatching {
        val result = readEvidence(JSONObject(requireNotNull(text).also { checkLength(it, MAX_EVIDENCE_JSON_BYTES) }))
        require(encodeEvidence(result) == text)
        result
    }.getOrNull()

    private fun runtime(value: RuntimeCandidate) = JSONObject().put("backend", value.backend.name).put("threads", value.numThreads)
    private fun readRuntime(value: JSONObject) = RuntimeCandidate(enumValueOf<RuntimeBackend>(string(value, "backend")), int(value, "threads"))
    private fun evidence(e: JointFixtureRuntimeEvidence) = JSONObject()
        .put("durationMs", e.durationMs).put("primarySubmittedFrames", e.primarySubmittedFrames)
        .put("auxiliarySubmittedFrames", e.auxiliarySubmittedFrames)
        .put("primaryTurnaroundMs", JSONArray(e.primaryTurnaroundMs)).put("auxiliaryTurnaroundMs", JSONArray(e.auxiliaryTurnaroundMs))
        .put("primaryLongestGapMs", e.primaryLongestGapMs).put("auxiliaryLongestGapMs", e.auxiliaryLongestGapMs)
        .put("fixtureQuality", JSONArray(e.fixtureQuality.map { q -> JSONObject()
            .put("fixtureId", q.fixtureId).put("primaryRawValueCount", q.primaryRawValueCount)
            .put("auxiliaryDetectionValueCount", q.auxiliaryDetectionValueCount)
            .put("auxiliaryPrototypeValueCount", q.auxiliaryPrototypeValueCount)
            .put("primaryOutputEquivalent", q.primaryOutputEquivalent).put("primaryFixtureRolesMatched", q.primaryFixtureRolesMatched)
            .put("auxiliaryRawEquivalent", q.auxiliaryRawEquivalent).put("auxiliaryMasksEquivalent", q.auxiliaryMasksEquivalent) }))
        .put("actualPrimary", runtime(e.actualPrimary)).put("actualAuxiliary", runtime(e.actualAuxiliary))
        .put("fallbackUsed", e.fallbackUsed).put("thermalStatus", e.thermalStatus)

    private fun readEvidence(e: JSONObject) = JointFixtureRuntimeEvidence(
        long(e, "durationMs"), int(e, "primarySubmittedFrames"), int(e, "auxiliarySubmittedFrames"),
        samples(e, "primaryTurnaroundMs"), samples(e, "auxiliaryTurnaroundMs"),
        double(e, "primaryLongestGapMs"), double(e, "auxiliaryLongestGapMs"),
        e.getJSONArray("fixtureQuality").let { values ->
            require(values.length() == JointRuntimeChoicePolicy.REQUIRED_FIXTURE_IDS.size)
            List(values.length()) { i -> values.getJSONObject(i).let { q -> JointFixtureQuality(
                string(q, "fixtureId"), int(q, "primaryRawValueCount"), int(q, "auxiliaryDetectionValueCount"),
                int(q, "auxiliaryPrototypeValueCount"), boolean(q, "primaryOutputEquivalent"),
                boolean(q, "primaryFixtureRolesMatched"), boolean(q, "auxiliaryRawEquivalent"), boolean(q, "auxiliaryMasksEquivalent")) }
            }
        }, readRuntime(e.getJSONObject("actualPrimary")), readRuntime(e.getJSONObject("actualAuxiliary")),
        boolean(e, "fallbackUsed"), int(e, "thermalStatus"))

    private fun checkLength(text: String, limit: Int) {
        require(text.length in 1..limit && text.toByteArray(Charsets.UTF_8).size <= limit)
    }
    private fun string(root: JSONObject, key: String): String = (root.get(key) as? String)?.also {
        require(it.isNotBlank() && it.length <= 4096)
    } ?: error("Expected text")
    private fun boolean(root: JSONObject, key: String) = root.get(key) as? Boolean ?: error("Expected boolean")
    private fun double(root: JSONObject, key: String): Double = (root.get(key) as? Number)?.toDouble()?.also {
        require(it.isFinite())
    } ?: error("Expected number")
    private fun int(root: JSONObject, key: String): Int = double(root, key).let {
        require(it >= Int.MIN_VALUE && it <= Int.MAX_VALUE && it.toInt().toDouble() == it)
        it.toInt()
    }
    private fun long(root: JSONObject, key: String): Long = double(root, key).let {
        require(it >= 0.0 && it <= 300_000.0 && it.toLong().toDouble() == it)
        it.toLong()
    }
    private fun samples(root: JSONObject, key: String): List<Double> = root.getJSONArray(key).let { values ->
        require(values.length() in 1..JointRuntimeChoicePolicy.MAXIMUM_SAMPLES)
        List(values.length()) { i -> (values.get(i) as? Number)?.toDouble()?.also { require(it.isFinite()) }
            ?: error("Expected sample") }
    }
}
