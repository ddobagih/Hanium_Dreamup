package kr.co.hanium.dreamup.walksafe.debuglog

import java.net.URI

object MetadataLogEndpointPolicy {
    fun isAllowed(url: String): Boolean {
        val uri = try {
            URI(url.trim())
        } catch (_: IllegalArgumentException) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme !in setOf("http", "https")) return false
        return !uri.host.isNullOrBlank()
    }
}
