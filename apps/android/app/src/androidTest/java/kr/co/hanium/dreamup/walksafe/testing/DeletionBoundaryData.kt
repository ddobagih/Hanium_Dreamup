package kr.co.hanium.dreamup.walksafe.testing

import java.time.Instant
import kr.co.hanium.dreamup.walksafe.session.*
import org.json.JSONArray
import org.json.JSONObject

/** Canonical synthetic v2 status only. No local deletion participant or real server is invoked. */
internal object DeletionBoundaryData {
    const val requestId = "synthetic_delete_request_0001"
    const val tombstone = "synthetic-tombstone-0001"
    val receipt = "1".repeat(64)
    val acceptedAt: String = "2026-10-10T00:00:00Z"

    fun statusBody(request: String = requestId): String {
        val accepted = Instant.parse(acceptedAt)
        val items = JSONArray()
        DeletionInventoryItem.entries.forEach { item -> items.put(JSONObject()
            .put("key", item.wireValue).put("status", "IN_PROGRESS").put("item_revision", 1)
            .put("due_at", accepted.plusMillis(item.maximumSlaMs).toString())
            .put("updated_at", accepted.plusSeconds(10).toString())
            .put("evidence_sha256", JSONObject.NULL).put("disposition_basis", JSONObject.NULL)
            .put("retry_after", JSONObject.NULL).put("restriction_reason", JSONObject.NULL)
            .put("legal_hold_review_at", JSONObject.NULL).put("legal_hold_contact", JSONObject.NULL).put("terminal_at", JSONObject.NULL)) }
        return JSONObject().put("schema_version", ACCOUNT_DELETION_STATUS_SCHEMA_VERSION)
            .put("request_id", request).put("client_revision", 1).put("revision", 1)
            .put("accepted_at", acceptedAt).put("updated_at", accepted.plusSeconds(10).toString())
            .put("account_generation", 7).put("tombstone_id", tombstone).put("request_receipt_sha256", receipt)
            .put("overall_status", "PROCESSING").put("items", items).put("completion_receipt_sha256", JSONObject.NULL).toString()
    }

    fun journal(origin: String, installation: String): AccountDeletionJournal = AccountDeletionJournal(
        gatewayOrigin = origin, installationId = installation, requestId = requestId,
        requestedAt = acceptedAt, phase = AccountDeletionPhase.IN_PROGRESS, serverRevision = 1,
        items = DeletionInventoryItem.entries.associateWith { item -> DeletionItemStatus(item = item,
            state = DeletionItemState.IN_PROGRESS, itemRevision = 1,
            dueAt = Instant.parse(acceptedAt).plusMillis(item.maximumSlaMs).toString(),
            updatedAt = Instant.parse(acceptedAt).plusSeconds(10).toString()) },
        acceptedAt = acceptedAt, accountGeneration = 7, tombstoneId = tombstone, requestReceiptSha256 = receipt,
    )
}
