package kr.co.hanium.dreamup.walksafe.runtime.vision.ar

import kr.co.hanium.dreamup.walksafe.device.RuntimeArSessionCleanupOwnership

/** A borrowed observation. Its native references may only be used while the exact lease is current. */
internal data class OwnedArSessionObservation<S : Any, P : Any, Q>(
    val session: S?,
    val provider: P?,
    val purpose: Q,
    val generation: Long,
)

internal data class OwnedArSessionLease<S : Any, P : Any, Q>(
    val session: S,
    val provider: P,
    val purpose: Q,
    val generation: Long,
)

internal data class ArNativeInvalidation<S : Any, P : Any, Q>(
    val observation: OwnedArSessionObservation<S, P, Q>,
    val ownsPurpose: Boolean,
    val closeOwnerRetained: Boolean,
    val sessionToClose: S?,
    val providerToClose: P?,
    val closeAction: (() -> Unit)?,
)

/**
 * One actual AR session/provider slot and its uncertain native close target.
 * The supplied state lock is the existing metric-state transaction lock. Construction does not
 * read a clock, SDK, permission, renderer or native resource. It never owns a camera fallback slot.
 */
internal class RuntimeArSessionOwner<S : Any, P : Any, Q>(
    private val stateLock: Any,
    private val nonePurpose: Q,
    private val closeSession: (S) -> Unit,
    private val closeProvider: (P) -> Unit,
    private val cleanupOwnership: RuntimeArSessionCleanupOwnership = RuntimeArSessionCleanupOwnership.processSingleton,
    private val diagnostic: (String, RuntimeException) -> Unit = { _, _ -> },
) {
    private var session: S? = null
    private var provider: P? = null
    @Volatile private var purpose: Q = nonePurpose
    @Volatile private var generation = 0L
    private val cleanupOwnerToken = Any()
    private var lastCloseFailure: RuntimeException? = null

    fun observe(): OwnedArSessionObservation<S, P, Q> = synchronized(stateLock) {
        OwnedArSessionObservation(session, provider, purpose, generation)
    }

    fun acquireLeaseOrNull(): OwnedArSessionLease<S, P, Q>? = synchronized(stateLock) {
        val capturedSession = session ?: return@synchronized null
        val capturedProvider = provider ?: return@synchronized null
        OwnedArSessionLease(capturedSession, capturedProvider, purpose, generation)
    }

    fun leaseIsCurrent(lease: OwnedArSessionLease<S, P, Q>): Boolean = synchronized(stateLock) {
        session === lease.session && provider === lease.provider &&
            purpose == lease.purpose && generation == lease.generation
    }

    /** The caller may commit its depth gate at the same original transaction boundary. */
    fun publish(newSession: S, newProvider: P, newPurpose: Q, commit: (Long) -> Unit = {}): Long =
        synchronized(stateLock) {
            session = newSession
            provider = newProvider
            purpose = newPurpose
            generation += 1L
            val publishedGeneration = generation
            commit(publishedGeneration)
            publishedGeneration
        }

    /** Retain before detaching; failed retention leaves the active slot and generation untouched. */
    fun detachForStop(closeNativeSession: Boolean, commit: () -> Unit = {}): OwnedArSessionObservation<S, P, Q>? =
        synchronized(stateLock) {
            val captured = observe()
            val target = captured.session
            if (closeNativeSession && target != null && !retainClose { closeSession(target) }) {
                return@synchronized null
            }
            clearActive()
            commit()
            captured
        }

    /**
     * Captures and retains before the caller invalidates its own metric/domain state. That caller
     * keeps the same state lock around capture, its commit and detachInvalidated, as before.
     */
    fun captureInvalidation(ownsPurpose: (Q) -> Boolean): ArNativeInvalidation<S, P, Q> = synchronized(stateLock) {
        val observed = observe()
        val eligible = ownsPurpose(observed.purpose)
        val target = observed.session.takeIf { eligible }
        val action: (() -> Unit)? = target?.let { owned -> { closeSession(owned) } }
        val retained = action == null || retainClose(action)
        ArNativeInvalidation(observed, eligible, retained, target.takeIf { retained },
            observed.provider.takeIf { eligible && retained }, action.takeIf { retained })
    }

    fun detachInvalidated(invalidation: ArNativeInvalidation<S, P, Q>): Boolean = synchronized(stateLock) {
        if (!invalidation.ownsPurpose || !invalidation.closeOwnerRetained) return@synchronized false
        val captured = invalidation.observation
        if (session !== captured.session || provider !== captured.provider ||
            purpose != captured.purpose || generation != captured.generation
        ) return@synchronized false
        clearActive()
        true
    }

    /**
     * The original completed preflight detaches Session before provider.close. A provider exception
     * therefore preserves provider/purpose/generation and is propagated; do not silently turn that
     * established failure into a successful full detach or a second uncertain Session close.
     */
    fun detachCompletedPreflight(): S? = synchronized(stateLock) {
        val capturedSession = session
        session = null
        provider?.let(closeProvider)
        provider = null
        purpose = nonePurpose
        generation += 1L
        capturedSession
    }

    fun cleanupPending(): Boolean = cleanupOwnership.isPending

    fun cleanupFailureOrNull(): RuntimeException? = synchronized(stateLock) { lastCloseFailure }

    fun retainClose(closeAction: () -> Unit): Boolean {
        val retained = cleanupOwnership.retain(cleanupOwnerToken, closeAction)
        if (retained) lastCloseFailure = null
        return retained
    }

    fun closeCandidate(closeAction: () -> Unit): Boolean {
        if (!retainClose(closeAction)) return false
        return closeRetained()
    }

    fun closeRetained(beforeClose: () -> Unit = {}): Boolean {
        val owned = cleanupOwnership.isOwnedBy(cleanupOwnerToken)
        val previousFailure = lastCloseFailure
        val completed = cleanupOwnership.closeRetained(cleanupOwnerToken, beforeClose)
        if (owned) {
            val failure = cleanupOwnership.failureFor(cleanupOwnerToken)
            if (completed) lastCloseFailure = null
            else if (failure != null) {
                lastCloseFailure = failure
                if (previousFailure !== failure) runCatching { diagnostic("ar_session_cleanup_failed", failure) }
            }
        }
        return completed
    }

    fun recordCleanupTailFailure(failure: RuntimeException, event: String) {
        synchronized(stateLock) {
            val first = lastCloseFailure
            if (first == null) lastCloseFailure = failure
            else if (first !== failure) first.addSuppressed(failure)
        }
        runCatching { diagnostic(event, failure) }
    }

    /** A closeAction only identifies that its target was retained; it is not invoked a second time. */
    fun completeInvalidationCleanup(closeAction: (() -> Unit)?, beforeClose: () -> Unit, afterClose: () -> Unit): Boolean {
        var tailFailure: RuntimeException? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (failure: RuntimeException) {
                val first = tailFailure
                if (first == null) tailFailure = failure
                else if (first !== failure) first.addSuppressed(failure)
            }
        }
        val closeCompleted = if (closeAction != null) closeRetained(beforeClose) else {
            attempt(beforeClose)
            !cleanupPending()
        }
        attempt(afterClose)
        tailFailure?.let { recordCleanupTailFailure(it, "ar_invalidation_cleanup_failed") }
        return closeCompleted && tailFailure == null
    }

    private fun clearActive() {
        session = null
        provider = null
        purpose = nonePurpose
        generation += 1L
    }
}
