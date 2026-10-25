package kr.co.hanium.dreamup.walksafe.runtime.camera

import android.content.Context
import android.os.Handler
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.ar.core.ArCoreApk
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.inference.OwnedYuvFrame
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

internal enum class CameraFallbackStartReason(val fieldValue: String) {
    CAPABILITY_DISTANCE_UNAVAILABLE("capability_distance_unavailable"),
    ARCORE_AVAILABILITY_UNSUPPORTED("arcore_availability_unsupported"),
    ARCORE_DEPTH_UNSUPPORTED("arcore_depth_unsupported"),
    ARCORE_SESSION_INCOMPATIBLE("arcore_session_incompatible"),
    RUNTIME_METRIC_LOST("runtime_metric_lost"),
    DEBUG_FORCED_SUPPORTED("debug_forced_supported"),
}

internal data class CameraFallbackLease(val epoch: WalkRuntimeEpoch, val generation: Int)

internal data class CameraFallbackState(
    val requested: Boolean,
    val running: Boolean,
    val generation: Int,
    val cleanupPending: Boolean,
    val releaseBlocked: Boolean,
    val hasCamera: Boolean,
    val afterReleasePending: Boolean,
    val frameAnalyzedGeneration: Int,
)

internal sealed interface CameraFallbackEvent {
    data class Bound(val lease: CameraFallbackLease, val reason: CameraFallbackStartReason,
        val availability: ArCoreApk.Availability) : CameraFallbackEvent
    data class Stopped(val wasRequested: Boolean, val updateUi: Boolean) : CameraFallbackEvent
    data class BindFailed(val lease: CameraFallbackLease) : CameraFallbackEvent
    data class ReleaseFailed(val stoppedEpoch: WalkRuntimeEpoch?) : CameraFallbackEvent
}

/** Only copied pixels and original capture metadata can leave a scoped native image transaction. */
internal data class CameraFallbackFrameInput(
    val cameraFrame: OwnedYuvFrame,
    val imageTimestampNs: Long,
    val imageQuarterTurns: Int,
)

internal interface CameraFallbackImageAccess {
    val timestampNs: Long
    val quarterTurns: Int
    val width: Int
    val height: Int
    fun hasImage(): Boolean
    fun <T> inspectLuma(block: (ByteBuffer?, Int?, Int?) -> T): T
    fun copyOwnedFrame(): OwnedYuvFrame?
    fun close()
}

/** This handle is synchronous and expires on return; it never exposes an Image or ImageProxy. */
internal class CameraFallbackFrameCapture(image: CameraFallbackImageAccess) {
    private var image: CameraFallbackImageAccess? = image
    val imageTimestampNs = image.timestampNs
    val imageQuarterTurns = image.quarterTurns
    val width = image.width
    val height = image.height

    fun hasImage(): Boolean = image?.hasImage() == true

    fun <T> inspectLuma(block: (ByteBuffer?, Int?, Int?) -> T): T {
        return requireNotNull(image) { "camera_fallback_capture_closed" }.inspectLuma(block)
    }

    fun copyOwnedInputAndClose(): CameraFallbackFrameInput? {
        val owned = requireNotNull(image) { "camera_fallback_capture_closed" }.copyOwnedFrame() ?: return null
        close()
        return CameraFallbackFrameInput(owned, imageTimestampNs, imageQuarterTurns)
    }

    internal fun close() {
        val current = image ?: return
        current.close()
        image = null
    }
}

/** Native resource operations are the testable boundary, not an Activity-shaped host. */
internal interface CameraFallbackResources {
    val hasCamera: Boolean
    val hasAnalysis: Boolean
    val hasResources: Boolean
    fun clearAnalyzer()
    fun unbindOwned()
    fun awaitClosed(onComplete: (Boolean) -> Unit)
}

internal interface CameraFallbackSource {
    fun requestBind(
        rotation: () -> Int,
        isCurrent: () -> Boolean,
        onImage: (CameraFallbackImageAccess) -> Unit,
        onBound: (CameraFallbackResources) -> Unit,
        onFailure: (Exception) -> Unit,
    )
}

internal interface CameraFallbackScheduler {
    fun postDelayed(action: Runnable, delayMs: Long)
    fun remove(action: Runnable)
}

