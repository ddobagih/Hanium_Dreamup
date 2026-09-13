package kr.co.hanium.dreamup.walksafe.inference

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Validated model-selection contract loaded from assets. Parsing checks configuration shape; model
 * asset availability and delegate fallback are resolved later by the detector loader.
 */
data class TwoModelRuntimeConfig(
    val bundleVersion: String,
    val runtimeSourceModel: String?,
    val primaryModelKey: String,
    val fallbackModelKey: String?,
    val unifiedWalksafe: ModelRuntimeConfig?,
    val customTactile: ModelRuntimeConfig?,
    val cocoGeneral: ModelRuntimeConfig?,
) {
    // Legacy pair loading can create its models even when their enabled flags are false.
    internal val requiresGpuThreadOwner: Boolean
        get() = listOfNotNull(unifiedWalksafe, customTactile, cocoGeneral)
            .any { it.runtime.delegate == "gpu" }

    /** Report identity preserves raw WalkMate class IDs while runtime selection keeps its logical key. */
    fun modelKeyForReportClass(className: String): String? {
        val name = className.lowercase()
        val model = unifiedWalksafe
        if (primaryModelKey == UNIFIED_MODEL_KEY && model?.enabled == true && name in model.classes) {
            return if (model.outputFormat.isRaw &&
                name in setOf("damaged_linear_tactile_paving", "damaged_dot_tactile_paving")) {
                WALKMATE_REPORT_MODEL_KEY
            } else primaryModelKey
        }
        val legacySelected = primaryModelKey == LEGACY_TWO_MODEL_KEY || fallbackModelKey == LEGACY_TWO_MODEL_KEY
        return if (legacySelected && (customTactile?.classes?.contains(name) == true ||
            cocoGeneral?.classes?.contains(name) == true)) LEGACY_TWO_MODEL_KEY else null
    }

    fun sourceModelForReportModel(modelKey: String?): String? {
        val config = when (modelKey?.trim()?.lowercase()) {
            UNIFIED_MODEL_KEY, WALKMATE_REPORT_MODEL_KEY -> unifiedWalksafe
            LEGACY_TWO_MODEL_KEY,
            "custom_tactile" -> customTactile
            else -> null
        } ?: return runtimeSourceModel
        return config.sourceModel ?: runtimeSourceModel
    }

    fun thresholdForReportClass(modelKey: String?, className: String): Float? {
        val config = when (modelKey?.trim()?.lowercase()) {
            UNIFIED_MODEL_KEY, WALKMATE_REPORT_MODEL_KEY -> unifiedWalksafe
            LEGACY_TWO_MODEL_KEY,
            "custom_tactile" -> customTactile
            else -> null
        } ?: return null
        return config.thresholdForClass(className)
    }

    companion object {
        private const val CONFIG_ASSET = "model-config/two_model_runtime.json"
        const val LEGACY_TWO_MODEL_KEY = "legacy_two_model"
        const val UNIFIED_MODEL_KEY = "unified_walksafe"
        const val WALKMATE_REPORT_MODEL_KEY = "walkmate_21cls"

        fun load(context: Context): TwoModelRuntimeConfig {
            val json = context.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
            val config = parse(json)
            val unified = config.unifiedWalksafe?.let { model ->
                if (!model.enabled || assetMatches(context, model)) model else model.copy(enabled = false)
            }
            return config.copy(unifiedWalksafe = unified)
        }

        fun parse(json: String): TwoModelRuntimeConfig {
            val root = JSONObject(json)
            val bundleVersion = root.get("version") as? String
                ?: throw IllegalArgumentException("version must be a string bundleVersion")
            require(bundleVersion.isNotBlank() && bundleVersion == bundleVersion.trim()) {
                "version must be a trimmed non-blank bundleVersion"
            }
            val models = root.getJSONObject("models")
            val runtimeSourceModel = root.optString("source_model").ifBlank { null }
            val primaryModelKey = root.optString("primary_model", UNIFIED_MODEL_KEY)
            require(primaryModelKey in setOf(LEGACY_TWO_MODEL_KEY, UNIFIED_MODEL_KEY)) {
                "primary_model must be $LEGACY_TWO_MODEL_KEY or $UNIFIED_MODEL_KEY"
            }
            val fallbackModelKey = if (root.has("fallback_model") && root.isNull("fallback_model")) {
                null
            } else {
                root.optString("fallback_model", LEGACY_TWO_MODEL_KEY).ifBlank { null }
            }
            require(fallbackModelKey == null || fallbackModelKey == LEGACY_TWO_MODEL_KEY) {
                "fallback_model must be absent or $LEGACY_TWO_MODEL_KEY"
            }
            val unifiedWalksafe = models.optJSONObject(UNIFIED_MODEL_KEY)
                ?.let { parseModel(UNIFIED_MODEL_KEY, it, allowlistRequired = true) }
            val customTactile = models.optJSONObject("custom_tactile")
                ?.let { parseModel("custom_tactile", it, allowlistRequired = false) }
            val cocoGeneral = models.optJSONObject("coco_general")
                ?.let { parseModel("coco_general", it, allowlistRequired = true) }
            if (primaryModelKey == LEGACY_TWO_MODEL_KEY || fallbackModelKey == LEGACY_TWO_MODEL_KEY) {
                require(customTactile != null) { "custom_tactile model config is required for legacy two-model runtime" }
                require(cocoGeneral != null) { "coco_general model config is required for legacy two-model runtime" }
                requireArtifactHash(customTactile)
                requireArtifactHash(cocoGeneral)
            }
            if (primaryModelKey == UNIFIED_MODEL_KEY) {
                require(unifiedWalksafe != null) { "unified_walksafe model config is required for unified primary runtime" }
            }
            return TwoModelRuntimeConfig(
                bundleVersion = bundleVersion,
                runtimeSourceModel = runtimeSourceModel,
                primaryModelKey = primaryModelKey,
                fallbackModelKey = fallbackModelKey,
                unifiedWalksafe = unifiedWalksafe,
                customTactile = customTactile,
                cocoGeneral = cocoGeneral,
            )
        }

        private fun parseModel(modelKey: String, json: JSONObject, allowlistRequired: Boolean): ModelRuntimeConfig {
            val classes = json.optJSONArray("classes").toStringList()
            val allowlist = json.optJSONArray("allowlist").toStringSet()
            val thresholds = json.getJSONObject("thresholds").toFloatMap()
            val inputSize = json.getInt("input_size")
            val asset = json.getString("asset")
            val sourceModel = json.optString("source_model").ifBlank { null }
            val artifactSha256 = json.optString("artifact_sha256").ifBlank { null }
            val sourceModelSha256 = json.optString("source_model_sha256").ifBlank { null }
            val enabled = json.optBoolean("enabled", true)
            val outputFormat = YoloOutputFormat.fromConfig(json.optString("output_format", YoloOutputFormat.END_TO_END_XYXY.configValue))
            if (modelKey == UNIFIED_MODEL_KEY && outputFormat.isRaw) {
                require(classes == TwoModelClassMap.unifiedWalksafeClasses) { "WalkMate class order must match the exported checkpoint" }
            }
            val maxDetections = if (json.has("max_detections")) {
                val value = json.get("max_detections")
                require((value is Int || value is Long) && (value as Number).toLong() in 1L..300L) {
                    "max_detections must be an integer between 1 and 300"
                }
                (value as Number).toInt()
            } else 300
            require(asset.startsWith("models/") && ".." !in asset) { "asset must be a relative models/ path" }
            require(inputSize > 0) { "input_size must be positive" }
            require(classes.isNotEmpty()) { "classes must not be empty" }
            require(classes.size == classes.toSet().size) { "classes must not contain duplicates" }
            require(thresholds.values.all { it in 0f..1f }) { "thresholds must be between 0 and 1" }
            if (allowlistRequired) {
                require(allowlist.isNotEmpty()) { "allowlist must not be empty" }
                require(allowlist.all { it in classes.map(String::lowercase).toSet() }) {
                    "allowlist must be a subset of classes"
                }
            }
            if (modelKey == UNIFIED_MODEL_KEY && enabled) {
                require(sourceModel != null) { "unified_walksafe source_model is required" }
                require(artifactSha256?.matches(SHA256_PATTERN) == true) {
                    "unified_walksafe artifact_sha256 must be 64 lowercase hex characters"
                }
                require(sourceModelSha256?.matches(SHA256_PATTERN) == true) {
                    "unified_walksafe source_model_sha256 must be 64 lowercase hex characters"
                }
            }
            return ModelRuntimeConfig(
                key = modelKey,
                asset = asset,
                inputSize = inputSize,
                classes = classes,
                allowlist = allowlist,
                thresholds = thresholds,
                sourceModel = sourceModel,
                artifactSha256 = artifactSha256,
                sourceModelSha256 = sourceModelSha256,
                runtime = parseRuntime(json.optJSONObject("runtime")),
                outputFormat = outputFormat,
                nmsIouThreshold = json.optDouble("nms_iou_threshold", 0.7).toFloat(),
                maxDetections = maxDetections,
                enabled = enabled,
            )
        }

        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

        private fun requireArtifactHash(model: ModelRuntimeConfig) {
            require(model.artifactSha256?.matches(SHA256_PATTERN) == true) {
                "${model.key} artifact_sha256 must be 64 lowercase hex characters"
            }
        }

        internal fun assetMatches(context: Context, model: ModelRuntimeConfig): Boolean {
            val expectedSha256 = model.artifactSha256 ?: return false
            return runCatching {
                val actualSha256 = context.assets.open(model.asset).use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                }
                actualSha256 == expectedSha256
            }.getOrDefault(false)
        }

        private fun parseRuntime(json: JSONObject?): ModelRuntimeOptions {
            if (json == null) return ModelRuntimeOptions()
            val delegate = json.optString("delegate", ModelRuntimeOptions.DEFAULT_DELEGATE).lowercase()
            val numThreads = if (!json.has("num_threads")) {
                ModelRuntimeOptions.DEFAULT_NUM_THREADS
            } else {
                // optInt truncates fractions and wraps overflowing Long values on Android.
                val rawThreads = json.get("num_threads")
                require(rawThreads is Int || rawThreads is Long) {
                    "runtime.num_threads must be a positive integer"
                }
                val exactThreads = (rawThreads as Number).toLong()
                require(exactThreads in 1L..Int.MAX_VALUE.toLong()) {
                    "runtime.num_threads must be a positive integer within Int range"
                }
                exactThreads.toInt()
            }
            val fallbackToCpu = json.optBoolean("fallback_to_cpu", true)
            val gpuPrecisionLossAllowed = json.optBoolean("gpu_precision_loss_allowed", false)
            val gpuSerializationCacheEnabled = json.optBoolean("gpu_serialization_cache_enabled", true)
            require(delegate in ModelRuntimeOptions.SUPPORTED_DELEGATES) {
                "runtime.delegate must be one of ${ModelRuntimeOptions.SUPPORTED_DELEGATES}"
            }
            require(numThreads > 0) { "runtime.num_threads must be positive" }
            return ModelRuntimeOptions(
                delegate = delegate,
                numThreads = numThreads,
                fallbackToCpu = fallbackToCpu,
                gpuPrecisionLossAllowed = gpuPrecisionLossAllowed,
                gpuSerializationCacheEnabled = gpuSerializationCacheEnabled,
            )
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return List(length()) { index -> getString(index) }
        }

        private fun JSONArray?.toStringSet(): Set<String> {
            if (this == null) return emptySet()
            return toStringList().map { it.lowercase() }.toSet()
        }

        private fun JSONObject.toFloatMap(): Map<String, Float> {
            val values = mutableMapOf<String, Float>()
            val keys = keys()
            while (keys.hasNext()) {
                val key = keys.next()
                values[key] = getDouble(key).toFloat()
            }
            return values
        }
    }
}

