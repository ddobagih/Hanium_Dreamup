package kr.co.hanium.dreamup.walksafe.device.environment

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Looper
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.device.CameraLumaMeasurementPolicy
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckLocationFixPolicy
import kr.co.hanium.dreamup.walksafe.device.OfficialEnvironmentGpsSample
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingSensorMeasurement
import kr.co.hanium.dreamup.walksafe.runtime.camera.CameraFallbackController
import kr.co.hanium.dreamup.walksafe.runtime.camera.CameraFallbackResources
import kr.co.hanium.dreamup.walksafe.session.ApprovedOfficialEnvironmentProfile
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** The common ARCore/CameraX observation formula. The Y plane is used only inside this call. */
internal object OfficialEnvironmentCameraObservationFactory {
    fun build(
        epoch: WalkRuntimeEpoch,
        observedAtElapsedRealtimeMs: Long,
        frameAvailable: Boolean?,
        width: Int? = null,
        height: Int? = null,
        yPlaneBuffer: ByteBuffer? = null,
        yPlaneRowStride: Int? = null,
        yPlanePixelStride: Int? = null,
        mountingMeasurement: () -> PhoneMountingSensorMeasurement?,
    ): CameraFrameQualityObservation {
        val luma = if (frameAvailable == true && width != null && height != null &&
            yPlaneBuffer != null && yPlaneRowStride != null && yPlanePixelStride != null
        ) CameraLumaMeasurementPolicy.measure(yPlaneBuffer, width, height, yPlaneRowStride, yPlanePixelStride)
        else null
        // Sensor freshness uses its own latestNow clock after luma measurement, as before.
        val mounting = if (frameAvailable == true) mountingMeasurement() else null
        return CameraFrameQualityObservation(
            epoch = epoch, observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
            frameAvailable = frameAvailable, normalizedBrightness = luma?.normalizedBrightness,
            occludedFraction = null, nearBlackFraction = luma?.nearBlackFraction,
            angularShakeDegreesPerSecond = mounting?.angularShakeDegreesPerSecond,
            mountPitchDegrees = mounting?.cameraPitchFromHorizontalDegrees,
        )
    }
}

/** Each check stays lazy so the first rejection preserves the original source-read order. */
internal interface OfficialEnvironmentCameraAdmission {
    fun cameraPermission(): Boolean
    fun releaseBlocked(): Boolean
    fun postLoginCameraActive(): Boolean
    fun postLoginCameraCleanupPending(): Boolean
    fun postLoginMetricCheckActive(): Boolean
    fun arCameraBusy(): Boolean
    fun fallbackCleanupPending(): Boolean
    fun fallbackRequested(): Boolean
}

internal interface OfficialEnvironmentMountingSource {
    fun start(): Boolean
    fun stopIfInitialized()
    fun latestNow(): PhoneMountingSensorMeasurement?
}

/** Owns the single CameraFallbackController native slot; this source never duplicates its state. */
internal interface OfficialEnvironmentSharedCameraSlot {
    fun resumeLifecycle()
    fun moveLifecycleToCreatedIfInitialized()
    fun bindPreflightCamera(provider: ProcessCameraProvider, analysis: ImageAnalysis)
    fun detachOwnedResources(): OfficialEnvironmentCameraResources?
    fun awaitClosed(resources: OfficialEnvironmentCameraResources?, completion: (Boolean) -> Unit)
    fun markReleaseBlocked()
}

/** A synchronous analyzer access. No ImageProxy/Image/plane buffer is retained or queued. */
internal interface OfficialEnvironmentCameraImage {
    val width: Int
    val height: Int
    fun hasImage(): Boolean
    fun <T> withLuma(block: (ByteBuffer?, Int?, Int?) -> T): T
    fun close()
}

internal interface OfficialEnvironmentCameraPlatform {
    fun requestBind(isCurrent: () -> Boolean,
        onImage: (OfficialEnvironmentCameraImage) -> Unit, onFailure: () -> Unit)
}

