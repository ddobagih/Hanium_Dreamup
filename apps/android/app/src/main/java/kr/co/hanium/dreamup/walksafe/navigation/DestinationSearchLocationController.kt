package kr.co.hanium.dreamup.walksafe.navigation

data class DestinationSearchLocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float?,
    val elapsedRealtimeMs: Long,
    val mock: Boolean = false,
)

data class DestinationSearchLocationLease(val actorId: String, val sessionGeneration: Long)

/** Search centering only. These fixes must not become walking or obstacle evidence. */
object DestinationSearchLocationPolicy {
    const val MAX_AGE_MS = 30_000L
    const val MAX_ACCURACY_M = 100f
    const val REQUEST_TIMEOUT_MS = 10_000L

    fun originOrNull(fix: DestinationSearchLocationFix?, nowElapsedRealtimeMs: Long): RoutePoint? {
        if (fix == null || fix.mock) return null
        if (!fix.latitude.isFinite() || fix.latitude !in -90.0..90.0) return null
        if (!fix.longitude.isFinite() || fix.longitude !in -180.0..180.0) return null
        val accuracy = fix.accuracyM ?: return null
        if (!accuracy.isFinite() || accuracy !in 0f..MAX_ACCURACY_M) return null
        if (fix.elapsedRealtimeMs < 0L || nowElapsedRealtimeMs < fix.elapsedRealtimeMs) return null
        if (nowElapsedRealtimeMs - fix.elapsedRealtimeMs > MAX_AGE_MS) return null
        return RoutePoint(fix.latitude, fix.longitude)
    }
}

fun interface DestinationSearchLocationSource {
    /** Starts one bounded request and returns a cancellation action. Null means unavailable. */
    fun request(onResult: (DestinationSearchLocationFix?) -> Unit): () -> Unit
}

/** Main-thread owner for search fixes; cancellation also discards the old account's cache. */
class DestinationSearchLocationController(
    private val source: DestinationSearchLocationSource,
    private val nowElapsedRealtimeMs: () -> Long,
) {
    private var generation = 0L
    private var lease: DestinationSearchLocationLease? = null
    private var fix: DestinationSearchLocationFix? = null
    private var cancelRequest: (() -> Unit)? = null
    private var completion: ((RoutePoint?) -> Unit)? = null
    private var allowed: (() -> Boolean)? = null
    var isAcquiring = false
        private set

    fun originOrNull(expectedLease: DestinationSearchLocationLease): RoutePoint? {
        if (lease != expectedLease) {
            cancel()
            lease = expectedLease
        }
        return DestinationSearchLocationPolicy.originOrNull(fix, nowElapsedRealtimeMs())
    }

    fun acquire(
        expectedLease: DestinationSearchLocationLease,
        stillAllowed: () -> Boolean,
        onResult: (RoutePoint?) -> Unit,
    ): Boolean {
        if (!stillAllowed()) {
            cancel()
            return false
        }
        originOrNull(expectedLease)?.let {
            onResult(it)
            return true
        }
        completion = onResult
        allowed = stillAllowed
        if (isAcquiring) return true
        isAcquiring = true
        val requestGeneration = ++generation
        val cancellation = source.request { candidate ->
            if (!isAcquiring || generation != requestGeneration || lease != expectedLease) return@request
            if (allowed?.invoke() != true) {
                cancel()
                return@request
            }
            val origin = DestinationSearchLocationPolicy.originOrNull(candidate, nowElapsedRealtimeMs())
            fix = candidate.takeIf { origin != null }
            isAcquiring = false
            cancelRequest = null
            val callback = completion
            completion = null
            allowed = null
            callback?.invoke(origin)
        }
        if (isAcquiring && generation == requestGeneration) cancelRequest = cancellation else cancellation()
        return true
    }

    fun cancel() {
        generation += 1L
        isAcquiring = false
        fix = null
        lease = null
        completion = null
        allowed = null
        val cancellation = cancelRequest
        cancelRequest = null
        cancellation?.invoke()
    }
}
