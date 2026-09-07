package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.util.concurrent.atomic.AtomicLong

class AnalysisCancelledException : IllegalStateException("ANALYSIS_CANCELLED")

class AnalysisCancellation {
    private val guard = Any()
    private var cancelled = false
    private val listeners = linkedSetOf<() -> Unit>()

    fun isCancelled(): Boolean = synchronized(guard) { cancelled }

    fun throwIfCancelled() {
        if (isCancelled() || Thread.currentThread().isInterrupted) throw AnalysisCancelledException()
    }

    fun cancel(): Boolean {
        val callbacks = synchronized(guard) {
            if (cancelled) return false
            cancelled = true
            listeners.toList().also { listeners.clear() }
        }
        callbacks.forEach { callback -> runCatching(callback) }
        return true
    }

    fun register(listener: () -> Unit): AutoCloseable {
        val invokeImmediately = synchronized(guard) {
            if (cancelled) {
                true
            } else {
                listeners += listener
                false
            }
        }
        if (invokeImmediately) listener()
        return AutoCloseable { synchronized(guard) { listeners.remove(listener) } }
    }
}

object AnalysisRunGeneration {
    private val value = AtomicLong(0L)

    fun next(): Long = value.updateAndGet { current ->
        check(current < Long.MAX_VALUE) { "ANALYSIS_GENERATION_EXHAUSTED" }
        current + 1L
    }
}

fun acceptsAnalysisCallback(
    activeGeneration: Long?,
    callbackGeneration: Long,
    callbackCancelled: Boolean,
): Boolean = activeGeneration == callbackGeneration && !callbackCancelled