/** The controller owns generation, interval, timeout, stability, cleanup and atomic evidence commit. */
internal class AndroidOfficialEnvironmentCameraSource(
    private val platform: OfficialEnvironmentCameraPlatform,
    private val admission: OfficialEnvironmentCameraAdmission,
    private val mounting: OfficialEnvironmentMountingSource,
    private val slot: OfficialEnvironmentSharedCameraSlot,
    private val detectorExecutor: Executor,
    private val runOnUiThread: (Runnable) -> Unit,
    private val nowMs: () -> Long,
    private val observe: (CameraFrameQualityObservation, Long) -> Unit,
) : OfficialEnvironmentCameraSource {
    override fun startFailureReason(): String? = when {
        !admission.cameraPermission() -> "CAMERA_PERMISSION_MISSING"
        admission.releaseBlocked() -> "PREVIOUS_CAMERA_RELEASE_TIMED_OUT"
        admission.postLoginCameraActive() -> "POST_LOGIN_CAMERA_CHECK_ACTIVE"
        admission.postLoginCameraCleanupPending() -> "POST_LOGIN_CAMERA_CHECK_CLEANUP_PENDING"
        admission.postLoginMetricCheckActive() -> "POST_LOGIN_METRIC_CHECK_ACTIVE"
        admission.arCameraBusy() -> "AR_CAMERA_PIPELINE_BUSY"
        admission.fallbackCleanupPending() -> "CAMERA_PIPELINE_CLEANUP_PENDING"
        admission.fallbackRequested() -> "CAMERA_PIPELINE_BUSY"
        !mounting.start() -> "MOUNTING_SENSOR_UNAVAILABLE"
        else -> null
    }

    override fun resumeLifecycle() = slot.resumeLifecycle()

    override fun start(epoch: WalkRuntimeEpoch, generation: Long, isCurrent: () -> Boolean,
        acceptAnalysisAt: (Long) -> Boolean, onBindFailed: () -> Unit) {
        platform.requestBind(isCurrent,
            onImage = image@{ image ->
                try {
                    // Original analyzer takes this clock reading even when its actor has retired.
                    val now = nowMs()
                    if (!isCurrent() || !acceptAnalysisAt(now)) return@image
                    val observation = image.withLuma { buffer, rowStride, pixelStride ->
                        OfficialEnvironmentCameraObservationFactory.build(epoch, now, image.hasImage(),
                            image.width, image.height, buffer, rowStride, pixelStride, mounting::latestNow)
                    }
                    observe(observation, generation)
                } finally { image.close() }
            },
            onFailure = { if (isCurrent()) onBindFailed() },
        )
    }

    override fun stopMountingSensorIfInitialized() = mounting.stopIfInitialized()
    override fun detachOwnedResources() = slot.detachOwnedResources()
    override fun moveLifecycleToCreatedIfInitialized() = slot.moveLifecycleToCreatedIfInitialized()
    override fun awaitClosed(resources: OfficialEnvironmentCameraResources?, completion: (Boolean) -> Unit) =
        slot.awaitClosed(resources, completion)
    override fun markSharedReleaseBlocked() = slot.markReleaseBlocked()
    override fun awaitAnalyzerBarrier(release: Runnable, onRejected: () -> Unit) {
        try { detectorExecutor.execute { runOnUiThread(release) } }
        catch (_: RejectedExecutionException) { onRejected() }
    }

    companion object {
        fun android(
            context: () -> Context,
            fallbackController: () -> CameraFallbackController,
            fallbackInitialized: () -> Boolean,
            displayRotation: () -> Int,
            detectorExecutor: Executor,
            mainExecutor: () -> Executor,
            runOnUiThread: (Runnable) -> Unit,
            nowMs: () -> Long,
            admission: OfficialEnvironmentCameraAdmission,
            mounting: OfficialEnvironmentMountingSource,
            observe: (CameraFrameQualityObservation, Long) -> Unit,
        ): AndroidOfficialEnvironmentCameraSource {
            val slot = AndroidOfficialEnvironmentSharedCameraSlot(fallbackController, fallbackInitialized)
            return AndroidOfficialEnvironmentCameraSource(
                AndroidOfficialEnvironmentCameraPlatform(context, displayRotation, detectorExecutor, mainExecutor, slot),
                admission, mounting, slot, detectorExecutor, runOnUiThread, nowMs, observe,
            )
        }
    }
}

