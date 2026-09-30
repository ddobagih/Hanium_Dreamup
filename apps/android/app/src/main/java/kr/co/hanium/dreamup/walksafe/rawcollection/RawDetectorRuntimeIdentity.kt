package kr.co.hanium.dreamup.walksafe.rawcollection

/**
 * Which detector actually produced a RAW runtime window (16-17 #6): fixed hashes of the loaded
 * model bytes, the model configuration asset and the class list, plus the execution delegate and
 * whether a fallback path ran. No file path, account, device, or installation identifier.
 * A window without this identity is rejected as INVALID_WINDOW, and an identity change is a
 * segment boundary. Only without an approved queue profile, once the walk's segment budget is in
 * use, a later window of another execution is merged and counted in `boundary_window_count`
 * (S1617-18-F2); the identity fields then describe the execution the segment was opened with.
 */
data class RawDetectorRuntimeIdentity(
    val modelArtifactSha256: String,
    val runtimeConfigSha256: String,
    val classListSha256: String,
    val activeDelegate: String,
    val delegateFallbackUsed: Boolean,
) {
    init {
        require(SHA256_HEX_PATTERN.matches(modelArtifactSha256))
        require(SHA256_HEX_PATTERN.matches(runtimeConfigSha256))
        require(SHA256_HEX_PATTERN.matches(classListSha256))
        require(activeDelegate in RAW_ALLOWED_DELEGATES)
    }

    companion object {
        /** Null (the window is then rejected) when any part is missing or malformed. */
        fun ofOrNull(
            modelArtifactSha256: String?,
            runtimeConfigSha256: String?,
            classListSha256: String?,
            activeDelegate: String?,
            delegateFallbackUsed: Boolean?,
        ): RawDetectorRuntimeIdentity? = runCatching {
            RawDetectorRuntimeIdentity(
                modelArtifactSha256 = requireNotNull(modelArtifactSha256),
                runtimeConfigSha256 = requireNotNull(runtimeConfigSha256),
                classListSha256 = requireNotNull(classListSha256),
                activeDelegate = requireNotNull(activeDelegate),
                delegateFallbackUsed = requireNotNull(delegateFallbackUsed),
            )
        }.getOrNull()
    }
}

private val SHA256_HEX_PATTERN = Regex("^[0-9a-f]{64}$")
private val RAW_ALLOWED_DELEGATES = setOf("cpu", "gpu", "nnapi")
