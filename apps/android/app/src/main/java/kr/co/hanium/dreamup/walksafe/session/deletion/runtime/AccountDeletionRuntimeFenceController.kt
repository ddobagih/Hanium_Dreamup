package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

/** Ordered cross-domain admission fence; existing peers retain their own mutable authority. */
internal class AccountDeletionRuntimeFenceController(
    private val blockSpeechStorage: () -> Unit,
    private val purgeAndRotatePosition: () -> Boolean,
    private val reports: AccountDeletionReportFenceActions,
    private val fenceRawCollections: () -> Unit,
    private val blockFieldProcessing: () -> Unit,
    private val blockConsent: () -> Unit,
    private val clearPermissions: () -> Unit,
    private val disableMetadata: () -> Unit,
    private val invalidateFrames: () -> Unit,
    private val stopActiveWalk: () -> Unit,
    private val showPositionFailure: () -> Unit,
) {
    fun apply(): Boolean {
        blockSpeechStorage()
        val positionFieldPurged = purgeAndRotatePosition()
        reports.cancelDrainAndFenceQueue()
        fenceRawCollections()
        reports.clearUserRequests()
        blockFieldProcessing()
        blockConsent()
        reports.advanceUploadAuthority()
        clearPermissions()
        disableMetadata()
        reports.cancelOriginalUploads()
        invalidateFrames()
        reports.clearAttemptState()
        stopActiveWalk()
        if (!positionFieldPurged) showPositionFailure()
        return positionFieldPurged
    }
}

internal interface AccountDeletionReportFenceActions {
    fun cancelDrainAndFenceQueue()
    fun clearUserRequests()
    fun advanceUploadAuthority()
    fun cancelOriginalUploads()
    fun clearAttemptState()
}