/** Keeps the original native nanosecond order before converting each sample to milliseconds. */
internal data class OfficialEnvironmentNativeGpsSample(
    val observedAtElapsedRealtimeNs: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Double?,
    val mock: Boolean,
) {
    fun observation() = OfficialEnvironmentGpsSample(observedAtElapsedRealtimeNs / 1_000_000L,
        latitude, longitude, horizontalAccuracyMeters, mock)
}

internal data class OfficialEnvironmentGpsRequestOptions(
    val intervalMs: Long = 1_000L,
    val minimumIntervalMs: Long = 500L,
    val maximumAgeMs: Long,
    val maximumDurationMs: Long,
)

internal interface OfficialEnvironmentGpsRegistration { fun start(); fun cancel() }

internal interface OfficialEnvironmentGpsPlatform {
    fun createRegistration(options: OfficialEnvironmentGpsRequestOptions,
        isCurrent: () -> Boolean,
        onSamples: (List<OfficialEnvironmentNativeGpsSample>) -> Unit,
        onTemporarilyUnavailable: () -> Unit,
        onRegistered: () -> Unit,
        onFailure: () -> Unit): OfficialEnvironmentGpsRegistration
}

/** No location client is read and no callback is registered before request.start(). */
internal class AndroidOfficialEnvironmentGpsSource(
    private val platform: OfficialEnvironmentGpsPlatform,
    private val maximumDurationMs: Long,
) : OfficialEnvironmentGpsSource {
    override fun createRequest(profile: ApprovedOfficialEnvironmentProfile?, isCurrent: () -> Boolean,
        onSample: (OfficialEnvironmentGpsSample) -> Unit, onTemporarilyUnavailable: () -> Unit,
        onFailure: () -> Unit): OfficialEnvironmentGpsRequest {
        val options = OfficialEnvironmentGpsRequestOptions(
            maximumAgeMs = minOf(DeviceCheckLocationFixPolicy.MAX_AGE_MS, profile?.maximumMeasuredEvidenceAgeMs ?: 0L),
            maximumDurationMs = maximumDurationMs,
        )
        return object : OfficialEnvironmentGpsRequest {
            private var started = false
            private var cancelled = false
            private var registration: OfficialEnvironmentGpsRegistration? = null
            private fun current() = !cancelled && isCurrent()
            private fun removeRegistration() {
                registration?.cancel()
            }
            override fun start() {
                if (started || cancelled) return
                started = true
                try {
                    val created = platform.createRegistration(options, ::current,
                        onSamples = { samples ->
                            if (current()) samples.sortedBy { it.observedAtElapsedRealtimeNs }.forEach {
                                if (current()) onSample(it.observation())
                            }
                        },
                        onTemporarilyUnavailable = { if (current()) onTemporarilyUnavailable() },
                        onRegistered = { if (!current()) removeRegistration() },
                        onFailure = { if (current()) onFailure() },
                    )
                    // Install the cancellation handle before the SDK can dispatch inline callbacks.
                    registration = created
                    created.start()
                } catch (_: RuntimeException) { onFailure() }
            }
            override fun cancel() {
                cancelled = true
                removeRegistration()
            }
        }
    }

    companion object {
        fun android(client: () -> FusedLocationProviderClient, mainExecutor: () -> Executor,
            maximumDurationMs: Long): AndroidOfficialEnvironmentGpsSource = AndroidOfficialEnvironmentGpsSource(
                AndroidOfficialEnvironmentGpsPlatform(client, mainExecutor), maximumDurationMs)
    }
}

private class AndroidOfficialEnvironmentSharedCameraResources(
    val owned: CameraFallbackResources,
) : OfficialEnvironmentCameraResources {
    override val hasAnalysis get() = owned.hasAnalysis
    override val hasCamera get() = owned.hasCamera
    override fun clearAnalyzer() = owned.clearAnalyzer()
    override fun unbindOwned() = owned.unbindOwned()
}

