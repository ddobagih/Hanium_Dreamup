package kr.co.hanium.dreamup.walksafe.session

import kr.co.hanium.dreamup.walksafe.network.validatedIntegratedConsentConfirmationOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegratedConsentPolicyTest {
    @Test
    fun independentSelectionsRequireCurrentServerConfirmation() {
        val session = IntegratedConsentSession()
        val confirmation = confirmation(
            IntegratedConsentSelections(
                rawSourceCollection = true,
                automaticReporting = false,
                mobileNetworkTransfer = true,
                trainingReuse = false,
            ),
        )

        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertTrue(session.accept(confirmation))
        assertTrue(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertFalse(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
        assertTrue(session.isAllowed(IntegratedConsentItem.MOBILE_NETWORK_TRANSFER))
        assertFalse(session.isAllowed(IntegratedConsentItem.TRAINING_REUSE))
    }

    @Test
    fun oneLocalWithdrawalDoesNotRewriteOtherIndependentDecisions() {
        val session = IntegratedConsentSession()
        session.accept(
            confirmation(
                IntegratedConsentSelections(
                    rawSourceCollection = true,
                    automaticReporting = true,
                    mobileNetworkTransfer = true,
                    trainingReuse = true,
                ),
            ),
        )

        assertTrue(session.withdrawImmediately(IntegratedConsentItem.TRAINING_REUSE))

        assertFalse(session.isAllowed(IntegratedConsentItem.TRAINING_REUSE))
        assertTrue(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertTrue(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
    }

    @Test
    fun exactServerResponseParserRejectsWrongVersionOrExtraFields() {
        val valid = """
            {
              "schema_version":"walksafe.integrated-consent-confirmation.v2",
              "current":true,
              "installation_id":"501e3ad4-e74f-4433-820f-72ac2fdd42ad",
              "request_id":"integrated_consent_request_0001",
              "policy_version":"FP-013-1.1.0",
              "item_versions":{
                "raw_source_collection":"FP-013-RAW-1.1.0",
                "automatic_reporting":"FP-013-AUTO-1.1.0",
                "mobile_network_transfer":"FP-013-MOBILE-1.0.0",
                "training_reuse":"FP-013-TRAINING-1.1.0"
              },
              "client_revision":4,
              "revision":3,
              "selections":{
                "raw_source_collection":true,
                "automatic_reporting":false,
                "mobile_network_transfer":false,
                "training_reuse":true
              },
              "confirmed_at":"2026-07-25T12:00:00.000Z",
              "gateway_audit_record_sha256":"${"9".repeat(64)}",
              "backend_consent_receipt_sha256":"${"a".repeat(64)}"
            }
        """.trimIndent()

        val parsed = validatedIntegratedConsentConfirmationOrNull(
            valid,
            "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
            "b".repeat(64),
        )

        assertNotNull(parsed)
        assertEquals(3L, parsed?.revision)
        assertEquals("9".repeat(64), parsed?.gatewayAuditRecordSha256)
        assertEquals("a".repeat(64), parsed?.backendConsentReceiptSha256)
        assertTrue(parsed?.selections?.rawSourceCollection == true)
        assertNull(
            validatedIntegratedConsentConfirmationOrNull(
                valid.replace("FP-013-1.1.0", "FP-013-0.9.0"),
                "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
                "b".repeat(64),
            ),
        )
        assertNull(
            validatedIntegratedConsentConfirmationOrNull(
                valid.replace("\"current\":true", "\"current\":true,\"extra\":1"),
                "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
                "b".repeat(64),
            ),
        )
        assertNull(
            validatedIntegratedConsentConfirmationOrNull(
                valid.replace("\"revision\":3", "\"revision\":\"3\""),
                "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
                "b".repeat(64),
            ),
        )
        assertNull(
            validatedIntegratedConsentConfirmationOrNull(
                valid
                    .replace(
                        "\"walksafe.integrated-consent-confirmation.v2\"",
                        "\"walksafe.integrated-consent-confirmation.v1\"",
                    )
                    .replace(
                        "\"gateway_audit_record_sha256\":\"${"9".repeat(64)}\",\n              \"backend_consent_receipt_sha256\"",
                        "\"receipt_sha256\"",
                    ),
                "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
                "b".repeat(64),
            ),
        )
    }

    private fun confirmation(
        selections: IntegratedConsentSelections,
    ) = IntegratedConsentConfirmation(
        schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        installationId = "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
        requestId = "integrated_consent_request_0001",
        itemVersions = IntegratedConsentItemVersions(),
        clientRevision = 1L,
        revision = 1L,
        selections = selections,
        confirmedAt = "2026-07-25T12:00:00.000Z",
        gatewayAuditRecordSha256 = "9".repeat(64),
        backendConsentReceiptSha256 = "a".repeat(64),
        controlSecret = "b".repeat(64),
    )
}
