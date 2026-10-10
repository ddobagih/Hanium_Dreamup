package kr.co.hanium.dreamup.walksafe.network

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceAction
import kr.co.hanium.dreamup.walksafe.navigation.selectAndroidVoiceAction
import kr.co.hanium.dreamup.walksafe.navigation.VoiceHelpPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real Gateway/Whisper/Android parsing check. Synthetic AAC, NOT a microphone accuracy test.
 * The host supplies fixed, non-personal speech files in cache/live-stt-fixtures before execution.
 * No recognized command is executed; destinations, consent and safety readiness are unchanged.
 */
@RunWith(AndroidJUnit4::class)
class LiveServerSpeechDeviceTest {
    /** Fixed synthetic speaker input through the real microphone; no raw text/audio retained. */
    @Test fun actualMicrophoneSeparatesTranscriptionFromCommandSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowLiveAcousticStt") == "true")
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val directory = File(instrumentation.targetContext.cacheDir, "acoustic-stt-diagnostic").apply { mkdirs() }
        var recorder: kr.co.hanium.dreamup.walksafe.feedback.ForegroundAacRecorder? = null
        var file: File? = null
        fun call(name: String, vararg args: Any?) = MainActivity::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == args.size
        }.apply { isAccessible = true }.invoke(activity, *args)
        fun report(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$text\n") })
        try {
            kr.co.hanium.dreamup.walksafe.session.DeviceTestAccountUiLogin.ensureLoggedIn(
                instrumentation, activity, SystemClock.elapsedRealtime() + 20_000L)
            val deadline = SystemClock.elapsedRealtime() + 30_000L
            while (GatewaySessionProcessCoordinator.snapshot().session?.let { it.isUsableFor(it.actorId) } != true && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100)
            }
            instrumentation.runOnMainSync {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val page = MainActivity::class.java.getDeclaredField("nativeUiPage").apply { isAccessible = true }.get(activity) as Enum<*>
                call("showNativeUiPage", page.javaClass.enumConstants!!.single { (it as Enum<*>).name == "SETTINGS" })
                call("cancelVoiceCommandRecognition"); call("cancelCommandSpeechResponse"); call("stopHandsFreeVoiceService")
                call("cancelForegroundHomeWakeListening", false)
            }
            val session = requireNotNull(GatewaySessionProcessCoordinator.snapshot().session)
            assertTrue(session.isUsableFor(session.actorId))
            for ((id, phrase) in listOf("help-three" to "3번", "help-topic" to "음성 명령 사용법", "help-close" to "도움말 닫기", "cancel" to "취소", "cancel-long" to "목적지 취소", "unrelated" to "오늘 날씨가 좋네요")) {
                val ended = java.util.concurrent.CountDownLatch(1)
                var captureFailure: String? = null
                instrumentation.runOnMainSync {
                    call("cancelForegroundHomeWakeListening", false)
                    recorder = kr.co.hanium.dreamup.walksafe.feedback.ForegroundAacRecorder(directory,
                        onInputSilenced = { captureFailure = "silenced"; ended.countDown() },
                        onInputRouteChanged = { captureFailure = "route_changed"; ended.countDown() },
                        onCaptureLimitExceeded = { captureFailure = "limit"; ended.countDown() }) { ended.countDown() }
                    assertTrue(recorder!!.start())
                }
                report("ACOUSTIC_DIAGNOSTIC_READY case=$id")
                assertTrue("Speech endpoint missing", ended.await(24, java.util.concurrent.TimeUnit.SECONDS))
                assertNull(captureFailure)
                instrumentation.runOnMainSync { file = recorder!!.stop() }
                assertNotNull("Recorder did not produce audio", file)
                try {
                    val started = SystemClock.elapsedRealtime()
                    val result = AndroidGatewaySpeechClient().transcribeCall(session, file!!, UUID.randomUUID().toString()).execute()
                    val helpChoice = VoiceHelpPolicy.parse(listOf(result.transcript))
                    val selected = if (result.acoustic.executionAllowed) selectAndroidVoiceAction(
                        listOf(result.transcript), floatArrayOf(result.acoustic.confidence.toFloat())) else null
                    val compact = { value: String -> value.filter { it.isLetterOrDigit() } }
                    report("ACOUSTIC_DIAGNOSTIC " + JSONObject().put("case", id)
                        .put("exactTextMatch", result.transcript == phrase)
                        .put("textMatchIgnoringSpacingPunctuation", compact(result.transcript) == compact(phrase))
                        .put("helpChoice", helpChoice ?: JSONObject.NULL)
                        .put("cancelSelected", selected == AndroidVoiceAction.CancelDestination)
                        .put("anyCommandSelected", selected != null)
                        .put("allowed", result.acoustic.executionAllowed).put("confidence", result.acoustic.confidence)
                        .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                        .put("modelRevision", result.modelRevision).put("commandExecuted", false))
                } finally { file?.delete(); file = null }
            }
        } finally {
            instrumentation.runOnMainSync { recorder?.cancel(); activity.finish() }
            file?.delete(); directory.deleteRecursively()
        }
    }

    @Test fun syntheticAacThroughRealGatewayAndAndroidCommandPolicy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowLiveSyntheticStt") == "true")
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val directory = File(instrumentation.targetContext.cacheDir, "live-stt-fixtures")
        val helpMenu = InstrumentationRegistry.getArguments().getString("testHelpMenu") == "true"
        val configuredCases: List<Triple<String, String, Any?>> = if (helpMenu) listOf(
            Triple("help-one", "1번", 1),
            Triple("help-two", "2번", 2),
            Triple("help-three", "3번", 3),
            Triple("help-four", "4번", 4),
            Triple("help-topic", "음성 명령 사용법", 1),
            Triple("help-close", "도움말 닫기", 0),
            Triple("help-open", "도움말 열어줘", 4),
            Triple("help-open-polite", "도움말 열어주세요", 4),
        ) else listOf(
            Triple("help-short", "도움말", AndroidVoiceAction.SpeakVoiceHelp),
            Triple("help-polite", "도움말 알려주세요", AndroidVoiceAction.SpeakVoiceHelp),
            Triple("settings", "설정 열어주세요", AndroidVoiceAction.OpenSettings),
            Triple("destination", "서울역으로 안내해줘", AndroidVoiceAction.SearchDestination("서울역")),
            Triple("unrelated", "오늘 날씨가 좋네요", null),
            Triple("silence", "", null),
        )
        val requested = InstrumentationRegistry.getArguments().getString("syntheticSttCaseIds")
            ?.split(',')?.toSet()
        require(requested == null || (requested.isNotEmpty() &&
            requested.all { id -> configuredCases.any { it.first == id } }))
        val cases = configuredCases.filter { requested == null || it.first in requested }
        try {
            val deadline = SystemClock.elapsedRealtime() + 30_000L
            while (GatewaySessionProcessCoordinator.snapshot().session?.let { it.isUsableFor(it.actorId) } != true && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100)
            }
            val session = requireNotNull(GatewaySessionProcessCoordinator.snapshot().session) { "Unlock and restore the real test account first" }
            assertTrue(session.isUsableFor(session.actorId))
            val failures = mutableListOf<String>()
            for ((id, expectedText, expectedAction) in cases) {
                val file = File(directory, "$id.m4a")
                assertTrue("Host synthetic fixture missing: $id", file.isFile)
                val started = SystemClock.elapsedRealtime()
                val result = AndroidGatewaySpeechClient().transcribeCall(session, file, UUID.randomUUID().toString()).execute()
                val selected = if (!result.acoustic.executionAllowed) null else if (helpMenu)
                    VoiceHelpPolicy.parse(listOf(result.transcript)) else selectAndroidVoiceAction(
                    listOf(result.transcript), floatArrayOf(result.acoustic.confidence.toFloat()),
                )
                val matched = selected == expectedAction
                // Only synthetic-file text is reported. Never use this path for recorded user speech.
                val report = JSONObject().put("case", id).put("expected", expectedText)
                    .put("transcript", result.transcript).put("acousticAllowed", result.acoustic.executionAllowed)
                    .put("confidence", result.acoustic.confidence).put("noSpeechProbability", result.acoustic.noSpeechProbability)
                    .put("commandMatched", matched).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    .put("modelRevision", result.modelRevision).put("microphoneTest", false)
                    .put("helpMenu", helpMenu)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "LIVE_SYNTHETIC_STT $report\n") })
                if (!matched) failures += id
            }
            assertEquals("Synthetic speech must select the intended action without executing it", emptyList<String>(), failures)
        } finally {
            cases.forEach { (id, _, _) -> File(directory, "$id.m4a").delete() }
            directory.delete()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
