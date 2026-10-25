package kr.co.hanium.dreamup.walksafe

import android.media.Image
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownDepthFrameResult
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownObjectDepthPipeline
import kr.co.hanium.dreamup.walksafe.depth.unknown.sameCapture
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointTracker
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointCaptureLease
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointTrackingResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingConfig
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask

/** A capture retains its original camera/service/geometry scope, never a replacement's tracker. */
internal class CameraUnknownMaterialPointScope(
    val cameraEpoch: Int,
    val serviceEpoch: Long,
    val geometryGeneration: Long,
    val geometryId: String,
    val pipeline: UnknownObjectDepthPipeline,
    val tracker: UnknownMaterialPointTracker,
) {
    private val active = AtomicBoolean(true)
    private val flights = ConcurrentHashMap<UnknownMaterialPointCaptureLease, CameraUnknownMaterialPointFlight>()
    val accepts: Boolean get() = active.get()
    fun deactivate() {
        active.set(false)
        flights.values.forEach { cancel(it) }
    }

    fun register(lease: UnknownMaterialPointCaptureLease): CameraUnknownMaterialPointFlight? {
        val flight = CameraUnknownMaterialPointFlight(lease, this)
        flights[lease] = flight
        if (!accepts) { cancel(flight); return null }
        return flight
    }

    fun owns(flight: CameraUnknownMaterialPointFlight) = flights[flight.lease] === flight
    fun complete(flight: CameraUnknownMaterialPointFlight) {
        if (flights.remove(flight.lease, flight)) flight.lease.close()
    }
    fun cancel(flight: CameraUnknownMaterialPointFlight) {
        if (flight.isOwnedBy(this)) {
            flights.remove(flight.lease, flight)
            flight.cancel()
        }
    }
    fun cancel(token: FastSamFrameToken) {
        flights.values.filter { sameCapture(it.lease.token, token) }.forEach { cancel(it) }
    }
}

/** Request cancellation is separate from gray release: successful consume also closes its lease. */
internal class CameraUnknownMaterialPointFlight(val lease: UnknownMaterialPointCaptureLease,
                                              private val owner: CameraUnknownMaterialPointScope) {
    private val cancelled = AtomicBoolean(false)
    val accepts: Boolean get() = !cancelled.get()
    fun isOwnedBy(scope: CameraUnknownMaterialPointScope) = owner === scope
    fun cancel() { cancelled.set(true); lease.close() }
}

internal data class CameraUnknownMaterialPointResult(
    val depth: UnknownDepthFrameResult,
    val tracking: UnknownMaterialPointTrackingResult,
)

/** Shared by the actual render/feedback selection boundaries; clear never stops accepted actuators. */
internal fun discardRetiredCameraUnknownOverlay(
    scope: CameraUnknownMaterialPointScope?,
    isCurrent: (CameraUnknownMaterialPointScope) -> Boolean,
    clear: () -> Unit,
): Boolean {
    if (scope == null || (scope.accepts && isCurrent(scope))) return false
    clear()
    return true
}

