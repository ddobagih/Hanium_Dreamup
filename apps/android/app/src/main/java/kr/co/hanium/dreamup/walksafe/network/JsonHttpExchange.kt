package kr.co.hanium.dreamup.walksafe.network

import java.net.HttpURLConnection
import java.net.URL

/** Keeps one JSON exchange inside the existing call's connection and stream cancellation lifetime. */
internal fun <T> withJsonHttpExchange(
    cancellation: HttpConnectionCancellation,
    endpoint: String,
    requestBody: String?,
    maximumResponseBytes: Int,
    configure: HttpURLConnection.() -> Unit,
    openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    consume: HttpURLConnection.(BoundedHttpResponse) -> T,
): T {
    val connection = openConnection(endpoint)
    cancellation.attach(connection)
    try {
        connection.apply {
            configure()
            if (requestBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (requestBody != null) {
            connection.outputStream.use { output ->
                cancellation.attach(output)
                try {
                    output.write(requestBody.toByteArray(Charsets.UTF_8))
                    output.flush()
                } finally {
                    cancellation.detach(output)
                }
            }
        }
        val response = connection.readBoundedResponse(maximumResponseBytes, cancellation)
        return connection.consume(response)
    } finally {
        cancellation.detach(connection)
        connection.disconnect()
    }
}
