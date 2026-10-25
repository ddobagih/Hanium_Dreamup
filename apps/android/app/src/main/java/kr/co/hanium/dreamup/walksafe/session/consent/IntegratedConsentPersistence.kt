package kr.co.hanium.dreamup.walksafe.session.consent

import kr.co.hanium.dreamup.walksafe.session.*
import org.json.JSONArray
import org.json.JSONObject
import kr.co.hanium.dreamup.walksafe.network.MobileNetworkPreference

/** Same encrypted and plain preference peers; owns consent encoding without a second cache/store. */
internal class IntegratedConsentPersistence(val sensitive: SensitiveStore, val plain: PlainStore) {
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putLong(key: String, value: Long): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun commit(): Boolean
    }
    interface PlainStore { fun edit(): Editor }
    interface SensitiveStore {
        fun isBlocked(): Boolean
        fun failClosed()
        fun contains(key: String): Boolean
        fun getString(key: String, defaultValue: String?): String?
        fun getLong(key: String, defaultValue: Long): Long
        fun getBoolean(key: String, defaultValue: Boolean): Boolean
        fun edit(): Editor
    }
    private val sensitivePrefs get() = sensitive
    private fun JSONObject.jsonKeySet(): Set<String> {
        val result = mutableSetOf<String>()
        val iterator = keys()
        while (iterator.hasNext()) result += iterator.next()
        return result
    }

    fun integratedConsentConfirmationJson(
        confirmation: IntegratedConsentConfirmation,
    ): String =
        JSONObject()
            .put("schema_version", confirmation.schemaVersion)
            .put("policy_version", confirmation.policyVersion)
            .put("installation_id", confirmation.installationId)
            .put("request_id", confirmation.requestId)
            .put(
                "item_versions",
                JSONObject()
                    .put(
                        "raw_source_collection",
                        confirmation.itemVersions.rawSourceCollection,
                    )
                    .put(
                        "automatic_reporting",
                        confirmation.itemVersions.automaticReporting,
                    )
                    .put(
                        "mobile_network_transfer",
                        confirmation.itemVersions.mobileNetworkTransfer,
                    )
                    .put(
                        "training_reuse",
                        confirmation.itemVersions.trainingReuse,
                    ),
            )
            .put("client_revision", confirmation.clientRevision)
            .put("revision", confirmation.revision)
            .put(
                "selections",
                integratedConsentSelectionsJson(confirmation.selections),
            )
            .put("confirmed_at", confirmation.confirmedAt)
            .put(
                "gateway_audit_record_sha256",
                confirmation.gatewayAuditRecordSha256,
            )
            .put(
                "backend_consent_receipt_sha256",
                confirmation.backendConsentReceiptSha256,
            )
            .put("control_secret", confirmation.controlSecret)
            .toString()

    fun integratedConsentConfirmationOrNull(
        raw: String,
    ): IntegratedConsentConfirmation? = runCatching {
        val root = JSONObject(raw)
        if (
            root.jsonKeySet() != setOf(
                "schema_version",
                "policy_version",
                "installation_id",
                "request_id",
                "item_versions",
                "client_revision",
                "revision",
                "selections",
                "confirmed_at",
                "gateway_audit_record_sha256",
                "backend_consent_receipt_sha256",
                "control_secret",
            )
        ) return null
        val itemVersions = root.getJSONObject("item_versions")
        if (
            itemVersions.jsonKeySet() != setOf(
                "raw_source_collection",
                "automatic_reporting",
                "mobile_network_transfer",
                "training_reuse",
            )
        ) return null
        val selections =
            integratedConsentSelectionsOrNull(root.getJSONObject("selections"))
                ?: return null
        IntegratedConsentConfirmation(
            schemaVersion = root.getString("schema_version"),
            policyVersion = root.getString("policy_version"),
            installationId = root.getString("installation_id"),
            requestId = root.getString("request_id"),
            itemVersions =
                kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItemVersions(
                    rawSourceCollection =
                        itemVersions.getString("raw_source_collection"),
                    automaticReporting =
                        itemVersions.getString("automatic_reporting"),
                    mobileNetworkTransfer =
                        itemVersions.getString("mobile_network_transfer"),
                    trainingReuse =
                        itemVersions.getString("training_reuse"),
                ),
            clientRevision = root.getLong("client_revision"),
            revision = root.getLong("revision"),
            selections = selections,
            confirmedAt = root.getString("confirmed_at"),
            gatewayAuditRecordSha256 =
                root.getString("gateway_audit_record_sha256"),
            backendConsentReceiptSha256 =
                root.getString("backend_consent_receipt_sha256"),
            controlSecret = root.getString("control_secret"),
        )
    }.getOrNull()

    fun pendingIntegratedConsentMutationJson(
        mutation: PendingIntegratedConsentMutation,
    ): String = RejectedConsentCasJournalCodec.pendingJson(mutation)

    fun pendingIntegratedConsentMutationOrNull(
        raw: String,
    ): PendingIntegratedConsentMutation? = RejectedConsentCasJournalCodec.pendingOrNull(raw)

    fun rejectedConsentCasJournalJson(
        journal: RejectedConsentCasRecoveryJournal,
    ): String = RejectedConsentCasJournalCodec.journalJson(journal)

    fun rejectedConsentCasJournalOrNull(
        raw: String,
        pending: PendingIntegratedConsentMutation?,
    ): RejectedConsentCasRecoveryJournal? =
        RejectedConsentCasJournalCodec.journalOrNull(raw, pending)

    fun restoredPendingPreservesConfirmation(
        pending: PendingIntegratedConsentMutation,
        confirmation: IntegratedConsentConfirmation,
        actorSha256: String?,
        localClientRevision: Long,
        casJournal: RejectedConsentCasRecoveryJournal?,
    ): Boolean = RejectedConsentCasJournalCodec.restoredPendingPreservesConfirmation(
        pending, confirmation, actorSha256, localClientRevision, casJournal,
    )

    fun integratedConsentSelectionsJson(
        selections: IntegratedConsentSelections,
    ): JSONObject = RejectedConsentCasJournalCodec.selectionsJson(selections)

    fun integratedConsentSelectionsOrNull(
        value: JSONObject,
    ): IntegratedConsentSelections? = RejectedConsentCasJournalCodec.selectionsOrNull(value)

    fun localWithdrawalMarkerItemsOrNull(
        raw: String,
    ): Set<IntegratedConsentItem>? = runCatching {
        val array = JSONArray(raw)
        if (array.length() == 0) return null
        val items = mutableSetOf<IntegratedConsentItem>()
        for (index in 0 until array.length()) {
            val wireValue = array.getString(index)
            val item =
                IntegratedConsentItem.entries.singleOrNull { it.wireValue == wireValue }
                    ?: return null
            if (!items.add(item)) return null
        }
        items
    }.getOrNull()

    fun persistedIntegratedConsentSelections(): IntegratedConsentSelections =
        if (
            !sensitivePrefs.isBlocked() &&
            sensitivePrefs.getString(PREF_INTEGRATED_CONSENT_POLICY_VERSION, null) ==
            INTEGRATED_CONSENT_POLICY_VERSION
        ) {
            IntegratedConsentSelections(
                rawSourceCollection =
                    sensitivePrefs.getBoolean(PREF_REPORT_PRIVACY_CONSENT_KEY, false),
                automaticReporting =
                    sensitivePrefs.getBoolean(PREF_AUTOMATIC_REPORT_CONSENT_KEY, false),
                mobileNetworkTransfer =
                    MobileNetworkPreference.fromWireValue(
                        sensitivePrefs.getString(PREF_MOBILE_NETWORK_PREFERENCE_KEY, null),
                    ) == MobileNetworkPreference.ALLOW_CELLULAR,
                trainingReuse =
                    sensitivePrefs.getBoolean(PREF_TRAINING_REUSE_CONSENT_KEY, false),
            )
        } else {
            IntegratedConsentSelections()
        }

    companion object {
        const val PREF_REPORT_PRIVACY_CONSENT_KEY = "report_privacy_consent_granted"
        const val PREF_AUTOMATIC_REPORT_CONSENT_KEY = "automatic_report_consent_granted"
        const val PREF_MOBILE_NETWORK_PREFERENCE_KEY = "mobile_network_preference"
        const val PREF_TRAINING_REUSE_CONSENT_KEY = "training_reuse_consent_granted"
        const val PREF_INTEGRATED_CONSENT_POLICY_VERSION = "integrated_consent_policy_version"
        const val PREF_INTEGRATED_CONSENT_REVISION = "integrated_consent_revision"
        const val PREF_INTEGRATED_CONSENT_CLIENT_REVISION = "integrated_consent_client_revision"
        const val PREF_INTEGRATED_CONSENT_RECEIPT_SHA256 = "integrated_consent_receipt_sha256"
        const val PREF_INTEGRATED_CONSENT_BACKEND_RECEIPT_SHA256 = "integrated_consent_backend_receipt_sha256_v2"
        const val PREF_INTEGRATED_CONSENT_GATEWAY_AUDIT_SHA256 = "integrated_consent_gateway_audit_sha256_v2"
        const val PREF_INTEGRATED_CONSENT_ACTOR_SHA256 = "integrated_consent_actor_sha256_v1"
        const val PREF_INTEGRATED_CONSENT_CONTROL_SECRET = "integrated_consent_control_secret"
        const val PREF_PENDING_INTEGRATED_CONSENT_MUTATION = "pending_integrated_consent_mutation_v1"
        const val PREF_PENDING_AUTOMATIC_REPORT_CLEANUP = "pending_automatic_report_cleanup_v1"
        const val PREF_REJECTED_CONSENT_CAS_JOURNAL = "rejected_integrated_consent_cas_journal_v1"
        const val PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION = "integrated_consent_server_confirmation_v2"
        const val PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED = "local_withdrawal_fail_closed_v1"
        const val PREF_RAW_SOURCE_FIELD_LOG_BLOCKED = "raw_source_field_log_blocked_v1"
        val INTEGRATED_CONSENT_RECEIPT_SHA256 = Regex("^[0-9a-f]{64}$")
        val INTEGRATED_CONSENT_CONTROL_SECRET = Regex("^[0-9a-f]{64}$")
    }
}
