package kr.co.hanium.dreamup.walksafe.feedback

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.json.JSONObject

/**
 * Memory-only identity references, published together by the lifecycle owner. The owner must bind
 * the actual current RAW confirmation, device/session permission authority, ACTIVE walk epoch,
 * general Gateway session/generation, actor, feedback lifetime, source and collection window.
 * Opaque references avoid manufacturing a second consent/permission authority in this store.
 * They are never formatted, persisted or inferred from engine READY state.
 */
internal class SpeechOutputStorageBinding(
    val rawConfirmation: Any,
    val permissionAuthority: Any,
    val walkEpoch: Any,
    val gatewaySession: Any,
    val gatewayGeneration: Long,
    val actor: Any,
    val feedbackLifetime: Any,
    val source: Any,
    val window: Any,
) {
    private val invalidated = AtomicBoolean()

    internal fun invalidate() { invalidated.set(true) }

    internal val isInvalidated: Boolean get() = invalidated.get()
}

/**
 * Injected approved crypto/storage boundary. All methods run on the store's serial I/O worker.
 * The provider owns the approved key, AAD, root, record limits, retention and verified whole-store
 * deletion. This preparation supplies no production factory, key alias, root or retention policy.
 * appendEncrypted must enforce that policy and must not synthesize metadata/events or retry.
 */
internal interface SpeechOutputEncryptedStorage {
    val aead: LocalAead
    val limits: AeadLimits
    fun associatedData(): ByteArray
    fun appendEncrypted(envelope: String): Boolean
    fun purgeAll(): Boolean
}

/**
 * Best-effort event storage preparation; enqueue is neither a disk ACK nor a playback ACK.
 * currentness must be a short, fail-closed privacy check over the published binding, including
 * pending deny/withdrawal/deletion and exact identities. It runs outside this store's admission
 * lock. The owner must invalidate before changing those authorities and join the purge future
 * from its existing deletion flow, never from the speech observer or under Main/actor locks.
 */
