package kr.co.hanium.dreamup.walksafe.debuglog

import android.content.Context

object DebugFrameCaptureUploaderFactory {
    fun create(context: Context): FrameCaptureUploader = NoopFrameCaptureUploader
}
