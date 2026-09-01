package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.network.GatewayCredentialPolicy

internal const val EXPLICIT_REPORT_CONFIRMATION_VALIDITY_MS = 30_000L
internal const val EXPLICIT_REPORT_DISCLOSURE_VALIDITY_MS = 120_000L

internal data class ExplicitReportConfirmationContext(
    val walkSessionId: String,
    val recoveryGeneration: Long,
    val reporterId: String,
    val consentReceiptSha256: String,
    val gatewaySessionGeneration: Long,
) {
    init {
        require(isCanonicalReportUuid(walkSessionId))
        require(recoveryGeneration >= 0L)
        require(GatewayCredentialPolicy.normalizedActorIdOrNull(reporterId) == reporterId)
        require(REPORT_SHA256_HEX.matches(consentReceiptSha256))
        require(gatewaySessionGeneration >= 0L)
    }
}

/** Owns the exact first-action bytes until they are consumed once or zeroized. */
internal class ExplicitReportFrozenPayload private constructor(
    val context: ExplicitReportConfirmationContext,
    val capturedAtEpochMs: Long,
    metadataUtf8: ByteArray,
    imageJpeg: ByteArray,
) {
    private var frozenMetadata: ByteArray? = metadataUtf8.copyOf()
    private var frozenImage: ByteArray? = imageJpeg.copyOf()

    @Synchronized
    fun consume(): ConfirmedExplicitReportPayload? {
        val metadata = frozenMetadata ?: return null
        val image = frozenImage ?: return null
        frozenMetadata = null
        frozenImage = null
        return ConfirmedExplicitReportPayload(
            context = context,
            capturedAtEpochMs = capturedAtEpochMs,
            metadataUtf8 = metadata,
            imageJpeg = image,
        )
    }

    @Synchronized
    fun zeroize() {
        frozenMetadata?.fill(0)
        frozenImage?.fill(0)
        frozenMetadata = null
        frozenImage = null
    }

    @Synchronized
    internal fun isZeroizedForTests(): Boolean =
        frozenMetadata == null && frozenImage == null

    companion object {
        fun freeze(
            context: ExplicitReportConfirmationContext,
            capturedAtEpochMs: Long,
            metadataUtf8: ByteArray,
            imageJpeg: ByteArray,
        ): ExplicitReportFrozenPayload? {
            if (
                capturedAtEpochMs < 0L ||
                reportActorIdFromMetadataOrNull(metadataUtf8) != context.reporterId ||
                imageJpeg.size < 4 ||
                imageJpeg[0] != 0xff.toByte() ||
                imageJpeg[1] != 0xd8.toByte() ||
                imageJpeg[imageJpeg.lastIndex - 1] != 0xff.toByte() ||
                imageJpeg[imageJpeg.lastIndex] != 0xd9.toByte()
            ) {
                return null
            }
            return ExplicitReportFrozenPayload(
                context = context,
                capturedAtEpochMs = capturedAtEpochMs,
                metadataUtf8 = metadataUtf8,
                imageJpeg = imageJpeg,
            )
        }
    }
}

/** Transferred ownership from the pending slot; [close] must run after the queue attempt. */
internal class ConfirmedExplicitReportPayload internal constructor(
    val context: ExplicitReportConfirmationContext,
    val capturedAtEpochMs: Long,
    private val metadataUtf8: ByteArray,
    private val imageJpeg: ByteArray,
) : AutoCloseable {
    private var closed = false
    private var used = false

    @Synchronized
    fun <T> useExactBytes(block: (ByteArray, ByteArray) -> T): T {
        check(!closed && !used) { "confirmed explicit report payload was already used" }
        used = true
        return block(metadataUtf8, imageJpeg)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        metadataUtf8.fill(0)
        imageJpeg.fill(0)
        closed = true
    }

    @Synchronized
    internal fun isZeroizedForTests(): Boolean =
        closed && metadataUtf8.all { it == 0.toByte() } && imageJpeg.all { it == 0.toByte() }
}

/** Memory-only, exact-context, single-use confirmation for one frozen payload. */
internal class ExplicitReportConfirmationPolicy(
    private val validityMs: Long = EXPLICIT_REPORT_CONFIRMATION_VALIDITY_MS,
    private val disclosureValidityMs: Long = EXPLICIT_REPORT_DISCLOSURE_VALIDITY_MS,
) {
    private data class Pending(
        val payload: ExplicitReportFrozenPayload,
        val stagedAtElapsedRealtimeMs: Long,
        val armedAtElapsedRealtimeMs: Long? = null,
    )

    private var pending: Pending? = null

    init {
        require(validityMs > 0L)
        require(disclosureValidityMs > 0L)
    }

    @Synchronized
    fun stage(
        payload: ExplicitReportFrozenPayload,
        nowElapsedRealtimeMs: Long,
    ) {
        require(nowElapsedRealtimeMs >= 0L)
        pending?.payload?.zeroize()
        pending = Pending(payload, nowElapsedRealtimeMs)
    }

    @Synchronized
    fun arm(
        context: ExplicitReportConfirmationContext,
        nowElapsedRealtimeMs: Long,
    ): Boolean {
        require(nowElapsedRealtimeMs >= 0L)
        val existing = pending ?: return false
        if (
            existing.payload.context != context ||
            existing.armedAtElapsedRealtimeMs != null ||
            nowElapsedRealtimeMs < existing.stagedAtElapsedRealtimeMs
        ) return false
        pending = existing.copy(armedAtElapsedRealtimeMs = nowElapsedRealtimeMs)
        return true
    }

    @Synchronized
    fun isAwaitingDisclosure(context: ExplicitReportConfirmationContext): Boolean =
        pending?.let {
            it.payload.context == context && it.armedAtElapsedRealtimeMs == null
        } == true

    @Synchronized
    fun consumeIfConfirmed(
        context: ExplicitReportConfirmationContext,
        nowElapsedRealtimeMs: Long,
    ): ConfirmedExplicitReportPayload? {
        require(nowElapsedRealtimeMs >= 0L)
        val existing = pending ?: return null
        if (existing.payload.context != context) {
            pending = null
            existing.payload.zeroize()
            return null
        }
        val armedAt = existing.armedAtElapsedRealtimeMs ?: return null
        pending = null
        if (
            nowElapsedRealtimeMs < armedAt ||
            nowElapsedRealtimeMs - armedAt > validityMs
        ) {
            existing.payload.zeroize()
            return null
        }
        return existing.payload.consume()
    }

    @Synchronized
    fun invalidateExpired(nowElapsedRealtimeMs: Long): Boolean {
        require(nowElapsedRealtimeMs >= 0L)
        val existing = pending ?: return false
        val deadlineStart = existing.armedAtElapsedRealtimeMs
            ?: existing.stagedAtElapsedRealtimeMs
        val allowedDuration = if (existing.armedAtElapsedRealtimeMs == null) {
            disclosureValidityMs
        } else {
            validityMs
        }
        val expired = nowElapsedRealtimeMs < deadlineStart ||
            nowElapsedRealtimeMs - deadlineStart > allowedDuration
        if (!expired) return false
        pending = null
        existing.payload.zeroize()
        return true
    }

    @Synchronized
    fun invalidateIfContext(context: ExplicitReportConfirmationContext): Boolean {
        val existing = pending ?: return false
        if (existing.payload.context != context) return false
        pending = null
        existing.payload.zeroize()
        return true
    }

    @Synchronized
    fun invalidate(): Boolean {
        val existing = pending ?: return false
        pending = null
        existing.payload.zeroize()
        return true
    }
}
