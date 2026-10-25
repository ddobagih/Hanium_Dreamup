package kr.co.hanium.dreamup.walksafe.session.deletion.reset

import kr.co.hanium.dreamup.walksafe.session.AccountDeletionAuthoritySnapshot
import kr.co.hanium.dreamup.walksafe.startup.PreparedPrivacyStartupReset

/** Strict confirmed-reset capture: a preparation failure propagates to the reservation rollback. */
internal object InstallationResetParticipants {
    fun <S, R> capture(
        prepareSpeech: () -> S,
        prepareRoute: () -> R?,
        awaitSpeech: (S) -> Boolean,
        awaitRoute: (R?) -> Boolean,
        speechIsCurrent: (S) -> Boolean,
        routeIsCurrent: (R) -> Boolean,
    ): PreparedPrivacyStartupReset {
        val speech = prepareSpeech()
        val route = prepareRoute()
        return object : PreparedPrivacyStartupReset {
            override fun awaitOriginalAcknowledgements(): Boolean {
                val speechPurgedAndDrained = awaitSpeech(speech)
                val routePurged = awaitRoute(route)
                return speechPurgedAndDrained && routePurged
            }
            override fun isCurrent(): Boolean = speechIsCurrent(speech) && route?.let(routeIsCurrent) == true
        }
    }
}

internal interface PreparedResetSpeechCollection {
    fun publish(): Boolean
    fun closeAndDrain()
}

/** The startup controller remains the single process owner of this handoff bit. */
internal interface InstallationResetStartupHandoff {
    var pending: Boolean
}

internal interface InstallationResetActions {
    fun isDestroyed(): Boolean
    fun activityCallbackAllowed(): Boolean
    fun updateUi()
    fun keepPrivacyFenceClosed()
    fun invalidateGatewaySession()
    fun captureParticipants(): PreparedPrivacyStartupReset
    fun prepareFreshSpeechCollection(): PreparedResetSpeechCollection?
    fun resetReportPrivacyForNewEnrollment()
    fun resetRuntimeForNewEnrollment()
    fun finishActivity()
    fun observeStartupAuthority(snapshot: AccountDeletionAuthoritySnapshot)
    fun blockPrivacyForResetFailure()
    fun applyTerminalPersistenceFailure(reason: String)
    fun showTerminalPersistenceFailure(reason: String)
}

internal enum class InstallationResetStartupResult { CONTINUE, FINISH }
internal enum class LegacyResetIntentState { ABSENT, EXACT, CORRUPT }
