package kr.co.hanium.dreamup.walksafe.debuglog

interface FrameCaptureUploader {
    fun upload(imageJpeg: ByteArray, metadataJson: String)
    fun close()
}

object NoopFrameCaptureUploader : FrameCaptureUploader {
    override fun upload(imageJpeg: ByteArray, metadataJson: String) = Unit
    override fun close() = Unit
}
