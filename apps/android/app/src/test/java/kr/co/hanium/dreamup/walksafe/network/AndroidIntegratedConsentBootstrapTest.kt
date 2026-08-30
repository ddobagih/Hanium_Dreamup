package kr.co.hanium.dreamup.walksafe.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidIntegratedConsentBootstrapTest {
    @Test
    fun readyBootstrapRequiresExactV11DocumentsAndBothReceiptBindings() {
        val parsed = validatedIntegratedConsentBootstrapOrNull(
            body = readyBody(),
            expectedInstallationId = INSTALLATION_ID,
        )

        assertEquals(IntegratedConsentBootstrapStatus.READY, parsed?.status)
        assertEquals(IntegratedConsentBootstrapSource.SIGNUP_CONSENT, parsed?.source)
        assertEquals(0L, parsed?.clientRevisionFloor)
        assertEquals("a".repeat(64), parsed?.sourceReceiptSha256)
        assertNull(parsed?.expectedPreviousBackendReceiptSha256)
        assertTrue(parsed?.selections?.rawSourceCollection == true)

        assertNull(
            validatedIntegratedConsentBootstrapOrNull(
                readyBody().replace("FP-013-RAW-1.1.0", "FP-013-RAW-1.0.0"),
                INSTALLATION_ID,
            ),
        )
        assertNull(
            validatedIntegratedConsentBootstrapOrNull(
                readyBody().replace(
                    "\"expected_previous_backend_receipt_sha256\":null",
                    "\"expected_previous_backend_receipt_sha256\":\"${"b".repeat(64)}\"",
                ),
                INSTALLATION_ID,
            ),
        )

        val malformedCurrent = readyBody()
            .replace("\"source\":\"SIGNUP_CONSENT\"", "\"source\":\"CURRENT_CONSENT\"")
            .replace(
                "\"expected_previous_backend_receipt_sha256\":null",
                "\"expected_previous_backend_receipt_sha256\":\"${"b".repeat(64)}\"",
            )
        assertNull(
            validatedIntegratedConsentBootstrapOrNull(
                malformedCurrent,
                INSTALLATION_ID,
            ),
        )
        val validCurrent = readyBody()
            .replace("\"source\":\"SIGNUP_CONSENT\"", "\"source\":\"CURRENT_CONSENT\"")
            .replace(
                "\"expected_previous_backend_receipt_sha256\":null",
                "\"expected_previous_backend_receipt_sha256\":\"${"a".repeat(64)}\"",
            )
        assertEquals(
            IntegratedConsentBootstrapSource.CURRENT_CONSENT,
            validatedIntegratedConsentBootstrapOrNull(validCurrent, INSTALLATION_ID)?.source,
        )
        assertNull(
            validatedIntegratedConsentBootstrapOrNull(
                readyBody().replace(
                    "\"source_receipt_sha256\":\"${"a".repeat(64)}\"",
                    "\"source_receipt_sha256\":null",
                ),
                INSTALLATION_ID,
            ),
        )
    }

    @Test
    fun reconsentBootstrapCarriesLatestEventCasAndRejectsCoercedTypes() {
        val body = reconsentBody()
        val parsed = validatedIntegratedConsentBootstrapOrNull(body, INSTALLATION_ID)

        assertEquals(IntegratedConsentBootstrapStatus.RECONSENT_REQUIRED, parsed?.status)
        assertEquals(7L, parsed?.clientRevisionFloor)
        assertEquals("b".repeat(64), parsed?.expectedPreviousBackendReceiptSha256)
        assertNull(parsed?.selections)

        assertNull(
            validatedIntegratedConsentBootstrapOrNull(
                body.replace("\"client_revision_floor\":7", "\"client_revision_floor\":\"7\""),
                INSTALLATION_ID,
            ),
        )
        assertNull(
            validatedIntegratedConsentBootstrapOrNull(
                body.replace("\"status\":\"RECONSENT_REQUIRED\"", "\"status\":7"),
                INSTALLATION_ID,
            ),
        )
    }

    private fun readyBody(): String = """
        {
          "schema_version":"walksafe.integrated-consent-bootstrap.v1",
          "status":"READY",
          "source":"SIGNUP_CONSENT",
          "installation_id":"$INSTALLATION_ID",
          "policy_version":"FP-013-1.1.0",
          "item_versions":{
            "raw_source_collection":"FP-013-RAW-1.1.0",
            "automatic_reporting":"FP-013-AUTO-1.1.0",
            "mobile_network_transfer":"FP-013-MOBILE-1.0.0",
            "training_reuse":"FP-013-TRAINING-1.1.0"
          },
          "client_revision_floor":0,
          "selections":{
            "raw_source_collection":true,
            "automatic_reporting":false,
            "mobile_network_transfer":false,
            "training_reuse":true
          },
          "source_receipt_sha256":"${"a".repeat(64)}",
          "expected_previous_backend_receipt_sha256":null
        }
    """.trimIndent()

    private fun reconsentBody(): String = """
        {
          "schema_version":"walksafe.integrated-consent-bootstrap.v1",
          "status":"RECONSENT_REQUIRED",
          "source":"NONE",
          "installation_id":"$INSTALLATION_ID",
          "policy_version":"FP-013-1.1.0",
          "item_versions":{
            "raw_source_collection":"FP-013-RAW-1.1.0",
            "automatic_reporting":"FP-013-AUTO-1.1.0",
            "mobile_network_transfer":"FP-013-MOBILE-1.0.0",
            "training_reuse":"FP-013-TRAINING-1.1.0"
          },
          "client_revision_floor":7,
          "selections":null,
          "source_receipt_sha256":null,
          "expected_previous_backend_receipt_sha256":"${"b".repeat(64)}"
        }
    """.trimIndent()

    private companion object {
        const val INSTALLATION_ID = "install-test-0001"
    }
}
