package kr.co.hanium.dreamup.walksafe.ui

import java.util.Locale
import kotlin.math.roundToInt
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassHeadingReason
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassHeadingResult
import kr.co.hanium.dreamup.walksafe.navigation.RouteFacingSource
import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import kr.co.hanium.dreamup.walksafe.navigation.UserMotionEstimate
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianMotionProfileSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorRegistrationOutcome
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorTrackerHealthSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorTrackerLifecycle
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorTrackerStoppedReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.TrackedSensorSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserDeviceBodyRelationState
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateOwner
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingCalibrationDiagnosticSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingCalibrationOutcome
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingCalibrationReason

/**
 * Read-only motion and positioning diagnostics from copied facts admitted by the caller.
 * This formatter neither collects sensors nor changes calibration, confidence or route state.
 * Original measurement/receipt times and owner identities are never refreshed for display.
 */
internal class PositioningDiagnosticsPresenter {
    fun userMotionStatusText(state: UserMotionStateSnapshot, motion: UserMotionEstimate): String {
        fun number(value: Double?): String = value?.let { String.format(Locale.US, "%.1f", it) } ?: "unknown"
        return "userState=${state.movement.name} locomotion=${state.locomotion.name} " +
            "deviceRotation=${state.deviceRotation.name} mount=${state.mount.name} motionReason=${state.reason.name} " +
            "userSpeed=${number(motion.speedMps)}m/s " +
            "travelCourse=${number(motion.courseDegreesTrueNorth)}deg " +
            "phoneHeading=${number(motion.phoneHeadingDegreesTrueNorth)}deg"
    }

    /** Consumes the existing decision for display; it never evaluates confidence. */
    fun positionConfidenceDiagnosticLine(snapshot: PositioningSnapshot): String {
        val decision = snapshot.confidence
        return "event=CONFIDENCE quality=${decision.quality.name} transition=${decision.transition.name} " +
            "paused=${decision.guidancePaused} high_recovery_samples=${decision.consecutiveHighRecoverySamples} " +
            "failure=${decision.failureReason?.name ?: "NONE"} " +
            "gnss=${snapshot.gnssUpdate?.disposition?.name ?: "NONE"} " +
            "reject=${snapshot.gnssUpdate?.hardRejectReason?.name ?: "NONE"} " +
            "reported_accuracy_m=${snapshot.raw?.reportedHorizontalAccuracyM} " +
            "filtered_sigma_m=${snapshot.filtered?.horizontalUncertaintyM}"
    }

