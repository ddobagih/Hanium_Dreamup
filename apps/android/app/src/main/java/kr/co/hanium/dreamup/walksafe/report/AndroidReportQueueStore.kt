package kr.co.hanium.dreamup.walksafe.report

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import java.util.Comparator
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.network.GatewayCredentialPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.json.JSONObject

internal class AndroidReportQueueStore private constructor(
    private val capacityProfile: ApprovedReportQueueCapacityProfile?,
    storageFactory: () -> ReportQueueStorage,
    private val aead: LocalAead,
    private val idFactory: () -> UUID,
    private val nowMillis: () -> Long,
) {
    private val storage by lazy(storageFactory)

    constructor(
        rootDirectory: File,
        capacityProfile: ApprovedReportQueueCapacityProfile? =
            PRODUCTION_REPORT_QUEUE_CAPACITY_PROFILE,
        aead: LocalAead = AndroidKeyStoreAead(REPORT_QUEUE_KEY_POLICY),
        idFactory: () -> UUID = UUID::randomUUID,
        nowMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        capacityProfile,
        { FileReportQueueStorage(rootDirectory) },
        aead,
        idFactory,
        nowMillis,
    )

    internal constructor(
        capacityProfile: ApprovedReportQueueCapacityProfile?,
        storageFactory: () -> ReportQueueStorage,
        aead: LocalAead,
        idFactory: () -> UUID,
        nowMillis: () -> Long,
        testOnly: Unit = Unit,
    ) : this(capacityProfile, storageFactory, aead, idFactory, nowMillis)

    @Synchronized
    fun enqueue(
        expectedReporterActorId: String,
        metadataUtf8: ByteArray,
        imageJpeg: ByteArray,
        priority: ReportQueuePriority,
        walkSessionId: String,
        consentReceiptSha256: String,
    ): QueuedReport? {
        val profile = capacityProfile ?: return null
        val now = nowMillis()
        val reporterActorId = reportActorIdFromMetadataOrNull(metadataUtf8) ?: return null
        if (
            GatewayCredentialPolicy.normalizedActorIdOrNull(expectedReporterActorId) !=
            expectedReporterActorId ||
            reporterActorId != expectedReporterActorId
        ) return null
        val incomingPayloadBytes = metadataUtf8.size.toLong() + imageJpeg.size.toLong()
        if (
            now < 0L ||
            !isCanonicalReportUuid(walkSessionId) ||
            !REPORT_SHA256_HEX.matches(consentReceiptSha256) ||
            incomingPayloadBytes > profile.maxPayloadBytes
        ) {
            return null
        }
        if (storage.measureUsage() == null) return null
        if (
            storage.hasFence(ACCOUNT_DELETE_FENCE) ||
            storage.hasFence(LIFECYCLE_PENDING_FENCE) ||
            storage.hasFence(consentRevokeFence(consentReceiptSha256)) ||
            (
                priority == ReportQueuePriority.AUTOMATIC &&
                    (
                        storage.hasFence(AUTOMATIC_REVOCATION_PENDING_FENCE) ||
                            storage.hasFence(
                                automaticConsentRevokeFence(consentReceiptSha256),
                            )
                    )
            )
        ) return null
        pruneExpiredLocked(now)
        val usage = storage.measureUsage() ?: return null
        val entryLimit = if (priority == ReportQueuePriority.AUTOMATIC) {
            profile.automaticMaxEntries
        } else {
            profile.maxEntries
        }
        val storedByteLimit = if (priority == ReportQueuePriority.AUTOMATIC) {
            profile.automaticMaxTotalBytes
        } else {
            profile.maxTotalBytes
        }
        if (
            usage.reportFileCount >= entryLimit ||
            usage.totalBytes >= storedByteLimit
        ) {
            return null
        }
        val reportId = newCanonicalReportId(idFactory)
        val payload = FrozenReportPayload.freeze(reportId, metadataUtf8, imageJpeg) ?: return null
        val expiresAt = now + REPORT_QUEUE_TTL_MS
        if (expiresAt < now) return null
        val report = QueuedReport(
            payload = payload,
            priority = priority,
            reporterActorId = reporterActorId,
            walkSessionId = walkSessionId,
            consentReceiptSha256 = consentReceiptSha256,
            createdAtEpochMs = now,
            expiresAtEpochMs = expiresAt,
        )
        return report.takeIf {
            storage.writeAtomicallyIfAllowed(
                reportId = reportId,
                storedEntryByteLimit = profile.maxStoredEntryBytes,
                entryLimit = entryLimit,
                storedByteLimit = storedByteLimit,
                blockingFences = setOf(
                    ACCOUNT_DELETE_FENCE,
                    LIFECYCLE_PENDING_FENCE,
                    consentRevokeFence(consentReceiptSha256),
                ) + if (priority == ReportQueuePriority.AUTOMATIC) {
                    setOf(
                        AUTOMATIC_REVOCATION_PENDING_FENCE,
                        automaticConsentRevokeFence(consentReceiptSha256),
                    )
                } else {
                    emptySet()
                },
                envelopeFactory = { sealReport(report) },
            )
        }
    }

    @Synchronized
    fun queuedReports(): List<QueuedReport> {
        if (capacityProfile == null) return emptyList()
        val now = nowMillis()
        if (now < 0L) return emptyList()
        pruneExpiredLocked(now)
        return readAllLocked()
            .filter { now >= it.createdAtEpochMs && now < it.expiresAtEpochMs }
            .sortedWith(REPORT_ORDER)
    }

    @Synchronized
    fun nextForDrain(): QueuedReport? = queuedReports().firstOrNull()

    @Synchronized
    fun nextForDrain(
        walkSessionId: String,
        consentReceiptSha256: String,
    ): QueuedReport? = reportsForDrain(walkSessionId, consentReceiptSha256).firstOrNull()

    @Synchronized
    fun countForDrain(
        walkSessionId: String,
        consentReceiptSha256: String,
    ): Int = reportsForDrain(walkSessionId, consentReceiptSha256).size

    /**
     * A failed upload may outlive both the walk and the optional-consent receipt that existed when
     * it was created. The stored receipt remains creation-time audit provenance; drain admission
     * is re-authorized with the current server-confirmed receipt.
     */
    @Synchronized
    fun nextForRecoveryDrain(reporterActorId: String): QueuedReport? =
        reportsForRecoveryDrain(reporterActorId).firstOrNull()

    @Synchronized
    fun countForRecoveryDrain(reporterActorId: String): Int =
        reportsForRecoveryDrain(reporterActorId).size

    @Synchronized
    fun pruneExpired(): Int = if (capacityProfile == null) 0 else pruneExpiredLocked(nowMillis())

    @Synchronized
    fun deleteAfterReceipt(
        reporterActorId: String,
        receipt: ReportQueueReceipt,
    ): Boolean {
        if (capacityProfile == null) return false
        if (
            GatewayCredentialPolicy.normalizedActorIdOrNull(reporterActorId) != reporterActorId ||
            !isCanonicalReportUuid(receipt.reportId) ||
            !REPORT_SHA256_HEX.matches(receipt.payloadSha256) ||
            receipt.payloadBytes <= 0L ||
            receipt.marker != REPORT_RECEIPT_MARKER ||
            !isCanonicalReportUuid(receipt.persistenceMarker)
        ) {
            return false
        }
        return storage.withLockedAccess { access ->
            if (access.measureUsage() == null || access.hasGlobalReadBarrier()) {
                return@withLockedAccess false
            }
            val report = readAllowedReport(access, receipt.reportId) ?: return@withLockedAccess false
            if (
                report.reporterActorId != reporterActorId ||
                report.payload.reportId != receipt.reportId ||
                report.payload.payloadSha256 != receipt.payloadSha256 ||
                report.payload.payloadBytes != receipt.payloadBytes
            ) {
                return@withLockedAccess false
            }
            access.delete(receipt.reportId)
        } == true
    }

    @Synchronized
    fun onConsentRevoked(consentReceiptSha256: String): Boolean {
        if (!REPORT_SHA256_HEX.matches(consentReceiptSha256)) return false
        return storage.purgeAllWithFence(consentRevokeFence(consentReceiptSha256)) { context ->
            val keyDestroyed = aead.destroyKnownVersions()
            keyDestroyed && (
                !context.storageReady ||
                    context.accountDeleted ||
                    aead.createFreshAfterVerifiedPurge()
                )
        }
    }

    /**
     * Persists a temporary automatic-only barrier, then fences every creation receipt found on an
     * automatic entry before deletion. Explicit reports share the queue key and remain readable.
     */
    @Synchronized
    fun onAutomaticReportingRevoked(consentReceiptSha256: String): Boolean {
        if (!REPORT_SHA256_HEX.matches(consentReceiptSha256)) return false
        return runCatching {
            storage.deleteMatchingWithFences(
                pendingFence = AUTOMATIC_REVOCATION_PENDING_FENCE,
                initialPermanentFences = setOf(
                    automaticConsentRevokeFence(consentReceiptSha256),
                ),
            ) { reportId, envelope ->
                val report = checkNotNull(openReport(envelope, reportId))
                automaticConsentRevokeFence(report.consentReceiptSha256)
                    .takeIf { report.priority == ReportQueuePriority.AUTOMATIC }
            }
        }.getOrDefault(false)
    }

    @Synchronized
    fun onAccountDeleted(): Boolean {
        return storage.purgeAllWithFence(ACCOUNT_DELETE_FENCE) {
            aead.destroyKnownVersions()
        }
    }

    private fun pruneExpiredLocked(now: Long): Int {
        if (now < 0L) return 0
        return storage.withLockedAccess { access ->
            if (access.measureUsage() == null || access.hasGlobalReadBarrier()) {
                return@withLockedAccess 0
            }
            var deleted = 0
            access.listReportIds().forEach { reportId ->
                val report = readAllowedReport(access, reportId) ?: return@forEach
                if (now >= report.expiresAtEpochMs && access.delete(reportId)) deleted += 1
            }
            deleted
        } ?: 0
    }

    private fun readAllLocked(): List<QueuedReport> = storage.withLockedAccess { access ->
        if (access.measureUsage() == null || access.hasGlobalReadBarrier()) {
            return@withLockedAccess emptyList()
        }
        access.listReportIds().mapNotNull { readAllowedReport(access, it) }
    }.orEmpty()

    private fun reportsForDrain(
        walkSessionId: String,
        consentReceiptSha256: String,
    ): List<QueuedReport> {
        if (
            !isCanonicalReportUuid(walkSessionId) ||
            !REPORT_SHA256_HEX.matches(consentReceiptSha256)
        ) {
            return emptyList()
        }
        return queuedReports().filter {
            it.walkSessionId == walkSessionId &&
                it.consentReceiptSha256 == consentReceiptSha256
        }
    }

    private fun reportsForRecoveryDrain(reporterActorId: String): List<QueuedReport> {
        if (GatewayCredentialPolicy.normalizedActorIdOrNull(reporterActorId) != reporterActorId) {
            return emptyList()
        }
        return queuedReports().filter { it.reporterActorId == reporterActorId }
    }

    private fun readAllowedReport(
        access: LockedReportQueueStorage,
        reportId: String,
    ): QueuedReport? {
        val envelope = access.read(reportId) ?: return null
        val report = openReport(envelope, reportId) ?: return null
        return report.takeIf {
            !access.hasFence(consentRevokeFence(it.consentReceiptSha256)) &&
                (
                    it.priority != ReportQueuePriority.AUTOMATIC ||
                        (
                            !access.hasFence(AUTOMATIC_REVOCATION_PENDING_FENCE) &&
                                !access.hasFence(
                                    automaticConsentRevokeFence(it.consentReceiptSha256),
                                )
                        )
                )
        }
    }

    private fun openReport(envelope: String, reportId: String): QueuedReport? {
        val opened = aead.open(envelope, reportAad(reportId), REPORT_LIMITS)
            as? AeadOpenResult.Opened
            ?: return null
        return try {
            decodeReport(opened.plaintext)?.takeIf {
                it.payload.reportId == reportId
            }
        } finally {
            opened.plaintext.fill(0)
        }
    }

    private fun LockedReportQueueStorage.hasGlobalReadBarrier(): Boolean =
        hasFence(ACCOUNT_DELETE_FENCE) || hasFence(LIFECYCLE_PENDING_FENCE)

    private fun sealReport(report: QueuedReport): String? {
        val plaintext = encodeReport(report)
        val sealed = aead.seal(plaintext, reportAad(report.payload.reportId), REPORT_LIMITS)
        plaintext.fill(0)
        return (sealed as? AeadSealResult.Sealed)?.envelope
    }

    private companion object {
        fun reportAad(reportId: String): ByteArray =
            "kr.co.hanium.dreamup.walksafe|USER|report-queue|schema=2|report=$reportId"
                .toByteArray(Charsets.UTF_8)

        val REPORT_ORDER = compareBy<QueuedReport>(
            { if (it.priority == ReportQueuePriority.EXPLICIT) 0 else 1 },
            QueuedReport::createdAtEpochMs,
            { it.payload.reportId },
        )
        const val ACCOUNT_DELETE_FENCE = "account-deleted"
        const val LIFECYCLE_PENDING_FENCE = "lifecycle-pending"
        const val AUTOMATIC_REVOCATION_PENDING_FENCE =
            "automatic-revocation-pending"
        fun consentRevokeFence(receiptSha256: String): String =
            "consent-revoked-$receiptSha256"
        fun automaticConsentRevokeFence(receiptSha256: String): String =
            "automatic-consent-revoked-$receiptSha256"
        val REPORT_LIMITS = AeadLimits(
            maxPlaintextBytes = 32 * 1_024 * 1_024,
            maxCiphertextBytes = 32 * 1_024 * 1_024 + 16,
            maxEnvelopeChars = 44 * 1_024 * 1_024,
        )
    }
}

