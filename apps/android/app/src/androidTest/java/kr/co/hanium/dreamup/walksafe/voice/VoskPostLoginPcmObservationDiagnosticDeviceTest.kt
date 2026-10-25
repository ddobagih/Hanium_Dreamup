package kr.co.hanium.dreamup.walksafe.voice

import android.app.Activity
import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.MainActivity
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 정상 UI의 post-login 호출어 요청 한 번을 수동으로 진행하면서 callback PCM만 관측한다.
 * Native 소유자는 실행 전에 외부 proof로 격리 가상 입력/고정 합성 WAV를 확인해야 한다.
 * 첫 청크는 누락될 수 있으며 원 consumer의 반환은 JNI accept 또는 성능 증거가 아니다.
 * 로그인/교육/인식결과를 만들지 않고 원 wake timeout 20초도 변경하지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class VoskPostLoginPcmObservationDiagnosticDeviceTest {
    @Test(timeout = 180_000L)
    fun observeNormalPostLoginCallbackPcm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runVoskPostLoginPcmObservationDiagnostic") == "true")
        check(arguments.getString("postLoginPcmIsolatedVirtualSyntheticInput") == "true")
        val externalProof = checkNotNull(arguments.getString("postLoginPcmExternalProofSha256"))
        check(externalProof.matches(Regex("[0-9a-f]{64}")))
        check(arguments.getString("postLoginPcmSyntheticWavSha256") == SYNTHETIC_WAV_SHA256)
        val context = instrumentation.targetContext
        check(context.packageName == "kr.co.hanium.dreamup.walksafe")
        check(context.applicationInfo.uid == 10178 && Process.myUid() == 10178)
        check(BuildConfig.DEBUG && !BuildConfig.DEVELOPMENT_QUICK_START && !BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
        val application = context.applicationContext as Application
        val handler = Handler(Looper.getMainLooper())
        val main = AtomicReference<MainActivity?>(null)
        val probeSeen = AtomicReference<VoskWakePhraseProbe?>(null)
        val attachment = AtomicReference<Attachment?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val watching = AtomicBoolean(true)
        val bodyDeadline = SystemClock.elapsedRealtime() + 150_000L
        val cache = context.cacheDir
        check(cache.isDirectory && !Files.isSymbolicLink(cache.toPath()))
        val root = File(cache, "vosk-post-login-pcm-${SystemClock.elapsedRealtimeNanos()}")
        check(root.mkdir())
        privateMode(root, 0x1c0)
        check(root.canonicalFile.parentFile == cache.canonicalFile)
        val journal = Journal(privateFile(root, "journal.jsonl"))
        var scenario: ActivityScenario<MainActivity>? = null
        var registered = false
        var primaryFailure: Throwable? = null
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) {
                    val previous = main.get()
                    if (previous == null || previous === activity) main.set(activity)
                    else failure.compareAndSet(null, IllegalStateException("A different MainActivity owner appeared"))
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
                    main.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.let { activity ->
                        currentOwner(activity, probeSeen)?.let { owner ->
                            val existing = attachment.get()
                            if (existing != null) {
                                check(existing.owner.source === owner.source && existing.owner.probe === owner.probe)
                                check(existing.callbackField.get(owner.source) === existing.wrapper)
                            } else if (failure.get() == null) {
                                attach(owner, root, journal, failure, attachment) {
                                    instrumentation.sendStatus(0, Bundle().apply {
                                        putString("stream", "POST_LOGIN_PCM_CALLBACK_READY artifact=${root.absolutePath}\n")
                                    })
                                }
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
            journal.record("BEGIN", JSONObject().put("external_native_proof_sha256", externalProof)
                .put("external_isolated_virtual_synthetic_input_attested", true)
                .put("guest_route_independently_verified", false).put("synthetic_wav_sha256", SYNTHETIC_WAV_SHA256)
                .put("wake_timeout_ms", 20_000).put("first_chunks_may_be_missing", true)
                .put("jni_accept_claimed", false).put("performance_evidence", false)
                .put("business_fields_or_results_injected", false))
            journal.flush()
            instrumentation.runOnMainSync {
                application.registerActivityLifecycleCallbacks(lifecycle)
                registered = true
                check(handler.post(poll))
            }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "POST_LOGIN_PCM_WAITING_FOR_NORMAL_UI artifact=${root.absolutePath}\n")
            })
            // Native 담당자가 정상 UI 버튼 한 번과 합성 입력 한 번을 소유한다.
            while (true) {
                val probe = probeSeen.get()
                if (probe != null && terminal(probe) && (attachment.get()?.retired() != false)) break
                check(SystemClock.elapsedRealtime() < bodyDeadline) { "No completed original post-login request within the diagnostic bound" }
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
            cleanup { scenario?.close() } // 정상 lifecycle 취소로만 종료하며 제품 필드를 쓰지 않는다.
            var drained = false
            cleanup {
                check(watcherRetired)
                val deadline = SystemClock.elapsedRealtime() + 10_000L
                while (attachment.get()?.retired() == false) {
                    check(SystemClock.elapsedRealtime() < deadline) { "Capture remains active; artifacts remain open" }
                    Thread.sleep(10L)
                }
                val released = CountDownLatch(1)
                VoskStreamingWorker.process.execute { released.countDown() }
                check(released.await((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L), TimeUnit.MILLISECONDS))
                drained = true
            }
            if (drained) {
                var callbackRestored = false
                var wrapperExecutionVerified = false
                cleanup {
                    val observed = attachment.get()
                    checkNotNull(observed) { "No actual post-login callback source was attached" }
                    try {
                        if (observed.callbackField.get(observed.owner.source) === observed.wrapper) {
                            observed.callbackField.set(observed.owner.source, observed.original)
                            check(observed.callbackField.get(observed.owner.source) === observed.original)
                            callbackRestored = true
                        } else {
                            failure.compareAndSet(null, IllegalStateException("Foreign callback owner was preserved"))
                        }
                    } finally { observed.finish() }
                    check(observed.callbacks.get() > 0 && observed.callbacks.get() == observed.consumerCalls.get())
                    wrapperExecutionVerified = true
                }
                cleanup { journal.record("FINISHED", JSONObject().put("capture_drained", true)
                    .put("callback_restored", callbackRestored).put("wrapper_execution_verified", wrapperExecutionVerified)
                    .put("observation_failure_class", failure.get()?.javaClass?.simpleName ?: "NONE")
                    .put("primary_failure_class", primaryFailure?.javaClass?.simpleName ?: "NONE")
                    .put("jni_accept_claimed", false).put("artifacts_preserved", true)) }
                cleanup { journal.close() }
                cleanup { instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "POST_LOGIN_PCM_OBSERVER_RETIRED artifact=${root.absolutePath}\n")
                }) }
            } else cleanup {
                journal.record("CLEANUP_INCOMPLETE", JSONObject().put("capture_drained", false).put("artifacts_left_open", true))
                journal.flush()
            }
            cleanup { instrumentation.runOnMainSync { if (registered) application.unregisterActivityLifecycleCallbacks(lifecycle) } }
            failure.get()?.let { cause -> cleanup { throw AssertionError("Post-login PCM observation failed", cause) } }
            cleanupFailure?.let { cause ->
                val original = primaryFailure
                if (original == null) throw cause
                if (original !== cause) original.addSuppressed(cause)
            }
        }
    }

    /** One opt-in real callback observation, followed by a fixed same-Main education handoff. */
    @Test(timeout = 600_000L)
    fun observeNormalPostLoginPcmAndTranscriptThenHoldMainForEducation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runVoskPostLoginPcmEducationHandoff") == "true")
        val prestartArgument = arguments.getString("postLoginPcmPrestartObservation")
        check(prestartArgument == null || prestartArgument in setOf("true", "false"))
        val prestart = prestartArgument == "true"
        val build = ObservationBuild.valueOf(arguments.getString("postLoginPcmObservationBuild") ?: "CURRENT")
        check(build == ObservationBuild.CURRENT || prestart) { "PRE_V6 requires prestart observation" }
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(arguments.getString("postLoginPcmIsolatedVirtualSyntheticInput") == "true")
        val proof = checkNotNull(arguments.getString("postLoginPcmExternalProofSha256"))
        check(proof.matches(Regex("[0-9a-f]{64}")))
        check(arguments.getString("postLoginPcmSyntheticWavSha256") == SYNTHETIC_WAV_SHA256)
        val context = instrumentation.targetContext
        check(context.packageName == "kr.co.hanium.dreamup.walksafe")
        check(context.applicationInfo.uid == 10178 && Process.myUid() == 10178)
        check(BuildConfig.DEBUG && !BuildConfig.DEVELOPMENT_QUICK_START && !BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
        val apkDigest = MessageDigest.getInstance("SHA-256")
        File(context.applicationInfo.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                apkDigest.update(buffer, 0, count)
            }
        }
        check(hex(apkDigest.digest()) == build.apkSha256)
        val application = context.applicationContext as Application
        val handler = Handler(Looper.getMainLooper())
        val main = AtomicReference<MainActivity?>(null)
        val probeSeen = AtomicReference<VoskWakePhraseProbe?>(null)
        val observed = AtomicReference<EducationHandoffObservation?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val watching = AtomicBoolean(true)
        val deadline = SystemClock.elapsedRealtime() + 150_000L
        val root = File(context.cacheDir, "vosk-post-login-education-pcm-${SystemClock.elapsedRealtimeNanos()}")
        check(!Files.isSymbolicLink(context.cacheDir.toPath()) && root.mkdir())
        privateMode(root, 0x1c0)
        check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
        val journal = Journal(privateFile(root, "journal.jsonl"))
        var scenario: ActivityScenario<MainActivity>? = null
        var registered = false
        var primaryFailure: Throwable? = null
        val prestartHold = if (prestart) PrestartHold(deadline, journal, failure) else null
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) {
                    val previous = main.get()
                    if (previous == null || previous === activity) main.set(activity)
                    else failure.compareAndSet(null, IllegalStateException("A different MainActivity owner appeared"))
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
                    main.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.let { activity ->
                        (if (prestartHold != null && observed.get() == null)
                            prestartOwner(activity, probeSeen, prestartHold, build)
                        else currentOwner(activity, probeSeen, build))?.let { owner ->
                            val existing = observed.get()
                            if (existing == null && failure.get() == null) {
                                val capture = EducationHandoffObservation(owner, root, journal, failure) {
                                    instrumentation.sendStatus(0, Bundle().apply {
                                        putString("stream", "POST_LOGIN_EDUCATION_CALLBACK_READY artifact=${root.absolutePath}\n")
                                    })
                                }
                                check(observed.compareAndSet(null, capture))
                                capture.attach(prestartHold)
                                prestartHold?.release()
                            } else if (existing != null) existing.checkOwner(owner)
                        }
                    }
                } catch (cause: Throwable) {
                    failure.compareAndSet(null, cause)
                    prestartHold?.release()
                } finally {
                    if (watching.get()) handler.postDelayed(this, 50L)
                }
            }
        }
        fun drain(until: Long) {
            while (observed.get()?.retired() == false) {
                check(SystemClock.elapsedRealtime() < until) { "Capture remains active; artifacts are preserved" }
                Thread.sleep(10L)
            }
            val barrier = CountDownLatch(1)
            VoskStreamingWorker.process.execute { barrier.countDown() }
            check(barrier.await((until - SystemClock.elapsedRealtime()).coerceAtLeast(0L), TimeUnit.MILLISECONDS))
        }
        try {
            journal.record("BEGIN", JSONObject().put("external_native_proof_sha256", proof)
                .put("isolated_emulator_synthetic_input_only", true).put("wake_timeout_ms", 20_000)
                .put("observer_limit_ms", 150_000).put("success_hold_ms", 360_000)
                .put("first_pcm_chunks_may_be_missing", true).put("jni_accept_claimed", false)
                .put("prestart_observation_requested", prestart).put("observation_build", build.name)
                .put("configured_home_wake_endpointing", build.homeWakeEndpointing).put("runtime_endpoint_field_check_pending", true)
                .put("actual_target_apk_sha256", build.apkSha256).put("prefix_collection_verified", false)
                .put("performance_evidence", false).put("product_raw_transcript_logging_added", false)
                .put("business_fields_or_results_injected", false))
            journal.flush()
            prestartHold?.enter()
            instrumentation.runOnMainSync {
                application.registerActivityLifecycleCallbacks(lifecycle)
                registered = true
                check(handler.post(poll))
            }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "POST_LOGIN_EDUCATION_WAITING_FOR_NORMAL_UI artifact=${root.absolutePath}\n")
            })
            while (true) {
                failure.get()?.let { throw AssertionError("Callback observation failed", it) }
                val probe = probeSeen.get()
                if (probe != null && terminal(probe) && observed.get()?.retired() == true) break
                check(SystemClock.elapsedRealtime() < deadline) { "Original wake observation150 expired" }
                Thread.sleep(25L)
            }
            watching.set(false)
            prestartHold?.release()
            instrumentation.runOnMainSync { handler.removeCallbacks(poll) }
            drain(minOf(deadline, SystemClock.elapsedRealtime() + 10_000L))
            val capture = checkNotNull(observed.get())
            capture.restoreAndFinish()
            failure.get()?.let { throw AssertionError("Callback observation failed", it) }
            check(SystemClock.elapsedRealtime() < deadline)
            instrumentation.waitForIdleSync()
            var eligible = false
            instrumentation.runOnMainSync {
                val activity = checkNotNull(main.get())
                check(!activity.isFinishing && !activity.isDestroyed)
                eligible = educationHandoffStageReady(activity.window.decorView)
            }
            val holdBegan = SystemClock.elapsedRealtime()
            val result = JSONObject().put("artifact", root.absolutePath).put("owner_pid", Process.myPid())
                .put("owner_uid", Process.myUid()).put("capture_drained_restored_and_closed", true)
                .put("actual_stage1_enabled_stage2_disabled", eligible)
                .put("hold_started_elapsed_ms", holdBegan)
                .put("hold_deadline_elapsed_ms", if (eligible) holdBegan + 360_000L else JSONObject.NULL)
            journal.record("EDUCATION_HANDOFF", result)
            journal.flush()
            instrumentation.sendStatus(0, Bundle().apply {
                putString("post_login_education_handoff", result.toString())
            })
            if (eligible) {
                // No input, completion latch, state mutation or early close during Native's UI work.
                val holdDeadline = holdBegan + 360_000L
                while (SystemClock.elapsedRealtime() < holdDeadline) {
                    failure.get()?.let { throw AssertionError("Owner changed during education hold", it) }
                    instrumentation.runOnMainSync {
                        val activity = checkNotNull(main.get())
                        check(!activity.isFinishing && !activity.isDestroyed)
                    }
                    Thread.sleep(minOf(250L, (holdDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)))
                }
                journal.record("FIXED_EDUCATION_HOLD_FINISHED", JSONObject().put("hold_ms", 360_000))
                journal.flush()
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
            cleanup { prestartHold?.release() }
            cleanup { instrumentation.runOnMainSync { handler.removeCallbacks(poll) } }
            cleanup { scenario?.close() }
            if (observed.get()?.finished?.get() == false || prestartHold != null) {
                cleanup {
                    drain(SystemClock.elapsedRealtime() + 10_000L)
                    prestartHold?.checkExited()
                    observed.get()?.takeUnless { it.finished.get() }?.restoreAndFinish()
                }
            }
            cleanup { instrumentation.runOnMainSync { if (registered) application.unregisterActivityLifecycleCallbacks(lifecycle) } }
            cleanup { journal.record("FINISHED", JSONObject()
                .put("capture_finalized", observed.get()?.finished?.get() == true)
                .put("observation_failure_class", failure.get()?.javaClass?.simpleName ?: "NONE")
                .put("primary_failure_class", primaryFailure?.javaClass?.simpleName ?: "NONE")
                .put("cleanup_failure_class", cleanupFailure?.javaClass?.simpleName ?: "NONE")) }
            cleanup { journal.close() }
            failure.get()?.let { cause -> cleanup { throw AssertionError("Callback observation failed", cause) } }
            cleanupFailure?.let { cause ->
                val original = primaryFailure
                if (original == null) throw cause
                if (original !== cause) original.addSuppressed(cause)
            }
        }
    }

    private fun educationHandoffStageReady(view: View): Boolean {
        val buttons = mutableListOf<Button>()
        fun visit(current: View) {
            if (current is Button && current.isShown) buttons.add(current)
            if (current is ViewGroup) for (index in 0 until current.childCount) visit(current.getChildAt(index))
        }
        visit(view)
        val stage1 = buttons.filter { it.text.toString() == "1. 안전 제한 안내 듣기" }
        val stage2 = buttons.filter { it.text.toString() == "2. 연습 필요성 듣기" }
        return stage1.size == 1 && stage1.single().isEnabled && stage1.single().isClickable &&
            stage2.size == 1 && !stage2.single().isEnabled
    }

    /** Dedicated to the new opt-in method; existing observation helpers remain unchanged. */
    private class EducationHandoffObservation(val owner: Owner, root: File, private val journal: Journal,
        private val failure: AtomicReference<Throwable?>, private val ready: () -> Unit) {
        private val pcmField = AndroidPcmAudioSource::class.java.getDeclaredField("onPcm16").apply { isAccessible = true }
        private val listenerField = VoskStreamingTranscriber::class.java.getDeclaredField("listener").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        private val originalPcm = pcmField.get(owner.source) as (ShortArray) -> Unit
        private val originalListener = listenerField.get(owner.transcriber) as VoskStreamingListener
        private val raw = FileOutputStream(privateFile(root, "callback.s16le"))
        private val digest = MessageDigest.getInstance("SHA-256")
        private val callbacks = AtomicInteger(0)
        private val consumerCalls = AtomicInteger(0)
        private val transcriptCalls = AtomicInteger(0)
        private val finalCalls = AtomicInteger(0)
        private val inFlight = AtomicInteger(0)
        private val captureThread = AtomicReference(owner.captureThread)
        private val readySent = AtomicBoolean(false)
        private var prestartHold: PrestartHold? = null
        private var attachedAtNanos = 0L
        private var firstCallbackAtNanos = 0L
        private var pcmInstalled = false
        private var listenerInstalled = false
        private var samplesTotal = 0L
        private var consumerFailures = 0
        val finished = AtomicBoolean(false)
        private val pcmWrapper: (ShortArray) -> Unit = { samples ->
            inFlight.incrementAndGet()
            val ordinal = callbacks.incrementAndGet()
            var bytes: ByteArray? = null
            observe {
                check(samples.isNotEmpty())
                if (captureThread.get() == null) {
                    val actualThread = synchronized(field(owner.source, "stateLock")!!) {
                        val session = checkNotNull(field(owner.source, "activeSession"))
                        val record = field(session, "record") as AudioRecord
                        check(record.sampleRate == 16_000 && record.channelCount == 1 && record.audioFormat == AudioFormat.ENCODING_PCM_16BIT)
                        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                        field(session, "worker") as Thread
                    }
                    captureThread.compareAndSet(null, actualThread)
                }
                check(Thread.currentThread() === captureThread.get())
                if (ordinal == 1) {
                    firstCallbackAtNanos = SystemClock.elapsedRealtimeNanos()
                    prestartHold?.let { hold ->
                        check(attachedAtNanos > 0 && hold.releasedAtNanos.get() >= attachedAtNanos)
                        check(firstCallbackAtNanos >= hold.releasedAtNanos.get())
                        check(pcmField.get(owner.source) === pcmWrapper)
                        journal.record("PRESTART_FIRST_CALLBACK_EXECUTED", JSONObject()
                            .put("attached_at_elapsed_nanos", attachedAtNanos)
                            .put("worker_release_at_elapsed_nanos", hold.releasedAtNanos.get())
                            .put("first_callback_at_elapsed_nanos", firstCallbackAtNanos)
                            .put("actual_audio_record_format_verified", true).put("same_capture_thread", true)
                            .put("jni_accept_claimed", false))
                    }
                }
                bytes = pcm16(samples)
            }
            var returned = false
            try {
                consumerCalls.incrementAndGet()
                originalPcm(samples)
                returned = true
            } finally {
                observe {
                    check(Thread.currentThread() === captureThread.get())
                    val captured = checkNotNull(bytes)
                    check(samplesTotal + captured.size / 2 <= 524_288L)
                    raw.write(captured)
                    digest.update(captured)
                    samplesTotal += captured.size / 2
                    journal.record("CALLBACK", JSONObject().put("ordinal", ordinal).put("samples", captured.size / 2)
                        .put("same_capture_thread", true).put("original_returned", returned))
                    if (ordinal == 1) journal.flush()
                    val running = prestartHold == null || synchronized(field(owner.transcriber, "stateLock")!!) {
                        (field(owner.transcriber, "state") as Enum<*>).name == "RUNNING" &&
                            field(owner.transcriber, "generation") == owner.run
                    }
                    if (failure.get() == null && running && readySent.compareAndSet(false, true)) ready()
                }
                if (!returned) consumerFailures++
                inFlight.decrementAndGet()
            }
        }
        private val listenerWrapper: VoskStreamingListener = object : VoskStreamingListener {
            override fun onReady(runId: Long) = originalListener.onReady(runId)
            override fun onAudioEnd(runId: Long) = originalListener.onAudioEnd(runId)
            override fun onAudioEnd(runId: Long, inputGeneration: Long?) = originalListener.onAudioEnd(runId, inputGeneration)
            override fun onError(runId: Long, error: VoskStreamingError) = originalListener.onError(runId, error)
            override fun onTranscript(runId: Long, transcript: VoskTranscript) {
                try { originalListener.onTranscript(runId, transcript) }
                finally { recordTranscript(runId, transcript, null) }
            }
            override fun onTranscript(runId: Long, transcript: VoskTranscript, inputGeneration: Long?) {
                try { originalListener.onTranscript(runId, transcript, inputGeneration) }
                finally { recordTranscript(runId, transcript, inputGeneration) }
            }
        }
        private fun observe(action: () -> Unit) {
            try { action() } catch (cause: Throwable) { failure.compareAndSet(null, cause) }
        }
        private fun recordTranscript(runId: Long, transcript: VoskTranscript, generation: Long?): Unit = observe {
            transcriptCalls.incrementAndGet()
            check(runId == owner.run && Thread.currentThread() === captureThread.get())
            check(listenerField.get(owner.transcriber) === listenerWrapper)
            if (transcript.isFinal) {
                finalCalls.incrementAndGet()
                journal.record("ACTUAL_PARSED_FINAL", JSONObject().put("run", runId)
                    .put("input_generation", generation ?: JSONObject.NULL).put("text", transcript.text)
                    .put("confidence", transcript.confidence ?: JSONObject.NULL)
                    .put("same_transcript_object_delegated_once", true).put("same_capture_thread", true))
            }
        }
        fun attach(hold: PrestartHold? = null) {
            check(captured(originalPcm, owner.recognizer.javaClass) === owner.recognizer)
            check(captured(originalPcm, VoskStreamingTranscriber::class.java) === owner.transcriber)
            check(field(owner.probes, "wakeProbe") === owner.wakeOwner)
            synchronized(field(owner.transcriber, "decoderLock")!!) {
                synchronized(field(owner.probe, "stateLock")!!) {
                    synchronized(field(owner.transcriber, "stateLock")!!) {
                        check(field(owner.transcriber, "generation") == owner.run)
                        if (hold == null) {
                            check((field(owner.probe, "state") as Enum<*>).name == "LISTENING")
                            check(field(owner.probe, "activeRunId") == owner.run)
                            check((field(owner.transcriber, "state") as Enum<*>).name == "RUNNING")
                            check(field(owner.transcriber, "audioSource") === owner.source)
                            check(field(owner.transcriber, "recognizer") === owner.recognizer)
                        } else {
                            hold.checkHeld()
                            check((field(owner.probe, "state") as Enum<*>).name == "STARTING")
                            check((field(owner.transcriber, "state") as Enum<*>).name == "STARTING")
                            check(field(checkNotNull(field(owner.probe, "stream")), "transcriber") === owner.transcriber)
                            check(field(owner.transcriber, "audioSource") == null && field(owner.transcriber, "recognizer") == null)
                            check(hold.queue.size == 1 && hold.queue.peek() === owner.queuedTask)
                            val prepared = checkNotNull(owner.prepared)
                            check(field(prepared, "audioSource") === owner.source)
                            check(field(prepared, "recognizer") === owner.recognizer)
                            synchronized(field(owner.source, "stateLock")!!) {
                                check((field(owner.source, "state") as Enum<*>).name == "IDLE")
                                check(field(owner.source, "activeSession") == null)
                            }
                            prestartHold = hold
                        }
                        check(pcmField.get(owner.source) === originalPcm)
                        check(listenerField.get(owner.transcriber) === originalListener)
                        pcmField.set(owner.source, pcmWrapper)
                        pcmInstalled = true
                        listenerField.set(owner.transcriber, listenerWrapper)
                        listenerInstalled = true
                        check(pcmField.get(owner.source) === pcmWrapper)
                        check(listenerField.get(owner.transcriber) === listenerWrapper)
                        attachedAtNanos = SystemClock.elapsedRealtimeNanos()
                    }
                }
            }
            journal.record("SOURCE_AND_LISTENER_ATTACHED", JSONObject().put("run", owner.run)
                .put("same_source_decoder_and_original_listener", true).put("listener_ART_execution_pending", true)
                .put("prestart_source_idle_session_null", hold != null)
                .put("configured_home_wake_endpointing", field(owner.transcriber, "homeWakeEndpointing"))
                .put("attached_at_elapsed_nanos", attachedAtNanos).put("prefix_collection_verified", false))
            journal.flush()
        }
        fun checkOwner(current: Owner) {
            check(current.source === owner.source && current.probe === owner.probe && current.run == owner.run)
            check(pcmField.get(owner.source) === pcmWrapper && listenerField.get(owner.transcriber) === listenerWrapper)
        }
        fun retired(): Boolean {
            val active = synchronized(field(owner.source, "stateLock")!!) { field(owner.source, "activeSession") != null }
            val current = synchronized(field(owner.transcriber, "stateLock")!!) { field(owner.transcriber, "audioSource") === owner.source }
            return !active && !current && captureThread.get()?.isAlive != true && inFlight.get() == 0
        }
        fun restoreAndFinish() {
            check(retired())
            check(finished.compareAndSet(false, true))
            var restoreFailure: Throwable? = null
            var pcmRestored = false
            var listenerRestored = false
            var rawClosed = false
            fun restore(action: () -> Unit) {
                try { action() } catch (cause: Throwable) {
                    if (restoreFailure == null) restoreFailure = cause else restoreFailure!!.addSuppressed(cause)
                }
            }
            synchronized(field(owner.transcriber, "decoderLock")!!) {
                restore {
                    check(pcmField.get(owner.source) === if (pcmInstalled) pcmWrapper else originalPcm) { "Foreign PCM callback preserved" }
                    if (pcmInstalled) pcmField.set(owner.source, originalPcm)
                    check(pcmField.get(owner.source) === originalPcm)
                    pcmRestored = true
                }
                restore {
                    check(listenerField.get(owner.transcriber) === if (listenerInstalled) listenerWrapper else originalListener) { "Foreign listener preserved" }
                    if (listenerInstalled) listenerField.set(owner.transcriber, originalListener)
                    check(listenerField.get(owner.transcriber) === originalListener)
                    listenerRestored = true
                }
            }
            restore { raw.use { it.fd.sync() }; rawClosed = true }
            journal.record("SOURCE_FINISHED", JSONObject().put("capture_drained", true)
                .put("callback_restored", pcmRestored).put("listener_restored", listenerRestored)
                .put("callbacks", callbacks.get()).put("consumer_calls", consumerCalls.get())
                .put("consumer_failures", consumerFailures).put("transcript_callbacks", transcriptCalls.get())
                .put("parsed_finals", finalCalls.get()).put("samples", samplesTotal).put("bytes", samplesTotal * 2)
                .put("raw_s16le_sha256", hex(digest.digest())).put("raw_closed", rawClosed)
                .put("listener_ART_execution_verified", transcriptCalls.get() > 0)
                .put("prestart_callback_ART_execution_verified", prestartHold != null && firstCallbackAtNanos > 0)
                .put("jni_accept_claimed", false))
            journal.flush()
            restoreFailure?.let { throw it }
            check(callbacks.get() > 0 && callbacks.get() == consumerCalls.get() && consumerFailures == 0)
            check(transcriptCalls.get() > 0) { "No actual listener callback; ART execution is unverified" }
        }
    }

    private enum class ObservationBuild(val apkSha256: String, val recognizerClassName: String,
        val homeWakeEndpointing: Boolean) {
        CURRENT("54282d0847ddb8a83c53b7f81ed34b589a9aba865f8f4af9f2cab011938be8d5",
            "kr.co.hanium.dreamup.walksafe.voice.RemoteVoskDecoder", true),
        PRE_V6("4f9b4e2eeefda04bf253a23bf975e959a42300886bd0f62ffece48a25e31baca", "org.vosk.Recognizer", false),
    }

    /** One bounded task only; the original queued task is never removed, invoked or replaced. */
    private class PrestartHold(private val deadline: Long, private val journal: Journal,
        private val failure: AtomicReference<Throwable?>) {
        private val entered = CountDownLatch(1)
        private val releaseLatch = CountDownLatch(1)
        private val exited = AtomicBoolean(false)
        private val active = AtomicBoolean(false)
        val releasedAtNanos = AtomicLong(0L)
        lateinit var queue: BlockingQueue<Runnable>
            private set

        fun enter() {
            val executor = VoskStreamingWorker.process
            // Android's delegate is inherited. A denied non-SDK field is a preparation failure;
            // no hidden_api policy change or fallback executor is permitted.
            val delegateFields = generateSequence(executor.javaClass as Class<*>?) { it.superclass }
                .take(6).flatMap { it.declaredFields.asSequence() }
                .filter { it.name == "e" && it.type == ExecutorService::class.java && !Modifier.isStatic(it.modifiers) }
                .toList()
            check(delegateFields.size == 1)
            val pool = delegateFields.single().apply { isAccessible = true }.get(executor) as ThreadPoolExecutor
            check(pool.corePoolSize == 1 && pool.maximumPoolSize == 1 && !pool.isShutdown)
            queue = pool.queue
            check(queue.isEmpty() && pool.activeCount == 0) { "Existing Vosk worker work is not owned by this observation" }
            executor.execute {
                try {
                    active.set(true)
                    journal.record("PRESTART_WORKER_ENTERED", JSONObject().put("same_shared_worker", true)
                        .put("worker_name", Thread.currentThread().name).put("observer_limit_ms", 150_000))
                    journal.flush()
                    entered.countDown()
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    check(remaining > 0 && releaseLatch.await(remaining, TimeUnit.MILLISECONDS)) { "Prestart worker bound expired" }
                } catch (cause: Throwable) {
                    failure.compareAndSet(null, cause)
                } finally {
                    release()
                    active.set(false)
                    exited.set(true)
                    entered.countDown()
                }
            }
            val remaining = minOf(2_000L, deadline - SystemClock.elapsedRealtime())
            check(remaining > 0 && entered.await(remaining, TimeUnit.MILLISECONDS)) { "Vosk worker did not enter before Main" }
            checkHeld()
        }

        fun checkHeld() {
            failure.get()?.let { throw AssertionError("Prestart preparation failed", it) }
            check(active.get() && entered.count == 0L && releasedAtNanos.get() == 0L)
            check(SystemClock.elapsedRealtime() < deadline)
        }

        fun release() {
            val now = SystemClock.elapsedRealtimeNanos()
            if (!releasedAtNanos.compareAndSet(0L, now)) return
            releaseLatch.countDown() // Release cannot depend on observation disk I/O completing.
            try {
                journal.record("PRESTART_WORKER_RELEASE_REQUESTED", JSONObject().put("release_at_elapsed_nanos", now))
                journal.flush()
            } catch (cause: Throwable) {
                failure.compareAndSet(null, cause)
            }
        }

        fun checkExited() { check(!active.get() && (entered.count != 0L || exited.get())) }
    }

    /** Reads fields only: PRE_V6 and CURRENT have different prepared recognizer getter signatures. */
    private fun prestartOwner(main: MainActivity, seen: AtomicReference<VoskWakePhraseProbe?>,
        hold: PrestartHold, build: ObservationBuild): Owner? {
        check(Looper.myLooper() === Looper.getMainLooper())
        hold.checkHeld()
        val holder = field(main, "postLoginDeviceProbesHolder") as Lazy<*>
        if (!holder.isInitialized()) return null
        val probes = checkNotNull(holder.value)
        val wakeOwner = field(probes, "wakeProbe") ?: return null
        val probe = captured(wakeOwner, VoskWakePhraseProbe::class.java)
        val prior = seen.get()
        check(prior == null || prior === probe) { "A second post-login attempt appeared" }
        seen.compareAndSet(null, probe)
        val stream = synchronized(field(probe, "stateLock")!!) {
            val state = (field(probe, "state") as Enum<*>).name
            if (state == "IDLE") return@synchronized null
            check(state == "STARTING") { "Post-login source passed its prestart boundary" }
            check(field(probe, "listenUntilWake") == false && field(probe, "timeoutMs") == 20_000L)
            field(probe, "stream")
        } ?: return null
        check(stream.javaClass.name == "kr.co.hanium.dreamup.walksafe.voice.AndroidVoskWakePhraseStream")
        val transcriber = field(stream, "transcriber") as VoskStreamingTranscriber
        return synchronized(field(transcriber, "stateLock")!!) {
            val state = (field(transcriber, "state") as Enum<*>).name
            if (state == "STOPPED") return@synchronized null
            check(state == "STARTING")
            val run = (field(transcriber, "generation") as Number).toLong()
            check(run > 0L && field(transcriber, "homeWakeEndpointing") == build.homeWakeEndpointing && field(transcriber, "preparedRunFactory") == null)
            check(field(transcriber, "audioSource") == null && field(transcriber, "recognizer") == null)
            val tasks = hold.queue.toTypedArray()
            if (tasks.isEmpty()) return@synchronized null
            check(tasks.size == 1) { "Expected exactly one prepared task" }
            val task = tasks.single()
            check(captured(task, VoskStreamingTranscriber::class.java) === transcriber)
            val preparedFields = task.javaClass.declaredFields.filter {
                !Modifier.isStatic(it.modifiers) && it.type.name == "kr.co.hanium.dreamup.walksafe.voice.VoskPreparedRun"
            }
            check(preparedFields.size == 1)
            val prepared = checkNotNull(preparedFields.single().apply { isAccessible = true }.get(task))
            val runs = task.javaClass.declaredFields.filter { it.type == java.lang.Long.TYPE && !Modifier.isStatic(it.modifiers) }
            check(runs.size == 1 && runs.single().apply { isAccessible = true }.getLong(task) == run)
            val source = field(prepared, "audioSource") as AndroidPcmAudioSource
            val recognizer = checkNotNull(field(prepared, "recognizer"))
            check(recognizer.javaClass.name == build.recognizerClassName && field(source, "audioRecordFactory") == null)
            synchronized(field(source, "stateLock")!!) {
                check((field(source, "state") as Enum<*>).name == "IDLE" && field(source, "activeSession") == null)
            }
            val original = checkNotNull(field(source, "onPcm16"))
            check(captured(original, recognizer.javaClass) === recognizer)
            check(captured(original, VoskStreamingTranscriber::class.java) === transcriber)
            val callbackRuns = original.javaClass.declaredFields.filter { it.type == java.lang.Long.TYPE && !Modifier.isStatic(it.modifiers) }
            check(callbackRuns.size == 1 && callbackRuns.single().apply { isAccessible = true }.getLong(original) == run)
            hold.checkHeld()
            check(hold.queue.size == 1 && hold.queue.peek() === task)
            Owner(probes, wakeOwner, probe, transcriber, source, recognizer, run, null, task, prepared)
        }
    }

    private data class Owner(val probes: Any, val wakeOwner: Any, val probe: VoskWakePhraseProbe,
        val transcriber: VoskStreamingTranscriber, val source: AndroidPcmAudioSource,
        val recognizer: Any, val run: Long, val captureThread: Thread?,
        val queuedTask: Runnable? = null, val prepared: Any? = null)

    private class Attachment(val owner: Owner, val callbackField: Field, val original: (ShortArray) -> Unit,
        private val raw: FileOutputStream, private val journal: Journal,
        private val failure: AtomicReference<Throwable?>, private val ready: () -> Unit) {
        lateinit var wrapper: (ShortArray) -> Unit
        val callbacks = AtomicInteger(0)
        val consumerCalls = AtomicInteger(0)
        private val inFlight = AtomicInteger(0)
        private val digest = MessageDigest.getInstance("SHA-256")
        private var samplesTotal = 0L
        private var consumerFailures = 0
        fun consume(samples: ShortArray) {
            inFlight.incrementAndGet()
            val ordinal = callbacks.incrementAndGet()
            var returned = false
            try {
                observe {
                    check(Thread.currentThread() === owner.captureThread)
                    check(samples.isNotEmpty() && samplesTotal + samples.size <= 524_288L)
                    val bytes = pcm16(samples)
                    raw.write(bytes)
                    digest.update(bytes)
                    samplesTotal += samples.size
                    journal.record("CALLBACK", JSONObject().put("ordinal", ordinal).put("samples", samples.size)
                        .put("same_capture_thread", true))
                }
                consumerCalls.incrementAndGet()
                original(samples) // 같은 배열·capture thread에서 원 consumer 정확 한 번.
                returned = true
                if (ordinal == 1) observe(ready)
            } finally {
                if (!returned) consumerFailures++
                inFlight.decrementAndGet()
            }
        }
        private fun observe(action: () -> Unit) {
            try { action() } catch (cause: Throwable) { failure.compareAndSet(null, cause) }
        }
        fun retired(): Boolean {
            val captureActive = synchronized(field(owner.source, "stateLock")!!) { field(owner.source, "activeSession") != null }
            val current = synchronized(field(owner.transcriber, "stateLock")!!) { field(owner.transcriber, "audioSource") === owner.source }
            return !captureActive && !current && owner.captureThread?.isAlive != true && inFlight.get() == 0
        }
        fun finish() {
            raw.use { it.fd.sync() }
            journal.record("SOURCE_FINISHED", JSONObject().put("callbacks", callbacks.get()).put("consumer_calls", consumerCalls.get())
                .put("consumer_failures", consumerFailures).put("samples", samplesTotal).put("bytes", samplesTotal * 2)
                .put("raw_s16le_sha256", hex(digest.digest())).put("jni_accept_claimed", false))
        }
    }

    private fun currentOwner(main: MainActivity, seen: AtomicReference<VoskWakePhraseProbe?>,
        build: ObservationBuild = ObservationBuild.CURRENT): Owner? {
        check(Looper.myLooper() === Looper.getMainLooper())
        val holder = field(main, "postLoginDeviceProbesHolder") as Lazy<*>
        if (!holder.isInitialized()) return null
        val probes = checkNotNull(holder.value)
        val wakeOwner = field(probes, "wakeProbe") ?: return null
        val probe = captured(wakeOwner, VoskWakePhraseProbe::class.java)
        val prior = seen.get()
        check(prior == null || prior === probe) { "A second post-login attempt appeared" }
        seen.compareAndSet(null, probe)
        val pair = synchronized(field(probe, "stateLock")!!) {
            if ((field(probe, "state") as Enum<*>).name != "LISTENING") return@synchronized null
            check(field(probe, "listenUntilWake") == false && field(probe, "timeoutMs") == 20_000L)
            Pair(checkNotNull(field(probe, "stream")), (field(probe, "activeRunId") as Number).toLong())
        } ?: return null
        check(pair.first.javaClass.name == "kr.co.hanium.dreamup.walksafe.voice.AndroidVoskWakePhraseStream")
        val transcriber = field(pair.first, "transcriber") as VoskStreamingTranscriber
        return synchronized(field(transcriber, "stateLock")!!) {
            if ((field(transcriber, "state") as Enum<*>).name != "RUNNING") return@synchronized null
            check((field(transcriber, "generation") as Number).toLong() == pair.second)
            check(field(transcriber, "homeWakeEndpointing") == build.homeWakeEndpointing && field(transcriber, "preparedRunFactory") == null)
            val source = field(transcriber, "audioSource") as? AndroidPcmAudioSource ?: return@synchronized null
            val recognizer = checkNotNull(field(transcriber, "recognizer"))
            check(recognizer.javaClass.name == build.recognizerClassName)
            check(field(source, "audioRecordFactory") == null)
            val worker = synchronized(field(source, "stateLock")!!) sourceState@ {
                val session = field(source, "activeSession") ?: return@sourceState null
                val record = field(session, "record") as AudioRecord
                check(record.sampleRate == 16_000 && record.channelCount == 1 && record.audioFormat == AudioFormat.ENCODING_PCM_16BIT)
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                field(session, "worker") as Thread
            } ?: return@synchronized null
            check(worker.isAlive && worker.name == "walksafe-pcm-capture")
            Owner(probes, wakeOwner, probe, transcriber, source, recognizer, pair.second, worker)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun attach(owner: Owner, root: File, journal: Journal, failure: AtomicReference<Throwable?>,
        attached: AtomicReference<Attachment?>, ready: () -> Unit) {
        val callback = AndroidPcmAudioSource::class.java.getDeclaredField("onPcm16").apply { isAccessible = true }
        val original = callback.get(owner.source) as (ShortArray) -> Unit
        check(captured(original, owner.recognizer.javaClass) === owner.recognizer)
        check(captured(original, VoskStreamingTranscriber::class.java) === owner.transcriber)
        val runs = original.javaClass.declaredFields.filter { it.type == java.lang.Long.TYPE && !Modifier.isStatic(it.modifiers) }
        check(runs.size == 1 && runs.single().apply { isAccessible = true }.getLong(original) == owner.run)
        val raw = FileOutputStream(privateFile(root, "callback.s16le"))
        val observer = try { Attachment(owner, callback, original, raw, journal, failure, ready) }
            catch (cause: Throwable) { raw.close(); throw cause }
        observer.wrapper = observer::consume
        check(attached.compareAndSet(null, observer))
        check(field(owner.probes, "wakeProbe") === owner.wakeOwner)
        synchronized(field(owner.probe, "stateLock")!!) {
            check((field(owner.probe, "state") as Enum<*>).name == "LISTENING" && field(owner.probe, "activeRunId") == owner.run)
            synchronized(field(owner.transcriber, "stateLock")!!) {
                check((field(owner.transcriber, "state") as Enum<*>).name == "RUNNING")
                check(field(owner.transcriber, "audioSource") === owner.source && field(owner.transcriber, "recognizer") === owner.recognizer)
                check((field(owner.transcriber, "generation") as Number).toLong() == owner.run && callback.get(owner.source) === original)
                callback.set(owner.source, observer.wrapper)
                check(callback.get(owner.source) === observer.wrapper)
            }
        }
        journal.record("SOURCE_ATTACHED", JSONObject().put("sample_rate_hz", 16_000).put("raw_format", "S16LE_MONO")
            .put("default_audio_record_factory", true).put("post_login_endpointing", true).put("original_wake_timeout_ms", 20_000)
            .put("same_owner_source_decoder_run", true).put("decoder_identity_is_main_proxy_only", true)
            .put("wrapper_execution_proof_pending", true))
        journal.flush()
    }

    private class Journal(file: File) {
        private val output = FileOutputStream(file)
        private val writer = try { output.bufferedWriter(Charsets.UTF_8) }
            catch (cause: Throwable) { output.close(); throw cause }
        @Synchronized fun record(event: String, data: JSONObject = JSONObject()) {
            writer.write(data.put("event", event).put("elapsed_ms", SystemClock.elapsedRealtime()).toString())
            writer.newLine()
        }
        @Synchronized fun flush() { writer.flush(); output.fd.sync() }
        @Synchronized fun close() { output.use { writer.use { it.flush(); output.fd.sync() } } }
    }

    private companion object {
        const val SYNTHETIC_WAV_SHA256 = "4ee8c3eb8bd5136483f3a4fa7e05ed20eabce5e79dae2620c63dd6f5996d3eab"
        fun field(receiver: Any, name: String): Any? = receiver.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(receiver)
        fun <T> captured(receiver: Any, type: Class<T>): T {
            val fields = receiver.javaClass.declaredFields.filter { it.type == type && !Modifier.isStatic(it.modifiers) }
            check(fields.size == 1)
            return type.cast(fields.single().apply { isAccessible = true }.get(receiver))
        }
        fun terminal(probe: VoskWakePhraseProbe): Boolean = synchronized(field(probe, "stateLock")!!) {
            (field(probe, "state") as Enum<*>).name == "TERMINAL"
        }
        fun privateMode(file: File, mode: Int) { Os.chmod(file.absolutePath, mode); check((Os.stat(file.absolutePath).st_mode and 0x1ff) == mode) }
        fun privateFile(root: File, name: String): File = File(root, name).also {
            check(it.createNewFile()); privateMode(it, 0x180)
            check(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == root.canonicalFile)
        }
        fun pcm16(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).also { bytes ->
            for (index in samples.indices) { val value = samples[index].toInt(); bytes[index * 2] = value.toByte(); bytes[index * 2 + 1] = (value ushr 8).toByte() }
        }
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