    fun motionEvidenceStatusMessage(
        state: UserMotionStateSnapshot,
        motion: UserMotionEstimate,
        stepHealth: SensorTrackerHealthSnapshot?,
        motionHealth: SensorTrackerHealthSnapshot?,
        activityPermissionGranted: Boolean,
        expectedOwner: UserMotionStateOwner?,
    ): String {
        fun sensorMessage(label: String, health: SensorTrackerHealthSnapshot?, step: Boolean): String {
            health ?: return "$label: 현재 수집 상태를 아직 확인하지 못했습니다."
            val lifecycle = when {
                health.cleanupPending -> "이전 등록 자원의 정리가 끝나지 않았습니다."
                health.lifecycle == SensorTrackerLifecycle.CLOSED -> "수집을 종료했습니다."
                health.stoppedReason == SensorTrackerStoppedReason.FAILED_START -> "수집을 시작하지 못했습니다."
                health.lifecycle == SensorTrackerLifecycle.STOPPED -> "수집을 중단했습니다."
                health.lifecycle == SensorTrackerLifecycle.STARTING -> "센서 등록을 진행하고 있습니다."
                health.lifecycle == SensorTrackerLifecycle.IDLE -> "수집을 아직 시작하지 않았습니다."
                else -> "센서 등록 상태를 확인했습니다."
            }
            val permission = if (step && !activityPermissionGranted) "현재 활동 인식 권한이 없습니다." else ""
            val sources = health.sources.map { source ->
                val sourceLabel = when (source.source) {
                    TrackedSensorSource.STEP_COUNTER -> "걸음 수 센서"
                    TrackedSensorSource.STEP_DETECTOR -> "개별 걸음 센서"
                    TrackedSensorSource.ACCELEROMETER -> "가속도 센서"
                    TrackedSensorSource.GYROSCOPE -> "회전 센서"
                }
                val sample = source.lastAcceptedSample?.takeIf {
                    source.acceptingSamples && health.lifecycle == SensorTrackerLifecycle.ACTIVE &&
                        it.producerRegistrationGeneration == health.registrationGeneration
                }
                val detail = when {
                    sample != null -> "실제 측정 이력이 있습니다." +
                        if (sample.receivedAtElapsedRealtimeNanos == null) " 원 수신 시각은 확인되지 않았습니다." else ""
                    source.lastRegistrationOutcome == SensorRegistrationOutcome.REGISTERED && source.acceptingSamples ->
                        "등록됐지만 실제 측정은 아직 확인되지 않았습니다."
                    source.lastRegistrationOutcome == SensorRegistrationOutcome.UNAVAILABLE -> "사용 가능한 센서가 없습니다."
                    source.lastRegistrationOutcome == SensorRegistrationOutcome.RETURNED_FALSE -> "등록 요청이 수용되지 않았습니다."
                    source.lastRegistrationOutcome == SensorRegistrationOutcome.SECURITY_EXCEPTION -> "등록 당시 권한 문제로 시작하지 못했습니다."
                    source.lastRegistrationOutcome == SensorRegistrationOutcome.RUNTIME_EXCEPTION -> "등록 중 오류로 시작하지 못했습니다."
                    else -> "현재 측정 수집이 확인되지 않았습니다."
                }
                "$sourceLabel: $detail"
            }
            return (listOf("$label: $lifecycle", permission) + sources).filter { it.isNotBlank() }.joinToString(" ")
        }
        val sensors = listOf(sensorMessage("걸음 감지", stepHealth, true), sensorMessage("휴대전화 움직임", motionHealth, false))
        if (expectedOwner == null || state.owner != expectedOwner ||
            state.reason in setOf(UserMotionStateReason.OWNER_MISMATCH, UserMotionStateReason.GENERATION_MISMATCH)
        ) return (listOf("현재 보행 범위의 움직임 근거를 다시 확인하고 있습니다.") +
            sensors.map { if (it.contains("정리가 끝나지")) it else "이전 보행의 측정은 현재 움직임 판단에 사용하지 않습니다." }.distinct())
            .joinToString("\n")
        val movement = when (state.movement) {
            kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMovementState.MOVING -> "이동 근거가 있습니다."
            kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMovementState.STATIONARY -> "정지 근거가 있습니다."
            else -> "이동 중인지 아직 확인하지 못했습니다."
        }
        val mounting = when (state.mount) {
            UserDeviceBodyRelationState.SUPPORTED_MOUNT -> "현재 보행의 명시적 고정 상태를 확인했습니다."
            UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED -> "휴대전화 고정 방식이 지원 조건과 다릅니다."
            UserDeviceBodyRelationState.UNKNOWN -> "현재 휴대전화 고정 상태는 확인되지 않았습니다."
        }
        fun reasonMessage(reason: UserMotionStateReason): String? = when (reason) {
            UserMotionStateReason.STEP_WITH_STATIONARY_GNSS -> "GPS 정지 근거와 실제 걸음 감지가 함께 들어와 근거가 서로 맞지 않습니다."
            UserMotionStateReason.GNSS_MOVEMENT_WITH_QUIET_DEVICE -> "GPS 이동 근거와 휴대전화의 조용한 상태가 함께 관측되었습니다."
            UserMotionStateReason.GNSS_MOVEMENT_UNCONFIRMED -> "GPS의 이동 변화는 아직 연속해서 확인되지 않았습니다."
            UserMotionStateReason.NO_EVIDENCE -> "현재 움직임을 판단할 측정 근거를 기다립니다."
            UserMotionStateReason.EXPIRED_EVIDENCE -> "이전 움직임 근거가 만료되어 새 측정을 기다립니다."
            UserMotionStateReason.INSUFFICIENT_EVIDENCE -> "현재 움직임을 판단하기에 근거가 충분하지 않습니다."
            UserMotionStateReason.INVALID_TIME -> "측정 시각이 맞지 않아 현재 근거로 사용할 수 없습니다."
            else -> null
        }
        val reasons = (state.support.conflicts.map { it.reason } + state.reasonCodes).mapNotNull(::reasonMessage).distinct()
        val directions = if (motion.phoneHeading != null && motion.course == null) {
            listOf("휴대전화 방향만으로 이동 방향을 확정하지 않습니다.")
        } else emptyList()
        return (listOf(movement, mounting) + reasons + directions + sensors).joinToString("\n")
    }