private fun encodeReport(report: QueuedReport): ByteArray = JSONObject()
    .put("schema_version", REPORT_QUEUE_SCHEMA)
    .put("report_id", report.payload.reportId)
    .put("priority", report.priority.name)
    .put("reporter_actor_id", report.reporterActorId)
    .put("walk_session_id", report.walkSessionId)
    .put("consent_receipt_sha256", report.consentReceiptSha256)
    .put("created_at_epoch_ms", report.createdAtEpochMs)
    .put("expires_at_epoch_ms", report.expiresAtEpochMs)
    .put("payload_sha256", report.payload.payloadSha256)
    .put("payload_bytes", report.payload.payloadBytes)
    .put("metadata_base64", Base64.getEncoder().encodeToString(report.payload.metadataUtf8()))
    .put("image_jpeg_base64", Base64.getEncoder().encodeToString(report.payload.imageJpeg()))
    .toString()
    .toByteArray(Charsets.UTF_8)

private fun decodeReport(bytes: ByteArray): QueuedReport? = runCatching {
    val root = JSONObject(String(bytes, Charsets.UTF_8))
    require(root.keysAsSet() == REPORT_QUEUE_FIELDS)
    require(root.getString("schema_version") == REPORT_QUEUE_SCHEMA)
    val reportId = root.getString("report_id")
    val reporterActorId = root.getString("reporter_actor_id")
    val walkSessionId = root.getString("walk_session_id")
    val consentReceipt = root.getString("consent_receipt_sha256")
    val createdAt = root.strictLong("created_at_epoch_ms")
    val expiresAt = root.strictLong("expires_at_epoch_ms")
    val declaredSha = root.getString("payload_sha256")
    val declaredBytes = root.strictLong("payload_bytes")
    require(isCanonicalReportUuid(reportId))
    require(GatewayCredentialPolicy.normalizedActorIdOrNull(reporterActorId) == reporterActorId)
    require(isCanonicalReportUuid(walkSessionId))
    require(REPORT_SHA256_HEX.matches(consentReceipt))
    require(createdAt >= 0L && expiresAt - createdAt == REPORT_QUEUE_TTL_MS)
    val metadata = Base64.getDecoder().decode(root.getString("metadata_base64"))
    val image = Base64.getDecoder().decode(root.getString("image_jpeg_base64"))
    val payload = try {
        require(reportActorIdFromMetadataOrNull(metadata) == reporterActorId)
        FrozenReportPayload.freeze(reportId, metadata, image)
    } finally {
        metadata.fill(0)
        image.fill(0)
    }
    requireNotNull(payload)
    require(payload.payloadSha256 == declaredSha)
    require(payload.payloadBytes == declaredBytes)
    QueuedReport(
        payload = payload,
        priority = ReportQueuePriority.valueOf(root.getString("priority")),
        reporterActorId = reporterActorId,
        walkSessionId = walkSessionId,
        consentReceiptSha256 = consentReceipt,
        createdAtEpochMs = createdAt,
        expiresAtEpochMs = expiresAt,
    )
}.getOrNull()

