package kr.co.hanium.dreamup.walksafe.report

import java.io.ByteArrayOutputStream
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

internal class FrozenReportPayload private constructor(
    val reportId: String,
    metadataUtf8: ByteArray,
    imageJpeg: ByteArray,
    val payloadSha256: String,
) {
    private val frozenMetadata = metadataUtf8.copyOf()
    private val frozenImage = imageJpeg.copyOf()

    val payloadBytes: Long = frozenMetadata.size.toLong() + frozenImage.size.toLong()

    fun metadataUtf8(): ByteArray = frozenMetadata.copyOf()

    fun imageJpeg(): ByteArray = frozenImage.copyOf()

    internal fun contentEquals(other: FrozenReportPayload): Boolean =
        reportId == other.reportId &&
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
)

internal fun reportActorIdFromMetadataOrNull(metadataUtf8: ByteArray): String? = runCatching {
    val root = JSONObject(String(metadataUtf8, Charsets.UTF_8))
    val actorId = root.get("reporter_user_id") as? String ?: return@runCatching null
    GatewayCredentialPolicy.normalizedActorIdOrNull(actorId)
        ?.takeIf { it == actorId }
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
    val preimage = ByteArrayOutputStream().use { output ->
        output.write(REPORT_PAYLOAD_DOMAIN)
        output.write(reportId.toByteArray(StandardCharsets.US_ASCII))
        output.write(u64be(metadataUtf8.size.toLong()))
        output.write(metadataUtf8)
        output.write(u64be(imageJpeg.size.toLong()))
        output.write(imageJpeg)
        output.toByteArray()
    }
    val digest = sha256Hex(preimage)
    preimage.fill(0)
    return digest
}

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

private fun isJpeg(value: ByteArray): Boolean =
    value.size >= 4 &&
        value[0] == 0xff.toByte() &&
        value[1] == 0xd8.toByte() &&
        value[value.lastIndex - 1] == 0xff.toByte() &&
        value[value.lastIndex] == 0xd9.toByte()

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
