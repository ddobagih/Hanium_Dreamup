package kr.co.hanium.dreamup.walksafe.session

import org.json.JSONArray
import org.json.JSONObject

/** Exact journal schema for the account-deletion writer and startup reader. */
internal object AccountDeletionJournalCodec {
    private const val ACCOUNT_DELETION_JOURNAL_SCHEMA_VERSION = "walksafe.account-deletion-journal.v2"

    fun encode(
        journal: AccountDeletionJournal,
    ): String {
        val items = JSONArray()
        DeletionInventoryItem.entries.forEach { item ->
            val status = journal.items.getValue(item)
            items.put(
                JSONObject()
                    .put("key", item.wireValue)
                    .put("status", status.state.wireValue)
                    .put("item_revision", status.itemRevision)
                    .put("due_at", status.dueAt)
                    .put("updated_at", status.updatedAt)
                    .put("evidence_sha256", status.evidenceSha256 ?: JSONObject.NULL)
                    .put("disposition_basis", status.dispositionBasis ?: JSONObject.NULL)
                    .put("retry_after", status.nextRetryAt ?: JSONObject.NULL)
                    .put("restriction_reason", status.reasonCode ?: JSONObject.NULL)
                    .put(
                        "legal_hold_review_at",
                        status.legalHoldReviewAt ?: JSONObject.NULL,
                    )
                    .put("legal_hold_contact", status.contactUrl ?: JSONObject.NULL)
                    .put("terminal_at", status.terminalAt ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("schema_version", ACCOUNT_DELETION_JOURNAL_SCHEMA_VERSION)
            .put("gateway_origin", journal.gatewayOrigin)
            .put("installation_id", journal.installationId)
            .put("request_id", journal.requestId)
            .put("requested_at", journal.requestedAt)
            .put("phase", journal.phase.name)
            .put("client_revision", journal.clientRevision)
            .put("server_revision", journal.serverRevision)
            .put("accepted_at", journal.acceptedAt ?: JSONObject.NULL)
            .put("account_generation", journal.accountGeneration ?: JSONObject.NULL)
            .put("tombstone_id", journal.tombstoneId ?: JSONObject.NULL)
            .put(
                "request_receipt_sha256",
                journal.requestReceiptSha256 ?: JSONObject.NULL,
            )
            .put("items", items)
            .put(
                "completion_receipt_sha256",
                journal.receiptSha256 ?: JSONObject.NULL,
            )
            .put("last_error_code", journal.lastErrorCode ?: JSONObject.NULL)
            .put("device_evidence_id", journal.deviceEvidenceId ?: JSONObject.NULL)
            .put(
                "device_evidence_sha256",
                journal.deviceEvidenceSha256 ?: JSONObject.NULL,
            )
            .put(
                "device_evidence_expected_status_revision",
                journal.deviceEvidenceExpectedStatusRevision ?: JSONObject.NULL,
            )
            .put(
                "device_evidence_result",
                journal.deviceEvidenceResult ?: JSONObject.NULL,
            )
            .put(
                "device_evidence_completed_at",
                journal.deviceEvidenceCompletedAt ?: JSONObject.NULL,
            )
            .put(
                "device_evidence_acknowledged",
                journal.deviceEvidenceAcknowledged,
            )
            .toString()
    }

    fun decodeOrNull(
        raw: String,
    ): AccountDeletionJournal? = runCatching {
        val root = JSONObject(raw)
        if (
            root.jsonKeySet() != setOf(
                "schema_version",
                "gateway_origin",
                "installation_id",
                "request_id",
                "requested_at",
                "phase",
                "client_revision",
                "server_revision",
                "accepted_at",
                "account_generation",
                "tombstone_id",
                "request_receipt_sha256",
                "items",
                "completion_receipt_sha256",
                "last_error_code",
                "device_evidence_id",
                "device_evidence_sha256",
                "device_evidence_expected_status_revision",
                "device_evidence_result",
                "device_evidence_completed_at",
                "device_evidence_acknowledged",
            ) ||
            root.getString("schema_version") != ACCOUNT_DELETION_JOURNAL_SCHEMA_VERSION
        ) return null
        val array = root.getJSONArray("items")
        if (array.length() != DeletionInventoryItem.entries.size) return null
        val items = mutableMapOf<DeletionInventoryItem, DeletionItemStatus>()
        for (index in 0 until array.length()) {
            val value = array.getJSONObject(index)
            if (
                value.jsonKeySet() != setOf(
                    "key",
                    "status",
                    "item_revision",
                    "due_at",
                    "updated_at",
                    "evidence_sha256",
                    "disposition_basis",
                    "retry_after",
                    "restriction_reason",
                    "legal_hold_review_at",
                    "legal_hold_contact",
                    "terminal_at",
                )
            ) return null
            val item = DeletionInventoryItem.entries[index]
            if (value.getString("key") != item.wireValue) return null
            val state =
                DeletionItemState.fromWireValue(value.getString("status"))
                    ?: return null
            items[item] = DeletionItemStatus(
                item = item,
                state = state,
                itemRevision = value.strictJsonLong("item_revision"),
                dueAt = value.getString("due_at"),
                updatedAt = value.getString("updated_at"),
                evidenceSha256 = value.nullableJsonString("evidence_sha256"),
                dispositionBasis = value.nullableJsonString("disposition_basis"),
                nextRetryAt = value.nullableJsonString("retry_after"),
                reasonCode = value.nullableJsonString("restriction_reason"),
                legalHoldReviewAt = value.nullableJsonString("legal_hold_review_at"),
                contactUrl = value.nullableJsonString("legal_hold_contact"),
                terminalAt = value.nullableJsonString("terminal_at"),
            )
        }
        AccountDeletionJournal(
            gatewayOrigin = root.getString("gateway_origin"),
            installationId = root.getString("installation_id"),
            requestId = root.getString("request_id"),
            requestedAt = root.getString("requested_at"),
            phase = AccountDeletionPhase.valueOf(root.getString("phase")),
            clientRevision = root.strictJsonLong("client_revision"),
            serverRevision = root.strictJsonLong("server_revision"),
            items = items,
            acceptedAt = root.nullableJsonString("accepted_at"),
            accountGeneration = root.nullableJsonLong("account_generation"),
            tombstoneId = root.nullableJsonString("tombstone_id"),
            requestReceiptSha256 = root.nullableJsonString("request_receipt_sha256"),
            receiptSha256 = root.nullableJsonString("completion_receipt_sha256"),
            lastErrorCode = root.nullableJsonString("last_error_code"),
            deviceEvidenceId = root.nullableJsonString("device_evidence_id"),
            deviceEvidenceSha256 = root.nullableJsonString("device_evidence_sha256"),
            deviceEvidenceExpectedStatusRevision =
                root.nullableJsonLong("device_evidence_expected_status_revision"),
            deviceEvidenceResult = root.nullableJsonString("device_evidence_result"),
            deviceEvidenceCompletedAt =
                root.nullableJsonString("device_evidence_completed_at"),
            deviceEvidenceAcknowledged =
                root.strictJsonBoolean("device_evidence_acknowledged"),
        )
    }.getOrNull()

    private fun JSONObject.jsonKeySet(): Set<String> {
        val result = mutableSetOf<String>()
        val iterator = keys()
        while (iterator.hasNext()) result += iterator.next()
        return result
    }

    private fun JSONObject.nullableJsonString(name: String): String? =
        if (isNull(name)) null else getString(name)

    private fun JSONObject.nullableJsonLong(name: String): Long? =
        if (isNull(name)) null else strictJsonLong(name)

    private fun JSONObject.strictJsonLong(name: String): Long =
        when (val value = get(name)) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> throw IllegalArgumentException("$name must be an integer")
        }

    private fun JSONObject.strictJsonBoolean(name: String): Boolean =
        get(name) as? Boolean
            ?: throw IllegalArgumentException("$name must be a boolean")
}