private fun JSONObject.keysAsSet(): Set<String> = buildSet { keys().forEach(::add) }

private fun JSONObject.strictLong(name: String): Long = when (val value = get(name)) {
    is Int -> value.toLong()
    is Long -> value
    else -> error("$name must be an integer")
}

internal interface ReportQueueStorage {
    fun listReportIds(): Set<String>
    fun read(reportId: String): String?
    fun <T> withLockedAccess(action: (LockedReportQueueStorage) -> T): T?
    fun writeAtomicallyIfAllowed(
        reportId: String,
        entryLimit: Int,
        storedByteLimit: Long,
        blockingFences: Set<String>,
        storedEntryByteLimit: Long = REPORT_QUEUE_MAX_STORED_ENTRY_BYTES,
        envelopeFactory: () -> String?,
    ): Boolean
    fun delete(reportId: String): Boolean
    fun hasFence(name: String): Boolean
    fun purgeAllWithFence(
        name: String,
        keyLifecycle: (ReportQueuePurgeContext) -> Boolean,
    ): Boolean
    fun deleteMatchingWithFences(
        pendingFence: String,
        initialPermanentFences: Set<String>,
        permanentFenceForMatch: (reportId: String, envelope: String) -> String?,
    ): Boolean
    fun measureUsage(): ReportQueueStorageUsage?
}

