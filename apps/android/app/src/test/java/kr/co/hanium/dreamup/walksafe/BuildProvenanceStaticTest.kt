package kr.co.hanium.dreamup.walksafe

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BuildProvenanceStaticTest {
    private val buildScript = File("build.gradle.kts").readText()
    private val mainSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val reportSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportCandidatePolicy.kt").readText()
    private val fieldLogSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/FieldSessionLog.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun releaseRequiresFortyHexCommitWhileDebugIsExplicitlyUnverified() {
        assertTrue(buildScript.contains("WALKSAFE_SOURCE_COMMIT"))
        assertTrue(buildScript.contains("^[0-9a-fA-F]{40}$"))
        assertTrue(buildScript.contains("preReleaseBuild"))
        assertTrue(buildScript.contains("verifiedSourceCommit ?: \"unverified\""))
    }

    @Test
    fun runtimeReportAndFieldManifestReferenceBuildCommit() {
        assertTrue(mainSource.contains("BuildConfig.WALKSAFE_SOURCE_COMMIT"))
        assertTrue(reportSource.contains(".put(\"source_commit\", input.sourceCommit)"))
        assertTrue(fieldLogSource.contains("provenance.put(\"source_commit\", deviceInfo.sourceCommit)"))
    }

    @Test
    fun releaseKeepsFieldLoggerAndControlsUnavailable() {
        val initialization = mainSource
            .substringAfter("fieldSessionLog = if (BuildConfig.DEBUG)")
            .substringBefore("fieldSessionLog.recordEvent(\"app_created\")")
        val toggle = mainSource
            .substringAfter("private fun toggleFieldSessionLog()")
            .substringBefore("private fun syncActiveSessionScreenPolicy()")
        val button = mainSource
            .substringAfter("private fun updateFieldSessionLogButton()")
            .substringBefore("private fun closeDetectorAsync()")

        assertTrue(initialization.contains("PersistentFieldSessionLog("))
        assertTrue(initialization.contains("NoopFieldSessionLog()"))
        assertTrue(toggle.contains("if (!BuildConfig.DEBUG) return"))
        assertTrue(button.contains("if (!BuildConfig.DEBUG)"))
        assertTrue(button.contains("fieldSessionLogButton.visibility = View.GONE"))
        assertTrue(button.contains("fieldSessionLogButton.isEnabled = false"))
    }

    @Test
    fun runtimeModelConfigUsesAssetHashValidationBeforeDetectorLoad() {
        assertTrue(mainSource.contains("TwoModelRuntimeConfig.load(this)"))
        assertFalse(mainSource.contains("TwoModelRuntimeConfig.parse(runtimeJson)"))
    }

    @Test
    fun releaseRequiresExactGatewayOriginAndEmbedsReleaseMarker() {
        assertTrue(buildScript.contains("WALKSAFE_GATEWAY_ORIGIN"))
        assertTrue(buildScript.contains("normalizedHttpsOriginOrNull"))
        assertTrue(buildScript.contains("walksafe-release-v1"))
        assertTrue(mainSource.contains("GatewayEndpointPolicy.approvedReleaseOriginOrNull"))
        assertTrue(mainSource.contains("isEnabled = BuildConfig.DEBUG"))
    }

    @Test
    fun appDataAndDebugFieldLogsAreExcludedFromBackupAndDeviceTransfer() {
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))

        val expectedExcludes = setOf(
            "root" to ".",
            "file" to ".",
            "database" to ".",
            "sharedpref" to ".",
            "external" to ".",
            "device_root" to ".",
            "device_file" to ".",
            "device_database" to ".",
            "device_sharedpref" to ".",
        )
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/res/xml/data_extraction_rules.xml"))

        listOf("cloud-backup", "device-transfer").forEach { sectionName ->
            val sections = document.getElementsByTagName(sectionName)
            assertEquals("exactly one $sectionName section", 1, sections.length)
            val children = sections.item(0).childNodes
            val actualExcludes = buildList {
                for (index in 0 until children.length) {
                    val child = children.item(index)
                    if (child is Element && child.tagName == "exclude") {
                        add(child.getAttribute("domain") to child.getAttribute("path"))
                    }
                }
            }
            assertEquals("$sectionName exclude count", expectedExcludes.size, actualExcludes.size)
            assertEquals("$sectionName excludes", expectedExcludes, actualExcludes.toSet())
        }
    }

    @Test
    fun androidRuntimeUsesThe16KbAlignedLiteRtInterpreterArtifact() {
        assertTrue(buildScript.contains("com.google.ai.edge.litert:litert:1.4.0"))
        assertFalse(buildScript.contains("org.tensorflow:tensorflow-lite:2.17.0"))
    }
}