private class AndroidOfficialEnvironmentSharedCameraSlot(
    private val controller: () -> CameraFallbackController,
    private val initialized: () -> Boolean,
) : OfficialEnvironmentSharedCameraSlot {
    override fun resumeLifecycle() = controller().moveTo(Lifecycle.State.RESUMED)
    override fun moveLifecycleToCreatedIfInitialized() {
        if (initialized()) controller().moveTo(Lifecycle.State.CREATED)
    }
    override fun bindPreflightCamera(provider: ProcessCameraProvider, analysis: ImageAnalysis) =
        controller().bindPreflightCamera(provider, analysis)
    override fun detachOwnedResources(): OfficialEnvironmentCameraResources? =
        if (initialized()) controller().detachOwnedResources()?.let(::AndroidOfficialEnvironmentSharedCameraResources) else null
    override fun awaitClosed(resources: OfficialEnvironmentCameraResources?, completion: (Boolean) -> Unit) {
        if (resources == null) completion(true)
        else (resources as AndroidOfficialEnvironmentSharedCameraResources).owned.awaitClosed(completion)
    }
    override fun markReleaseBlocked() = controller().markReleaseBlocked()
}

private class AndroidOfficialEnvironmentCameraPlatform(
    private val context: () -> Context,
    private val rotation: () -> Int,
    private val detectorExecutor: Executor,
    private val mainExecutor: () -> Executor,
    private val slot: OfficialEnvironmentSharedCameraSlot,
) : OfficialEnvironmentCameraPlatform {
    @androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
    override fun requestBind(isCurrent: () -> Boolean,
        onImage: (OfficialEnvironmentCameraImage) -> Unit, onFailure: () -> Unit) {
        val future = ProcessCameraProvider.getInstance(context())
        future.addListener({
            if (!isCurrent()) return@addListener
            try {
                val provider = future.get()
                val resolution = ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(
                    Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
                val analysis = ImageAnalysis.Builder().setResolutionSelector(resolution).setTargetRotation(rotation())
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setOutputImageRotationEnabled(true).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(detectorExecutor) { proxy -> onImage(OfficialEnvironmentImageProxyAccess(proxy)) }
                slot.bindPreflightCamera(provider, analysis)
            } catch (_: Exception) { if (isCurrent()) onFailure() }
        }, mainExecutor())
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
private class OfficialEnvironmentImageProxyAccess(private val image: ImageProxy) : OfficialEnvironmentCameraImage {
    override val width get() = image.width
    override val height get() = image.height
    override fun hasImage() = image.image != null
    override fun <T> withLuma(block: (ByteBuffer?, Int?, Int?) -> T): T {
        val plane = image.planes.firstOrNull()
        return block(plane?.buffer, plane?.rowStride, plane?.pixelStride)
    }
    override fun close() = image.close()
}

private class AndroidOfficialEnvironmentGpsPlatform(
    private val client: () -> FusedLocationProviderClient,
    private val mainExecutor: () -> Executor,
) : OfficialEnvironmentGpsPlatform {
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun createRegistration(options: OfficialEnvironmentGpsRequestOptions, isCurrent: () -> Boolean,
        onSamples: (List<OfficialEnvironmentNativeGpsSample>) -> Unit,
        onTemporarilyUnavailable: () -> Unit, onRegistered: () -> Unit,
        onFailure: () -> Unit): OfficialEnvironmentGpsRegistration {
        val fused = client()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!isCurrent()) return
                onSamples(result.locations.map { location -> OfficialEnvironmentNativeGpsSample(
                    location.elapsedRealtimeNanos, location.latitude, location.longitude,
                    location.accuracy.takeIf { location.hasAccuracy() }?.toDouble(),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider,
                ) })
            }
            override fun onLocationAvailability(availability: LocationAvailability) {
                if (isCurrent() && !availability.isLocationAvailable) onTemporarilyUnavailable()
            }
        }
        return object : OfficialEnvironmentGpsRegistration {
            override fun start() {
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, options.intervalMs)
                    .setGranularity(Granularity.GRANULARITY_FINE).setMaxUpdateAgeMillis(options.maximumAgeMs)
                    .setMinUpdateIntervalMillis(options.minimumIntervalMs).setWaitForAccurateLocation(true)
                    .setDurationMillis(options.maximumDurationMs).build()
                fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
                    .addOnSuccessListener(mainExecutor()) { onRegistered() }
                    .addOnFailureListener(mainExecutor()) { onFailure() }
            }
            override fun cancel() { fused.removeLocationUpdates(callback) }
        }
    }
}
