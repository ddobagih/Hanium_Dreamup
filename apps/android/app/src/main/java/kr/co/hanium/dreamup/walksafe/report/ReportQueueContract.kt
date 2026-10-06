package kr.co.hanium.dreamup.walksafe.report

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.network.GatewayCredentialPolicy
import kr.co.hanium.dreamup.walksafe.network.GatewayEndpointPolicy
import org.json.JSONObject

internal enum class ReportQueuePriority {
    EXPLICIT,
    AUTOMATIC,
}

internal data class ApprovedReportQueueCapacityProfile(
    val maxEntries: Int,
    val maxPayloadBytes: Int,
    val maxStoredEntryBytes: Long,
    val maxTotalBytes: Long,
    val automaticMaxEntries: Int,
    val automaticMaxTotalBytes: Long,
) {
    init {
        require(maxEntries >= 2)
        require(maxPayloadBytes in 1..REPORT_QUEUE_MAX_PAYLOAD_BYTES)
        require(maxStoredEntryBytes in maxPayloadBytes.toLong()..REPORT_QUEUE_MAX_STORED_ENTRY_BYTES)
        require(maxTotalBytes >= maxStoredEntryBytes)
        require(automaticMaxEntries in 1 until maxEntries)
        require(automaticMaxTotalBytes >= maxStoredEntryBytes)
        require(automaticMaxTotalBytes < maxTotalBytes)
        require(maxTotalBytes - automaticMaxTotalBytes >= maxStoredEntryBytes)
    }
}

internal fun approvedReportQueueCapacityProfile(
    enabled: Boolean,
    maxEntries: Int,
    maxPayloadBytes: Int,
    maxStoredEntryBytes: Long,
    maxTotalBytes: Long,
    automaticMaxEntries: Int,
    automaticMaxTotalBytes: Long,
): ApprovedReportQueueCapacityProfile? {
    if (!enabled) return null
    return runCatching {
        ApprovedReportQueueCapacityProfile(
            maxEntries = maxEntries,
            maxPayloadBytes = maxPayloadBytes,
            maxStoredEntryBytes = maxStoredEntryBytes,
            maxTotalBytes = maxTotalBytes,
            automaticMaxEntries = automaticMaxEntries,
            automaticMaxTotalBytes = automaticMaxTotalBytes,
        )
    }.getOrNull()
}

/** Owns plaintext copies. The caller must close after its last use; getters return caller-owned copies. */
internal class FrozenReportPayload private constructor(
    val reportId: String,
    metadataUtf8: ByteArray,
    imageJpeg: ByteArray,
    val payloadSha256: String,
) : java.io.Closeable {
    private val frozenMetadata = metadataUtf8.copyOf()
    private val frozenImage = imageJpeg.copyOf()

    val payloadBytes: Long = frozenMetadata.size.toLong() + frozenImage.size.toLong()

    private var closed = false

    fun metadataUtf8(): ByteArray {
        check(!closed) { "report payload is closed" }
        return frozenMetadata.copyOf()
    }

    fun imageJpeg(): ByteArray {
        check(!closed) { "report payload is closed" }
        return frozenImage.copyOf()
    }

    override fun close() {
        frozenMetadata.fill(0)
        frozenImage.fill(0)
        closed = true
    }

    internal fun contentEquals(other: FrozenReportPayload): Boolean =
        !closed && !other.closed && reportId == other.reportId &&
            payloadSha256 == other.payloadSha256 &&
            frozenMetadata.contentEquals(other.frozenMetadata) &&
            frozenImage.contentEquals(other.frozenImage)

    companion object {
        fun freeze(
            reportId: String,
            metadataUtf8: ByteArray,
            imageJpeg: ByteArray,
        ): FrozenReportPayload? {
            if (
                !isCanonicalReportUuid(reportId) ||
                metadataUtf8.isEmpty() ||
                !isStrictUtf8(metadataUtf8) ||
                !isJpeg(imageJpeg)
            ) {
                return null
            }
            val digest = reportPayloadSha256(reportId, metadataUtf8, imageJpeg)
            return FrozenReportPayload(reportId, metadataUtf8, imageJpeg, digest)
        }
    }
}

internal data class QueuedReport(
    val payload: FrozenReportPayload,
    val priority: ReportQueuePriority,
    val reporterActorId: String,
    val walkSessionId: String,
    val consentReceiptSha256: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val accountGeneration: Long? = null,
    val quarantined: Boolean = false,
    val retryNotBeforeEpochMs: Long = 0L,
) : java.io.Closeable {
    // copy() shares the payload owner; it does not create a separate plaintext snapshot.
    override fun close() = payload.close()
}

internal fun reportActorIdFromMetadataOrNull(metadataUtf8: ByteArray): String? = runCatching {
    val root = JSONObject(String(metadataUtf8, Charsets.UTF_8))
    val actorId = root.get("reporter_user_id") as? String ?: return@runCatching null
    GatewayCredentialPolicy.normalizedActorIdOrNull(actorId)
        ?.takeIf { it == actorId }
}.getOrNull()

