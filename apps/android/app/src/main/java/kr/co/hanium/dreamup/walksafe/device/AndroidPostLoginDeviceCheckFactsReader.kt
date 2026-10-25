package kr.co.hanium.dreamup.walksafe.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build

internal data class PostLoginDeviceCheckVoiceFacts(
    val modelPreparationFailed: Boolean,
    val speechRecognitionOverride: Boolean?,
    val koreanSpeechOverride: Boolean?,
)

/** Reads platform permission/service facts and copied values from their existing single owners. */
internal class AndroidPostLoginDeviceCheckFactsReader(
    context: Context,
    private val actorSource: () -> PostLoginDeviceCheckActorFacts,
    private val startupSource: () -> WalkSafeStartupCapabilityInput,
    private val resourceSource: () -> WalkSessionDeviceResourceSnapshot,
    private val detectorSource: () -> PostLoginDeviceCheckDetectorFacts,
    private val voiceSource: () -> PostLoginDeviceCheckVoiceFacts,
    private val voiceDisclosureAccepted: () -> Boolean,
    private val resultBindingSource: (String) -> PostLoginDeviceCheckResultBinding?,
) : PostLoginDeviceCheckFactsReader {
    private val context = context.applicationContext

    override fun actor(): PostLoginDeviceCheckActorFacts = actorSource()
    override fun resultBinding(actorId: String): PostLoginDeviceCheckResultBinding? = resultBindingSource(actorId)

    override fun hardware(): PostLoginDeviceCheckHardwareFacts {
        val startup = startupSource()
        val resources = resourceSource()
        val detector = detectorSource()
        val voice = voiceSource()
        val cameraPermission = granted(Manifest.permission.CAMERA)
        val locationPermission = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val microphonePermission = granted(Manifest.permission.RECORD_AUDIO)
        val missing = buildList {
            if (!cameraPermission) add(Manifest.permission.CAMERA)
            if (!locationPermission) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (!microphonePermission) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.distinct()
        val locationServiceEnabled = runCatching {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager?.isLocationEnabled == true
            else manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                manager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        }.getOrDefault(false)
        return PostLoginDeviceCheckHardwareFacts(
            startup = startup,
            resourceReadiness = resources.measurementReadinessStatus,
            thermalThrottled = resources.thermalThrottled,
            detector = detector,
            missingRequiredPermissions = missing,
            cameraPermission = cameraPermission,
            microphonePermission = microphonePermission,
            locationServiceEnabled = locationServiceEnabled,
            voiceDisclosureAccepted = voiceDisclosureAccepted(),
            voiceModelPreparationFailed = voice.modelPreparationFailed,
            speechRecognitionOverride = voice.speechRecognitionOverride,
            koreanSpeechOverride = voice.koreanSpeechOverride,
        )
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
