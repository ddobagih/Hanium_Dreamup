package kr.co.hanium.dreamup.walksafe.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kr.co.hanium.dreamup.walksafe.BuildConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in field probe: reads only the public anonymous session status, without credentials. */
@RunWith(AndroidJUnit4::class)
class AndroidGatewayPublicConnectionDeviceTest {
    @Test
    fun configuredPublicGatewayIsReachableOverVerifiedHttps() {
        val origin = BuildConfig.WALKSAFE_GATEWAY_ORIGIN
        assertTrue("This probe requires a configured public HTTPS origin", origin.startsWith("https://"))
        val connection = URL("$origin/api/field-session").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            assertEquals(200, connection.responseCode)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            assertFalse(JSONObject(body).getBoolean("authenticated"))
        } finally {
            connection.disconnect()
        }
    }
}