/** Owns CameraX fallback SDK resources, exact leases and the original drain/close transaction. */
internal class CameraFallbackController(
    private val source: CameraFallbackSource,
    private val moveLifecycle: (Lifecycle.State) -> Unit,
    private val detectorExecutor: Executor,
    private val mainExecutor: Executor,
    private val scheduler: CameraFallbackScheduler,
    private val ownerIsCurrent: (WalkRuntimeEpoch) -> Boolean,
    private val sourceMayBind: () -> Boolean,
    private val onEvent: (CameraFallbackEvent) -> Unit,
    private val onFrame: (CameraFallbackFrameCapture, CameraFallbackLease) -> Unit,
    private val callbackOwnerLive: () -> Boolean = { true },
) {
    @Volatile private var requested = false
    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var releaseBlocked = false
    @Volatile private var releaseAttempt: OwnedCameraReleaseAttempt? = null
    private val cleanupPending get() = releaseAttempt?.terminal?.get() == false
    @Volatile private var frameAnalyzedGeneration = -1
    private var startReason: CameraFallbackStartReason? = null
    private var availability: ArCoreApk.Availability? = null
    private var resources: CameraFallbackResources? = null

    val state: CameraFallbackState get() = CameraFallbackState(requested, running, generation,
        cleanupPending, releaseBlocked, resources?.hasCamera == true, releaseAttempt?.onReleased != null,
        frameAnalyzedGeneration)

    /** A failed attempt retains its uncertain native target; a later stop cannot invent close proof. */
    private class OwnedCameraReleaseAttempt(
        val resources: CameraFallbackResources,
        val stoppedEpoch: WalkRuntimeEpoch?,
        var onReleased: (() -> Unit)?,
        var onFailed: (() -> Unit)?,
    ) {
        val releaseClaim = AtomicBoolean(false)
        val terminal = AtomicBoolean(false)
        @Volatile var barrierTimedOut = false
        @Volatile var preparationFailed = false
        lateinit var deadline: Runnable
    }

    fun moveTo(state: Lifecycle.State) = moveLifecycle(state)

    fun request(reason: CameraFallbackStartReason, availability: ArCoreApk.Availability) {
        startReason = reason
        this.availability = availability
    }

    fun rejectRequest() {
        requested = false
        startReason = null
        availability = null
    }

    fun start(epoch: WalkRuntimeEpoch, rotation: () -> Int) {
        beginRequestedSession()
        bind(epoch, rotation)
    }

    fun beginRequestedSession() { requested = true }

    fun bind(epoch: WalkRuntimeEpoch, rotation: () -> Int) {
        if (!requested || running || cleanupPending || releaseBlocked || !sourceMayBind()) return
        val reason = startReason ?: return
        val available = availability ?: return
        val lease = CameraFallbackLease(epoch, ++generation)
        source.requestBind(rotation,
            isCurrent = { isCurrent(lease) && callbackOwnerLive() },
            onImage = { image -> consumeImage(image, lease) },
            onBound = { bound ->
                resources = bound
                running = true
                frameAnalyzedGeneration = -1
                onEvent(CameraFallbackEvent.Bound(lease, reason, available))
            },
            onFailure = {
                if (isCurrent(lease)) {
                    running = false
                    stop(epoch, updateUi = false, onReleased = { onEvent(CameraFallbackEvent.BindFailed(lease)) })
                }
            },
        )
    }

    fun isCurrent(lease: CameraFallbackLease, requireRunning: Boolean = false): Boolean =
        ownerIsCurrent(lease.epoch) && requested && lease.generation == generation &&
            (!requireRunning || running)

    fun consumeImage(image: CameraFallbackImageAccess, lease: CameraFallbackLease) {
        val capture = CameraFallbackFrameCapture(image)
        try {
            onFrame(capture, lease)
        } finally {
            capture.close()
        }
    }

    fun markFrameAnalyzed(lease: CameraFallbackLease): Boolean {
        if (frameAnalyzedGeneration == lease.generation || !isCurrent(lease, requireRunning = true)) return false
        frameAnalyzedGeneration = lease.generation
        return true
    }

    fun clearPendingContinuation() { releaseAttempt?.onReleased = null }

    fun markReleaseBlocked() { releaseBlocked = true }

    /** Official camera preflight shares the same native slot; detach before its existing cleanup. */
    fun detachOwnedResources(): CameraFallbackResources? = resources.also { resources = null }

    fun bindPreflightCamera(provider: ProcessCameraProvider, analysis: ImageAnalysis) {
        val native = source as? CameraXFallbackSource ?: error("camera_fallback_native_source_required")
        native.bindPreflight(provider, analysis) { resources = it }
    }

    fun stop(
        stoppedEpoch: WalkRuntimeEpoch?,
        updateUi: Boolean = true,
        onReleased: (() -> Unit)? = null,
        onReleaseFailed: (() -> Unit)? = null,
    ) {
        val pending = releaseAttempt
        if (pending != null && !pending.terminal.get() && (onReleased != null || onReleaseFailed != null)) {
            pending.onReleased = onReleased
            pending.onFailed = onReleaseFailed
        }
        val wasRequested = requested
        val owned = detachOwnedResources()
        requested = false
        running = false
        generation += 1
        frameAnalyzedGeneration = -1
        startReason = null
        availability = null
        onEvent(CameraFallbackEvent.Stopped(wasRequested, updateUi))
        if (owned?.hasResources != true) {
            if (!cleanupPending) {
                if (releaseBlocked) {
                    if (onReleaseFailed != null) onReleaseFailed() else onEvent(CameraFallbackEvent.ReleaseFailed(stoppedEpoch))
                } else onReleased?.invoke()
            }
            return
        }
        val attempt = OwnedCameraReleaseAttempt(owned, stoppedEpoch, onReleased, onReleaseFailed)
        releaseAttempt = attempt
        runCatching { owned.clearAnalyzer() }.onFailure { attempt.preparationFailed = true }
        val release = Runnable {
            if (releaseAttempt !== attempt || attempt.terminal.get() || !attempt.releaseClaim.compareAndSet(false, true)) return@Runnable
            try {
                scheduler.remove(attempt.deadline)
                owned.unbindOwned()
                runCatching { moveLifecycle(Lifecycle.State.CREATED) }
                    .onFailure { attempt.preparationFailed = true }
                owned.awaitClosed { closed ->
                    finishRelease(attempt, closed && !attempt.barrierTimedOut && !attempt.preparationFailed)
                }
            } catch (_: RuntimeException) {
                finishRelease(attempt, released = false)
            }
        }
        attempt.deadline = Runnable { attempt.barrierTimedOut = true; release.run() }
        fun dispatchRelease() {
            try { mainExecutor.execute(release) }
            catch (_: RejectedExecutionException) { finishRelease(attempt, released = false) }
        }
        if (!owned.hasAnalysis) {
            dispatchRelease()
            return
        }
        try { scheduler.postDelayed(attempt.deadline, ANALYZER_RELEASE_BARRIER_TIMEOUT_MS) }
        catch (_: RuntimeException) { finishRelease(attempt, released = false); return }
        try {
            detectorExecutor.execute { dispatchRelease() }
        } catch (_: RejectedExecutionException) {
            attempt.barrierTimedOut = true
            dispatchRelease()
        }
    }

    private fun finishRelease(attempt: OwnedCameraReleaseAttempt, released: Boolean) {
        if (releaseAttempt !== attempt || !attempt.terminal.compareAndSet(false, true)) return
        runCatching { scheduler.remove(attempt.deadline) }
        val completed = attempt.onReleased
        val failed = attempt.onFailed
        attempt.onReleased = null
        attempt.onFailed = null
        if (released) releaseAttempt = null else releaseBlocked = true
        // Publish terminal ownership before user code can reenter stop/start.
        if (released) completed?.invoke()
        else if (failed != null) failed() else onEvent(CameraFallbackEvent.ReleaseFailed(attempt.stoppedEpoch))
    }

    companion object {
        private const val ANALYZER_RELEASE_BARRIER_TIMEOUT_MS = 2_000L

        fun android(
            context: () -> Context,
            surfaceProvider: () -> Preview.SurfaceProvider,
            detectorExecutor: Executor,
            mainExecutor: Executor,
            releaseHandler: Handler,
            awaitClosed: (Camera?, (Boolean) -> Unit) -> Unit,
            ownerIsCurrent: (WalkRuntimeEpoch) -> Boolean,
            sourceMayBind: () -> Boolean,
            onEvent: (CameraFallbackEvent) -> Unit,
            onFrame: (CameraFallbackFrameCapture, CameraFallbackLease) -> Unit,
            callbackOwnerLive: () -> Boolean,
        ): CameraFallbackController {
            val lifecycle = CameraFallbackLifecycleOwner()
            val source = CameraXFallbackSource(context, lifecycle, surfaceProvider, detectorExecutor,
                mainExecutor, awaitClosed)
            return CameraFallbackController(source, lifecycle::moveTo, detectorExecutor, mainExecutor,
                object : CameraFallbackScheduler {
                    override fun postDelayed(action: Runnable, delayMs: Long) { releaseHandler.postDelayed(action, delayMs) }
                    override fun remove(action: Runnable) { releaseHandler.removeCallbacks(action) }
                }, ownerIsCurrent, sourceMayBind, onEvent, onFrame, callbackOwnerLive)
        }
    }
}

