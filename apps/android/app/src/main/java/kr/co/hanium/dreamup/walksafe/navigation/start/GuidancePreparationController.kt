package kr.co.hanium.dreamup.walksafe.navigation.start

import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.GuidanceStartSpeechDelivery
import kr.co.hanium.dreamup.walksafe.navigation.InstalledRouteStartSpeechCoordinator
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage

/** Owns preparation polling and its speech leases; the installed-start owner is shared by route peers. */
internal class GuidancePreparationController(
    private val workflow: NativeWorkflowController,
    private val scope: Scope,
    private val speech: Speech,
    private val callbacks: Callbacks,
    private val elapsedRealtimeMs: () -> Long,
    val installedStart: InstalledRouteStartSpeechCoordinator = InstalledRouteStartSpeechCoordinator(),
) {
    class PendingGuidancePreparationSpeech(val startedAtMs: Long, val isCurrent: () -> Boolean) {
        val delivery = GuidanceStartSpeechDelivery()
        var delivered = false
        var exhausted = false
        var responseGeneration: Long? = null
        var failureMessage: String? = null
    }

    var pending: PendingGuidancePreparationSpeech? = null
        private set
    private var poll: Runnable? = null
    private var failureOwner: PendingGuidancePreparationSpeech? = null
    var visibleFailure: String? = null
        private set
    @Volatile var requestedAtMs: Long? = null
        private set

    fun recordRequestedAt() { requestedAtMs = elapsedRealtimeMs() }
    fun clearVisibleFailure() { visibleFailure = null }
    fun reportVisibleFailure(message: String) { visibleFailure = message }
    fun clearPreparationFailureOwner() { failureOwner = null }

    fun recordStage(stage: String, enabled: Boolean, record: (String, Long) -> Unit) {
        if (!enabled) return
        runCatching {
            val now = elapsedRealtimeMs()
            val elapsed = requestedAtMs?.let { now - it } ?: -1L
            record(stage, elapsed)
        }
    }

    fun resetStartFailure() {
        visibleFailure = null
        installedStart.clearFailureOwner()
        failureOwner = null
    }

    fun begin(destination: DestinationSearchResult) {
        cancel()
        val actor = scope.reporterId()
        val gatewayGeneration = scope.gatewayGeneration()
        val epoch = scope.epoch()
        fun current(): Boolean = scope.foreground() && scope.reporterId() == actor &&
            scope.gatewayGeneration() == gatewayGeneration && scope.epoch() == epoch &&
            scope.walkState() !in setOf(WalkSessionState.PAUSED, WalkSessionState.SAFE_STOP, WalkSessionState.ENDED) &&
            workflow.pendingUiDestination === destination && workflow.nativeUiPage == NativeUiPage.GUIDANCE &&
            !scope.hasRoute() && workflow.nativePrewalkStartEpoch?.let { !scope.preflightFailedOrTimedOut() } != false
        if (!current()) return
        val owner = PendingGuidancePreparationSpeech(requestedAtMs ?: elapsedRealtimeMs(), ::current)
        pending = owner
        lateinit var action: Runnable
        action = Runnable {
            if (poll !== action || pending !== owner) return@Runnable
            poll = null
            if (!current()) { cancel(); return@Runnable }
            maybeSpeakWait(elapsedRealtimeMs(), scope.locationWaiting(), speech.busy())
            if (pending === owner && !owner.delivered && !owner.exhausted && current()) {
                poll = action
                callbacks.postDelayed(action, 500L)
            }
        }
        poll = action
        callbacks.postDelayed(action, 4_000L)
    }

    /** Preserve the current policy: acquisition waiting alone does not produce automatic speech. */
    @Suppress("UNUSED_PARAMETER")
    fun maybeSpeakWait(
        nowMs: Long,
        locationWaiting: Boolean,
        speechBusy: Boolean,
        submitSpeech: (String, () -> Boolean, () -> Unit, () -> Unit) -> Boolean = speech::submitWaitingNotice,
        locationStillWaiting: () -> Boolean = scope::locationWaiting,
        publishFailure: () -> Unit = {},
    ) = Unit

    fun cancel() {
        val owner = pending
        pending = null
        poll?.let(callbacks::remove)
        poll = null
        owner?.delivery?.cancel()
        val generation = owner?.responseGeneration
        owner?.responseGeneration = null
        if (generation != null && generation == speech.commandGeneration()) {
            speech.cancelRegisteredResponse(generation)
            speech.cancelCommandInteraction()
        }
    }

    fun inheritPreparationFailure(previous: PendingGuidancePreparationSpeech?) {
        if (previous == null || failureOwner !== previous || previous.failureMessage == null ||
            previous.failureMessage != visibleFailure) return
        if (installedStart.inheritWaitingFailure(previous.failureMessage!!)) failureOwner = null
    }

    fun clearWaitingFailureIfCurrent(current: () -> Boolean, refreshUi: () -> Unit): Boolean {
        if (!installedStart.clearWaitingFailureIfCurrent(current, visibleFailure)) return false
        visibleFailure = null
        refreshUi()
        return true
    }

    interface Scope {
        fun reporterId(): String?
        fun gatewayGeneration(): Long
        fun epoch(): WalkRuntimeEpoch
        fun walkState(): WalkSessionState
        fun foreground(): Boolean
        fun hasRoute(): Boolean
        fun preflightFailedOrTimedOut(): Boolean
        fun locationWaiting(): Boolean
    }

    interface Speech {
        fun busy(): Boolean
        fun commandGeneration(): Long
        fun cancelRegisteredResponse(generation: Long)
        fun cancelCommandInteraction()
        fun submitWaitingNotice(message: String, current: () -> Boolean, completed: () -> Unit, failed: () -> Unit): Boolean
    }

    interface Callbacks {
        fun postDelayed(action: Runnable, delayMs: Long)
        fun remove(action: Runnable)
    }
}
