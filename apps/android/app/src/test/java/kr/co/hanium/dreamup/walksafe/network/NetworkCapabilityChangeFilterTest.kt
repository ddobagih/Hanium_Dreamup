package kr.co.hanium.dreamup.walksafe.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** transfer-F7: signal-strength style capability updates do not restart the post-walk transfers. */
class NetworkCapabilityChangeFilterTest {
    @Test
    fun onlyANetworkTransportOrValidationChangeCounts() {
        val filter = NetworkCapabilityChangeFilter()
        assertTrue(filter.changed("net-1", ActiveNetworkTransport.WIFI, validated = true))
        assertFalse(filter.changed("net-1", ActiveNetworkTransport.WIFI, validated = true))
        assertFalse(filter.changed("net-1", ActiveNetworkTransport.WIFI, validated = true))
        assertTrue(filter.changed("net-1", ActiveNetworkTransport.WIFI, validated = false))
        assertTrue(filter.changed("net-1", ActiveNetworkTransport.OTHER, validated = false))
        assertTrue(filter.changed("net-2", ActiveNetworkTransport.OTHER, validated = false))
        filter.reset()
        assertTrue(filter.changed("net-2", ActiveNetworkTransport.OTHER, validated = false))
    }
}
