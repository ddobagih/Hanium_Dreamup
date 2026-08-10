package kr.co.hanium.dreamup.walksafe.debuglog

interface FrameCaptureUploader {
    fun upload(imageJpeg: ByteArray, metadataJson: String)
    fun cancelActiveUpload()
    fun close()
}

object NoopFrameCaptureUploader : FrameCaptureUploader {
    override fun upload(imageJpeg: ByteArray, metadataJson: String) = Unit
    override fun cancelActiveUpload() = Unit
    override fun close() = Unit
}
