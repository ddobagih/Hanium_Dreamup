package kr.co.hanium.dreamup.walksafe.rawcollection

import kr.co.hanium.dreamup.walksafe.BuildConfig

/**
 * Phone-side RAW queue limits (16-17 #18). The numbers are a measured device value
 * (GATE-PHONE-QUEUE-BYTE-LIMIT), so none is written here: they are injected by the build like the
 * report queue profile and are absent (null) in release. Without an approved profile the store
 * keeps at most [RAW_NO_PROFILE_RETAINED_SEGMENTS] uploadable segments of all walks together;
 * copies no walk can upload are bounded separately (S1617-18-F1), so every stored collection stays
 * within the existing per-segment bound [RAW_MAX_COLLECTION_BYTES].
 */
internal data class ApprovedRawQueueCapacityProfile(
    val maxSegments: Int,
    val maxTotalBytes: Long,
) {
    init {
        require(maxSegments >= 1)
        require(maxTotalBytes >= RAW_MAX_COLLECTION_BYTES)
    }
}

internal fun approvedRawQueueCapacityProfile(
    enabled: Boolean,
    maxSegments: Int,
    maxTotalBytes: Long,
): ApprovedRawQueueCapacityProfile? {
    if (!enabled) return null
    return runCatching { ApprovedRawQueueCapacityProfile(maxSegments, maxTotalBytes) }.getOrNull()
}

internal val PRODUCTION_RAW_QUEUE_CAPACITY_PROFILE: ApprovedRawQueueCapacityProfile? =
    approvedRawQueueCapacityProfile(
        enabled = BuildConfig.DEBUG &&
            BuildConfig.WALKSAFE_RAW_QUEUE_MAX_SEGMENTS > 0 &&
            BuildConfig.WALKSAFE_RAW_QUEUE_MAX_TOTAL_BYTES > 0L,
        maxSegments = BuildConfig.WALKSAFE_RAW_QUEUE_MAX_SEGMENTS,
        maxTotalBytes = BuildConfig.WALKSAFE_RAW_QUEUE_MAX_TOTAL_BYTES,
    )

/**
 * No approved profile (every current build): "sealed 1 + active 1" (S1617-18-F1/F2). This is a
 * count, not a measured byte value: with it the upload slot needs at most
 * `RAW_NO_PROFILE_WALK_SEGMENTS × RAW_MAX_COLLECTION_BYTES` plus one transient rewrite copy.
 */
internal const val RAW_NO_PROFILE_WALK_SEGMENTS = 2

/**
 * S1617-18 / owner decision 3(b) (2026-09-29): a sealed segment now waits until its walk has ended
 * and the post-walk upload (Wi-Fi while charging) sends it under its original walk id, so segments
 * of earlier walks are uploadable and count. Without an approved profile the phone keeps at most
 * this many uploadable segments of all walks together, the ACTIVE one included: the existing
 * conservative count [RAW_NO_PROFILE_WALK_SEGMENTS] is reused as the total until the
 * GATE-PHONE-QUEUE-BYTE-LIMIT measurement approves a profile. At the bound a new segment is held
 * (NPC-PHONE-QUEUE-CAPACITY cleanup order first; an unsent, unexpired segment is never deleted for
 * space); the oldest segment is uploaded first, and every segment expires after 30 days.
 */
internal const val RAW_NO_PROFILE_RETAINED_SEGMENTS = RAW_NO_PROFILE_WALK_SEGMENTS

/**
 * No approved profile: unreadable collections moved to quarantine are outside the upload slot
 * (they can never be uploaded or decrypted) but are bounded by count. Reuses the per-walk count,
 * so quarantine can at most double the no-profile footprint. Once that many unreadable copies are
 * kept, new RAW segments are held (fail closed: repeated unreadable collections point at a key or
 * storage fault) until account deletion removes them; nothing unreadable is deleted for space.
 */
internal const val RAW_NO_PROFILE_MAX_QUARANTINED_COLLECTIONS = RAW_NO_PROFILE_WALK_SEGMENTS

/**
 * transfer-F3: what [RawCollectionStore.admitNewSegmentReportingStale] did with the unsealed
 * segment of an earlier walk whose process ended before ENDED (16-17 #13 reason record).
 */
internal enum class RawStaleSegmentOutcome {
    /** Sealed and kept for the post-walk upload under its own walk id and receipt. */
    SEALED,
    /** Could not be sealed (incomplete or invalid) and was deleted. */
    DISCARDED,
}

/** transfer-F3: the capacity decision plus the stale-segment outcome (null when there was none). */
internal data class RawSegmentAdmission(
    val capacity: RawCapacityAdmission,
    val staleSegment: RawStaleSegmentOutcome?,
)

/** Result of the pre-open capacity check, after the policy cleanup order ran. */
internal enum class RawCapacityAdmission {
    ALLOWED,
    /** No approved profile: [RAW_NO_PROFILE_RETAINED_SEGMENTS] uploadable segments are already kept. */
    HOLD_NO_APPROVED_PROFILE,
    /** No approved profile: [RAW_NO_PROFILE_MAX_QUARANTINED_COLLECTIONS] unreadable copies exist. */
    HOLD_QUARANTINE_LIMIT,
    HOLD_SEGMENT_LIMIT,
    HOLD_BYTE_LIMIT,
    /** The stored footprint could not be measured; unknown usage is never treated as free. */
    HOLD_USAGE_UNKNOWN,
}

/** Stored footprint of live and quarantined collections, in on-disk bytes. */
internal data class RawStorageUsage(
    val liveCollections: Int,
    val quarantinedCollections: Int,
    val totalBytes: Long,
)