/** Only short publication locks run on UI/GL. Native tracker cleanup stays on the image owner queue. */
internal class CameraUnknownMaterialPointOwner(
    private val enqueueOwner: (() -> Unit) -> Unit,
    private val config: VisualTrackingConfig = VisualTrackingConfig(),
    private val clockNanos: () -> Long = System::nanoTime,
    private val createTracker: () -> UnknownMaterialPointTracker = { UnknownMaterialPointTracker(config, clockNanos) },
) {
    private val publicationLock = Any()
    @Volatile private var current: CameraUnknownMaterialPointScope? = null

    fun acquireScope(cameraEpoch: Int, serviceEpoch: Long, geometryGeneration: Long,
                     pipeline: UnknownObjectDepthPipeline, geometryId: String,
                     isCurrent: () -> Boolean): CameraUnknownMaterialPointScope? {
        if (!isCurrent()) return null
        var retired: CameraUnknownMaterialPointScope? = null
        val scope = synchronized(publicationLock) {
            if (!isCurrent()) return null
            current?.takeIf { it.accepts && it.cameraEpoch == cameraEpoch && it.serviceEpoch == serviceEpoch &&
                it.geometryGeneration == geometryGeneration && it.geometryId == geometryId && it.pipeline === pipeline } ?: run {
                retired = current?.also { it.deactivate() }
                CameraUnknownMaterialPointScope(cameraEpoch, serviceEpoch, geometryGeneration, geometryId, pipeline, createTracker())
                    .also { current = it }
            }
        }
        retired?.let { old -> enqueueOwner { old.tracker.reset() } }
        return scope
    }

    fun invalidate(serviceEpoch: Long? = null, isCurrent: () -> Boolean = { true }) {
        val retired = synchronized(publicationLock) {
            current?.takeIf { isCurrent() && (serviceEpoch == null || it.serviceEpoch == serviceEpoch) }?.also {
                it.deactivate()
                current = null
            }
        }
        retired?.let { old -> enqueueOwner { old.tracker.reset() } }
    }

    private fun owns(scope: CameraUnknownMaterialPointScope, isCurrent: () -> Boolean) =
        scope.accepts && current === scope && isCurrent()

    fun qualifyFrame(cameraImageTimestampNs: Long?, isCurrent: () -> Boolean): Boolean {
        if (!isCurrent()) return false
        if (cameraImageTimestampNs == null) {
            invalidate(isCurrent = isCurrent)
            return false
        }
        return true
    }

    /** Called for every already acquired qualified Image, before FastSAM submission pacing. */
    fun offerImage(scope: CameraUnknownMaterialPointScope, token: FastSamFrameToken, turns: Int,
                   image: Image, nowMs: Long, isCurrent: () -> Boolean): Boolean {
        if (token.sessionEpoch != scope.serviceEpoch || token.geometryId != scope.geometryId || !owns(scope, isCurrent)) return false
        return synchronized(scope.tracker) {
            if (!owns(scope, isCurrent)) return false
            val plane = image.planes[0]
            scope.tracker.offerLuma(token, turns, nowMs, image.timestamp, image.width, image.height,
                plane.buffer, plane.rowStride, plane.pixelStride).accepted
        }
    }

    fun process(scope: CameraUnknownMaterialPointScope, capture: FrozenUnknownDepthCapture,
                token: FastSamFrameToken, turns: Int, masks: List<InstanceMask>, nowMs: Long,
                callbackStartedNs: Long, flight: CameraUnknownMaterialPointFlight? = null,
                isCurrent: () -> Boolean): CameraUnknownMaterialPointResult? {
        if (flight != null && !scope.owns(flight)) return null // Never release a foreign request's lease.
        try {
            if (token.sessionEpoch != scope.serviceEpoch || token.geometryId != scope.geometryId || !sameCapture(capture.token, token) ||
                !owns(scope, isCurrent) || flight?.accepts == false) return null
            val tracked = synchronized(scope.tracker) {
                if (!owns(scope, isCurrent) || flight?.accepts == false) return null
                // The original callback allowance includes time waiting for this tracker lock.
                val remaining = (config.maxExecutionNs - (clockNanos() - callbackStartedNs).coerceAtLeast(0L))
                    .coerceAtLeast(0L)
                scope.tracker.consume(token, turns, masks, nowMs, remaining, flight?.lease)
            }
            if (!owns(scope, isCurrent) || flight?.accepts == false) return null
            val depth = scope.pipeline.process(capture, token, masks, nowMs,
                materialPointsByMaskIndex = tracked.materialPointsByMaskIndex)
            if (!owns(scope, isCurrent) || flight?.accepts == false) return null
            return CameraUnknownMaterialPointResult(depth, tracked)
        } finally { flight?.let { scope.complete(it) } }
    }

    fun reserveCapture(scope: CameraUnknownMaterialPointScope, token: FastSamFrameToken, turns: Int,
                       nowMs: Long, isCurrent: () -> Boolean): CameraUnknownMaterialPointFlight? {
        if (token.sessionEpoch != scope.serviceEpoch || token.geometryId != scope.geometryId || !owns(scope, isCurrent)) return null
        return synchronized(scope.tracker) {
            if (!owns(scope, isCurrent)) return null
            val lease = scope.tracker.reserveCapture(token, turns, nowMs).lease ?: return null
            scope.register(lease)
        }
    }

    fun cancelFlight(scope: CameraUnknownMaterialPointScope, flight: CameraUnknownMaterialPointFlight,
                     isCurrent: () -> Boolean) {
        scope.cancel(flight) // Atomic release; Image/native-flight ownership stays with its existing owner.
        if (flight.isOwnedBy(scope)) cancelCapture(flight.lease.token) { it === scope && isCurrent() }
    }

    /** Discard callbacks may arrive on UI. Capture the owner now and cancel only on its worker queue. */
    fun cancelCapture(token: FastSamFrameToken, isCurrent: (CameraUnknownMaterialPointScope) -> Boolean) {
        val scope = current ?: return
        if (token.sessionEpoch != scope.serviceEpoch || !owns(scope) { isCurrent(scope) }) return
        scope.cancel(token) // Does not wait for a running consume, including UI discard callbacks.
        enqueueOwner {
            synchronized(scope.tracker) {
                if (owns(scope) { isCurrent(scope) }) scope.tracker.cancelCapture(token)
            }
        }
    }
}
