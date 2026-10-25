package kr.co.hanium.dreamup.walksafe.depth

import java.io.Closeable

/** The branch requesting Full Depth; this does not claim any object's need for Full pixels. */
enum class FullDepthRequestPurpose {
    RAW_UNAVAILABLE_FALLBACK,
    RAW_AVAILABLE_SUPPLEMENT,
    PREFLIGHT_PRIMARY,
}

/** Durations only, never frame/CPU/capture timestamps. Null means that phase was not measured. */
data class FullDepthTiming(
    val purpose: FullDepthRequestPurpose,
    val acquisitionDurationNs: Long?,
    val acquisitionSucceeded: Boolean,
    val conversionDurationNs: Long?,
    val conversionSucceeded: Boolean?,
    val closeDurationNs: Long?,
    val closeSucceeded: Boolean?,
) {
    val conversionAndCloseDurationNs: Long?
        get() = conversionDurationNs?.let { conversion -> closeDurationNs?.let { conversion + it } }
}

/** One optional Full attempt. Observer/clock failures cannot change acquisition or ownership. */
internal class FullDepthTimingCapture(
    private val purpose: FullDepthRequestPurpose,
    private val clockNs: () -> Long,
    private val observer: (FullDepthTiming) -> Unit,
) {
    private var acquisitionDurationNs: Long? = null
    private var acquisitionSucceeded = false
    private var conversionDurationNs: Long? = null
    private var conversionSucceeded: Boolean? = null
    private var closeDurationNs: Long? = null
    private var closeSucceeded: Boolean? = null
    private var reported = false

    fun <T> acquire(block: () -> T): T {
        val started = readClock()
        try {
            return block().also { acquisitionSucceeded = true }
        } finally {
            acquisitionDurationNs = elapsedSince(started)
            if (!acquisitionSucceeded) report()
        }
    }

    fun <T> convert(block: () -> T): T {
        val started = readClock()
        conversionSucceeded = false
        try {
            return block().also { conversionSucceeded = true }
        } finally {
            conversionDurationNs = elapsedSince(started)
        }
    }

    fun close(block: () -> Unit) {
        val started = readClock()
        closeSucceeded = false
        try {
            block()
            closeSucceeded = true
        } finally {
            closeDurationNs = elapsedSince(started)
            report()
        }
    }

    /** Kotlin use preserves the original failure with a close failure suppressed. */
    fun <T : AutoCloseable, R> useAndClose(resource: T, block: (T) -> R): R =
        Closeable { close { resource.close() } }.use { block(resource) }

    private fun readClock(): Long? = runCatching { clockNs() }.getOrNull()

    private fun elapsedSince(started: Long?): Long? {
        val ended = readClock()
        return if (started == null || ended == null) null else (ended - started).takeIf { it >= 0L }
    }

    private fun report() {
        if (reported) return
        reported = true
        runCatching {
            observer(FullDepthTiming(purpose, acquisitionDurationNs, acquisitionSucceeded,
                conversionDurationNs, conversionSucceeded, closeDurationNs, closeSucceeded))
        }
    }
}
