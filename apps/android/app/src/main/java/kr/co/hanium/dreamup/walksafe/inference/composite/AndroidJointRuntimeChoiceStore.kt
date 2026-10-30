package kr.co.hanium.dreamup.walksafe.inference.composite

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Looper
import kr.co.hanium.dreamup.walksafe.device.AndroidDeviceTuningProfileStore
import kr.co.hanium.dreamup.walksafe.inference.RuntimeBackend
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCalibrationFixtures
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import kr.co.hanium.dreamup.walksafe.inference.RuntimeFeatureScope
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamModelContract
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.WeakHashMap

/** One atomic, jointly measured choice. Existing primary/auxiliary comparison profiles stay separate. */
class AndroidJointRuntimeChoiceStore internal constructor(
    private val preferences: SharedPreferences,
    private val bindingFor: (TwoModelRuntimeConfig, RuntimeFeatureScope, JointCameraMode) -> String?,
    private val availableProcessors: () -> Int,
    private val isMainThread: () -> Boolean,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        { config, scope, mode -> createBinding(context.applicationContext, config, scope, mode) },
        { Runtime.getRuntime().availableProcessors() },
        { Looper.myLooper() == Looper.getMainLooper() },
    )

    private val state = synchronized(states) { states.getOrPut(preferences) { State() } }

    /** Without scope/mode this is only an initial model-preparation lookup, not camera admission. */
    fun load(config: TwoModelRuntimeConfig, scope: RuntimeFeatureScope? = null,
        mode: JointCameraMode? = null): ValidatedJointRuntimeChoice? = synchronized(state) {
        if (state.invalidated) return null
        val choice = JointRuntimeChoiceCodec.decode(read()) ?: return null
        if (scope != null && scope != choice.scope || mode != null && mode != choice.cameraMode) return null
        val binding = bindingFor(config, choice.scope, choice.cameraMode) ?: return null
        choice.takeIf { it.isValid(binding, availableProcessors()) }
    }

    /** Caller checks its attempt/actor authority immediately before save and again before adoption. */
    fun save(config: TwoModelRuntimeConfig, choice: ValidatedJointRuntimeChoice): Boolean {
        if (isMainThread()) return false
        return synchronized(state) {
            val binding = bindingFor(config, choice.scope, choice.cameraMode) ?: return false
            if (!choice.isValid(binding, availableProcessors())) return false
            val encoded = JointRuntimeChoiceCodec.encode(choice) ?: return false
            read() // Capture the last persisted value before commit(false) can change preferences memory.
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

    private class State { var loaded = false; var text: String? = null; var invalidated = false }

    companion object {
        const val PREFERENCES_NAME = "walksafe_joint_runtime_choice_v1"
        private const val RECORD_KEY = "choice"
        private const val SCHEDULE_VERSION = "shared-rgb-primary-owner-fast-owner-post-single-flight-v1"
        private const val PREVIEW_VERSION = "full-camera-preview-with-controls-v1"
        private val states = WeakHashMap<SharedPreferences, State>()

        fun createBinding(context: Context, config: TwoModelRuntimeConfig, scope: RuntimeFeatureScope,
            mode: JointCameraMode? = null): String? = runCatching {
            // The mode must come from the active camera, never a device-name or model-size guess.
            val measuredMode = requireNotNull(mode)
            require(measuredMode.isValid())
            val primary = requireNotNull(AndroidDeviceTuningProfileStore.createBinding(context, config, scope))
            val fields = listOf(
                JointRuntimeChoicePolicy.VERSION, primary, FastSamModelContract.MODEL_ID, FastSamModelContract.SHA256,
                "fused-yuv-rgb-letterbox-float32-768-v1",
                "fast768-rgb-uint8-opencv413-bilinear-half-even-pad114-nhwc-f32-div255-v1",
                "fast768-37x12096-192x192x32-conf025-nms07-max100-crop-before-threshold-logit0-v1",
                "recognition-packet-frame-provider-epoch-cpu-timestamp-v1", SCHEDULE_VERSION, PREVIEW_VERSION,
                AndroidDeviceTuningProfileStore.RUNTIME_CONTRACT_VERSION,
                RuntimeCalibrationFixtures.VERSION, RuntimeCalibrationFixtures.MANIFEST_SHA256,
                Build.FINGERPRINT, Build.HARDWARE, Build.SUPPORTED_ABIS.joinToString(","),
                android.os.Process.is64Bit().toString(), System.getProperty("os.arch").orEmpty(),
                Runtime.getRuntime().availableProcessors().toString(), scope.name,
                measuredMode.width.toString(), measuredMode.height.toString(), measuredMode.fps.toString(),
                measuredMode.displayRefreshHz.toString(),
                "age=${JointRuntimeChoicePolicy.MAXIMUM_SOURCE_AGE_MS};ui=active-display-budget;camera=active-fps-budget",
            )
            require(fields.all { it.isNotBlank() && it.length <= 4096 })
            MessageDigest.getInstance("SHA-256").digest(fields.joinToString("") { "${it.length}:$it" }
                .toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        }.getOrNull()
    }
}

/** Strict bounded codec; load still rechecks model/device binding and every evidence invariant. */
internal object JointRuntimeChoiceCodec {
    private const val SCHEMA = 1
    private const val MAXIMUM_LENGTH = 524_288

    fun encode(choice: ValidatedJointRuntimeChoice): String? = runCatching {
        JSONObject().put("schema", SCHEMA).put("policy", choice.policyVersion)
            .put("binding", choice.bindingHash).put("scope", choice.scope.name)
            .put("candidate", JSONObject().put("primary", runtime(choice.candidate.primary))
                .put("auxiliary", runtime(choice.candidate.auxiliary))
                .put("primaryIntervalMs", choice.candidate.primaryIntervalMs)
                .put("auxiliaryIntervalMs", choice.candidate.auxiliaryIntervalMs))
            .put("evidence", evidence(choice.evidence)).toString().also { require(it.length <= MAXIMUM_LENGTH) }
    }.getOrNull()

    fun decode(text: String?): ValidatedJointRuntimeChoice? = runCatching {
        val root = JSONObject(requireNotNull(text).also { require(it.length in 1..MAXIMUM_LENGTH) })
        require(int(root, "schema") == SCHEMA)
        val candidate = root.getJSONObject("candidate")
        val choice = ValidatedJointRuntimeChoice(string(root, "binding"),
            JointRuntimeCandidate(readRuntime(candidate.getJSONObject("primary")), readRuntime(candidate.getJSONObject("auxiliary")),
                long(candidate, "primaryIntervalMs"), long(candidate, "auxiliaryIntervalMs")),
            enumValueOf<RuntimeFeatureScope>(string(root, "scope")), readEvidence(root.getJSONObject("evidence")),
            string(root, "policy"))
        // The record is written only by this encoder. Reject permissive JSON syntax, trailing
        // data, duplicate/unknown fields and alternate coercions accepted by Android JSONObject.
        require(encode(choice) == text)
        choice
    }.getOrNull()

    private fun runtime(value: RuntimeCandidate) = JSONObject().put("backend", value.backend.name).put("threads", value.numThreads)
    private fun readRuntime(value: JSONObject) = RuntimeCandidate(enumValueOf<RuntimeBackend>(string(value, "backend")), int(value, "threads"))
    private fun evidence(e: JointRuntimeEvidence) = JSONObject()
        .put("durationMs", e.durationMs).put("submittedFrames", e.submittedFrames)
        .put("primarySubmittedFrames", e.primarySubmittedFrames)
        .put("primaryAgesMs", JSONArray(e.primaryAgesMs)).put("auxiliaryAgesMs", JSONArray(e.auxiliaryAgesMs))
        .put("freshAuxiliaryFrames", e.freshAuxiliaryFrames).put("staleAuxiliaryFrames", e.staleAuxiliaryFrames)
        .put("primaryLongestGapMs", e.primaryLongestGapMs).put("auxiliaryLongestGapMs", e.auxiliaryLongestGapMs)
        .put("uiDelayMs", JSONArray(e.uiDelayMs)).put("uiBudgetMs", e.uiBudgetMs)
        .put("cameraIntervalMs", JSONArray(e.cameraIntervalMs)).put("cameraBudgetMs", e.cameraBudgetMs)
        .put("fixtureEquivalent", e.fixtureEquivalent).put("sourceQualified", e.sourceQualified)
        .put("fallbackUsed", e.fallbackUsed).put("thermalStatus", e.thermalStatus)
        .put("cameraWidth", e.cameraWidth).put("cameraHeight", e.cameraHeight).put("cameraFps", e.cameraFps)
        .put("displayRefreshHz", e.displayRefreshHz)
        .put("actualPrimary", runtime(e.actualPrimary)).put("actualAuxiliary", runtime(e.actualAuxiliary))

    private fun readEvidence(e: JSONObject) = JointRuntimeEvidence(
        long(e, "durationMs"), int(e, "submittedFrames"), samples(e, "primaryAgesMs"), samples(e, "auxiliaryAgesMs"),
        int(e, "freshAuxiliaryFrames"), int(e, "staleAuxiliaryFrames"),
        double(e, "primaryLongestGapMs"), double(e, "auxiliaryLongestGapMs"), samples(e, "uiDelayMs"), double(e, "uiBudgetMs"),
        samples(e, "cameraIntervalMs"), double(e, "cameraBudgetMs"), boolean(e, "fixtureEquivalent"), boolean(e, "sourceQualified"),
        boolean(e, "fallbackUsed"), int(e, "thermalStatus"), int(e, "cameraWidth"), int(e, "cameraHeight"), int(e, "cameraFps"),
        double(e, "displayRefreshHz"), readRuntime(e.getJSONObject("actualPrimary")), readRuntime(e.getJSONObject("actualAuxiliary")),
        int(e, "primarySubmittedFrames"))

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
