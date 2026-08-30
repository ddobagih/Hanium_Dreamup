package kr.co.hanium.dreamup.walksafe.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.google.ar.core.ArCoreApk
import java.io.Closeable
import java.util.Locale

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
    private var textToSpeech: TextToSpeech? = null
    private var offlineKoreanTextToSpeechAvailable: Boolean? = null
    private var metricDistanceAvailable: Boolean? = null

    fun start() {
        check(!started) { "AndroidStartupCapabilityProbe may only be started once" }
        started = true
        val currentGeneration = ++generation
        probeDistance(currentGeneration)
        probeOfflineKoreanTextToSpeech(currentGeneration)
        notifyChanged(currentGeneration)
    }

    fun snapshot(): WalkSafeStartupCapabilityInput {
        return WalkSafeStartupCapabilityInput(
            androidVersionSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            cameraAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
            gpsAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS),
            microphoneAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
            vibrationAvailable = vibrator()?.hasVibrator() == true,
            onDeviceSpeechRecognitionAvailable = onDeviceSpeechRecognitionAvailable(),
            offlineKoreanTextToSpeechAvailable = offlineKoreanTextToSpeechAvailable,
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        }.getOrDefault(false)
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
                // live session proves stable metric frames on an approved device profile.
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

    private fun probeOfflineKoreanTextToSpeech(currentGeneration: Int) {
        runCatching {
            textToSpeech = TextToSpeech(appContext) { status ->
                mainHandler.post {
                    if (!isCurrent(currentGeneration)) return@post
                    val engine = textToSpeech
                    offlineKoreanTextToSpeechAvailable =
                        status == TextToSpeech.SUCCESS && engine?.hasOfflineKoreanVoice() == true
                    engine?.shutdown()
                    textToSpeech = null
                    onCapabilityChanged()
                }
            }
        }.onFailure {
            offlineKoreanTextToSpeechAvailable = false
            notifyChanged(currentGeneration)
        }
    }

    private fun TextToSpeech.hasOfflineKoreanVoice(): Boolean {
        val koreanSupported = runCatching {
            isLanguageAvailable(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
        }.getOrDefault(false)
        if (!koreanSupported) return false
        return runCatching {
            voices.orEmpty().any { voice ->
                voice.locale.language.equals(Locale.KOREAN.language, ignoreCase = true) &&
                    !voice.isNetworkConnectionRequired
            }
        }.getOrDefault(false)
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
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    private companion object {
        const val ARCORE_DEPTH_FEATURE = "com.google.ar.core.depth"
    }
}