internal class ConsentBoundSpeechOutputStore(
    private val storage: SpeechOutputEncryptedStorage,
    private val currentness: (SpeechOutputStorageBinding) -> Boolean,
) : AutoCloseable {
    private val admissionLock = Any()
    private val active = AtomicReference<SpeechOutputStorageBinding?>()
    private val dropped = AtomicLong()
    @Volatile private var closed = false
    @Volatile private var purgeStarted = false
    private var purgeResult: CompletableFuture<Boolean>? = null
    private var drainResult: CompletableFuture<Boolean>? = null
    private var shutdownSucceeded = false
    // Replaced on every admission authority mutation; reference identity also fences ABA.
    private var admissionToken = Any()
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(64),
        { task -> Thread(task, "walksafe-speech-output-store").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /** Admission only: the caller must already have built the binding from current authorities. */
    fun publish(binding: SpeechOutputStorageBinding): Boolean {
        val observedToken = synchronized(admissionLock) {
            if (closed || purgeStarted || binding.isInvalidated) return false
            admissionToken
        }
        // A reentrant/concurrent publication or invalidation can happen during this callback.
        if (!privacyCurrent(binding)) return false
        return synchronized(admissionLock) {
            if (admissionToken !== observedToken || closed || purgeStarted ||
                binding.isInvalidated
            ) return@synchronized false
            val previous = active.getAndSet(binding)
            if (previous !== binding) previous?.invalidate()
            admissionToken = Any()
            true
        }
    }

    /** Permanent for this binding, including tasks admitted before invalidation. No I/O/wait. */
    fun invalidate(binding: SpeechOutputStorageBinding) {
        synchronized(admissionLock) {
            binding.invalidate()
            active.compareAndSet(binding, null)
            admissionToken = Any()
        }
    }

    /** A captured listener must pass its own binding; it cannot use a newer actor's binding. */
    fun enqueue(binding: SpeechOutputStorageBinding, event: SpeechOutputEvent) {
        if (event.atElapsedRealtimeMs < 0L || !isCurrent(binding)) {
            dropped.incrementAndGet()
            return
        }
        synchronized(admissionLock) {
            if (closed || purgeStarted || active.get() !== binding || binding.isInvalidated) {
                dropped.incrementAndGet()
                return
            }
            try {
                executor.execute(EventWrite(binding, event))
            } catch (_: Exception) {
                // AbortPolicy: full queue/shutdown/thread creation failure never runs caller I/O.
                dropped.incrementAndGet()
            }
        }
    }

    /** Memory-only diagnostic; never emits a synthetic dropped event. */
    fun droppedEventCount(): Long = dropped.get()

    /**
     * Fence first, discard waiting events, then purge after any in-flight write. A bounded queue
     * reserves room by removing only this store's event tasks. Publication stays blocked even
     * after success; a new lifetime/store needs the owner's verified deletion/reconsent flow.
     * Failure is observable and never retried automatically. Calling after close returns false.
     */
    fun invalidateAndPurge(): CompletableFuture<Boolean> = synchronized(admissionLock) {
        purgeResult?.let { return@synchronized it }
        purgeStarted = true
        admissionToken = Any()
        active.getAndSet(null)?.invalidate()
        discardWaitingEvents()
        val result = CompletableFuture<Boolean>()
        purgeResult = result
        try {
            executor.execute {
                result.complete(runCatching { storage.purgeAll() }.getOrDefault(false))
            }
        } catch (_: Exception) {
            result.complete(false)
        }
        result
    }

    /** No wait or disk work. A previously admitted purge remains queued on the serial worker. */
    override fun close() { closeAndDrain() }

    /**
     * Permanently fence admission and discard waiting events, then acknowledge old in-flight
     * work and already queued control tasks on the same bounded worker. True means quiescence,
     * never file/key deletion or purge success. Wait only on the owner's existing cleanup worker.
     * Repeated close/drain returns the same future; rejection/shutdown failure completes false.
     */
    fun closeAndDrain(): CompletableFuture<Boolean> {
        var failed = false
        val result = synchronized(admissionLock) {
            drainResult?.let { return it }
            closed = true
            admissionToken = Any()
            active.getAndSet(null)?.invalidate()
            discardWaitingEvents()
            val result = CompletableFuture<Boolean>()
            drainResult = result
            try {
                executor.execute {
                    // A fast worker may reach here before shutdown returns. The short memory lock
                    // observes its final acknowledgement; future callbacks run outside that lock.
                    val acknowledged = synchronized(admissionLock) { shutdownSucceeded }
                    result.complete(acknowledged)
                }
            } catch (_: Exception) {
                failed = true
            }
            try {
                executor.shutdown()
                shutdownSucceeded = !failed && executor.isShutdown
            } catch (_: Exception) {
                shutdownSucceeded = false
                failed = true
            }
            result
        }
        if (failed) result.complete(false)
        return result
    }

    private fun discardWaitingEvents() {
        executor.queue.toTypedArray().forEach { task ->
            if (task is EventWrite && executor.queue.remove(task)) dropped.incrementAndGet()
        }
    }

    private fun privacyCurrent(binding: SpeechOutputStorageBinding): Boolean =
        runCatching { currentness(binding) }.getOrDefault(false)

    private fun isCurrent(binding: SpeechOutputStorageBinding): Boolean =
        !closed && !purgeStarted && !binding.isInvalidated && active.get() === binding &&
            privacyCurrent(binding) && !closed && !purgeStarted &&
            !binding.isInvalidated && active.get() === binding

    private inner class EventWrite(
        private val binding: SpeechOutputStorageBinding,
        private val event: SpeechOutputEvent,
    ) : Runnable {
        override fun run() {
            var plaintext: ByteArray? = null
            try {
                if (!isCurrent(binding)) { dropped.incrementAndGet(); return }
                // Only enum names, the original monotonic timestamp and an integer/fixed NONE.
                // Existing field-log primitive vocabulary bounds strings to 160 characters.
                val metric = event.kind.name
                val state = event.outcome.name
                check(metric.length <= 160 && state.length <= 160)
                plaintext = JSONObject()
                    .put("metric", metric)
                    .put("state", state)
                    .put("elapsed_realtime_ms", event.atElapsedRealtimeMs)
                    .put("result", event.errorCode ?: "NONE")
                    .toString().toByteArray(Charsets.UTF_8)
                val sealed = storage.aead.seal(plaintext, storage.associatedData(), storage.limits)
                // Seal can be slow. Recheck immediately before the actual provider write.
                if (sealed !is AeadSealResult.Sealed || !isCurrent(binding) ||
                    !storage.appendEncrypted(sealed.envelope)
                ) dropped.incrementAndGet()
            } catch (_: Exception) {
                dropped.incrementAndGet()
            } finally {
                plaintext?.fill(0)
            }
        }
    }
}