internal interface LockedReportQueueStorage {
    fun listReportIds(): Set<String>
    fun read(reportId: String): String?
    fun delete(reportId: String): Boolean
    fun hasFence(name: String): Boolean
    fun measureUsage(): ReportQueueStorageUsage?
}

internal data class ReportQueuePurgeContext(
    val storageReady: Boolean,
    val accountDeleted: Boolean,
)

internal data class ReportQueueStorageUsage(
    val reportFileCount: Int,
    val totalBytes: Long,
) {
    init {
        require(reportFileCount >= 0)
        require(totalBytes >= 0L)
    }
}

internal class FileReportQueueStorage(rootDirectory: File) : ReportQueueStorage {
    private val storageBase = rootDirectory.toPath().toAbsolutePath().normalize().toFile()
    private val root = File(storageBase, "report_queue_v1")
    private val reports = File(root, "reports")
    private val fences = File(root, "fences")
    private val lockedAccess = object : LockedReportQueueStorage {
        override fun listReportIds(): Set<String> = this@FileReportQueueStorage.listReportIds()
        override fun read(reportId: String): String? = this@FileReportQueueStorage.read(reportId)
        override fun delete(reportId: String): Boolean = deleteUnlocked(reportId)
        override fun hasFence(name: String): Boolean = this@FileReportQueueStorage.hasFence(name)
        override fun measureUsage(): ReportQueueStorageUsage? =
            this@FileReportQueueStorage.measureUsage()
    }

