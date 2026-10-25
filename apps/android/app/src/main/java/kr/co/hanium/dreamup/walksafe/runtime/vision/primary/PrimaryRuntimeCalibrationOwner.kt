package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import kr.co.hanium.dreamup.walksafe.inference.AndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import kr.co.hanium.dreamup.walksafe.inference.RuntimeTuningProfile

internal data class PrimaryCalibrationObservation<S : Any>(
    val session: S?,
    val preparing: Boolean,
    val generation: Long,
    val activeCandidate: RuntimeCandidate?,
    val activeProfile: RuntimeTuningProfile?,
    val selectionBinding: String?,
    val restoreAttemptedBinding: String?,
    val schedule: Runnable?,
    val detectorCloseAfterCalibration: Boolean,
    val oldDetectorReleasePending: Boolean,
    val afterOldDetectorRelease: (() -> Unit)?,
)

/** Actual primary calibration state and uncertain old-native-release completion; no sensor policy. */
internal class PrimaryRuntimeCalibrationOwner<S : Any>(
    private val detectorOwner: PrimaryDetectorExecutionOwner,
    private val elapsedRealtimeMs: () -> Long,
    private val cancelSession: (S, String) -> Unit,
    private val removeScheduled: (Runnable) -> Unit,
) {
    @Volatile private var session: S? = null
    @Volatile private var preparing = false
    @Volatile private var generation = 0L
    @Volatile private var activeCandidate: RuntimeCandidate? = null
    @Volatile private var activeProfile: RuntimeTuningProfile? = null
    @Volatile private var selectionBinding: String? = null
    @Volatile private var runtimeLoadPendingRecordedBinding: String? = null
    private var restoreAttemptedBinding: String? = null
    private var schedule: Runnable? = null
    private var detectorCloseAfterCalibration = false
    @Volatile private var oldDetectorReleasePending = false
    private var afterOldDetectorRelease: (() -> Unit)? = null

    /** Original separate volatile/plain observations; no new frame/metric lock or clock read. */
    fun projection() = PrimaryCalibrationObservation(session, preparing, generation, activeCandidate,
        activeProfile, selectionBinding, restoreAttemptedBinding, schedule, detectorCloseAfterCalibration,
        oldDetectorReleasePending, afterOldDetectorRelease)

    fun beginPreparation(): Long { generation += 1L; preparing = true; return generation }
    fun preparationFinished() { preparing = false }
    fun preparationContinues() { preparing = true }

    fun flowIsCurrent(expectedGeneration: Long, authority: () -> Boolean): Boolean =
        expectedGeneration == generation && !detectorOwner.projection().closing && authority()

    fun baselineIsCurrent(expectedGeneration: Long, baseline: AndroidFrameDetector, deadlineMs: Long,
        authority: () -> Boolean): Boolean = flowIsCurrent(expectedGeneration, authority) &&
        detectorOwner.projection().detector === baseline && elapsedRealtimeMs() < deadlineMs

    /** Keep the original volatile check/record before the caller submits to the tuning lane. */
    fun recordPendingDegradationIfNeeded(hasPendingReason: Boolean, record: (String) -> Unit) {
        val binding = projection().selectionBinding
        if (hasPendingReason && binding != null && runtimeLoadPendingRecordedBinding != binding) {
            runtimeLoadPendingRecordedBinding = binding
            record(binding)
        }
    }
    fun clearPendingDegradationRecording() { runtimeLoadPendingRecordedBinding = null }

    fun retainSelectionBinding(binding: String) { selectionBinding = binding }
    fun retainActiveCandidate(candidate: RuntimeCandidate?) { activeCandidate = candidate }
    fun retainMeasuredProfile(profile: RuntimeTuningProfile) { activeCandidate = profile.candidate; activeProfile = profile }
    fun retainAlreadyActiveProfile(profile: RuntimeTuningProfile?) { activeProfile = profile }

    fun beginStoredRestorationIfNeeded(manual: Boolean, auxiliaryAbsent: () -> Boolean,
        profile: RuntimeTuningProfile?, candidate: RuntimeCandidate, binding: String): Boolean {
        if (manual || !auxiliaryAbsent() || profile == null || restoreAttemptedBinding == binding ||
            profile.candidate == candidate) return false
        restoreAttemptedBinding = binding
        return true
    }
    fun storedRestorationDidNotAdopt() { restoreAttemptedBinding = null }

    fun installSession(owned: S) { session = owned }
    fun sessionIsOwned(owned: S?): Boolean = session === owned
    fun clearClosedSessionIfOwned(owned: S?) { if (session === owned) session = null }

    /** Preserve remove-before-clear and generation-before-native-cancel, including partial failure. */
    fun clearScheduledPreparation() {
        schedule?.let(removeScheduled)
        schedule = null
    }
    fun cancel(reason: String) {
        clearScheduledPreparation()
        generation += 1L
        session?.let { cancelSession(it, reason) }
    }

    fun deferDetectorCloseIfCalibrationPresent(auxiliaryPresent: () -> Boolean): Boolean {
        if (session == null && !auxiliaryPresent()) return false
        detectorCloseAfterCalibration = true
        return true
    }
    fun consumeDeferredDetectorClose(): Boolean {
        if (!detectorCloseAfterCalibration) return false
        detectorCloseAfterCalibration = false
        return true
    }

    fun retainOldDetectorRelease() { oldDetectorReleasePending = true }
    fun oldDetectorReleaseFailed() { oldDetectorReleasePending = true }
    fun awaitOldDetectorReleaseOrFinish(finish: () -> Unit) {
        if (oldDetectorReleasePending) afterOldDetectorRelease = finish else finish()
    }
    /** Only a real old-native acknowledgement reaches this callback; no failed-close replay. */
    fun oldDetectorReleaseAcknowledged() {
        oldDetectorReleasePending = false
        val finish = afterOldDetectorRelease
        afterOldDetectorRelease = null
        finish?.invoke()
    }
}
