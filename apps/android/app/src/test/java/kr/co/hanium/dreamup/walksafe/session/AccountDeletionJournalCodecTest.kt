package kr.co.hanium.dreamup.walksafe.session

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountDeletionJournalCodecTest {
    @Test fun pendingJournalRoundTripKeepsAllItemsOriginalOrderAndExplicitNullFields() {
        val journal = pendingJournal()
        val encoded = AccountDeletionJournalCodec.encode(journal)
        val root = JSONObject(encoded)
        assertEquals("walksafe.account-deletion-journal.v2", root.getString("schema_version"))
        assertEquals(journal, AccountDeletionJournalCodec.decodeOrNull(encoded))
        val items = root.getJSONArray("items")
        assertEquals(DeletionInventoryItem.entries.size, items.length())
        DeletionInventoryItem.entries.forEachIndexed { index, item ->
            assertEquals(item.wireValue, items.getJSONObject(index).getString("key"))
        }
        assertTrue(root.has("accepted_at"))
        assertTrue(root.isNull("accepted_at"))
        assertTrue(root.has("device_evidence_expected_status_revision"))
        assertTrue(root.isNull("device_evidence_expected_status_revision"))
    }

    @Test fun acceptedDeviceEvidenceRoundTripKeepsTheServerAndDeviceIdentityTuple() {
        val journal = acceptedJournal()
        val result = AccountDeletionJournalCodec.decodeOrNull(AccountDeletionJournalCodec.encode(journal))
        assertEquals(journal, result)
        assertEquals(7L, result!!.accountGeneration)
        assertEquals(2L, result.deviceEvidenceExpectedStatusRevision)
        assertTrue(result.deviceEvidenceAcknowledged)
    }

    @Test fun strictIntegerFieldsRejectStringsFractionsAndBooleans() {
        for (field in listOf("client_revision", "server_revision", "account_generation", "device_evidence_expected_status_revision")) {
            for (invalid in listOf<Any>("2", 2.5, true)) {
                val root = JSONObject(AccountDeletionJournalCodec.encode(acceptedJournal())).put(field, invalid)
                assertNull("$field accepted $invalid", AccountDeletionJournalCodec.decodeOrNull(root.toString()))
            }
        }
        for (invalid in listOf<Any>("1", 1.5, true)) {
            val root = JSONObject(AccountDeletionJournalCodec.encode(pendingJournal()))
            root.getJSONArray("items").getJSONObject(0).put("item_revision", invalid)
            assertNull(AccountDeletionJournalCodec.decodeOrNull(root.toString()))
        }
    }

    @Test fun acknowledgedMustRemainARealBoolean() {
        for (invalid in listOf<Any>("false", 0, JSONObject.NULL)) {
            val root = JSONObject(AccountDeletionJournalCodec.encode(pendingJournal()))
                .put("device_evidence_acknowledged", invalid)
            assertNull(AccountDeletionJournalCodec.decodeOrNull(root.toString()))
        }
    }

    @Test fun extraAndMissingKeysRemainRejectedForTheEnvelopeAndEveryItem() {
        val encoded = AccountDeletionJournalCodec.encode(pendingJournal())
        assertNull(AccountDeletionJournalCodec.decodeOrNull(JSONObject(encoded).put("extra", 1).toString()))
        val missing = JSONObject(encoded).apply { remove("last_error_code") }
        assertNull(AccountDeletionJournalCodec.decodeOrNull(missing.toString()))
        val itemExtra = JSONObject(encoded)
        itemExtra.getJSONArray("items").getJSONObject(0).put("extra", 1)
        assertNull(AccountDeletionJournalCodec.decodeOrNull(itemExtra.toString()))
        val itemMissing = JSONObject(encoded)
        itemMissing.getJSONArray("items").getJSONObject(0).remove("restriction_reason")
        assertNull(AccountDeletionJournalCodec.decodeOrNull(itemMissing.toString()))
    }

    @Test fun inventoryOrderAndCompletenessCannotBeChanged() {
        val encoded = AccountDeletionJournalCodec.encode(pendingJournal())
        val reversed = JSONObject(encoded)
        val items = reversed.getJSONArray("items")
        val first = items.getJSONObject(0)
        items.put(0, items.getJSONObject(1)).put(1, first)
        assertNull(AccountDeletionJournalCodec.decodeOrNull(reversed.toString()))
        val missing = JSONObject(encoded)
        missing.getJSONArray("items").remove(0)
        assertNull(AccountDeletionJournalCodec.decodeOrNull(missing.toString()))
        val duplicate = JSONObject(encoded)
        duplicate.getJSONArray("items").put(duplicate.getJSONArray("items").getJSONObject(0))
        assertNull(AccountDeletionJournalCodec.decodeOrNull(duplicate.toString()))
    }

    @Test fun malformedSchemaPhaseAndDomainIdentityNeverProduceAJournal() {
        val encoded = AccountDeletionJournalCodec.encode(pendingJournal())
        assertNull(AccountDeletionJournalCodec.decodeOrNull("{bad-json"))
        assertNull(AccountDeletionJournalCodec.decodeOrNull(JSONObject(encoded).put("schema_version", "walksafe.account-deletion-journal.v1").toString()))
        assertNull(AccountDeletionJournalCodec.decodeOrNull(JSONObject(encoded).put("phase", "IDLE").toString()))
        assertNull(AccountDeletionJournalCodec.decodeOrNull(JSONObject(encoded).put("request_id", "short").toString()))
        assertNull(AccountDeletionJournalCodec.decodeOrNull(JSONObject(encoded).put("gateway_origin", "http://example.test/path").toString()))
        assertNull(AccountDeletionJournalCodec.decodeOrNull(JSONObject(encoded).put("device_evidence_acknowledged", true).toString()))
    }

    private fun pendingJournal() = AccountDeletionJournal.pending(
        gatewayOrigin = "https://gateway.example.test", installationId = "installation_0001",
        requestId = "account_delete_request_0001", requestedAt = "2026-10-06T00:00:00Z")

    private fun acceptedJournal() = pendingJournal().copy(
        phase = AccountDeletionPhase.IN_PROGRESS, serverRevision = 2L,
        acceptedAt = "2026-10-06T00:01:00Z", accountGeneration = 7L,
        tombstoneId = "tombstone_0001", requestReceiptSha256 = "a".repeat(64),
        deviceEvidenceId = "device_evidence_0001", deviceEvidenceSha256 = "b".repeat(64),
        deviceEvidenceExpectedStatusRevision = 2L, deviceEvidenceResult = "DELETED",
        deviceEvidenceCompletedAt = "2026-10-06T00:02:00Z", deviceEvidenceAcknowledged = true)
}
