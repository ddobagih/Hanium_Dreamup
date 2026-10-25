package kr.co.hanium.dreamup.walksafe.voice

import android.graphics.Rect
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.sun.jna.Pointer
import java.io.File
import java.io.FileInputStream
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
import java.util.concurrent.atomic.AtomicLong
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
 * Native의 실제 UI GUIDANCE→END→HOME 이후 source 하나의 callback만 관측한다.
 * UI/로그인/교육/정책/source 생성을 조작하지 않는다. instrumentation 시작은 앱 PID와
 * Activity를 바꿀 수 있으므로 Native는 SDK 준비 뒤 실제 UI 흐름을 다시 만들어야 한다.
 * BEFORE/AFTER는 consumer 경계이고 native accept/reset/partial/close 내부 trace가 아니다.
 * 최초 청크 누락, 기록 비용, SIGABRT에서 finally가 실행되지 않는 한계를 유지한다.
 */
@RunWith(AndroidJUnit4::class)
class VoskHomePcmObservationDiagnosticDeviceTest {
    @Test(timeout = 780_000L)
    fun observeOneHomeSourceAfterActualGuidanceEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("runVoskHomePcmObservationDiagnostic") == "true")
        check(BuildConfig.WALKSAFE_GATEWAY_ORIGIN == "http://127.0.0.1:8081")
        val context = instrumentation.targetContext
        check(context.packageName == "kr.co.hanium.dreamup.walksafe")
        check(Process.myUid() == 10178 && context.applicationInfo.uid == 10178)
        val expectedApkSha = InstrumentationRegistry.getArguments().getString("homePcmExpectedTargetApkSha256")
        check(expectedApkSha != null && expectedApkSha.matches(Regex("[0-9a-f]{64}")))
        check(expectedApkSha == "df66df2f1c75ada1102e13791b4dfa2a0f912a0ea3bfd08a26deb0531ba56d2a")
        val actualApkSha = fileSha(File(context.applicationInfo.sourceDir), SystemClock.elapsedRealtime() + 30_000L)
        check(actualApkSha == expectedApkSha) { "The exact normal App57 target APK is required" }
        val cache = context.cacheDir
        check(cache.isDirectory && !Files.isSymbolicLink(cache.toPath()))
        val root = File(cache, "vosk-home-pcm-${SystemClock.elapsedRealtimeNanos()}")
        check(root.mkdir())
        privateMode(root, 0x1c0)
        check(root.canonicalFile.parentFile == cache.canonicalFile)
        var journal: Journal? = null
        var attachment: Attachment? = null
        var primaryFailure: Throwable? = null
        val observationFailure = AtomicReference<Throwable?>(null)
        try {
            val openedJournal = Journal(privateFile(root, "journal.jsonl"))
            journal = openedJournal
            openedJournal.record("PREPARED_WAITING_ACTUAL_GUIDANCE", JSONObject()
                .put("artifact_directory", root.absolutePath).put("pid", Process.myPid()).put("uid", Process.myUid())
                .put("prepare_wait_ms", 600_000).put("observation_window_ms", 90_000)
                .put("native_ui_retirement_wait_ms", 30_000).put("instrumentation_may_replace_pid_activity", true)
                .put("compiled_gateway_origin", BuildConfig.WALKSAFE_GATEWAY_ORIGIN)
                .put("target_apk_sha256", actualApkSha).put("expected_target_apk_sha256", expectedApkSha)
                .put("private_39873_alias_modified", false).put("private_alias_mapping_proved_by_sdk", false)
                .put("first_chunks_may_be_missing", true).put("suppression_getter_called", false)
                .put("native_call_enter_exit_observed", false).put("jni_accept_claimed", false)
                .put("native_pipeline_identity_claimed", false).put("performance_evidence", false))
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("stream", "HOME_PCM artifact=${root.absolutePath} native_call_trace=false\n")
            })
            val prepareDeadline = SystemClock.elapsedRealtime() + 600_000L
            var guidanceMain: MainActivity? = null
            var guidanceProbe: Any? = null
            var pinnedEffect: Any? = null
            var homeTransitionRecorded = false
            while (attachment == null) {
                check(SystemClock.elapsedRealtime() < prepareDeadline) { "Actual GUIDANCE then HOME source was not observed" }
                var guidanceObserved = false
                var homeObserved = false
                var effectPinnedNow = false
                var candidateOwner: Owner? = null
                onMain {
                    val mains = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>()
                    check(mains.size <= 1) { "Multiple resumed Main owners are not supported" }
                    val main = mains.singleOrNull()
                    if (main != null && main.hasWindowFocus() && !main.isFinishing && !main.isDestroyed && initializedWake(main) != null) {
                        val page = (MainActivityControllerTestFixture.field("nativeUiPage").get(main) as Enum<*>).name
                        val end = if (page == "GUIDANCE") MainActivityControllerTestFixture.field("nativeGuidanceCancelButton").get(main) as? Button else null
                        val endRect = Rect()
                        val visibleEnd = end != null && end.text.toString() == "안내 종료" &&
                            end.contentDescription?.toString() == "안내 종료" && end.isShown &&
                            end.isAttachedToWindow && end.isEnabled && end.getGlobalVisibleRect(endRect) && !endRect.isEmpty
                        if (guidanceMain == null && page == "GUIDANCE" && visibleEnd) {
                            guidanceMain = main
                            guidanceProbe = initializedWake(main)?.let { field(it, "foregroundHomeWakeProbe") }
                            guidanceObserved = true
                        } else if (main === guidanceMain && page == "HOME") {
                            homeObserved = true
                            val effect = initializedWake(main)?.let { field(it, "foregroundHomeWakeProbe") }
                            if (pinnedEffect == null && effect != null && effect !== guidanceProbe) {
                                pinnedEffect = effect
                                effectPinnedNow = true
                            }
                            pinnedEffect?.let { pinned ->
                                check(effect === pinned) { "The first observed HOME effect was replaced before attachment" }
                                val probe = captured(pinned, VoskWakePhraseProbe::class.java)
                                synchronized(field(probe, "stateLock")!!) {
                                    check((field(probe, "state") as Enum<*>).name != "TERMINAL") {
                                        "The first observed HOME effect retired before attachment"
                                    }
                                }
                                candidateOwner = currentOwner(main, pinned)
                            }
                        } else if (main === guidanceMain && pinnedEffect != null) {
                            error("The pinned HOME window ended before its source was attached")
                        }
                    }
                }
                if (guidanceObserved) openedJournal.record("GUIDANCE_OBSERVED_READY_FOR_NATIVE_VISIBLE_END",
                    JSONObject().put("focused_page", "GUIDANCE").put("actual_end_visible", true).put("sdk_ui_actions", 0))
                if (effectPinnedNow) openedJournal.record("FIRST_OBSERVED_HOME_EFFECT_PINNED", JSONObject()
                    .put("first_actual_effect_creation_proved", false).put("poll_interval_ms", 100))
                if (homeObserved && !homeTransitionRecorded) {
                    homeTransitionRecorded = true
                    openedJournal.record("ACTUAL_HOME_AFTER_GUIDANCE", JSONObject().put("same_activity", true)
                        .put("sdk_end_action", false))
                }
                candidateOwner?.let { owner ->
                    val prepared = prepareAttachment(owner, root, openedJournal, observationFailure)
                    attachment = prepared // 설치 실패도 이 source가 종료된 뒤 정리한다.
                    onMain { install(prepared) }
                    openedJournal.record("SOURCE_ATTACHED", prepared.identityFacts()
                        .put("first_chunks_may_be_missing", true).put("wrapper_execution_proof_pending", true))
                }
                if (attachment == null) Thread.sleep(100L)
            }
            val observed = checkNotNull(attachment)
            val windowDeadline = SystemClock.elapsedRealtime() + 90_000L
            while (SystemClock.elapsedRealtime() < windowDeadline && !observed.retired()) {
                observationFailure.get()?.let { throw AssertionError("HOME callback observation failed", it) }
                onMain {
                    val currentEffect = field(observed.owner.wake, "foregroundHomeWakeProbe")
                    check(currentEffect == null || currentEffect === observed.owner.effect) {
                        "The pinned HOME effect was replaced during the observation window"
                    }
                }
                Thread.sleep(100L)
            }
            openedJournal.record("OBSERVATION_WINDOW_ENDED", observed.identityFacts()
                .put("source_already_retired", observed.retired()).put("sdk_source_stop_called", false))
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = failure else if (first !== failure) first.addSuppressed(failure)
                }
            }
            attachment?.recording?.set(false)
            var drained = attachment == null
            cleanup {
                onMain { Unit } // main의 owner 조회와 observer 설치 작업의 바리어.
                val selected = attachment
                if (selected != null) {
                    journal?.record("WAITING_NATIVE_UI_SOURCE_RETIREMENT", selected.identityFacts()
                        .put("sdk_source_stop_called", false))
                    val deadline = SystemClock.elapsedRealtime() + 30_000L
                    while (!selected.retired()) {
                        check(SystemClock.elapsedRealtime() < deadline) { "Actual HOME source remains active; artifacts remain open" }
                        Thread.sleep(10L)
                    }
                    val released = CountDownLatch(1)
                    VoskStreamingWorker.process.execute { released.countDown() }
                    check(released.await(10_000L, TimeUnit.MILLISECONDS)) { "Original Vosk release barrier did not complete" }
                }
                drained = true
            }
            if (drained) {
                attachment?.let { selected -> cleanup {
                    try {
                        if (selected.callbackField.get(selected.owner.source) === selected.wrapper) {
                            selected.callbackField.set(selected.owner.source, selected.original)
                        } else observationFailure.compareAndSet(null, IllegalStateException("Foreign callback owner was preserved"))
                    } finally { selected.finish() }
                    check(selected.observedCallbacks.get() > 0 && selected.callbacks.get() == selected.consumerCalls.get()) {
                        "Actual observer execution and one original consumer per callback were not proved"
                    }
                } }
                cleanup { journal?.record("FINISHED", JSONObject().put("capture_drained", true)
                    .put("observation_failure_class", observationFailure.get()?.javaClass?.simpleName ?: "NONE")
                    .put("primary_failure_class", primaryFailure?.javaClass?.simpleName ?: "NONE")
                    .put("native_call_enter_exit_observed", false).put("jni_accept_claimed", false)) }
                cleanup { journal?.close() }
            } else cleanup { journal?.record("CLEANUP_INCOMPLETE", JSONObject().put("artifacts_left_open", true)
                .put("capture_drained", false).put("native_call_enter_exit_observed", false)) }
            observationFailure.get()?.let { failure -> cleanup { throw AssertionError("HOME PCM observation is not verified", failure) } }
            cleanupFailure?.let { failure ->
                val original = primaryFailure
                if (original == null) throw failure
                if (original !== failure) original.addSuppressed(failure)
            }
        }
    }

    private data class Owner(val main: MainActivity, val wake: Any, val effect: Any, val probe: VoskWakePhraseProbe,
        val transcriber: VoskStreamingTranscriber, val source: AndroidPcmAudioSource,
        val recognizer: Recognizer, val run: Long, val inputEpoch: AtomicLong, val captureThread: Thread)

    private class Attachment(val owner: Owner, val callbackField: Field, val original: (ShortArray) -> Unit,
        val raw: FileOutputStream, val journal: Journal, val observationFailure: AtomicReference<Throwable?>) {
        lateinit var wrapper: (ShortArray) -> Unit
        val recording = AtomicBoolean(true)
        val callbacks = AtomicInteger(0)
        val observedCallbacks = AtomicInteger(0)
        val consumerCalls = AtomicInteger(0)
        private val inFlight = AtomicInteger(0)
        private val digest = MessageDigest.getInstance("SHA-256")
        private var samplesTotal = 0L
        fun identityFacts(): JSONObject = JSONObject().put("source_number", 1).put("bound_run", owner.run)
            .put("bound_java_recognizer_id", journal.javaIdentity(owner.recognizer)).put("pid", Process.myPid())
            .put("jni_accept_claimed", false).put("native_call_enter_exit_observed", false)
        fun consume(samples: ShortArray) {
            inFlight.incrementAndGet()
            val ordinal = callbacks.incrementAndGet()
            val observeChunk = recording.get()
            if (observeChunk) observedCallbacks.incrementAndGet()
            var consumerFailure: Throwable? = null
            try {
                if (observeChunk) observe {
                    check(Thread.currentThread() === owner.captureThread)
                    val before = snapshot()
                    val bytes = pcm16(samples)
                    var zeros = 0
                    var squares = 0.0
                    var peak = 0
                    for (sample in samples) {
                        if (sample == 0.toShort()) zeros++
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
                        journal.record("CALLBACK_BEFORE", before.put("callback", ordinal).put("samples", samples.size)
                            .put("s16le_sha256", sha(bytes)).put("zero_ratio", zeros.toDouble() / samples.size)
                            .put("rms", sqrt(squares / samples.size)).put("peak", peak))
                    }
                }
                consumerCalls.incrementAndGet()
                original(samples) // 같은 배열과 capture thread에서 원 consumer를 한 번만 호출한다.
            } catch (failure: Throwable) {
                consumerFailure = failure
                throw failure
            } finally {
                try { if (observeChunk) observe {
                    journal.record("CALLBACK_AFTER", snapshot().put("callback", ordinal)
                        .put("consumer_returned", consumerFailure == null)
                        .put("consumer_failure_class", consumerFailure?.javaClass?.simpleName ?: "NONE"))
                } } finally { inFlight.decrementAndGet() }
            }
        }
        private fun observe(action: () -> Unit) {
            try { action() } catch (failure: Throwable) { observationFailure.compareAndSet(null, failure) }
        }
        private fun snapshot(): JSONObject {
            check(Thread.currentThread() === owner.captureThread)
            var currentRecognizer: Recognizer? = null
            var currentHandle = 0L
            var boundHandle = 0L
            val facts = synchronized(field(owner.transcriber, "decoderLock")!!) {
                synchronized(field(owner.transcriber, "stateLock")!!) {
                    currentRecognizer = field(owner.transcriber, "recognizer") as? Recognizer
                    currentHandle = currentRecognizer?.let { Pointer.nativeValue(it.pointer) } ?: 0L
                    boundHandle = Pointer.nativeValue(owner.recognizer.pointer)
                    JSONObject().put("transcriber_run", field(owner.transcriber, "generation"))
                        .put("transcriber_state", (field(owner.transcriber, "state") as Enum<*>).name)
                        .put("same_current_source", field(owner.transcriber, "audioSource") === owner.source)
                        .put("same_current_java_recognizer", currentRecognizer === owner.recognizer)
                        .put("stored_decoder_suppressed", field(owner.transcriber, "decoderSuppressed"))
                        .put("stored_decoder_input_generation", field(owner.transcriber, "decoderInputGeneration") ?: JSONObject.NULL)
                }
            }
            // 주소는 출력/저장하지 않는다. 순번은 생존/내부 pipeline 동일성 증거가 아니다.
            return facts.put("source_number", 1).put("bound_run", owner.run)
                .put("bound_java_recognizer_id", journal.javaIdentity(owner.recognizer))
                .put("current_java_recognizer_id", currentRecognizer?.let { journal.javaIdentity(it) } ?: JSONObject.NULL)
                .put("bound_native_handle_id", journal.handleIdentity(boundHandle))
                .put("current_native_handle_id", journal.handleIdentity(currentHandle))
                .put("input_epoch_observed", owner.inputEpoch.get()).put("actual_process_pcm_input_argument_observed", false)
                .put("wake_generation_field_observed", field(owner.wake, "foregroundHomeWakeGeneration"))
                .put("pid", Process.myPid()).put("native_tid", Process.myTid()).put("java_thread_id", Thread.currentThread().id)
                .put("suppression_getter_called", false).put("native_alive_claimed", false)
                .put("native_pipeline_identity_claimed", false).put("native_call_enter_exit_observed", false)
                .put("jni_accept_claimed", false)
        }
        fun retired(): Boolean {
            val capture = synchronized(field(owner.source, "stateLock")!!) { field(owner.source, "activeSession") != null }
            val current = synchronized(field(owner.transcriber, "stateLock")!!) { field(owner.transcriber, "audioSource") === owner.source }
            return !capture && !current && !owner.captureThread.isAlive && inFlight.get() == 0
        }
        fun finish() = synchronized(journal.lock) {
            raw.use { it.fd.sync() }
            journal.record("SOURCE_FINISHED", identityFacts().put("callbacks", callbacks.get())
                .put("observed_callbacks", observedCallbacks.get()).put("consumer_calls", consumerCalls.get())
                .put("samples", samplesTotal).put("bytes", samplesTotal * 2).put("raw_s16le_sha256", hex(digest.digest())))
        }
    }

    private class Journal(file: File) {
        val lock = Any()
        private val output = FileOutputStream(file)
        private val writer = try { output.bufferedWriter(Charsets.UTF_8) }
            catch (failure: Throwable) { output.close(); throw failure }
        private val javaIdentities = IdentityHashMap<Any, Int>()
        private val handleIdentities = HashMap<Long, Int>()
        fun javaIdentity(value: Any): Int = synchronized(lock) {
            javaIdentities[value] ?: (javaIdentities.size + 1).also { javaIdentities[value] = it }
        }
        fun handleIdentity(value: Long): Any = synchronized(lock) {
            if (value == 0L) JSONObject.NULL else handleIdentities[value] ?: (handleIdentities.size + 1).also { handleIdentities[value] = it }
        }
        fun record(event: String, data: JSONObject = JSONObject()) = synchronized(lock) {
            writer.write(data.put("event", event).put("elapsed_ms", SystemClock.elapsedRealtime())
                .put("wall_time_ms", System.currentTimeMillis()).toString())
            writer.newLine()
            writer.flush()
            output.fd.sync()
        }
        fun close() = synchronized(lock) { output.use { writer.use { it.flush(); output.fd.sync() } } }
    }

    private fun currentOwner(main: MainActivity, pinnedEffect: Any): Owner? {
        val wake = initializedWake(main) ?: return null
        val effect = field(wake, "foregroundHomeWakeProbe") ?: return null
        check(effect === pinnedEffect)
        val probe = captured(effect, VoskWakePhraseProbe::class.java)
        val streamAndRun = synchronized(field(probe, "stateLock")!!) {
            val stream = field(probe, "stream") ?: return@synchronized null
            val run = (field(probe, "activeRunId") as? Number)?.toLong() ?: return@synchronized null
            Pair(stream, run)
        } ?: return null
        check(streamAndRun.first.javaClass.name == "kr.co.hanium.dreamup.walksafe.voice.AndroidVoskWakePhraseStream")
        val transcriber = field(streamAndRun.first, "transcriber") as VoskStreamingTranscriber
        return synchronized(field(transcriber, "stateLock")!!) {
            if ((field(transcriber, "state") as Enum<*>).name != "RUNNING") return@synchronized null
            check((field(transcriber, "generation") as Number).toLong() == streamAndRun.second)
            check(field(transcriber, "homeWakeEndpointing") == true && field(transcriber, "preparedRunFactory") == null)
            val source = field(transcriber, "audioSource") as? AndroidPcmAudioSource ?: return@synchronized null
            val recognizer = field(transcriber, "recognizer") as Recognizer
            check(field(source, "audioRecordFactory") == null)
            val worker = synchronized(field(source, "stateLock")!!) sourceState@ {
                val session = field(source, "activeSession") ?: return@sourceState null
                val record = field(session, "record") as AudioRecord
                check(record.sampleRate == 16_000 && record.channelCount == 1 && record.audioFormat == AudioFormat.ENCODING_PCM_16BIT)
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                field(session, "worker") as Thread
            } ?: return@synchronized null
            check(worker.isAlive && worker.name == "walksafe-pcm-capture")
            Owner(main, wake, effect, probe, transcriber, source, recognizer, streamAndRun.second,
                field(wake, "foregroundHomeWakeInputGeneration") as AtomicLong, worker)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun prepareAttachment(owner: Owner, root: File, journal: Journal, failure: AtomicReference<Throwable?>): Attachment {
        val callbackField = AndroidPcmAudioSource::class.java.getDeclaredField("onPcm16").apply { isAccessible = true }
        val original = callbackField.get(owner.source) as (ShortArray) -> Unit
        check(captured(original, Recognizer::class.java) === owner.recognizer)
        check(captured(original, VoskStreamingTranscriber::class.java) === owner.transcriber)
        val runFields = original.javaClass.declaredFields.filter { it.type == java.lang.Long.TYPE && !Modifier.isStatic(it.modifiers) }
        check(runFields.size == 1 && runFields.single().apply { isAccessible = true }.getLong(original) == owner.run)
        val rawFile = privateFile(root, "source-1.s16le")
        val raw = FileOutputStream(rawFile)
        val prepared = try { Attachment(owner, callbackField, original, raw, journal, failure) }
            catch (cause: Throwable) { raw.close(); throw cause }
        prepared.wrapper = prepared::consume
        try {
            journal.record("SOURCE_ATTACH_PREPARED", prepared.identityFacts().put("raw_file", rawFile.absolutePath)
                .put("raw_format", "S16LE_MONO").put("sample_rate_hz", 16_000).put("first_chunks_may_be_missing", true))
        } catch (cause: Throwable) { raw.close(); throw cause }
        return prepared
    }

    private fun install(attachment: Attachment) {
        val owner = attachment.owner
        check(owner.main.hasWindowFocus() && !owner.main.isFinishing && !owner.main.isDestroyed)
        check((MainActivityControllerTestFixture.field("nativeUiPage").get(owner.main) as Enum<*>).name == "HOME")
        check(field(owner.wake, "foregroundHomeWakeProbe") === owner.effect)
        synchronized(field(owner.probe, "stateLock")!!) {
            check((field(owner.probe, "activeRunId") as? Number)?.toLong() == owner.run)
        }
        synchronized(field(owner.transcriber, "stateLock")!!) {
            check((field(owner.transcriber, "state") as Enum<*>).name == "RUNNING")
            check(field(owner.transcriber, "audioSource") === owner.source && field(owner.transcriber, "recognizer") === owner.recognizer)
            check((field(owner.transcriber, "generation") as Number).toLong() == owner.run)
            check(attachment.callbackField.get(owner.source) === attachment.original)
            attachment.callbackField.set(owner.source, attachment.wrapper)
            check(attachment.callbackField.get(owner.source) === attachment.wrapper)
        }
    }

    private companion object {
        fun initializedWake(main: MainActivity): Any? {
            if (!(field(main, "voiceInputController\$delegate") as Lazy<*>).isInitialized()) return null
            return field(MainActivityControllerTestFixture.voiceInput(main), "wake")
        }
        fun field(receiver: Any, name: String): Any? = receiver.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(receiver)
        fun <T> captured(receiver: Any, type: Class<T>): T {
            val fields = receiver.javaClass.declaredFields.filter { it.type == type && !Modifier.isStatic(it.modifiers) }
            check(fields.size == 1) { "The exact existing delegate capture is unavailable" }
            return type.cast(fields.single().apply { isAccessible = true }.get(receiver))
        }
        fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
        fun privateMode(file: File, mode: Int) {
            Os.chmod(file.absolutePath, mode)
            check((Os.stat(file.absolutePath).st_mode and 0x1ff) == mode)
        }
        fun privateFile(root: File, name: String): File = File(root, name).also {
            check(it.createNewFile())
            privateMode(it, 0x180)
            check(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == root.canonicalFile)
        }
        fun fileSha(file: File, deadline: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { stream ->
                val buffer = ByteArray(65_536)
                while (true) {
                    check(SystemClock.elapsedRealtime() < deadline) { "Target APK SHA verification exceeded its bound" }
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return hex(digest.digest())
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
