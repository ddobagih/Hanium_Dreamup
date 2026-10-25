package kr.co.hanium.dreamup.walksafe.runtime.metric

import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import kr.co.hanium.dreamup.walksafe.shouldRecheckArCoreAvailability
import kr.co.hanium.dreamup.walksafe.depth.DepthAcquisitionFailureReason
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.runtime.camera.lifecycle.RuntimeArPlatform
import kr.co.hanium.dreamup.walksafe.runtime.vision.ar.RuntimeArSessionOwner
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Preflight SDK/publication orchestration around the actual metric/native/lifecycle owners. */
internal class RuntimeMetricPreflightCoordinator<L, Q, S : Any, P : Any, F>(
    private val stateLock: Any,
    private val metric: () -> RuntimeMetricSessionController<L, Q, S>,
    private val native: () -> RuntimeArSessionOwner<S, P, Q>,
    private val platform: RuntimeArPlatform<S, P>,
    private val probe: RuntimeMetricDepthSupportProbe,
    private val frames: RuntimeMetricPreflightFrameEvidence<F>,
    private val admission: RuntimeMetricPreflightAdmission,
    private val renderer: RuntimeMetricPreflightRenderer,
    private val presentation: RuntimeMetricPreflightPresentation,
    private val loss: RuntimeMetricLossLifecycle,
    private val preflightPurpose: Q,
    private val runtimePurpose: Q,
    private val elapsedRealtimeMs: () -> Long,
    private val debug: Boolean,
    private val diagnostic: (String) -> Unit,
) {
    fun updateOutputGate(frame: F, snapshot: DepthFrameSnapshot, observedAtMs: Long,
        expectedGeneration: Long, expectedEpoch: WalkRuntimeEpoch,
        acquisitionFailure: DepthAcquisitionFailureReason? = null, recognitionCameraTimestampNs: Long? = null,
    ) {
        metric().updateRuntimeMetricFrameEvidence(expectedGeneration, expectedEpoch, acquisitionFailure,
            observeFrameEvidence = { frames.observe(frame, snapshot, observedAtMs, expectedGeneration, recognitionCameraTimestampNs) })
    }

    fun handleRuntimeMetricLoss(expectedArSessionGeneration: Long) {
        if (native().observe().purpose != runtimePurpose ||
            native().observe().generation != expectedArSessionGeneration ||
            admission.metricDistanceAllowed() != true) return
        val availability = runCatching { platform.availability() }.getOrDefault(ArCoreApk.Availability.UNKNOWN_ERROR)
        if (debug) {
            val activationResult = synchronized(stateLock) { metric().activationEvaluator?.result() }
            diagnostic("event=RUNTIME_METRIC_LOST activation=${activationResult?.status?.name ?: "COMPLETE"} " +
                "reason=${activationResult?.reason?.name ?: "FRAME_OUTPUT_LOST"} " +
                "frames=${activationResult?.distinctFrameCount ?: -1} " +
                "passing=${activationResult?.passingFrameCount ?: -1} " +
                "span_ms=${activationResult?.observationSpanMs ?: -1L}")
        }
        loss.stopDepth()
        // The original fallback request owns its safety-stop/recheck notice.
        loss.requestFallback(availability)
    }

    fun startRuntimeMetricPreflight(generation: Long, lifecycleGeneration: Int) {
        if (!metric().isRuntimeMetricPreflightAttemptCurrent(generation, lifecycleGeneration)) return
        val availability = platform.availability()
        if (shouldRecheckArCoreAvailability(availability)) {
            presentation.availabilityPending()
            platform.availabilityAsync { resolved ->
                presentation.onUi {
                    if (metric().isRuntimeMetricPreflightAttemptCurrent(generation, lifecycleGeneration)) {
                        continueRuntimeMetricPreflightStart(resolved, generation, lifecycleGeneration)
                    }
                }
            }
            return
        }
        continueRuntimeMetricPreflightStart(availability, generation, lifecycleGeneration)
    }

    fun continueRuntimeMetricPreflightStart(availability: ArCoreApk.Availability, generation: Long, lifecycleGeneration: Int) {
        if (!metric().isRuntimeMetricPreflightAttemptCurrent(generation, lifecycleGeneration)) return
        var diagnosticReason = availability.name
        val depthSupport = when {
            !admission.cameraPermission() -> {
                diagnosticReason = "CAMERA_PERMISSION_MISSING"
                RuntimeMetricDepthSupport.UNKNOWN
            }
            availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> RuntimeMetricDepthSupport.UNSUPPORTED
            availability != ArCoreApk.Availability.SUPPORTED_INSTALLED -> RuntimeMetricDepthSupport.UNKNOWN
            else -> try {
                if (probe.automaticDepthSupported()) {
                    diagnosticReason = "DEPTH_MODE_AUTOMATIC_SUPPORTED"
                    RuntimeMetricDepthSupport.SUPPORTED
                } else {
                    diagnosticReason = "DEPTH_MODE_AUTOMATIC_UNSUPPORTED"
                    RuntimeMetricDepthSupport.UNSUPPORTED
                }
            } catch (_: UnavailableDeviceNotCompatibleException) {
                diagnosticReason = "ARCORE_DEVICE_NOT_COMPATIBLE"
                RuntimeMetricDepthSupport.UNSUPPORTED
            } catch (error: Exception) {
                diagnosticReason = error.javaClass.simpleName
                RuntimeMetricDepthSupport.UNKNOWN
            }
        }
        metric().finishDeviceMetricDepthSupportCheck(generation, lifecycleGeneration, depthSupport, diagnosticReason)
    }

    fun startRuntimeMetricPreflightSession(preflightSession: S, provider: P, generation: Long, lifecycleGeneration: Int) {
        if (!metric().isRuntimeMetricPreflightAttemptCurrent(generation, lifecycleGeneration)) {
            platform.closeSession(preflightSession)
            return
        }
        val startedAtMs = elapsedRealtimeMs()
        val evaluator = RuntimeMetricPreflightSession(generation, startedAtMs,
            RuntimeMetricDepthSupport.SUPPORTED, admission.profile())
        val resumeRenderer = renderer.pauseForClose()
        try {
            val textureBound = platform.resumeBeforePublish(preflightSession)
            synchronized(stateLock) {
                native().publish(preflightSession, provider, preflightPurpose) { arGeneration ->
                    metric().publishPreflightEvaluator(evaluator, arGeneration)
                    renderer.textureBound(textureBound)
                }
            }
        } finally {
            renderer.resumeAfterClose(resumeRenderer)
        }
        renderer.publishSupported()
        presentation.status("미터 거리 기능 확인 중", "보행 기능과 신고는 시작하지 않고 실제 Depth 프레임만 확인합니다.")
        presentation.refreshUi()
        presentation.postExpiry({ metric().expirePreflightIfCurrent(generation, lifecycleGeneration) }, RuntimeMetricPreflightPolicy.MAX_DURATION_MS)
    }
}
