package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S10-36: the release APK carries only the models of the approved runtime bundle (FP-037/039).
 * The Gradle transform result itself is checked by `:app:filterReleaseModelAssets` (Root).
 */
class ReleaseModelAssetAllowlistStaticTest {
    private val build = File("build.gradle.kts").readText()

    private fun allowlist(): Set<String> = Regex(""""(models/[^"]+)"""")
        .findAll(build.substringAfter("val releaseModelAssetAllowlist = setOf(").substringBefore(")"))
        .map { it.groupValues[1] }
        .toSet()

    @Test
    fun allowlistIsExactlyTheApprovedPrimaryAndUnknownObjectModels() {
        val runtime = JSONObject(File("src/main/assets/model-config/two_model_runtime.json").readText())
        assertEquals("unified_walksafe", runtime.getString("primary_model"))
        assertTrue("release never falls back to the legacy pair", runtime.isNull("fallback_model"))
        val primary = runtime.getJSONObject("models").getJSONObject("unified_walksafe").getString("asset")
        val fastSam = JSONObject(File("src/main/assets/model-config/fastsam_s_768_contract.json").readText()).getString("asset")
        assertEquals(setOf(primary, fastSam), allowlist())
        allowlist().forEach { assertTrue(it, File("src/main/assets", it).isFile) }
        // Legacy (custom/coco) and the unreferenced yolo26n candidates are not release assets.
        val present = File("src/main/assets/models").list().orEmpty().map { "models/$it" }.toSet()
        assertTrue(present.containsAll(allowlist()))
        assertTrue((present - allowlist()).all { it.contains("yolo11") || it.contains("yolo26") || it.contains("custom_tactile") || it.contains("coco") })
    }

    @Test
    fun onlyTheReleaseVariantTransformsItsAssetsAndTheFilterFailsClosed() {
        val release = build.substringAfter("onVariants(selector().withBuildType(\"release\")) { variant ->")
            .substringBefore("\n}\n")
        assertTrue(release.contains("variant.artifacts.use(filter)"))
        assertTrue(release.contains(".toTransform(SingleArtifact.ASSETS)"))
        assertTrue(release.contains("allowedModelAssets.set(releaseModelAssetAllowlist)"))
        val task = build.substringAfter("abstract class FilterReleaseModelAssetsTask : DefaultTask() {")
            .substringBefore("\nandroidComponents {")
        assertTrue(task.contains("if (relative.startsWith(\"models/\") && relative !in allowed) return@forEach"))
        assertTrue(task.contains("check(kept == allowed.toSortedSet())"))
    }

    @Test
    fun theReleaseFilterFailsWhenTheAllowlistDriftsFromThePackagedModelConfigs() {
        // S10-36-F1: the allowlist is checked against the merged release assets' own configs
        // before anything is copied, so a config change cannot silently drop a loaded model.
        val task = build.substringAfter("abstract class FilterReleaseModelAssetsTask : DefaultTask() {")
            .substringBefore("\nandroidComponents {")
        val action = task.substringAfter("fun filter() {").substringBefore("\n    }\n")
        val configured = action.indexOf("val configured = configuredReleaseModelAssets(source)")
        val compared = action.indexOf("check(configured == allowed.toSortedSet())")
        assertTrue(configured >= 0 && compared > configured)
        assertTrue(compared < action.indexOf("source.walkTopDown()"))
        val helper = task.substringAfter("private fun configuredReleaseModelAssets(assets: File)")
        listOf(
            "\"model-config/two_model_runtime.json\"",
            "runtime[\"primary_model\"] ?: error(",
            "runtime[\"fallback_model\"]",
            "listOfNotNull(",
            "\"model-config/fastsam_s_768_contract.json\"",
            "[\"asset\"]",
        ).forEach { assertTrue(it, helper.contains(it)) }
        assertTrue(build.contains("import groovy.json.JsonSlurper"))
        // Debug (and androidTest, which runs on debug) keeps every asset.
        val debug = build.substringAfter("onVariants(selector().withBuildType(\"debug\")) { variant ->")
            .substringBefore("assembleDebugTasks.configureEach")
        assertFalse(debug.contains("SingleArtifact.ASSETS"))
        assertEquals(1, Regex("""toTransform\(SingleArtifact\.ASSETS\)""").findAll(build).count())
    }
}
