package kr.co.hanium.dreamup.walksafe.network

import java.net.URI

/** Binds release traffic to one build-approved origin; debug keeps an adb-reverse escape hatch. */
object GatewayEndpointPolicy {
    fun approvedReleaseOriginOrNull(raw: String?, approvedOrigin: String): String? {
        val approved = normalizeOrNull(approvedOrigin, allowDebugLoopbackHttp = false) ?: return null
        val requested = normalizeOrNull(raw, allowDebugLoopbackHttp = false) ?: return null
        return requested.takeIf { it == approved }
    }

    fun debugOriginOrNull(raw: String?): String? {
        return normalizeOrNull(raw, allowDebugLoopbackHttp = true)
    }

    private fun normalizeOrNull(raw: String?, allowDebugLoopbackHttp: Boolean): String? {
        val value = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (uri.rawPath?.takeIf { it.isNotEmpty() } !in setOf(null, "/")) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        if (uri.scheme.equals("https", ignoreCase = true)) return "https://${uri.host.lowercase()}$port"
        val loopback = uri.host.equals("127.0.0.1") || uri.host.equals("localhost", ignoreCase = true)
        return "http://${uri.host.lowercase()}$port"
            .takeIf { allowDebugLoopbackHttp && uri.scheme.equals("http", ignoreCase = true) && loopback }
    }
}
