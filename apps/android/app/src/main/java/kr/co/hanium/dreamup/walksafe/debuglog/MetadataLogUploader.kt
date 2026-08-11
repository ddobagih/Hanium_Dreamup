package kr.co.hanium.dreamup.walksafe.debuglog

import java.io.Closeable
import kr.co.hanium.dreamup.walksafe.MetadataCaptureLogEntry

interface MetadataLogUploader : Closeable {
    fun enqueue(entry: MetadataCaptureLogEntry)
    fun setEnabled(enabled: Boolean)
    fun isEnabled(): Boolean
    fun statusText(): String
}

class NoopMetadataLogUploader : MetadataLogUploader {
    override fun enqueue(entry: MetadataCaptureLogEntry) = Unit
    override fun setEnabled(enabled: Boolean) = Unit
    override fun isEnabled(): Boolean = false
    override fun statusText(): String = "server-log=off"
    override fun close() = Unit
}
