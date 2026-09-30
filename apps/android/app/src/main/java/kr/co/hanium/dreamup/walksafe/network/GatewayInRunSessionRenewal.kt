package kr.co.hanium.dreamup.walksafe.network

import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot

/**
 * login-F1/F4 (owner decision 2, Root decision R1, 2026-09-29): a running app keeps a
 * renewable long-lived Gateway login instead of clearing it when its short access credential
 * expires. The renewal uses the same durable reserve -> renew -> commit path as a login
 * restored at start (MainActivity.renewRestoredGatewaySession), single flight per process.
 *
 * This file holds only the decisions so they are testable on the JVM; MainActivity wires them.
 */
internal enum class GatewayInRunRenewalTrigger {
    /** A feature asked for the session (gatewaySessionOrNull / expired-session cleanup). */
    ON_DEMAND,
    /** The timer about [GatewayInRunRenewalPolicy.PROACTIVE_RENEWAL_LEAD_MS] before access expiry. */
    PROACTIVE,
    /** The Gateway rejected the access credential (401/403 or status not authenticated). */
    UNAUTHORIZED,
}

internal enum class GatewayInRunSessionAction {
    /** The access credential is valid: use the session unchanged. */
    USE,
    /** A renewable long-lived login: renew durably, never move it to pending revocation. */
    RENEW,
    /** Not renewable (short/device session, idle or absolute expiry, invalidated): clear as before. */
    CLEAR,
    /** Nothing to do for this trigger (no verified session, or nothing due). */
    IGNORE,
}

/** What a failed renewal does; identical for a restored and an in-run renewal. */
internal sealed interface GatewayRenewalFailureAction {
    /** Another attempt already owns this one-use refresh proof: change nothing. */
    data object DuplicateAttempt : GatewayRenewalFailureAction

    /** A confirmed-unapplied busy answer for this lease: durable rollback, then retry later. */
    data class RetryAfterRollback(
        val reservation: GatewayRefreshRetryReservation,
    ) : GatewayRenewalFailureAction

    /** Everything else ends the login through pending revocation, with the notice if any. */
    data class Abandon(val notice: GatewayRenewalFailureNotice?) : GatewayRenewalFailureAction
}

internal object GatewayInRunRenewalPolicy {
    /** Renew about one minute before the access credential expires. */
    const val PROACTIVE_RENEWAL_LEAD_MS = 60_000L
    /** Lower bound for a scheduled renewal so a short access TTL cannot spin. */
    const val MIN_RENEWAL_DELAY_MS = 1_000L
    /** Retry while offline or after a deferred renewal. */
    const val RENEWAL_RETRY_DELAY_MS = 15_000L
    /** login-F4: at most one refresh probe per login family in this interval (no loops). */
    const val UNAUTHORIZED_PROBE_INTERVAL_MS = 5L * 60_000L

    fun actionFor(
        session: GatewayFieldSession?,
        trigger: GatewayInRunRenewalTrigger,
        nowEpochMs: Long,
    ): GatewayInRunSessionAction {
        if (session == null || session.verificationState != GatewaySessionVerificationState.VERIFIED) {
            return GatewayInRunSessionAction.IGNORE
        }
        val usable = session.isUsableFor(session.actorId, nowEpochMs)
        val renewable = session.sessionScope == GatewaySessionScope.GENERAL &&
            !session.isBackendAccountDeviceBound &&
            session.isRenewableFor(session.actorId, nowEpochMs)
        return when (trigger) {
            GatewayInRunRenewalTrigger.ON_DEMAND -> when {
                usable -> GatewayInRunSessionAction.USE
                renewable -> GatewayInRunSessionAction.RENEW
                else -> GatewayInRunSessionAction.CLEAR
            }
            GatewayInRunRenewalTrigger.PROACTIVE -> when {
                renewable -> GatewayInRunSessionAction.RENEW
                else -> GatewayInRunSessionAction.IGNORE
            }
            GatewayInRunRenewalTrigger.UNAUTHORIZED -> when {
                renewable -> GatewayInRunSessionAction.RENEW
                else -> GatewayInRunSessionAction.CLEAR
            }
        }
    }

    /**
     * Delay until the proactive renewal, or null when the session is not a renewable login.
     * Long access lifetimes renew [PROACTIVE_RENEWAL_LEAD_MS] early; short ones at half the
     * remaining time, never sooner than [MIN_RENEWAL_DELAY_MS] unless already expired.
     */
    fun proactiveDelayMsOrNull(session: GatewayFieldSession?, nowEpochMs: Long): Long? {
        if (actionFor(session, GatewayInRunRenewalTrigger.PROACTIVE, nowEpochMs) !=
            GatewayInRunSessionAction.RENEW
        ) return null
        val remaining = requireNotNull(session).accessExpiresAtEpochMs - nowEpochMs
        if (remaining <= MIN_RENEWAL_DELAY_MS) return 0L
        val delay = if (remaining > 2L * PROACTIVE_RENEWAL_LEAD_MS) {
            remaining - PROACTIVE_RENEWAL_LEAD_MS
        } else {
            remaining / 2L
        }
        return delay.coerceAtLeast(MIN_RENEWAL_DELAY_MS)
    }

