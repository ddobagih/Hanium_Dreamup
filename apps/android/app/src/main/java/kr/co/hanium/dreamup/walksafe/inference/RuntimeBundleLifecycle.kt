package kr.co.hanium.dreamup.walksafe.inference

data class RuntimeBundleDescriptor(
    val bundleVersion: String,
    val modelArtifacts: Map<String, String>,
    val modelClasses: Map<String, List<String>>,
    val guidanceAllowlist: Map<String, Set<String>>,
    val classThresholds: Map<String, Map<String, Float>>,
    val appArtifactSha256: String,
    val configSha256: String,
) {
    init {
        require(bundleVersion.isNotBlank() && bundleVersion == bundleVersion.trim()) {
            "bundleVersion must be a trimmed non-blank value"
        }
        require(modelArtifacts.isNotEmpty()) { "modelArtifacts must not be empty" }
        require(modelArtifacts.keys.all { it.isNotBlank() && it == it.trim() }) {
            "model artifact keys must be trimmed non-blank values"
        }
        require(modelArtifacts.values.all(SHA256_PATTERN::matches)) {
            "model artifact hashes must be 64 lowercase hex characters"
        }
        require(modelClasses.keys == modelArtifacts.keys) {
            "modelClasses must pin every model artifact exactly"
        }
        require(modelClasses.values.all { classes ->
            classes.isNotEmpty() &&
                classes.all { it.isNotBlank() && it == it.trim() } &&
                classes.size == classes.toSet().size
        }) {
            "model classes must be non-empty, trimmed, and unique"
        }
        require(guidanceAllowlist.keys == modelArtifacts.keys) {
            "guidanceAllowlist must pin every model artifact exactly"
        }
        require(guidanceAllowlist.values.any { it.isNotEmpty() }) {
            "at least one guidance class must be pinned"
        }
        require(guidanceAllowlist.all { (modelKey, allowlist) ->
            allowlist.all { it in checkNotNull(modelClasses[modelKey]) }
        }) {
            "guidance allowlists must be subsets of their pinned model classes"
        }
        require(classThresholds.keys == modelArtifacts.keys) {
            "classThresholds must pin every model artifact exactly"
        }
        require(classThresholds.all { (modelKey, thresholds) ->
            val classes = checkNotNull(modelClasses[modelKey])
            thresholds.isNotEmpty() &&
                thresholds.keys.all { it in classes } &&
                checkNotNull(guidanceAllowlist[modelKey]).all { it in thresholds } &&
                thresholds.values.all { it.isFinite() && it in 0f..1f }
        }) {
            "class thresholds must be finite pinned-model values covering guidance classes"
        }
        require(SHA256_PATTERN.matches(appArtifactSha256)) {
            "appArtifactSha256 must be 64 lowercase hex characters"
        }
        require(SHA256_PATTERN.matches(configSha256)) {
            "configSha256 must be 64 lowercase hex characters"
        }
    }

    private companion object {
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

data class RuntimeBundleApprovalBinding(
    val approvalId: String,
    val signatureFingerprint: String,
    val descriptor: RuntimeBundleDescriptor,
) {
    init {
        require(approvalId.isNotBlank() && approvalId == approvalId.trim()) {
            "approvalId must be a trimmed non-blank value"
        }
        require(
            signatureFingerprint.isNotBlank() &&
                signatureFingerprint == signatureFingerprint.trim(),
        ) {
            "signatureFingerprint must be a trimmed non-blank value"
        }
    }
}

data class RuntimeBundleApprovalProfile(
    val bindings: Set<RuntimeBundleApprovalBinding>,
) {
    init {
        require(bindings.isNotEmpty()) { "approval bindings must not be empty" }
        require(bindings.map { it.approvalId }.toSet().size == bindings.size) {
            "approvalId must identify exactly one approved bundle"
        }
    }

    fun exactMatch(
        descriptor: RuntimeBundleDescriptor,
        approvalId: String,
        signatureFingerprint: String,
    ): Boolean = bindings.any { binding ->
        binding.approvalId == approvalId &&
            binding.signatureFingerprint == signatureFingerprint &&
            binding.descriptor == descriptor
    }
}

data class RuntimeBundleValidationEvidence(
    val bundleVersion: String,
    val compatibilityPassed: Boolean,
    val selfTestPassed: Boolean,
) {
    init {
        require(bundleVersion.isNotBlank() && bundleVersion == bundleVersion.trim()) {
            "validation bundleVersion must be a trimmed non-blank value"
        }
    }
}

data class RuntimeBundleActivationRequest(
    val candidate: RuntimeBundleDescriptor,
    val approvalId: String,
    val signatureFingerprint: String,
    val validation: RuntimeBundleValidationEvidence,
    val activeWalk: Boolean,
)

data class RuntimeBundleRollbackAuditBinding(
    val auditId: String,
    val approvalId: String,
    val targetBundleVersion: String,
) {
    init {
        require(auditId.isNotBlank() && auditId == auditId.trim()) {
            "auditId must be a trimmed non-blank value"
        }
        require(approvalId.isNotBlank() && approvalId == approvalId.trim()) {
            "audit approvalId must be a trimmed non-blank value"
        }
        require(
            targetBundleVersion.isNotBlank() &&
                targetBundleVersion == targetBundleVersion.trim(),
        ) {
            "audit targetBundleVersion must be a trimmed non-blank value"
        }
    }
}

data class RuntimeBundleRollbackRequest(
    val target: RuntimeBundleDescriptor,
    val approvalId: String,
    val signatureFingerprint: String,
    val validation: RuntimeBundleValidationEvidence,
    val activeWalk: Boolean,
    val databaseBackwardCompatible: Boolean,
    val dataBackwardCompatible: Boolean,
    val noDataLoss: Boolean,
    val explicitApprovalId: String?,
    val auditBinding: RuntimeBundleRollbackAuditBinding?,
)

enum class RuntimeBundleChangeStatus {
    NOT_CONFIGURED,
    ACTIVE_WALK_IN_PROGRESS,
    APPROVAL_MISMATCH,
    VALIDATION_BINDING_MISMATCH,
    COMPATIBILITY_FAILED,
    SELF_TEST_FAILED,
    TARGET_NOT_PREVIOUS_KNOWN_GOOD,
    DATABASE_NOT_BACKWARD_COMPATIBLE,
    DATA_NOT_BACKWARD_COMPATIBLE,
    DATA_LOSS_RISK,
    EXPLICIT_APPROVAL_MISSING,
    AUDIT_BINDING_MISSING,
    ALREADY_CURRENT,
    ACTIVATED_FOR_NEXT_WALK,
    ROLLED_BACK_FOR_NEXT_WALK,
}

data class RuntimeBundleLifecycleSnapshot(
    val current: RuntimeBundleDescriptor,
    val previousKnownGood: RuntimeBundleDescriptor?,
)

data class RuntimeBundleChangeResult(
    val status: RuntimeBundleChangeStatus,
    val snapshot: RuntimeBundleLifecycleSnapshot,
)

/**
 * Pure descriptor lifecycle. Callers supply already-local descriptors and externally produced
 * approval/validation evidence; this class performs no download, signature verification, install,
 * app control, or active-walk replacement.
 */
class RuntimeBundleLifecycle(
    initialCurrent: RuntimeBundleDescriptor,
    initialPreviousKnownGood: RuntimeBundleDescriptor? = null,
    private val approvalProfile: RuntimeBundleApprovalProfile? = productionApprovalProfile,
) {
    private var current = initialCurrent
    private var previousKnownGood = initialPreviousKnownGood

    @Synchronized
    fun snapshot() = RuntimeBundleLifecycleSnapshot(current, previousKnownGood)

    @Synchronized
    fun activateForNextWalk(
        request: RuntimeBundleActivationRequest,
    ): RuntimeBundleChangeResult {
        val profile = approvalProfile ?: return result(RuntimeBundleChangeStatus.NOT_CONFIGURED)
        if (request.activeWalk) return result(RuntimeBundleChangeStatus.ACTIVE_WALK_IN_PROGRESS)
        if (
            !profile.exactMatch(
                request.candidate,
                request.approvalId,
                request.signatureFingerprint,
            )
        ) {
            return result(RuntimeBundleChangeStatus.APPROVAL_MISMATCH)
        }
        validationFailure(request.candidate, request.validation)?.let { return result(it) }
        if (request.candidate == current) return result(RuntimeBundleChangeStatus.ALREADY_CURRENT)

        previousKnownGood = current
        current = request.candidate
        return result(RuntimeBundleChangeStatus.ACTIVATED_FOR_NEXT_WALK)
    }

    @Synchronized
    fun rollbackForNextWalk(
        request: RuntimeBundleRollbackRequest,
    ): RuntimeBundleChangeResult {
        val profile = approvalProfile ?: return result(RuntimeBundleChangeStatus.NOT_CONFIGURED)
        if (request.activeWalk) return result(RuntimeBundleChangeStatus.ACTIVE_WALK_IN_PROGRESS)
        if (
            !profile.exactMatch(
                request.target,
                request.approvalId,
                request.signatureFingerprint,
            )
        ) {
            return result(RuntimeBundleChangeStatus.APPROVAL_MISMATCH)
        }
        if (request.target != previousKnownGood) {
            return result(RuntimeBundleChangeStatus.TARGET_NOT_PREVIOUS_KNOWN_GOOD)
        }
        validationFailure(request.target, request.validation)?.let { return result(it) }
        if (!request.databaseBackwardCompatible) {
            return result(RuntimeBundleChangeStatus.DATABASE_NOT_BACKWARD_COMPATIBLE)
        }
        if (!request.dataBackwardCompatible) {
            return result(RuntimeBundleChangeStatus.DATA_NOT_BACKWARD_COMPATIBLE)
        }
        if (!request.noDataLoss) return result(RuntimeBundleChangeStatus.DATA_LOSS_RISK)
        if (request.explicitApprovalId != request.approvalId) {
            return result(RuntimeBundleChangeStatus.EXPLICIT_APPROVAL_MISSING)
        }
        val audit = request.auditBinding
        if (
            audit == null ||
            audit.approvalId != request.approvalId ||
            audit.targetBundleVersion != request.target.bundleVersion
        ) {
            return result(RuntimeBundleChangeStatus.AUDIT_BINDING_MISSING)
        }

        val replaced = current
        current = request.target
        previousKnownGood = replaced
        return result(RuntimeBundleChangeStatus.ROLLED_BACK_FOR_NEXT_WALK)
    }

    private fun validationFailure(
        descriptor: RuntimeBundleDescriptor,
        validation: RuntimeBundleValidationEvidence,
    ): RuntimeBundleChangeStatus? = when {
        validation.bundleVersion != descriptor.bundleVersion ->
            RuntimeBundleChangeStatus.VALIDATION_BINDING_MISMATCH
        !validation.compatibilityPassed -> RuntimeBundleChangeStatus.COMPATIBILITY_FAILED
        !validation.selfTestPassed -> RuntimeBundleChangeStatus.SELF_TEST_FAILED
        else -> null
    }

    private fun result(status: RuntimeBundleChangeStatus) = RuntimeBundleChangeResult(
        status = status,
        snapshot = RuntimeBundleLifecycleSnapshot(current, previousKnownGood),
    )

    companion object {
        /** External approval/signature bindings are absent from production configuration. */
        val productionApprovalProfile: RuntimeBundleApprovalProfile? = null
    }
}
