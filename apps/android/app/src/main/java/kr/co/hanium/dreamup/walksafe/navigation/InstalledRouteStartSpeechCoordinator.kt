package kr.co.hanium.dreamup.walksafe.navigation

/** Serializes the installed-route announcement without granting route-position or speech admission. */
internal class InstalledRouteStartSpeechCoordinator {
    class PendingStart internal constructor(
        val message: String,
        val isCurrent: () -> Boolean,
    )

    private class PendingState(val start: PendingStart) {
        var submitted = false
    }

    private data class WaitingFailure(val owner: PendingStart, val message: String)

    private var state: PendingState? = null
    private var waitingFailure: WaitingFailure? = null
    val pending: PendingStart? get() = state?.start
    val failureOwner: PendingStart? get() = waitingFailure?.owner

    fun install(message: String, current: () -> Boolean): PendingStart =
        PendingStart(message, current).also { state = PendingState(it) }

    fun clearPending() {
        state = null
    }

    fun clearFailureOwner() {
        waitingFailure = null
    }

    fun clear() {
        clearPending()
        clearFailureOwner()
    }

    fun hold(
        locationReady: () -> Boolean,
        submitSpeech: (String, () -> Boolean, () -> Unit) -> Boolean,
        resumeInstructions: () -> Unit,
        initialJoinPending: Boolean,
        speechBusy: () -> Boolean,
        deferInstructions: () -> Unit,
        routeUpdate: RouteNavigatorUpdate?,
        onStage: (String) -> Unit = {},
    ): Boolean {
        val pendingState = state ?: return false
        val owner = pendingState.start
        if (!owner.isCurrent()) {
            state = null
            return false
        }
        fun ready(): Boolean = owner.isCurrent() && locationReady()
        if (pendingState.submitted) return true
        if (!ready()) {
            // This reference makes no accepted-position claim; ordinary reserve/START guards remain.
            val reference = routeUpdate?.let {
                it.directionOnly && it.speechCueToken?.directionOnly == true &&
                    it.instruction != null && !it.arrived && !it.shouldReroute && !it.userDecisionRequired
            } == true
            if (initialJoinPending || reference) return false
            return true
        }
        if (speechBusy()) {
            deferInstructions()
            return true
        }
        pendingState.submitted = true
        onStage("ROUTE_START_SPEECH_REQUESTED")
        val terminal = {
            if (state === pendingState) {
                state = null
                onStage("ROUTE_START_SPEECH_TERMINAL")
                if (ready()) resumeInstructions()
            }
        }
        val accepted = submitSpeech(owner.message, ::ready, terminal)
        if (!accepted) terminal()
        return true
    }

    /** Main first verifies the preparation owner and its visible failure; only this start inherits it. */
    fun inheritWaitingFailure(message: String): Boolean {
        val owner = pending ?: return false
        if (!owner.isCurrent()) return false
        waitingFailure = WaitingFailure(owner, message)
        return true
    }

    fun clearWaitingFailureIfCurrent(current: () -> Boolean, visibleFailure: String?): Boolean {
        val failure = waitingFailure ?: return false
        if ((pending != null && pending !== failure.owner) || !failure.owner.isCurrent() || !current() ||
            visibleFailure != failure.message) return false
        waitingFailure = null
        return true
    }
}
