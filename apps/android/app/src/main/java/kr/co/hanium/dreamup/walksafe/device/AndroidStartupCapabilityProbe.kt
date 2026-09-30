package kr.co.hanium.dreamup.walksafe.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import com.google.ar.core.ArCoreApk
import java.io.Closeable
import kr.co.hanium.dreamup.walksafe.voice.BundledVoskModelInstaller
import kr.co.hanium.dreamup.walksafe.voice.OfflineKoreanSpeechUnavailableCause

class AndroidStartupCapabilityProbe(
    context: Context,
    private val onCapabilityChanged: () -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val packageManager = appContext.packageManager
    private val hasCameraHardware by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    private val hasGpsHardware by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
    }
    private val hasMicrophoneHardware by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }
    private val hasVibrationHardware by lazy { vibrator()?.hasVibrator() == true }
    private val bundledOfflineRecognitionAvailable by lazy {
        BundledVoskModelInstaller.bundledModelAvailable(appContext)
    }
    private val approvedDeviceProfileMatch = ApprovedDeviceProfileMatcher.match(
        WalkSafeDeviceIdentity(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            sdk = Build.VERSION.SDK_INT,
        ),
    )

    private var started = false
    private var closed = false
    private var generation = 0
    private val offlineKoreanTextToSpeechProbeState = OfflineKoreanTextToSpeechProbeState()
    private var koreanTextToSpeechProbe: AndroidKoreanTextToSpeechSynthesisProbe? = null
    private var metricDistanceAvailable: Boolean? = null
    private var metricDistanceProbeFailure: WalkSafeMetricDistanceProbeFailure? = null

    fun start() {
        check(!closed) { "AndroidStartupCapabilityProbe is already closed" }
        check(!started) { "AndroidStartupCapabilityProbe may only be started once" }
        started = true
        val currentGeneration = ++generation
        probeDistance(currentGeneration)
        probeOfflineKoreanTextToSpeech(currentGeneration)
        notifyChanged(currentGeneration)
    }

    fun recheckOfflineKoreanTextToSpeech() {
        check(!closed) { "AndroidStartupCapabilityProbe is already closed" }
        check(started) { "AndroidStartupCapabilityProbe must be started before rechecking" }
        val currentGeneration = generation
        probeOfflineKoreanTextToSpeech(currentGeneration, notifyPending = true)
    }

    fun snapshot(): WalkSafeStartupCapabilityInput {
        return WalkSafeStartupCapabilityInput(
            androidVersionSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            cameraAvailable = hasCameraHardware,
            gpsAvailable = hasGpsHardware,
            microphoneAvailable = hasMicrophoneHardware,
            vibrationAvailable = hasVibrationHardware,
            onDeviceSpeechRecognitionAvailable = bundledOfflineRecognitionAvailable,
            offlineKoreanTextToSpeechAvailable = offlineKoreanTextToSpeechProbeState.available,
            metricDistanceAvailable = metricDistanceAvailable,
            approvedDesignatedDeviceProfile = approvedDeviceProfileMatch.approved,
            designatedDeviceProfileVersion = approvedDeviceProfileMatch.profileVersion,
            metricDistanceProbeFailure = metricDistanceProbeFailure,
        )
    }

    fun deviceProfileMatch(): ApprovedDeviceProfileMatch = approvedDeviceProfileMatch

    /** Cause of the latest completed unavailable probe; null while pending, available or unknown. */
    fun offlineKoreanTextToSpeechUnavailableCause(): OfflineKoreanSpeechUnavailableCause? =
        offlineKoreanTextToSpeechProbeState.unavailableCause

    fun decision(
        metricDistanceOverride: Boolean? = null,
        onDeviceSpeechRecognitionOverride: Boolean? = null,
        offlineKoreanTextToSpeechOverride: Boolean? = null,
        approvedDeviceProfileRequired: Boolean = true,
    ): WalkSafeStartupCapabilityDecision {
        val input = snapshot()
        val effectiveInput = input.copy(
            metricDistanceAvailable = metricDistanceOverride ?: input.metricDistanceAvailable,
            onDeviceSpeechRecognitionAvailable =
                onDeviceSpeechRecognitionOverride ?: input.onDeviceSpeechRecognitionAvailable,
            offlineKoreanTextToSpeechAvailable =
                offlineKoreanTextToSpeechOverride ?: input.offlineKoreanTextToSpeechAvailable,
        )
        return WalkSafeStartupCapabilityResolver.resolve(
            effectiveInput,
            approvedDeviceProfileRequired = approvedDeviceProfileRequired,
        )
    }

    private fun vibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun probeDistance(currentGeneration: Int) {
        val availabilityResult = runCatching {
            ArCoreApk.getInstance().checkAvailability(appContext)
        }
        val availability = availabilityResult.getOrNull()
        metricDistanceAvailable = distanceAvailableOrNull(availability)
        metricDistanceProbeFailure = resolveRuntimeMetricDistanceProbeFailure(availabilityResult)
        if (availability?.isUnknown != true) return

        runCatching {
            ArCoreApk.getInstance().checkAvailabilityAsync(appContext) { resolvedAvailability ->
                if (!isCurrent(currentGeneration)) return@checkAvailabilityAsync
                metricDistanceAvailable = distanceAvailableOrNull(resolvedAvailability)
                metricDistanceProbeFailure = resolveRuntimeMetricDistanceProbeFailure(
                    Result.success(resolvedAvailability),
                )
                notifyChanged(currentGeneration)
            }
        }.onFailure {
            metricDistanceAvailable = null
            metricDistanceProbeFailure = WalkSafeMetricDistanceProbeFailure.QUERY_EXCEPTION
        }
    }

    private fun distanceAvailableOrNull(availability: ArCoreApk.Availability?): Boolean? {
        return resolveRuntimeMetricDistanceAvailability(availability)
    }

    private fun probeOfflineKoreanTextToSpeech(
        currentGeneration: Int,
        notifyPending: Boolean = false,
    ) {
        val currentKoreanTextToSpeechGeneration = offlineKoreanTextToSpeechProbeState.begin()
        val previousProbe = koreanTextToSpeechProbe
        koreanTextToSpeechProbe = null
        previousProbe?.close()
        if (notifyPending) notifyChanged(currentGeneration)
        val probe = runCatching {
            AndroidKoreanTextToSpeechSynthesisProbe(appContext) { result ->
                if (
                    !isCurrent(currentGeneration) ||
                    !offlineKoreanTextToSpeechProbeState.complete(
                        currentKoreanTextToSpeechGeneration,
                        result.available,
                        result.unavailableCause,
                    )
                ) {
                    return@AndroidKoreanTextToSpeechSynthesisProbe
                }
                koreanTextToSpeechProbe = null
                onCapabilityChanged()
            }
        }.getOrElse {
            if (
                isCurrent(currentGeneration) &&
                offlineKoreanTextToSpeechProbeState.complete(
                    currentKoreanTextToSpeechGeneration,
                    available = false,
                )
            ) {
                notifyChanged(currentGeneration)
            }
            return
        }
        koreanTextToSpeechProbe = probe
        runCatching { probe.start() }.onFailure {
            if (
                !isCurrent(currentGeneration) ||
                !offlineKoreanTextToSpeechProbeState.complete(
                    currentKoreanTextToSpeechGeneration,
                    available = false,
                )
            ) {
                if (koreanTextToSpeechProbe === probe) koreanTextToSpeechProbe = null
                probe.close()
                return@onFailure
            }
            koreanTextToSpeechProbe = null
            probe.close()
            notifyChanged(currentGeneration)
        }
    }

    private fun notifyChanged(currentGeneration: Int) {
        mainHandler.post {
            if (isCurrent(currentGeneration)) onCapabilityChanged()
        }
    }

    private fun isCurrent(currentGeneration: Int): Boolean =
        !closed && currentGeneration == generation

    override fun close() {
        if (closed) return
        closed = true
        generation += 1
        mainHandler.removeCallbacksAndMessages(null)
        offlineKoreanTextToSpeechProbeState.close()
        val previousProbe = koreanTextToSpeechProbe
        koreanTextToSpeechProbe = null
        previousProbe?.close()
    }

}

