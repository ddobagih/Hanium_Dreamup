package kr.co.hanium.dreamup.walksafe.runtime.camera.lifecycle

import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import kr.co.hanium.dreamup.walksafe.ArCoreStartGate
import kr.co.hanium.dreamup.walksafe.resolveArCoreStartGate
import kr.co.hanium.dreamup.walksafe.shouldRecheckArCoreAvailability
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.runtime.camera.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.ar.RuntimeArSessionOwner
import kr.co.hanium.dreamup.walksafe.session.*

/** Resource start/stop transaction, composed with the existing exact native owner. */
internal class RuntimeArLifecycleTransactions<S : Any, P : Any, Q>(
    private val metricStateLock: Any,
    private val arOwner: RuntimeArSessionOwner<S, P, Q>,
    private val runtimePurpose: Q,
    private val admission: RuntimeCameraAdmission,
    private val platform: RuntimeArPlatform<S, P>,
    private val renderer: RuntimeCameraRenderer,
    private val metric: RuntimeCameraMetric,
    private val readiness: RuntimeCameraReadiness,
    private val presentation: RuntimeCameraPresentation,
    private val frames: RuntimeCameraFrameRetirement,
    private val elapsedRealtimeMs: () -> Long,
    private val sourceChanged: () -> Unit = {},
) {
    fun start(availability: ArCoreApk.Availability, runtimeWalkEpoch: WalkRuntimeEpoch,
        userRequestedInstall: () -> Boolean, onInstallRequested: () -> Unit,
        requestFallback: (CameraFallbackStartReason, ArCoreApk.Availability) -> Unit,
        stopIncompatible: () -> Unit) {
        var candidateSession: S? = null
        try {
            when (platform.requestInstall(userRequestedInstall())) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    onInstallRequested()
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            presentation.guidanceStage("ARCORE_CREATE_BEGIN")
            val newSession = platform.createSession().also { candidateSession = it }
            presentation.guidanceStage("ARCORE_CREATE_COMPLETE")
            val provider = platform.createProvider(newSession)
            admission.depthSupported = platform.configureDepth(provider)
            admission.loadDetectorAfterCameraGate()
            if (!admission.detectorAvailable()) {
                arOwner.closeCandidate { platform.closeSession(newSession) }
                candidateSession = null
                metric.invalidate("runtime_detector_start_unavailable")
                presentation.status(
                    "객체 탐지 사용 불가 · 안전 중지",
                    "탐지 모델을 사용할 수 없어 보행 기능을 시작하지 않았습니다.",
                )
                return
            }
            if (!admission.depthSupported) {
                arOwner.closeCandidate { platform.closeSession(newSession) }
                candidateSession = null
                requestFallback(CameraFallbackStartReason.ARCORE_DEPTH_UNSUPPORTED, availability)
                return
            }
            val runtimeStartedAtMs = elapsedRealtimeMs()
            val resumeRenderer = renderer.pauseForClose()
            val runtimeGeneration = try {
                val textureBound = platform.resumeBeforePublish(newSession)
                synchronized(metricStateLock) {
                    arOwner.publish(newSession, provider, runtimePurpose) { generation ->
                        candidateSession = null
                        metric.beginActivation(generation, runtimeStartedAtMs)
                        renderer.textureBound(textureBound)
                    }
                }
            } finally {
                renderer.resumeAfterClose(resumeRenderer)
            }
            sourceChanged()
            // Route/location guidance has its own current permission and positioning gates.
            readiness.startInitialNavigationIfCurrent(runtimeGeneration, runtimeWalkEpoch)
            renderer.syncScreenPolicy()
            presentation.startAction("ARCore Depth 실행 중", false)
            presentation.status(
                status = if (admission.depthSupported) "ARCore 미터 거리 재확인 중" else "ARCore Depth 미지원",
                detail = if (admission.depthSupported) {
                    "길안내를 진행하며, 현재 거리값이 확인되면 거리 출력을 사용합니다."
                } else {
                    "이 기기는 ARCore DepthMode.AUTOMATIC을 지원하지 않아 metric depth가 제한됩니다."
                },
            )
            renderer.postActivationExpiry(
                {
                    val activation = metric.activationEvaluator()
                    if (
                        activation != null &&
                        activation.generation == runtimeGeneration &&
                        arOwner.observe().purpose == runtimePurpose &&
                        arOwner.observe().generation == runtimeGeneration
                    ) {
                        val observedAtMs = elapsedRealtimeMs()
                        val result = activation.expire(observedAtMs)
                        if (result.status == RuntimeMetricPreflightStatus.UNKNOWN) {
                            if (result.reason == RuntimeMetricPreflightReason.TIMEOUT) {
                                metric.retainOrdinaryLoss(runtimeGeneration,
                                    runtimeWalkEpoch, result.completedAtElapsedRealtimeMs ?: observedAtMs,
                                    evaluator = activation)
                            } else {
                                metric.handleLoss(runtimeGeneration)
                            }
                        }
                    }
                },
                RuntimeMetricPreflightPolicy.MAX_DURATION_MS,
            )
            presentation.recordEvent(
                "arcore_session_started",
                mapOf(
                    "depth_supported" to admission.depthSupported,
                    "detector_available" to admission.detectorAvailable(),
                    "loaded_model" to (admission.loadedModel() ?: "none"),
                    "model_fallback_used" to admission.detectorFallbackUsed(),
                ),
            )
        } catch (_: UnavailableDeviceNotCompatibleException) {
            runCatching { candidateSession?.let { candidate -> arOwner.closeCandidate { platform.closeSession(candidate) } } }
            admission.arCoreSupported = false
            stopIncompatible()
            requestFallback(CameraFallbackStartReason.ARCORE_SESSION_INCOMPATIBLE, availability)
        } catch (error: Exception) {
            runCatching { candidateSession?.let { candidate -> arOwner.closeCandidate { platform.closeSession(candidate) } } }
            presentation.status("ARCore 시작 실패", error.message ?: error::class.java.simpleName)
            metric.invalidate("runtime_session_start_failed")
        }
    }

    fun stopDepthSession(closeSession: Boolean = false): Boolean {
        val stoppedResources = synchronized(metricStateLock) {
            val captured = arOwner.detachForStop(closeSession) {
            metric.retireRuntimeEvidence()
            } ?: run {
                metric.revokeOutput()
                return false
            }
            captured.session to captured.provider
        }
        val sessionToStop = stoppedResources.first
        val providerToStop = stoppedResources.second
        val hadSession = sessionToStop != null
        var resumeRenderer = false
        var tailFailure: RuntimeException? = null
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (failure: RuntimeException) {
                val first = tailFailure
                if (first == null) tailFailure = failure
                else if (first !== failure) first.addSuppressed(failure)
            }
        }
        attempt(sourceChanged)
        val prepareClose = {
            providerToStop?.let(platform::closeProvider)
            if (hadSession) resumeRenderer = renderer.pauseForClose()
            runCatching { sessionToStop?.let(platform::pauseSession) }
            Unit
        }
        val closeCompleted = if (closeSession && hadSession) {
            arOwner.closeRetained(prepareClose)
        } else {
            attempt { prepareClose() }
            !closeSession || arOwner.closeRetained()
        }
        renderer.textureBound(false)
        admission.arCoreSupported = false
        admission.depthSupported = false
        presentation.localRouteState("localRoute=tmap:tactile_not_visible")
        presentation.reportStatus("reportCandidate=blocked")
        frames.retireDetectorGeneration()
        attempt { renderer.syncScreenPolicy() }
        attempt { frames.resetVisualTracking() }
        frames.clearDetectionSnapshot()
        attempt { frames.resetPacing() }
        attempt { frames.clearMetadata() }
        attempt {
            presentation.disableMetadataUploadIfInitialized()
        }
        attempt {
            presentation.clearOverlayOnUiIfInitialized()
        }
        attempt {
            if (presentation.actionInitialized()) presentation.startAction("ARCore Depth 시작", true)
        }
        attempt { presentation.updateUploadActionIfInitialized() }
        attempt {
            if (hadSession && presentation.fieldLogInitialized()) {
                presentation.recordEvent("arcore_session_stopped", mapOf("closed" to (closeSession && closeCompleted)))
            }
        }
        attempt { renderer.resumeAfterClose(resumeRenderer) }
        tailFailure?.let { failure ->
            arOwner.recordCleanupTailFailure(failure, "ar_session_cleanup_tail_failed")
        }
        return closeCompleted && tailFailure == null
    }
}
