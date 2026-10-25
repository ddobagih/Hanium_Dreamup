package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorDecisionToken
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.TactileActiveRouteProgress
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import kr.co.hanium.dreamup.walksafe.session.WalkSessionRecoveryStage
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

internal enum class RuntimeGpsSpeechMeaning { AVAILABLE, ACCURACY_LIMITED, FIX_UNAVAILABLE, WAITING }

internal data class RuntimeGpsSpeechState(
    val epoch: WalkRuntimeEpoch,
    val profileId: String?,
    val meaning: RuntimeGpsSpeechMeaning,
)

/** Current projections are read in lease order, so a retired prefix does not read later state. */
internal interface CurrentStateSpeechReader {
    val routeGeneration: Int
    val routeId: String?
    val routeProgress: TactileActiveRouteProgress
    val routeActive: Boolean
    val routeRequestPending: Boolean
    val positionPaused: Boolean
    val directionPauseReason: String?
    val decisionToken: RouteNavigatorDecisionToken?
    val runtimeGpsState: RuntimeGpsSpeechState?
    val destination: RoutePoint?
    val pendingDestination: DestinationSearchResult?
    val lifecycle: WalkSessionLifecycle?
    val epoch: WalkRuntimeEpoch?
    val state: WalkSessionState?
    val recoveryStage: WalkSessionRecoveryStage?
    val page: Enum<*>
    val homeRouteMessage: String
}

/** Published semantic inputs, captured once before producing the state answer. */
internal data class CurrentStateSpeechContext(
    override val routeGeneration: Int,
    override val routeId: String?,
    override val routeProgress: TactileActiveRouteProgress,
    override val routeActive: Boolean,
    override val routeRequestPending: Boolean,
    override val positionPaused: Boolean,
    override val directionPauseReason: String?,
    override val decisionToken: RouteNavigatorDecisionToken?,
    override val runtimeGpsState: RuntimeGpsSpeechState?,
    override val destination: RoutePoint?,
    override val pendingDestination: DestinationSearchResult?,
    override val lifecycle: WalkSessionLifecycle?,
    override val epoch: WalkRuntimeEpoch?,
    override val state: WalkSessionState?,
    override val recoveryStage: WalkSessionRecoveryStage?,
    override val page: Enum<*>,
    override val homeRouteMessage: String,
) : CurrentStateSpeechReader

/** One state-answer snapshot; READY and native START read current inputs only while comparing. */
internal class CurrentStateSpeechLease private constructor(private val captured: CurrentStateSpeechContext) {
    fun isCurrent(current: CurrentStateSpeechReader): Boolean =
        current.routeGeneration == captured.routeGeneration &&
            current.routeId == captured.routeId && current.routeProgress == captured.routeProgress &&
            current.routeActive == captured.routeActive && current.routeRequestPending == captured.routeRequestPending &&
            current.positionPaused == captured.positionPaused &&
            current.directionPauseReason == captured.directionPauseReason &&
            current.decisionToken == captured.decisionToken && current.runtimeGpsState == captured.runtimeGpsState &&
            current.destination == captured.destination && current.pendingDestination === captured.pendingDestination &&
            current.lifecycle === captured.lifecycle && current.epoch == captured.epoch &&
            current.state == captured.state && current.recoveryStage == captured.recoveryStage &&
            current.page == captured.page && current.homeRouteMessage == captured.homeRouteMessage

    companion object {
        fun capture(context: CurrentStateSpeechContext): CurrentStateSpeechLease = CurrentStateSpeechLease(context)
    }
}
