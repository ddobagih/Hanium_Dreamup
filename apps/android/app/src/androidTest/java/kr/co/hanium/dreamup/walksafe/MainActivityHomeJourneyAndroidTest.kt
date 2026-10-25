package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.location.Location
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.view.View
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.camera.view.PreviewView
import androidx.camera.core.ImageAnalysis
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.tasks.Tasks
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityDecision
import kr.co.hanium.dreamup.walksafe.navigation.start.NativeGuidanceCalibrationSession
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativePhoneMountingCheckRequest
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeSafetyStop
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.PrimaryDetectorExecutionOwner
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.PrimaryWorkCompletion
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.PrimaryNativeTimingSnapshot
import kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.UnknownRuntimeServiceOwner
import kr.co.hanium.dreamup.walksafe.inference.InferencePacingTicket
import kr.co.hanium.dreamup.walksafe.device.AndroidStartupCapabilityProbe
import kr.co.hanium.dreamup.walksafe.device.OfflineKoreanTextToSpeechProbeState
import kr.co.hanium.dreamup.walksafe.voice.*
import kr.co.hanium.dreamup.walksafe.voice.input.VoiceInputDiagnostics
import kr.co.hanium.dreamup.walksafe.voice.input.VoiceInputSessionController
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputActuatorFactory
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.PositioningCollectionBinding
import kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Main.onCreate, HOME/search/start controls, HTTP parsers, native prewalk producers,
 * Android TTS, admission and encrypted route storage. Only the account/education/prior device
 * check are synthetic preconditions, the localhost backend is a fixture, and Fused positions
 * are controlled leaf inputs. No readiness, ACTIVE, route, camera or TTS success is injected.
 * VirtualScene camera and emulator sensors are virtual inputs, never physical-walk evidence.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityHomeJourneyAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val previous = MainActivityOnCreateHomeDestinationIntegrationAndroidTest()
    private lateinit var fixtureBaseUrl: String
    private val speechEvents = SpeechOutputDiagnostics()
    private val nativeUtteranceMessages = java.util.concurrent.ConcurrentHashMap<Pair<AndroidFeedbackActuator, String>, String>()
    private val nativeUtteranceStarted = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<AndroidFeedbackActuator, String>>()
    private val nativeUtteranceAccepted = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<AndroidFeedbackActuator, String>>()
    private val nativeUtteranceDone = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<AndroidFeedbackActuator, String>>()
    private val observedActuators = java.util.IdentityHashMap<AndroidFeedbackActuator, (SpeechOutputEvent) -> Unit>()
    private val observedOutputControllers = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    private data class DetectorFailureSample(
        val observedAt: Long,
        val frameGeneration: Int,
        val loadGeneration: Long,
        val epoch: WalkRuntimeEpoch?,
    )
    private val detectorFailureSamples = java.util.concurrent.ConcurrentHashMap<InferencePacingTicket, DetectorFailureSample>()
    private val detectorTimingSamples = java.util.concurrent.atomic.AtomicInteger()
    private val detectorUninvokedSamples = java.util.concurrent.atomic.AtomicInteger()

    @Test(timeout = 120_000L)
    fun actualHomeVoiceDecoderDeathAllowsExplicitRetryAndUiCancel() = withHome { scenario, _ ->
        val context = instrumentation.targetContext
        assertNotNull("Use the existing verified model, without substituting a recognizer",
            BundledVoskModelInstaller.installedModelOrNull(context))
        val ownerPid = android.os.Process.myPid()
        val ownerUid = android.os.Process.myUid()
        val inputEvents = CopyOnWriteArrayList<Pair<VoiceInputDiagnosticEvent, Int?>>()
        val metadataFiles = listOf("events.previous.log", "events.log").map {
            java.io.File(context.cacheDir, "runtime-diagnostics/$it")
        }
        fun metadata() = metadataFiles.flatMap { if (it.isFile) it.readLines() else emptyList() }.toSet()
        val beforeMetadata = metadata()
        val prompt = "안내가 끝나면 말씀해 주세요."
        val decoderFailureMessage = "휴대전화 음성 인식에 오류가 발생했습니다. 음성 명령 버튼을 눌러 다시 시도해 주세요." +
            " 음성 명령을 다시 눌러 주세요."
        fun completedPrompts() = nativeUtteranceMessages.filterValues { it == prompt }.keys.filter {
            it in nativeUtteranceAccepted && it in nativeUtteranceStarted && it in nativeUtteranceDone
        }.toSet()
        lateinit var originalMain: MainActivity
        lateinit var input: VoiceInputSessionController
        lateinit var originalDiagnostics: VoiceInputDiagnostics
        lateinit var observedDiagnostics: VoiceInputDiagnostics
        fun preferred(): PreferredOfflineSpeechRecognizer? {
            val adapter = field(input, "speechRecognizer") ?: return null
            return adapter.javaClass.declaredFields.single {
                it.type == PreferredOfflineSpeechRecognizer::class.java
            }.apply { isAccessible = true }.get(adapter) as PreferredOfflineSpeechRecognizer
        }
        fun engine(recognizer: PreferredOfflineSpeechRecognizer): OfflineSpeechEngine? =
            field(requireNotNull(field(recognizer, "requests")), "engine") as? OfflineSpeechEngine
        fun stream(recognizer: PreferredOfflineSpeechRecognizer): VoskStreamingTranscriber {
            val backend = requireNotNull(field(recognizer, "fallback"))
            val vosk = field(backend, "delegate") as VoskSpeechRecognizer
            val adapter = requireNotNull(field(vosk, "transcriber"))
            return adapter.javaClass.declaredFields.single {
                it.type == VoskStreamingTranscriber::class.java
            }.apply { isAccessible = true }.get(adapter) as VoskStreamingTranscriber
        }
        fun captureRelease(transcriber: VoskStreamingTranscriber): CountDownLatch {
            val source = synchronized(requireNotNull(field(transcriber, "stateLock"))) {
                assertTrue("Production one-shot must own the remote decoder", field(transcriber, "recognizer") is RemoteVoskDecoder)
                field(transcriber, "audioSource") as AndroidPcmAudioSource
            }
            val session = synchronized(requireNotNull(field(source, "stateLock"))) {
                requireNotNull(field(source, "activeSession"))
            }
            return field(session, "releaseComplete") as CountDownLatch
        }
        fun report(stage: String, values: JSONObject) {
            values.put("stage", stage).put("owner_pid", ownerPid).put("uid", ownerUid)
                .put("original_kaldi_assertion_reproduced", false).put("human_recognition_proved", false)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "HOME_VOICE_DECODER_RETRY $values\n")
            })
        }
        scenario.onActivity { main ->
            originalMain = main
            input = (field(main, "voiceInputController\$delegate") as Lazy<*>).value as VoiceInputSessionController
            originalDiagnostics = field(input, "diagnostics") as VoiceInputDiagnostics
            observedDiagnostics = object : VoiceInputDiagnostics by originalDiagnostics {
                override fun input(event: VoiceInputDiagnosticEvent, confidence: Float?, resultCount: Int?, errorCode: Int?) {
                    originalDiagnostics.input(event, confidence, resultCount, errorCode)
                    inputEvents.add(event to errorCode)
                }
            }
            set(input, "diagnostics", observedDiagnostics)
        }
        try {
            await(scenario, "The actual HOME voice card must be available", 25_000L) { main ->
                call(main, "homeVoiceCommandAvailable") == true && descendants(main.window.decorView)
                    .filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "음성 명령" &&
                        clickableAncestor(it)?.isEnabled == true }
            }
            val promptsBefore = completedPrompts()
            scenario.onActivity { main ->
                val card = descendants(main.window.decorView).filterIsInstance<TextView>().first {
                    it.isShown && it.text.toString() == "음성 명령"
                }.let(::clickableAncestor)
                assertTrue(requireNotNull(card).performClick())
                assertEquals("VOICE_COMMAND", field(main, "nativeUiPage").toString())
            }
            lateinit var firstRecognizer: PreferredOfflineSpeechRecognizer
            lateinit var firstEngine: OfflineSpeechEngine
            await(scenario, "Real prompt DONE and one-shot READY must precede fault injection", 25_000L) {
                val selected = preferred() ?: return@await false
                val selectedEngine = engine(selected) ?: return@await false
                val ready = inputEvents.count { it.first == VoiceInputDiagnosticEvent.ONE_SHOT_UI_READY } == 1
                if (!ready || !input.state.recognitionActive || (completedPrompts() - promptsBefore).size != 1) return@await false
                firstRecognizer = selected
                firstEngine = selectedEngine
                true
            }
            report("natural_backend", JSONObject().put("backend", firstEngine.name).put("fault_count", 0))
            assumeTrue("Natural HOME backend is $firstEngine; Vosk precondition unmet, decoder fault count=0",
                firstEngine == OfflineSpeechEngine.VOSK)
            lateinit var firstStream: VoskStreamingTranscriber
            lateinit var firstRelease: CountDownLatch
            var firstGeneration = -1
            var killedPid = -1
            await(scenario, "Existing typed diagnostics must identify Vosk selection and READY", 3_000L) {
                val added = metadata() - beforeMetadata
                added.any { it.contains("event=ENGINE_SELECTED ") && it.contains("selection=VOSK ") } &&
                    added.any { it.contains("event=READY backend=VOSK ") }
            }
            scenario.onActivity { main ->
                assertSame(firstRecognizer, preferred())
                assertTrue(input.state.recognitionActive)
                firstGeneration = input.state.recognitionGeneration
                firstStream = stream(firstRecognizer)
                assertTrue("Actual production capture must be RUNNING", firstStream.isRunning())
                firstRelease = captureRelease(firstStream)
                assertEquals(1L, firstRelease.count)
                val decoder = requireNotNull(context.getSystemService(ActivityManager::class.java).runningAppProcesses)
                    .single { it.processName == context.packageName + VoskDecoderProtocol.PROCESS_SUFFIX }
                assertEquals(ownerUid, decoder.uid)
                assertNotEquals(ownerPid, decoder.pid)
                assertTrue(main.hasWindowFocus())
                killedPid = decoder.pid
                android.os.Process.sendSignal(killedPid, 6)
            }
            report("owned_decoder_aborted", JSONObject().put("decoder_pid", killedPid)
                .put("request_generation", firstGeneration).put("fault_count", 1).put("signal", "SIGABRT"))
            var failureObservation = JSONObject().put("observed", false)
            try {
                await(scenario, "Decoder death must reach the actual one-shot failure UI exactly once", 10_000L) { main ->
                    assertSame(originalMain, main)
                    val errors = inputEvents.filter { it.first == VoiceInputDiagnosticEvent.ONE_SHOT_UI_ERROR }
                    val active = input.state.recognitionActive
                    val adapterNull = field(input, "speechRecognizer") == null
                    val status = (field(main, "gatewayVoiceStatusText") as TextView).text.toString()
                    val expectedMatches = status == decoderFailureMessage
                    // This is retained status text; actual visible retry controls are checked below.
                    failureObservation = JSONObject().put("observed", true)
                        .put("error_codes", org.json.JSONArray(errors.map { it.second ?: JSONObject.NULL }))
                        .put("error_count", errors.size).put("active", active).put("adapter_null", adapterNull)
                        .put("page", field(main, "nativeUiPage").toString()).put("gateway_status_text", status)
                        .put("expected_matches", expectedMatches)
                    errors.size == 1 && !active && adapterNull && expectedMatches
                }
            } finally {
                // Diagnostic delivery must not mask the original assertion or cleanup exception.
                runCatching { report("decoder_failure_observation", failureObservation) }
            }
            VoskStreamingWorker.process.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(listOf(SpeechRecognizer.ERROR_CLIENT), inputEvents.filter {
                it.first == VoiceInputDiagnosticEvent.ONE_SHOT_UI_ERROR
            }.map { it.second })
            assertEquals("The dead request must release its AudioRecord and microphone lease", 0L, firstRelease.count)
            assertFalse(firstStream.isRunning())
            assertEquals(ownerPid, android.os.Process.myPid())
            scenario.onActivity { main ->
                assertTrue(main.hasWindowFocus())
                assertEquals("VOICE_COMMAND", field(main, "nativeUiPage").toString())
                record("voice_decoder_failure_ui_survived", main)
            }
            // HOME wake may legitimately reacquire the process microphone or spawn the retry PID.
            // Assert the failed capture's release, not a permanently empty global lease/PID list.
            await(scenario, "The real screen must permit an explicit retry after error speech", 20_000L) { main ->
                val button = field(main, "voiceReportButton") as View
                button.isShown && button.isEnabled && !input.isHandsFreeVoiceOutputActive() &&
                    nativeUtteranceMessages.any { (key, message) -> message == decoderFailureMessage &&
                        key in nativeUtteranceAccepted && key in nativeUtteranceStarted && key in nativeUtteranceDone }
            }
            val promptsBeforeRetry = completedPrompts()
            scenario.onActivity { main ->
                assertTrue((field(main, "voiceReportButton") as View).performClick())
            }
            lateinit var retryRecognizer: PreferredOfflineSpeechRecognizer
            lateinit var retryEngine: OfflineSpeechEngine
            var retryGeneration = -1
            await(scenario, "Explicit UI retry must complete its own prompt and reach actual READY", 25_000L) {
                val selected = preferred() ?: return@await false
                val selectedEngine = engine(selected) ?: return@await false
                if (!input.state.recognitionActive || input.state.recognitionGeneration <= firstGeneration ||
                    inputEvents.count { it.first == VoiceInputDiagnosticEvent.ONE_SHOT_UI_READY } != 2 ||
                    (completedPrompts() - promptsBeforeRetry).size != 1) return@await false
                retryRecognizer = selected
                retryEngine = selectedEngine
                retryGeneration = input.state.recognitionGeneration
                true
            }
            var retryPid: Int? = null
            var retryStream: VoskStreamingTranscriber? = null
            var retryRelease: CountDownLatch? = null
            scenario.onActivity { main ->
                assertNotSame("Failure must retire the old recognizer", firstRecognizer, retryRecognizer)
                assertSame("Cancel must own the recognizer observed at READY", retryRecognizer, preferred())
                assertTrue("Cancel must interrupt an active request", input.state.recognitionActive)
                assertEquals(retryGeneration, input.state.recognitionGeneration)
                assertEquals(retryEngine, engine(retryRecognizer))
                assertNotNull("The READY request must still exist at UI cancel", field(retryRecognizer, "request"))
                if (retryEngine == OfflineSpeechEngine.VOSK) {
                    val running = stream(retryRecognizer)
                    assertTrue(running.isRunning())
                    retryStream = running
                    retryRelease = captureRelease(running)
                    assertEquals(1L, requireNotNull(retryRelease).count)
                    val decoder = requireNotNull(context.getSystemService(ActivityManager::class.java).runningAppProcesses)
                        .single { it.processName == context.packageName + VoskDecoderProtocol.PROCESS_SUFFIX }
                    assertEquals(ownerUid, decoder.uid)
                    assertNotEquals(ownerPid, decoder.pid)
                    assertNotEquals(killedPid, decoder.pid)
                    retryPid = decoder.pid
                }
                report("explicit_ui_retry_ready", JSONObject().put("retry_backend", retryEngine.name)
                    .put("retry_decoder_pid", retryPid ?: JSONObject.NULL).put("request_generation", retryGeneration)
                    .put("retry_vosk_running", retryStream != null).put("failed_capture_released", firstRelease.count == 0L))
                val cancel = descendants(main.window.decorView).filterIsInstance<Button>().single {
                    it.isShown && it.text.toString() == "음성 명령 취소"
                }
                assertTrue("Cancel must use the actual visible screen control", cancel.isShown && cancel.isEnabled && cancel.performClick())
            }
            await(scenario, "Actual UI cancel must retire only its one-shot and return HOME", 8_000L) { main ->
                field(main, "nativeUiPage").toString() == "HOME" && !input.state.recognitionActive &&
                    input.state.recognitionGeneration > retryGeneration && field(input, "recognitionSource") == null &&
                    field(retryRecognizer, "request") == null
            }
            VoskStreamingWorker.process.submit {}.get(10, TimeUnit.SECONDS)
            retryStream?.let { assertFalse(it.isRunning()) }
            retryRelease?.let { assertEquals(0L, it.count) }
            assertEquals(1, inputEvents.count { it.first == VoiceInputDiagnosticEvent.ONE_SHOT_UI_ERROR })
            report("ui_cancel_home", JSONObject().put("fault_count", 1).put("failure_count", 1)
                .put("retry_backend", retryEngine.name).put("retry_capture_released", retryRelease?.count?.let { it == 0L } ?: JSONObject.NULL)
                .put("main_survived", android.os.Process.myPid() == ownerPid).put("home", true))
        } finally {
            instrumentation.runOnMainSync {
                if (field(input, "diagnostics") === observedDiagnostics) set(input, "diagnostics", originalDiagnostics)
            }
        }
    }

    @Test(timeout = 180_000L)
    fun actualHomeSearchStartAndCancelRetirePreparation() = withHome { scenario, locations ->
        val destination = searchAndSelect(scenario)
        start(scenario, destination)
        await(scenario, "Actual prewalk request was not created", 10_000L) {
            field(it, "nativePhoneMountingCheckRequest") != null
        }
        // Permit actual SDK producer callbacks; cancellation itself does not require a successful walk.
        SystemClock.sleep(2_000L)
        scenario.onActivity { main ->
            record("before_cancel", main)
            val cancel = field(main, "nativeGuidanceCancelButton") as View
            assertTrue("The real cancellation control must be visible", cancel.isShown)
            assertTrue(cancel.performClick())
        }
        await(scenario, "Cancel did not return to HOME and revoke pending start", 8_000L) {
            field(it, "nativeUiPage").toString() == "HOME" &&
                field(it, "pendingUiDestination") == null &&
                field(it, "nativePhoneMountingCheckRequest") == null &&
                field(it, "nativePrewalkStartEpoch") == null && field(it, "isRouteActive") == false
        }
        scenario.moveToState(Lifecycle.State.CREATED)
        instrumentation.runOnMainSync { locations.emitRetiredCallbacks() }
        scenario.moveToState(Lifecycle.State.RESUMED)
        SystemClock.sleep(1_000L)
        scenario.onActivity { main ->
            assertNull(field(main, "pendingUiDestination"))
            assertFalse(field(main, "isRouteActive") as Boolean)
            assertFalse((field(main, "routeNavigator") as RouteNavigator).hasRoute())
            record("cancel_background_resume_late_callback_fenced", main)
        }
    }

    @Test(timeout = 120_000L)
    fun emptySearchCanRetryAndSelectWithoutLeavingTheRealSearchScreen() = withHome { scenario, _ ->
        controlBackend(JSONObject().put("search_mode", "empty"))
        openSearchAndSubmit(scenario)
        await(scenario, "Empty HTTP response was not consumed", 20_000L) {
            field(it, "destinationSearchZeroResultQuery") == "통합시험 목적지" &&
                !(field(it, "destinationSearchInFlight") as Boolean)
        }
        scenario.onActivity { main ->
            assertEquals("DESTINATION_SEARCH", field(main, "nativeUiPage").toString())
            assertTrue((field(main, "destinationSearchResults") as List<*>).isEmpty())
            assertNull(field(main, "pendingUiDestination"))
            record("empty_search_visible", main)
        }
        controlBackend(JSONObject().put("search_mode", "success"))
        scenario.onActivity { assertTrue((field(it, "destinationSearchButton") as View).performClick()) }
        val selected = awaitAndSelectSearch(scenario)
        scenario.onActivity { main ->
            assertSame(selected, field(main, "pendingUiDestination"))
            assertFalse(field(main, "isRouteActive") as Boolean)
            record("empty_search_retry_selected", main)
        }
    }

    @Test(timeout = 120_000L)
    fun cancellingSearchRejectsItsDelayedHttpResponse() = withHome { scenario, _ ->
        controlBackend(JSONObject().put("search_delay_ms", 4_000))
        openSearchAndSubmit(scenario)
        await(scenario, "Search did not own an in-flight request", 5_000L) {
            field(it, "destinationSearchInFlight") == true
        }
        scenario.onActivity { main ->
            val cancel = field(main, "destinationCancelButton") as View
            assertTrue(cancel.isShown && cancel.performClick())
            assertEquals("HOME", field(main, "nativeUiPage").toString())
        }
        SystemClock.sleep(5_000L)
        scenario.onActivity { main ->
            assertEquals("HOME", field(main, "nativeUiPage").toString())
            assertTrue((field(main, "destinationSearchResults") as List<*>).isEmpty())
            assertNull(field(main, "pendingUiDestination"))
            assertFalse(field(main, "isRouteActive") as Boolean)
            record("late_search_response_fenced", main)
        }
    }

    @Test(timeout = 150_000L)
    fun savedDeviceCheckFailureOpensActualVoiceSettingsAndRechecksOnReturn() = withHome(persistSyntheticDeviceCheck = true) { scenario, _ ->
        lateinit var originalMain: MainActivity
        lateinit var previousActuator: AndroidFeedbackActuator
        scenario.onActivity { main ->
            originalMain = main
            val recovery = (field(main, "voiceDataSettingsRecovery\$delegate") as Lazy<*>).value!!
            call(recovery, "ensurePendingKoreanSpeechProbeStarted")
            previousActuator = call(main, "ensureFeedbackActuator") as AndroidFeedbackActuator
        }
        await(scenario, "Installed native Korean synthesis and the initial actuator must finish before failure injection", 25_000L) {
            (field(it, "startupCapabilityProbe") as AndroidStartupCapabilityProbe).snapshot().offlineKoreanTextToSpeechAvailable == true &&
                previousActuator.statusText() == "tts=ready"
        }
        scenario.onActivity { main ->
            persistDeviceCheckPrerequisite(main, requireNotNull(field(main, "reporterUserId") as? String))
            val probe = field(main, "startupCapabilityProbe") as AndroidStartupCapabilityProbe
            val state = field(probe, "offlineKoreanTextToSpeechProbeState") as OfflineKoreanTextToSpeechProbeState
            // Failure-only leaf input. Recovery below must synthesize through the real native probe.
            assertTrue(state.complete(state.begin(), false, OfflineKoreanSpeechUnavailableCause.KOREAN_VOICE_DATA_MISSING))
            @Suppress("UNCHECKED_CAST")
            (field(probe, "onCapabilityChanged") as () -> Unit).invoke()
            val settings = descendants(main.window.decorView).filterIsInstance<TextView>()
                .firstOrNull { it.isShown && it.text.toString() == "설정" }?.let(::clickableAncestor)
            assertNotNull("Actual HOME settings card must be reachable", settings)
            assertTrue(requireNotNull(settings).performClick())
            assertEquals("SETTINGS", field(main, "nativeUiPage").toString())
            val button = descendants(main.window.decorView).filterIsInstance<Button>().single {
                it.isShown && it.contentDescription?.toString()?.startsWith("한국어 음성 데이터 설치 열기") == true
            }
            assertEquals("한국어 음성 데이터 설치", button.text.toString())
            record("synthetic_korean_probe_failure_settings_recovery_visible", main)
            assertTrue(button.performClick())
        }
        val pausedBySettings = SystemClock.elapsedRealtime() + 10_000L
        while (scenario.state == Lifecycle.State.RESUMED && SystemClock.elapsedRealtime() < pausedBySettings) SystemClock.sleep(100L)
        assertNotEquals("The real external settings launch must pause Main", Lifecycle.State.RESUMED, scenario.state)
        instrumentation.runOnMainSync {
            assertFalse(field(originalMain, "isActivityForeground") as Boolean)
            assertNull("Android onPause must close the old actuator", field(originalMain, "feedbackActuator"))
        }
        val externalPackages = setOf("com.android.settings", "com.google.android.tts")
        val focusedBy = SystemClock.elapsedRealtime() + 10_000L
        var externalPackage: String? = null
        while (SystemClock.elapsedRealtime() < focusedBy) {
            externalPackage = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
            if (externalPackage in externalPackages) break
            SystemClock.sleep(100L)
        }
        assertTrue("Expected actual Android/Google voice settings, got $externalPackage", externalPackage in externalPackages)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream",
            "HOME_JOURNEY voice_settings_external_package=$externalPackage; failure_leaf=KOREAN_VOICE_DATA_MISSING; native_probe_recovery=true\n") })
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("input keyevent 4")
        ).use { it.readBytes() }
        val resumed = SystemClock.elapsedRealtime() + 15_000L
        while (scenario.state != Lifecycle.State.RESUMED && SystemClock.elapsedRealtime() < resumed) SystemClock.sleep(100L)
        assertEquals("Back must resume the same Main Activity", Lifecycle.State.RESUMED, scenario.state)
        await(scenario, "Settings return did not complete the actual Korean synthesis recheck", 30_000L) { main ->
            assertSame(originalMain, main)
            val probe = field(main, "startupCapabilityProbe") as AndroidStartupCapabilityProbe
            probe.snapshot().offlineKoreanTextToSpeechAvailable == true &&
                field(main, "offlineKoreanTextToSpeechCapabilityOverride") == true &&
                call(main, "postLoginDeviceCheckPassesFeatureGate") == true &&
                call(main, "currentReporterUserId") == field(main, "reporterUserId") &&
                call(main, "currentReporterUserId") != null &&
                descendants(main.window.decorView).filterIsInstance<Button>().none {
                    it.isShown && it.contentDescription?.toString()?.startsWith("한국어 음성 데이터 설치 열기") == true
                }
        }
        scenario.onActivity { main ->
            // Exercise the production SDK factory after return; READY is never a fixture input.
            assertNotSame(previousActuator, call(main, "ensureFeedbackActuator"))
        }
        await(scenario, "A new actual native actuator did not become READY after settings return", 20_000L) {
            (field(it, "feedbackActuator") as? AndroidFeedbackActuator)?.statusText() == "tts=ready"
        }
        scenario.onActivity { record("voice_settings_same_activity_native_recheck_and_new_actuator_ready", it) }
    }

    @Test(timeout = 180_000L)
    fun transientRouteHttpFailureRetainsIntentAndRecoversFromRegisteredHandler() = withHome { scenario, _ ->
        controlBackend(JSONObject().put("walking_mode", "timeout"))
        val destination = searchAndSelect(scenario)
        val automaticRetryWasPresented = java.util.concurrent.atomic.AtomicBoolean(false)
        scenario.onActivity { main ->
            // Main's latestNavigationState intentionally stores only the first status word.
            // Observe the full production TextView publication, including transient metadata.
            (field(main, "navigationStatusText") as TextView).addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                    if (text?.contains("automatic_retry_pending") == true) automaticRetryWasPresented.set(true)
                }
                override fun afterTextChanged(text: android.text.Editable?) = Unit
            })
        }
        start(scenario, destination)
        lateinit var originalEpoch: WalkRuntimeEpoch
        scenario.onActivity { originalEpoch = (field(it, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot().epoch }
        await(scenario, "Actual route failure did not retain a registered automatic retry", 85_000L) { main ->
            val acquisition = (field(main, "routeAcquisitionController\$delegate") as Lazy<*>).value!!
            val retry = requireNotNull(field(acquisition, "initialRouteRetry"))
            field(retry, "current") != null && field(retry, "timer") != null && automaticRetryWasPresented.get()
        }
        scenario.onActivity { main ->
            assertSame(destination, field(main, "pendingUiDestination"))
            assertEquals(originalEpoch, (field(main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot().epoch)
            assertFalse(field(main, "isRouteActive") as Boolean)
            assertFalse((field(main, "routeNavigator") as RouteNavigator).hasRoute())
            val acquisition = (field(main, "routeAcquisitionController\$delegate") as Lazy<*>).value!!
            val retry = requireNotNull(field(acquisition, "initialRouteRetry"))
            val episode = requireNotNull(field(retry, "current"))
            assertSame(destination, field(requireNotNull(field(episode, "context")), "selectedDestination"))
            assertNotNull("The production Handler must already own the recovery Runnable", field(retry, "timer"))
            record("http_failure_retained_with_registered_retry", main)
        }
        // Repair the external service only: never run or replace the production retry Runnable.
        controlBackend(JSONObject().put("walking_mode", "success"))
        await(scenario, "Registered retry did not install the retained route", 25_000L) {
            field(it, "isRouteActive") == true && (field(it, "routeNavigator") as RouteNavigator).hasRoute()
        }
        lateinit var store: EncryptedRouteSnapshotStore
        lateinit var scope: RouteSnapshotScope
        scenario.onActivity { main ->
            val walk = (field(main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot()
            assertEquals(originalEpoch, walk.epoch)
            assertEquals(WalkSessionState.ACTIVE, walk.state)
            assertSame(destination, field(main, "pendingUiDestination"))
            store = field(main, "routeSnapshotStore") as EncryptedRouteSnapshotStore
            scope = field(main, "routeSnapshotWalkScope") as RouteSnapshotScope
            record("http_retry_route_installed", main)
        }
        val saved = requireNotNull(store.load(scope, System.currentTimeMillis()))
        assertEquals(destination.point, saved.destination)
        assertEquals(originalEpoch.walkSessionId, saved.walkSessionId)
        scenario.onActivity { main ->
            val end = field(main, "nativeGuidanceCancelButton") as View
            assertTrue("The real END control must remain available", end.isShown && end.performClick())
            assertEquals(WalkSessionState.ENDED, (field(main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot().state)
            assertEquals("HOME", field(main, "nativeUiPage").toString())
            record("http_retry_explicit_end", main)
        }
    }

    @Test(timeout = 300_000L)
    fun actualHomeStartMustInstallStoredRouteAndReachArrival() = withHome { scenario, locations ->
        val destination = searchAndSelect(scenario)
        start(scenario, destination)
        await(scenario, "Production readiness/admission did not install an actual HTTP route", 85_000L) {
            field(it, "isRouteActive") == true && (field(it, "routeNavigator") as RouteNavigator).hasRoute()
        }
        lateinit var store: EncryptedRouteSnapshotStore
        lateinit var scope: RouteSnapshotScope
        scenario.onActivity { main ->
            assertEquals(WalkSessionState.ACTIVE, (field(main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot().state)
            store = field(main, "routeSnapshotStore") as EncryptedRouteSnapshotStore
            scope = field(main, "routeSnapshotWalkScope") as RouteSnapshotScope
            assertEquals("tts=ready", (field(main, "feedbackActuator") as AndroidFeedbackActuator).statusText())
            record("actual_route_installed", main)
        }
        // Read on the instrumentation worker, retaining the production encrypted store and ACK.
        val saved = requireNotNull(store.load(scope, System.currentTimeMillis()))
        assertEquals(destination.point, saved.destination)
        assertEquals(scope.walkSessionId, saved.walkSessionId)
        await(scenario, "The first navigation cue needs native DONE and the current navigator ACK", 30_000L) { main ->
            val navigator = field(main, "routeNavigator") as RouteNavigator
            val completed = RouteNavigator::class.java.getDeclaredField("completedSpeechCue")
                .apply { isAccessible = true }.get(navigator)
            speechEvents.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.DONE) > 0 && completed != null
        }
        scenario.onActivity { record("first_navigation_native_done_and_ack", it) }
        moveToAutomaticArrival(scenario, locations)
        awaitAutomaticArrival(scenario, locations, "actual_arrival_ended_home")
    }

    @Test(timeout = 240_000L)
    fun activeBackgroundRequiresExplicitResumeAndRejectsRetiredLocationOwner() = withHome(persistSyntheticDeviceCheck = true) { scenario, locations ->
        val destination = searchAndSelect(scenario)
        start(scenario, destination)
        awaitInstalledWithNativeCue(scenario, destination)
        lateinit var mainBefore: MainActivity
        lateinit var beforeEpoch: WalkRuntimeEpoch
        lateinit var oldActuator: AndroidFeedbackActuator
        scenario.onActivity { main ->
            persistDeviceCheckPrerequisite(main, requireNotNull(field(main, "reporterUserId") as? String))
            mainBefore = main
            beforeEpoch = lifecycle(main).snapshot().epoch
            oldActuator = field(main, "feedbackActuator") as AndroidFeedbackActuator
        }
        val nativeDoneBefore = speechEvents.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.DONE)
        scenario.moveToState(Lifecycle.State.CREATED)
        instrumentation.runOnMainSync {
            val paused = lifecycle(mainBefore).snapshot()
            assertEquals(WalkSessionState.PAUSED, paused.state)
            assertEquals(WalkSessionRecoveryStage.RECHECK_REQUIRED, paused.recoveryStage)
            assertNotEquals(beforeEpoch, paused.epoch)
            assertNull(field(mainBefore, "feedbackActuator"))
            assertFalse((field(mainBefore, "routeNavigator") as RouteNavigator).hasRoute())
            val locationBefore = field(mainBefore, "latestTrustedLocation")
            assertTrue("An actual prior Fused callback must be retired", locations.retiredCount > 0)
            locations.eastM = 900.0
            locations.emitRetiredCallbacks()
            locations.eastM = 0.0
            assertSame("Fresh samples from the retired owner cannot publish a position", locationBefore,
                field(mainBefore, "latestTrustedLocation"))
            assertEquals(paused, lifecycle(mainBefore).snapshot())
        }
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.onActivity { main ->
            assertSame(mainBefore, main)
            assertEquals("Foreground return cannot itself resume navigation", WalkSessionState.PAUSED, lifecycle(main).snapshot().state)
            assertFalse(field(main, "isRouteActive") as Boolean)
            assertTrue("Saved synthetic prerequisites must survive actual onResume validation",
                call(main, "postLoginDeviceCheckPassesFeatureGate") as Boolean)
            assertNotNull(call(main, "currentReporterUserId"))
            assertNotSame(oldActuator, field(main, "feedbackActuator"))
            record("active_background_return_requires_confirmation", main)
        }
        await(scenario, "GUIDANCE must expose a real recovery control after foreground return", 10_000L) {
            val button = field(it, "nativeGuidanceRetryButton") as Button
            if (!button.isShown) {
                false
            } else {
                when (button.text.toString()) {
                    "재개 준비 중", "재개 확인 중" -> { assertFalse(button.isEnabled); true }
                    "다시 시도" -> if (button.isEnabled) { assertTrue(button.performClick()); true } else false
                    "안전 제한 안내 확인", "보행 안내 재개 확인" -> { assertTrue(button.isEnabled); true }
                    else -> error("Unexpected visible recovery action label")
                }
            }
        }
        // Real SDK remeasurement and lease validation create the current token. No event is injected.
        var noticeRequested = false
        await(scenario, "Production recheck did not offer a current explicit resume control", 70_000L) { main ->
            val snapshot = lifecycle(main).snapshot()
            val button = field(main, "nativeGuidanceRetryButton") as Button
            val token = snapshot.confirmationToken
            if (snapshot.state != WalkSessionState.PAUSED ||
                snapshot.recoveryStage != WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION ||
                token == null || !button.isShown || !button.isEnabled) {
                false
            } else {
                assertEquals(snapshot.epoch, token.epoch)
                val request = requireNotNull(field(main, "nativePhoneMountingCheckRequest") as? NativePhoneMountingCheckRequest)
                val calibration = call(main, "getNativeGuidanceCalibrationSession") as NativeGuidanceCalibrationSession
                assertTrue("An enabled notice/resume action requires current completed calibration", calibration.isReady(request))
                when (button.text.toString()) {
                    "안전 제한 안내 확인" -> {
                        assertFalse(call(main, "isStartupCapabilityConfirmed") as Boolean)
                        if (!noticeRequested) {
                            noticeRequested = true
                            assertTrue(button.performClick())
                            record("explicit_resume_notice_requested", main)
                        }
                        false
                    }
                    "보행 안내 재개 확인" -> {
                        assertTrue(call(main, "isStartupCapabilityConfirmed") as Boolean)
                        val actuator = field(main, "feedbackActuator") as AndroidFeedbackActuator
                        assertNotSame(oldActuator, actuator)
                        val decision = field(main, "startupCapabilityDecision") as WalkSafeStartupCapabilityDecision
                        assertTrue("Current actor notice needs its exact native ACCEPTED/START/DONE",
                            nativeUtteranceMessages.any { (key, message) ->
                                key.first === actuator && message == decision.noticeKo &&
                                    key in nativeUtteranceAccepted && key in nativeUtteranceStarted && key in nativeUtteranceDone
                            })
                        true
                    }
                    else -> error("Unexpected enabled recovery action before confirmation")
                }
            }
        }
        scenario.onActivity { main ->
            val snapshot = lifecycle(main).snapshot()
            assertEquals(snapshot.epoch, requireNotNull(snapshot.confirmationToken).epoch)
            val resume = field(main, "nativeGuidanceRetryButton") as Button
            assertEquals("보행 안내 재개 확인", resume.text.toString())
            assertTrue(resume.isShown && resume.isEnabled && resume.performClick())
        }
        await(scenario, "Explicit resume did not install a new current route", 70_000L) {
            lifecycle(it).snapshot().state == WalkSessionState.ACTIVE && field(it, "isRouteActive") == true
        }
        assertStoredDestination(scenario, destination)
        scenario.onActivity { main ->
            val locationBefore = field(main, "latestTrustedLocation")
            assertNotNull(locationBefore)
            val resumed = lifecycle(main).snapshot()
            val currentCue = completedCue(main)
            locations.eastM = 1.0
            locations.emitRetiredCallbacks()
            locations.eastM = 0.0
            assertSame("Nearby fresh samples from retired owners cannot replace current position", locationBefore,
                field(main, "latestTrustedLocation"))
            assertEquals(resumed, lifecycle(main).snapshot())
            assertEquals(currentCue, completedCue(main))
            assertTrue(field(main, "isRouteActive") as Boolean)
        }
        await(scenario, "Resumed route needs a new native DONE and current cue ACK", 30_000L) {
            speechEvents.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.DONE) > nativeDoneBefore && completedCue(it) != null
        }
        scenario.onActivity { main -> record("explicit_resume_new_route_native_done_and_ack", main); endFromUi(main) }
    }

    @Test(timeout = 240_000L)
    fun endingDuringRouteHttpRejectsLateResultAndAllowsANewJourney() = withHome { scenario, _ ->
        controlBackend(JSONObject().put("walking_delay_ms", 7_000))
        val firstDestination = searchAndSelect(scenario)
        start(scenario, firstDestination)
        await(scenario, "Actual client must own an active route HTTP request before cancellation", 85_000L) {
            (field(it, "routeRequestInFlight") as java.util.concurrent.atomic.AtomicBoolean).get() &&
                lifecycle(it).snapshot().state == WalkSessionState.ACTIVE
        }
        // The server records completion later; its 200 alone is never Android receipt evidence.
        SystemClock.sleep(750L)
        lateinit var firstEpoch: WalkRuntimeEpoch
        scenario.onActivity { main ->
            firstEpoch = lifecycle(main).snapshot().epoch
            assertTrue((field(main, "routeRequestInFlight") as java.util.concurrent.atomic.AtomicBoolean).get())
            assertFalse("The HTTP response must not have installed a route yet",
                (field(main, "routeNavigator") as RouteNavigator).hasRoute())
            record("actual_route_http_inflight_before_explicit_end", main)
            endFromUi(main)
        }
        SystemClock.sleep(8_000L)
        scenario.onActivity { main ->
            assertEquals(WalkSessionState.ENDED, lifecycle(main).snapshot().state)
            assertFalse(field(main, "isRouteActive") as Boolean)
            assertFalse((field(main, "routeNavigator") as RouteNavigator).hasRoute())
            assertEquals("HOME", field(main, "nativeUiPage").toString())
            record("late_route_http_cannot_reinstall_after_end", main)
        }
        // Preserve the same fixture leases/idempotency; only remove the external response delay.
        controlBackend(JSONObject().put("walking_delay_ms", 0))
        val nextDestination = searchAndSelect(scenario)
        start(scenario, nextDestination)
        awaitInstalledWithNativeCue(scenario, nextDestination)
        scenario.onActivity { main ->
            assertNotEquals(firstEpoch, lifecycle(main).snapshot().epoch)
            record("new_journey_after_late_http_has_stored_route_and_native_ack", main)
            endFromUi(main)
        }
    }

    @Test(timeout = 300_000L)
    fun leftTurnUsesNativeDirectionCueAndReachesArrival() = runTurnJourney("left", "9시 방향", 0.0, 1.0)

    @Test(timeout = 300_000L)
    fun rightTurnUsesNativeDirectionCueAndReachesArrival() = runTurnJourney("right", "3시 방향", 180.0, -1.0)


    /** Separate from the original ten journeys: rendered pixels and actual pointer routing. */
    @Test(timeout = 300_000L)
    fun previewSurfaceRendersGuidanceAndAcceptsCoordinateTouches() = withHome { scenario, locations ->
        val evidence = java.io.File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
            "preview-touch-${System.currentTimeMillis()}")
        assertTrue("Use a fresh evidence directory without replacing earlier screenshots", evidence.mkdir())
        captureRenderedScreen(scenario, evidence, "home_before_start")
        val firstDestination = searchAndSelect(scenario)
        touchControl(scenario, "nativeDestinationStartButton", "coordinate_start")
        awaitInstalledWithNativeCue(scenario, firstDestination)
        scenario.onActivity { main ->
            assertSame(firstDestination, field(main, "pendingUiDestination"))
            assertEquals("GUIDANCE", field(main, "nativeUiPage").toString())
            assertBoundPreviewGeometry(main)
            record("coordinate_guidance_active", main)
        }
        captureRenderedScreen(scenario, evidence, "guidance_active")
        touchControl(scenario, "nativeGuidanceCancelButton", "coordinate_active_end")
        awaitCoordinateEnd(scenario)
        captureRenderedScreen(scenario, evidence, "home_after_active_end")

        // A new real admission and storage ACK also exercise the Surface lifetime after END.
        val doneBeforeRestart = speechEvents.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.DONE)
        val destination = searchAndSelect(scenario)
        touchControl(scenario, "nativeDestinationStartButton", "coordinate_second_start")
        awaitInstalledWithNativeCue(scenario, destination)
        await(scenario, "The second route needs a new native DONE and its current ACK", 30_000L) {
            speechEvents.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.DONE) > doneBeforeRestart &&
                completedCue(it) != null
        }
        scenario.onActivity { assertBoundPreviewGeometry(it) }
        moveToAutomaticArrival(scenario, locations)
        awaitAutomaticArrival(scenario, locations, "coordinate_journey_automatic_arrival_ended_home")
        captureRenderedScreen(scenario, evidence, "home_after_automatic_arrival")
    }

    private fun runTurnJourney(shape: String, expectedPhrase: String, exitHeading: Double, northSign: Double) =
        withHome { scenario, locations ->
            controlBackend(JSONObject().put("route_shape", shape))
            val config = JSONObject(URL("$fixtureBaseUrl/__fixture/config").readText())
            assertEquals(shape, config.getString("route_shape"))
            val destination = searchAndSelect(scenario)
            val endpoint = config.getJSONObject("destination")
            assertEquals(endpoint.getDouble("latitude"), destination.point.latitude, 0.0000001)
            assertEquals(endpoint.getDouble("longitude"), destination.point.longitude, 0.0000001)
            requestEmulatorHeading(90.0)
            start(scenario, destination)
            awaitInstalledWithNativeCue(scenario, destination)
            awaitHeading(scenario, 90.0)
            scenario.onActivity { main ->
                assertEquals(WalkSessionState.ACTIVE, lifecycle(main).snapshot().state)
                locations.captureNavigationCallbacks(lifecycle(main).snapshot().epoch)
            }
            moveContinuously(locations, 50.0, 0.0, 50_000L)
            await(scenario, "The actual $shape navigation sentence needs matching native START/DONE and guide ACK", 30_000L) { main ->
                nativeUtteranceDone.any { id -> id in nativeUtteranceStarted && id in nativeUtteranceAccepted &&
                    id.second.startsWith("nav-") && nativeUtteranceMessages[id]?.contains(expectedPhrase) == true } && completedCue(main)?.guideIndex == 0
            }
            scenario.onActivity { record("${shape}_turn_native_direction_done_and_ack", it) }
            requestEmulatorHeading(exitHeading)
            awaitHeading(scenario, exitHeading)
            moveContinuously(locations, 50.0, northSign * 50.0, 50_000L)
            awaitAutomaticArrival(scenario, locations, "${shape}_automatic_arrival_ended_home")
        }


    private fun moveToAutomaticArrival(scenario: ActivityScenario<MainActivity>, locations: ControlledLocations) {
        scenario.onActivity { main ->
            assertEquals(WalkSessionState.ACTIVE, lifecycle(main).snapshot().state)
            locations.captureNavigationCallbacks(lifecycle(main).snapshot().epoch)
        }
        for (eastM in 5..100 step 5) {
            var active = false
            scenario.onActivity { main ->
                observeSpeech(main)
                active = lifecycle(main).snapshot().state == WalkSessionState.ACTIVE
                if (active) locations.eastM = eastM.toDouble()
            }
            if (!active) break
            SystemClock.sleep(3_000L)
        }
    }

    private fun awaitAutomaticArrival(
        scenario: ActivityScenario<MainActivity>,
        locations: ControlledLocations,
        stage: String,
    ) {
        val finalMessage = "도착했습니다. 안내를 종료합니다"
        await(scenario, "Automatic arrival needs one actual native final utterance, ENDED and HOME", 50_000L) { main ->
            val arrivals = nativeUtteranceMessages.filterValues { it == finalMessage }.keys
            arrivals.size == 1 && arrivals.single() in nativeUtteranceAccepted &&
                arrivals.single() in nativeUtteranceStarted && arrivals.single() in nativeUtteranceDone &&
                lifecycle(main).snapshot().state == WalkSessionState.ENDED &&
                field(main, "nativeUiPage").toString() == "HOME" &&
                field(main, "isRouteActive") == false && !(field(main, "routeNavigator") as RouteNavigator).hasRoute()
        }
        lateinit var endedEpoch: WalkRuntimeEpoch
        var completedAtEnd: RouteSpeechCueToken? = null
        var requestGenerationAtEnd = -1
        var navigationCountsAtEnd: List<Int> = emptyList()
        scenario.onActivity { main ->
            endedEpoch = lifecycle(main).snapshot().epoch
            completedAtEnd = completedCue(main)
            requestGenerationAtEnd = (MainActivityControllerTestFixture.controller(main,
                "routeAcquisitionController") as RouteAcquisitionController).requestGeneration
            navigationCountsAtEnd = navigationOutputCounts()
            assertFalse("Arrival must not offer a confirmation or rejection button",
                descendants(main.window.decorView).filterIsInstance<Button>().any {
                    it.text.toString() in setOf("도착 확인", "도착 아님")
                })
            assertEquals(requireNotNull(locations.capturedNavigationEpoch).walkSessionId, endedEpoch.walkSessionId)
            assertNotEquals("Arrival retires the ACTIVE motion epoch", locations.capturedNavigationEpoch, endedEpoch)
            assertEquals("Account GPS must bind the ended motion owner", endedEpoch,
                (field(main, "positioningCollectionBinding") as PositioningCollectionBinding)
                    .coordinator.currentUserMotionOwner()?.walkEpoch)
            record(stage, main)
            // The account subscription remains useful on HOME. Its real callback must no longer
            // dispatch work for the ended walk, even when a fresh near-endpoint fix arrives.
            locations.eastM += 5.0
            assertTrue("Deliver the actual callback captured during ACTIVE", locations.emitNavigationCallbacks() > 0)
            locations.emitRetiredCallbacks()
        }
        SystemClock.sleep(1_000L)
        scenario.onActivity { main ->
            assertEquals(endedEpoch, lifecycle(main).snapshot().epoch)
            assertEquals(WalkSessionState.ENDED, lifecycle(main).snapshot().state)
            assertEquals("HOME", field(main, "nativeUiPage").toString())
            assertFalse((field(main, "routeNavigator") as RouteNavigator).hasRoute())
            assertFalse(field(main, "isRouteActive") as Boolean)
            assertEquals("Post-END GPS cannot request another route", requestGenerationAtEnd,
                (MainActivityControllerTestFixture.controller(main,
                    "routeAcquisitionController") as RouteAcquisitionController).requestGeneration)
            assertEquals("Post-END GPS cannot acknowledge another navigation cue", completedAtEnd, completedCue(main))
            assertEquals("Post-END GPS cannot request or deliver navigation speech", navigationCountsAtEnd,
                navigationOutputCounts())
            assertEquals("The ACTIVE motion owner cannot be restored by its account GPS callback", endedEpoch,
                (field(main, "positioningCollectionBinding") as PositioningCollectionBinding)
                    .coordinator.currentUserMotionOwner()?.walkEpoch)
            assertEquals("Final arrival speech must be submitted once", 1,
                nativeUtteranceMessages.count { it.value == finalMessage })
            record("${stage}_post_end_navigation_unchanged", main)
        }
    }

    private fun navigationOutputCounts(): List<Int> = listOf(
        SpeechOutputOutcome.REQUESTED, SpeechOutputOutcome.STARTED, SpeechOutputOutcome.DONE,
    ).map { speechEvents.count(SpeechOutputKind.NAVIGATION, it) }

    private fun touchControl(scenario: ActivityScenario<MainActivity>, fieldName: String, stage: String) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Pointer injection must use the instrumentation thread" }
        var x = 0f
        var y = 0f
        scenario.onActivity { main ->
            val target = field(main, fieldName) as View
            val global = Rect()
            val local = Rect()
            assertTrue("Coordinate target must be shown, attached, enabled and clickable",
                target.isShown && target.isAttachedToWindow && target.isEnabled && target.isClickable)
            assertTrue("Coordinate target must own a nonempty global visible rect", target.getGlobalVisibleRect(global) && !global.isEmpty)
            assertTrue(target.getLocalVisibleRect(local) && !local.isEmpty)
            val onScreen = IntArray(2)
            target.getLocationOnScreen(onScreen)
            x = onScreen[0] + local.exactCenterX()
            y = onScreen[1] + local.exactCenterY()
            instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                "HOME_JOURNEY pointer_stage=$stage; target=$fieldName; global_rect=$global; screen_x=$x; screen_y=$y; delivery=MotionEvent\n") })
        }
        val downAt = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN)
        try { SystemClock.sleep(50L) } finally { send(MotionEvent.ACTION_UP) }
        // The caller asserts the real transition. No View.performClick fallback is permitted.
    }

    private fun awaitCoordinateEnd(scenario: ActivityScenario<MainActivity>) {
        await(scenario, "Pointer END must reach ENDED/HOME and clear the route", 8_000L) {
            lifecycle(it).snapshot().state == WalkSessionState.ENDED &&
                field(it, "nativeUiPage").toString() == "HOME" && field(it, "isRouteActive") == false &&
                !(field(it, "routeNavigator") as RouteNavigator).hasRoute()
        }
        scenario.onActivity { record("coordinate_end_home", it) }
    }

    private fun assertBoundPreviewGeometry(main: MainActivity) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val preview = field(main, "cameraFallbackPreviewView") as PreviewView
        assertEquals(PreviewView.ImplementationMode.PERFORMANCE, preview.implementationMode)
        assertTrue("Bound guidance preview must have visible laid-out geometry",
            preview.isShown && preview.isLaidOut && preview.width > 0 && preview.height > 0)
        val renderers = descendants(preview).filter { it is SurfaceView || it is TextureView }
        assertEquals("Observe the actual CameraX renderer, including a legitimate TextureView fallback", 1, renderers.size)
        assertTrue(renderers.single().width > 0 && renderers.single().height > 0)
        val holder = MainActivity::class.java.getDeclaredField("cameraFallbackControllerHolder")
            .apply { isAccessible = true }.get(main) as Lazy<*>
        assertTrue("Geometry observation must not initialize a new camera controller", holder.isInitialized())
        val controller = requireNotNull(holder.value)
        val resources = requireNotNull(controller.javaClass.getDeclaredField("resources").apply { isAccessible = true }.get(controller))
        val analysis = resources.javaClass.getDeclaredField("analysis").apply { isAccessible = true }.get(resources) as ImageAnalysis
        val info = requireNotNull(analysis.resolutionInfo)
        val size = info.resolution
        val crop = info.cropRect
        assertTrue(size.width > 0 && size.height > 0)
        assertTrue(!crop.isEmpty && crop.left >= 0 && crop.top >= 0 && crop.right <= size.width && crop.bottom <= size.height)
        assertTrue(info.rotationDegrees in setOf(0, 90, 180, 270))
        instrumentation.sendStatus(0, Bundle().apply { putString("stream",
            "HOME_JOURNEY bound_analysis_size=${size.width}x${size.height}; bound_analysis_crop=$crop; " +
                "bound_analysis_rotation=${info.rotationDegrees}; geometry_scope=bound_resolution_not_per_frame; ${previewResourceDiagnostic(main)}\n") })
    }

    private fun captureRenderedScreen(scenario: ActivityScenario<MainActivity>, directory: java.io.File, stage: String) {
        check(Looper.myLooper() != Looper.getMainLooper())
        var secure = false
        scenario.onActivity { main ->
            secure = main.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            assertTrue(main.window.decorView.isShown)
            record("pixels_$stage", main)
        }
        if (secure) {
            instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                "HOME_JOURNEY screenshot_stage=$stage; screenshot_unavailable=FLAG_SECURE; pixels=UNVERIFIED\n") })
            return
        }
        awaitCommittedCaptureFrame(scenario)
        val image = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
        if (image == null) {
            instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                "HOME_JOURNEY screenshot_stage=$stage; screenshot_unavailable=capture_not_returned; pixels=UNVERIFIED\n") })
            return
        }
        try {
            val output = java.io.File(directory, "$stage.png")
            assertFalse("Preserve previous screenshot evidence", output.exists())
            output.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                "HOME_JOURNEY screenshot_stage=$stage; screenshot_path=${output.absolutePath}; pixels=REVIEW_REQUIRED\n") })
        } finally { image.recycle() }
    }

    /** Observe a submitted UI frame without changing layout, scrolling or runtime state. */
    private fun awaitCommittedCaptureFrame(scenario: ActivityScenario<MainActivity>) {
        check(Looper.myLooper() != Looper.getMainLooper())
        val observed = java.util.concurrent.CountDownLatch(1)
        val retired = java.util.concurrent.atomic.AtomicBoolean(false)
        var drawView: View? = null
        var observer: android.view.ViewTreeObserver? = null
        val secondAnimation = Runnable { if (!retired.get()) observed.countDown() }
        val firstAnimation = Runnable { if (!retired.get()) drawView?.postOnAnimation(secondAnimation) }
        val committed = Runnable { if (!retired.get()) drawView?.postOnAnimation(firstAnimation) }
        try {
            scenario.onActivity { main ->
                val decor = main.window.decorView
                assertTrue("Frame observation requires the attached hardware-rendered window",
                    decor.isAttachedToWindow && decor.isShown && decor.isHardwareAccelerated)
                drawView = decor
                observer = decor.viewTreeObserver
                assertTrue(requireNotNull(observer).isAlive)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    requireNotNull(observer).registerFrameCommitCallback(committed)
                    decor.invalidate()
                } else {
                    fail("This screenshot observation requires API 29 frame commit callbacks")
                }
            }
            assertTrue("The current UI frame was not committed within the capture observation bound",
                observed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            retired.set(true)
            scenario.onActivity {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    observer?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(committed)
                }
                drawView?.removeCallbacks(firstAnimation)
                drawView?.removeCallbacks(secondAnimation)
            }
        }
        // Submission is not proof of visible pixels; keep the real screenshot review separate.
    }

    private fun lifecycle(main: MainActivity) = field(main, "walkSessionLifecycle") as WalkSessionLifecycle
    private fun completedCue(main: MainActivity): RouteSpeechCueToken? = RouteNavigator::class.java
        .getDeclaredField("completedSpeechCue").apply { isAccessible = true }
        .get(field(main, "routeNavigator")) as? RouteSpeechCueToken

    private fun awaitInstalledWithNativeCue(scenario: ActivityScenario<MainActivity>, destination: DestinationSearchResult) {
        await(scenario, "Actual readiness/HTTP/storage did not install the route", 85_000L) {
            field(it, "isRouteActive") == true && lifecycle(it).snapshot().state == WalkSessionState.ACTIVE &&
                (field(it, "routeNavigator") as RouteNavigator).hasRoute()
        }
        assertStoredDestination(scenario, destination)
        await(scenario, "Initial actual navigation needs native DONE and current ACK", 30_000L) {
            speechEvents.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.DONE) > 0 && completedCue(it) != null
        }
    }

    private fun assertStoredDestination(scenario: ActivityScenario<MainActivity>, destination: DestinationSearchResult) {
        lateinit var store: EncryptedRouteSnapshotStore
        lateinit var scope: RouteSnapshotScope
        scenario.onActivity { main ->
            store = field(main, "routeSnapshotStore") as EncryptedRouteSnapshotStore
            scope = field(main, "routeSnapshotWalkScope") as RouteSnapshotScope
        }
        val saved = requireNotNull(store.load(scope, System.currentTimeMillis()))
        assertEquals(destination.point, saved.destination)
        assertEquals(scope.walkSessionId, saved.walkSessionId)
    }

    private fun endFromUi(main: MainActivity) {
        val end = field(main, "nativeGuidanceCancelButton") as View
        assertTrue(end.isShown && end.performClick())
        assertEquals(WalkSessionState.ENDED, lifecycle(main).snapshot().state)
        assertEquals("HOME", field(main, "nativeUiPage").toString())
        record("explicit_ui_end", main)
    }

    private fun requestEmulatorHeading(degrees: Double) {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("fixtureRawSensorControl"))
        // Host runner changes only this owned emulator's raw magnetic sensor; no app state is set.
        android.util.Log.i("HomeJourneySensor", "requested_heading=$degrees")
    }

    private fun awaitHeading(scenario: ActivityScenario<MainActivity>, expected: Double) {
        await(scenario, "Actual raw-sensor compass did not reach requested heading $expected", 25_000L) {
            val result = call(it, "currentRouteCompassHeadingResult", SystemClock.elapsedRealtime()) as? RouteCompassHeadingResult
            val degrees = result?.observation?.degreesTrueNorth
            degrees != null && kotlin.math.abs((degrees - expected + 540.0) % 360.0 - 180.0) <= 18.0
        }
    }

    private fun moveContinuously(locations: ControlledLocations, east: Double, north: Double, durationMs: Long) {
        val startEast = locations.eastM
        val startNorth = locations.northM
        val started = SystemClock.elapsedRealtime()
        do {
            val fraction = ((SystemClock.elapsedRealtime() - started).toDouble() / durationMs).coerceIn(0.0, 1.0)
            instrumentation.runOnMainSync {
                locations.eastM = startEast + (east - startEast) * fraction
                locations.northM = startNorth + (north - startNorth) * fraction
            }
            if (fraction >= 1.0) break
            SystemClock.sleep(100L)
        } while (true)
    }

    private fun withHome(persistSyntheticDeviceCheck: Boolean = false,
        body: (ActivityScenario<MainActivity>, ControlledLocations) -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Isolated emulator fixture opt-in required", args.getString("runHomeJourneyFixture") == "true")
        assertEquals("kr.co.hanium.dreamup.walksafe.speechstorefixture", instrumentation.targetContext.packageName)
        assertTrue(BuildConfig.DEBUG)
        assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
        // Check before launching: a permission dialog would steal foreground from this journey.
        val requiredPermissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        for (permission in requiredPermissions) {
            assertEquals("Prepare the isolated fixture permission before launch: $permission",
                PackageManager.PERMISSION_GRANTED, instrumentation.targetContext.checkSelfPermission(permission))
        }
        val base = requireNotNull(args.getString("fixtureBaseUrl"))
        fixtureBaseUrl = base
        require(URL(base).host in setOf("127.0.0.1", "localhost"))
        controlBackend(JSONObject().put("reset", true))
        val config = JSONObject(URL("$base/__fixture/config").readText())
        val onboarding = call(previous, "completedOnboarding") as FirstRunOnboardingSnapshot
        assertEquals(config.getString("actor_id"), requireNotNull(onboarding.verifiedActorBinding).value)
        val session = GatewayFieldSession.longLivedSession(
            base, config.getString("actor_id"), config.getString("device_id"),
            config.getString("family_id"), config.getLong("rotation"), config.getString("cookie_pair"),
            config.getString("refresh_token"), config.getLong("access_expires_at_epoch_ms"),
            config.getLong("idle_expires_at_epoch_ms"), config.getLong("absolute_expires_at_epoch_ms"),
        )
        assertNull("Never replace an existing account", GatewaySessionProcessCoordinator.snapshot().session)
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
        val generation = GatewaySessionProcessCoordinator.snapshot().generation
        val locations = ControlledLocations()
        var restoreDeviceStore: (() -> Unit)? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                call(previous, "awaitPrivacyStartupReady", scenario)
                scenario.onActivity { main ->
                    // Keep real SDK measurements restored from earlier app runs unchanged.
                    val capabilityNames = listOf("metricDistanceCapabilityOverride", "onDeviceSpeechRecognitionCapabilityOverride", "offlineKoreanTextToSpeechCapabilityOverride")
                    val capabilitiesBefore = capabilityNames.associateWith { field(main, it) }
                    // Synthetic completed-account prerequisites only. Prewalk producers remain real.
                    set(main, "firstRunOnboardingSnapshot", onboarding)
                    set(main, "reporterUserId", session.actorId)
                    set(main, "priorityUserOnboardingActorId", session.actorId)
                    set(main, "priorityUserOnboardingPolicy", call(previous, "completedPriorityEducation"))
                    set(main, "postLoginDeviceCheckSnapshot", call(previous, "completedDeviceCheck", session.actorId, generation))
                    if (persistSyntheticDeviceCheck) {
                        restoreDeviceStore = preserveDeviceStore(main)
                    }
                    for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.RECORD_AUDIO)) {
                        assertEquals(permission, PackageManager.PERMISSION_GRANTED, main.checkSelfPermission(permission))
                    }
                    assertTrue("Activity must actually be foreground", field(main, "isActivityForeground") as Boolean)
                    call(main, "stopLocationUpdates", true)
                    set(main, "fusedLocationClient", locations.client)
                    call(main, "renderMainUi")
                    assertTrue("Completed synthetic account must make HOME available", call(main, "shouldShowNativeHome") as Boolean)
                    assertEquals("HOME", field(main, "nativeUiPage").toString())
                    val capabilitiesAfter = capabilityNames.associateWith { field(main, it) }
                    assertEquals("Synthetic account prerequisites must not change actual capability measurements", capabilitiesBefore, capabilitiesAfter)
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "HOME_JOURNEY capability_measurements_before=$capabilitiesBefore; after=$capabilitiesAfter\n")
                    })
                    observeRiskStop(main)
                    record("actual_oncreate_home", main)
                }
                body(scenario, locations)
            }
        } finally {
            try {
                instrumentation.runOnMainSync { locations.close(); restoreDeviceStore?.invoke() }
            } finally {
                GatewaySessionProcessCoordinator.clear(generation)
                controlBackend(JSONObject().put("reset", true))
            }
        }
    }

    private fun preserveDeviceStore(main: MainActivity): () -> Unit {
        val store = field(main, "postLoginDeviceCheckResultStore") as kr.co.hanium.dreamup.walksafe.device.AndroidDeviceCheckResultStore
        val preferences = store.javaClass.getDeclaredField("preferences").apply { isAccessible = true }
            .get(store) as android.content.SharedPreferences
        val keys = store.javaClass.declaredFields.filter {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java && it.name.endsWith("_KEY")
        }.map { it.apply { isAccessible = true }.get(null) as String }.toSet()
        assertEquals("Preserve only the actual seven store keys", 7, keys.size)
        val previousValues = preferences.all.filterKeys { it in keys }
        return {
            val editor = preferences.edit()
            keys.forEach(editor::remove)
            previousValues.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
                    else -> error("Unexpected persisted store type")
                }
            }
            assertTrue("Original store values must be restored without clearing other preferences", editor.commit())
            assertTrue("Original store values must match", previousValues == preferences.all.filterKeys { it in keys })
        }
    }

    private fun persistDeviceCheckPrerequisite(main: MainActivity, actorId: String) {
        assertEquals("44444444-4444-4444-8444-444444444444", actorId)
        val store = field(main, "postLoginDeviceCheckResultStore") as kr.co.hanium.dreamup.walksafe.device.AndroidDeviceCheckResultStore
        val binding = call(main, "currentPostLoginDeviceCheckResultBinding", actorId)
            as kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckResultBinding
        if (store.hasCurrentPolicyResultCandidate()) {
            assertNotNull("An existing current-policy result must belong to this exact synthetic binding", store.restore(binding))
        }
        val snapshot = field(main, "postLoginDeviceCheckSnapshot") as kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
        val deferred = call(main, "getPostLoginCameraDependentChecksDeferred") as Boolean
        val metric = call(main, "getPostLoginMetricDepthState") as kr.co.hanium.dreamup.walksafe.device.PostLoginMetricDepthState
        val names = listOf("metricDistanceCapabilityOverride", "onDeviceSpeechRecognitionCapabilityOverride", "offlineKoreanTextToSpeechCapabilityOverride")
        val measurements = names.associateWith { field(main, it) }
        assertTrue(store.save(snapshot, binding, deferred, metric))
        val restored = requireNotNull(store.restore(binding))
        assertEquals(snapshot.state, restored.state)
        assertEquals(snapshot.disabledFeatures, restored.disabledFeatures)
        assertEquals(deferred, restored.cameraDependentChecksDeferred)
        assertEquals(metric, restored.metricDepthState)
        assertEquals("Saving synthetic prior prerequisites cannot alter live SDK measurements", measurements,
            names.associateWith { field(main, it) })
        instrumentation.sendStatus(0, Bundle().apply { putString("stream",
            "HOME_JOURNEY synthetic_device_prerequisite_saved=true; exact_binding_restore=true; existing_keys_preserved=true; raw_measurements_unchanged=true\n") })
    }

    private fun searchAndSelect(scenario: ActivityScenario<MainActivity>): DestinationSearchResult {
        openSearchAndSubmit(scenario)
        return awaitAndSelectSearch(scenario)
    }

    private fun openSearchAndSubmit(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { main ->
            val card = descendants(main.window.decorView).filterIsInstance<TextView>()
                .firstOrNull { it.isShown && it.text.toString() in setOf("길라잡이", "목적지 검색") }
                ?.let(::clickableAncestor)
            assertNotNull("Actual HOME search card is missing", card)
            assertTrue(requireNotNull(card).performClick())
            assertEquals("DESTINATION_SEARCH", field(main, "nativeUiPage").toString())
            val input = field(main, "destinationQueryInput") as EditText
            assertTrue(input.isShown)
            input.setText("통합시험 목적지")
            assertTrue((field(main, "destinationSearchButton") as View).performClick())
        }
    }

    private fun awaitAndSelectSearch(scenario: ActivityScenario<MainActivity>): DestinationSearchResult {
        await(scenario, "HTTP destination search did not render candidates", 25_000L) {
            (field(it, "destinationSearchResults") as List<*>).isNotEmpty() &&
                !(field(it, "destinationSearchInFlight") as Boolean)
        }
        lateinit var destination: DestinationSearchResult
        scenario.onActivity { main ->
            destination = (field(main, "destinationSearchResults") as List<*>).first() as DestinationSearchResult
            val candidates = descendants(main.window.decorView).filterIsInstance<Button>()
                .filter { it.isShown && it.isEnabled && it.text.toString().contains(destination.name) }
            assertTrue("HTTP candidate must be rendered as an actual selectable control", candidates.isNotEmpty())
            assertTrue(candidates.first().performClick())
            assertEquals("DESTINATION_CONFIRM", field(main, "nativeUiPage").toString())
            assertSame(destination, field(main, "pendingUiDestination"))
            record("http_candidate_selected", main)
        }
        return destination
    }

    private fun start(scenario: ActivityScenario<MainActivity>, destination: DestinationSearchResult) {
        scenario.onActivity { main ->
            val start = field(main, "nativeDestinationStartButton") as View
            assertTrue(start.isShown && start.isEnabled && start.performClick())
            assertSame(destination, field(main, "pendingUiDestination"))
            assertEquals("GUIDANCE", field(main, "nativeUiPage").toString())
            record("start_clicked", main)
        }
    }

    private fun await(scenario: ActivityScenario<MainActivity>, message: String, timeoutMs: Long,
        predicate: (MainActivity) -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        var diagnostic = ""
        while (SystemClock.elapsedRealtime() < until) {
            var passed = false
            scenario.onActivity { main -> observeSpeech(main); passed = predicate(main); diagnostic = diagnostic(main) }
            if (passed) return
            SystemClock.sleep(100L)
        }
        fail("$message; $diagnostic")
    }

    private fun diagnostic(main: MainActivity): String {
        val walk = (field(main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot()
        val names = listOf("nativeUiPage", "latestNavigationState", "startupCapabilityDecision",
            "officialEnvironmentPreflightPhase", "officialEnvironmentGpsEvidence", "officialEnvironmentCameraEvidence",
            "latestPhoneMountingCameraAssessment", "isRouteActive", "nativePhoneMountingCheckRequest")
        return names.joinToString("; ") { name -> "$name=${runCatching { field(main, name) }.getOrNull()}" } +
            "; lifecycle=${walk.state}; readiness_blocking=${walk.readiness?.blockingReasons}" +
            "; readiness_plan=${walk.readiness?.plan}" +
            "; tts=${(field(main, "feedbackActuator") as? AndroidFeedbackActuator)?.statusText()}" +
            "; speech_events=${speechEvents.snapshot()}"
    }

    private fun record(stage: String, main: MainActivity) {
        observeSpeech(main)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "HOME_JOURNEY stage=$stage; ${diagnostic(main)}; " +
            "synthetic_account=true; prior_device_check=fixture; http=localhost_fixture; fused=controlled; " +
            "native_prewalk=true; tts=native; speech_events=${speechEvents.snapshot()}; " +
            "${auxiliaryResourceDiagnostic(main)}; ${previewResourceDiagnostic(main)}; physical_walk=UNVERIFIED\n") })
    }

    private fun auxiliaryResourceDiagnostic(main: MainActivity): String = runCatching {
        val lazyOwner = field(main, "unknownServiceOwnerLazy") as Lazy<*>
        val initialized = lazyOwner.isInitialized()
        // Never create an owner merely to observe whether the automatic path created it.
        val owner = if (initialized) lazyOwner.value as UnknownRuntimeServiceOwner else null
        val ar = main.captureRuntimeArObservation()
        "unknown_owner_initialized=$initialized; " +
            "unknown_current_service_present=${owner?.currentService != null}; " +
            "unknown_loading_service_present=${owner?.loadingService != null}; " +
            "unknown_closing=${owner?.closing}; unknown_calibration_present=${owner?.hasCalibration}; " +
            "runtime_ar_session_present=${ar.session != null}; runtime_ar_provider_present=${ar.provider != null}; " +
            "runtime_ar_purpose=${ar.purpose}"
    }.getOrElse { "auxiliary_diagnostic_unavailable=${it.javaClass.simpleName}" }

    private fun previewResourceDiagnostic(main: MainActivity): String = runCatching {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Preview observation requires the UI thread" }
        val preview = field(main, "cameraFallbackPreviewView") as PreviewView
        fun visibility(view: View) = when (view.visibility) {
            View.VISIBLE -> "VISIBLE"
            View.INVISIBLE -> "INVISIBLE"
            View.GONE -> "GONE"
            else -> "UNKNOWN"
        }
        val childTypes = descendants(preview).drop(1).map {
            when (it) {
                is SurfaceView -> "SurfaceView"
                is TextureView -> "TextureView"
                else -> it.javaClass.simpleName
            }
        }.distinct().sorted().joinToString("+").ifEmpty { "NONE" }
        val layers = listOf("surfaceView", "debugBboxOverlay", "controlsScroll", "walkSafetyScroll", "cameraScreenControls")
            .joinToString("; ") { name ->
                val view = field(main, name) as View
                "${name}_visibility=${visibility(view)}; ${name}_shown=${view.isShown}"
            }
        "preview_mode=${preview.implementationMode}; preview_visibility=${visibility(preview)}; " +
            "preview_shown=${preview.isShown}; preview_laid_out=${preview.isLaidOut}; " +
            "preview_size=${preview.width}x${preview.height}; preview_child_types=$childTypes; $layers"
    }.getOrElse { "preview_diagnostic_unavailable=${it.javaClass.simpleName}" }

    private fun observeRiskStop(main: MainActivity) {
        observeFailedDetectorCompletion(main)
        val coordinator = requireNotNull(field(main, "walkRuntimeSafetyCoordinator"))
        @Suppress("UNCHECKED_CAST")
        val previous = requireNotNull(field(coordinator, "completionCallback") as? ((WalkRuntimeSafetyStop, (Boolean) -> Unit) -> Unit))
        val observer: (WalkRuntimeSafetyStop, (Boolean) -> Unit) -> Unit = { stop, acknowledge ->
            runCatching {
                val observedAt = SystemClock.elapsedRealtime()
                val nativeBlocked = runCatching {
                    (call(main, "getPrimaryDetectorOwner") as PrimaryDetectorExecutionOwner).nativeWorkBlocked()
                }.getOrNull()
                val detectorFacts = runCatching { detectorFailureDiagnostic(main, observedAt) }
                    .getOrElse { "detector_diagnostic_unavailable=${it.javaClass.simpleName}" }
                val frames = Throwable().stackTrace.asSequence()
                    .filter { it.className.startsWith("kr.co.hanium.dreamup.walksafe.") }
                    .take(14).joinToString(" <- ")
                android.util.Log.i("HomeJourneyRiskCaller", "at=$observedAt; causes=${stop.causes}; " +
                    "native_work_blocked=$nativeBlocked; $detectorFacts; $frames")
            }
            // Diagnosis must neither swallow nor duplicate the actual cleanup/ACK callback.
            previous(stop, acknowledge)
        }
        set(coordinator, "completionCallback", observer)
    }

    private fun detectorFailureDiagnostic(main: MainActivity, observedAt: Long): String {
        val owner = call(main, "getPrimaryDetectorOwner") as PrimaryDetectorExecutionOwner
        val detector = owner.projection()
        val pacing = requireNotNull(field(owner, "pacing"))
        val ticket = field(pacing, "activeTicket") as? InferencePacingTicket
        val startedAt = ticket?.startedAtElapsedRealtimeMs
        if (ticket != null) {
            detectorFailureSamples.putIfAbsent(ticket,
                DetectorFailureSample(observedAt, detector.frameGeneration, detector.loadGeneration,
                    (field(main, "walkSessionLifecycle") as WalkSessionLifecycle).currentRuntimeEpochOrNull()))
        }
        // finishWork has not recorded this invocation yet: these are previous completed samples.
        val previousSample = owner.pacingSnapshot()
        val supervisor = requireNotNull(field(main, "detectorRuntimeSupervisor"))
        val clockStart = field(supervisor, "validOutputClockStartMs") as? Long
        val run = field(supervisor, "currentRun") as? Pair<*, *>
        val runEpoch = run?.second as? WalkRuntimeEpoch
        val currentEpoch = (field(main, "walkSessionLifecycle") as WalkSessionLifecycle).currentRuntimeEpochOrNull()
        return "detector_class=${detector.detector.javaClass.name}; detector_available=${detector.available}; " +
            "detector_closing=${detector.closing}; frame_generation=${detector.frameGeneration}; " +
            "load_generation=${detector.loadGeneration}; work_in_flight=${detector.workInFlight}; " +
            "active_ticket_identity=${ticket?.let { System.identityHashCode(it) }}; " +
            "active_ticket_start=$startedAt; active_ticket_age=${startedAt?.let { observedAt - it }}; " +
            "previous_completed_samples=${previousSample.completedSamples}; " +
            "previous_sample_inference_ms=${previousSample.lastInferenceMs}; " +
            "previous_sample_end_to_end_ms=${previousSample.lastEndToEndMs}; " +
            "previous_sample_reason=${previousSample.reason}; " +
            "valid_output_clock_start=$clockStart; valid_output_gap=${clockStart?.let { observedAt - it }}; " +
            "maximum_valid_output_gap=${field(supervisor, "maximumValidOutputGapMs")}; " +
            "consecutive_failures=${field(supervisor, "consecutiveFailures")}; " +
            "supervisor_run_generation=${run?.first as? Int}; " +
            "supervisor_run_recovery_generation=${runEpoch?.recoveryGeneration}; " +
            "supervisor_run_epoch_current=${runEpoch != null && runEpoch == currentEpoch}"
    }

    private fun observeFailedDetectorCompletion(main: MainActivity) {
        val worker = requireNotNull(call(main, "getPrimaryCameraFallbackDetectorWorker"))
        val invocationWorker = requireNotNull(call(main, "getPrimaryDetectorInvocationWorker"))
        val owner = call(main, "getPrimaryDetectorOwner") as PrimaryDetectorExecutionOwner
        val previous = field(worker, "completion") as PrimaryWorkCompletion
        val observer = PrimaryWorkCompletion { ticket, invoked, inferenceDurationMs, validCompletion ->
            val failure = detectorFailureSamples.remove(ticket)
            // Read before finish releases the slot, so the next call cannot replace this snapshot.
            val timingRead = runCatching { field(invocationWorker, "latestNativeTiming") as? PrimaryNativeTimingSnapshot }
            val timing = timingRead.getOrNull()
            val generationAtCompletion = owner.projection().frameGeneration
            try {
                previous.finish(ticket, invoked, inferenceDurationMs, validCompletion)
            } finally {
                val sampleIndex = if (invoked) detectorTimingSamples.incrementAndGet() else detectorUninvokedSamples.incrementAndGet()
                // Bound successful output; keep any failing ticket even after the sample window.
                if (failure != null || sampleIndex <= (if (invoked) 128 else 8)) runCatching {
                    recordDetectorTiming(ticket, invoked, inferenceDurationMs, validCompletion, failure,
                        timing, timingRead.exceptionOrNull()?.javaClass?.simpleName, generationAtCompletion, sampleIndex)
                }
                // Same ticket as the earlier stop; cleanup time must not become the capture age.
                if (failure != null) runCatching {
                    val failureAge = failure.observedAt - ticket.startedAtElapsedRealtimeMs
                    android.util.Log.i("HomeJourneyDetectorCompletion",
                        "ticket_identity=${System.identityHashCode(ticket)}; ticket_start=${ticket.startedAtElapsedRealtimeMs}; " +
                            "failure_at=${failure.observedAt}; age_at_failure_ms=$failureAge; " +
                            "failure_frame_generation=${failure.frameGeneration}; failure_load_generation=${failure.loadGeneration}; " +
                            "invoked=$invoked; same_invocation_duration_ms=$inferenceDurationMs; valid_completion=$validCompletion; " +
                            "non_inference_remainder_ms_approx=${inferenceDurationMs?.let { failureAge - it }}; " +
                            "after_cleanup_observed_at=${SystemClock.elapsedRealtime()}")
                }
            }
        }
        set(worker, "completion", observer)
    }

    private fun recordDetectorTiming(
        ticket: InferencePacingTicket, invoked: Boolean, inferenceDurationMs: Long?, validCompletion: Boolean,
        failure: DetectorFailureSample?, snapshot: PrimaryNativeTimingSnapshot?, readError: String?,
        generationAtCompletion: Int, sampleIndex: Int,
    ) {
        val sameTicket = snapshot?.pacingTicket === ticket
        val sample = snapshot?.takeIf { sameTicket }
        val timing = sample?.timing
        val runtime = timing?.modelRuntime
        val values = linkedMapOf<String, Any?>(
            "sample_index" to sampleIndex, "sample_limit" to if (invoked) 128 else 8,
            "ticket_identity" to System.identityHashCode(ticket), "ticket_start" to ticket.startedAtElapsedRealtimeMs,
            "invoked" to invoked, "valid_completion" to validCompletion,
            "snapshot_present" to (snapshot != null), "same_ticket" to sameTicket,
            "snapshot_relation" to when {
                readError != null -> "READ_ERROR"
                snapshot == null -> "NO_RETURNED_SNAPSHOT"
                !sameTicket -> "DIFFERENT_TICKET"
                else -> "SAME_TICKET"
            },
            "diagnostic_error" to readError, "failure_at" to failure?.observedAt,
            "failure_frame_generation" to failure?.frameGeneration,
            "owner_generation_at_completion" to generationAtCompletion,
            "snapshot_frame_generation" to sample?.frameGeneration,
            "snapshot_recovery_generation" to sample?.epoch?.recoveryGeneration,
            "snapshot_epoch_matches_failure" to sample?.let { s -> failure?.let { s.epoch == it.epoch } },
            "failure_generation_delta" to failure?.let { f -> sample?.let { f.frameGeneration - it.frameGeneration } },
            "captured_at" to sample?.capturedAtElapsedRealtimeMs,
            "native_started_at" to sample?.nativeStartedAtElapsedRealtimeMs,
            "native_returned_at" to sample?.nativeReturnedAtElapsedRealtimeMs,
            "result_age_ms" to sample?.let { it.nativeReturnedAtElapsedRealtimeMs - it.capturedAtElapsedRealtimeMs },
            "before_native_ms" to sample?.let { it.nativeStartedAtElapsedRealtimeMs - it.capturedAtElapsedRealtimeMs },
            "native_call_elapsed_ms" to sample?.let { it.nativeReturnedAtElapsedRealtimeMs - it.nativeStartedAtElapsedRealtimeMs },
            "same_invocation_duration_ms" to inferenceDurationMs, "status" to sample?.status?.name,
            "yuv_decode_ms" to timing?.yuvDecodeMs, "model_preprocess_ms" to timing?.modelPreprocessMs,
            "model_inference_ms" to timing?.modelInferenceMs, "model_parse_ms" to timing?.modelParseMs,
            "owned_tensor_copy_ms" to timing?.ownedTensorCopyMs, "reported_total_ms" to timing?.totalMs,
            "person_supplement_inference_ms" to timing?.personSupplementInferenceMs,
            "person_supplement_parse_ms" to timing?.personSupplementParseMs,
            "model_key" to timing?.modelKey, "requested_delegate" to runtime?.requestedDelegate,
            "active_delegate" to runtime?.activeDelegate, "requested_threads" to runtime?.numThreads,
            "delegate_fallback_used" to runtime?.fallbackUsed,
            "after_cleanup_observed_at" to SystemClock.elapsedRealtime(),
        )
        val json = JSONObject()
        values.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        android.util.Log.i("HomeJourneyDetectorTiming", json.toString())
    }

    private fun observeSpeech(main: MainActivity) {
        val output = requireNotNull(field(main, "speechOutputController"))
        if (observedOutputControllers.add(output)) {
            val original = field(output, "actuatorFactory") as SpeechOutputActuatorFactory
            set(output, "actuatorFactory", SpeechOutputActuatorFactory { unavailable, ready ->
                original.create(unavailable, ready).also(::observeActuator)
            })
        }
        (field(main, "feedbackActuator") as? AndroidFeedbackActuator)?.let(::observeActuator)
    }

    // Observe a replacement before its first dispatch; delegate every native operation unchanged.
    private fun observeActuator(actuator: AndroidFeedbackActuator) {
        if (observedActuators.containsKey(actuator)) return
        val native = field(actuator, "speechRuntime") as FeedbackSpeechRuntime
        val observedNative = object : FeedbackSpeechRuntime by native {
            override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
                val key = actuator to utteranceId
                nativeUtteranceMessages[key] = message
                val result = native.speak(message, queueMode, utteranceId)
                if (result == android.speech.tts.TextToSpeech.SUCCESS) {
                    nativeUtteranceAccepted.add(key)
                }
                return result
            }
            override fun logSpeechEvent(event: String, utteranceId: String?) {
                native.logSpeechEvent(event, utteranceId)
                if (utteranceId != null) {
                    if (event == "started") nativeUtteranceStarted.add(actuator to utteranceId)
                    if (event == "done") nativeUtteranceDone.add(actuator to utteranceId)
                }
            }
        }
        set(actuator, "speechRuntime", observedNative)
        val previousListener = actuator.guidanceDiagnosticListener
        val listener: (SpeechOutputEvent) -> Unit = { event ->
            previousListener?.invoke(event)
            speechEvents.record(event)
            if (event.outcome == SpeechOutputOutcome.CANCELLED_STALE_NAVIGATION) {
                // Bounded, outcome-only observation: no spoken text, token or account content.
                val frames = Throwable().stackTrace.asSequence()
                    .filter { it.className.startsWith("kr.co.hanium.dreamup.walksafe.") }
                    .take(14).joinToString(" <- ")
                android.util.Log.d("HomeJourneyCancelCaller", "at=${event.atElapsedRealtimeMs}; $frames")
            }
        }
        // Observe the existing native factory/runtime without replacing its consented listener.
        observedActuators[actuator] = listener
        actuator.guidanceDiagnosticListener = listener
    }

    private fun controlBackend(value: JSONObject) {
        val connection = URL("$fixtureBaseUrl/__fixture/control").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(value.toString().toByteArray(Charsets.UTF_8)) }
            assertEquals("Local fixture control must acknowledge the test mode", 200, connection.responseCode)
            connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }

    private class ControlledLocations {
        private val handler = Handler(Looper.getMainLooper())
        private val callbacks = linkedSetOf<LocationCallback>()
        private val retired = mutableListOf<LocationCallback>()
        private var navigationCallbacks: List<LocationCallback> = emptyList()
        var capturedNavigationEpoch: WalkRuntimeEpoch? = null
            private set
        val retiredCount get() = retired.size
        var eastM = 0.0
        var northM = 0.0
        private fun location() = Location("gps").apply {
            latitude = 37.0 + northM / 111_194.92664455874
            longitude = 127.0 + eastM / (111_194.92664455874 * kotlin.math.cos(Math.toRadians(37.0)))
            accuracy = 2f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        private val tick = object : Runnable {
            override fun run() {
                callbacks.toList().forEach { it.onLocationResult(LocationResult.create(listOf(location()))) }
                if (callbacks.isNotEmpty()) handler.postDelayed(this, 500L)
            }
        }
        val client = Proxy.newProxyInstance(FusedLocationProviderClient::class.java.classLoader,
            arrayOf(FusedLocationProviderClient::class.java)) { proxy, method, args ->
            when (method.name) {
                "getCurrentLocation", "getLastLocation" -> Tasks.forResult(location())
                "requestLocationUpdates" -> {
                    val callback = requireNotNull(args).filterIsInstance<LocationCallback>().single()
                    callbacks.add(callback)
                    handler.removeCallbacks(tick)
                    handler.postDelayed(tick, 100L)
                    Tasks.forResult<Void>(null)
                }
                "removeLocationUpdates" -> {
                    val callback = requireNotNull(args).filterIsInstance<LocationCallback>().single()
                    callbacks.remove(callback); retired.add(callback)
                    if (callbacks.isEmpty()) handler.removeCallbacks(tick)
                    Tasks.forResult<Void>(null)
                }
                "toString" -> "HomeJourneyControlledFusedLeaf"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                else -> error("Unexpected Fused client call: ${method.name}")
            }
        } as FusedLocationProviderClient
        fun captureNavigationCallbacks(epoch: WalkRuntimeEpoch) {
            check(Looper.myLooper() == Looper.getMainLooper())
            check(callbacks.isNotEmpty()) { "ACTIVE must have a real Fused registration" }
            navigationCallbacks = callbacks.toList()
            capturedNavigationEpoch = epoch
        }
        fun emitNavigationCallbacks(): Int {
            check(Looper.myLooper() == Looper.getMainLooper())
            navigationCallbacks.forEach { it.onLocationResult(LocationResult.create(listOf(location()))) }
            return navigationCallbacks.size
        }
        fun emitRetiredCallbacks() = retired.toList().forEach { it.onLocationResult(LocationResult.create(listOf(location()))) }
        fun close() { handler.removeCallbacksAndMessages(null); callbacks.clear(); retired.clear(); navigationCallbacks = emptyList() }
    }

    private fun field(owner: Any, name: String): Any? = MainActivityControllerTestFixture.field(name).get(owner)
    private fun set(owner: Any, name: String, value: Any?) = MainActivityControllerTestFixture.field(name).set(owner, value)
    private fun call(owner: Any, name: String, vararg args: Any?): Any? {
        val method = owner.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        try { return method.apply { isAccessible = true }.invoke(owner, *args) }
        catch (failure: InvocationTargetException) { throw requireNotNull(failure.cause) }
    }
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
    }
    private fun clickableAncestor(view: View): View? {
        var candidate: View? = view
        while (candidate != null && !candidate.isClickable) candidate = candidate.parent as? View
        return candidate
    }
}