data class ModelRuntimeConfig(
    val key: String,
    val asset: String,
    val inputSize: Int,
    val classes: List<String>,
    val allowlist: Set<String>,
    val thresholds: Map<String, Float>,
    val sourceModel: String? = null,
    val artifactSha256: String? = null,
    val sourceModelSha256: String? = null,
    val runtime: ModelRuntimeOptions = ModelRuntimeOptions(),
    val enabled: Boolean = true,
    val outputFormat: YoloOutputFormat = YoloOutputFormat.END_TO_END_XYXY,
    val nmsIouThreshold: Float = 0.7f,
    val maxDetections: Int = 300,
) {
    init {
        require(nmsIouThreshold.isFinite() && nmsIouThreshold in 0f..1f) { "nms_iou_threshold must be between 0 and 1" }
        require(maxDetections in 1..300) { "max_detections must be between 1 and 300" }
        if (outputFormat.isRaw) {
            require(inputSize in 32..2048 && inputSize % 32 == 0) { "raw YOLO input size must be a multiple of 32" }
        }
    }

    fun outputTensorShape(): IntArray = when (outputFormat) {
        YoloOutputFormat.END_TO_END_XYXY -> intArrayOf(1, 300, 6)
        YoloOutputFormat.RAW_XYWH_PIXELS, YoloOutputFormat.RAW_XYWH_NORMALIZED -> intArrayOf(1, 4 + classes.size,
            listOf(8, 16, 32).sumOf { stride -> (inputSize / stride) * (inputSize / stride) })
    }

    fun classNameForId(classId: Int): String? = classes.getOrNull(classId)

    fun isAllowedClass(className: String): Boolean {
        return allowlist.isEmpty() || className.lowercase() in allowlist
    }

    /** Missing class/default thresholds fail closed so an unconfigured class cannot be emitted. */
    fun thresholdForClass(className: String): Float {
        return thresholds[className]
            ?: thresholds[className.lowercase()]
            ?: thresholds["default"]
            ?: FAIL_CLOSED_THRESHOLD
    }

    private companion object {
        const val FAIL_CLOSED_THRESHOLD = 1.0f
    }
}

