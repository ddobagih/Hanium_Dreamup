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

class AndroidStartupCapabilityProbe(
    context: Context,
    private val onCapabilityChanged: () -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val packageManager = appContext.packageManager
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
            cameraAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
            gpsAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS),
            microphoneAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
            vibrationAvailable = vibrator()?.hasVibrator() == true,
            onDeviceSpeechRecognitionAvailable = onDeviceSpeechRecognitionAvailable(),
            offlineKoreanTextToSpeechAvailable = offlineKoreanTextToSpeechProbeState.available,
            metricDistanceAvailable = metricDistanceAvailable,
            approvedDesignatedDeviceProfile = approvedDeviceProfileMatch.approved,
            designatedDeviceProfileVersion = approvedDeviceProfileMatch.profileVersion,
        )
    }

    fun deviceProfileMatch(): ApprovedDeviceProfileMatch = approvedDeviceProfileMatch

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

    private fun onDeviceSpeechRecognitionAvailable(): Boolean {
        return BundledVoskModelInstaller.bundledModelAvailable(appContext)
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
        val availability = runCatching {
            ArCoreApk.getInstance().checkAvailability(appContext)
        }.getOrNull()
        metricDistanceAvailable = distanceAvailableOrNull(availability)
        if (availability?.isUnknown != true) return

        runCatching {
            ArCoreApk.getInstance().checkAvailabilityAsync(appContext) { resolvedAvailability ->
                if (!isCurrent(currentGeneration)) return@checkAvailabilityAsync
                metricDistanceAvailable = distanceAvailableOrNull(resolvedAvailability)
                notifyChanged(currentGeneration)
            }
        }.onFailure {
            metricDistanceAvailable = null
        }
    }

    private fun distanceAvailableOrNull(availability: ArCoreApk.Availability?): Boolean? {
        return when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            -> if (packageManager.hasSystemFeature(ARCORE_DEPTH_FEATURE)) {
                // A hardware declaration identifies only a candidate. FULL stays blocked until a
                // live session proves stable metric frames on this device.
                null
            } else {
                false
            }

            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> false
            ArCoreApk.Availability.UNKNOWN_CHECKING,
            ArCoreApk.Availability.UNKNOWN_ERROR,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
            null,
            -> null
        }
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

    private companion object {
        const val ARCORE_DEPTH_FEATURE = "com.google.ar.core.depth"
    }
}

internal class OfflineKoreanTextToSpeechProbeState {
    var available: Boolean? = null
        private set

    private var generation = 0
    private var closed = false

    fun begin(): Int {
        check(!closed) { "Offline Korean TTS probe state is already closed" }
        available = null
        generation += 1
        return generation
    }

    fun complete(
        currentGeneration: Int,
        available: Boolean,
    ): Boolean {
        if (closed || currentGeneration != generation) return false
        this.available = available
        generation += 1
        return true
    }

    fun close() {
        if (closed) return
        closed = true
        generation += 1
    }
}
