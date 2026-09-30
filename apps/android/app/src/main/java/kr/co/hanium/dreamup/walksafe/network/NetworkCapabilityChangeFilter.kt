package kr.co.hanium.dreamup.walksafe.network

/**
 * transfer-F7 (w24 review): ConnectivityManager reports capability changes for signal strength,
 * bandwidth and similar updates too. The post-walk transfers are cancelled and restarted only when
 * the network itself, its transport or its validation changed, because a restarted RAW upload
 * sends every chunk again (FP-035-DETAIL-02 interim).
 */
class NetworkCapabilityChangeFilter {
    private data class Observed(
        val network: Any,
        val transport: ActiveNetworkTransport,
        val validated: Boolean,
    )

    private var last: Observed? = null

    /** True when this observation differs from the previous one (the first one always does). */
    @Synchronized
    fun changed(network: Any, transport: ActiveNetworkTransport, validated: Boolean): Boolean {
        val observed = Observed(network, transport, validated)
        if (observed == last) return false
        last = observed
        return true
    }

    /** A lost network makes the next observation a change. */
    @Synchronized
    fun reset() {
        last = null
    }
}
