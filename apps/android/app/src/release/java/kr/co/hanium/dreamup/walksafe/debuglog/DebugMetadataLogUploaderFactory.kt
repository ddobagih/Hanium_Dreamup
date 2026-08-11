package kr.co.hanium.dreamup.walksafe.debuglog

import android.content.Context

object DebugMetadataLogUploaderFactory {
    fun create(context: Context): MetadataLogUploader = NoopMetadataLogUploader()
}
