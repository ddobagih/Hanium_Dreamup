package kr.co.hanium.dreamup.walksafe.voice

import android.app.Activity
import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.Os
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.MainActivityControllerTestFixture
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Recognizer

/**
 * 원 MIC06 공개 메서드를 한 번 실행하면서 실제 버튼 요청의 callback PCM만 보존한다.
 * 첫 RUNNING 이후 attach하므로 최초 청크가 누락될 수 있다. consumer 반환은 JNI accept
 * 증거가 아니며 Java Recognizer identity도 native feature/i-vector identity가 아니다.
 * 원 입력/error 단언/cleanup/외부 360초 상한을 바꾸지 않고 별도 인수 시험으로 세지 않는다.
 * 기록 동기화 비용이 있으므로 이 진단을 성능 증거로 사용하지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class VoskMic06PcmObservationDiagnosticDeviceTest {
    @Test
    fun observeOriginalMic06CallbackPcm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("runVoskMic06PcmObservationDiagnostic") == "true")
        check(BuildConfig.WALKSAFE_GATEWAY_ORIGIN == "http://127.0.0.1:39873") {
            "The exact compiled account gateway origin is required"
        }
        val context = instrumentation.targetContext
        check(context.packageName == "kr.co.hanium.dreamup.walksafe.accountrestartfixture")
        check(context.applicationInfo.uid == Process.myUid())
        val application = context.applicationContext as Application
        val handler = Handler(Looper.getMainLooper())
        val activity = AtomicReference<MainActivity?>(null)
        val watching = AtomicBoolean(true)
        val originalExecuting = AtomicBoolean(false)
        val observationFailure = AtomicReference<Throwable?>(null)
        val attachments = IdentityHashMap<AndroidPcmAudioSource, Attachment>()
        var journal: Journal? = null
        var root: File? = null
        var registered = false
        var originalMethodCalls = 0
        var primaryFailure: Throwable? = null
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(created: Activity, state: Bundle?) {
                if (created is MainActivity && originalExecuting.get()) activity.compareAndSet(null, created)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        val poll = object : Runnable {
            override fun run() {
                if (!watching.get()) return
                try {
                    val main = activity.get()
                    if (main != null && !main.isFinishing && !main.isDestroyed && observationFailure.get() == null) {
                        currentOwner(main)?.let { owner ->
                            val existing = attachments[owner.source]
                            if (existing == null) attach(owner, attachments.size + 1, checkNotNull(root),
                                checkNotNull(journal), observationFailure, attachments)
                            else check(existing.callbackField.get(owner.source) === existing.wrapper) {
                                "The exact callback observer no longer owns its source"
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    observationFailure.compareAndSet(null, failure)
                } finally {
                    if (watching.get()) handler.postDelayed(this, 100L)
                }
            }
        }
        try {
            // 관측 setup 실패도 원 공개 메서드의 실행/판정을 대체하지 않는다.
            try {
                val cache = context.cacheDir
                check(cache.isDirectory && !Files.isSymbolicLink(cache.toPath()))
                val ownedRoot = File(cache, "vosk-mic06-pcm-${SystemClock.elapsedRealtimeNanos()}")
                check(ownedRoot.mkdir())
                privateMode(ownedRoot, 0x1c0)
                check(ownedRoot.canonicalFile.parentFile == cache.canonicalFile)
                root = ownedRoot
                journal = Journal(privateFile(ownedRoot, "journal.jsonl"))
                journal!!.record("BEGIN", JSONObject().put("artifact_directory", ownedRoot.absolutePath)
                    .put("original_method", "DestinationVoiceFollowUpDeviceTest#recognizerErrorsAndRejectedSelectionsKeepTheCurrentCandidatePage")
                    .put("original_method_calls_planned", 1).put("original_outer_timeout_seconds", 360)
                    .put("first_chunks_may_be_missing", true).put("jni_accept_claimed", false)
                    .put("reset_calls_observed", false).put("native_pipeline_identity_observed", false)
                    .put("performance_evidence", false).put("new_pcm_or_error_injection", false))
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "MIC06_PCM artifact=${ownedRoot.absolutePath} jni_accept_claimed=false\n")
                })
                instrumentation.runOnMainSync {
                    application.registerActivityLifecycleCallbacks(lifecycle)
                    registered = true
                    check(handler.post(poll))
                }
            } catch (failure: Throwable) {
                observationFailure.compareAndSet(null, failure)
            }
            // 원 입력, 실제 error 6/7 조건, finally를 그대로 한 번만 실행한다.
            originalExecuting.set(true)
            originalMethodCalls++
            DestinationVoiceFollowUpDeviceTest().recognizerErrorsAndRejectedSelectionsKeepTheCurrentCandidatePage()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            originalExecuting.set(false)
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = failure
                    else if (first !== failure) first.addSuppressed(failure)
                }
            }
            watching.set(false)
            var watcherRetired = false
            cleanup { instrumentation.runOnMainSync { handler.removeCallbacks(poll) }; watcherRetired = true }
            var drained = false
            cleanup {
                check(watcherRetired) { "Observer main-thread barrier did not complete; artifacts remain open" }
                val deadline = SystemClock.elapsedRealtime() + 10_000L
                while (attachments.values.any { !it.retired() }) {
                    check(SystemClock.elapsedRealtime() < deadline) { "Actual capture remains active; artifacts remain open" }
                    Thread.sleep(10L)
                }
                val released = CountDownLatch(1)
                VoskStreamingWorker.process.execute { released.countDown() }
                check(released.await((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)) {
                    "Original Vosk release barrier did not complete; artifacts remain open"
                }
                drained = true
            }
            if (drained) {
                for (attachment in attachments.values) cleanup {
                    try {
                        if (attachment.callbackField.get(attachment.owner.source) === attachment.wrapper) {
                            attachment.callbackField.set(attachment.owner.source, attachment.original)
                        } else {
                            observationFailure.compareAndSet(null, IllegalStateException("Foreign callback owner was preserved"))
                        }
                    } finally { attachment.finish() }
                    if (attachment.callbacks.get() == 0 || attachment.callbacks.get() != attachment.consumerCalls.get()) {
                        observationFailure.compareAndSet(null, IllegalStateException("Exact wrapper execution was not proved"))
                    }
                }
                cleanup {
                    if (attachments.isEmpty()) observationFailure.compareAndSet(null, IllegalStateException("No actual MIC06 source was observed"))
                    journal?.record("FINISHED", JSONObject().put("original_method_returned", primaryFailure == null)
                        .put("original_method_calls", originalMethodCalls)
                        .put("original_failure_class", primaryFailure?.javaClass?.simpleName ?: "NONE")
                        .put("observation_failure_class", observationFailure.get()?.javaClass?.simpleName ?: "NONE")
                        .put("sources", attachments.size).put("capture_drained", true)
                        .put("artifacts_preserved", true).put("jni_accept_claimed", false))
                }
                cleanup { journal?.close() }
            } else cleanup {
                journal?.record("CLEANUP_INCOMPLETE", JSONObject().put("capture_drained", false)
                    .put("artifacts_left_open", true).put("jni_accept_claimed", false))
            }
            cleanup { instrumentation.runOnMainSync {
                if (registered) application.unregisterActivityLifecycleCallbacks(lifecycle)
            } }
            observationFailure.get()?.let { failure -> cleanup { throw AssertionError("MIC06 observation is not verified", failure) } }
            cleanupFailure?.let { failure ->
                val original = primaryFailure
                if (original == null) throw failure
                if (original !== failure) original.addSuppressed(failure)
            }
        }
    }

    /** External normal UI owns the one request and fixed synthetic feed; this test only observes. */
    @Test(timeout = 180_000L)
    fun observeCurrentNormalDestinationCommandPcm() {
        val began = SystemClock.elapsedRealtime()
        val bodyDeadline = began + 150_000L
        val wholeDeadline = began + 180_000L
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runVoskCurrentNormalDestinationPcmObservation") == "true")
        check(arguments.getString("normalDestinationPcmIsolatedVirtualSyntheticInput") == "true")
        val proof = checkNotNull(arguments.getString("normalDestinationPcmExternalProofSha256"))
        check(proof.matches(Regex("[0-9a-f]{64}")))
        check(arguments.getString("normalDestinationPcmSyntheticWavSha256") == CURRENT_DESTINATION_WAV_SHA256)
        val context = instrumentation.targetContext
        check(context.packageName == "kr.co.hanium.dreamup.walksafe")
        check(context.applicationInfo.uid == Process.myUid())
        check(BuildConfig.DEBUG && !BuildConfig.DEVELOPMENT_QUICK_START && !BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
        check(context.applicationInfo.splitSourceDirs.isNullOrEmpty())
        val apkDigest = MessageDigest.getInstance("SHA-256")
        File(context.applicationInfo.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                apkDigest.update(buffer, 0, count)
            }
        }
        check(hex(apkDigest.digest()) == CURRENT_NORMAL_APP_SHA256)
        val cache = context.cacheDir
        check(cache.isDirectory && !Files.isSymbolicLink(cache.toPath()))
        val root = File(cache, "vosk-normal-destination-pcm-${SystemClock.elapsedRealtimeNanos()}")
        check(root.mkdir())
        privateMode(root, 0x1c0)
        check(root.canonicalFile.parentFile == cache.canonicalFile)
        val uiObservationFinished = File(root, "normal-ui-observation.done")
        check(!uiObservationFinished.exists())
        val journal = Journal(privateFile(root, "journal.jsonl"))
        val application = context.applicationContext as Application
        val handler = Handler(Looper.getMainLooper())
        val activity = AtomicReference<MainActivity?>(null)
        val observed = AtomicReference<Attachment?>(null)
        val attachments = IdentityHashMap<AndroidPcmAudioSource, Attachment>()
        val failure = AtomicReference<Throwable?>(null)
        val watching = AtomicBoolean(true)
        val ready = AtomicBoolean(false)
        var boundPreferred: PreferredOfflineSpeechRecognizer? = null
        var boundPreferredGeneration: Long? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var sourceRetiredAt: Long? = null
        var registered = false
        var primaryFailure: Throwable? = null
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(resumed: Activity) {
                if (resumed is MainActivity) {
                    val prior = activity.get()
                    if (prior == null || prior === resumed) activity.set(resumed)
                    else failure.compareAndSet(null, IllegalStateException("A different normal Main owner appeared"))
                }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        val poll = object : Runnable {
            override fun run() {
                if (!watching.get()) return
                try {
                    activity.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.let { main ->
                        currentOwner(main, remoteDecoder = true)?.let { owner ->
                            val prior = observed.get()
                            if (prior == null) {
                                check(attachments.isEmpty()) { "Only one actual command source may be observed" }
                                val controller = MainActivityControllerTestFixture.voiceInput(main)
                                val adapter = checkNotNull(field(controller, "speechRecognizer"))
                                val preferred = captured(adapter, PreferredOfflineSpeechRecognizer::class.java)
                                val fence = checkNotNull(field(checkNotNull(field(preferred, "requests")), "fence"))
                                boundPreferred = preferred
                                boundPreferredGeneration = (field(fence, "generation") as Number).toLong()
                                attach(owner, 1, root, journal, failure, attachments, maximumSamples = 524_288L) {
                                    check(failure.get() == null)
                                    check(ready.compareAndSet(false, true))
                                    journal.record("NORMAL_DESTINATION_PCM_CALLBACK_READY", JSONObject()
                                        .put("bound_run", owner.run).put("bound_request_id", owner.requestId)
                                        .put("wrapper_execution_verified", true).put("jni_accept_claimed", false))
                                    instrumentation.sendStatus(0, Bundle().apply {
                                        putString("stream", "NORMAL_DESTINATION_PCM_CALLBACK_READY artifact=${root.absolutePath}\n")
                                    })
                                }
                                check(observed.compareAndSet(null, checkNotNull(attachments[owner.source])))
                            } else {
                                check(prior.owner == owner) { "A second command request, run, source or decoder appeared" }
                                check(prior.callbackField.get(owner.source) === prior.wrapper)
                            }
                        }
                    }
                } catch (cause: Throwable) {
                    failure.compareAndSet(null, cause)
                } finally {
                    if (watching.get()) handler.postDelayed(this, 50L)
                }
            }
        }
        try {
            journal.record("BEGIN", JSONObject().put("artifact_directory", root.absolutePath)
                .put("actual_target_apk_sha256", CURRENT_NORMAL_APP_SHA256)
                .put("synthetic_wav_sha256", CURRENT_DESTINATION_WAV_SHA256).put("external_proof_sha256", proof)
                .put("normal_ui_observation_finish_marker", uiObservationFinished.absolutePath)
                .put("body_timeout_ms", 150_000).put("whole_timeout_ms", 180_000)
                .put("first_chunks_may_be_missing", true).put("jni_accept_claimed", false)
                .put("reset_calls_observed", false).put("original_crash_pcm_claimed", false)
                .put("performance_evidence", false).put("business_fields_or_results_injected", false))
            instrumentation.runOnMainSync {
                application.registerActivityLifecycleCallbacks(lifecycle)
                registered = true
                check(handler.post(poll))
            }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "NORMAL_DESTINATION_PCM_WAITING_FOR_NORMAL_UI artifact=${root.absolutePath}\n")
            })
            while (true) {
                failure.get()?.let { throw AssertionError("Normal destination PCM observation failed", it) }
                check(SystemClock.elapsedRealtime() < bodyDeadline) { "Original observation150 expired" }
                if (ready.get() && observed.get()?.retired() == true) {
                    val current = SystemClock.elapsedRealtime()
                    val retired = sourceRetiredAt ?: current.also {
                        sourceRetiredAt = it
                        journal.record("SOURCE_RETIRED_NORMAL_UI_WINDOW", JSONObject()
                            .put("window_ms", 20_000).put("body_deadline_unchanged", true))
                    }
                    if (current - retired >= 20_000L && uiObservationFinished.isFile) {
                        check(!Files.isSymbolicLink(uiObservationFinished.toPath()))
                        check(uiObservationFinished.canonicalFile.parentFile == root.canonicalFile)
                        check((Os.stat(uiObservationFinished.absolutePath).st_mode and 0x1ff) == 0x180)
                        check(uiObservationFinished.length() == 64L && uiObservationFinished.readText(Charsets.US_ASCII) == proof)
                        journal.record("NORMAL_UI_OBSERVATION_FINISH_ACK", JSONObject()
                            .put("host_ui_observation_finished", true).put("host_ui_outcome_asserted", false))
                        check(SystemClock.elapsedRealtime() < bodyDeadline) { "Original observation150 expired" }
                        break
                    }
                }
                Thread.sleep(25L)
            }
        } catch (cause: Throwable) {
            primaryFailure = cause
            throw cause
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (cause: Throwable) {
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = cause else if (first !== cause) first.addSuppressed(cause)
                }
            }
            watching.set(false)
            var watcherRetired = false
            cleanup { instrumentation.runOnMainSync { handler.removeCallbacks(poll) }; watcherRetired = true }
            cleanup {
                val historyFailure = AtomicReference<Throwable?>(null)
                if (observed.get() != null) instrumentation.runOnMainSync {
                    try {
                        val main = checkNotNull(activity.get())
                        val controller = MainActivityControllerTestFixture.voiceInput(main)
                        val adapter = checkNotNull(field(controller, "speechRecognizer"))
                        val preferred = captured(adapter, PreferredOfflineSpeechRecognizer::class.java)
                        check(preferred === boundPreferred) { "The command request owner changed" }
                        val fence = checkNotNull(field(checkNotNull(field(preferred, "requests")), "fence"))
                        check((field(fence, "generation") as Number).toLong() == boundPreferredGeneration) {
                            "A second command request began after the bound callback"
                        }
                        journal.record("COMMAND_REQUEST_HISTORY_VERIFIED", JSONObject()
                            .put("preferred_request_generation", boundPreferredGeneration)
                            .put("observation_interval_ended", true))
                    } catch (cause: Throwable) {
                        historyFailure.compareAndSet(null, cause)
                    }
                }
                historyFailure.get()?.let { throw AssertionError("Command request history changed", it) }
            }
            cleanup { scenario?.close() }
            var drained = false
            cleanup {
                check(watcherRetired)
                val deadline = minOf(wholeDeadline, SystemClock.elapsedRealtime() + 10_000L)
                while (attachments.values.any { !it.retired() }) {
                    check(SystemClock.elapsedRealtime() < deadline) { "Actual capture remains active; artifacts remain open" }
                    Thread.sleep(10L)
                }
                val released = CountDownLatch(1)
                VoskStreamingWorker.process.execute { released.countDown() }
                check(released.await((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L), TimeUnit.MILLISECONDS))
                drained = true
            }
            if (drained) {
                var callbackRestored = false
                cleanup {
                    for (attachment in attachments.values) {
                        try {
                            check(attachment.callbackField.get(attachment.owner.source) === attachment.wrapper) {
                                "Foreign callback owner was preserved"
                            }
                            attachment.callbackField.set(attachment.owner.source, attachment.original)
                            check(attachment.callbackField.get(attachment.owner.source) === attachment.original)
                            callbackRestored = true
                        } finally { attachment.finish() }
                        check(attachment.callbacks.get() > 0 && attachment.callbacks.get() == attachment.consumerCalls.get())
                    }
                    check(attachments.size == 1 && ready.get()) { "One actual command callback was not verified" }
                }
                cleanup { journal.record("FINISHED", JSONObject().put("capture_drained", true)
                    .put("callback_restored", callbackRestored).put("sources", attachments.size)
                    .put("observation_failure_class", failure.get()?.javaClass?.simpleName ?: "NONE")
                    .put("primary_failure_class", primaryFailure?.javaClass?.simpleName ?: "NONE")
                    .put("jni_accept_claimed", false).put("artifacts_preserved", true)) }
                cleanup { journal.close() }
            } else cleanup {
                journal.record("CLEANUP_INCOMPLETE", JSONObject().put("capture_drained", false).put("artifacts_left_open", true))
            }
            cleanup { instrumentation.runOnMainSync {
                if (registered) application.unregisterActivityLifecycleCallbacks(lifecycle)
            } }
            failure.get()?.let { cause -> cleanup { throw AssertionError("Normal destination PCM observation failed", cause) } }
            cleanup { check(SystemClock.elapsedRealtime() <= wholeDeadline) { "Original observation180 expired" } }
            cleanupFailure?.let { cause ->
                val original = primaryFailure
                if (original == null) throw cause
                if (original !== cause) original.addSuppressed(cause)
            }
        }
    }

    private data class Owner(val request: VoskSpeechRecognizer, val transcriber: VoskStreamingTranscriber,
        val source: AndroidPcmAudioSource, val recognizer: Any, val run: Long,
        val requestId: Long, val requestGeneration: Int, val captureThread: Thread)

    private class Attachment(val owner: Owner, val callbackField: Field, val original: (ShortArray) -> Unit,
        val raw: FileOutputStream, val journal: Journal, val number: Int,
        val observationFailure: AtomicReference<Throwable?>, private val maximumSamples: Long? = null,
        private val ready: (() -> Unit)? = null) {
        lateinit var wrapper: (ShortArray) -> Unit
        val callbacks = AtomicInteger(0)
        val consumerCalls = AtomicInteger(0)
        private val inFlight = AtomicInteger(0)
        private val digest = MessageDigest.getInstance("SHA-256")
        private var samplesTotal = 0L
        fun consume(samples: ShortArray) {
            inFlight.incrementAndGet()
            val ordinal = callbacks.incrementAndGet()
            var consumerFailure: Throwable? = null
            try {
                observe {
                    check(Thread.currentThread() === owner.captureThread)
                    maximumSamples?.let { check(samplesTotal + samples.size <= it) { "Bounded callback PCM limit exceeded" } }
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
                        journal.record("CALLBACK_BEFORE", snapshot().put("callback", ordinal)
                            .put("samples", samples.size).put("s16le_sha256", sha(bytes))
                            .put("zero_ratio", zero.toDouble() / samples.size)
                            .put("rms", sqrt(squares / samples.size)).put("peak", peak))
                    }
                }
                consumerCalls.incrementAndGet()
                original(samples) // 같은 배열을 같은 capture thread에서 원 consumer에 한 번만 전달한다.
                if (ordinal == 1 && ready != null) observe {
                    check(Thread.currentThread() === owner.captureThread)
                    check(callbackField.get(owner.source) === wrapper)
                    check(field(owner.request, "activeRunId") == owner.run && field(owner.request, "requestId") == owner.requestId)
                    synchronized(field(owner.transcriber, "stateLock")!!) {
                        check((field(owner.transcriber, "state") as Enum<*>).name == "RUNNING")
                        check((field(owner.transcriber, "generation") as Number).toLong() == owner.run)
                        check(field(owner.transcriber, "audioSource") === owner.source && field(owner.transcriber, "recognizer") === owner.recognizer)
                    }
                    ready.invoke()
                }
            } catch (failure: Throwable) {
                consumerFailure = failure
                throw failure
            } finally {
                try { observe {
                    journal.record("CALLBACK_AFTER", snapshot().put("callback", ordinal)
                        .put("consumer_returned", consumerFailure == null)
                        .put("consumer_failure_class", consumerFailure?.javaClass?.simpleName ?: "NONE"))
                } } finally { inFlight.decrementAndGet() }
            }
        }
        private fun observe(action: () -> Unit) {
            try { action() } catch (failure: Throwable) { observationFailure.compareAndSet(null, failure) }
        }
        @Suppress("UNCHECKED_CAST")
        private fun snapshot(): JSONObject {
            val facts = synchronized(field(owner.transcriber, "stateLock")!!) {
                JSONObject().put("transcriber_run", field(owner.transcriber, "generation"))
                    .put("transcriber_state", (field(owner.transcriber, "state") as Enum<*>).name)
                    .put("same_current_source", field(owner.transcriber, "audioSource") === owner.source)
                    .put("same_current_java_recognizer", field(owner.transcriber, "recognizer") === owner.recognizer)
            }
            val input = field(owner.transcriber, "inputGeneration") as () -> Long?
            val suppression = field(owner.transcriber, "isInputSuppressed") as () -> Boolean
            return facts.put("source_number", number).put("bound_run", owner.run)
                .put("bound_request_id", owner.requestId).put("bound_request_generation", owner.requestGeneration)
                .put("bound_java_recognizer_id", journal.identity(owner.recognizer))
                .put("input_generation", input() ?: JSONObject.NULL).put("suppressed", suppression())
                .put("capture_thread_id", Thread.currentThread().id).put("jni_accept_claimed", false)
        }
        fun retired(): Boolean {
            val captureActive = synchronized(field(owner.source, "stateLock")!!) { field(owner.source, "activeSession") != null }
            val current = synchronized(field(owner.transcriber, "stateLock")!!) { field(owner.transcriber, "audioSource") === owner.source }
            return !captureActive && !current && !owner.captureThread.isAlive && inFlight.get() == 0
        }
        fun finish() = synchronized(journal.lock) {
            raw.use { it.fd.sync() }
            journal.record("SOURCE_FINISHED", JSONObject().put("source_number", number)
                .put("callbacks", callbacks.get()).put("consumer_calls", consumerCalls.get())
                .put("samples", samplesTotal).put("bytes", samplesTotal * 2)
                .put("raw_s16le_sha256", hex(digest.digest())).put("jni_accept_claimed", false))
        }
    }

    private class Journal(file: File) {
        val lock = Any()
        private val output = FileOutputStream(file)
        private val writer = try { output.bufferedWriter(Charsets.UTF_8) }
            catch (failure: Throwable) { output.close(); throw failure }
        private val identities = IdentityHashMap<Any, Int>()
        fun identity(value: Any): Int = synchronized(lock) { identities[value] ?: (identities.size + 1).also { identities[value] = it } }
        fun record(event: String, data: JSONObject = JSONObject()) = synchronized(lock) {
            writer.write(data.put("event", event).put("elapsed_ms", SystemClock.elapsedRealtime()).toString())
            writer.newLine()
            writer.flush()
            output.fd.sync()
        }
        fun close() = synchronized(lock) { output.use { writer.use { it.flush(); output.fd.sync() } } }
    }

    private fun currentOwner(main: MainActivity, remoteDecoder: Boolean = false): Owner? {
        check(Looper.myLooper() === Looper.getMainLooper())
        val lazyOwner = field(main, "voiceInputController\$delegate") as Lazy<*>
        if (!lazyOwner.isInitialized()) return null // 관측 때문에 새 제품 owner를 만들지 않는다.
        val controller = MainActivityControllerTestFixture.voiceInput(main)
        val adapter = field(controller, "speechRecognizer") ?: return null
        val preferred = captured(adapter, PreferredOfflineSpeechRecognizer::class.java)
        val fallback = field(preferred, "fallback") ?: return null
        check(fallback.javaClass.name == "kr.co.hanium.dreamup.walksafe.voice.AndroidVoskSpeechBackend")
        val request = field(fallback, "delegate") as VoskSpeechRecognizer
        val requestId = (field(request, "requestId") as? Number)?.toLong() ?: return null
        val run = (field(request, "activeRunId") as? Number)?.toLong() ?: return null
        val stream = field(request, "transcriber") as VoskRequestStream
        val transcriber = captured(stream, VoskStreamingTranscriber::class.java)
        return synchronized(field(transcriber, "stateLock")!!) {
            if ((field(transcriber, "state") as Enum<*>).name != "RUNNING") return@synchronized null
            check((field(transcriber, "generation") as Number).toLong() == run)
            check(field(request, "destroyed") == false)
            check(field(transcriber, "homeWakeEndpointing") == false)
            check(field(transcriber, "preparedRunFactory") == null)
            val source = field(transcriber, "audioSource") as? AndroidPcmAudioSource ?: return@synchronized null
            val recognizer = if (remoteDecoder) checkNotNull(field(transcriber, "recognizer")).also {
                check(it.javaClass.name == "kr.co.hanium.dreamup.walksafe.voice.RemoteVoskDecoder")
            } else field(transcriber, "recognizer") as Recognizer
            check(field(source, "audioRecordFactory") == null)
            val worker = synchronized(field(source, "stateLock")!!) sourceState@ {
                val session = field(source, "activeSession") ?: return@sourceState null
                val record = field(session, "record") as AudioRecord
                check(record.sampleRate == 16_000 && record.channelCount == 1 && record.audioFormat == AudioFormat.ENCODING_PCM_16BIT)
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                field(session, "worker") as Thread
            } ?: return@synchronized null
            check(worker.isAlive && worker.name == "walksafe-pcm-capture")
            Owner(request, transcriber, source, recognizer, run, requestId,
                (field(controller, "voiceRecognitionGeneration") as Number).toInt(), worker)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun attach(owner: Owner, number: Int, root: File, journal: Journal,
        failure: AtomicReference<Throwable?>, attachments: IdentityHashMap<AndroidPcmAudioSource, Attachment>,
        maximumSamples: Long? = null, ready: (() -> Unit)? = null) {
        val callbackField = AndroidPcmAudioSource::class.java.getDeclaredField("onPcm16").apply { isAccessible = true }
        val original = callbackField.get(owner.source) as (ShortArray) -> Unit
        if (owner.recognizer is Recognizer) {
            check(captured(original, Recognizer::class.java) === owner.recognizer)
        } else {
            check(owner.recognizer.javaClass.name == "kr.co.hanium.dreamup.walksafe.voice.RemoteVoskDecoder")
            val decoderFields = original.javaClass.declaredFields.filter {
                it.type.name == "kr.co.hanium.dreamup.walksafe.voice.RemoteVoskDecoder" && !Modifier.isStatic(it.modifiers)
            }
            check(decoderFields.size == 1 && decoderFields.single().apply { isAccessible = true }.get(original) === owner.recognizer)
        }
        check(captured(original, VoskStreamingTranscriber::class.java) === owner.transcriber)
        val runFields = original.javaClass.declaredFields.filter { it.type == java.lang.Long.TYPE && !Modifier.isStatic(it.modifiers) }
        check(runFields.size == 1 && runFields.single().apply { isAccessible = true }.getLong(original) == owner.run)
        val raw = FileOutputStream(privateFile(root, "source-$number.s16le"))
        val attachment = try { Attachment(owner, callbackField, original, raw, journal, number, failure, maximumSamples, ready) }
            catch (cause: Throwable) { raw.close(); throw cause }
        attachment.wrapper = attachment::consume
        attachments[owner.source] = attachment // 설치/기록 실패도 원 capture 종료 뒤 정리한다.
        check(field(owner.request, "activeRunId") == owner.run && field(owner.request, "requestId") == owner.requestId)
        synchronized(field(owner.transcriber, "stateLock")!!) {
            check((field(owner.transcriber, "state") as Enum<*>).name == "RUNNING")
            check(field(owner.transcriber, "audioSource") === owner.source && field(owner.transcriber, "recognizer") === owner.recognizer)
            check((field(owner.transcriber, "generation") as Number).toLong() == owner.run)
            check(callbackField.get(owner.source) === original)
            callbackField.set(owner.source, attachment.wrapper)
            check(callbackField.get(owner.source) === attachment.wrapper)
        }
        journal.record("SOURCE_ATTACHED", JSONObject().put("source_number", number)
            .put("bound_run", owner.run).put("bound_request_id", owner.requestId)
            .put("bound_request_generation", owner.requestGeneration)
            .put("bound_java_recognizer_id", journal.identity(owner.recognizer))
            .put("source_class", owner.source.javaClass.name).put("transcriber_class", owner.transcriber.javaClass.name)
            .put("raw_file", File(root, "source-$number.s16le").absolutePath)
            .put("raw_format", "S16LE_MONO").put("sample_rate_hz", 16_000)
            .put("final_field", Modifier.isFinal(callbackField.modifiers)).put("wrapper_execution_proof_pending", true)
            .put("first_chunks_may_be_missing", true).put("jni_accept_claimed", false))
    }

    private companion object {
        const val CURRENT_NORMAL_APP_SHA256 = "38d794285c2008bd535223bb1f30f45b0afed0cacc8c47a2789f846234fc451e"
        const val CURRENT_DESTINATION_WAV_SHA256 = "134d407669728143fbd848353e495d86ba93cfa859d54cffb2db18019341780e"
        fun field(receiver: Any, name: String): Any? = receiver.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(receiver)
        fun <T> captured(receiver: Any, type: Class<T>): T {
            val fields = receiver.javaClass.declaredFields.filter { it.type == type && !Modifier.isStatic(it.modifiers) }
            check(fields.size == 1) { "The exact existing delegate capture is unavailable" }
            return type.cast(fields.single().apply { isAccessible = true }.get(receiver))
        }
        fun privateMode(file: File, mode: Int) {
            Os.chmod(file.absolutePath, mode)
            check((Os.stat(file.absolutePath).st_mode and 0x1ff) == mode)
        }
        fun privateFile(root: File, name: String): File = File(root, name).also {
            check(it.createNewFile())
            privateMode(it, 0x180)
            check(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == root.canonicalFile)
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
