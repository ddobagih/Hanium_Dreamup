package kr.co.hanium.dreamup.walksafe.account

import kr.co.hanium.dreamup.walksafe.network.GatewayAccountHttpException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SignupPolicyPreflightTest {
    @Test
    fun currentAndEquivalentBundledVersionsRemainTheVersionsActuallyDisplayed() {
        assertTrue(SignupPolicyPreflight.unavailableOptional(policy()).isEmpty())
        val changed = policy()
        changed.getJSONObject("documents").getJSONObject("terms_of_service")
            .put("version", "walksafe.terms-of-service.v2")
            .put("equivalent_versions", JSONArray(listOf(SIGNUP_DOCUMENT_VERSIONS.getValue("terms_of_service"))))
        assertTrue(SignupPolicyPreflight.unavailableOptional(changed).isEmpty())
        assertEquals("walksafe.terms-of-service.v1", SIGNUP_DOCUMENT_VERSIONS.getValue("terms_of_service"))
    }

    @Test
    fun changedRequiredOrUnknownOptionalVersionFailsClosed() {
        for (key in listOf("terms_of_service", "raw_original")) {
            val changed = policy()
            changed.getJSONObject("documents").getJSONObject(key).put("version", "changed.v2")
            val error = assertThrows(GatewayAccountHttpException::class.java) {
                SignupPolicyPreflight.unavailableOptional(changed)
            }
            assertEquals(409, error.statusCode)
            assertEquals(SignupConsentPolicyNotice.OUTDATED_CODE, error.serverCode)
        }
    }

    @Test
    fun knownPriorOptionalDocumentCanNeverGrantTheNewVersion() {
        val changed = policy()
        changed.getJSONObject("documents").getJSONObject("raw_original")
            .put("version", "FP-013-RAW-2.0.0")
            .put("prior_versions", JSONArray(listOf(SIGNUP_DOCUMENT_VERSIONS.getValue("raw_original"))))
        val unavailable = SignupPolicyPreflight.unavailableOptional(changed)
        assertEquals(setOf("raw_original"), unavailable)
        val selections = SignupConsentSelections(true, true, true, true, true, true)
        val restricted = SignupPolicyPreflight.restrict(selections, unavailable)
        assertFalse(restricted.rawOriginal)
        assertTrue(restricted.requiredGranted)
        assertTrue(restricted.automaticReporting)
        assertTrue(restricted.trainingReuse)
    }

    @Test
    fun malformedMetadataCannotAuthorizeSignup() {
        val invalid = listOf(
            policy().put("extra", true),
            policy().put("schema_version", "unknown"),
            policy().also { it.getJSONObject("documents").remove("training_reuse") },
            policy().also { it.getJSONObject("documents").getJSONObject("raw_original").put("required", true) },
            policy().also { it.getJSONObject("documents").getJSONObject("privacy_notice").put("equivalent_versions", JSONArray(listOf("v1", "v1"))) },
            policy().also { it.getJSONObject("documents").getJSONObject("privacy_notice").put("version", "invalid version") },
        )
        invalid.forEach { value ->
            assertThrows(GatewayAccountHttpException::class.java) { SignupPolicyPreflight.unavailableOptional(value) }
        }
    }

    private fun policy(): JSONObject = JSONObject()
        .put("schema_version", "walksafe.account-signup-policy.v1")
        .put("documents", JSONObject().also { documents ->
            SIGNUP_DOCUMENT_VERSIONS.entries.forEachIndexed { index, (key, version) ->
                documents.put(key, JSONObject().put("version", version).put("required", index < 3)
                    .put("equivalent_versions", JSONArray()).put("prior_versions", JSONArray()))
            }
        })
}
