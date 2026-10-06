package kr.co.hanium.dreamup.walksafe.rawcollection

import java.util.EnumMap

/** Why a raw runtime boundary was not admitted; never shown to the user (FP-035 failure_behavior[2]). */
enum class RawCollectionGateReason {
    WALK_STATE,
    RECOVERY_STAGE,
    SESSION,
    OWNER,
    CONSENT,
    CONSENT_BINDING,
    WALK_ID,
    GATEWAY_WALK_LEASE,
    NETWORK,
    /** FP-035-DETAIL-04 interim: RAW originals are sent only while charging (unknown = not charging). */
    CHARGING,
    SERVER_DISABLED,
    RETRY_DELAY,
}

/** Separates "the queue is empty" from "the queue could not be checked" (16-17 #12, #60). */
sealed interface RawReadyStatus {
    data class Count(val count: Int) : RawReadyStatus {
        init {
            require(count >= 0)
        }
    }

    data class GateBlocked(val reason: RawCollectionGateReason) : RawReadyStatus

    data object ReadFailed : RawReadyStatus
}

/** Fixed internal counter keys; the set is closed so the aggregate stays small. */
enum class RawCollectionOutcomeCounter {
    CAPTURE_CAPTURED,
    CAPTURE_GATE_BLOCKED,
    CAPTURE_RATE_LIMITED,
    CAPTURE_STORAGE_FAILURE,
    CAPTURE_INVALID_WINDOW,
    /** A new segment was held: phone queue capacity (16-17 #18) or device space (14 #39). */
    CAPTURE_CAPACITY_HOLD,
    SEGMENT_SEALED_AT_BOUNDARY,
    /** S1617-18-F2: no approved profile, walk budget in use: a boundary window was merged. */
    SEGMENT_MERGED_AT_BOUNDARY,
    /** S1617-6-F1: a partial window closed unstored because the detector identity changed in it. */
    IDENTITY_SPLIT_WINDOW,
    SEGMENT_DISCARDED_INCOMPLETE,
    /**
     * S1617-13 (16-17 #13) / owner decision 3(b): at walk end (or, after a process end, at the next
     * post-walk check) the walk's unsealed segment is kept: sealed for the post-walk upload under
     * its original walk id and receipt.
     */
    SEGMENT_SEALED_AT_WALK_END,
    /** S1617-13: the ended walk's remainder could not be sealed (incomplete, invalid, stale receipt) and was deleted. */
    SEGMENT_DISCARDED_AT_WALK_END,
    EXPIRED_DISCARD,
    UPLOAD_BLOCKED,
    UPLOAD_FAILED,
    UPLOAD_SERVER_DISABLED,
    /** The Gateway refused the segment's walk for good (unknown, another account or device, too old): deleted. */
    UPLOAD_WALK_REJECTED,
}

/**
 * In-memory aggregate of raw collection outcomes for internal diagnostics only.
 * It holds counts keyed by closed enums: no identifiers, payload bytes, location or time series.
 */
class RawCollectionOutcomeCounters {
    private val outcomes = EnumMap<RawCollectionOutcomeCounter, Long>(
        RawCollectionOutcomeCounter::class.java,
    )
    private val captureGateReasons = EnumMap<RawCollectionGateReason, Long>(
        RawCollectionGateReason::class.java,
    )
    private val uploadGateReasons = EnumMap<RawCollectionGateReason, Long>(
        RawCollectionGateReason::class.java,
    )

    @Synchronized
    fun record(counter: RawCollectionOutcomeCounter, amount: Long = 1L) {
        if (amount <= 0L) return
        outcomes[counter] = saturatingAdd(outcomes[counter] ?: 0L, amount)
    }

    @Synchronized
    fun recordCaptureGate(reason: RawCollectionGateReason) {
        record(RawCollectionOutcomeCounter.CAPTURE_GATE_BLOCKED)
        captureGateReasons[reason] = saturatingAdd(captureGateReasons[reason] ?: 0L, 1L)
    }

    @Synchronized
    fun recordUploadGate(reason: RawCollectionGateReason) {
        record(
            if (reason == RawCollectionGateReason.SERVER_DISABLED) {
                RawCollectionOutcomeCounter.UPLOAD_SERVER_DISABLED
            } else {
                RawCollectionOutcomeCounter.UPLOAD_BLOCKED
            },
        )
        uploadGateReasons[reason] = saturatingAdd(uploadGateReasons[reason] ?: 0L, 1L)
    }

    @Synchronized
    fun count(counter: RawCollectionOutcomeCounter): Long = outcomes[counter] ?: 0L

    @Synchronized
    fun captureGateCount(reason: RawCollectionGateReason): Long = captureGateReasons[reason] ?: 0L

    @Synchronized
    fun uploadGateCount(reason: RawCollectionGateReason): Long = uploadGateReasons[reason] ?: 0L

    /** Stable, non-zero entries only; suitable for a debug field-log event or diagnostics text. */
    @Synchronized
    fun snapshot(): Map<String, Long> = buildMap {
        outcomes.forEach { (key, value) -> if (value > 0L) put(key.name.lowercase(), value) }
        captureGateReasons.forEach { (key, value) ->
            if (value > 0L) put("capture_gate_${key.name.lowercase()}", value)
        }
        uploadGateReasons.forEach { (key, value) ->
            if (value > 0L) put("upload_gate_${key.name.lowercase()}", value)
        }
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
