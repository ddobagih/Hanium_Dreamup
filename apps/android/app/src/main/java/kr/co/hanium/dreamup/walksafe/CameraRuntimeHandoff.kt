package kr.co.hanium.dreamup.walksafe

import java.util.concurrent.CompletableFuture

/** Between Activity owners, retain only release metadata until every native resource acknowledges. */
internal object CameraRuntimeHandoff {
    private val registry = Registry()

    fun pendingRelease(): CompletableFuture<Unit>? = registry.pendingRelease()
    fun begin(): Claim? = registry.begin()

    internal class Claim internal constructor(private val registry: Registry) {
        internal val released = CompletableFuture<Unit>()
        internal var terminal = false
        fun complete() = registry.finish(this, null)
        fun fail(error: Throwable) = registry.finish(this, error)
        fun completeAfter(releases: List<CompletableFuture<*>>) {
            releases.forEach { release -> release.whenComplete { _, error -> if (error != null) fail(error) } }
            CompletableFuture.allOf(*releases.toTypedArray()).whenComplete { _, error ->
                if (error == null) complete() else fail(error)
            }
        }
    }

    /** Isolated state also allows failure-persistence tests without resetting the process owner. */
    internal class Registry {
        private var retained: Claim? = null

        @Synchronized fun pendingRelease(): CompletableFuture<Unit>? = retained?.released
        @Synchronized fun begin(): Claim? {
            if (retained != null) return null
            return Claim(this).also { retained = it }
        }

        internal fun finish(claim: Claim, error: Throwable?) {
            synchronized(this) {
                if (retained !== claim || claim.terminal) return
                claim.terminal = true
                if (error == null) retained = null
            }
            // Completion callbacks may reenter a different owner, so never dispatch under the lock.
            if (error == null) claim.released.complete(Unit) else claim.released.completeExceptionally(error)
        }
    }
}
