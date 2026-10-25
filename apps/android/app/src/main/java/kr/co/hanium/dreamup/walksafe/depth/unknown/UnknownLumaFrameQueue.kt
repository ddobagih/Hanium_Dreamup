package kr.co.hanium.dreamup.walksafe.depth.unknown


/** A short-lock queue. Processing, producer monitors and discard callbacks run outside it. */
internal class UnknownLumaFrameQueue<T>(private val maximumFrames: Int) {
    private val lock = Any()
    private val pending = ArrayDeque<T>()
    private var accepting = true
    private var drainScheduled = false

    init { require(maximumFrames in 1..32) }
    val isAccepting: Boolean get() = synchronized(lock) { accepting }
    val pendingCount: Int get() = synchronized(lock) { pending.size }

    fun enqueue(frame: T, schedule: (() -> Unit) -> Unit,
        process: (T) -> Unit, discard: (T) -> Unit): Boolean {
        var startDrain = false
        val evicted = synchronized(lock) {
            if (!accepting) return false
            val old = if (pending.size == maximumFrames) pending.removeFirst() else null
            pending.addLast(frame)
            if (!drainScheduled) { drainScheduled = true; startDrain = true }
            old
        }
        evicted?.let(discard)
        if (startDrain) try {
            schedule { drain(process, discard) }
        } catch (error: RuntimeException) {
            invalidate(discard)
            throw error
        }
        return true
    }

    fun invalidate(discard: (T) -> Unit) {
        val old = synchronized(lock) {
            accepting = false
            pending.toList().also { pending.clear() }
        }
        old.forEach(discard)
    }

    private fun drain(process: (T) -> Unit, discard: (T) -> Unit) {
        while (true) {
            val frame = synchronized(lock) {
                if (!accepting || pending.isEmpty()) { drainScheduled = false; return }
                pending.removeFirst()
            }
            try { process(frame) } catch (error: RuntimeException) {
                discard(frame)
                invalidate(discard)
                synchronized(lock) { drainScheduled = false }
                throw error
            }
        }
    }
}