private class CameraFallbackLifecycleOwner : LifecycleOwner {
    private val registry by lazy { LifecycleRegistry(this) }
    override val lifecycle: Lifecycle get() = registry
    fun moveTo(state: Lifecycle.State) { registry.currentState = state }
}

private class CameraXFallbackResources(
    private val provider: ProcessCameraProvider,
    var camera: Camera?,
    private val analysis: ImageAnalysis,
    private val preview: Preview?,
    private val closed: (Camera?, (Boolean) -> Unit) -> Unit,
) : CameraFallbackResources {
    override val hasCamera get() = camera != null
    override val hasAnalysis = true
    override val hasResources = true
    override fun clearAnalyzer() { analysis.clearAnalyzer() }
    override fun unbindOwned() {
        runCatching { provider.unbind(analysis) }
        preview?.let { runCatching { provider.unbind(it) } }
    }
    override fun awaitClosed(onComplete: (Boolean) -> Unit) { closed(camera, onComplete) }
}

private class CameraXFallbackSource(
    private val context: () -> Context,
    private val lifecycle: LifecycleOwner,
    private val surfaceProvider: () -> Preview.SurfaceProvider,
    private val detectorExecutor: Executor,
    private val mainExecutor: Executor,
    private val awaitClosed: (Camera?, (Boolean) -> Unit) -> Unit,
) : CameraFallbackSource {
    @androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
    override fun requestBind(rotation: () -> Int, isCurrent: () -> Boolean,
        onImage: (CameraFallbackImageAccess) -> Unit, onBound: (CameraFallbackResources) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val future = ProcessCameraProvider.getInstance(context())
        future.addListener({
            if (!isCurrent()) return@addListener
            try {
                val provider = future.get()
                val resolution = ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(
                    Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
                val analysis = ImageAnalysis.Builder().setResolutionSelector(resolution).setTargetRotation(rotation())
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setOutputImageRotationEnabled(true)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                val preview = Preview.Builder().setResolutionSelector(resolution).setTargetRotation(rotation()).build()
                preview.setSurfaceProvider(surfaceProvider())
                analysis.setAnalyzer(detectorExecutor) { proxy -> onImage(ImageProxyFallbackAccess(proxy)) }
                val camera = provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                onBound(CameraXFallbackResources(provider, camera, analysis, preview, awaitClosed))
            } catch (failure: Exception) { onFailure(failure) }
        }, mainExecutor)
    }

    fun bindPreflight(provider: ProcessCameraProvider, analysis: ImageAnalysis,
        onPrepared: (CameraFallbackResources) -> Unit,
    ) {
        val resources = CameraXFallbackResources(provider, null, analysis, null, awaitClosed)
        onPrepared(resources)
        resources.camera = provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
private class ImageProxyFallbackAccess(private val proxy: ImageProxy) : CameraFallbackImageAccess {
    override val timestampNs get() = proxy.imageInfo.timestamp
    override val quarterTurns get() = (proxy.imageInfo.rotationDegrees / 90) % 4
    override val width get() = proxy.width
    override val height get() = proxy.height
    private var observedImage = false
    private var image: android.media.Image? = null
    override fun hasImage(): Boolean {
        if (!observedImage) { image = proxy.image; observedImage = true }
        return image != null
    }
    override fun <T> inspectLuma(block: (ByteBuffer?, Int?, Int?) -> T): T {
        val plane = proxy.planes.firstOrNull()
        return block(plane?.buffer, plane?.rowStride, plane?.pixelStride)
    }
    override fun copyOwnedFrame(): OwnedYuvFrame? {
        if (!hasImage()) return null
        return OwnedYuvFrame.copyOf(requireNotNull(image), proxy.cropRect)
    }
    override fun close() { proxy.close(); image = null }
}
