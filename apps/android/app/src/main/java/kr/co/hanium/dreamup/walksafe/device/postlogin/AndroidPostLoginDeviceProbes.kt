package kr.co.hanium.dreamup.walksafe.device.postlogin

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.AndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.OwnedYuvFrame
import kr.co.hanium.dreamup.walksafe.voice.*

internal enum class PostLoginCameraPipelineFailure {
    PERMISSION_OR_MODEL_UNAVAILABLE, CAMERA_RESOURCE_BUSY, BIND_FAILED,
    NO_VALID_FRAME, DETECTOR_FAILED, COMPOSITE_RUNTIME_UNAVAILABLE, RELEASE_TIMED_OUT, TIMED_OUT,
}

/** Actual pair preparation and worker release. The Controller separately commits the final bundle. */
internal data class PostLoginCompositeCameraCheckResult(
    val success: Boolean,
    val metricDepth: PostLoginMetricDepthState? = null,
    val reason: String = "",
    val releaseConfirmed: Boolean = true,
)

internal enum class PostLoginLocationFixTerminalFailure { REQUEST_FAILED, TIMED_OUT }

/** Native handles stay in their source; the owner receives original measurement values only. */
internal data class PostLoginLocationSample(
    val latitude: Double, val longitude: Double, val accuracyMeters: Float?,
    val observedAtElapsedRealtimeNs: Long, val mock: Boolean,
)

internal interface PostLoginProbeDispatcher {
    fun post(action: Runnable)
    fun schedule(delayMs: Long, action: Runnable): DeviceCheckScheduledTask
}

internal fun interface PostLoginLocationRegistration { fun cancel() }

internal interface PostLoginLocationSource {
    val available: Boolean
    fun noFixCause(): DeviceCheckLocationFixUnavailability
    fun request(
        onLocations: (List<PostLoginLocationSample>) -> Unit,
        onRegistered: () -> Unit,
        onFailure: () -> Unit,
    ): PostLoginLocationRegistration
}

/** This transaction is synchronous. Image and ImageProxy never enter owner state or queues. */
internal interface PostLoginCameraImage {
    val timestampNs: Long
    val width: Int
    val height: Int
    fun hasImage(): Boolean
    fun copyOwnedFrame(): OwnedYuvFrame?
    fun close()
}

internal interface PostLoginCameraResources {
    val hasAnalysis: Boolean
    val hasCamera: Boolean
    fun clearAnalyzer()
    fun unbindAndDestroy()
    fun awaitClosed(onComplete: (Boolean) -> Unit)
}

internal interface PostLoginCameraSource {
    fun requestBind(
        isCurrent: () -> Boolean,
        onPrepared: (PostLoginCameraResources) -> Unit,
        onImage: (PostLoginCameraImage) -> Unit,
        onFailure: () -> Unit,
    )
}

internal interface PostLoginWakeProbe {
    fun start(): Boolean
    fun cancel()
}

internal fun interface PostLoginWakeProbeFactory {
    fun create(modelDirectory: File, onReady: () -> Unit,
        onResult: (VoskWakePhraseProbeResult) -> Unit): PostLoginWakeProbe
}

internal interface PostLoginHapticProbe {
    fun requestTestVibration(): Boolean
    fun close()
}

internal data class PostLoginProbeEnvironment(
    val locationPermission: Boolean,
    val locationServiceEnabled: Boolean,
    val cameraPermission: Boolean,
    val detectorAvailable: Boolean,
    val otherCameraOwnerBusy: Boolean,
)

/** Shared capability/resource and ARCore services retain their own actors and epoch tokens. */
internal interface PostLoginProbeRuntimeSupport {
    val requiresJointRuntimeCheck: Boolean get() = false
    val capabilityProbeStarted: Boolean
    fun restartCapabilityAndResources(binding: PostLoginDeviceCheckBinding, onChanged: () -> Unit)
    fun prepareVoiceModelAndDetector()
    fun prepareRuntimeLease(binding: PostLoginDeviceCheckBinding, onReady: (Boolean) -> Unit) { onReady(true) }
    fun startAutomaticProbes(binding: PostLoginDeviceCheckBinding)
    fun startMetricPreflight(binding: PostLoginDeviceCheckBinding)
    fun startCompositeCameraCheck(binding: PostLoginDeviceCheckBinding,
        onFinished: (PostLoginCompositeCameraCheckResult) -> Unit): Boolean = false
    fun cancelCompositeCameraCheck(reason: String) {}
    fun cancelCalibration(reason: String)
    fun cancelMetricPreflight(reason: String)
    fun closeCapabilityAndResetResources()
    fun mayValidateStoredBinding(): Boolean
    fun startStoredBindingValidation(onChanged: () -> Unit): Boolean
    fun mayStartStoredMetricRefresh(binding: PostLoginDeviceCheckBinding): Boolean
    fun startStoredMetricRefresh(binding: PostLoginDeviceCheckBinding)
}

