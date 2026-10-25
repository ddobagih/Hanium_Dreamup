package kr.co.hanium.dreamup.walksafe.device

/** Retains one uncertain native close for the process without assuming close can be replayed. */
internal class RuntimeArSessionCleanupOwnership {
    private val lock = Any()
    private var retainedOwner: Any? = null
    private var retainedAction: (() -> Unit)? = null
    private var attempted = false
    private var firstFailure: RuntimeException? = null

    val isPending: Boolean
        get() = synchronized(lock) { retainedAction != null }

    fun isOwnedBy(owner: Any): Boolean = synchronized(lock) { retainedOwner === owner }

    fun failureFor(owner: Any): RuntimeException? = synchronized(lock) {
        if (retainedOwner === owner) firstFailure else null
    }

    fun retain(owner: Any, action: () -> Unit): Boolean = synchronized(lock) {
        if (retainedAction != null) return@synchronized false
        retainedOwner = owner
        retainedAction = action
        attempted = false
        firstFailure = null
        true
    }

    fun close(owner: Any, action: () -> Unit): Boolean {
        if (!retain(owner, action)) return false
        return closeRetained(owner)
    }

    fun closeRetained(owner: Any, beforeClose: () -> Unit = {}): Boolean {
        val action = synchronized(lock) {
            val retained = retainedAction ?: return true
            if (retainedOwner !== owner || attempted) return false
            attempted = true
            retained
        }
        try {
            // Neither native close nor its pre-close consumer executes while holding the state lock.
            beforeClose()
            action()
        } catch (failure: RuntimeException) {
            synchronized(lock) {
                if (retainedOwner === owner && retainedAction === action && firstFailure == null) firstFailure = failure
            }
            return false
        }
        return synchronized(lock) {
            if (retainedOwner !== owner || retainedAction !== action) return@synchronized false
            retainedOwner = null
            retainedAction = null
            attempted = false
            firstFailure = null
            true
        }
    }

    companion object {
        val processSingleton = RuntimeArSessionCleanupOwnership()
    }
}
