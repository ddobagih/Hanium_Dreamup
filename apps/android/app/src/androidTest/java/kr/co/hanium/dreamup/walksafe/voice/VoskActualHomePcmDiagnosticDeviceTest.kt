package kr.co.hanium.dreamup.walksafe.voice

import android.Manifest
import android.app.Instrumentation
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.MainActivityControllerTestFixture
import kr.co.hanium.dreamup.walksafe.session.DeviceTestAccountUiLogin
import kr.co.hanium.dreamup.walksafe.voice.input.AndroidVoiceInputPlatform
import kr.co.hanium.dreamup.walksafe.voice.input.WakeProbeEffect
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 원 Main과 AudioRecord callback의 opt-in 관측이다. JNI accept 증거로 보고하지 않는다.
 * 계정/교육/권한/호출어 판정을 주입하거나 음원을 생성하지 않는다. private journal과
 * source별 실제 PCM 파일만 새로 보존한다. 원 255개 인수 시험과 별도 진단이다.
 * 기록/동기화 비용이 있으므로 이 실행을 성능 증거로 사용하지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class VoskActualHomePcmDiagnosticDeviceTest {
    @Test(timeout = 180_000L)
    fun observeActualHomeAndTypedDestinationAcrossForegroundReentry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("runVoskActualHomePcmDiagnostic") == "true")
        val root = File(instrumentation.targetContext.cacheDir, "vosk-actual-home-pcm-${SystemClock.elapsedRealtimeNanos()}")
        check(root.mkdir()) { "Cannot create the exact diagnostic-owned directory" }
        val journal = Journal(File(root, "journal.jsonl"))
        val attachments = IdentityHashMap<AndroidPcmAudioSource, Attachment>()
        var scenario: ActivityScenario<MainActivity>? = null
        var activity: MainActivity? = null
        var primaryFailure: Throwable? = null
        val phase = AtomicReference("HOME_IDLE")
        try {
            journal.record("BEGIN", JSONObject().put("artifact_directory", root.absolutePath)
                .put("observed_only", true).put("jni_accept_tested", false))
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "ACTUAL_HOME_PCM artifact=${root.absolutePath} observed_only=true\n") })
            val launched = ActivityScenario.launch(MainActivity::class.java)
            scenario = launched
            launched.onActivity { activity = it }
            val main = checkNotNull(activity)
            val preflightDeadline = SystemClock.elapsedRealtime() + 20_000L
            DeviceTestAccountUiLogin.ensureLoggedIn(instrumentation, main, preflightDeadline)
            await(instrumentation, preflightDeadline, "Actual authorized/educated HOME is unavailable") {
                main.hasWindowFocus() && mainBoolean(main, "firstRunOnboardingComplete") &&
                    mainField(main, "isActivityForeground") == true && mainBoolean(main, "nativeHomeFeatureContextAvailable") &&
                    mainBoolean(main, "homeVoiceCommandAvailable") && mainBoolean(main, "isHandsFreeVoiceDisclosureAccepted") &&
                    main.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                    page(main) == "HOME" && BundledVoskModelInstaller.installedModelOrNull(main) != null
            }
            val first = awaitAttachment(instrumentation, main, attachments, journal, root, phase,
                SystemClock.elapsedRealtime() + 20_000L)
            await(instrumentation, SystemClock.elapsedRealtime() + 5_000L, "Final-field observer did not receive actual callbacks") {
                val current = currentOwner(main)
                current?.source === first.owner.source && first.callbackField.get(first.owner.source) === first.wrapper && first.callbacks.get() > 0
            }
            val observedAt = SystemClock.elapsedRealtime()
            var destinationClicked = false
            var typed = false
            var foregroundRestarted = false
            var sourceBeforeForeground: AndroidPcmAudioSource? = null
            var foregroundReplacementProved = false
            var sourceAbsentAt: Long? = null
            val initialSource = first.owner.source
            while (SystemClock.elapsedRealtime() - observedAt < 90_000L) {
                journal.failure.get()?.let { throw AssertionError("PCM observation failed; original consumer was preserved", it) }
                val elapsed = SystemClock.elapsedRealtime() - observedAt
                if (!destinationClicked && elapsed >= 10_000L) {
                    onMain(instrumentation) {
                        assertEquals("Actual HOME page is required before its button click", "HOME", page(main))
                        val button = descendants(main.window.decorView).filterIsInstance<Button>()
                            .single { it.text.toString() == "길라잡이" && it.isShown && it.isEnabled }
                        assertTrue("The actual destination button must handle the click", button.performClick())
                    }
                    destinationClicked = true
                    journal.record("DESTINATION_BUTTON_CLICKED")
                }
                if (destinationClicked && !typed) {
                    typed = onMain(instrumentation) {
                        if (page(main) != "DESTINATION_SEARCH") false else {
                            val input = descendants(main.window.decorView).filterIsInstance<EditText>()
                                .single { it.contentDescription?.toString() == "목적지 입력" && it.isShown && it.isEnabled }
                            assertTrue("Actual destination input must take focus", input.requestFocus())
                            assertTrue("Actual editable UI must accept text", input.performAccessibilityAction(
                                AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "금오공대")
                                }))
                            assertEquals("금오공대", input.text.toString())
                            true
                        }
                    }
                    if (typed) {
                        phase.set("DESTINATION_TYPED")
                        journal.record("DESTINATION_TYPED", JSONObject().put("page", "DESTINATION_SEARCH"))
                    } else assertTrue("Destination UI did not become ready after the actual click", elapsed < 30_000L)
                }
                if (typed && !foregroundRestarted && elapsed >= 45_000L) {
                    sourceBeforeForeground = checkNotNull(onMain(instrumentation) { currentOwner(main) }).source
                    phase.set("FOREGROUND_REENTRY")
                    journal.record("FOREGROUND_STOP_REQUESTED")
                    launched.moveToState(Lifecycle.State.CREATED)
                    launched.moveToState(Lifecycle.State.RESUMED)
                    foregroundRestarted = true
                    journal.record("FOREGROUND_RETURNED")
                }
                val owner = onMain(instrumentation) { currentOwner(main) }
                if (owner != null) {
                    sourceAbsentAt = null
                    val existing = attachments[owner.source]
                    if (existing == null) onMain(instrumentation) {
                        attach(owner, attachments.size + 1, journal, root, phase, attachments)
                    } else {
                        assertTrue("The exact source observer was replaced", existing.callbackField.get(owner.source) === existing.wrapper)
                        assertTrue("Final-field reflection did not observe this actual source",
                            existing.callbacks.get() > 0 || SystemClock.elapsedRealtime() - existing.attachedAtMs < 5_000L)
                        if (foregroundRestarted && owner.source !== sourceBeforeForeground && existing.callbacks.get() > 0) {
                            foregroundReplacementProved = true
                        }
                    }
                } else {
                    val absentAt = sourceAbsentAt ?: SystemClock.elapsedRealtime().also { sourceAbsentAt = it }
                    assertTrue("Actual foreground source never became ready", SystemClock.elapsedRealtime() - absentAt < 20_000L)
                }
                Thread.sleep(100L)
            }
            assertTrue("Actual observation must span ninety seconds", SystemClock.elapsedRealtime() - observedAt >= 90_000L)
            assertTrue("The actual destination transition must be observed", destinationClicked && typed)
            assertTrue("Actual foreground stop/return must run", foregroundRestarted)
            assertTrue("A newly attached source must execute after foreground return", foregroundReplacementProved)
            assertTrue("An actual replacement source must be observed", attachments.keys.any { it !== initialSource })
            onMain(instrumentation) { assertEquals("DESTINATION_SEARCH", page(main)) }
            journal.record("OBSERVATION_WINDOW_COMPLETED")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                scenario?.close()
                val released = CountDownLatch(1)
                VoskStreamingWorker.process.execute { released.countDown() }
                val cleanupDeadline = SystemClock.elapsedRealtime() + 10_000L
                check(released.await((cleanupDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)) {
                    "Actual native release did not finish; observation files remain open"
                }
                // 원 release와 callback 후처리 완료를 같은 10초 창에서 기다린다.
                while (attachments.values.any { attachment ->
                    val source = attachment.owner.source
                    val captureActive = synchronized(field(source, "stateLock")!!) { field(source, "activeSession") != null }
                    attachment.inFlight.get() != 0 || captureActive
                }) {
                    check(SystemClock.elapsedRealtime() < cleanupDeadline) { "Source capture is still active; observation files remain open" }
                    Thread.sleep(10L)
                }
                // 제품 lock을 유지한 채 consumer를 호출하지 않는다. 복원과 파일 종료는 capture 해제 뒤다.
                var retirementFailure: Throwable? = null
                try {
                    for (attachment in attachments.values) {
                        try {
                            try {
                                if (attachment.callbackField.get(attachment.owner.source) === attachment.wrapper) {
                                    attachment.callbackField.set(attachment.owner.source, attachment.original)
                                }
                            } finally { attachment.finish() }
                            if (primaryFailure == null) {
                                assertTrue("Every attached source must prove actual wrapper execution", attachment.callbacks.get() > 0)
                                assertEquals("Each callback must invoke its original consumer exactly once",
                                    attachment.callbacks.get(), attachment.consumerCalls.get())
                            }
                        } catch (failure: Throwable) {
                            val firstFailure = retirementFailure
                            if (firstFailure == null) retirementFailure = failure
                            else if (firstFailure !== failure) firstFailure.addSuppressed(failure)
                        }
                    }
                    retirementFailure?.let { throw it }
                    journal.failure.get()?.let { throw AssertionError("PCM journal observation failed", it) }
                    journal.record("FINISHED", JSONObject().put("sources", attachments.size).put("artifacts_preserved", true))
                } finally { journal.close() }
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original == null) throw cleanupFailure
                if (original !== cleanupFailure) original.addSuppressed(cleanupFailure)
            }
        }
    }

    /** Observe the current normal HOME source while Native owns the ordinary Settings UI.
     * This opt-in diagnostic preserves each actual callback before invoking its original
     * consumer once. Native input, suppression, decoder state, account and UI results remain unchanged.
     * Disk synchronization affects timing; this is not a performance test or a crash-clearance test.
     */
    @Test(timeout = 180_000L)
    fun observeHomePcmWhileNativeRunsSettingsRefresh() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runVoskSettingsPcmObservation") == "true")
        val expected = arguments.getString("settingsPcmExpectedTargetApkSha256")
        check(expected == "7444d09ab7837b8e3945960424ed51c7bf117740cf695c8819f0a4ce64bc5d5a")
        val context = instrumentation.targetContext
        check(context.packageName == "kr.co.hanium.dreamup.walksafe" && context.applicationInfo.uid == 10178)
        check(android.os.Process.myUid() == 10178)
        check(kr.co.hanium.dreamup.walksafe.BuildConfig.DEBUG &&
            !kr.co.hanium.dreamup.walksafe.BuildConfig.DEVELOPMENT_QUICK_START &&
            !kr.co.hanium.dreamup.walksafe.BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
        val digest = MessageDigest.getInstance("SHA-256")
        val hashDeadline = SystemClock.elapsedRealtime() + 30_000L
        File(context.applicationInfo.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check(SystemClock.elapsedRealtime() < hashDeadline)
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == expected)
        val root = File(context.cacheDir, "vosk-settings-pcm-${SystemClock.elapsedRealtimeNanos()}")
        check(root.mkdir())
        val journal = Journal(File(root, "journal.jsonl"))
        val attachments = IdentityHashMap<AndroidPcmAudioSource, Attachment>()
        var scenario: ActivityScenario<MainActivity>? = null
        var activity: MainActivity? = null
        var primaryFailure: Throwable? = null
        val phase = AtomicReference("HOME_SETTINGS_OBSERVATION")
        try {
            journal.record("BEGIN", JSONObject().put("artifact_directory", root.absolutePath)
                .put("observed_only", true).put("jni_accept_tested", false)
                .put("first_chunks_may_be_missing", true).put("performance_evidence", false)
                .put("native_owns_settings_ui", true).put("target_apk_sha256", expected))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "SETTINGS_PCM artifact=${root.absolutePath} observed_only=true\n")
            })
            val launched = ActivityScenario.launch(MainActivity::class.java)
            scenario = launched
            launched.onActivity { activity = it }
            val main = checkNotNull(activity)
            val preflightDeadline = SystemClock.elapsedRealtime() + 20_000L
            await(instrumentation, preflightDeadline, "Current normal HOME is unavailable") {
                main.hasWindowFocus() && mainField(main, "isActivityForeground") == true &&
                    mainBoolean(main, "firstRunOnboardingComplete") && page(main) == "HOME" &&
                    main.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                    BundledVoskModelInstaller.installedModelOrNull(main) != null
            }
            val first = awaitAttachment(instrumentation, main, attachments, journal, root, phase,
                SystemClock.elapsedRealtime() + 20_000L)
            await(instrumentation, SystemClock.elapsedRealtime() + 5_000L, "Actual PCM callback was not observed") {
                val current = currentOwner(main)
                current?.source === first.owner.source && current.run == first.owner.run &&
                    main.hasWindowFocus() && mainField(main, "isActivityForeground") == true && page(main) == "HOME" &&
                    first.callbackField.get(first.owner.source) === first.wrapper && first.callbacks.get() > 0
            }
            journal.record("CALLBACK_READY", JSONObject().put("first_source", 1))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "SETTINGS_PCM_CALLBACK_READY artifact=${root.absolutePath}\n")
            })
            val observedAt = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - observedAt < 90_000L) {
                journal.failure.get()?.let { throw AssertionError("PCM observation failed", it) }
                onMain(instrumentation) {
                    currentOwner(main)?.let { owner ->
                        val existing = attachments[owner.source]
                        if (existing == null) attach(owner, attachments.size + 1, journal, root, phase, attachments)
                        else check(existing.callbackField.get(owner.source) === existing.wrapper)
                    }
                }
                Thread.sleep(50L)
            }
            journal.record("OBSERVATION_WINDOW_COMPLETED")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                scenario?.close()
                val released = CountDownLatch(1)
                VoskStreamingWorker.process.execute { released.countDown() }
                val cleanupDeadline = SystemClock.elapsedRealtime() + 10_000L
                check(released.await((cleanupDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)) {
                    "Actual native release did not finish; observation files remain open"
                }
                // 원 release와 callback 후처리 완료를 같은 10초 창에서 기다린다.
                while (attachments.values.any { attachment ->
                    val source = attachment.owner.source
                    val captureActive = synchronized(field(source, "stateLock")!!) { field(source, "activeSession") != null }
                    attachment.inFlight.get() != 0 || captureActive
                }) {
                    check(SystemClock.elapsedRealtime() < cleanupDeadline) { "Source capture is still active; observation files remain open" }
                    Thread.sleep(10L)
                }
                // 제품 lock을 유지한 채 consumer를 호출하지 않는다. 복원과 파일 종료는 capture 해제 뒤다.
                var retirementFailure: Throwable? = null
                try {
                    for (attachment in attachments.values) {
                        try {
                            try {
                                if (attachment.callbackField.get(attachment.owner.source) === attachment.wrapper) {
                                    attachment.callbackField.set(attachment.owner.source, attachment.original)
                                }
                            } finally { attachment.finish() }
                            if (primaryFailure == null) {
                                assertTrue("Every attached source must prove actual wrapper execution", attachment.callbacks.get() > 0)
                                assertEquals("Each callback must invoke its original consumer exactly once",
                                    attachment.callbacks.get(), attachment.consumerCalls.get())
                            }
                        } catch (failure: Throwable) {
                            val firstFailure = retirementFailure
                            if (firstFailure == null) retirementFailure = failure
                            else if (firstFailure !== failure) firstFailure.addSuppressed(failure)
                        }
                    }
                    retirementFailure?.let { throw it }
                    journal.failure.get()?.let { throw AssertionError("PCM journal observation failed", it) }
                    journal.record("FINISHED", JSONObject().put("sources", attachments.size).put("artifacts_preserved", true))
                } finally { journal.close() }
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original == null) throw cleanupFailure
                if (original !== cleanupFailure) original.addSuppressed(cleanupFailure)
            }
        }
    }

    private data class Owner(val probe: VoskWakePhraseProbe, val transcriber: VoskStreamingTranscriber,
        val source: AndroidPcmAudioSource, val run: Long)

    private class Attachment(val owner: Owner, val callbackField: Field, val original: (ShortArray) -> Unit,
        val journal: Journal, val raw: FileOutputStream, val number: Int, val phase: AtomicReference<String>) {
        lateinit var wrapper: (ShortArray) -> Unit
        val attachedAtMs = SystemClock.elapsedRealtime()
        val callbacks = AtomicInteger(0)
        val consumerCalls = AtomicInteger(0)
        val inFlight = AtomicInteger(0)
        private val digest = MessageDigest.getInstance("SHA-256")
        private var samplesTotal = 0L
        private var zerosTotal = 0L
        private var squaresTotal = 0.0
        private var peakTotal = 0

        fun consume(samples: ShortArray) {
            inFlight.incrementAndGet()
            val ordinal = callbacks.incrementAndGet()
            var consumerFailure: Throwable? = null
            try {
                journal.observe {
                    val bytes = pcm16(samples)
                    var zero = 0
                    var squares = 0.0
                    var peak = 0
                    for (sample in samples) {
                        if (sample == 0.toShort()) zero++
                        val value = sample.toInt()
                        squares += value.toDouble() * value
                        peak = maxOf(peak, abs(value))
                    }
                    synchronized(journal.lock) {
                        raw.write(bytes)
                        raw.flush()
                        raw.fd.sync()
                        digest.update(bytes)
                        samplesTotal += samples.size
                        zerosTotal += zero
                        squaresTotal += squares
                        peakTotal = maxOf(peakTotal, peak)
                        journal.record("CALLBACK_BEFORE", snapshot().put("source_number", number)
                            .put("phase", phase.get()).put("callback", ordinal).put("samples", samples.size)
                            .put("zero_ratio", zero.toDouble() / samples.size).put("rms", sqrt(squares / samples.size))
                            .put("peak", peak).put("s16le_sha256", sha(bytes)))
                    }
                }
                consumerCalls.incrementAndGet()
                original(samples) // 같은 배열과 같은 capture thread에서 원 consumer를 정확히 한 번 호출한다.
            } catch (failure: Throwable) {
                consumerFailure = failure
                throw failure
            } finally {
                try {
                    journal.observe {
                        journal.record("CALLBACK_AFTER", snapshot().put("source_number", number)
                            .put("callback", ordinal).put("phase", phase.get())
                            .put("consumer_returned", consumerFailure == null)
                            .put("consumer_failure", consumerFailure?.javaClass?.simpleName ?: "NONE"))
                    }
                } finally { inFlight.decrementAndGet() }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun snapshot(): JSONObject {
            val stateLock = field(owner.transcriber, "stateLock")!!
            val facts = synchronized(stateLock) {
                JSONObject().put("transcriber_run", field(owner.transcriber, "generation"))
                    .put("transcriber_state", (field(owner.transcriber, "state") as Enum<*>).name)
                    .put("same_current_source", field(owner.transcriber, "audioSource") === owner.source)
            }
            val input = field(owner.transcriber, "inputGeneration") as () -> Long?
            val suppression = field(owner.transcriber, "isInputSuppressed") as () -> Boolean
            return facts.put("bound_probe_run", owner.run).put("probe_run", field(owner.probe, "activeRunId") ?: JSONObject.NULL)
                .put("input_generation", input() ?: JSONObject.NULL).put("suppressed", suppression())
                .put("observed_only", true).put("jni_accept_claimed", false)
        }

        fun finish() {
            synchronized(journal.lock) {
                raw.use { it.fd.sync() }
                journal.record("SOURCE_FINISHED", JSONObject().put("source_number", number)
                    .put("bound_probe_run", owner.run).put("callbacks", callbacks.get())
                    .put("consumer_calls", consumerCalls.get()).put("samples", samplesTotal)
                    .put("zero_ratio", if (samplesTotal == 0L) JSONObject.NULL else zerosTotal.toDouble() / samplesTotal)
                    .put("rms", if (samplesTotal == 0L) JSONObject.NULL else sqrt(squaresTotal / samplesTotal))
                    .put("peak", peakTotal).put("raw_s16le_sha256", hex(digest.digest())))
            }
        }
    }

    private class Journal(file: File) {
        val lock = Any()
        val failure = AtomicReference<Throwable?>(null)
        private val output = FileOutputStream(file)
        private val writer = output.bufferedWriter(Charsets.UTF_8)
        fun record(event: String, data: JSONObject = JSONObject()) = synchronized(lock) {
            writer.write(data.put("event", event).put("elapsed_ms", SystemClock.elapsedRealtime()).toString())
            writer.newLine()
            writer.flush()
            output.fd.sync()
        }
        fun observe(action: () -> Unit) {
            try { action() } catch (observationFailure: Throwable) { failure.compareAndSet(null, observationFailure) }
        }
        fun close() = synchronized(lock) { output.use { writer.use { it.flush(); output.fd.sync() } } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun attach(owner: Owner, number: Int, journal: Journal, root: File,
        phase: AtomicReference<String>, attachments: IdentityHashMap<AndroidPcmAudioSource, Attachment>): Attachment {
        val callbackField = AndroidPcmAudioSource::class.java.getDeclaredField("onPcm16").apply { isAccessible = true }
        check(callbackField.type.name == "kotlin.jvm.functions.Function1")
        val original = callbackField.get(owner.source) as (ShortArray) -> Unit
        val rawFile = File(root, "source-$number.s16le")
        check(rawFile.createNewFile())
        val attachment = Attachment(owner, callbackField, original, journal, FileOutputStream(rawFile), number, phase)
        attachment.wrapper = attachment::consume
        attachments[owner.source] = attachment // 설치나 journal 실패도 원 source 해제 후 정리한다.
        callbackField.set(owner.source, attachment.wrapper)
        check(callbackField.get(owner.source) === attachment.wrapper)
        journal.record("SOURCE_ATTACHED", JSONObject().put("source_number", number).put("probe_run", owner.run)
            .put("raw_file", rawFile.absolutePath).put("final_field", Modifier.isFinal(callbackField.modifiers))
            .put("execution_proof_pending", true).put("observed_only", true)
            .put("raw_format", "S16LE_MONO").put("sample_rate_hz", 16_000))
        return attachment
    }

    private fun currentOwner(activity: MainActivity): Owner? {
        val effect = mainField(activity, "foregroundHomeWakeProbe") ?: return null
        check(effect is WakeProbeEffect)
        check(effect.javaClass.name == AndroidVoiceInputPlatform::class.java.name + "\$createProbe\$1")
        val captures = effect.javaClass.declaredFields.filter {
            it.type == VoskWakePhraseProbe::class.java && !Modifier.isStatic(it.modifiers)
        }
        check(captures.size == 1)
        val probe = captures.single().apply { isAccessible = true }.get(effect) as VoskWakePhraseProbe
        val run = (field(probe, "activeRunId") as? Number)?.toLong() ?: return null
        val stream = field(probe, "stream") ?: return null
        check(stream.javaClass.name == "kr.co.hanium.dreamup.walksafe.voice.AndroidVoskWakePhraseStream")
        val transcriber = field(stream, "transcriber") as VoskStreamingTranscriber
        val stateLock = field(transcriber, "stateLock")!!
        return synchronized(stateLock) {
            if ((field(transcriber, "state") as Enum<*>).name != "RUNNING") null else {
                val source = field(transcriber, "audioSource") as? AndroidPcmAudioSource ?: return@synchronized null
                check((field(transcriber, "generation") as Number).toLong() == run)
                Owner(probe, transcriber, source, run)
            }
        }
    }

    private fun awaitAttachment(instrumentation: Instrumentation, main: MainActivity,
        attachments: IdentityHashMap<AndroidPcmAudioSource, Attachment>, journal: Journal, root: File,
        phase: AtomicReference<String>, deadline: Long): Attachment {
        while (SystemClock.elapsedRealtime() < deadline) {
            val result = onMain(instrumentation) {
                currentOwner(main)?.let { owner ->
                    attachments[owner.source] ?: attach(owner, attachments.size + 1, journal, root, phase, attachments)
                }
            }
            if (result != null) return result
            Thread.sleep(100L)
        }
        throw AssertionError("Actual HOME audio source was not ready")
    }

    private fun await(instrumentation: Instrumentation, deadline: Long, message: String, condition: () -> Boolean) {
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(instrumentation, condition)) return
            Thread.sleep(100L)
        }
        throw AssertionError(message)
    }

    private companion object {
        fun field(receiver: Any, name: String): Any? = receiver.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(receiver)
        fun mainField(main: MainActivity, name: String): Any? = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.get(main)
        fun mainBoolean(main: MainActivity, name: String): Boolean = MainActivity::class.java.getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(main) as Boolean
        fun page(main: MainActivity): String = (mainField(main, "nativeUiPage") as Enum<*>).name
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            var value: T? = null
            var failure: Throwable? = null
            instrumentation.runOnMainSync { try { value = action() } catch (caught: Throwable) { failure = caught } }
            failure?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return value as T
        }
        fun pcm16(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).also { bytes ->
            for (index in samples.indices) {
                val value = samples[index].toInt()
                bytes[index * 2] = value.toByte()
                bytes[index * 2 + 1] = (value ushr 8).toByte()
            }
        }
        fun sha(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