internal interface PostLoginProbeObserver {
    fun changed()
    fun continueCheck(binding: PostLoginDeviceCheckBinding)
    fun evaluateCheck(binding: PostLoginDeviceCheckBinding)
    fun navigationDiagnostic(message: String)
    fun locationDiagnostic(decision: DeviceCheckLocationFixDecision)
    fun locationTerminal(result: String, reason: String, accuracyBucket: String, ageBucket: String)
    fun cameraSamplingFailure(generation: Long, attempts: Int, passes: Int,
        failure: PostLoginCameraPipelineFailure)
    fun wakeReady()
    fun wakeResult(result: VoskWakePhraseProbeResult)
    fun announceProgress(message: String)
}

/**
 * Owns each functional attempt, timeout and native resource retirement. Construction is inert.
 * Cancellation retires identity before native cleanup, so queued callbacks cannot publish results.
 */
internal class AndroidPostLoginDeviceProbes(
    private val location: PostLoginLocationSource,
    private val camera: PostLoginCameraSource,
    private val wakeFactory: PostLoginWakeProbeFactory,
    private val hapticFactory: () -> PostLoginHapticProbe,
    private val detector: () -> AndroidFrameDetector,
    private val detectorExecutor: Executor,
    private val dispatcher: PostLoginProbeDispatcher,
    private val cameraReleaseDispatcher: PostLoginProbeDispatcher,
    private val elapsedRealtimeMs: () -> Long,
    private val environment: () -> PostLoginProbeEnvironment,
    private val currentBinding: () -> PostLoginDeviceCheckBinding?,
    private val isCurrent: (PostLoginDeviceCheckBinding) -> Boolean,
    private val uiAlive: () -> Boolean,
    private val sharedReleaseBlocked: () -> Boolean,
    private val markSharedReleaseBlocked: () -> Unit,
    private val modelDirectory: () -> File?,
    private val modelPreparationFailed: () -> Boolean,
    private val stopVoiceService: () -> Unit,
    private val support: PostLoginProbeRuntimeSupport,
    private val observer: PostLoginProbeObserver,
) : PostLoginDeviceCheckProbeSuite {
    override val requiresJointRuntimeCheck get() = support.requiresJointRuntimeCheck
    private var cameraSignal = PostLoginDeviceCheckSignal.PENDING
    private var locationSignal = PostLoginDeviceCheckSignal.PENDING
    private var wakeSignal = PostLoginDeviceCheckSignal.PENDING
    private var metricDepth = PostLoginMetricDepthState.PENDING
    override val signals get() = PostLoginDeviceCheckProbeSignals(cameraSignal, locationSignal, wakeSignal, metricDepth,
        compositeRuntimeFailed = cameraFailure == PostLoginCameraPipelineFailure.COMPOSITE_RUNTIME_UNAVAILABLE,
        compositeRuntimeActive = compositeCameraRun?.let { !it.retired && cameraCurrent(it.binding, it.generation) } == true)
    override var metricPreflightStarted = false
        private set
    override val capabilityProbeStarted get() = support.capabilityProbeStarted
    override var storedBindingValidationPending = false
        private set
    private var storedValidationGeneration = 0L

    var locationDecision: DeviceCheckLocationFixDecision? = null
        private set
    var locationTerminalFailure: PostLoginLocationFixTerminalFailure? = null
        private set
    private var locationGeneration = 0L
    private var locationRun: LocationRun? = null
    private var locationStartedBinding: PostLoginDeviceCheckBinding? = null
    private class LocationRun(val binding: PostLoginDeviceCheckBinding, val generation: Long) {
        var registration: PostLoginLocationRegistration? = null
        var timeout: DeviceCheckScheduledTask? = null
    }

    @Volatile private var cameraGeneration = 0L
    @Volatile var cameraActive = false
        private set
    var cameraCleanupPending = false
        private set
    val hasCamera get() = compositeCameraRun != null || cameraResources?.hasCamera == true
    var cameraFailure: PostLoginCameraPipelineFailure? = null
        private set
    private var cameraResources: PostLoginCameraResources? = null
    private var cameraTimeout: DeviceCheckScheduledTask? = null
    private var cameraTerminalClaim: AtomicBoolean? = null
    private var cameraReleaseGeneration = 0L
    private var compositeCameraRun: CompositeCameraRun? = null
    private var unconfirmedCompositeReleaseBinding: PostLoginDeviceCheckBinding? = null
    private class CompositeCameraRun(val binding: PostLoginDeviceCheckBinding, val generation: Long) {
        var handled: Boolean? = null
        var completion: PostLoginCompositeCameraCheckResult? = null
        var completionDispatched = false
        var retired = false
        var releaseAcknowledged = false
    }

    private var wakeProbe: PostLoginWakeProbe? = null
    val wakeActive get() = wakeProbe != null
    private var wakeReadyCue: PostLoginHapticProbe? = null
    private var wakeStart: Runnable? = null
    val wakeStartScheduled get() = wakeStart != null
    private var wakeStartCancellation: DeviceCheckScheduledTask? = null
    var wakeUserStarted = false
        private set
    var hapticSignal = PostLoginDeviceCheckSignal.PENDING
        private set
    private var hapticProbe: PostLoginHapticProbe? = null

    override fun resetForAttempt(binding: PostLoginDeviceCheckBinding) {
        cancelLocation()
        locationStartedBinding = null
        metricDepth = PostLoginMetricDepthState.PENDING
        cameraSignal = PostLoginDeviceCheckSignal.PENDING
        cameraFailure = null
        locationSignal = PostLoginDeviceCheckSignal.PENDING
        locationDecision = null
        locationTerminalFailure = null
        wakeSignal = PostLoginDeviceCheckSignal.PENDING
        wakeUserStarted = false
        hapticSignal = PostLoginDeviceCheckSignal.PENDING
    }

    override fun restartCapabilityAndResources(binding: PostLoginDeviceCheckBinding, onChanged: () -> Unit) {
        metricPreflightStarted = false
        support.restartCapabilityAndResources(binding, onChanged)
    }
    override fun prepareVoiceModelAndDetector() = support.prepareVoiceModelAndDetector()
    override fun prepareRuntimeLease(binding: PostLoginDeviceCheckBinding, onReady: (Boolean) -> Unit) =
        support.prepareRuntimeLease(binding, onReady)
    override fun startAutomaticProbes(binding: PostLoginDeviceCheckBinding) {
        // Controller calls only after permission observation, journal commit and runtime lease.
        if (!isCurrent(binding)) return
        startLocation(binding)
        if (isCurrent(binding)) support.startAutomaticProbes(binding)
    }
    override fun startMetricPreflight(binding: PostLoginDeviceCheckBinding) = support.startMetricPreflight(binding)
    override fun cancelCalibration(reason: String) {
        retireCompositeCamera(reason)
        support.cancelCalibration(reason)
    }
    override fun cancelMetricPreflight(reason: String) {
        support.cancelMetricPreflight(reason)
        metricPreflightStarted = false
    }
    override fun closeCapabilityAndResetResources() = support.closeCapabilityAndResetResources()
    override fun clearStoredBindingValidation() { ++storedValidationGeneration; storedBindingValidationPending = false }
    override fun restoreMetricDepth(state: PostLoginMetricDepthState) { metricDepth = state }
    fun setMetricPreflightStarted(started: Boolean) { metricPreflightStarted = started }
    override fun mayValidateStoredBinding() = support.mayValidateStoredBinding()
    override fun startStoredBindingValidation(onChanged: () -> Unit): Boolean {
        val generation = ++storedValidationGeneration
        storedBindingValidationPending = true
        val started = support.startStoredBindingValidation {
            if (generation == storedValidationGeneration) onChanged()
        }
        if (!started && generation == storedValidationGeneration) storedBindingValidationPending = false
        return started
    }
    override fun mayStartStoredMetricRefresh(binding: PostLoginDeviceCheckBinding) = support.mayStartStoredMetricRefresh(binding)
    override fun startStoredMetricRefresh(binding: PostLoginDeviceCheckBinding) = support.startStoredMetricRefresh(binding)

    fun startLocation(binding: PostLoginDeviceCheckBinding) {
        if (!isCurrent(binding) || locationStartedBinding == binding) return
        // Claim before native request or observer callbacks, including synchronous reentrancy.
        locationStartedBinding = binding
        cancelLocation()
        locationDecision = null
        val env = environment()
        if (!isCurrent(binding) || !location.available || !env.locationPermission || !env.locationServiceEnabled) {
            if (!env.locationServiceEnabled) locationDecision = DeviceCheckLocationFixPolicy.unavailable(
                DeviceCheckLocationFixUnavailability.LOCATION_SERVICE_OFF)
            locationTerminalFailure = PostLoginLocationFixTerminalFailure.REQUEST_FAILED
            locationSignal = PostLoginDeviceCheckSignal.UNAVAILABLE
            recordLocationTerminal(false, locationTerminalFailure)
            observer.continueCheck(binding)
            return
        }
        val run = LocationRun(binding, ++locationGeneration)
        locationRun = run
        locationSignal = PostLoginDeviceCheckSignal.PENDING
        locationDecision = null
        locationTerminalFailure = null
        val registration = try {
            location.request(
                onLocations = { samples ->
                    if (locationCurrent(run)) {
                        val decisions = samples.map { sample ->
                            DeviceCheckLocationFixPolicy.evaluate(DeviceCheckLocationFixObservation(
                                sample.latitude, sample.longitude, sample.accuracyMeters,
                                sample.observedAtElapsedRealtimeNs / NANOS_PER_MILLISECOND,
                                elapsedRealtimeMs(), sample.mock))
                        }
                        decisions.forEach { locationDecision = it; observer.locationDiagnostic(it); observer.changed() }
                        val decision = DeviceCheckLocationFixPolicy.selectBatch(decisions)
                        if (decision != null) locationDecision = decision
                        if (decision?.reason == DeviceCheckLocationFixReason.MOCK_LOCATION || decision?.passed == true)
                            finishLocation(run, decision.passed, null)
                    }
                },
                onRegistered = { if (!locationCurrent(run)) run.registration?.cancel() },
                onFailure = {
                    if (!locationCurrent(run)) run.registration?.cancel()
                    else finishLocation(run, false, PostLoginLocationFixTerminalFailure.REQUEST_FAILED)
                },
            )
        } catch (_: RuntimeException) {
            finishLocation(run, false, PostLoginLocationFixTerminalFailure.REQUEST_FAILED)
            return
        }
        run.registration = registration
        if (!locationCurrent(run)) { registration.cancel(); return }
        run.timeout = dispatcher.schedule(LOCATION_TIMEOUT_MS + LOCATION_CALLBACK_GRACE_MS, Runnable {
            if (locationCurrent(run)) finishLocation(run, false, PostLoginLocationFixTerminalFailure.TIMED_OUT)
        })
    }

    private fun locationCurrent(run: LocationRun) = locationRun === run &&
        run.generation == locationGeneration && isCurrent(run.binding)

    private fun finishLocation(run: LocationRun, passed: Boolean, failure: PostLoginLocationFixTerminalFailure?) {
        if (!locationCurrent(run)) return
        if (!passed && locationDecision == null && failure == PostLoginLocationFixTerminalFailure.TIMED_OUT)
            locationDecision = DeviceCheckLocationFixPolicy.unavailable(location.noFixCause())
        recordLocationTerminal(passed, failure)
        cancelLocation()
        locationTerminalFailure = failure
        locationSignal = if (passed) PostLoginDeviceCheckSignal.READY else PostLoginDeviceCheckSignal.UNAVAILABLE
        observer.continueCheck(run.binding)
        observer.changed()
    }

    private fun recordLocationTerminal(passed: Boolean, failure: PostLoginLocationFixTerminalFailure?) {
        val accuracy = locationDecision?.accuracyMeters
        val accuracyBucket = when {
            accuracy == null || !accuracy.isFinite() -> "unknown"
            accuracy <= DeviceCheckLocationFixPolicy.MAX_ACCURACY_METERS -> "0_15m"
            accuracy <= 25f -> "over_15_to_25m"
            accuracy <= 50f -> "over_25_to_50m"
            accuracy <= 100f -> "over_50_to_100m"
            else -> "over_100m"
        }
        val age = locationDecision?.ageMs
        val ageBucket = when {
            age == null -> "unknown"
            age < 0L -> "future"
            age <= DeviceCheckLocationFixPolicy.MAX_AGE_MS -> "fresh"
            else -> "stale"
        }
        observer.locationTerminal(if (passed) "pass" else "fail",
            locationDecision?.reason?.name ?: failure?.name ?: "UNKNOWN", accuracyBucket, ageBucket)
    }

    fun cancelLocation() {
        ++locationGeneration
        val retired = locationRun
        locationRun = null
        retired?.timeout?.cancel()
        retired?.registration?.cancel()
    }

    fun requestHaptic(binding: PostLoginDeviceCheckBinding) {
        hapticProbe?.close()
        hapticProbe = null
        if (!isCurrent(binding)) return
        val probe = hapticFactory()
        hapticProbe = probe
        hapticSignal = if (probe.requestTestVibration()) PostLoginDeviceCheckSignal.PENDING
            else PostLoginDeviceCheckSignal.UNAVAILABLE
        observer.changed()
    }

    fun confirmHaptic(binding: PostLoginDeviceCheckBinding, felt: Boolean) {
        if (!isCurrent(binding) || hapticSignal != PostLoginDeviceCheckSignal.PENDING) return
        hapticProbe?.close()
        hapticProbe = null
        hapticSignal = if (felt) PostLoginDeviceCheckSignal.READY else PostLoginDeviceCheckSignal.UNAVAILABLE
        observer.continueCheck(binding)
        observer.changed()
    }

    fun beginWakeFromUserAction(binding: PostLoginDeviceCheckBinding) {
        if (!isCurrent(binding) || wakeSignal != PostLoginDeviceCheckSignal.PENDING ||
            wakeProbe != null || wakeStart != null || modelDirectory() == null) return
        wakeUserStarted = true
        observer.changed()
        lateinit var start: Runnable
        start = Runnable {
            if (wakeStart !== start) return@Runnable
            wakeStart = null
            wakeStartCancellation = null
            if (isCurrent(binding)) maybeStartWake(binding)
        }
        wakeStart = start
        wakeStartCancellation = dispatcher.schedule(0L, start)
    }

    fun maybeStartWake(binding: PostLoginDeviceCheckBinding) {
        if (!wakeUserStarted || wakeSignal != PostLoginDeviceCheckSignal.PENDING || wakeProbe != null || !isCurrent(binding)) return
        val directory = modelDirectory()
        if (directory == null) {
            if (modelPreparationFailed()) {
                wakeSignal = PostLoginDeviceCheckSignal.UNAVAILABLE
                observer.evaluateCheck(binding)
                observer.changed()
            }
            return
        }
        stopVoiceService()
        lateinit var probe: PostLoginWakeProbe
        probe = wakeFactory.create(directory,
            onReady = {
                if (wakeProbe === probe && isCurrent(binding)) {
                    observer.wakeReady()
                    wakeReadyCue?.close()
                    wakeReadyCue = hapticFactory().also { it.requestTestVibration() }
                }
            },
            onResult = { result ->
                if (wakeProbe === probe) {
                    wakeProbe = null
                    wakeReadyCue?.close()
                    wakeReadyCue = null
                    if (isCurrent(binding)) {
                        val available = result.availability == VoskWakePhraseProbeAvailability.AVAILABLE
                        observer.wakeResult(result)
                        wakeSignal = if (available) PostLoginDeviceCheckSignal.READY else PostLoginDeviceCheckSignal.UNAVAILABLE
                        if (available) observer.announceProgress("호출어 인식 시험을 통과했습니다.")
                        observer.continueCheck(binding)
                        observer.changed()
                    }
                }
            },
        )
        wakeProbe = probe
        if (!probe.start()) observer.navigationDiagnostic("postLoginDeviceCheck=vosk_probe_start_failed")
        else {
            observer.navigationDiagnostic("postLoginDeviceCheck=vosk_wake_phrase_listening")
            observer.changed()
        }
    }

    fun cancelWake() {
        wakeStartCancellation?.cancel()
        wakeStartCancellation = null
        wakeStart = null
        wakeUserStarted = false
        val retired = wakeProbe
        wakeProbe = null
        retired?.cancel()
        wakeReadyCue?.close()
        wakeReadyCue = null
    }

    override fun cancelFunctionalProbes() {
        cancelLocation()
        cancelWake()
        hapticProbe?.close()
        hapticProbe = null
    }

    override fun startCamera(binding: PostLoginDeviceCheckBinding) {
        if (cameraActive || !isCurrent(binding)) return
        if (cameraCleanupPending) { observer.navigationDiagnostic("postLoginDeviceCheck=camera_cleanup_pending"); return }
        if (sharedReleaseBlocked()) { rejectCamera(binding, PostLoginCameraPipelineFailure.RELEASE_TIMED_OUT); return }
        val env = environment()
        if (!env.cameraPermission || (!requiresJointRuntimeCheck && !env.detectorAvailable)) { rejectCamera(binding, PostLoginCameraPipelineFailure.PERMISSION_OR_MODEL_UNAVAILABLE); return }
        if (!requiresJointRuntimeCheck && env.otherCameraOwnerBusy) { rejectCamera(binding, PostLoginCameraPipelineFailure.CAMERA_RESOURCE_BUSY); return }
        val generation = ++cameraGeneration
        cameraActive = true
        cameraSignal = PostLoginDeviceCheckSignal.PENDING
        cameraFailure = null
        val terminalClaim = AtomicBoolean(false)
        cameraTerminalClaim = terminalClaim
        if (startCompositeCamera(binding, generation)) return
        if (requiresJointRuntimeCheck) {
            finishCamera(binding, generation, false, PostLoginCameraPipelineFailure.COMPOSITE_RUNTIME_UNAVAILABLE)
            return
        }
        cameraTimeout = dispatcher.schedule(CAMERA_TIMEOUT_MS, Runnable {
            if (cameraCurrent(binding, generation) && terminalClaim.compareAndSet(false, true))
                finishCamera(binding, generation, false, PostLoginCameraPipelineFailure.TIMED_OUT)
        })
        val frameClaimed = AtomicBoolean(false)
        val attemptedFrames = AtomicInteger()
        val passedFrames = AtomicInteger()
        camera.requestBind(
            isCurrent = { cameraCurrent(binding, generation) },
            onPrepared = { resources -> if (cameraCurrent(binding, generation)) cameraResources = resources },
            onImage = image@{ image ->
                if (!cameraCurrent(binding, generation) || !frameClaimed.compareAndSet(false, true)) { image.close(); return@image }
                var failure = PostLoginCameraPipelineFailure.NO_VALID_FRAME
                var closed = false
                val succeeded = try {
                    val timestampNs = image.timestampNs
                    val width = image.width
                    val height = image.height
                    val frame = if (image.hasImage()) {
                        failure = PostLoginCameraPipelineFailure.DETECTOR_FAILED
                        image.copyOwnedFrame()
                    } else null
                    if (frame == null) false else {
                        failure = PostLoginCameraPipelineFailure.DETECTOR_FAILED
                        image.close()
                        closed = true
                        val result = detector().detect(frame, timestampNs / NANOS_PER_MILLISECOND)
                        width > 0 && height > 0 && timestampNs > 0L && DeviceCheckDetectorExecutionPolicy.passes(result)
                    }
                } catch (_: RuntimeException) { false }
                finally { if (!closed) image.close() }
                val attempts = attemptedFrames.incrementAndGet()
                val passes = if (succeeded) passedFrames.incrementAndGet() else passedFrames.get()
                val verdict = DeviceCheckCameraSamplingPolicy.evaluate(attempts, passes)
                if (verdict == DeviceCheckCameraSampleVerdict.CONTINUE) frameClaimed.set(false)
                else {
                    val passed = verdict == DeviceCheckCameraSampleVerdict.PASSED
                    if (!passed) observer.cameraSamplingFailure(generation, attempts, passes, failure)
                    if (terminalClaim.compareAndSet(false, true)) dispatcher.post(Runnable {
                        finishCamera(binding, generation, passed, if (passed) null else failure)
                    })
                }
            },
            onFailure = {
                if (terminalClaim.compareAndSet(false, true))
                    finishCamera(binding, generation, false, PostLoginCameraPipelineFailure.BIND_FAILED)
            },
        )
    }

    /** The production support replaces the legacy short CameraX sample with the whole workload. */
    private fun startCompositeCamera(binding: PostLoginDeviceCheckBinding, generation: Long): Boolean {
        val run = CompositeCameraRun(binding, generation)
        compositeCameraRun = run
        val handled = try {
            support.startCompositeCameraCheck(binding) { result ->
                synchronized(run) {
                    if (run.completion == null) run.completion = result
                }
                dispatchCompositeCompletion(run)
            }
        } catch (_: Throwable) {
            // A throwing source may already own native resources. Do not fall through to another
            // camera; cancellation and its eventual completion retain the release barrier.
            synchronized(run) { run.handled = true }
            cameraFailure = PostLoginCameraPipelineFailure.COMPOSITE_RUNTIME_UNAVAILABLE
            retireCompositeCamera("composite_start_failed")
            observer.navigationDiagnostic("postLoginDeviceCheck=composite_camera_start_failed_cleanup_pending")
            true
        }
        synchronized(run) { run.handled = handled }
        if (!handled) {
            run.retired = true
            if (compositeCameraRun === run) compositeCameraRun = null
            return false
        }
        dispatchCompositeCompletion(run)
        return true
    }

    private fun dispatchCompositeCompletion(run: CompositeCameraRun) {
        val result = synchronized(run) {
            if (run.handled != true || run.completionDispatched) return
            val completed = run.completion ?: return
            run.completionDispatched = true
            completed
        }
        dispatcher.post(Runnable {
            if (compositeCameraRun !== run) return@Runnable
            compositeCameraRun = null
            val released = result.releaseConfirmed || synchronized(run) { run.releaseAcknowledged }
            cameraCleanupPending = !released
            unconfirmedCompositeReleaseBinding = if (released) null else run.binding
            val current = !run.retired && cameraCurrent(run.binding, run.generation)
            cameraActive = false
            cameraTerminalClaim?.set(true)
            cameraTerminalClaim = null
            if (current) {
                val success = result.success && result.releaseConfirmed
                cameraFailure = if (success) null else PostLoginCameraPipelineFailure.COMPOSITE_RUNTIME_UNAVAILABLE
                cameraSignal = if (success) PostLoginDeviceCheckSignal.READY else PostLoginDeviceCheckSignal.UNAVAILABLE
                if (success) result.metricDepth?.let { metricDepth = it }
                observer.navigationDiagnostic("postLoginDeviceCheck=composite_camera_${if (success) "ready" else "unavailable"} reason=${result.reason}")
            }
            // A retired completion proves release only. It may let a newer current attempt start,
            // but can never publish READY or metric evidence from the old attempt.
            currentBinding()?.let { binding -> if (isCurrent(binding)) observer.continueCheck(binding) }
            if (uiAlive()) observer.changed()
        })
    }

    /** Late death ACK releases only its original worker fence, never its failed result. */
    fun confirmCompositeRuntimeRelease(binding: PostLoginDeviceCheckBinding) {
        val run = compositeCameraRun
        if (run?.binding == binding) {
            synchronized(run) { run.releaseAcknowledged = true }
            return
        }
        if (unconfirmedCompositeReleaseBinding != binding) return
        unconfirmedCompositeReleaseBinding = null
        cameraCleanupPending = false
        currentBinding()?.let { current -> if (isCurrent(current)) observer.continueCheck(current) }
        if (uiAlive()) observer.changed()
    }

    private fun retireCompositeCamera(reason: String): Boolean {
        val run = compositeCameraRun ?: return false
        if (!run.retired) {
            run.retired = true
            cameraActive = false
            ++cameraGeneration
            cameraTerminalClaim?.set(true)
            cameraTerminalClaim = null
            cameraCleanupPending = true
            try { support.cancelCompositeCameraCheck(reason) }
            catch (_: RuntimeException) {
                markSharedReleaseBlocked()
                observer.navigationDiagnostic("postLoginDeviceCheck=composite_camera_cancel_failed_cleanup_pending")
            }
        }
        return true
    }

    private fun cameraCurrent(binding: PostLoginDeviceCheckBinding, generation: Long) =
        cameraActive && cameraGeneration == generation && isCurrent(binding)

    private fun rejectCamera(binding: PostLoginDeviceCheckBinding, failure: PostLoginCameraPipelineFailure) {
        cameraFailure = failure
        cameraSignal = PostLoginDeviceCheckSignal.UNAVAILABLE
        observer.evaluateCheck(binding)
    }

    private fun finishCamera(binding: PostLoginDeviceCheckBinding, generation: Long, passed: Boolean,
        failure: PostLoginCameraPipelineFailure?) {
        if (!cameraCurrent(binding, generation)) return
        cameraFailure = if (passed) null else failure
        cameraSignal = if (passed) PostLoginDeviceCheckSignal.READY else PostLoginDeviceCheckSignal.UNAVAILABLE
        stopCamera()
        observer.changed()
    }

    override fun stopCamera() {
        if (retireCompositeCamera("camera_stopped")) return
        if (!cameraActive && cameraResources == null) return
        cameraTerminalClaim?.set(true)
        cameraTerminalClaim = null
        cameraActive = false
        ++cameraGeneration
        cameraTimeout?.cancel()
        cameraTimeout = null
        val resources = cameraResources
        cameraResources = null
        resources?.clearAnalyzer()
        val releaseGeneration = ++cameraReleaseGeneration
        val releaseClaim = AtomicBoolean(false)
        val barrierTimedOut = AtomicBoolean(false)
        var deadline: DeviceCheckScheduledTask? = null
        val release = Runnable {
            if (!releaseClaim.compareAndSet(false, true)) return@Runnable
            deadline?.cancel()
            resources?.unbindAndDestroy()
            val complete: (Boolean) -> Unit = { closed ->
                if (releaseGeneration == cameraReleaseGeneration) {
                    cameraCleanupPending = false
                    if (!closed || barrierTimedOut.get()) {
                        markSharedReleaseBlocked()
                        cameraFailure = PostLoginCameraPipelineFailure.RELEASE_TIMED_OUT
                        cameraSignal = PostLoginDeviceCheckSignal.UNAVAILABLE
                    }
                    currentBinding()?.let { binding -> if (isCurrent(binding)) observer.continueCheck(binding) }
                    if (uiAlive()) observer.changed()
                }
            }
            if (resources == null) complete(true) else resources.awaitClosed(complete)
        }
        cameraCleanupPending = resources?.hasAnalysis == true || resources?.hasCamera == true
        if (resources?.hasAnalysis != true) { release.run(); return }
        deadline = cameraReleaseDispatcher.schedule(CAMERA_BARRIER_TIMEOUT_MS, Runnable { barrierTimedOut.set(true); release.run() })
        try { detectorExecutor.execute { dispatcher.post(release) } }
        catch (_: RejectedExecutionException) { barrierTimedOut.set(true); dispatcher.post(release) }
    }

    companion object {
        const val LOCATION_TIMEOUT_MS = 30_000L
        const val LOCATION_CALLBACK_GRACE_MS = 2_000L
        const val CAMERA_TIMEOUT_MS = 15_000L
        const val CAMERA_BARRIER_TIMEOUT_MS = 2_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        fun android(
            context: Context,
            locationClient: () -> FusedLocationProviderClient?,
            detector: () -> AndroidFrameDetector,
            detectorExecutor: Executor,
            mainExecutor: Executor,
            displayRotation: () -> Int,
            dispatcher: PostLoginProbeDispatcher,
            cameraReleaseDispatcher: PostLoginProbeDispatcher,
            awaitCameraClosed: (Camera?, (Boolean) -> Unit) -> Unit,
            elapsedRealtimeMs: () -> Long,
            environment: () -> PostLoginProbeEnvironment,
            currentBinding: () -> PostLoginDeviceCheckBinding?,
            isCurrent: (PostLoginDeviceCheckBinding) -> Boolean,
            uiAlive: () -> Boolean,
            sharedReleaseBlocked: () -> Boolean,
            markSharedReleaseBlocked: () -> Unit,
            modelDirectory: () -> File?,
            modelPreparationFailed: () -> Boolean,
            voiceOutputSuppressed: () -> Boolean,
            stopVoiceService: () -> Unit,
            support: PostLoginProbeRuntimeSupport,
            observer: PostLoginProbeObserver,
        ): AndroidPostLoginDeviceProbes = AndroidPostLoginDeviceProbes(
            AndroidPostLoginLocationSource(context, locationClient, mainExecutor),
            AndroidPostLoginCameraSource(context, displayRotation, detectorExecutor, mainExecutor, awaitCameraClosed),
            PostLoginWakeProbeFactory { directory, ready, result ->
                val probe = VoskWakePhraseProbe(context.applicationContext, directory, onResult = result,
                    isInputSuppressed = voiceOutputSuppressed, onReady = ready,
                    listenUntilWake = false, wakeEndpointing = true)
                object : PostLoginWakeProbe {
                    override fun start() = probe.start()
                    override fun cancel() = probe.cancel()
                }
            },
            {
                val probe = AndroidDeviceCheckHapticProbe(context)
                object : PostLoginHapticProbe {
                    override fun requestTestVibration() = probe.requestTestVibration()
                    override fun close() = probe.close()
                }
            }, detector, detectorExecutor, dispatcher, cameraReleaseDispatcher, elapsedRealtimeMs,
            environment, currentBinding, isCurrent, uiAlive, sharedReleaseBlocked, markSharedReleaseBlocked,
            modelDirectory, modelPreparationFailed, stopVoiceService, support, observer,
        )
    }
}