data class ModelRuntimeOptions(
    val delegate: String = DEFAULT_DELEGATE,
    val numThreads: Int = DEFAULT_NUM_THREADS,
    val fallbackToCpu: Boolean = true,
    val gpuPrecisionLossAllowed: Boolean = false,
    val gpuSerializationCacheEnabled: Boolean = true,
) {
    init {
        require(delegate in SUPPORTED_DELEGATES) { "unsupported runtime delegate: $delegate" }
        require(numThreads > 0) { "runtime.num_threads must be positive" }
    }

    companion object {
        const val DEFAULT_DELEGATE = "cpu"
        const val DEFAULT_NUM_THREADS = 4
        val SUPPORTED_DELEGATES = setOf("cpu", "gpu", "nnapi")
    }
}

/** Output layouts are explicit; a different model must never be guessed from tensor values. */
enum class YoloOutputFormat(val configValue: String) {
    END_TO_END_XYXY("yolo_end_to_end_xyxy"),
    RAW_XYWH_PIXELS("yolo_raw_xywh_pixels"),
    RAW_XYWH_NORMALIZED("yolo_raw_xywh_normalized");

    val isRaw: Boolean get() = this != END_TO_END_XYXY

    companion object {
        fun fromConfig(value: String): YoloOutputFormat = values().firstOrNull { it.configValue == value }
            ?: throw IllegalArgumentException("unsupported YOLO output_format: $value")
    }
}
