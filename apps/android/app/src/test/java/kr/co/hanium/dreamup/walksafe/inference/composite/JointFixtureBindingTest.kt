package kr.co.hanium.dreamup.walksafe.inference.composite

import java.io.File
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCalibrationFixtures
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.YoloOutputFormat
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JointFixtureBindingTest {
    private val f = JointRuntimeTestFixtures

    @Test fun modelConfigurationAndActualDeviceRuntimeAndFixtureIdentitiesAllAffectBinding() {
        val model = requireNotNull(f.config.unifiedWalksafe)
        val original = requireNotNull(binding())
        listOf(
            f.config.copy(bundleVersion = "next-bundle"),
            f.config.copy(unifiedWalksafe = model.copy(artifactSha256 = "b".repeat(64))),
            f.config.copy(unifiedWalksafe = model.copy(classes = model.classes.reversed())),
            f.config.copy(unifiedWalksafe = model.copy(thresholds = mapOf("default" to .6f))),
            f.config.copy(unifiedWalksafe = model.copy(runtime = model.runtime.copy(numThreads = 3))),
            f.config.copy(unifiedWalksafe = model.copy(runtime = model.runtime.copy(delegate = "gpu"))),
            f.config.copy(unifiedWalksafe = model.copy(outputFormat = YoloOutputFormat.END_TO_END_XYXY_PIXELS)),
        ).forEach { assertNotEquals(original, binding(it)) }
        assertNotEquals(original, binding(os = "new-os"))
        assertNotEquals(original, binding(hardware = "new-hardware"))
        assertNotEquals(original, binding(abis = listOf("x86_64")))
        assertNotEquals(original, binding(process64Bit = false))
        assertNotEquals(original, binding(processors = 4))
        assertNotEquals(original, binding(runtime = "new-runtime"))
        assertNotEquals(original, binding(fixtureVersion = "next-fixtures"))
        assertNotEquals(original, binding(fixtureHash = "c".repeat(64)))
    }

    @Test fun incompleteModelOrWrongTensorAndPrecisionContractsCannotProduceBinding() {
        val model = requireNotNull(f.config.unifiedWalksafe)
        listOf(
            f.config.copy(unifiedWalksafe = null),
            f.config.copy(unifiedWalksafe = model.copy(enabled = false)),
            f.config.copy(unifiedWalksafe = model.copy(artifactSha256 = null)),
            f.config.copy(unifiedWalksafe = model.copy(inputSize = 640)),
            f.config.copy(unifiedWalksafe = model.copy(runtime = model.runtime.copy(gpuPrecisionLossAllowed = true))),
            f.config.copy(unifiedWalksafe = model.copy(outputFormat = YoloOutputFormat.RAW_XYWH_PIXELS)),
        ).forEach { assertNull(binding(it)) }
        assertNull(binding(processors = 0))
        assertNull(binding(abis = emptyList()))
        assertNull(binding(os = ""))
    }

    @Test fun pinnedFourImageCoverageAndCurrentSharedScooterClassIdsMatchTheCheckedAssets() {
        val manifest = JSONObject(File("src/main/assets/runtime-calibration/manifest.json").readText())
        val entries = manifest.getJSONArray("fixtures")
        val required = (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { it.getBoolean("required") }.map { it.getString("id") }.toSet()
        assertEquals(JointRuntimeChoicePolicy.REQUIRED_FIXTURE_IDS, required)
        val current = TwoModelRuntimeConfig.parse(File("src/main/assets/model-config/two_model_runtime.json").readText())
        assertNotNull(binding(current))
        val model = requireNotNull(current.unifiedWalksafe)
        assertNull(binding(current.copy(unifiedWalksafe = model.copy(classIdThresholds = model.classIdThresholds - 9))))
    }

    private fun binding(config: TwoModelRuntimeConfig = f.config, os: String = "os", hardware: String = "hardware",
        abis: List<String> = listOf("arm64-v8a"), process64Bit: Boolean = true, processors: Int = 8,
        runtime: String = "runtime", fixtureVersion: String = RuntimeCalibrationFixtures.VERSION,
        fixtureHash: String = RuntimeCalibrationFixtures.MANIFEST_SHA256,
    ) = JointFixtureBinding.create(config, os, hardware, abis, process64Bit, "aarch64", processors,
        runtime, fixtureVersion, fixtureHash)
}
