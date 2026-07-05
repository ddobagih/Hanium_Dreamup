package kr.co.hanium.dreamup.walksafe.debuglog

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class HttpFrameCaptureUploader(
    private val endpointUrl: String,
    private val sessionId: String,
    private val deviceModel: String?,
    private val androidVersion: String?,
    private val appVersionName: String?,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "walksafe-frame-capture-uploader").apply {
            priority = Thread.NORM_PRIORITY - 2
        }
    },
) : FrameCaptureUploader {
    override fun upload(imageJpeg: ByteArray, metadataJson: String) {
        if (!MetadataLogEndpointPolicy.isAllowed(endpointUrl)) return
        if (imageJpeg.isEmpty()) return
        try {
            executor.execute {
                try {
                    postMultipart(imageJpeg, wrapMetadata(metadataJson))
                } catch (_: RuntimeException) {
                    // Debug-only capture must never crash the ARCore render loop.
                }
            }
        } catch (_: RejectedExecutionException) {
            // Ignore debug upload failures.
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun wrapMetadata(metadataJson: String): String {
        return """
            {
              "schema_version":"android.frame_capture.v1",
              "session_id":"$sessionId",
              "device_model":${deviceModel.jsonStringOrNull()},
              "android_version":${androidVersion.jsonStringOrNull()},
              "app_version_name":${appVersionName.jsonStringOrNull()},
              "entry":$metadataJson
            }
        """.trimIndent()
    }

    private fun postMultipart(imageJpeg: ByteArray, metadataJson: String) {
        val boundary = "walksafe-${UUID.randomUUID()}"
        val connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2_000
            readTimeout = 4_000
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"metadata\"\r\n")
                    writer.write("Content-Type: application/json; charset=utf-8\r\n\r\n")
                    writer.write(metadataJson)
                    writer.write("\r\n--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"image\"; filename=\"frame.jpg\"\r\n")
                    writer.write("Content-Type: image/jpeg\r\n\r\n")
                    writer.flush()
                    output.write(imageJpeg)
                    output.flush()
                    writer.write("\r\n--$boundary--\r\n")
                }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.close()
        } finally {
            connection.disconnect()
        }
    }

    private fun String?.jsonStringOrNull(): String {
        if (this == null) return "null"
        return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
