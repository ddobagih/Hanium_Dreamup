package kr.co.hanium.dreamup.walksafe.account

import kr.co.hanium.dreamup.walksafe.network.GatewayAccountHttpException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SignupPolicyPreflightTest {
    private fun policy(): JSONObject = JSONObject().put("schema_version", "walksafe.account-signup-policy.v1")
        .put("documents", JSONObject().also { docs -> SIGNUP_DOCUMENT_VERSIONS.entries.forEachIndexed { i, (key, version) ->
            docs.put(key, JSONObject().put("version", version).put("required", i < 3)
                .put("equivalent_versions", JSONArray()).put("prior_versions", JSONArray()))
        } })
    @Test fun bundledAndApprovedEquivalentVersionsCanProceed() {
        assertTrue(SignupPolicyPreflight.unavailableOptional(policy()).isEmpty())
        val policy = policy()
        policy.getJSONObject("documents").getJSONObject("terms_of_service")
            .put("version", "v2").put("equivalent_versions", JSONArray(listOf(SIGNUP_DOCUMENT_VERSIONS.getValue("terms_of_service"))))
        assertTrue(SignupPolicyPreflight.unavailableOptional(policy).isEmpty())
    }
    @Test fun requiredOrUnknownOptionalMismatchBlocks() {
        listOf("terms_of_service", "training_reuse").forEach { key ->
            val policy = policy()
            policy.getJSONObject("documents").getJSONObject(key).put("version", "v2")
            val error = assertThrows(GatewayAccountHttpException::class.java) { SignupPolicyPreflight.unavailableOptional(policy) }
            assertEquals("signup_consent_outdated", error.serverCode)
        }
    }
    @Test fun knownPriorOptionalGrantIsRemovedButRequiredGrantsSurvive() {
        val policy = policy()
        policy.getJSONObject("documents").getJSONObject("training_reuse").put("version", "v2")
            .put("prior_versions", JSONArray(listOf(SIGNUP_DOCUMENT_VERSIONS.getValue("training_reuse"))))
        val unavailable = SignupPolicyPreflight.unavailableOptional(policy)
        val selections = SignupPolicyPreflight.restrict(SignupConsentSelections(true, true, true, true, true, true), unavailable)
        assertEquals(setOf("training_reuse"), unavailable)
        assertFalse(selections.trainingReuse)
        assertTrue(selections.requiredGranted && selections.rawOriginal && selections.automaticReporting)
    }
    @Test fun malformedOrWeakenedPolicyIsRejected() {
        val policy = policy()
        policy.getJSONObject("documents").getJSONObject("privacy_notice").put("required", false)
        assertThrows(GatewayAccountHttpException::class.java) { SignupPolicyPreflight.unavailableOptional(policy) }
        assertThrows(GatewayAccountHttpException::class.java) { SignupPolicyPreflight.unavailableOptional(JSONObject()) }
    }
}
