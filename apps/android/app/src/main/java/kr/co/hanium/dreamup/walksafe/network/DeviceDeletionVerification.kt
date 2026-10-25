package kr.co.hanium.dreamup.walksafe.network

import java.time.Instant
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionOverallStatus
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStatus
import kr.co.hanium.dreamup.walksafe.session.DeletionInventoryItem
import kr.co.hanium.dreamup.walksafe.session.DeletionItemState
import org.json.JSONObject

/** Dynamic server projection, deliberately excluded from the canonical journal and receipt hash. */
data class DeviceDeletionVerification(
    val completionView: String,
    val targetCount: Long,
    val deletedCount: Long,
    val notFoundCount: Long,
    val failedCount: Long,
    val pendingCount: Long,
    val unverifiableCount: Long,
    val deviceClockAnomalyCount: Long,
    val deviceDueAt: String,
    val evaluatedAt: String,
    val unverifiableReason: String?,
    val followUp: String?,
)

internal data class AccountDeletionStatusView(
    val canonicalStatus: AccountDeletionStatus,
    val deviceVerification: DeviceDeletionVerification?,
)

internal fun validatedAccountDeletionStatusViewOrNull(
    body: String,
    expectedInstallationId: String,
    expectedRequestId: String,
): AccountDeletionStatusView? = runCatching {
    val root = JSONObject(body)
    if (!root.has("device_verification")) {
        return@runCatching validatedAccountDeletionStatusOrNull(body, expectedInstallationId, expectedRequestId)
            ?.let { AccountDeletionStatusView(it, null) }
    }
    val view = root.getJSONObject("device_verification")
    root.remove("device_verification")
    val status = validatedAccountDeletionStatusOrNull(root.toString(), expectedInstallationId, expectedRequestId)
        ?: return@runCatching null
    val countKeys = listOf("target_count", "deleted_count", "not_found_count", "failed_count",
        "pending_count", "unverifiable_count", "device_clock_anomaly_count")
    val stringKeys = setOf("schema_version", "completion_view", "device_due_at", "evaluated_at")
    if (view.keys().asSequence().toSet() != stringKeys + countKeys + setOf("unverifiable_reason", "follow_up") ||
        stringKeys.any { view.opt(it) !is String } ||
        view.getString("schema_version") != "walksafe.account-deletion-device-verification.v1") return@runCatching null
    fun count(name: String): Long = when (val value = view.get(name)) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("Device verification count is not an integer")
    }.also { require(it in 0L..9_007_199_254_740_991L) }
    fun nullableString(name: String): String? = if (view.isNull(name)) null else {
        require(view.opt(name) is String); view.getString(name)
    }
    val counts = countKeys.associateWith(::count)
    val completion = view.getString("completion_view")
    if (completion !in setOf("COMPLETED", "IN_PROGRESS", "SERVER_COMPLETED_DEVICE_UNVERIFIED")) return@runCatching null
    if (counts.getValue("target_count") != countKeys.slice(1..5).sumOf { counts.getValue(it) }) return@runCatching null
    val unresolved = counts.getValue("unverifiable_count") > 0L
    val reason = nullableString("unverifiable_reason")
    val followUp = nullableString("follow_up")
    if (reason != (if (unresolved) "SLA_ELAPSED_NO_CONTACT" else null) ||
        followUp != (if (unresolved) "DELETE_ON_NEXT_CONTACT_OR_REMOVE_APP" else null)) return@runCatching null
    if ((status.overallStatus == AccountDeletionOverallStatus.COMPLETED) != (completion == "COMPLETED")) return@runCatching null
    val failed = counts.getValue("failed_count"); val pending = counts.getValue("pending_count")
    if (completion == "COMPLETED" && (failed > 0L || pending > 0L || unresolved)) return@runCatching null
    if (completion == "SERVER_COMPLETED_DEVICE_UNVERIFIED" && (!unresolved || failed > 0L || pending > 0L ||
        status.items.any { (item, result) -> item != DeletionInventoryItem.DEVICE_UNSENT &&
            result.state !in setOf(DeletionItemState.COMPLETED, DeletionItemState.NOT_APPLICABLE) })) return@runCatching null
    val due = view.getString("device_due_at"); val evaluated = view.getString("evaluated_at")
    require(due.endsWith('Z') && evaluated.endsWith('Z'))
    Instant.parse(due); Instant.parse(evaluated)
    AccountDeletionStatusView(status, DeviceDeletionVerification(completion,
        counts.getValue("target_count"), counts.getValue("deleted_count"), counts.getValue("not_found_count"),
        failed, pending, counts.getValue("unverifiable_count"), counts.getValue("device_clock_anomaly_count"),
        due, evaluated, reason, followUp))
}.getOrNull()
