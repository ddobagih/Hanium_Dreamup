package kr.co.hanium.dreamup.walksafe.debuglog

import android.content.Context

object DebugFrameCaptureUploaderFactory {
    fun create(context: Context): FrameCaptureUploader {
        return HttpFrameCaptureUploader(
            endpointUrl = DEFAULT_LOCAL_ENDPOINT,
            sessionId = "android-frame-${System.currentTimeMillis()}",
            deviceModel = android.os.Build.MODEL,
            androidVersion = android.os.Build.VERSION.RELEASE,
            appVersionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName,
        )
    }

    private const val DEFAULT_LOCAL_ENDPOINT = "http://127.0.0.1:8000/android/debug/frame-captures"
}
