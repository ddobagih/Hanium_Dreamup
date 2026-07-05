package kr.co.hanium.dreamup.walksafe.inference

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TwoModelRuntimeConfig(
    val runtimeSourceModel: String?,
    val primaryModelKey: String,
    val fallbackModelKey: String?,
    val unifiedWalksafe: ModelRuntimeConfig?,
    val customTactile: ModelRuntimeConfig?,
    val cocoGeneral: ModelRuntimeConfig?,
) {
    fun sourceModelForReportModel(modelKey: String?): String? {
        val config = when (modelKey?.trim()?.lowercase()) {
            UNIFIED_MODEL_KEY -> unifiedWalksafe
            LEGACY_TWO_MODEL_KEY,
            "custom_tactile" -> customTactile
            else -> null
        } ?: return runtimeSourceModel
        return config.sourceModel ?: runtimeSourceModel
    }

    fun thresholdForReportClass(modelKey: String?, className: String): Float? {
        val config = when (modelKey?.trim()?.lowercase()) {
            UNIFIED_MODEL_KEY -> unifiedWalksafe
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

        fun load(context: Context): TwoModelRuntimeConfig {
            val json = context.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
            return parse(json)
        }

        fun parse(json: String): TwoModelRuntimeConfig {
            val root = JSONObject(json)
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
            }
            if (primaryModelKey == UNIFIED_MODEL_KEY) {
                require(unifiedWalksafe != null) { "unified_walksafe model config is required for unified primary runtime" }
            }
            return TwoModelRuntimeConfig(
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
            val enabled = json.optBoolean("enabled", true)
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
            return ModelRuntimeConfig(
                key = modelKey,
                asset = asset,
                inputSize = inputSize,
                classes = classes,
                allowlist = allowlist,
                thresholds = thresholds,
                sourceModel = sourceModel,
                runtime = parseRuntime(json.optJSONObject("runtime")),
                enabled = enabled,
            )
        }

        private fun parseRuntime(json: JSONObject?): ModelRuntimeOptions {
            if (json == null) return ModelRuntimeOptions()
            val delegate = json.optString("delegate", ModelRuntimeOptions.DEFAULT_DELEGATE).lowercase()
            val numThreads = json.optInt("num_threads", ModelRuntimeOptions.DEFAULT_NUM_THREADS)
            val fallbackToCpu = json.optBoolean("fallback_to_cpu", true)
            require(delegate in ModelRuntimeOptions.SUPPORTED_DELEGATES) {
                "runtime.delegate must be one of ${ModelRuntimeOptions.SUPPORTED_DELEGATES}"
            }
            require(numThreads in 1..8) { "runtime.num_threads must be between 1 and 8" }
            return ModelRuntimeOptions(
                delegate = delegate,
                numThreads = numThreads,
                fallbackToCpu = fallbackToCpu,
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
    val runtime: ModelRuntimeOptions = ModelRuntimeOptions(),
    val enabled: Boolean = true,
) {
    fun classNameForId(classId: Int): String? = classes.getOrNull(classId)

    fun isAllowedClass(className: String): Boolean {
        return allowlist.isEmpty() || className.lowercase() in allowlist
    }

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
) {
    companion object {
        const val DEFAULT_DELEGATE = "cpu"
        const val DEFAULT_NUM_THREADS = 4
        val SUPPORTED_DELEGATES = setOf("cpu", "nnapi")
    }
}