/**
 * Queue priority implied by the frozen metadata. Automatic entries must carry `trigger=auto` with
 * `auto_reported=true`; explicit entries must carry a voice/on-screen trigger with
 * `auto_reported=false`. Missing, non-string/non-boolean or contradictory values yield null.
 */
internal fun reportQueuePriorityFromMetadataOrNull(metadataUtf8: ByteArray): ReportQueuePriority? =
    runCatching {
        val root = JSONObject(String(metadataUtf8, Charsets.UTF_8))
        val trigger = root.get("trigger") as? String ?: return@runCatching null
        val automatic = root.get("auto_reported") as? Boolean ?: return@runCatching null
        when {
            automatic && trigger == AndroidReportCandidatePolicy.TRIGGER_AUTO ->
                ReportQueuePriority.AUTOMATIC
            !automatic && (
                trigger == AndroidReportCandidatePolicy.TRIGGER_VOICE ||
                    trigger == AndroidReportCandidatePolicy.TRIGGER_ON_SCREEN
                ) -> ReportQueuePriority.EXPLICIT
            else -> null
        }
    }.getOrNull()

internal data class ReportQueueReceipt(
    val reportId: String,
    val payloadSha256: String,
    val payloadBytes: Long,
    val marker: String,
    val persistenceMarker: String,
)

internal fun reportPayloadSha256(
    reportId: String,
    metadataUtf8: ByteArray,
    imageJpeg: ByteArray,
): String {
    require(isCanonicalReportUuid(reportId))
    return MessageDigest.getInstance("SHA-256").apply {
        update(REPORT_PAYLOAD_DOMAIN)
        update(reportId.toByteArray(StandardCharsets.US_ASCII))
        update(u64be(metadataUtf8.size.toLong()))
        update(metadataUtf8)
        update(u64be(imageJpeg.size.toLong()))
        update(imageJpeg)
    }.digest().joinToString("") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }
}

internal fun ReportQueueReceipt.matches(report: QueuedReport): Boolean =
    reportId == report.payload.reportId &&
        payloadSha256 == report.payload.payloadSha256 &&
        payloadBytes == report.payload.payloadBytes &&
        marker == REPORT_RECEIPT_MARKER &&
        isCanonicalReportUuid(persistenceMarker)

internal fun isCanonicalReportUuid(value: String): Boolean = runCatching {
    UUID.fromString(value).toString() == value
}.getOrDefault(false)

internal fun newCanonicalReportId(idFactory: () -> UUID = UUID::randomUUID): String =
    idFactory().toString()

private fun u64be(value: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES)
    .order(ByteOrder.BIG_ENDIAN)
    .putLong(value)
    .array()

private fun isStrictUtf8(value: ByteArray): Boolean = runCatching {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value))
    true
}.getOrDefault(false)

private fun isJpeg(value: ByteArray): Boolean = JpegStructureValidator.isStructurallyValid(value)

internal fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

internal const val REPORT_QUEUE_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
internal const val REPORT_RECEIPT_MARKER = "DATABASE_AND_ENCRYPTED_IMAGE_STORE"
internal const val REPORT_QUEUE_MAX_PAYLOAD_BYTES = 16 * 1_024 * 1_024
internal const val REPORT_QUEUE_MAX_STORED_ENTRY_BYTES = 44L * 1_024L * 1_024L
internal val REPORT_SHA256_HEX = Regex("[0-9a-f]{64}")
internal val PRODUCTION_REPORT_QUEUE_CAPACITY_PROFILE: ApprovedReportQueueCapacityProfile? =
    approvedReportQueueCapacityProfile(
        enabled = BuildConfig.DEBUG && BuildConfig.WALKSAFE_REPORT_QUEUE_ENABLED,
        maxEntries = BuildConfig.WALKSAFE_REPORT_QUEUE_MAX_ENTRIES,
        maxPayloadBytes = BuildConfig.WALKSAFE_REPORT_QUEUE_MAX_PAYLOAD_BYTES,
        maxStoredEntryBytes = BuildConfig.WALKSAFE_REPORT_QUEUE_MAX_STORED_ENTRY_BYTES,
        maxTotalBytes = BuildConfig.WALKSAFE_REPORT_QUEUE_MAX_TOTAL_BYTES,
        automaticMaxEntries = BuildConfig.WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_ENTRIES,
        automaticMaxTotalBytes = BuildConfig.WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_TOTAL_BYTES,
    )

internal fun approvedReportQueueGatewayOriginOrNull(raw: String?): String? =
    if (!BuildConfig.DEBUG || !BuildConfig.WALKSAFE_REPORT_QUEUE_ENABLED) {
        null
    } else {
        GatewayEndpointPolicy.approvedDebugOriginOrNull(
            raw = raw,
            approvedOrigin = BuildConfig.WALKSAFE_REPORT_QUEUE_TEST_ORIGIN,
        )
    }
private val REPORT_PAYLOAD_DOMAIN = "walksafe-report-payload-v1\u0000".toByteArray(Charsets.US_ASCII)
