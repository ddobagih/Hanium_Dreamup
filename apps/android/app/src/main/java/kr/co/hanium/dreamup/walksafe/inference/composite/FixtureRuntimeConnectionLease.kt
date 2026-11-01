package kr.co.hanium.dreamup.walksafe.inference.composite

import java.util.concurrent.CancellationException

internal enum class FixtureRuntimeReleaseProof { NONE, NO_NATIVE_START, WORKER_DEATH }
internal enum class FixtureRuntimeEndpointRole { FIRST, RECONNECT, DUPLICATE }

/**
 * SDK-free authority for one bind attempt. Retiring revokes queued START tasks atomically.
 * Before a START permit is issued, no native owner can exist, even if connection callbacks lag.
 * Once a permit is issued, only actual deaths of every observed endpoint release native ownership.
 */
internal class FixtureRuntimeConnectionLease<K : Any> {
    private var bindAttempted = false
    private var bindReturned = false
    private var bindAccepted = false
    private var unbindClaimed = false
    private var unbindAcknowledged = false
    private var unbindFinished = false
    private var retiredState = false
    private var startMayHaveBeenSent = false
    private var first: K? = null
    private var authenticated: K? = null
    private val endpoints = LinkedHashMap<K, Boolean>()

    @Synchronized fun beginBind(): Boolean {
        if (retiredState || bindAttempted) return false
        bindAttempted = true
        return true
    }

    /** False and synchronous exceptions both finish an attempted bind and still require unbind. */
    @Synchronized fun finishBind(accepted: Boolean) {
        check(bindAttempted && !bindReturned)
        bindAccepted = accepted
        bindReturned = true
    }
    fun bind(operation: () -> Boolean, afterReturn: () -> Unit): Boolean {
        if (!beginBind()) throw CancellationException("fixture_bind_retired")
        var accepted = false
        try { accepted = operation(); return accepted }
        finally { finishBind(accepted); afterReturn() }
    }

    /** Claim once after the call returns; cancellation during bind leaves this action deferred. */
    @Synchronized fun claimUnbind(): Boolean {
        if (!retiredState || !bindAttempted || !bindReturned || unbindClaimed) return false
        unbindClaimed = true
        return true
    }
    @Synchronized fun finishUnbind(success: Boolean) {
        check(unbindClaimed && !unbindFinished)
        unbindFinished = true
        unbindAcknowledged = success
    }

    @Synchronized fun endpointConnected(key: K): FixtureRuntimeEndpointRole {
        if (endpoints.containsKey(key)) return FixtureRuntimeEndpointRole.DUPLICATE
        endpoints[key] = false
        if (first == null) { first = key; return FixtureRuntimeEndpointRole.FIRST }
        return FixtureRuntimeEndpointRole.RECONNECT
    }

    @Synchronized fun authenticate(key: K): Boolean {
        if (retiredState || !bindReturned || !bindAccepted || key != first || endpoints[key] != false) return false
        authenticated = key
        return true
    }

    /** Called immediately before Binder.transact(START), never while merely enqueueing an IPC task. */
    @Synchronized fun authorizeStart(key: K): Boolean {
        if (retiredState || startMayHaveBeenSent || !bindReturned || !bindAccepted ||
            key != first || key != authenticated || endpoints[key] != false) return false
        startMayHaveBeenSent = true
        return true
    }
    fun <T> dispatchStart(key: K, eligible: () -> Boolean, operation: () -> T): T {
        if (!eligible() || !authorizeStart(key)) throw CancellationException("fixture_start_retired")
        return operation()
    }

    @Synchronized fun retire() { retiredState = true }
    @Synchronized fun endpointDied(key: K) { if (endpoints.containsKey(key)) endpoints[key] = true }
    val firstEndpoint: K? get() = synchronized(this) { first }
    val bindWasAttempted: Boolean get() = synchronized(this) { bindAttempted }
    val retired: Boolean get() = synchronized(this) { retiredState }
    val acceptsMetrics: Boolean get() = synchronized(this) { !retiredState && startMayHaveBeenSent }
    val needsDeath: Boolean get() = synchronized(this) { startMayHaveBeenSent }
    val releaseProof: FixtureRuntimeReleaseProof get() = synchronized(this) {
        when {
            !retiredState -> FixtureRuntimeReleaseProof.NONE
            !startMayHaveBeenSent -> FixtureRuntimeReleaseProof.NO_NATIVE_START
            unbindAcknowledged && endpoints.isNotEmpty() && endpoints.values.all { it } -> FixtureRuntimeReleaseProof.WORKER_DEATH
            else -> FixtureRuntimeReleaseProof.NONE
        }
    }
}

internal data class FixtureRuntimeStartupResult(val admitted: Boolean, val failure: Throwable? = null)

/** A failed scheduler/executor admission never leaves the process-wide native-allocation lease. */
internal object FixtureRuntimeStartup {
    fun launch(owner: Any, schedule: () -> Unit, execute: () -> Unit,
        abort: () -> Unit): FixtureRuntimeStartupResult {
        if (!FixtureRuntimeSupervisor.acquire(owner)) return FixtureRuntimeStartupResult(false)
        return try {
            schedule(); execute()
            FixtureRuntimeStartupResult(true)
        } catch (failure: Throwable) {
            runCatching(abort)
            FixtureRuntimeSupervisor.release(owner, true)
            FixtureRuntimeStartupResult(true, failure)
        }
    }
}
