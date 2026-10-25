package kr.co.hanium.dreamup.walksafe.session.permission

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Construction does not read SDK state or launch an Android permission request. */
internal class AndroidPermissionPlatform(
    private val activity: Activity,
    private val exitBypass: Boolean,
    private val launch: (Array<String>, Int) -> Unit,
) : PermissionPlatform {
    override val sdkInt: Int get() = Build.VERSION.SDK_INT
    override fun finishing() = activity.isFinishing
    override fun destroyed() = activity.isDestroyed
    override fun debugExitBypass() = exitBypass
    override fun cameraGranted() = activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    override fun preciseLocationGranted() = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    override fun microphoneGranted() = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    override fun activityRecognitionGranted() = sdkInt < Build.VERSION_CODES.Q ||
        activity.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    override fun notificationGranted() = sdkInt < Build.VERSION_CODES.TIRAMISU ||
        activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    override fun coarseLocationGranted() = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    override fun rationaleRecommended(permission: String) = activity.shouldShowRequestPermissionRationale(permission)
    override fun launchRequest(permissions: Array<String>, requestCode: Int) = launch(permissions, requestCode)
}
