package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import kr.co.hanium.dreamup.walksafe.CameraRuntimeHandoff
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Joins host drains before capturing one owner-scoped native retirement capability. */
internal object VisionOwnerRetirement {
    fun begin(ownerId: String, participant: CameraRuntimeHandoff.Claim,
        primaryDrained: CompletionStage<Unit>,
        auxiliaryAfterPrimaryDrain: () -> List<CompletionStage<*>>,
        nativeAfterConsumers: () -> VisionRetirementSnapshot?,
        requestExit: (VisionCohortLease) -> Unit,
        onProblem: (Throwable) -> Unit = {},
    ) {
        fun report(error: Throwable, unprovenDrain: Boolean) {
            runCatching { onProblem(error) }
            if (unprovenDrain) participant.fail(error)
        }
        fun finishNative() {
            val snapshot = try { nativeAfterConsumers() } catch (error: Throwable) {
                report(error, true)
                return
            }
            if (snapshot == null) { participant.complete(); return }
            if (snapshot.lease.ownerId != ownerId) {
                report(IllegalStateException("Retirement snapshot belongs to another owner"), true)
                return
            }
            // Capture once. Neither a late callback nor a new Activity can replace this epoch.
            snapshot.actualReleased.whenComplete { receipt, error ->
                if (error != null) report(error, true)
                else if (receipt?.cohortId != snapshot.lease.cohortId)
                    report(IllegalStateException("Retirement receipt belongs to another cohort"), true)
                else participant.complete()
            }
            // A control failure is not a failed physical receipt. A later real ACK still settles
            // a valid drain, while missing ACK keeps the allocation fence pending.
            try { requestExit(snapshot.lease) } catch (error: Throwable) { report(error, false) }
        }
        primaryDrained.whenComplete { _, primaryError ->
            if (primaryError != null) report(primaryError, true)
            val auxiliary = try { auxiliaryAfterPrimaryDrain() } catch (error: Throwable) {
                report(error, true)
                finishNative()
                return@whenComplete
            }
            val allAuxiliary = try {
                CompletableFuture.allOf(*auxiliary.map { it.toCompletableFuture() }.toTypedArray())
            } catch (error: Throwable) {
                report(error, true)
                finishNative()
                return@whenComplete
            }
            allAuxiliary
                .whenComplete { _, auxiliaryError ->
                    if (auxiliaryError != null) report(auxiliaryError, true)
                    // Even an unproven host drain must continue its own native cleanup.
                    finishNative()
                }
        }
    }
}
