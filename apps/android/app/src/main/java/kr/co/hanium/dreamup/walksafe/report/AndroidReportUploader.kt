package kr.co.hanium.dreamup.walksafe.report

import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

data class ReportUploadResponse(
    val statusCode: Int,
    val responseBody: String,
) {
    fun duplicateReportIds(): List<String> {
        return duplicateReportIdsFromBody(responseBody)
    }
}

data class ReportUploadError(
    val statusCode: Int,
    val errorBody: String,
)

class AndroidReportUploader {
    fun upload(
        baseUrl: String,
        metadataJson: String,
        imageJpeg: ByteArray,
    ): ReportUploadResponse {
        val endpoint = baseUrl.trimEnd('/') + "/reports/v2"
        val boundary = "----walksafe-${System.currentTimeMillis()}"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 12_000
            doOutput = true
            doInput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            writeMultipart(connection.outputStream, boundary, metadataJson, imageJpeg)
            val statusCode = connection.responseCode
            val response = if (statusCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }
            if (statusCode !in 200..299) {
                throw ReportUploadHttpException(ReportUploadError(statusCode, response))
            }
            return ReportUploadResponse(statusCode, response)
        } finally {
            connection.disconnect()
        }
    }

    private fun writeMultipart(
        output: OutputStream,
        boundary: String,
        metadataJson: String,
        imageJpeg: ByteArray,
    ) {
        val line = "\r\n"
        output.write("--$boundary$line".toByteArray(Charsets.UTF_8))
        output.write(
            "Content-Disposition: form-data; name=\"metadata\"$line".toByteArray(Charsets.UTF_8),
        )
        output.write("Content-Type: application/json$line$line".toByteArray(Charsets.UTF_8))
        output.write(metadataJson.toByteArray(Charsets.UTF_8))
        output.write(line.toByteArray(Charsets.UTF_8))

        output.write("--$boundary$line".toByteArray(Charsets.UTF_8))
        output.write(
            "Content-Disposition: form-data; name=\"image\"; filename=\"report.jpg\"$line".toByteArray(Charsets.UTF_8),
        )
        output.write("Content-Type: image/jpeg$line$line".toByteArray(Charsets.UTF_8))
        output.write(imageJpeg)
        output.write(line.toByteArray(Charsets.UTF_8))
        output.write("--$boundary--$line".toByteArray(Charsets.UTF_8))
        output.flush()
    }
}

class ReportUploadHttpException(val error: ReportUploadError) : IllegalStateException("report upload failed: ${error.statusCode}")

fun duplicateReportIdsFromBody(body: String): List<String> {
    if (body.isBlank()) return emptyList()
    return try {
        val root = JSONObject(body)
        val direct = root.optJSONArray("duplicate_report_ids")
        val metadata = root.optJSONObject("metadata")?.optJSONArray("duplicate_report_ids")
        (direct ?: metadata).toStringList()
    } catch (_: RuntimeException) {
        emptyList()
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val values = mutableListOf<String>()
    for (index in 0 until length()) {
        val value = optString(index).trim()
        if (value.isNotBlank()) values += value
    }
    return values
}