private class AndroidPostLoginLocationSource(
    context: Context,
    private val client: () -> FusedLocationProviderClient?,
    private val mainExecutor: Executor,
) : PostLoginLocationSource {
    private val context = context.applicationContext
    override val available get() = client() != null
    override fun noFixCause(): DeviceCheckLocationFixUnavailability {
        val manager = context.getSystemService(LocationManager::class.java)
        val enabled = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager?.isLocationEnabled == true
            else manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                manager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        }.getOrDefault(false)
        if (!enabled) return DeviceCheckLocationFixUnavailability.LOCATION_SERVICE_OFF
        val providers = runCatching { manager?.getProviders(true) }.getOrNull()
        return if (providers != null && providers.none { it != LocationManager.PASSIVE_PROVIDER })
            DeviceCheckLocationFixUnavailability.PROVIDER_DISABLED else DeviceCheckLocationFixUnavailability.NO_FIX_RECEIVED
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun request(onLocations: (List<PostLoginLocationSample>) -> Unit,
        onRegistered: () -> Unit, onFailure: () -> Unit): PostLoginLocationRegistration {
        val fused = requireNotNull(client())
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                onLocations(result.locations.map { location -> PostLoginLocationSample(
                    location.latitude, location.longitude, location.accuracy.takeIf { location.hasAccuracy() },
                    location.elapsedRealtimeNanos,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider,
                ) })
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setMaxUpdateAgeMillis(DeviceCheckLocationFixPolicy.MAX_AGE_MS)
            .setMinUpdateIntervalMillis(500L).setWaitForAccurateLocation(false)
            .setDurationMillis(AndroidPostLoginDeviceProbes.LOCATION_TIMEOUT_MS).build()
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnSuccessListener(mainExecutor) { onRegistered() }
            .addOnFailureListener(mainExecutor) { onFailure() }
        return PostLoginLocationRegistration { fused.removeLocationUpdates(callback); Unit }
    }
}

