package kr.co.hanium.dreamup.walksafe

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.tracking.InterFrameDetectionTracker
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

/**
 * Diagnostic composition of the unchanged HOME journey; never a production selection PASS.
 * Snapshots bracket the whole factory and observe returned warmup/live completion only.
 * /proc reads perturb timing; new TIDs have no confirmed TFLite/helper ownership.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityCpuThreadDiagnosticDeviceTest {
    @Test(timeout = 300_000L)
    fun explicitCpuThreadsRunTheUnchangedHomeJourney() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit Main CPU diagnostic opt-in required",
            args.getString("allowMainCpuThreadDiagnostic") == "true")
        assertEquals("true", args.getString("runHomeJourneyFixture"))
        val threads = requireNotNull(args.getString("mainCpuDiagnosticThreads")?.toIntOrNull())
        require(threads in setOf(1, 2)) { "mainCpuDiagnosticThreads must explicitly be 1 or 2" }
        assertTrue("Pre-created injection requires API 29+", Build.VERSION.SDK_INT >= 29)
        assertEquals("Use the unchanged two-core comparison environment", 2, Runtime.getRuntime().availableProcessors())
        assertTrue(BuildConfig.DEBUG)
        assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
        val context = instrumentation.targetContext
        assertEquals(TARGET_PACKAGE, context.packageName)
        val expectedApk = requireNotNull(args.getString("mainCpuDiagnosticExpectedTargetApkSha256"))
        require(expectedApk.matches(Regex("[a-f0-9]{64}")))
        val installedApk = File(context.applicationInfo.sourceDir).inputStream().use(::sha256)
        assertEquals("Actual installed target identity must match the Root-bound APK", expectedApk, installedApk)
        assertTrue("Only the single APK fixture is supported", context.applicationInfo.splitSourceDirs.isNullOrEmpty())
        val baseConfig = TwoModelRuntimeConfig.load(context)
        val model = requireNotNull(baseConfig.unifiedWalksafe)
        assertEquals(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, baseConfig.primaryModelKey)
        assertTrue(model.enabled)
        assertEquals(MODEL_SHA256, model.artifactSha256)
        assertEquals(768, model.inputSize)
        assertArrayEquals(intArrayOf(1, 300, 6), model.outputTensorShape())
        assertEquals(800L, MainActivity::class.java.getDeclaredField("MAX_DEPTH_DETECTION_SOURCE_AGE_MS")
            .apply { isAccessible = true }.getLong(null))
        assertEquals(0.65f, InterFrameDetectionTracker::class.java.getDeclaredField("MATERIAL_POINT_MIN_QUALITY")
            .apply { isAccessible = true }.getFloat(null), 0f)

        val application = context.applicationContext as Application
        // Snapshot only the two runtime preference records; never synthesize or save a profile.
        val fixedPreferences = context.getSharedPreferences(AndroidFixedFixtureRuntimeSelector.PREFERENCES_NAME, 0)
        val profilePreferences = context.getSharedPreferences(AndroidDeviceTuningProfileStore.PREFERENCES_NAME, 0)
        val fixedBefore = fixedPreferences.all.toMap()
        val profileBefore = profilePreferences.all.toMap()
        val seen = AtomicInteger()
        val selections = AtomicInteger()
        val warmups = AtomicInteger()
        val verifications = AtomicInteger()
        val callbackFailure = AtomicReference<Throwable?>()
        var main: MainActivity? = null
        var controller: Any? = null
        var configField: Field? = null
        var platformField: Field? = null
        var originalConfig: PrimaryModelConfiguration? = null
        var originalPlatform: PrimaryLifecyclePlatform? = null
        var injectedConfig: PrimaryModelConfiguration? = null
        var injectedPlatform: PrimaryLifecyclePlatform? = null
        val selector = FixedFixtureRuntimeSelection()
        var diagnosticRecord: String? = null
        val observationFailure = AtomicReference<Throwable?>()
        val observationLock = Any()
        val observations = ArrayList<JSONObject>(4)
        val interpreterOrdinals = IdentityHashMap<Any, Int>()
        var factoryInterpreterOrdinal: Int? = null
        var previousTaskTids: Set<Int>? = null
        var previousPoint: String? = null
        var observationsSealed = false
        val completionLock = Any()
        val completionInstallationAttempted = AtomicBoolean()
        val firstLiveCompletion = AtomicBoolean()
        var completionOwner: Any? = null
        var completionField: Field? = null
        var previousCompletion: PrimaryWorkCompletion? = null
        var observedCompletion: PrimaryWorkCompletion? = null

        fun observationFailed(error: Throwable) {
            observationFailure.compareAndSet(null, error)
        }
        fun recordObservation(point: String, detector: TfliteAndroidFrameDetector?,
            details: JSONObject = JSONObject()) {
            // No new workers, native calls or file writes. Only own-process metadata is read.
            try {
                synchronized(observationLock) {
                    check(!observationsSealed) { "Observation collection already ended" }
                    val startedNs = System.nanoTime()
                    val caller = Thread.currentThread()
                    val callerTid = Process.myTid()
                    val event = JSONObject()
                        .put("point", point)
                        .put("observed_elapsed_realtime_ms", SystemClock.elapsedRealtime())
                        .put("caller_role", if (point == "FIRST_LIVE_COMPLETION") "completion_observer_caller" else "factory_or_warmup_seam_caller")
                        .put("caller_java_thread_id", caller.id)
                        .put("caller_java_thread_name", caller.name)
                        .put("caller_linux_tid", callerTid)
                        .put("caller_linux_nice", Process.getThreadPriority(callerTid))
                        .put("details", details)
                        .put("constructor_boundary_observed", false)
                        .put("invocation_entry_observed", false)
                        .put("tid_owner_mapping", "unconfirmed")
                        .put("priority_inheritance_proven", false)
                        .put("unperturbed_latency_evidence", false)
                    val tasks = JSONArray()
                    val taskTids = linkedSetOf<Int>()
                    var callerTaskRead = false
                    val procStartedNs = System.nanoTime()
                    val directories = requireNotNull(File("/proc/self/task").listFiles()) { "Own task directory unavailable" }
                        .mapNotNull { directory -> directory.name.toIntOrNull()?.let { it to directory } }
                        .sortedBy { it.first }
                    for ((tid, directory) in directories) {
                        taskTids.add(tid) // Compare enumeration, including entries that disappear during reads.
                        val task = JSONObject().put("tid", tid)
                        try {
                            // stat comm may contain spaces/parentheses; fields start after its final ')'.
                            val stat = File(directory, "stat").readText()
                            val close = stat.lastIndexOf(')')
                            check(close > stat.indexOf('('))
                            check(stat.substringBefore(' ').toInt() == tid)
                            val values = stat.substring(close + 1).trim().split(Regex("\\s+"))
                            val nice = values[16].toInt() // field 19; values[0] is field 3 (state).
                            task.put("comm", File(directory, "comm").readText().trimEnd('\n', '\r').take(256))
                                .put("nice", nice)
                            if (tid == callerTid) callerTaskRead = true
                        } catch (error: Exception) {
                            // A task can exit between enumeration and read; missing data is not an owner claim.
                            task.put("read_error_type", error.javaClass.simpleName)
                        }
                        tasks.put(task)
                    }
                    check(callerTaskRead) { "Caller missing/unreadable in own task snapshot" }
                    event.put("tasks", tasks)
                        .put("proc_read_elapsed_ns", System.nanoTime() - procStartedNs)
                        .put("interval_from_point", previousPoint ?: JSONObject.NULL)
                        .put("new_tids_in_observed_interval", JSONArray(
                            previousTaskTids?.let { (taskTids - it).sorted() } ?: emptyList<Int>()))
                        .put("new_tid_meaning", "newly_enumerated_interval_candidate_not_confirmed_creation_or_helper")
                        .put("task_snapshot_atomic", false)
                    val phases = JSONArray()
                    if (detector != null) {
                        val unified = requireNotNull(field(detector, "unifiedDetector").get(detector))
                        val runtime = requireNotNull(field(unified, "interpreterRuntime").get(unified))
                        val handle = requireNotNull(field(runtime, "resource").get(runtime))
                        val interpreter = requireNotNull(field(handle, "interpreter").get(handle))
                        // Identity map ordinals only: never print a pointer, toString or identityHashCode.
                        val ordinal = interpreterOrdinals[interpreter] ?: (interpreterOrdinals.size + 1).also {
                            interpreterOrdinals[interpreter] = it
                        }
                        if (point == "FACTORY_RETURN") factoryInterpreterOrdinal = ordinal
                        event.put("interpreter_ordinal", ordinal)
                            .put("same_as_post_factory_interpreter", factoryInterpreterOrdinal?.let { it == ordinal } ?: JSONObject.NULL)
                        val timing = detector.lifecycleTiming()
                        event.put("load_generation", timing.loadGeneration)
                        for (phase in timing.phases) {
                            phases.put(JSONObject()
                                .put("phase", phase.phase.name)
                                .put("started_ns", phase.startedNs).put("finished_ns", phase.finishedNs)
                                .put("java_thread_id", phase.threadId).put("java_thread_name", phase.threadName)
                                .put("same_java_caller_as_snapshot", phase.threadId == caller.id)
                                .put("outcome", phase.outcome.name)
                                .put("model_key", phase.modelKey ?: JSONObject.NULL)
                                .put("delegate", phase.delegate ?: JSONObject.NULL)
                                .put("failure_type", phase.failureType ?: JSONObject.NULL))
                        }
                    }
                    event.put("existing_phase_timings", phases)
                        .put("observation_elapsed_ns", System.nanoTime() - startedNs)
                    observations.add(event)
                    previousTaskTids = taskTids.toSet()
                    previousPoint = point
                }
            } catch (error: Throwable) {
                // Observation must not skip/replace the original factory, inference or callback.
                observationFailed(error)
            }
        }
        fun restoreCompletion() = synchronized(completionLock) {
            val owner = completionOwner ?: return@synchronized
            val port = requireNotNull(completionField)
            val previous = requireNotNull(previousCompletion)
            val observer = requireNotNull(observedCompletion)
            val current = port.get(owner)
            if (current === observer) port.set(owner, previous)
            else assertSame("Do not overwrite another completion owner", previous, current)
            assertSame("Original completion port must be restored", previous, port.get(owner))
        }
        fun installCompletionObservation(detector: TfliteAndroidFrameDetector) {
            if (!completionInstallationAttempted.compareAndSet(false, true)) return
            try {
                instrumentation.runOnMainSync {
                    synchronized(completionLock) {
                        val actual = requireNotNull(main)
                        val lazyWorker = field(actual, "primaryCameraFallbackDetectorWorker\$delegate").get(actual) as Lazy<*>
                        check(lazyWorker.isInitialized()) { "Original HOME observer must create the worker first" }
                        val worker = requireNotNull(lazyWorker.value) // Never initialize a new worker.
                        val execution = field(worker, "detectorOwner").get(worker) as PrimaryDetectorExecutionOwner
                        assertSame("Observe the already adopted detector", detector, execution.projection().detector)
                        assertFalse("Observation must precede live work", execution.projection().workInFlight)
                        val invocation = requireNotNull(field(worker, "invocationWorker").get(worker))
                        val latest = field(invocation, "latestNativeTiming")
                        assertNull("Do not relabel a later completion as the first", latest.get(invocation))
                        val port = field(worker, "completion")
                        val previous = port.get(worker) as PrimaryWorkCompletion
                        assertNotSame("Keep the original HOME completion observer in the chain",
                            field(actual, "primaryWorkerCompletion").get(actual), previous)
                        val observer = PrimaryWorkCompletion { ticket, invoked, duration, valid ->
                            val collect = invoked && firstLiveCompletion.compareAndSet(false, true)
                            try {
                                if (collect) {
                                    try {
                                        val snapshot = latest.get(invocation) as? PrimaryNativeTimingSnapshot
                                        val sameTicket = snapshot?.pacingTicket === ticket
                                        val detail = JSONObject().put("invoked", invoked)
                                            .put("valid_completion", valid)
                                            .put("reported_duration_ms", duration ?: JSONObject.NULL)
                                            .put("same_native_timing_ticket", sameTicket)
                                        if (sameTicket) {
                                            val matched = requireNotNull(snapshot)
                                            detail.put("native_started_elapsed_ms", matched.nativeStartedAtElapsedRealtimeMs)
                                                .put("native_returned_elapsed_ms", matched.nativeReturnedAtElapsedRealtimeMs)
                                                .put("native_status", matched.status.name)
                                        }
                                        recordObservation("FIRST_LIVE_COMPLETION",
                                            execution.projection().detector as? TfliteAndroidFrameDetector, detail)
                                    } catch (error: Throwable) { observationFailed(error) }
                                }
                                // Every invocation, including uninvoked/failed work, delegates exactly once.
                                previous.finish(ticket, invoked, duration, valid)
                            } finally {
                                if (collect) {
                                    // Restore in this same finally without replacing the original callback exception.
                                    try { restoreCompletion() } catch (error: Throwable) { observationFailed(error) }
                                }
                            }
                        }
                        completionOwner = worker
                        completionField = port
                        previousCompletion = previous
                        observedCompletion = observer
                        port.set(worker, observer)
                        assertSame("Completion observer injection must actually work", observer, port.get(worker))
                    }
                }
            } catch (error: Throwable) { observationFailed(error) }
        }

        fun checkRuntime(result: AndroidDetectionResult) {
            assertTrue("The real interpreter must complete", result.inferenceCompleted)
            assertFalse(result.partial)
            assertEquals(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, result.timing.modelKey)
            val runtime = requireNotNull(result.timing.modelRuntime)
            assertEquals("cpu", runtime.requestedDelegate)
            assertEquals("cpu", runtime.activeDelegate)
            assertEquals(threads, runtime.numThreads)
            assertFalse(runtime.fallbackUsed)
        }
        fun restorePorts() {
            val owner = controller ?: return
            fun restore(field: Field?, injected: Any?, original: Any?) {
                if (field == null || injected == null) return
                val current = field.get(owner)
                if (current === injected) field.set(owner, original)
                else assertSame("Do not overwrite another owner's port", original, current)
                assertSame("Port restoration must be observable", original, field.get(owner))
            }
            // Attempt both restorations even when one fails; preserve the first failure.
            var failure: Throwable? = null
            try { restore(platformField, injectedPlatform, originalPlatform) } catch (error: Throwable) { failure = error }
            try { restore(configField, injectedConfig, originalConfig) } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
            failure?.let { throw it }
        }
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, state: Bundle?) {
                if (activity.javaClass != MainActivity::class.java) return
                try {
                    assertEquals("Exactly one Main launch is supported", 1, seen.incrementAndGet())
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    val actual = activity as MainActivity
                    main = actual
                    val execution = call(actual, "getPrimaryDetectorOwner") as PrimaryDetectorExecutionOwner
                    val initial = execution.projection()
                    assertFalse(initial.available)
                    assertFalse(initial.workInFlight)
                    assertFalse(initial.closing)
                    assertEquals(0L, initial.loadGeneration)
                    assertTrue(initial.detector is NoopAndroidFrameDetector)
                    val lifecycle = call(actual, "getPrimaryDetectorLifecycleController") as PrimaryDetectorLifecycleController<*>
                    controller = lifecycle
                    assertFalse("Injection must precede the first load request", lifecycle.metadata().loadAttempted)
                    assertFalse(lifecycle.metadata().loadInFlight)
                    configField = field(lifecycle, "configuration")
                    platformField = field(lifecycle, "platform")
                    val beforeConfig = configField!!.get(lifecycle) as PrimaryModelConfiguration
                    val beforePlatform = platformField!!.get(lifecycle) as PrimaryLifecyclePlatform
                    originalConfig = beforeConfig
                    originalPlatform = beforePlatform
                    fun selected(config: TwoModelRuntimeConfig?): TwoModelRuntimeConfig? = config?.let {
                        assertEquals("Base asset config must remain the Source56 config", baseConfig, it)
                        val unified = requireNotNull(it.unifiedWalksafe)
                        it.copy(unifiedWalksafe = unified.copy(runtime = unified.runtime.copy(delegate = "cpu", numThreads = threads)))
                    }
                    val configuration = object : PrimaryModelConfiguration by beforeConfig {
                        override fun loadOrCurrent() = selected(beforeConfig.loadOrCurrent())
                        override fun current() = selected(beforeConfig.current())
                        // sha256 still identifies the unchanged asset, not a synthesized config file.
                    }
                    val platform = object : PrimaryLifecyclePlatform by beforePlatform {
                        override fun select(config: TwoModelRuntimeConfig, allowComparison: Boolean,
                            current: () -> Boolean, progress: (String) -> Unit): AndroidDetectorLoadResult {
                            assertEquals(1, selections.incrementAndGet())
                            assertFalse(allowComparison)
                            assertEquals(threads, requireNotNull(config.unifiedWalksafe).runtime.numThreads)
                            assertEquals("cpu", config.unifiedWalksafe!!.runtime.delegate)
                            val binding = FixedFixtureBinding.create(config, listOf(
                                Build.FINGERPRINT, Build.MANUFACTURER, Build.MODEL, Build.HARDWARE,
                                Build.VERSION.SDK_INT.toString(), Build.VERSION.RELEASE, Build.VERSION.INCREMENTAL,
                                android.os.Process.is64Bit().toString(), System.getProperty("os.arch").orEmpty(),
                            ) + Build.SUPPORTED_ABIS, threads)
                            val fixtures = RuntimeCalibrationFixtures(context)
                            val preprocessor = YuvImagePreprocessor()
                            return selector.load(config, binding, threads, false, object : FixedFixtureRuntimeIo {
                                override fun read() = diagnosticRecord
                                override fun write(value: String): Boolean {
                                    diagnosticRecord = value
                                    return false // Memory only; never call this persisted.
                                }
                                override fun manifest() = fixtures.loadManifest()
                                override fun prepare(fixture: RuntimeCalibrationFixture) = preprocessor.preprocessBilinear(
                                    fixtures.loadArgb(fixture), requireNotNull(config.unifiedWalksafe).inputSize)
                                override fun create(options: ModelRuntimeOptions): FixedFixtureDetector {
                                    assertEquals("cpu", options.delegate)
                                    assertEquals(threads, options.numThreads)
                                    recordObservation("FACTORY_ENTER", null)
                                    val load = TfliteAndroidFrameDetector.createWithStatus(context,
                                        config.copy(fallbackModelKey = null, customTactile = null, cocoGeneral = null),
                                        options, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
                                    recordObservation("FACTORY_RETURN", load.detector,
                                        JSONObject().put("detector_available", load.detectorAvailable))
                                    return object : FixedFixtureDetector {
                                        override val result = load
                                        override fun infer(input: PreprocessedImage): AndroidCalibrationDetectionResult {
                                            val output = requireNotNull(load.detector).inferPreparedUnifiedForCalibration(
                                                input, 0L, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
                                            checkRuntime(output.result)
                                            warmups.incrementAndGet()
                                            recordObservation("FIRST_WARMUP_RETURN", load.detector)
                                            return output
                                        }
                                        override fun close() { load.detector?.close() }
                                    }
                                }
                                override fun log(source: String, cache: String, comparison: String, backend: String, ms: Double) {
                                    assertEquals("default", source)
                                    assertEquals("miss", cache)
                                    assertEquals("skipped", comparison)
                                    assertEquals("cpu", backend)
                                    instrumentation.sendStatus(0, Bundle().apply {
                                        putString("stream", "MAIN_CPU_DIAGNOSTIC selected cpu=$threads cache=diagnostic_memory_miss binding=$binding\n")
                                    })
                                }
                            }, current, progress)
                        }
                        override fun loadDefault(): AndroidDetectorLoadResult = error("A missing config is not a valid diagnostic fallback")
                        override fun sample(detector: TfliteAndroidFrameDetector): AndroidDetectionResult {
                            installCompletionObservation(detector)
                            return beforePlatform.sample(detector).also { checkRuntime(it); verifications.incrementAndGet() }
                        }
                    }
                    injectedConfig = configuration
                    injectedPlatform = platform
                    configField!!.set(lifecycle, configuration)
                    platformField!!.set(lifecycle, platform)
                    assertSame("Private final configuration injection must actually work", configuration, configField!!.get(lifecycle))
                    assertSame("Private final platform injection must actually work", platform, platformField!!.get(lifecycle))
                    assertFalse(lifecycle.metadata().loadAttempted)
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "MAIN_CPU_DIAGNOSTIC pre_create ports_readback=true cpu=$threads installed_target_sha256=$installedApk baseline_source56=$SOURCE56_MANIFEST_SHA256\n")
                    })
                } catch (error: Throwable) {
                    callbackFailure.compareAndSet(null, error)
                    try { restorePorts() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                    throw error // Preserve failed injection; never run it as a successful CPU comparison.
                }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        var originalFailure: Throwable? = null
        instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) }
        try {
            // Direct composition retains every original UI/readiness/TTS/route assertion and cleanup.
            // Its annotation is not inherited by a method call, hence this class also keeps 300s.
            MainActivityHomeJourneyAndroidTest().actualHomeStartMustInstallStoredRouteAndReachArrival()
            callbackFailure.get()?.let { throw it }
            assertEquals(1, seen.get())
            assertEquals(1, selections.get())
            assertEquals("Keep the selector's one real warmup", 1, warmups.get())
            assertTrue("The production verification sample must execute", verifications.get() > 0)
            val actual = requireNotNull(main)
            val calibration = call(actual, "getPrimaryRuntimeCalibrationOwner") as PrimaryRuntimeCalibrationOwner<*>
            assertEquals(RuntimeCandidate(RuntimeBackend.CPU, threads), calibration.projection().activeCandidate)
            assertNull("Never synthesize a measured profile", calibration.projection().activeProfile)
            assertNull("Do not inject a measured selection binding", calibration.projection().selectionBinding)
            val invocation = requireNotNull(call(actual, "getPrimaryDetectorInvocationWorker"))
            val live = requireNotNull(field(invocation, "latestNativeTiming").get(invocation) as? PrimaryNativeTimingSnapshot) {
                "No actual Main camera invocation was observed"
            }
            assertEquals(AndroidDetectionStatus.SUCCESS, live.status)
            assertEquals(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, live.timing.modelKey)
            val runtime = requireNotNull(live.timing.modelRuntime)
            assertEquals("cpu", runtime.requestedDelegate)
            assertEquals("cpu", runtime.activeDelegate)
            assertEquals(threads, runtime.numThreads)
            assertFalse(runtime.fallbackUsed)
            assertEquals("Main's base asset/calibration config must remain unchanged", baseConfig,
                MainActivityControllerTestFixture.field("reportRuntimeConfig").get(actual))
            observationFailure.get()?.let { throw it }
            synchronized(observationLock) {
                assertEquals("All four honest observation boundaries must be present",
                    listOf("FACTORY_ENTER", "FACTORY_RETURN", "FIRST_WARMUP_RETURN", "FIRST_LIVE_COMPLETION"),
                    observations.map { it.getString("point") })
                assertNotNull("Actual Interpreter identity must be readable", factoryInterpreterOrdinal)
                for (event in observations.drop(1)) {
                    assertEquals("Record one reused Interpreter without exposing its native pointer",
                        factoryInterpreterOrdinal, event.getInt("interpreter_ordinal"))
                }
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "MAIN_CPU_DIAGNOSTIC journey_assertions_passed=true cpu=$threads actual_main_frame_runtime=true production_selector_pass=false field_safety_pass=false\n")
            })
        } catch (error: Throwable) {
            originalFailure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                instrumentation.runOnMainSync {
                    application.unregisterActivityLifecycleCallbacks(callbacks)
                    var completionCleanup: Throwable? = null
                    try { restoreCompletion() } catch (error: Throwable) { completionCleanup = error }
                    try { restorePorts() } catch (error: Throwable) {
                        if (completionCleanup == null) completionCleanup = error else completionCleanup!!.addSuppressed(error)
                    }
                    completionCleanup?.let { throw it }
                }
            } catch (error: Throwable) { cleanupFailure = error }
            try {
                assertEquals("Product fixed cache must remain unchanged", fixedBefore, fixedPreferences.all)
                assertEquals("Product tuning/profile store must remain unchanged", profileBefore, profilePreferences.all)
            } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
            }
            try {
                // One test-owned private write after cleanup; no per-inference file I/O or new thread.
                val name = "main-cpu-thread-observation-${UUID.randomUUID()}.jsonl"
                val recordFile = File(context.filesDir, name)
                check(!recordFile.exists()) { "Never overwrite a prior observation file" }
                synchronized(observationLock) {
                    observationsSealed = true
                    context.openFileOutput(name, android.content.Context.MODE_PRIVATE).bufferedWriter(Charsets.UTF_8).use { output ->
                        output.appendLine(JSONObject()
                            .put("kind", "MAIN_CPU_THREAD_OBSERVATION")
                            .put("installed_target_sha256", installedApk)
                            .put("cpu_threads", threads)
                            .put("snapshot_count", observations.size)
                            .put("observation_failure_type", observationFailure.get()?.javaClass?.simpleName ?: JSONObject.NULL)
                            .put("new_tid_owner_confirmed", false)
                            .put("priority_inheritance_proven", false)
                            .put("unperturbed_latency_evidence", false)
                            .put("original_failure_type", originalFailure?.javaClass?.simpleName ?: JSONObject.NULL)
                            .toString())
                        observations.forEach { output.appendLine(it.toString()) }
                    }
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "MAIN_CPU_THREAD_OBSERVATION private_file=${recordFile.absolutePath} snapshots=${observations.size} owner_mapping=false latency_unperturbed=false\n")
                    })
                }
            } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
            }
            observationFailure.get()?.let { error ->
                if (error !== originalFailure && error !== cleanupFailure) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                }
            }
            cleanupFailure?.let { if (originalFailure != null) originalFailure!!.addSuppressed(it) else throw it }
        }
    }

    private fun field(owner: Any, name: String): Field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun call(owner: Any, name: String): Any? {
        val method = owner.javaClass.declaredMethods.single { it.name == name && it.parameterCount == 0 }
        try { return method.apply { isAccessible = true }.invoke(owner) }
        catch (failure: InvocationTargetException) { throw requireNotNull(failure.cause) }
    }
    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65_536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    companion object {
        private const val TARGET_PACKAGE = "kr.co.hanium.dreamup.walksafe.speechstorefixture"
        private const val SOURCE56_MANIFEST_SHA256 = "30ac16da2644dc1972b8b916a9d7e77a173cc0bdf0f8024d5be1b15c837bda0a"
        private const val MODEL_SHA256 = "41ec417342f6aea7f4735de009e257ffe92071a055647b9a981f451089d3e79c"
    }
}
