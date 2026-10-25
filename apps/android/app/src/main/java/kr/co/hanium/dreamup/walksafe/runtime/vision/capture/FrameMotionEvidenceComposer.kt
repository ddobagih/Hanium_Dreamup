package kr.co.hanium.dreamup.walksafe.runtime.vision.capture

import kr.co.hanium.dreamup.walksafe.navigation.session.PositioningSessionController
import kr.co.hanium.dreamup.walksafe.navigation.session.WalkingCalibrationScope
import kr.co.hanium.dreamup.walksafe.ui.PositioningDiagnosticsPresenter
import kr.co.hanium.dreamup.walksafe.navigation.UserMotionEstimateFactory
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorTrackerLifecycle
import android.hardware.GeomagneticField
import com.google.ar.core.Frame
import kr.co.hanium.dreamup.walksafe.depth.MotionContext
import kr.co.hanium.dreamup.walksafe.navigation.AndroidStepTracker
import kr.co.hanium.dreamup.walksafe.navigation.ArCoreTactileProjectionContextFactory
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.TactileProjectionContext
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionSnapshot

internal interface FrameMotionEvidenceSource {
    fun freshTrustedLocationOrNull(nowElapsedRealtimeMs: Long): TrustedLocation?
    fun orientation(): kr.co.hanium.dreamup.walksafe.navigation.DeviceEarthOrientation?
    fun elapsedRealtimeMs(): Long
    fun lifecycleSnapshot(): kr.co.hanium.dreamup.walksafe.session.WalkSessionSnapshot?
    fun gatewaySnapshot(): kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessSnapshot
    fun stepTrackerOrNull(): AndroidStepTracker?
    val stepTrackingEpoch: WalkRuntimeEpoch?
    fun activityRecognitionPermissionGranted(): Boolean
    fun synchronizeCalibrationScope(): WalkingCalibrationScope
}

internal interface WalkingCalibrationDisplay {
    fun guidanceStatusPublished(): Boolean
    fun guidancePageCurrent(): Boolean
    fun updateCurrentGuidance(message: String)
    fun onMainThread(): Boolean
    fun onUiThread(action: () -> Unit)
}