    fun walkingCalibrationProfileMessage(profile: PedestrianMotionProfileSnapshot): String =
        if (profile.isCalibrated) "개인 보폭 보정값을 사용하고 있습니다."
        else "개인 보폭 보정이 아직 완료되지 않아 기본 보폭을 사용합니다."

    fun walkingCalibrationDiagnosticMessage(
        profile: PedestrianMotionProfileSnapshot,
        diagnostic: WalkingCalibrationDiagnosticSnapshot,
        step: StepEvent?,
    ): String {
        val profileMessage = walkingCalibrationProfileMessage(profile)
        val decision = diagnostic.latestDecision?.takeIf { (diagnostic.decisionCounts[it] ?: 0L) > 0L }
            ?: return "$profileMessage 보정에 쓸 걸음과 위치 정보를 기다립니다."
        val reason = when (decision.reason) {
            WalkingCalibrationReason.STEP_NOT_INDIVIDUAL -> if (step?.source == StepEventSource.STEP_DETECTOR &&
                step.confidence != StepEventConfidence.HIGH
            ) {
                "이 걸음 감지의 신뢰가 낮아 보정에 쓰지 않았습니다."
            } else "이 입력에는 개별 걸음 시각이 확인되지 않아 보정에 쓰지 않았습니다."
            WalkingCalibrationReason.STEP_NOT_SINGLE -> "한 번에 들어온 여러 걸음을 보정에 쓰지 않았습니다."
            WalkingCalibrationReason.WAITING_FOR_RAW_FIX -> "걸음에 대응하는 위치 정보를 기다립니다."
            WalkingCalibrationReason.WAITING_FOR_STEP_WATERMARK,
            WalkingCalibrationReason.NEW_SEGMENT_ANCHOR -> "걸음과 위치 정보를 맞추며 직선 보행 구간을 확인하고 있습니다."
            WalkingCalibrationReason.INSUFFICIENT_STEPS,
            WalkingCalibrationReason.INSUFFICIENT_DURATION,
            WalkingCalibrationReason.INSUFFICIENT_DISTANCE -> "보정에 충분한 직선 보행 구간이 모일 때까지 기다립니다."
            WalkingCalibrationReason.STRAIGHT_SEGMENT_READY -> "보정에 쓸 유효한 직선 보행 구간을 확인했습니다."
            WalkingCalibrationReason.ELIGIBLE_STEP,
            WalkingCalibrationReason.CREDIBLE_RAW_FIX,
            WalkingCalibrationReason.ALIGNED_FIX -> "보정에 필요한 걸음과 위치 입력을 확인했습니다."
            WalkingCalibrationReason.UNTRUSTED_RAW_FIX,
            WalkingCalibrationReason.INVALID_FIX_COORDINATE,
            WalkingCalibrationReason.MISSING_FIX_ACCURACY,
            WalkingCalibrationReason.UNSUITABLE_FIX_ACCURACY -> "위치 정보의 신뢰가 부족해 이 입력을 보정에 쓰지 않았습니다."
            WalkingCalibrationReason.MISSING_SPEED,
            WalkingCalibrationReason.MISSING_SPEED_ACCURACY,
            WalkingCalibrationReason.MISSING_COURSE,
            WalkingCalibrationReason.MISSING_COURSE_ACCURACY,
            WalkingCalibrationReason.UNSUITABLE_SPEED,
            WalkingCalibrationReason.UNSUITABLE_SPEED_ACCURACY,
            WalkingCalibrationReason.UNSUITABLE_COURSE,
            WalkingCalibrationReason.UNSUITABLE_COURSE_ACCURACY -> "보행 속도나 방향 근거가 충분하지 않아 이 입력을 보정에 쓰지 않았습니다."
            WalkingCalibrationReason.COURSE_SPREAD,
            WalkingCalibrationReason.POSITION_JUMP,
            WalkingCalibrationReason.REVERSED_DISPLACEMENT,
            WalkingCalibrationReason.LATERAL_DISPLACEMENT,
            WalkingCalibrationReason.DISPLACEMENT_COURSE_MISMATCH,
            WalkingCalibrationReason.SPEED_DISTANCE_MISMATCH -> "직선 보행을 확인하지 못해 이 구간을 보정에 쓰지 않았습니다."
            else -> "걸음과 위치 입력의 시각이나 연속성이 맞지 않아 이 입력을 보정에 쓰지 않았습니다."
        }
        val waiting = if (decision.outcome == WalkingCalibrationOutcome.WAITING) "보정 대기: " else ""
        return "$profileMessage $waiting$reason"
    }

