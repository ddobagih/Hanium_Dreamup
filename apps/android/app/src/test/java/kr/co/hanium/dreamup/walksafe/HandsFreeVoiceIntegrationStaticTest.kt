package kr.co.hanium.dreamup.walksafe

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeVoiceIntegrationStaticTest {
    private val mainActivity = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun modelPreparationRunsOnlyAfterPrivacyStartupIsReady() {
        val privacyReady = functionBlock("private fun completePrivacyStartupReadyIfForeground")

        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                privacyReady,
                "privacyStartupInspectionComplete = true",
                "onReady()",
                "prepareHandsFreeVoiceModel()",
            ),
        )
        assertEquals(
            1,
            Regex("""(?m)^\s*prepareHandsFreeVoiceModel\(\)\s*$""")
                .findAll(mainActivity)
                .count(),
        )
    }

    @Test
    fun foregroundServiceFollowsTheActiveWalkLifecycle() {
        val runtimeEligibility = functionBlock("private fun isWalkSessionRuntimeActive")
        val activate = functionBlock("private fun activateWalkSessionRuntime")
        val startAfterCameraRelease =
            functionBlock("private fun startWalkSessionRuntimeAfterCameraRelease")
        val start = functionBlock("private fun maybeStartHandsFreeVoiceService")
        val transition = functionBlock("private fun transitionWalkSession")
        val pause = functionBlock("override fun onPause")
        val destroy = functionBlock("override fun onDestroy")
        val close = functionBlock("private fun closeHandsFreeVoice")

        assertTrue(runtimeEligibility.contains("snapshot.state == WalkSessionState.ACTIVE"))
        assertTrue(activate.contains("startWalkSessionRuntimeAfterCameraRelease(runtimeEpoch)"))
        assertTrue(startAfterCameraRelease.contains("maybeStartHandsFreeVoiceService()"))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                start,
                "!isWalkSessionRuntimeActive()",
                "ContextCompat.startForegroundService(this, startIntent)",
            ),
        )
        assertTrue(transition.contains("transition.current.state != WalkSessionState.ACTIVE"))
        assertTrue(transition.contains("stopHandsFreeVoiceService()"))
        assertTrue(pause.contains("stopHandsFreeVoiceService()"))
        assertTrue(destroy.contains("closeHandsFreeVoice()"))
        assertTrue(close.contains("stopHandsFreeVoiceService()"))
    }

    @Test
    fun deviceCheckRecordsDisclosureAndVoiceCapabilityWithoutAnInteractiveProbe() {
        val deviceCheckStart =
            functionBlock("private fun startPostLoginDeviceCheckFromPrimaryAction")
        val orchestration = functionBlock("private fun maybeContinuePostLoginDeviceCheck")
        val observation = functionBlock("private fun currentPostLoginDeviceCheckObservation")
        val requiredPermissions =
            functionBlock("private fun requiredPostLoginDeviceCheckPermissions")

        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                deviceCheckStart,
                "acknowledgeHandsFreeVoiceDisclosure()",
                "beginPostLoginDeviceCheckFromUserAction(sessionBinding)",
            ),
        )
        assertFalse(deviceCheckStart.contains("AlertDialog"))
        assertFalse(orchestration.contains("maybeStartPostLoginWakePhraseProbe("))
        assertFalse(orchestration.contains("beginPostLoginWakePhraseProbeFromUserAction("))
        assertTrue(
            observation.contains(
                "wakePhraseRecognition = speechRecognitionAvailable.toDeviceCheckSignal()",
            ),
        )
        assertTrue(requiredPermissions.contains("Manifest.permission.RECORD_AUDIO"))
        assertTrue(requiredPermissions.contains("Manifest.permission.POST_NOTIFICATIONS"))
        val start = functionBlock("private fun maybeStartHandsFreeVoiceService")
        assertTrue(start.contains("isHandsFreeVoiceDisclosureAccepted()"))
        assertTrue(start.contains("voice_hands_free=disclosure_required"))
        assertTrue(start.contains("hasHandsFreeNotificationPermission()"))
        assertTrue(start.contains("voice_hands_free=notification_permission_required"))
        val observedPermissions = functionBlock("private fun applyObservedPermissionStateChange")
        assertTrue(
            observedPermissions.contains(
                "if (!hasHandsFreeNotificationPermission() || !hasRecordAudioPermission())",
            ),
        )
    }

    @Test
    fun handsFreeTranscriptReusesTheExistingHandlerWithoutDisplayingRawText() {
        val attach = functionBlock("private fun attachHandsFreeVoiceController")
        val commandCallback = ReportStaticSourceInspector.blockAfter(
            attach,
            "onCommand = command@",
        )

        assertTrue(commandCallback.contains("handleVoiceCommandPhrases("))
        assertTrue(commandCallback.contains("floatArrayOf(confidence ?: Float.NaN)"))
        assertTrue(commandCallback.contains("includeRecognizedTextInStatus = false"))
        assertTrue(commandCallback.contains("redactedRecognitionSource = \"on_device_hands_free\""))
        assertFalse(commandCallback.contains("updateNavigationStatus("))
        assertFalse(commandCallback.contains("toStatusToken("))
    }

    @Test
    fun staleStartupAndTerminalErrorsCannotLeaveAFalseListeningService() {
        val controller = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/HandsFreeVoiceController.kt",
        ).readText()

        assertTrue(controller.contains("if (closed || !starting) return"))
        assertEquals(2, Regex("runCatching\\(onTerminalError\\)").findAll(controller).count())
        assertTrue(mainActivity.contains("onTerminalError = ::handleHandsFreeVoiceTerminalError"))
        val terminalHandler = functionBlock("private fun handleHandsFreeVoiceTerminalError")
        assertTrue(terminalHandler.contains("handsFreeVoiceServiceRequested = false"))
        assertTrue(terminalHandler.contains("stopService("))
    }

    @Test
    fun pcmStaysInMemoryAndNativeDecoderCloseIsSerializedWithInference() {
        val pcmSource = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/AndroidPcmAudioSource.kt",
        ).readText()
        val transcriber = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/VoskStreamingTranscriber.kt",
        ).readText()

        assertTrue(pcmSource.contains("const val SAMPLE_RATE_HZ = 16_000"))
        assertFalse(pcmSource.contains("FileOutputStream"))
        assertFalse(pcmSource.contains("preRoll"))
        assertTrue(transcriber.contains("synchronized(decoderLock)"))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                functionBlockFrom(transcriber, "private fun releaseResources"),
                "resources.audioSource?.stop()",
                "synchronized(decoderLock)",
                "resources.recognizer?.close()",
                "resources.model?.close()",
            ),
        )
    }

    @Test
    fun manifestAndBuildDeclareTheMicrophoneForegroundServiceRuntime() {
        val manifest = parseXml(File("src/main/AndroidManifest.xml"))
        val permissions = manifest.getElementsByTagName("uses-permission")
        val permissionNames = buildSet {
            for (index in 0 until permissions.length) {
                add(permissions.item(index).androidAttribute("name"))
            }
        }
        assertTrue("android.permission.RECORD_AUDIO" in permissionNames)
        assertTrue("android.permission.FOREGROUND_SERVICE" in permissionNames)
        assertTrue("android.permission.FOREGROUND_SERVICE_MICROPHONE" in permissionNames)

        val application = manifest.getElementsByTagName("application").item(0)
        assertEquals(".voice.WalkSafeApplication", application.androidAttribute("name"))
        val services = manifest.getElementsByTagName("service")
        val voiceService = (0 until services.length)
            .map(services::item)
            .single { it.androidAttribute("name") == ".voice.WalkVoiceForegroundService" }
        assertEquals("false", voiceService.androidAttribute("exported"))
        assertEquals("microphone", voiceService.androidAttribute("foregroundServiceType"))

        val build = File("build.gradle.kts").readText()
        assertTrue(build.contains("implementation(\"com.alphacephei:vosk-android:0.3.75\")"))
        assertTrue(build.contains("verifyBundledVoskModelAssets"))
        assertTrue(build.contains("name == \"mergeDebugAssets\""))
    }

    private fun functionBlock(signature: String): String =
        ReportStaticSourceInspector.functionBlock(mainActivity, signature)

    private fun functionBlockFrom(source: String, signature: String): String =
        ReportStaticSourceInspector.functionBlock(source, signature)

    private fun org.w3c.dom.Node.androidAttribute(name: String): String =
        attributes.getNamedItemNS(ANDROID_NAMESPACE, name).nodeValue

    private fun parseXml(file: File) =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }.newDocumentBuilder().parse(file)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
