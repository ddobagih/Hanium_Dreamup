package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import java.util.concurrent.atomic.AtomicBoolean

/** Native-free does not prove that a parent Android/Binder call has returned. */
internal class VisionParentReleaseTracker {
    private val lock = Any()
    private var tasks = 0
    private var bindAttempted = false
    private var bindFinished = false
    private var unbindConfirmed = false
    private var sealed = false

    internal class Work(private val owner: VisionParentReleaseTracker) {
        private val finished = AtomicBoolean(false)
        fun finish() { if (finished.compareAndSet(false, true)) owner.finish() }
    }

    fun reserve(): Work? = synchronized(lock) {
        if (sealed) null else { tasks++; Work(this) }
    }

    private fun finish() = synchronized(lock) { check(tasks > 0); tasks-- }
    fun bindingStarted() = synchronized(lock) { check(!bindFinished); bindAttempted = true }
    fun bindingFinished() = synchronized(lock) { bindFinished = true }
    /** Only a normally returned SDK unbind call proves cleanup of an attempted bind. */
    fun unbindingSucceeded() = synchronized(lock) { check(bindAttempted && bindFinished); unbindConfirmed = true }

    fun claimRelease(nativeConfirmed: Boolean): Boolean = synchronized(lock) {
        if (sealed || !nativeConfirmed || !bindFinished || bindAttempted && !unbindConfirmed || tasks != 0) false
        else { sealed = true; true }
    }
}