    @Synchronized
    override fun listReportIds(): Set<String> {
        if (!isSafeQueueDirectory(reports)) return emptySet()
        return directoryEntries(reports)
            .orEmpty()
            .mapNotNullTo(linkedSetOf()) { file ->
                val metadata = runCatching {
                    Files.readAttributes(
                        file.toPath(),
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                }.getOrNull()
                file.name.removeSuffix(REPORT_SUFFIX).takeIf {
                    metadata?.isRegularFile == true &&
                        !metadata.isSymbolicLink &&
                        file.name.endsWith(REPORT_SUFFIX) &&
                        isCanonicalReportUuid(it)
                }
            }
    }

    @Synchronized
    override fun read(reportId: String): String? {
        if (!isSafeQueueDirectory(reports)) return null
        val file = reportFile(reportId) ?: return null
        return runCatching {
            val metadata = Files.readAttributes(
                file.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            file.takeIf {
                metadata.isRegularFile &&
                    !metadata.isSymbolicLink &&
                    metadata.size() in 1..MAX_ENVELOPE_BYTES
            }?.readText(Charsets.UTF_8)
        }.getOrNull()
    }

    override fun <T> withLockedAccess(action: (LockedReportQueueStorage) -> T): T? =
        withStorageLock { action(lockedAccess) }

    @Synchronized
    override fun hasFence(name: String): Boolean {
        if (!isSafeQueueDirectory(fences)) return false
        val file = fenceFile(name) ?: return false
        return runCatching {
            val metadata = Files.readAttributes(
                file.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            metadata.isRegularFile && !metadata.isSymbolicLink
        }.getOrDefault(false)
    }

    override fun writeAtomicallyIfAllowed(
        reportId: String,
        entryLimit: Int,
        storedByteLimit: Long,
        blockingFences: Set<String>,
        storedEntryByteLimit: Long,
        envelopeFactory: () -> String?,
    ): Boolean {
        if (
            entryLimit <= 0 ||
            storedEntryByteLimit !in 1L..MAX_ENVELOPE_BYTES ||
            storedByteLimit <= 0L ||
            blockingFences.any { fenceFile(it) == null }
        ) return false
        val target = reportFile(reportId) ?: return false
        return withStorageLock {
            val usage = measureUsage() ?: return@withStorageLock false
            if (
                blockingFences.any(::hasFence) ||
                usage.reportFileCount >= entryLimit ||
                usage.totalBytes >= storedByteLimit ||
                Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                return@withStorageLock false
            }
            val envelope = envelopeFactory() ?: return@withStorageLock false
            val envelopeBytes = envelope.toByteArray(Charsets.UTF_8).size.toLong()
            val latestUsage = measureUsage() ?: return@withStorageLock false
            if (
                envelopeBytes !in 1L..MAX_ENVELOPE_BYTES ||
                envelopeBytes > storedEntryByteLimit ||
                blockingFences.any(::hasFence) ||
                latestUsage.reportFileCount >= entryLimit ||
                latestUsage.totalBytes >= storedByteLimit ||
                envelopeBytes > storedByteLimit - latestUsage.totalBytes ||
                Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) return@withStorageLock false
            atomicWrite(target, envelope, replaceExisting = false)
        } == true
    }

    override fun delete(reportId: String): Boolean {
        return withStorageLock { deleteUnlocked(reportId) } == true
    }

    override fun purgeAllWithFence(
        name: String,
        keyLifecycle: (ReportQueuePurgeContext) -> Boolean,
    ): Boolean {
        if (fenceFile(name) == null) return false
        if (
            name == LIFECYCLE_PENDING_FENCE ||
            name == AUTOMATIC_REVOCATION_PENDING_FENCE
        ) return false
        val pending = fenceFile(LIFECYCLE_PENDING_FENCE) ?: return false
        var keyLifecycleAttempted = false
        val result = withStorageLock {
            if (
                !ensureQueueDirectory(reports) ||
                !ensureQueueDirectory(fences) ||
                measureUsage() == null
            ) {
                keyLifecycleAttempted = true
                keyLifecycle(
                    ReportQueuePurgeContext(
                        storageReady = false,
                        accountDeleted = name == ACCOUNT_DELETE_FENCE,
                    ),
                )
                return@withStorageLock false
            }
            val priorPendingIntents = readPendingIntents(pending)
            if (priorPendingIntents == null) {
                keyLifecycleAttempted = true
                keyLifecycle(
                    ReportQueuePurgeContext(
                        storageReady = false,
                        accountDeleted = true,
                    ),
                )
                return@withStorageLock false
            }
            val pendingIntents = priorPendingIntents + name
            val accountDeleted =
                ACCOUNT_DELETE_FENCE in pendingIntents || hasFence(ACCOUNT_DELETE_FENCE)
            val pendingWritten = atomicWrite(
                pending,
                encodePendingIntents(pendingIntents),
                replaceExisting = true,
            )
            if (!pendingWritten) {
                keyLifecycleAttempted = true
                keyLifecycle(ReportQueuePurgeContext(false, accountDeleted))
                return@withStorageLock false
            }
            val fenced = pendingIntents.all { intent ->
                val intentTarget = fenceFile(intent) ?: return@all false
                atomicWrite(intentTarget, "$intent\n", replaceExisting = true)
            }
            val deleted = fenced && deleteAllUnlocked()
            val storageReady = fenced && deleted
            keyLifecycleAttempted = true
            val keyLifecycleCompleted = keyLifecycle(
                ReportQueuePurgeContext(storageReady, accountDeleted),
            )
            storageReady &&
                keyLifecycleCompleted &&
                deleteAndSync(pending) &&
                !hasFence(LIFECYCLE_PENDING_FENCE)
        }
        if (result == null && !keyLifecycleAttempted) {
            keyLifecycle(
                ReportQueuePurgeContext(
                    storageReady = false,
                    accountDeleted = name == ACCOUNT_DELETE_FENCE,
                ),
            )
        }
        return result == true
    }

    override fun deleteMatchingWithFences(
        pendingFence: String,
        initialPermanentFences: Set<String>,
        permanentFenceForMatch: (reportId: String, envelope: String) -> String?,
    ): Boolean {
        if (
            pendingFence != AUTOMATIC_REVOCATION_PENDING_FENCE ||
            initialPermanentFences.any { !AUTOMATIC_CONSENT_FENCE.matches(it) }
        ) return false
        val pending = fenceFile(pendingFence) ?: return false
        return withStorageLock {
            if (
                !ensureQueueDirectory(reports) ||
                !ensureQueueDirectory(fences) ||
                measureUsage() == null ||
                !atomicWrite(pending, "$pendingFence\n", replaceExisting = true)
            ) return@withStorageLock false
            val reportIds = listReportIds()
            val matches = mutableListOf<String>()
            val permanentFences = initialPermanentFences.toMutableSet()
            reportIds.forEach { reportId ->
                val envelope = read(reportId)
                    ?: return@withStorageLock false
                val permanentFence = permanentFenceForMatch(reportId, envelope)
                if (permanentFence != null) {
                    if (!AUTOMATIC_CONSENT_FENCE.matches(permanentFence)) {
                        return@withStorageLock false
                    }
                    permanentFences += permanentFence
                    matches += reportId
                }
            }
            if (
                !permanentFences.all { name ->
                    val target = fenceFile(name) ?: return@all false
                    atomicWrite(target, "$name\n", replaceExisting = true)
                }
            ) return@withStorageLock false
            if (!matches.map(::deleteUnlocked).all { it }) {
                return@withStorageLock false
            }
            deleteAndSync(pending) && !hasFence(AUTOMATIC_REVOCATION_PENDING_FENCE)
        } == true
    }

    @Synchronized
    override fun measureUsage(): ReportQueueStorageUsage? {
        return try {
            if (!isSafeStorageBase()) return null
            val rootEntries = directoryEntries(root) ?: return null
            if (
                rootEntries.any {
                    it.name != "reports" && it.name != "fences" && it.name != LOCK_FILE
                }
            ) return null
            rootEntries.firstOrNull { it.name == LOCK_FILE }?.let { lockFile ->
                val lockMetadata = Files.readAttributes(
                    lockFile.toPath(),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                if (
                    !lockMetadata.isRegularFile ||
                    lockMetadata.isSymbolicLink ||
                    lockMetadata.size() != 0L
                ) return null
            }
            val reportUsage = directoryUsage(reports, countEntries = true) ?: return null
            val fenceUsage = directoryUsage(fences, countEntries = false) ?: return null
            ReportQueueStorageUsage(
                reportFileCount = reportUsage.reportFileCount,
                totalBytes = Math.addExact(reportUsage.totalBytes, fenceUsage.totalBytes),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun reportFile(reportId: String): File? =
        reportId.takeIf(::isCanonicalReportUuid)?.let { File(reports, "$it$REPORT_SUFFIX") }

    private fun fenceFile(name: String): File? =
        name.takeIf {
            it == ACCOUNT_DELETE_FENCE ||
                it == LIFECYCLE_PENDING_FENCE ||
                it == AUTOMATIC_REVOCATION_PENDING_FENCE ||
                CONSENT_FENCE.matches(it) ||
                AUTOMATIC_CONSENT_FENCE.matches(it)
        }
            ?.let { File(fences, "$it.fence") }

    private fun readPendingIntents(pending: File): Set<String>? = runCatching {
        val path = pending.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@runCatching emptySet()
        val metadata = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (
            !metadata.isRegularFile ||
            metadata.isSymbolicLink ||
            metadata.size() !in 1L..MAX_PENDING_FENCE_BYTES
        ) return@runCatching null
        val encoded = pending.readText(Charsets.US_ASCII)
        val intents = encoded
            .removeSuffix("\n")
            .split('\n')
            .toSet()
        if (
            intents.isEmpty() ||
            intents.any { it != ACCOUNT_DELETE_FENCE && !CONSENT_FENCE.matches(it) } ||
            encodePendingIntents(intents) != encoded
        ) return@runCatching null
        intents
    }.getOrNull()

    private fun encodePendingIntents(intents: Set<String>): String =
        intents.sorted().joinToString(separator = "\n", postfix = "\n")

    private fun directoryUsage(
        directory: File,
        countEntries: Boolean,
    ): ReportQueueStorageUsage? {
        val entries = directoryEntries(directory) ?: return null
        var reportFileCount = 0
        var totalBytes = 0L
        entries.forEach { entry ->
            val metadata = Files.readAttributes(
                entry.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (!metadata.isRegularFile || metadata.isSymbolicLink) return null
            totalBytes = Math.addExact(totalBytes, metadata.size())
            if (countEntries) reportFileCount = Math.addExact(reportFileCount, 1)
        }
        return ReportQueueStorageUsage(reportFileCount, totalBytes)
    }

    private fun directoryEntries(directory: File): Array<File>? {
        val path = directory.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return emptyArray()
        val metadata = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!metadata.isDirectory || metadata.isSymbolicLink) return null
        return directory.listFiles()
    }

    private fun <T> withStorageLock(action: () -> T): T? {
        synchronized(PROCESS_STORAGE_LOCK) {
            return runCatching {
                if (!ensureQueueDirectory(root)) return@runCatching null
                val lockFile = File(root, LOCK_FILE)
                FileChannel.open(
                    lockFile.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { channel ->
                    if (!isSafeDirectory(root)) return@use null
                    val metadata = Files.readAttributes(
                        lockFile.toPath(),
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                    if (
                        !metadata.isRegularFile ||
                        metadata.isSymbolicLink ||
                        metadata.size() != 0L
                    ) return@use null
                    channel.lock().use { action() }
                }
            }.getOrNull()
        }
    }

    private fun deleteAllUnlocked(): Boolean {
        val entries = directoryEntries(reports) ?: return false
        entries.forEach { entry ->
            val metadata = runCatching {
                Files.readAttributes(
                    entry.toPath(),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            }.getOrNull() ?: return false
            if (!metadata.isRegularFile || metadata.isSymbolicLink) return false
        }
        return entries.map(::deleteAndSync).all { it }
    }

    private fun deleteUnlocked(reportId: String): Boolean {
        val target = reportFile(reportId) ?: return false
        return isSafeQueueDirectory(reports) && deleteAndSync(target)
    }

    private fun atomicWrite(
        target: File,
        value: String,
        replaceExisting: Boolean,
    ): Boolean = runCatching {
        val parent = target.parentFile ?: return@runCatching false
        if (!ensureQueueDirectory(parent)) return@runCatching false
        val temporary = File.createTempFile(".${target.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            val moveOptions = if (replaceExisting) {
                arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                arrayOf(StandardCopyOption.ATOMIC_MOVE)
            }
            Files.move(temporary.toPath(), target.toPath(), *moveOptions)
            FileOutputStream(target, true).use { it.fd.sync() }
            syncDirectory(parent)
            true
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }.getOrDefault(false)

    private fun deleteAndSync(file: File): Boolean = runCatching {
        if (!file.exists()) return@runCatching true
        val parent = file.parentFile ?: return@runCatching false
        if (!file.isFile || !file.delete()) return@runCatching false
        syncDirectory(parent)
        true
    }.getOrDefault(false)

    private fun ensureQueueDirectory(directory: File): Boolean = runCatching {
        if (!isSafeStorageBase()) return@runCatching false
        val parent = when (directory) {
            root -> storageBase
            reports, fences -> {
                if (!ensureQueueDirectory(root)) return@runCatching false
                root
            }
            else -> return@runCatching false
        }
        val path = directory.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@runCatching isSafeDirectory(directory)
        Files.createDirectory(path)
        syncDirectory(parent)
        isSafeDirectory(directory)
    }.getOrDefault(false)

    private fun isSafeDirectory(directory: File): Boolean = runCatching {
        val metadata = Files.readAttributes(
            directory.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        metadata.isDirectory && !metadata.isSymbolicLink
    }.getOrDefault(false)

    private fun isSafeStorageBase(): Boolean = runCatching {
        val path = storageBase.toPath()
        var current = path.root ?: return@runCatching false
        if (!isSafeDirectory(current.toFile())) return@runCatching false
        for (segment in path) {
            current = current.resolve(segment)
            if (!isSafeDirectory(current.toFile())) return@runCatching false
        }
        true
    }.getOrDefault(false)

    private fun isSafeQueueDirectory(directory: File): Boolean =
        isSafeStorageBase() &&
            isSafeDirectory(root) &&
            when (directory) {
                root -> true
                reports, fences -> isSafeDirectory(directory)
                else -> false
            }

    private fun syncDirectory(directory: File) {
        FileChannel.open(
            directory.toPath(),
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { it.force(true) }
    }

    private companion object {
        val PROCESS_STORAGE_LOCK = Any()
        const val ACCOUNT_DELETE_FENCE = "account-deleted"
        const val LIFECYCLE_PENDING_FENCE = "lifecycle-pending"
        const val AUTOMATIC_REVOCATION_PENDING_FENCE =
            "automatic-revocation-pending"
        const val MAX_PENDING_FENCE_BYTES = 64L * 1_024L
        const val LOCK_FILE = ".queue.lock"
        const val REPORT_SUFFIX = ".aead"
        const val MAX_ENVELOPE_BYTES = 44L * 1_024L * 1_024L
        val CONSENT_FENCE = Regex("consent-revoked-[0-9a-f]{64}")
        val AUTOMATIC_CONSENT_FENCE =
            Regex("automatic-consent-revoked-[0-9a-f]{64}")
    }
}

internal val REPORT_QUEUE_KEY_POLICY = AeadKeyPolicy(
    aliasPrefix = "walksafe.user.report_queue.aead.v",
    currentVersion = 1,
    readableVersions = setOf(1),
)

private const val REPORT_QUEUE_SCHEMA = "walksafe.android.report-queue-entry.v2"
private val REPORT_QUEUE_FIELDS = setOf(
    "schema_version",
    "report_id",
    "priority",
    "reporter_actor_id",
    "walk_session_id",
    "consent_receipt_sha256",
    "created_at_epoch_ms",
    "expires_at_epoch_ms",
    "payload_sha256",
    "payload_bytes",
    "metadata_base64",
    "image_jpeg_base64",
)
