package kr.co.hanium.dreamup.walksafe

import java.util.concurrent.CompletableFuture

/** Between Activity owners, retain only release metadata until every native resource acknowledges. */
internal object CameraRuntimeHandoff {
    private val registry = Registry()

    fun pendingRelease(): CompletableFuture<Unit>? = registry.pendingRelease()
    fun begin(): Claim? = registry.begin()
    /** Joins cleanup only; this does not authorize a second camera/model allocation. */
    fun joinRetirement(): Claim = registry.joinRetirement()

    internal class ReleaseGroup {
        val released = CompletableFuture<Unit>()
        val participants = mutableSetOf<Claim>()
        var failed = false
    }

    internal class Claim internal constructor(private val registry: Registry, internal val group: ReleaseGroup) {
        internal val released get() = group.released
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
        private var retained: ReleaseGroup? = null

        @Synchronized fun pendingRelease(): CompletableFuture<Unit>? = retained?.released
        @Synchronized fun begin(): Claim? {
            if (retained != null) return null
            val group = ReleaseGroup().also { retained = it }
            return Claim(this, group).also { group.participants += it }
        }
        @Synchronized fun joinRetirement(): Claim {
            val group = retained ?: ReleaseGroup().also { retained = it }
            return Claim(this, group).also { group.participants += it }
        }

        internal fun finish(claim: Claim, error: Throwable?) {
            var completed = false
            synchronized(this) {
                if (retained !== claim.group || claim.terminal) return
                claim.terminal = true
                if (error != null) claim.group.failed = true
                if (!claim.group.failed && claim.group.participants.all { it.terminal }) {
                    retained = null
                    completed = true
                }
            }
            // Completion callbacks may reenter a different owner, so never dispatch under the lock.
            if (error != null) claim.released.completeExceptionally(error)
            else if (completed) claim.released.complete(Unit)
        }
    }
}
