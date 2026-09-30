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
            onStatus = { status ->
                android.util.Log.d(
                    "WalkSafeFrameUpload",
                    "ok=${status.succeeded} failed=${status.failed} cancelled=${status.cancelled} " +
                        "dropped=${status.dropped} pendingBytes=${status.pendingBytes} " +
                        "http=${status.lastHttpStatus} reason=${status.lastFailureReason}",
                )
            },
        )
    }

    private const val DEFAULT_LOCAL_ENDPOINT = "http://127.0.0.1:8000/android/debug/frame-captures"
}