internal fun resolveRuntimeMetricDistanceProbeFailure(
    result: Result<ArCoreApk.Availability?>,
): WalkSafeMetricDistanceProbeFailure? {
    if (result.isFailure) return WalkSafeMetricDistanceProbeFailure.QUERY_EXCEPTION
    return when (result.getOrNull()) {
        ArCoreApk.Availability.UNKNOWN_ERROR -> WalkSafeMetricDistanceProbeFailure.ARCORE_ERROR
        ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> WalkSafeMetricDistanceProbeFailure.ARCORE_TIMED_OUT
        else -> null
    }
}

/** Only a live ARCore Session may turn a supported ARCore candidate into metric-depth evidence. */
internal fun resolveRuntimeMetricDistanceAvailability(
    availability: ArCoreApk.Availability?,
): Boolean? = when (availability) {
    ArCoreApk.Availability.SUPPORTED_INSTALLED,
    ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
    ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
    -> null

    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> false
    ArCoreApk.Availability.UNKNOWN_CHECKING,
    ArCoreApk.Availability.UNKNOWN_ERROR,
    ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
    null,
    -> null
}

internal class OfflineKoreanTextToSpeechProbeState {
    var available: Boolean? = null
        private set
    var unavailableCause: OfflineKoreanSpeechUnavailableCause? = null
        private set

    private var generation = 0
    private var closed = false

    fun begin(): Int {
        check(!closed) { "Offline Korean TTS probe state is already closed" }
        available = null
        unavailableCause = null
        generation += 1
        return generation
    }

    fun complete(
        currentGeneration: Int,
        available: Boolean,
        unavailableCause: OfflineKoreanSpeechUnavailableCause? = null,
    ): Boolean {
        if (closed || currentGeneration != generation) return false
        this.available = available
        this.unavailableCause = if (available) null else unavailableCause
        generation += 1
        return true
    }

    fun close() {
        if (closed) return
        closed = true
        generation += 1
    }
}