    /** A null result means the caller has not admitted a current compass collection. */
    fun routeCompassStatusMessage(result: RouteCompassHeadingResult?): String {
        result ?: return "위치 측정과 함께 나침반 방향을 준비합니다."
        result.observation?.let { observation ->
            val labels = listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")
            val index = ((observation.degreesTrueNorth + 22.5) / 45.0).toInt() % labels.size
            val degrees = observation.degreesTrueNorth.roundToInt() % 360
            val source = if (observation.source == RouteFacingSource.ROTATION_VECTOR) "" else " 보조 나침반 사용 중."
            return "나침반: 휴대폰 후면은 ${labels[index]}쪽(${degrees}도)을 향하고 있습니다.$source"
        }
        return when (result.reason) {
            RouteCompassHeadingReason.SENSOR_UNAVAILABLE -> "나침반 센서를 시작하지 못했습니다."
            RouteCompassHeadingReason.HEADING_ACCURACY_UNAVAILABLE -> "나침반 센서가 방향 오차 정보를 아직 제공하지 않습니다."
            RouteCompassHeadingReason.REFERENCE_MISSING -> "현재 위치를 확인해 나침반의 진북 기준을 맞추고 있습니다."
            RouteCompassHeadingReason.REAR_AXIS_VERTICAL -> "휴대폰 후면이 바닥이나 하늘을 향하고 있습니다. 후면을 걷는 방향으로 들어 주세요."
            RouteCompassHeadingReason.GRAVITY_MISSING, RouteCompassHeadingReason.MAGNETIC_FIELD_MISSING ->
                "보조 나침반에 필요한 센서 값을 기다리고 있습니다."
            RouteCompassHeadingReason.GRAVITY_UNSTABLE ->
                "휴대폰 움직임이 커서 방향을 다시 확인하고 있습니다. 잠시 안정적으로 잡아 주세요."
            RouteCompassHeadingReason.SENSOR_STALLED ->
                "나침반 센서 값이 들어오지 않아 방향 안내를 멈췄습니다."
            RouteCompassHeadingReason.QUALITY_LOW, RouteCompassHeadingReason.HEADING_ACCURACY,
            RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY ->
                "나침반 방향이 불안정합니다. 자석·금속에서 떨어져 휴대폰을 천천히 움직여 주세요."
            else -> "새 나침반 방향을 기다리고 있습니다."
        }
    }
}
