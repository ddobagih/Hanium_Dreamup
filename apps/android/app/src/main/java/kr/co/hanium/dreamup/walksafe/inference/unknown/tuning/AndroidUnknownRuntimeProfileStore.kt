package kr.co.hanium.dreamup.walksafe.inference.unknown.tuning

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamModelContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.WeakHashMap

/** The existing record codec/retry policy are reused; primary-only preferences remain untouched. */
class AndroidUnknownRuntimeProfileStore(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("walksafe_unknown_runtime_tuning", Context.MODE_PRIVATE)
    private val state = synchronized(states) { states.getOrPut(preferences) { State() } }

    fun load(binding: UnknownRuntimeBinding): UnknownRuntimeProfile? = synchronized(state) {
        val record = read()
        val runtime = record.first.profile ?: return null
        val root = record.second ?: return null
        runCatching {
            UnknownRuntimeProfile(runtime, readLoad(root.getJSONObject("primaryBaseline")),
                readLoad(root.getJSONObject("primarySelected")), root.getString("policyVersion"))
        }.getOrNull()?.takeIf { it.isValid(binding, Runtime.getRuntime().availableProcessors()) }
    }

    fun needsRecheck(binding: UnknownRuntimeBinding, manual: Boolean = false): Boolean = synchronized(state) {
        val hash = binding.hashOrNull() ?: return false
        if (manual) return true
        val attempt = read().first.attempt?.takeIf { it.bindingHash == hash }
        if (load(binding) != null && (attempt == null || attempt.pendingReason == null)) return false
        attempt == null || attempt.pendingReason == DeviceTuningRecheckReason.EXECUTION_RECOVERY ||
            DeviceTuningRetryPolicy.canRetry(attempt, clock())
    }

    fun loadCursor(binding: UnknownRuntimeBinding): RuntimeCandidateQueueSnapshot? = synchronized(state) {
        read().first.attempt?.takeIf { it.bindingHash == binding.hashOrNull() }?.cursor
    }

    fun markStarted(binding: UnknownRuntimeBinding, cursor: RuntimeCandidateQueueSnapshot? = null): Boolean = synchronized(state) {
        val hash = binding.hashOrNull() ?: return false
        val now = clock()
        val old = read()
        write(old.first.copy(attempt = DeviceTuningAttempt(hash, now.epochMs, now.elapsedMs, now.bootCount,
            DeviceTuningRecheckReason.INCOMPLETE_COMPARISON, cursor)), old.second)
    }

    fun markPending(binding: UnknownRuntimeBinding, reason: DeviceTuningRecheckReason?,
        cursor: RuntimeCandidateQueueSnapshot? = null): Boolean = synchronized(state) {
        val old = read()
        val attempt = old.first.attempt?.takeIf { it.bindingHash == binding.hashOrNull() } ?: return false
        write(old.first.copy(attempt = attempt.copy(pendingReason = reason, cursor = cursor)), old.second)
    }

    /** Caller checks its current flow immediately before this atomic commit and again before adoption. */
    fun save(binding: UnknownRuntimeBinding, profile: UnknownRuntimeProfile): Boolean = synchronized(state) {
        if (!profile.isValid(binding, Runtime.getRuntime().availableProcessors())) return false
        val old = read()
        val extra = JSONObject().put("policyVersion", profile.policyVersion)
            .put("primaryBaseline", loadJson(profile.primaryBaseline))
            .put("primarySelected", loadJson(profile.primarySelected))
        write(old.first.copy(profile = profile.runtime), extra)
    }

    private fun read(): Pair<DeviceTuningRecord, JSONObject?> {
        if (!state.loaded) {
            state.text = runCatching { preferences.getString("record", null) }.getOrNull()
            state.loaded = true
        }
        return runCatching {
            val root = JSONObject(requireNotNull(state.text).also { require(it.length <= 262_144) })
            require(root.getInt("schema") == 1)
            requireNotNull(DeviceTuningRecordCodec.decode(root.getString("runtime"))) to root.optJSONObject("primary")
        }.getOrDefault(DeviceTuningRecord() to null)
    }

    private fun write(record: DeviceTuningRecord, primary: JSONObject?): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        val runtime = DeviceTuningRecordCodec.encode(record) ?: return false
        val encoded = JSONObject().put("schema", 1).put("runtime", runtime).put("primary", primary).toString()
        if (encoded.length > 262_144) return false
        val saved = runCatching { preferences.edit().putString("record", encoded).commit() }.getOrDefault(false)
        // Failed SharedPreferences commits can still replace its in-memory value.
        if (saved) { state.text = encoded; state.loaded = true }
        return saved
    }

    private fun clock() = DeviceTuningClockReading(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT).takeIf { it >= 0 } }.getOrNull())

    private fun loadJson(value: UnknownPrimaryLoad) = JSONObject().put("duration", value.durationMs)
        .put("latencies", JSONArray(value.captureToCompleteMs)).put("gap", value.longestCompletionGapMs)
        .put("stale", value.staleOrDropped)
        .put("latencyVariation", value.repeatedLatencyVariationMs).put("gapVariation", value.repeatedGapVariationMs)
        .put("rateVariation", value.repeatedRateVariationPerMs).put("staleVariation", value.repeatedStaleFractionVariation)

    private fun readLoad(value: JSONObject): UnknownPrimaryLoad {
        val times = value.getJSONArray("latencies")
        require(times.length() in 1..4096)
        return UnknownPrimaryLoad(value.getLong("duration"), List(times.length()) { times.getDouble(it) },
            value.getDouble("gap"), value.getInt("stale"), value.getDouble("latencyVariation"),
            value.getDouble("gapVariation"), value.getDouble("rateVariation"), value.getDouble("staleVariation"))
    }

    private class State { var loaded = false; var text: String? = null }
    companion object {
        private val states = WeakHashMap<SharedPreferences, State>()
        const val SCHEDULE_VERSION = "primary-owner-fast-independent-owner-post-single-flight-v1"
        fun createBinding(context: Context, config: TwoModelRuntimeConfig,
            primaryCandidate: RuntimeCandidate, scope: RuntimeFeatureScope): UnknownRuntimeBinding? = runCatching {
            val model = requireNotNull(config.unifiedWalksafe)
            require(config.primaryModelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY && model.inputSize == 768)
            val manifest = RuntimeCalibrationFixtures(context).loadManifest()
            UnknownRuntimeBinding(
                requireNotNull(model.artifactSha256), FastSamModelContract.SHA256,
                "fused-yuv-rgb-letterbox-float32-768-v1",
                "fast768-rgb-uint8-opencv413-bilinear-half-even-pad114-nhwc-f32-div255-v1",
                "${config.bundleVersion}:${model.outputFormat.configValue}:" +
                    "${model.outputTensorShape().contentToString()}:${model.nmsIouThreshold}:" +
                    "${model.maxDetections}:${model.classes}:${model.allowlist.sorted()}:${model.thresholds.toSortedMap()}",
                "fast768-37x12096-192x192x32-conf025-nms07-max100-crop-before-threshold-logit0-v1",
                primaryCandidate, SCHEDULE_VERSION,
                "${Build.FINGERPRINT}:${Build.SUPPORTED_ABIS.joinToString()}:${android.os.Process.is64Bit()}:${System.getProperty("os.arch")}",
                "${AndroidDeviceTuningProfileStore.RUNTIME_CONTRACT_VERSION}|fast768-strict-gpu-serialization-v1",
                "private-camera-tracking-primary-fast-mask-post-v1",
                manifest.version, manifest.sha256, scope,
            ).also { require(it.hashOrNull() != null) }
        }.getOrNull()
    }
}
