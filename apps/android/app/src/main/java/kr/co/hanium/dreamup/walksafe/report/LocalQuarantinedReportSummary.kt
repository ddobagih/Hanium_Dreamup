package kr.co.hanium.dreamup.walksafe.report

/** Metadata only: no image, decrypted payload or online acceptance claim leaves the queue. */
internal data class LocalQuarantinedReportSummary(
    val reportId: String,
    val reporterActorId: String,
    val accountGeneration: Long?,
    val payloadSha256: String,
    val payloadBytes: Long,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val priority: ReportQueuePriority,
    val capturedAt: String,
    val className: String,
    val confidence: Double,
) {
    fun descriptionKo(): String {
        val captured = java.time.Instant.parse(capturedAt).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("M월 d일 HH시 mm분", java.util.Locale.KOREAN))
        return "기기에 보관한 손상 점자블록 신고. 촬영 시각 $captured. " +
            if (priority == ReportQueuePriority.EXPLICIT) "직접 확인한 신고입니다." else "자동 신고입니다."
    }
}

internal fun QueuedReport.quarantineSummaryOrNull(): LocalQuarantinedReportSummary? {
    if (!quarantined) return null
    val metadata = payload.metadataUtf8()
    return try {
        runCatching {
            val encoded = String(metadata, Charsets.UTF_8)
            require(strictJsonObjectDocument(encoded))
            val value = org.json.JSONObject(encoded)
            val confidence = (value.get("confidence") as? Number)?.toDouble() ?: error("confidence missing")
            require(confidence.isFinite() && confidence in 0.0..1.0)
            val className = value.get("class_name") as? String ?: error("class missing")
            require(AndroidReportCandidatePolicy.isReportableDamageClass(className))
            val capturedAt = value.get("captured_at") as? String ?: error("capture time missing")
            java.time.Instant.parse(capturedAt)
            LocalQuarantinedReportSummary(payload.reportId, reporterActorId, accountGeneration,
                payload.payloadSha256, payload.payloadBytes, createdAtEpochMs, expiresAtEpochMs,
                priority, capturedAt, className, confidence)
        }.getOrNull()
    } finally { metadata.fill(0) }
}