private class PostLoginCameraLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun moveTo(state: Lifecycle.State) { registry.currentState = state }
}

private class AndroidPostLoginCameraResources(
    private val lifecycleOwner: PostLoginCameraLifecycleOwner,
    private val awaitClosed: (Camera?, (Boolean) -> Unit) -> Unit,
) : PostLoginCameraResources {
    var provider: ProcessCameraProvider? = null
    var analysis: ImageAnalysis? = null
    var camera: Camera? = null
    override val hasCamera get() = camera != null
    override val hasAnalysis get() = analysis != null
    override fun clearAnalyzer() { analysis?.clearAnalyzer() }
    override fun unbindAndDestroy() {
        analysis?.let { ownedAnalysis -> runCatching { provider?.unbind(ownedAnalysis) } }
        lifecycleOwner.moveTo(Lifecycle.State.DESTROYED)
    }
    override fun awaitClosed(onComplete: (Boolean) -> Unit) = awaitClosed(camera, onComplete)
}

private class AndroidPostLoginCameraSource(
    private val context: Context,
    private val rotation: () -> Int,
    private val detectorExecutor: Executor,
    private val mainExecutor: Executor,
    private val awaitClosed: (Camera?, (Boolean) -> Unit) -> Unit,
) : PostLoginCameraSource {
    @androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
    override fun requestBind(isCurrent: () -> Boolean,
        onPrepared: (PostLoginCameraResources) -> Unit, onImage: (PostLoginCameraImage) -> Unit,
        onFailure: () -> Unit) {
        val lifecycleOwner = PostLoginCameraLifecycleOwner().also {
            it.moveTo(Lifecycle.State.CREATED)
            it.moveTo(Lifecycle.State.RESUMED)
        }
        val resources = AndroidPostLoginCameraResources(lifecycleOwner, awaitClosed)
        onPrepared(resources)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!isCurrent()) { lifecycleOwner.moveTo(Lifecycle.State.DESTROYED); return@addListener }
            try {
                val provider = future.get()
                val resolution = ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(
                    Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
                val analysis = ImageAnalysis.Builder().setResolutionSelector(resolution).setTargetRotation(rotation())
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setOutputImageRotationEnabled(true).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                resources.provider = provider
                resources.analysis = analysis
                analysis.setAnalyzer(detectorExecutor) { proxy -> onImage(object : PostLoginCameraImage {
                    override val timestampNs get() = proxy.imageInfo.timestamp
                    override val width get() = proxy.image?.width ?: 0
                    override val height get() = proxy.image?.height ?: 0
                    override fun hasImage() = proxy.image != null
                    override fun copyOwnedFrame() = proxy.image?.let { OwnedYuvFrame.copyOf(it, proxy.cropRect) }
                    override fun close() = proxy.close()
                }) }
                resources.camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (_: Exception) { onFailure() }
        }, mainExecutor)
    }
}
