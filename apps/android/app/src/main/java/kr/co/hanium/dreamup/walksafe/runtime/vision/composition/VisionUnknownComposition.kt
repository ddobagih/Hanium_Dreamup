package kr.co.hanium.dreamup.walksafe.runtime.vision.composition

import android.app.Activity
import android.content.Context
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.PrimaryDetectorExecutionOwner
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.PrimaryRuntimeCalibrationOwner
import kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.*

/** Activity interaction at the existing auxiliary preparation event; no retained UI or lifetime facts. */
internal interface VisionUnknownPreparationInteraction {
    fun cancelHomeWakeListening()
    fun closeDetector()
    fun mayRefreshReadiness(): Boolean
    fun refreshReadiness()
    fun speakPreparationCompleted()
    fun requirePairPreparation(reason: String) {}
}

/** The two actual mutually referring Lazy holders, with SDK and policy queries deferred to their original phase. */
internal class VisionUnknownComposition<S : Any>(
    private val context: () -> Context,
    private val activity: () -> Activity,
    private val primary: () -> PrimaryDetectorExecutionOwner,
    private val calibration: () -> PrimaryRuntimeCalibrationOwner<S>,
    private val frameLock: () -> Any,
    private val clock: () -> UnknownCaptureClock,
    private val authority: () -> UnknownRuntimeServiceAuthority,
    private val captureScope: () -> UnknownCaptureScopeReader,
    private val captureObserver: () -> UnknownCaptureObserver,
    private val onMain: (() -> Unit) -> Unit,
    private val invalidateObservations: () -> Unit,
    private val interaction: VisionUnknownPreparationInteraction,
) {
    val services: Lazy<UnknownRuntimeServiceOwner> = lazy {
        UnknownRuntimeServiceOwner(
            profiles = AndroidUnknownRuntimeProfileRepository(context),
            factory = AndroidUnknownRuntimeFactory(context, activity, primary().nativeLane),
            authority = authority(),
            clock = clock(),
            deviceTuningExecutor = primary().tuningLane,
            mainDispatcher = onMain,
            invalidateObservations = invalidateObservations,
            // Capturing the Lazy, rather than a receiver method reference, keeps the original cycle cold.
            onResult = { capture.value.consumeUnknownObjectResult(it) },
            onDiscard = { token, reason -> capture.value.handleUnknownFrameDiscard(token, reason) },
            onEvent = ::onServiceEvent,
        )
    }
    val capture: Lazy<UnknownCaptureCoordinator> = lazy {
        UnknownCaptureCoordinator(
            frameStateLock = frameLock(),
            detectorExecutor = primary().nativeLane,
            mainDispatcher = onMain,
            scopeReader = captureScope(),
            clock = clock(),
            observer = captureObserver(),
            services = services.value,
            detectionInFlight = { primary().projection().workInFlight },
        )
    }

    private fun onServiceEvent(event: UnknownRuntimeServiceEvent) {
        when (event) {
            is UnknownRuntimeServiceEvent.PairPreparationRequired -> interaction.requirePairPreparation(event.reason)
            UnknownRuntimeServiceEvent.PreparationFinished -> calibration().preparationFinished()
            UnknownRuntimeServiceEvent.CalibrationWillStart -> interaction.cancelHomeWakeListening()
            is UnknownRuntimeServiceEvent.CalibrationClosed -> {
                if (calibration().consumeDeferredDetectorClose()) {
                    interaction.closeDetector()
                } else if (interaction.mayRefreshReadiness()) {
                    interaction.refreshReadiness()
                    if (event.result?.adopted == true) interaction.speakPreparationCompleted()
                    else services.value.reportCaptureStatus("추가 물체 인식 점검 미완료 · 설정에서 다시 확인 가능")
                }
            }
        }
    }
}
