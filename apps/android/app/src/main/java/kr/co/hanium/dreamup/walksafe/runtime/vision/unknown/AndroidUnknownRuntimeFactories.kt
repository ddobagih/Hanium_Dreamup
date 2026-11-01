package kr.co.hanium.dreamup.walksafe.runtime.vision.unknown

import android.app.Activity
import android.content.Context
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.device.DeviceTuningRecheckReason
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import kr.co.hanium.dreamup.walksafe.inference.RuntimeFeatureScope
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.composite.AndroidJointRuntimeChoiceStore
import kr.co.hanium.dreamup.walksafe.inference.composite.ValidatedJointRuntimeChoice
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.AndroidUnknownRuntimeProfileStore
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeAttachment
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeBinding
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeCalibration
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeProfile

/** Only these native factories see Android Context/Activity; no SDK reads occur in constructors. */
internal class AndroidUnknownRuntimeFactory(
    private val applicationContext: () -> Context,
    private val activity: () -> Activity,
    private val primaryExecutor: Executor,
) : UnknownRuntimeFactory {
    override fun createService(epoch: Long, options: FastSamRuntimeOptions,
        listener: FastSamRuntimeService.Listener<UnknownRuntimeAttachment>): FastSamRuntimeService<UnknownRuntimeAttachment> =
        FastSamRuntimeService(applicationContext(), epoch, options, listener)

    override fun createCalibration(request: UnknownCalibrationRequest): UnknownCalibrationSession {
        val native = UnknownRuntimeCalibration(activity = activity(), binding = request.binding,
            primaryDetector = request.primary, primaryExecutor = primaryExecutor,
            sessionEpoch = request.serviceEpoch, requestDeadlineMs = request.deadlineMs,
            isCurrent = request.isCurrent, speechActive = request.speechActive,
            onSelected = request.onSelected, onClosed = request.onClosed,
            onProductionResult = request.onProductionResult, onCalibrationResult = request.onCalibrationResult,
            onProductionError = request.onProductionError, onProductionDiscard = request.onProductionDiscard)
        return object : UnknownCalibrationSession {
            override val isRunning get() = native.isRunning
            override val isDraining get() = native.isDraining
            override fun start() = native.start()
            override fun cancel(reason: String) = native.cancel(reason)
        }
    }
}

internal class AndroidUnknownRuntimeProfileRepository(private val context: () -> Context) : UnknownRuntimeProfileRepository {
    private val store by lazy { AndroidUnknownRuntimeProfileStore(context()) }
    private val jointStore by lazy { AndroidJointRuntimeChoiceStore(context()) }
    override val requiresJointChoice = true
    override fun loadJoint(config: TwoModelRuntimeConfig, primary: RuntimeCandidate): ValidatedJointRuntimeChoice? =
        jointStore.load(config)?.takeIf { it.candidate.primary == primary }
    override fun invalidateJointChoice(choice: ValidatedJointRuntimeChoice) = jointStore.invalidateIfMatches(choice)
    override fun createBinding(config: TwoModelRuntimeConfig, primary: RuntimeCandidate,
        scope: RuntimeFeatureScope): UnknownRuntimeBinding? = AndroidUnknownRuntimeProfileStore.createBinding(context(), config, primary, scope)
    override fun load(binding: UnknownRuntimeBinding): UnknownRuntimeProfile? = store.load(binding)
    override fun needsRecheck(binding: UnknownRuntimeBinding, manual: Boolean) = store.needsRecheck(binding, manual)
    override fun markPending(binding: UnknownRuntimeBinding, reason: DeviceTuningRecheckReason) = store.markPending(binding, reason)
}
