package kr.co.hanium.dreamup.walksafe.fieldtest

import java.io.OutputStream
import java.util.ArrayDeque

/** One session, one I/O thread. The monitor protects admission only, never sink operations. */
internal class BoundedRawGnssWriter(
    private val openSink: () -> OutputStream,
    private val header: ByteArray,
    private val maxFileBytes: Long,
    private val maxPendingBatches: Int = 16,
    private val maxPendingBytes: Int = 1024 * 1024,
    private val publishClosedSink: () -> Unit = {},
    private val onChanged: () -> Unit = {},
    private val onClosed: () -> Unit = {},
) {
    enum class State { IDLE, STARTING, RECORDING, STOPPING, STOPPED, ERROR }

    data class Batch(
        val bytes: ByteArray,
        val measurements: Long,
        val adrValid: Long = 0,
        val missingFullBias: Long = 0,
        val missingCodeType: Long = 0,
        val utcFallbacks: Long = 0,
        val elapsedRealtimeNanos: Long? = null,
    )

    data class Snapshot(
        val state: State = State.IDLE,
        val generation: Long = 0,
        val closed: Boolean = false,
        val pendingBatches: Int = 0,
        val pendingBytes: Int = 0,
        val bytesWritten: Long = 0,
        val measurements: Long = 0,
        val epochs: Long = 0,
        val adrValid: Long = 0,
        val missingFullBias: Long = 0,
        val missingCodeType: Long = 0,
        val utcFallbacks: Long = 0,
        val lastEpochNanos: Long? = null,
        val droppedMeasurements: Long = 0,
        val error: String? = null,
    )

    private val lock = Object()
    private val queue = ArrayDeque<Batch>()
    private var stopRequested = false
    private var admittedBytes = header.size.toLong()
    private var pendingMeasurements = 0L

    @Volatile
    var snapshot = Snapshot()
        private set

    init {
        require(maxPendingBatches > 0 && maxPendingBytes > 0)
        require(maxFileBytes >= header.size.toLong() + FOOTER_RESERVE_BYTES)
    }

    /** Completion true means header write/flush finished, not merely that a thread was queued. */
    fun start(onOpened: (Boolean, Long) -> Unit) {
        val token = synchronized(lock) {
            if (snapshot.state != State.IDLE) null else {
                snapshot = snapshot.copy(state = State.STARTING, generation = snapshot.generation + 1)
                snapshot.generation
            }
        }
        if (token == null) {
            onOpened(false, snapshot.generation)
            return
        }
        changed()
        try {
            Thread({ runWriter(token, onOpened) }, "walksafe-raw-gnss").start()
        } catch (failure: Exception) {
            synchronized(lock) {
                snapshot = snapshot.copy(state = State.ERROR, closed = true,
                    generation = snapshot.generation + 1, error = "Raw worker start failed: ${failure.javaClass.simpleName}")
            }
            runCatching { onOpened(false, token) }
            changed()
            runCatching(onClosed)
        }
    }

    fun accepts(token: Long): Boolean = snapshot.let { it.state == State.RECORDING && it.generation == token }

    /** Pending budgets include the batch currently blocked in write/flush. Payloads are owned copies. */
    fun append(token: Long, batch: Batch): Boolean {
        val accepted = synchronized(lock) {
            if (!accepts(token)) return false
            val reason = when {
                snapshot.pendingBatches >= maxPendingBatches || batch.bytes.size > maxPendingBytes - snapshot.pendingBytes ->
                    "Raw queue overflow; observations omitted; PPK continuity is not established."
                batch.bytes.size.toLong() > maxFileBytes - FOOTER_RESERVE_BYTES - admittedBytes ->
                    "Raw file byte limit reached; observations omitted; PPK continuity is not established."
                else -> null
            }
            if (reason != null) {
                snapshot = snapshot.copy(droppedMeasurements = snapshot.droppedMeasurements + batch.measurements)
                requestStopLocked(reason)
                false
            } else {
                queue.addLast(batch.copy(bytes = batch.bytes.copyOf()))
                admittedBytes += batch.bytes.size
                pendingMeasurements += batch.measurements
                snapshot = snapshot.copy(pendingBatches = snapshot.pendingBatches + 1,
                    pendingBytes = snapshot.pendingBytes + batch.bytes.size)
                lock.notifyAll()
                true
            }
        }
        changed()
        return accepted
    }

    /** Nonblocking. STOPPED is published only after flush, close, and final-name publication. */
    fun stop() {
        if (snapshot.state == State.IDLE) {
            fail("Raw recording was not started.")
            return
        }
        synchronized(lock) {
            if (snapshot.closed) return
            requestStopLocked(null)
        }
        changed()
    }

    fun fail(message: String) {
        val closedWithoutWorker = synchronized(lock) {
            if (snapshot.closed) return
            if (snapshot.state == State.IDLE) {
                snapshot = snapshot.copy(state = State.ERROR, closed = true, generation = snapshot.generation + 1, error = message)
                true
            } else {
                requestStopLocked(message)
                false
            }
        }
        changed()
        if (closedWithoutWorker) runCatching(onClosed)
    }

    private fun requestStopLocked(error: String?) {
        if (!stopRequested) {
            stopRequested = true
            snapshot = snapshot.copy(generation = snapshot.generation + 1)
        }
        snapshot = snapshot.copy(state = State.STOPPING, error = snapshot.error ?: error)
        lock.notifyAll()
    }

    private fun runWriter(token: Long, onOpened: (Boolean, Long) -> Unit) {
        var sink: OutputStream? = null
        var openedNotified = false
        try {
            val output = openSink()
            sink = output
            output.write(header)
            output.flush()
            val opened = synchronized(lock) {
                snapshot = snapshot.copy(bytesWritten = header.size.toLong(),
                    state = if (stopRequested) State.STOPPING else State.RECORDING)
                !stopRequested
            }
            changed()
            openedNotified = true
            runCatching { onOpened(opened, token) }
            while (true) {
                val batch = synchronized(lock) {
                    while (queue.isEmpty() && !stopRequested) lock.wait()
                    if (queue.isEmpty()) null else queue.removeFirst()
                } ?: break
                output.write(batch.bytes)
                output.flush()
                synchronized(lock) {
                    pendingMeasurements -= batch.measurements
                    snapshot = snapshot.copy(
                        pendingBatches = snapshot.pendingBatches - 1,
                        pendingBytes = snapshot.pendingBytes - batch.bytes.size,
                        bytesWritten = snapshot.bytesWritten + batch.bytes.size,
                        measurements = snapshot.measurements + batch.measurements,
                        epochs = snapshot.epochs + 1,
                        adrValid = snapshot.adrValid + batch.adrValid,
                        missingFullBias = snapshot.missingFullBias + batch.missingFullBias,
                        missingCodeType = snapshot.missingCodeType + batch.missingCodeType,
                        utcFallbacks = snapshot.utcFallbacks + batch.utcFallbacks,
                        lastEpochNanos = batch.elapsedRealtimeNanos,
                    )
                }
                changed()
            }
            val end = snapshot
            val footer = ("# RawSessionEnd,${if (end.error == null) "COMPLETE" else "INCOMPLETE"}," +
                "omitted=${end.droppedMeasurements},PPK_UNVERIFIED\n").toByteArray(Charsets.UTF_8)
            check(footer.size <= FOOTER_RESERVE_BYTES && footer.size <= maxFileBytes - end.bytesWritten)
            output.write(footer)
            output.flush()
            synchronized(lock) { snapshot = snapshot.copy(bytesWritten = snapshot.bytesWritten + footer.size) }
        } catch (failure: Exception) {
            synchronized(lock) {
                snapshot = snapshot.copy(droppedMeasurements = snapshot.droppedMeasurements + pendingMeasurements,
                    pendingBatches = 0, pendingBytes = 0)
                pendingMeasurements = 0
                queue.clear()
                requestStopLocked("Raw I/O failed: ${failure.javaClass.simpleName}; file is incomplete.")
            }
            changed()
        } finally {
            if (!openedNotified) runCatching { onOpened(false, token) }
            // Neither these slow operations nor publication holds the admission monitor.
            try { sink?.flush() } catch (failure: Exception) { closeFailure("flush", failure) }
            try { sink?.close() } catch (failure: Exception) { closeFailure("close", failure) }
            if (sink != null && snapshot.error == null) {
                try { publishClosedSink() } catch (failure: Exception) { closeFailure("publish", failure) }
            }
            synchronized(lock) {
                snapshot = snapshot.copy(state = if (snapshot.error == null) State.STOPPED else State.ERROR, closed = true)
            }
            changed()
            runCatching(onClosed)
        }
    }

    private fun closeFailure(operation: String, failure: Exception) = synchronized(lock) {
        val detail = "Raw $operation failed: ${failure.javaClass.simpleName}; file is incomplete."
        requestStopLocked(snapshot.error?.let { "$it / $detail" } ?: detail)
        // Preserve an earlier overflow/I/O diagnosis while also showing close failures.
        if (snapshot.error?.contains(detail) != true) snapshot = snapshot.copy(error = "${snapshot.error} / $detail")
    }

    private fun changed() { runCatching(onChanged) }

    private companion object {
        const val FOOTER_RESERVE_BYTES = 256L
    }
}
