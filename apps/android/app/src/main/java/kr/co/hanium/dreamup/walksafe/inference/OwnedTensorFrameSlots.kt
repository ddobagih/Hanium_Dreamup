package kr.co.hanium.dreamup.walksafe.inference

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class OverlapFrameKey(
    val sessionId: Long,
    val arFrameTimestampNs: Long,
    val cameraImageTimestampNs: Long,
)

internal enum class OverlapFrameStatus {
    ACCEPTED,
    BUSY,
    NO_READY_FRAME,
    DUPLICATE_FRAME,
    OUT_OF_ORDER_FRAME,
    WRONG_SESSION,
    INVALID_TIMESTAMP,
    STALE,
    CLOSED,
    INVALID_LEASE,
    SOURCE_TIMESTAMP_MISMATCH,
    INCOMPLETE_TENSOR,
    FAILED,
}

internal data class FrameSlotResult<T>(
    val status: OverlapFrameStatus,
    val value: T? = null,
    val replacedReadyKey: OverlapFrameKey? = null,
    val discardedKey: OverlapFrameKey? = null,
)

internal class TensorPreparationLease internal constructor(
    internal val owner: Any,
    internal val slot: Int,
    internal val token: Long,
    val key: OverlapFrameKey,
    val capturedAtElapsedRealtimeMs: Long,
    val inputBuffer: ByteBuffer,
)

internal class TensorInferenceLease<M : Any> internal constructor(
    internal val owner: Any,
    internal val slot: Int,
    internal val token: Long,
    val key: OverlapFrameKey,
    val capturedAtElapsedRealtimeMs: Long,
    val inputBuffer: ByteBuffer,
    val metadata: M,
)

/**
 * Exactly two owned tensors: one may be in inference while the other is prepared/replaced.
 * There is at most one ready frame. Never expose leases beyond the experiment wrapper.
 */