/** Reads the existing positioning owner and source refs at their original proof/UI boundaries. */
internal class FrameMotionEvidenceComposer(
    private val positioning: () -> kr.co.hanium.dreamup.walksafe.navigation.session.PositioningSessionController,
    private val navigator: () -> RouteNavigator,
    private val source: FrameMotionEvidenceSource,
    private val display: WalkingCalibrationDisplay,
    private val presenter: PositioningDiagnosticsPresenter,
) {
    fun buildDepthMotionContext(elapsedRealtimeMs: Long): MotionContext {
        // General obstacle confidence is independent of destination and route state. Route-bound
        // tactile guidance validates heading and corridor separately in TactileRoutePolicy.
        val freshness = source.freshTrustedLocationOrNull(elapsedRealtimeMs)?.let { trusted ->
            val ageMs = elapsedRealtimeMs - trusted.elapsedRealtimeMs
            when {
                ageMs < 0L -> 0.8f
                ageMs <= 2_500L -> 1f
                ageMs <= 7_500L -> 0.75f
                else -> 0.45f
            }
        } ?: 0.7f
        val gpsMotion = positioning().gpsMotionInputs
        return MotionContext(
            freshnessQuality = freshness,
            userMotion = UserMotionEstimateFactory.estimate(
                nowElapsedRealtimeMs = elapsedRealtimeMs,
                speed = gpsMotion?.speed,
                course = gpsMotion?.course,
                phoneHeading = positioning().chestMountedHeadingForStep(elapsedRealtimeMs),
                locationEvidence = gpsMotion?.evidence,
            ),
        )
    }

    fun buildTactileProjectionContext(
        frame: Frame,
        elapsedRealtimeMs: Long,
    ): TactileProjectionContext {
        val location = source.freshTrustedLocationOrNull(elapsedRealtimeMs)
        val expectedRouteId = navigator().currentRouteId()
        val routeProjection = location?.let(navigator()::currentProjection)
        val magneticDeclinationDeg = location?.let { trusted ->
            runCatching {
                GeomagneticField(
                    trusted.latitude.toFloat(),
                    trusted.longitude.toFloat(),
                    0f,
                    System.currentTimeMillis(),
                ).declination
            }.getOrNull()
        }
        return ArCoreTactileProjectionContextFactory.create(
            frame = frame,
            elapsedRealtimeMs = elapsedRealtimeMs,
            expectedRouteId = expectedRouteId,
            routeProjection = routeProjection,
            trustedLocation = location,
            orientation = source.orientation(),
            magneticDeclinationDeg = magneticDeclinationDeg,
        )
    }

    /** Current UI facts; tracker registration never supplies a measurement or receipt time. */
    fun currentMotionEvidenceStatusMessage(): String {
        val coordinator = positioning().coordinator
        val binding = positioning().binding
        val epoch = source.lifecycleSnapshot()?.epoch
        val gateway = source.gatewaySnapshot()
        val nowMs = source.elapsedRealtimeMs()
        val state = coordinator.peekUserMotion(nowMs)
        val owner = state.owner?.takeIf {
            binding != null && binding.coordinator === coordinator && state.generation == binding.inputGeneration &&
                it == coordinator.currentUserMotionOwner() && it.walkEpoch == epoch &&
                it.actorGeneration == gateway.generation && binding.owner.gatewaySession === gateway.session &&
                positioning().isPositioningCollectionCurrent(binding)
        }
        val step = source.stepTrackerOrNull()
        val pedestrian = positioning().motionTracker
        val stepHealth = step?.operationalHealth?.takeIf {
            it.lifecycle != SensorTrackerLifecycle.ACTIVE || (owner != null && source.stepTrackingEpoch == epoch)
        }
        val motionHealth = pedestrian?.operationalHealth?.takeIf {
            it.lifecycle != SensorTrackerLifecycle.ACTIVE || owner != null
        }
        val inputs = positioning().gpsMotionInputs.takeIf { owner != null }
        val motion = UserMotionEstimateFactory.estimate(nowMs, inputs?.speed, inputs?.course,
            if (owner != null) positioning().chestMountedHeadingForStep(nowMs) else null, inputs?.evidence)
        val activityPermissionGranted = source.activityRecognitionPermissionGranted()
        val currentGateway = source.gatewaySnapshot()
        val stillOwned = coordinator === positioning().coordinator && binding === positioning().binding &&
            state.owner == coordinator.currentUserMotionOwner() && coordinator.currentInputGeneration() == state.generation &&
            (source.lifecycleSnapshot()?.epoch) == epoch &&
            currentGateway.generation == gateway.generation && currentGateway.session === gateway.session &&
            (source.stepTrackerOrNull()) === step && positioning().motionTracker === pedestrian
        return presenter.motionEvidenceStatusMessage(state, motion, stepHealth, motionHealth, activityPermissionGranted,
            owner.takeIf { stillOwned })
    }

    fun currentUserMotionStatusText(): String {
        val nowMs = source.elapsedRealtimeMs()
        val inputs = positioning().gpsMotionInputs
        val motion = UserMotionEstimateFactory.estimate(
            nowElapsedRealtimeMs = nowMs,
            speed = inputs?.speed,
            course = inputs?.course,
            phoneHeading = positioning().chestMountedHeadingForStep(nowMs),
            locationEvidence = inputs?.evidence,
        )
        val state = positioning().coordinator.peekUserMotion(nowMs)
        return presenter.userMotionStatusText(state, motion)
    }

    /** UI-only display; no Field navigation telemetry or renewed measurement time. */
    fun refreshWalkingCalibrationDiagnostics() {
        val scope = source.synchronizeCalibrationScope()
        if (!display.guidanceStatusPublished()) return
        val profile = positioning().coordinator.currentProfile()
        val diagnostic = positioning().calibrationSegments.diagnosticSnapshot()
        val gateway = source.gatewaySnapshot()
        val lifecycle = source.lifecycleSnapshot()
        val message = presenter.walkingCalibrationDiagnosticMessage(profile, diagnostic, positioning().calibrationStep)
        val refresh = {
            val currentGateway = source.gatewaySnapshot()
            if (positioning().walkingCalibrationScopeIsCurrent(scope) && display.guidancePageCurrent() &&
                currentGateway.generation == gateway.generation && currentGateway.session === gateway.session &&
                (source.lifecycleSnapshot()) == lifecycle &&
                positioning().coordinator.currentProfile() == profile && positioning().calibrationSegments.diagnosticSnapshot() == diagnostic
            ) {
                display.updateCurrentGuidance(message + "\n" + currentMotionEvidenceStatusMessage())
            }
        }
        if (display.onMainThread()) refresh() else display.onUiThread(refresh)
    }

    fun walkingCalibrationStatusMessage(): String {
        val scope = positioning().calibrationScope
        if (scope != null && !positioning().walkingCalibrationScopeIsCurrent(scope)) {
            return "보폭 정보는 현재 사용자를 확인한 뒤 표시합니다."
        }
        val calibrationSummary = presenter.walkingCalibrationDiagnosticMessage(positioning().coordinator.currentProfile(),
            positioning().calibrationSegments.diagnosticSnapshot(), positioning().calibrationStep)
        return calibrationSummary + "\n" + currentMotionEvidenceStatusMessage()
    }
}
