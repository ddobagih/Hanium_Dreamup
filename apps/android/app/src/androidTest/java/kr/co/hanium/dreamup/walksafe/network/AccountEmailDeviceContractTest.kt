package kr.co.hanium.dreamup.walksafe.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountEmailDeviceContractTest {
    @Test fun platformIcuMatchesTheSharedGatewayVectors() {
        val json = InstrumentationRegistry.getInstrumentation().context.assets.open("account-email.json")
            .bufferedReader().use { it.readText() }
        val vectors = JSONObject(json).getJSONArray("vectors")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            assertEquals(vector.getString("input"),
                if (vector.isNull("canonical")) null else vector.getString("canonical"),
                GatewayAccountInputPolicy.canonicalEmailOrNull(vector.getString("input")))
        }
    }
}
