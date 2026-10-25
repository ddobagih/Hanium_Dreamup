package kr.co.hanium.dreamup.walksafe.network

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountEmailContractTest {
    @Test
    fun everySharedVectorMatchesTheCanonicalWireValue() {
        val vectors = JSONObject(File("../../../contracts/test-vectors/account-email.json").readText()).getJSONArray("vectors")
        assertEquals("the shared acceptance corpus", 53, vectors.length())
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val expected = if (vector.isNull("canonical")) null else vector.getString("canonical")
            val input = vector.getString("input")
            assertEquals(input, expected, GatewayAccountInputPolicy.canonicalEmailOrNull(input))
            assertEquals(input, expected != null, GatewayAccountInputPolicy.validEmail(input))
        }
    }
}
