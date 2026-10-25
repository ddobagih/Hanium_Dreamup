package kr.co.hanium.dreamup.walksafe.navigation.start

/** Owns the initial prompt lease and its elapsed observation window, never sensor accuracy.
 * Callers retain the existing streams, read live owner/permission proof, and schedule effects.
 */
internal class GuidanceStartCalibration {
    enum class Phase { WAIT_VOICE_DONE, OBSERVING, READY }

    class Attempt internal constructor(
        val generation: Long,
        val startedAtMs: Long,
        internal val requestIdentity: Any,
    )

    class PromptLease internal constructor(
        val attempt: Attempt,
        val serial: Long,
        val claimedAtMs: Long,
    )

    data class Snapshot(
        val attempt: Attempt,
        val phase: Phase,
        val prompt: PromptLease?,
        val voiceDoneAtMs: Long?,
        val readyAtMs: Long?,
    )

    private class State(val attempt: Attempt) {
        var phase = Phase.WAIT_VOICE_DONE
        var prompt: PromptLease? = null
        var voiceDoneAtMs: Long? = null
        var readyAtMs: Long? = null
        var promptRetryNotBeforeMs: Long? = null
        fun snapshot() = Snapshot(attempt, phase, prompt, voiceDoneAtMs, readyAtMs)
    }

    private var state: State? = null
    private var generation = 0L
    private var promptSerial = 0L
    private var lastNowMs: Long? = null

    val currentAttempt: Attempt? @Synchronized get() = state?.attempt
    val snapshot: Snapshot? @Synchronized get() = state?.snapshot()

    /** Only a new explicit request identity replaces an attempt. Recovery reuses its existing proof. */
    @Synchronized
    fun begin(requestIdentity: Any, nowMs: Long): Attempt? {
        if (!acceptClock(nowMs)) {
            state = null
            return null
        }
        state?.takeIf { it.attempt.requestIdentity === requestIdentity }?.let { return it.attempt }
        return Attempt(++generation, nowMs, requestIdentity).also { state = State(it) }
    }

    /** Reserve before submitting speech, so even an inline terminal callback names one exact lease. */
    @Synchronized
    fun claimPrompt(attempt: Attempt, nowMs: Long, ownerIsCurrent: Boolean, speechBusy: Boolean): PromptLease? {
        val current = acceptAttempt(attempt, nowMs, ownerIsCurrent) ?: return null
        if (current.phase != Phase.WAIT_VOICE_DONE || current.prompt != null || speechBusy ||
            current.promptRetryNotBeforeMs?.let { nowMs < it } == true) return null
        return PromptLease(attempt, ++promptSerial, nowMs).also { current.prompt = it }
    }

    /** Identity only: the caller must additionally check its live authority before output. */
    @Synchronized
    fun isPromptCurrent(lease: PromptLease): Boolean = state?.let {
        it.attempt === lease.attempt && it.phase == Phase.WAIT_VOICE_DONE && it.prompt === lease
    } == true

    /** DONE is the native terminal time; a delayed dispatcher must not renew it to receipt time. */
    @Synchronized
    fun voiceDone(lease: PromptLease, doneAtMs: Long, nowMs: Long, ownerIsCurrent: Boolean): Boolean {
        if (!isPromptCurrent(lease)) return false
        val current = acceptAttempt(lease.attempt, nowMs, ownerIsCurrent) ?: return false
        if (doneAtMs < lease.claimedAtMs || doneAtMs > nowMs || doneAtMs > Long.MAX_VALUE - OBSERVATION_DURATION_MS) {
            state = null
            return false
        }
        current.prompt = null
        current.voiceDoneAtMs = doneAtMs
        current.readyAtMs = doneAtMs + OBSERVATION_DURATION_MS
        current.phase = if (nowMs >= requireNotNull(current.readyAtMs)) Phase.READY else Phase.OBSERVING
        return true
    }

    /** A rejected/failed utterance retains the same intent for retry; it never counts as DONE. */
    @Synchronized
    fun voiceFailed(lease: PromptLease, nowMs: Long, ownerIsCurrent: Boolean, retryDelayMs: Long = 0L): Boolean {
        if (!isPromptCurrent(lease)) return false
        val current = acceptAttempt(lease.attempt, nowMs, ownerIsCurrent) ?: return false
        if (retryDelayMs < 0L || nowMs > Long.MAX_VALUE - retryDelayMs) {
            state = null
            return false
        }
        current.prompt = null
        current.promptRetryNotBeforeMs = nowMs + retryDelayMs
        return true
    }

    /** READY means only that the observation time elapsed. GPS/facing admission stays outside. */
    @Synchronized
    fun evaluate(attempt: Attempt, nowMs: Long, ownerIsCurrent: Boolean): Snapshot? {
        val current = acceptAttempt(attempt, nowMs, ownerIsCurrent) ?: return null
        if (current.phase == Phase.OBSERVING && nowMs >= requireNotNull(current.readyAtMs)) {
            current.phase = Phase.READY
        }
        return current.snapshot()
    }

    @Synchronized
    fun cancel() { state = null }

    private fun acceptAttempt(attempt: Attempt, nowMs: Long, ownerIsCurrent: Boolean): State? {
        val current = state?.takeIf { it.attempt === attempt } ?: return null
        if (!ownerIsCurrent || !acceptClock(nowMs)) {
            state = null
            return null
        }
        return current
    }

    private fun acceptClock(nowMs: Long): Boolean {
        if (nowMs < 0L || lastNowMs?.let { nowMs < it } == true) return false
        lastNowMs = nowMs
        return true
    }

    companion object {
        const val OBSERVATION_DURATION_MS = 3_000L
        const val PROMPT_KO = "휴대전화를 몸 정면에 고정하고 잠시 서 있어 주세요. 방향을 확인하겠습니다."
    }
}