    /** Same mapping the restore path used before (proof fence, busy rollback, abandon + notice). */
    fun failureAction(
        error: RuntimeException,
        sourceLease: GatewaySessionLease,
    ): GatewayRenewalFailureAction {
        val http = error as? GatewaySessionHttpException
        if (http?.reason == "gateway_refresh_proof_already_attempted") {
            return GatewayRenewalFailureAction.DuplicateAttempt
        }
        val reservation = http?.refreshRetryReservation
        if (reservation != null && reservation.sourceLease == sourceLease) {
            return GatewayRenewalFailureAction.RetryAfterRollback(reservation)
        }
        return GatewayRenewalFailureAction.Abandon(GatewayRenewalFailureNotice.of(error))
    }

    /**
     * True when [current] is the same login as [previous], renewed in this process: same
     * Gateway, actor, device, family and scope with a later rotation. A walk lease is bound to
     * the actor and device, so such a renewal does not change the walk authority.
     */
    fun isRenewedContinuation(previous: GatewayFieldSession, current: GatewayFieldSession?): Boolean {
        if (current == null || current === previous) return false
        val previousRotation = previous.rotation ?: return false
        val currentRotation = current.rotation ?: return false
        return previous.isLongLived && current.isLongLived &&
            current.gatewayBaseUrl == previous.gatewayBaseUrl &&
            current.actorId == previous.actorId &&
            current.deviceId == previous.deviceId &&
            current.familyId == previous.familyId &&
            current.sessionScope == previous.sessionScope &&
            currentRotation > previousRotation
    }

    /**
     * login-F9 (S13-30-F1 rule of showAccountMessage): true when the POLITE live region of the
     * account status reads [message] itself (screen reader on, status shown and changed to it),
     * so a separate spoken interaction would read the same sentence twice.
     */
    fun liveRegionReadsNotice(
        screenReaderActive: Boolean,
        statusShown: Boolean,
        statusBefore: String?,
        statusAfter: String?,
        message: String,
    ): Boolean = screenReaderActive && statusShown && statusAfter == message && statusBefore != message
}

/**
 * Single flight for the in-run renewal. The first caller owns the renewal and must call
 * [finish]; concurrent callers join and their waiters run once the renewal ends (success or
 * failure). A busy rollback or a missing network defers the next attempt.
 */
internal class GatewayInRunRenewalGate {
    enum class Admission { STARTED, JOINED, DEFERRED }

    private val lock = Any()
    private var inFlight: GatewaySessionLease? = null
    private var notBeforeElapsedMs: Long? = null
    private val waiters = ArrayList<() -> Unit>()

    fun tryBegin(
        sourceLease: GatewaySessionLease,
        nowElapsedMs: Long,
        waiter: (() -> Unit)? = null,
    ): Admission = synchronized(lock) {
        if (inFlight != null) {
            waiter?.let(waiters::add)
            return Admission.JOINED
        }
        val notBefore = notBeforeElapsedMs
        if (notBefore != null && nowElapsedMs < notBefore) return Admission.DEFERRED
        notBeforeElapsedMs = null
        inFlight = sourceLease
        waiter?.let(waiters::add)
        Admission.STARTED
    }

    /** Queues [waiter] for the running renewal; false when none is running. */
    fun joinIfInFlight(waiter: () -> Unit): Boolean = synchronized(lock) {
        if (inFlight == null) return false
        waiters += waiter
        true
    }

    fun isInFlight(): Boolean = synchronized(lock) { inFlight != null }

    fun deferralRemainingMs(nowElapsedMs: Long): Long = synchronized(lock) {
        (notBeforeElapsedMs?.minus(nowElapsedMs) ?: 0L).coerceAtLeast(0L)
    }

    /** Ends the renewal owned by [sourceLease] and returns the waiters to run (outside the lock). */
    fun finish(
        sourceLease: GatewaySessionLease,
        retryNotBeforeElapsedMs: Long? = null,
    ): List<() -> Unit> = synchronized(lock) {
        if (inFlight != sourceLease) return emptyList()
        inFlight = null
        notBeforeElapsedMs = retryNotBeforeElapsedMs
        val ready = waiters.toList()
        waiters.clear()
        ready
    }
}

/** login-F4: bounds the refresh probes after a rejected access credential (no loops). */
internal class GatewayUnauthorizedRenewalProbe(
    private val intervalMs: Long = GatewayInRunRenewalPolicy.UNAUTHORIZED_PROBE_INTERVAL_MS,
) {
    private var lastFamilyId: String? = null
    private var lastAtElapsedMs: Long? = null

    @Synchronized
    fun tryAcquire(familyId: String?, nowElapsedMs: Long): Boolean {
        if (familyId == null) return false
        val last = lastAtElapsedMs
        if (familyId == lastFamilyId && last != null && nowElapsedMs - last < intervalMs) return false
        lastFamilyId = familyId
        lastAtElapsedMs = nowElapsedMs
        return true
    }
}

/** Context of one in-run renewal passed through the shared durable renewal path. */
internal class GatewayInRunRenewalContext(
    /** The published process credential being renewed; kept when a busy answer is rolled back. */
    val sourceSession: GatewayFieldSession,
    /** First-run progress to publish with the renewed credential (the process copy, may be newer). */
    val publishFirstRunSnapshot: FirstRunOnboardingSnapshot,
) {
    @Volatile
    var retryNotBeforeElapsedMs: Long? = null
}

/** Process-wide single flight shared by every Activity instance (like the process coordinator). */
internal object GatewayInRunRenewalProcessState {
    val gate = GatewayInRunRenewalGate()
    val unauthorizedProbe = GatewayUnauthorizedRenewalProbe()
}
