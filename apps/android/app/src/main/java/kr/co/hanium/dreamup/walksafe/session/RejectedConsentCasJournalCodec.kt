package kr.co.hanium.dreamup.walksafe.session

import org.json.JSONArray
import org.json.JSONObject

/** Strict codec shared by the Android pending-request writer and process-restart reader. */
internal object RejectedConsentCasJournalCodec {
    private const val PENDING_SCHEMA = "walksafe.pending-integrated-consent-mutation.v2"
    private const val JOURNAL_SCHEMA = "walksafe.rejected-consent-cas-journal.v2"
    const val MAX_JOURNAL_BYTES = 32 * 1024

    fun pendingJson(mutation: PendingIntegratedConsentMutation): String = JSONObject()
        .put("schema_version", PENDING_SCHEMA)
        .put("installation_id", mutation.installationId)
        .put("actor_sha256", mutation.actorSha256)
        .put("request_id", mutation.requestId)
        .put("policy_version", mutation.policyVersion)
        .put("client_revision", mutation.clientRevision)
        .put("previous_server_revision", mutation.previousServerRevision)
        .put("expected_previous_backend_receipt_sha256",
            mutation.expectedPreviousBackendReceiptSha256 ?: JSONObject.NULL)
        .put("desired_selections", selectionsJson(mutation.desiredSelections))
        .put("withdrawal_items", JSONArray(
            mutation.withdrawalItems.map(IntegratedConsentItem::wireValue)))
        .put("created_at_epoch_ms", mutation.createdAtEpochMs)
        .toString()

    fun pendingOrNull(raw: String): PendingIntegratedConsentMutation? = runCatching {
        val root = JSONObject(raw)
        if (root.keySet() != setOf(
            "schema_version", "installation_id", "actor_sha256", "request_id",
            "policy_version", "client_revision", "previous_server_revision",
            "expected_previous_backend_receipt_sha256", "desired_selections",
            "withdrawal_items", "created_at_epoch_ms",
        ) || root.getString("schema_version") != PENDING_SCHEMA) return null
        val withdrawals = root.getJSONArray("withdrawal_items")
        val items = mutableSetOf<IntegratedConsentItem>()
        for (index in 0 until withdrawals.length()) {
            val item = IntegratedConsentItem.entries.singleOrNull {
                it.wireValue == withdrawals.getString(index)
            } ?: return null
            if (!items.add(item)) return null
        }
        PendingIntegratedConsentMutation(
            installationId = root.getString("installation_id"),
            actorSha256 = root.getString("actor_sha256"),
            requestId = root.getString("request_id"),
            policyVersion = root.getString("policy_version"),
            clientRevision = root.getLong("client_revision"),
            previousServerRevision = root.getLong("previous_server_revision"),
            expectedPreviousBackendReceiptSha256 =
                if (root.isNull("expected_previous_backend_receipt_sha256")) null
                else root.getString("expected_previous_backend_receipt_sha256"),
            desiredSelections = selectionsOrNull(root.getJSONObject("desired_selections"))
                ?: return null,
            withdrawalItems = items,
            createdAtEpochMs = root.getLong("created_at_epoch_ms"),
        )
    }.getOrNull()

    fun journalJson(journal: RejectedConsentCasRecoveryJournal): String {
        val transition = journal.latestTransition
        return JSONObject()
            .put("schema_version", JOURNAL_SCHEMA)
            .put("first_rejected", JSONObject(pendingJson(journal.firstRejected)))
            .put("latest_transition", JSONObject()
                .put("original", JSONObject(pendingJson(transition.original)))
                .put("backend_selections", selectionsJson(transition.backendSelections))
                .put("backend_receipt_sha256", transition.backendReceiptSha256)
                .put("backend_client_revision_floor", transition.backendClientRevisionFloor)
                .put("successor", JSONObject(pendingJson(transition.successor))))
            .put("cumulative_denials", JSONArray(
                journal.cumulativeDenials.map(IntegratedConsentItem::wireValue)))
            .put("rejected_count", journal.rejectedCount)
            .toString()
    }

    fun journalOrNull(
        raw: String,
        pending: PendingIntegratedConsentMutation?,
    ): RejectedConsentCasRecoveryJournal? = runCatching {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_JOURNAL_BYTES) return null
        val root = JSONObject(raw)
        if (root.keySet() != setOf(
            "schema_version", "first_rejected", "latest_transition",
            "cumulative_denials", "rejected_count",
        ) || root.getString("schema_version") != JOURNAL_SCHEMA) return null
        val first = pendingOrNull(root.getJSONObject("first_rejected").toString())
            ?: return null
        val entry = root.getJSONObject("latest_transition")
        if (entry.keySet() != setOf(
            "original", "backend_selections", "backend_receipt_sha256",
            "backend_client_revision_floor", "successor",
        )) return null
        val original = pendingOrNull(entry.getJSONObject("original").toString())
            ?: return null
        val successor = pendingOrNull(entry.getJSONObject("successor").toString())
            ?: return null
        val denials = root.getJSONArray("cumulative_denials")
        val items = mutableSetOf<IntegratedConsentItem>()
        for (index in 0 until denials.length()) {
            val item = IntegratedConsentItem.entries.singleOrNull {
                it.wireValue == denials.getString(index)
            } ?: return null
            if (!items.add(item)) return null
        }
        val transition = RejectedConsentCasSupersession(
            original = original,
            backendSelections = selectionsOrNull(entry.getJSONObject("backend_selections"))
                ?: return null,
            backendReceiptSha256 = entry.getString("backend_receipt_sha256"),
            backendClientRevisionFloor = entry.getLong("backend_client_revision_floor"),
            successor = successor,
        )
        val journal = RejectedConsentCasRecoveryJournal(
            firstRejected = first,
            latestTransition = transition,
            cumulativeDenials = items,
            rejectedCount = root.getLong("rejected_count"),
        )
        if (journal.successor != pending) return null
        journal
    }.getOrNull()

    fun restoredPendingPreservesConfirmation(
        pending: PendingIntegratedConsentMutation,
        confirmation: IntegratedConsentConfirmation,
        actorSha256: String?,
        localClientRevision: Long,
        journal: RejectedConsentCasRecoveryJournal?,
    ): Boolean = if (journal == null) {
        pending.preservesRestoredConfirmation(
            confirmation, actorSha256, localClientRevision)
    } else {
        journal.successor == pending &&
            pending.clientRevision == localClientRevision &&
            journal.firstRejected.preservesRestoredConfirmation(
                confirmation, actorSha256, journal.firstRejected.clientRevision)
    }

    fun selectionsJson(selections: IntegratedConsentSelections): JSONObject = JSONObject()
        .put("raw_source_collection", selections.rawSourceCollection)
        .put("automatic_reporting", selections.automaticReporting)
        .put("mobile_network_transfer", selections.mobileNetworkTransfer)
        .put("training_reuse", selections.trainingReuse)

    fun selectionsOrNull(value: JSONObject): IntegratedConsentSelections? {
        if (value.keySet() != setOf(
            "raw_source_collection", "automatic_reporting",
            "mobile_network_transfer", "training_reuse",
        )) return null
        return runCatching { IntegratedConsentSelections(
            rawSourceCollection = value.getBoolean("raw_source_collection"),
            automaticReporting = value.getBoolean("automatic_reporting"),
            mobileNetworkTransfer = value.getBoolean("mobile_network_transfer"),
            trainingReuse = value.getBoolean("training_reuse"),
        ) }.getOrNull()
    }

    private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()
}
