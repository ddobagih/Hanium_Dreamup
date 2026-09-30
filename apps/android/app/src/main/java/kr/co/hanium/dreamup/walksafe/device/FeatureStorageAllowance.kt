package kr.co.hanium.dreamup.walksafe.device

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import java.io.File
import java.util.EnumMap

/**
 * Storage-dependent features that may be held quietly without touching the walk (14 #39,
 * NPC-PHONE-QUEUE-CAPACITY rules[1..4]). Explicit reports, consent, deletion and security records
 * are deliberately not members: they are protected and never held for space here.
 */
enum class StorageDependentFeature {
    RAW_COLLECTION,
    AUTOMATIC_REPORT_CANDIDATE,
}

enum class FeatureStorageHoldReason {
    /** The OS reports less allocatable space than the next write needs, temp copy included. */
    INSUFFICIENT_ALLOCATABLE_SPACE,
    /** The OS query failed; unknown space is never treated as enough. */
    ALLOCATABLE_SPACE_UNKNOWN,
    /** A write that passed the space check still failed. */
    IO_FAILURE,
}

/**
 * `canWrite()` and the system low-storage broadcast say nothing about the next write. The
 * admission compares the OS allocatable bytes with the feature's own projected write, where the
 * projection is the feature's existing maximum item size plus one temporary copy used by the
 * atomic temp-write + rename protocol. No device threshold is invented here: the OS allocatable
 * value already excludes the space the system keeps reserved (GATE-PHONE-QUEUE-BYTE-LIMIT).
 */
object FeatureStorageAllowance {
    fun projectedWriteBytes(maxItemBytes: Long): Long {
        require(maxItemBytes > 0L)
        return if (Long.MAX_VALUE / 2L < maxItemBytes) Long.MAX_VALUE else maxItemBytes * 2L
    }

    /** Null when the write may proceed; otherwise the hold reason. */
    fun holdReason(allocatableBytes: Long?, maxItemBytes: Long): FeatureStorageHoldReason? = when {
        allocatableBytes == null || allocatableBytes < 0L ->
            FeatureStorageHoldReason.ALLOCATABLE_SPACE_UNKNOWN
        allocatableBytes < projectedWriteBytes(maxItemBytes) ->
            FeatureStorageHoldReason.INSUFFICIENT_ALLOCATABLE_SPACE
        else -> null
    }
}

/**
 * In-memory, internal-only record of feature holds: closed enum keys and counts, no user
 * message, no walk state change. A later successful write of the same feature clears its hold.
 */
class FeatureStorageHolds {
    private val current = EnumMap<StorageDependentFeature, FeatureStorageHoldReason>(
        StorageDependentFeature::class.java,
    )
    private val counts = mutableMapOf<Pair<StorageDependentFeature, FeatureStorageHoldReason>, Long>()

    @Synchronized
    fun record(feature: StorageDependentFeature, reason: FeatureStorageHoldReason) {
        current[feature] = reason
        val key = feature to reason
        counts[key] = (counts[key] ?: 0L).let { if (it == Long.MAX_VALUE) it else it + 1L }
    }

    @Synchronized
    fun clear(feature: StorageDependentFeature) {
        current.remove(feature)
    }

    @Synchronized
    fun currentHold(feature: StorageDependentFeature): FeatureStorageHoldReason? = current[feature]

    @Synchronized
    fun count(feature: StorageDependentFeature, reason: FeatureStorageHoldReason): Long =
        counts[feature to reason] ?: 0L

    @Synchronized
    fun snapshot(): Map<String, Long> = counts.entries.associate { (key, value) ->
        "storage_hold_${key.first.name.lowercase()}_${key.second.name.lowercase()}" to value
    }
}

/** OS allocatable bytes of the volume holding [directory]; null when the query fails. */
class AndroidAllocatableBytes(
    context: Context,
    private val directory: File,
) : () -> Long? {
    private val storageManager = context.applicationContext.getSystemService(StorageManager::class.java)

    override fun invoke(): Long? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = requireNotNull(storageManager)
            manager.getAllocatableBytes(manager.getUuidForPath(directory))
        } else {
            directory.usableSpace
        }
    }.getOrNull()?.takeIf { it >= 0L }
}
