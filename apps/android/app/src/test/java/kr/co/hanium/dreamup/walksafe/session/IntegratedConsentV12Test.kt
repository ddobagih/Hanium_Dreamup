package kr.co.hanium.dreamup.walksafe.session

import java.io.File
import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import kr.co.hanium.dreamup.walksafe.network.validatedIntegratedConsentBootstrapOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * transfer-F1 / Root decision R3 (2026-09-29): the RAW and automatic-reporting disclosures now send
 * after the walk ends, so both items and the document are FP-013 1.2.0; the unchanged mobile and
 * training items keep their versions. A stored 1.1.0 consent is a denied reconsent (the existing
 * bootstrap flow asks again) and nothing kept under it is sent before the new consent.
 */
class IntegratedConsentV12Test {
    private val source = listOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first(File::isFile).readText()

    @Test
    fun onlyTheRawAndAutomaticItemsAndTheDocumentMoveTo120() {
        assertEquals("FP-013-1.2.0", INTEGRATED_CONSENT_POLICY_VERSION)
        assertEquals("FP-013-RAW-1.2.0", INTEGRATED_CONSENT_RAW_ITEM_VERSION)
        assertEquals("FP-013-AUTO-1.2.0", INTEGRATED_CONSENT_AUTOMATIC_ITEM_VERSION)
        assertEquals("FP-013-MOBILE-1.0.0", INTEGRATED_CONSENT_MOBILE_ITEM_VERSION)
        assertEquals("FP-013-TRAINING-1.1.0", INTEGRATED_CONSENT_TRAINING_ITEM_VERSION)
        assertEquals("FP-013-1.1.0", PREVIOUS_INTEGRATED_CONSENT_POLICY_VERSION)
        assertEquals(setOf("FP-013-1.0.0", "FP-013-1.1.0"), KNOWN_PREVIOUS_INTEGRATED_CONSENT_POLICY_VERSIONS)
        assertTrue(INTEGRATED_CONSENT_DISCLOSURE_KO.startsWith("통합 동의 문서 FP-013-1.2.0."))
        assertTrue(INTEGRATED_CONSENT_DISCLOSURE_KO.contains("보행을 끝낸 뒤"))
        assertEquals("FP-013-RAW-1.2.0", kr.co.hanium.dreamup.walksafe.account.SIGNUP_DOCUMENT_VERSIONS["raw_original"])
        assertEquals(
            "FP-013-AUTO-1.2.0",
            kr.co.hanium.dreamup.walksafe.account.SIGNUP_DOCUMENT_VERSIONS["automatic_reporting"],
        )
        assertEquals(
            "FP-013-TRAINING-1.1.0",
            kr.co.hanium.dreamup.walksafe.account.SIGNUP_DOCUMENT_VERSIONS["training_reuse"],
        )
    }

    @Test
    fun aStored110ConfirmationCannotExistAndA110BootstrapIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            IntegratedConsentItemVersions(rawSourceCollection = "FP-013-RAW-1.1.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            IntegratedConsentItemVersions(automaticReporting = "FP-013-AUTO-1.1.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            IntegratedConsentConfirmation(
                schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
                policyVersion = "FP-013-1.1.0",
                installationId = "installation-0001",
                requestId = "request_id_1234567890",
                itemVersions = IntegratedConsentItemVersions(),
                clientRevision = 1L,
                revision = 1L,
                selections = IntegratedConsentSelections(rawSourceCollection = true, automaticReporting = true),
                confirmedAt = "2026-09-29T00:00:00.000Z",
                gatewayAuditRecordSha256 = "a".repeat(64),
                backendConsentReceiptSha256 = "c".repeat(64),
                controlSecret = "b".repeat(64),
            )
        }
        val v11 = """
            {"schema_version":"walksafe.integrated-consent-bootstrap.v1","status":"READY",
             "source":"CURRENT_CONSENT","installation_id":"installation-0001",
             "policy_version":"FP-013-1.1.0",
             "item_versions":{"raw_source_collection":"FP-013-RAW-1.1.0",
               "automatic_reporting":"FP-013-AUTO-1.1.0","mobile_network_transfer":"FP-013-MOBILE-1.0.0",
               "training_reuse":"FP-013-TRAINING-1.1.0"},
             "client_revision_floor":1,
             "selections":{"raw_source_collection":true,"automatic_reporting":true,
               "mobile_network_transfer":false,"training_reuse":false},
             "source_receipt_sha256":"${"c".repeat(64)}",
             "expected_previous_backend_receipt_sha256":"${"c".repeat(64)}"}
        """.trimIndent()
        assertNull(runCatching { validatedIntegratedConsentBootstrapOrNull(v11, "installation-0001") }.getOrNull())
    }

    @Test
    fun startupMigrationTurnsA110ConfirmationIntoADeniedReconsentWithoutDeletingQueuedData() {
        val migration = functionBlock("private fun migrateKnownIntegratedConsentPolicyAtStartup")
        assertTrue(migration.contains("storedPolicy !in KNOWN_PREVIOUS_INTEGRATED_CONSENT_POLICY_VERSIONS"))
        assertTrue(migration.contains("resetIntegratedConsentForReconsent()"))
        assertTrue(migration.contains(".remove(PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION)"))
        assertTrue(migration.contains(".remove(PREF_AUTOMATIC_REPORT_CONSENT_KEY)"))
        // Held, not deleted: the migration never revokes the RAW or automatic-report receipts.
        assertFalse(migration.contains("onConsentRevoked"))
        assertFalse(migration.contains("onAutomaticReportingRevoked"))
        assertFalse(migration.contains("reportQueueStore"))
        // The existing server bootstrap then asks again (RECONSENT_REQUIRED keeps every item off).
        val bootstrap = functionBlock("private fun startIntegratedConsentBootstrap")
        assertTrue(bootstrap.contains("integratedConsent=reconsent_required policy="))
    }

    private fun functionBlock(signature: String): String = MainActivityRefactorSource.function(source, signature)
}
