package kr.co.hanium.dreamup.walksafe

/** Retains the exact native camera release until both close calls are acknowledged. */
internal class CameraSessionRelease {
    class Attempt internal constructor(
        val epoch: Long?,
        internal val closeProvider: () -> Unit,
        internal val closeSession: () -> Unit,
    ) {
        internal var executed = false
        var failure: Exception? = null
            internal set
    }

    @Volatile private var retained: Attempt? = null
    val blocksStart: Boolean get() = retained != null
    val failure: Exception? get() = retained?.failure

    @Synchronized
    fun begin(epoch: Long?, closeProvider: () -> Unit, closeSession: () -> Unit): Attempt {
        check(retained == null) { "Previous camera release is not confirmed" }
        return Attempt(epoch, closeProvider, closeSession).also { retained = it }
    }

    /** Runs on the existing camera/inference lane, after all outstanding Images are returned. */
    @Synchronized
    fun execute(attempt: Attempt): Boolean {
        if (retained !== attempt || attempt.executed) return false
        attempt.executed = true
        try { attempt.closeProvider() } catch (failure: Exception) { attempt.failure = failure }
        try { attempt.closeSession() } catch (failure: Exception) {
            if (attempt.failure == null) attempt.failure = failure else attempt.failure?.addSuppressed(failure)
        }
        if (attempt.failure != null) return false
        retained = null
        return true
    }

    /** Executor rejection also retains the owner. Native close is never guessed to have succeeded. */
    @Synchronized
    fun schedulingFailed(attempt: Attempt, failure: Exception) {
        if (retained === attempt && !attempt.executed) attempt.failure = failure
    }
}