internal class OwnedTensorFrameSlots<M : Any>(
    private val sessionId: Long,
    val bytesPerTensor: Int,
    private val maximumSourceAgeMs: Long = 800L,
) {
    init {
        require(sessionId > 0L)
        require(bytesPerTensor > 0)
        require(maximumSourceAgeMs in 1L..800L)
    }

    val tensorBufferCount: Int get() = 2
    val ownedTensorBytes: Long get() = bytesPerTensor.toLong() * tensorBufferCount
    private val lock = Any()
    private val slots = Array(2) { Slot<M>(ByteBuffer.allocateDirect(bytesPerTensor).order(ByteOrder.nativeOrder())) }
    private var closed = false
    private var nextToken = 0L
    private var lastAdmittedKey: OverlapFrameKey? = null
    private var lastStartedKey: OverlapFrameKey? = null
    private var lastPublishedKey: OverlapFrameKey? = null
    private var closedReadyKey: OverlapFrameKey? = null

    fun beginPreparation(
        key: OverlapFrameKey,
        capturedAtElapsedRealtimeMs: Long,
        nowElapsedRealtimeMs: Long,
        actualCameraImageTimestampNs: Long = key.cameraImageTimestampNs,
    ): FrameSlotResult<TensorPreparationLease> = synchronized(lock) {
        if (closed) return@synchronized FrameSlotResult(OverlapFrameStatus.CLOSED)
        validateKey(key)?.let { return@synchronized FrameSlotResult(it) }
        if (actualCameraImageTimestampNs != key.cameraImageTimestampNs) {
            return@synchronized FrameSlotResult(OverlapFrameStatus.SOURCE_TIMESTAMP_MISMATCH)
        }
        freshness(capturedAtElapsedRealtimeMs, nowElapsedRealtimeMs)?.let { return@synchronized FrameSlotResult(it) }
        ordering(key, lastAdmittedKey)?.let { return@synchronized FrameSlotResult(it) }
        if (slots.any { it.state == State.PREPARING }) return@synchronized FrameSlotResult(OverlapFrameStatus.BUSY)
        // Replace an unconsumed ready frame before taking the second free buffer: no FIFO backlog.
        val index = slots.indexOfFirst { it.state == State.READY }.takeIf { it >= 0 }
            ?: slots.indexOfFirst { it.state == State.FREE }
        if (index < 0) return@synchronized FrameSlotResult(OverlapFrameStatus.BUSY)
        val slot = slots[index]
        val replaced = slot.key.takeIf { slot.state == State.READY }
        slot.state = State.PREPARING
        slot.token = ++nextToken
        slot.key = key
        slot.capturedAtMs = capturedAtElapsedRealtimeMs
        slot.metadata = null
        slot.buffer.clear()
        lastAdmittedKey = key
        FrameSlotResult(
            OverlapFrameStatus.ACCEPTED,
            TensorPreparationLease(this, index, slot.token, key, capturedAtElapsedRealtimeMs, slot.buffer),
            replaced,
        )
    }

    fun completePreparation(
        lease: TensorPreparationLease,
        metadata: M,
        nowElapsedRealtimeMs: Long,
    ): OverlapFrameStatus = synchronized(lock) {
        val slot = preparationSlot(lease) ?: return@synchronized OverlapFrameStatus.INVALID_LEASE
        val rejection = when {
            closed -> OverlapFrameStatus.CLOSED
            slot.buffer.position() != bytesPerTensor -> OverlapFrameStatus.INCOMPLETE_TENSOR
            else -> freshness(slot.capturedAtMs, nowElapsedRealtimeMs)
        }
        if (rejection != null) {
            slot.release()
            return@synchronized rejection
        }
        slot.buffer.rewind()
        slot.buffer.limit(bytesPerTensor)
        slot.metadata = metadata
        slot.state = State.READY
        OverlapFrameStatus.ACCEPTED
    }

    fun cancelPreparation(lease: TensorPreparationLease): Boolean = synchronized(lock) {
        val slot = preparationSlot(lease) ?: return@synchronized false
        slot.release()
        true
    }

    fun claimLatest(nowElapsedRealtimeMs: Long): FrameSlotResult<TensorInferenceLease<M>> = synchronized(lock) {
        if (closed) {
            val discarded = closedReadyKey
            closedReadyKey = null
            return@synchronized FrameSlotResult(OverlapFrameStatus.CLOSED, discardedKey = discarded)
        }
        if (slots.any { it.state == State.IN_FLIGHT }) return@synchronized FrameSlotResult(OverlapFrameStatus.BUSY)
        val index = slots.indexOfFirst { it.state == State.READY }
        if (index < 0) return@synchronized FrameSlotResult(OverlapFrameStatus.NO_READY_FRAME)
        val slot = slots[index]
        val key = requireNotNull(slot.key)
        val rejection = freshness(slot.capturedAtMs, nowElapsedRealtimeMs) ?: ordering(key, lastStartedKey)
        if (rejection != null) {
            slot.release()
            return@synchronized FrameSlotResult(rejection, discardedKey = key)
        }
        slot.state = State.IN_FLIGHT
        lastStartedKey = key
        FrameSlotResult(
            OverlapFrameStatus.ACCEPTED,
            TensorInferenceLease(
                this, index, slot.token, key, slot.capturedAtMs,
                slot.buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder()), requireNotNull(slot.metadata),
            ),
        )
    }

    /** Read the clock after acquiring the slot lock, so producer completion cannot overtake it. */
    fun claimLatest(nowElapsedRealtimeMs: () -> Long): FrameSlotResult<TensorInferenceLease<M>> = synchronized(lock) {
        claimLatest(nowElapsedRealtimeMs())
    }

    fun completeInference(
        lease: TensorInferenceLease<M>,
        nowElapsedRealtimeMs: Long,
        succeeded: Boolean = true,
    ): OverlapFrameStatus = synchronized(lock) {
        val slot = inferenceSlot(lease) ?: return@synchronized OverlapFrameStatus.INVALID_LEASE
        val rejection = when {
            closed -> OverlapFrameStatus.CLOSED
            !succeeded -> OverlapFrameStatus.FAILED
            else -> freshness(slot.capturedAtMs, nowElapsedRealtimeMs) ?: ordering(lease.key, lastPublishedKey)
        }
        slot.release()
        if (rejection != null) return@synchronized rejection
        lastPublishedKey = lease.key
        OverlapFrameStatus.ACCEPTED
    }

    /** Active leases retain their storage until completion; close prevents every later publication. */
    fun close() = synchronized(lock) {
        if (!closed) closedReadyKey = slots.firstOrNull { it.state == State.READY }?.key
        closed = true
        slots.filter { it.state == State.READY }.forEach { it.release() }
    }

    fun isDrained(): Boolean = synchronized(lock) { slots.all { it.state == State.FREE } }

    private fun validateKey(key: OverlapFrameKey): OverlapFrameStatus? = when {
        key.sessionId != sessionId -> OverlapFrameStatus.WRONG_SESSION
        key.arFrameTimestampNs <= 0L || key.cameraImageTimestampNs <= 0L -> OverlapFrameStatus.INVALID_TIMESTAMP
        else -> null
    }

    private fun freshness(capturedAtMs: Long, nowMs: Long): OverlapFrameStatus? = when {
        capturedAtMs < 0L || nowMs < capturedAtMs -> OverlapFrameStatus.INVALID_TIMESTAMP
        nowMs - capturedAtMs > maximumSourceAgeMs -> OverlapFrameStatus.STALE
        else -> null
    }

    private fun ordering(key: OverlapFrameKey, previous: OverlapFrameKey?): OverlapFrameStatus? = when {
        previous == null -> null
        key.arFrameTimestampNs < previous.arFrameTimestampNs ||
            key.cameraImageTimestampNs < previous.cameraImageTimestampNs -> OverlapFrameStatus.OUT_OF_ORDER_FRAME
        key.arFrameTimestampNs == previous.arFrameTimestampNs ||
            key.cameraImageTimestampNs == previous.cameraImageTimestampNs -> OverlapFrameStatus.DUPLICATE_FRAME
        else -> null
    }

    private fun preparationSlot(lease: TensorPreparationLease): Slot<M>? =
        slots.getOrNull(lease.slot)?.takeIf {
            lease.owner === this && it.state == State.PREPARING && it.token == lease.token && it.key == lease.key
        }

    private fun inferenceSlot(lease: TensorInferenceLease<M>): Slot<M>? =
        slots.getOrNull(lease.slot)?.takeIf {
            lease.owner === this && it.state == State.IN_FLIGHT && it.token == lease.token && it.key == lease.key
        }

    private enum class State { FREE, PREPARING, READY, IN_FLIGHT }

    private class Slot<M : Any>(val buffer: ByteBuffer) {
        var state = State.FREE
        var token = 0L
        var key: OverlapFrameKey? = null
        var capturedAtMs = 0L
        var metadata: M? = null

        fun release() {
            state = State.FREE
            key = null
            metadata = null
        }
    }
}
