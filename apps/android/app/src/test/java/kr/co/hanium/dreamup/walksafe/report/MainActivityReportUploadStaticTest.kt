package kr.co.hanium.dreamup.walksafe.report

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityReportUploadStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val gatewaySource = File("src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSession.kt").readText()
    private val uploaderSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportUploader.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun reportUploadStateTokensCoverPreparedUploadingSuccessFailureAndBackoff() {
        assertTrue(source.contains("reportCandidate=blocked:no_report_image"))
        assertTrue(source.contains("reportCandidate=prepared"))
        assertTrue(source.contains("reportCandidate=uploading"))
        assertTrue(source.contains("reportCandidate=succeeded"))
        assertTrue(source.contains("reportCandidate=failed_http"))
        assertTrue(source.contains("reportCandidate=failed retryMs="))
        assertTrue(source.contains("reportCandidate=duplicate_inflight"))
        assertTrue(source.contains("reportCandidate=cooldown_"))
        assertTrue(source.contains("reportCandidate=backoff"))
        assertTrue(source.contains("reportCandidate=succeeded_duplicate"))
        assertTrue(source.contains("duplicateReportIds"))
    }

    @Test
    fun successfulUploadRecordsSpatialCooldownBeforeSessionOrUiCanRace() {
        val uploadIndex = source.indexOf("val response = uploadCall.execute()")
        val cooldownIndex = source.indexOf("reportAttemptStore.markSucceeded", startIndex = uploadIndex)
        val sessionGuardIndex = source.indexOf("if (!isCurrentGatewaySession(gatewaySession)) return@execute", startIndex = uploadIndex)

        assertTrue(uploadIndex >= 0)
        assertTrue(cooldownIndex > uploadIndex)
        assertTrue(sessionGuardIndex > cooldownIndex)
    }

    @Test
    fun reportCooldownUsesActorGpsScopeAndVoiceOnlyBypassesAutomaticCooldown() {
        assertTrue(source.contains("AndroidReportCooldownPolicy.spatialScopeOrNull"))
        assertTrue(source.contains("bypassAutomaticCooldown = explicitRequest"))
        assertFalse(source.contains("\${output.trackId}:\${output.className}:\${output.source}"))
        assertTrue(source.contains("restoreReportCooldownsFromPrefs()"))
        assertTrue(source.contains("persistReportCooldowns()"))
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
    }

    @Test
    fun explicitReportRequiresLoginGpsAndSpeaksDuplicateState() {
        assertTrue(source.contains("login_required_explicit_report"))
        assertTrue(source.contains("trigger = \"voice\""))
        assertTrue(source.contains("위치 정보가 필요합니다."))
        assertTrue(source.contains("이미 신고가 된 상태입니다."))
        assertTrue(source.contains("reporterUserId = reporterId"))
    }

    @Test
    fun rejectedGpsFixDoesNotEraseTheLastJumpFilterBaseline() {
        val rejectedFixBranch = source
            .substringAfter("val freshTrusted = LocationTrustPolicy.freshOrNull")
            .substringBefore("latestTrustedLocation = freshTrusted")

        assertTrue(rejectedFixBranch.contains("clearLocationDerivedState()"))
        assertFalse(rejectedFixBranch.contains("clearTrustedLocation()"))
    }

    @Test
    fun reportUsesReportObjectGateAndBoundGatewaySession() {
        assertTrue(source.contains("bestOutput = reportOutput,"))
        assertTrue(source.contains("gateState = reportGateState"))
        assertTrue(source.contains("gatewaySessionOrNull(reason = \"report\""))
        assertTrue(source.contains("session = gatewaySession,"))
        assertTrue(gatewaySource.contains("/api/field-session"))
        assertTrue(gatewaySource.contains("statusJson.optString(\"actor_id\") != actor"))
        assertTrue(source.contains("clearGatewaySession(logoutRemote = true)"))
        assertTrue(source.contains("backendFieldTokenInput.text?.clear()"))
        assertTrue(source.contains("AndroidGatewaySessionStore"))
        assertTrue(source.contains("restoreGatewaySessionFromPrefs()"))
        assertTrue(uploaderSource.contains("/api/reports/v2"))
        assertTrue(uploaderSource.contains("session.requestHeaders()"))
        assertFalse(uploaderSource.contains("x-walksafe-field-test-token"))
        assertFalse(uploaderSource.contains("x-walksafe-actor-id"))
        assertTrue(source.contains("requestElapsedRealtimeMs = requestElapsedRealtimeMs"))
    }

    @Test
    fun privacyConsentGatesAndCancelsOnlyReportTransferWhileLocalDetectionRemainsWired() {
        val reportPreparation = source
            .substringAfter("private fun prepareReportCandidate(")
            .substringBefore("private fun processReportCandidate(")
        val pauseLifecycle = source
            .substringAfter("override fun onPause()")
            .substringBefore("internal fun pauseWalkSafeRuntime()")
        val pauseRuntime = source
            .substringAfter("internal fun pauseWalkSafeRuntime()")
            .substringBefore("override fun onDestroy()")
        val destroyLifecycle = source
            .substringAfter("override fun onDestroy()")
            .substringBefore("override fun onRequestPermissionsResult(")
        val cameraPermissionResult = source
            .substringAfter("private fun handleCameraPermissionResult()")
            .substringBefore("private fun handleNavigationPermissionResult()")

        assertTrue(reportPreparation.contains("reportPrivacyConsentSession.isGranted()"))
        assertTrue(reportPreparation.indexOf("privacy_consent_required") < reportPreparation.indexOf("freshTrustedLocationOrNull"))
        assertTrue(source.contains("reportPrivacyConsentSession.trackIfLive("))
        assertTrue(
            source.contains(
                "reportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent()",
            ),
        )
        assertTrue(source.contains("reportPrivacyConsentSession.complete(uploadCall)"))
        assertFalse(source.contains("withdrawReportPrivacyConsent(reason = \"gateway_session_ended\")"))
        assertTrue(pauseLifecycle.contains("pauseWalkSafeRuntime()"))
        assertTrue(pauseRuntime.contains("reportPrivacyConsentSession.cancelActiveCalls()"))
        assertTrue(destroyLifecycle.contains("reportPrivacyConsentSession.cancelActiveCalls()"))
        assertTrue(source.contains("text = REPORT_PRIVACY_DISCLOSURE_KO"))
        assertTrue(source.contains("setOnClickListener { onReportPrivacyConsentButtonClicked() }"))
        assertTrue(source.contains("setOnClickListener { onAutomaticReportConsentButtonClicked() }"))
        assertTrue(source.contains("ReportTransferPurpose.AUTOMATIC"))
        assertFalse(cameraPermissionResult.contains("reportPrivacyConsentSession.grant()"))
        assertTrue(source.contains("scheduleDetectionIfDue("))
        assertTrue(source.contains("emitFeedbackAction(it, elapsedRealtimeMs)"))
        assertTrue(source.contains("PREF_REPORT_PRIVACY_CONSENT_KEY"))
        assertTrue(source.contains("PREF_AUTOMATIC_REPORT_CONSENT_KEY"))
    }

    @Test
    fun lateOldSessionResponseCannotClearOrPublishOverCurrentSession() {
        assertTrue(
            source.contains(
                "if (expectedSession != null && previous !== expectedSession) return null",
            ),
        )
        assertTrue(source.contains("expectedSession = session"))
        assertTrue(source.contains("if (!isCurrentGatewaySession(gatewaySession)) return@execute"))
        assertTrue(source.contains("gatewayFailureUiGuardOrNull"))
        assertTrue(source.contains("isGatewayFailureUiGuardCurrent"))
        assertTrue(source.contains("expectedAuthenticationFailure"))
        assertTrue(source.contains("destination_search_cancelled session_changed"))
        assertTrue(source.contains("route_cancelled session_changed"))
    }

    @Test
    fun voiceCommandUsesSpeechRecognizerAndKeepsExplicitReportRoute() {
        assertTrue(manifest.contains("android.permission.RECORD_AUDIO"))
        assertTrue(source.contains("PermissionRequestPurpose.VOICE_COMMAND"))
        assertTrue(source.contains("requestPermissionsWithLease("))
        assertTrue(source.contains("Manifest.permission.RECORD_AUDIO"))
        assertTrue(source.contains("SpeechRecognizer.isOnDeviceRecognitionAvailable"))
        assertTrue(source.contains("SpeechRecognizer.createOnDeviceSpeechRecognizer"))
        assertTrue(source.contains("RecognizerIntent.ACTION_RECOGNIZE_SPEECH"))
        assertTrue(source.contains("음성 명령"))
        assertTrue(source.contains("voice=report_command_recognized"))
        assertTrue(source.contains("requestExplicitReport()"))
        assertTrue(source.contains("selectAndroidVoiceAction(phrases, confidenceScores)"))
        assertTrue(source.contains("SpeechRecognizer.CONFIDENCE_SCORES"))
        assertTrue(source.contains("selectVoiceDestinationCandidate"))
        assertTrue(source.contains("cancelVoiceCommandRecognition()"))
        assertTrue(source.contains("expectedGeneration != voiceRecognitionGeneration"))
        assertTrue(source.contains("walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch)"))
    }

    @Test
    fun apkDigestIsHexEncodedWithoutHashingTheDigestTwice() {
        assertTrue(source.contains("hexEncoded(digest.digest())"))
        assertTrue(source.contains("hexEncoded(MessageDigest.getInstance(\"SHA-256\").digest(bytes))"))
    }
}
