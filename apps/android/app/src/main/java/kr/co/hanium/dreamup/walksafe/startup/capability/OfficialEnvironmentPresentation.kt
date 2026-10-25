package kr.co.hanium.dreamup.walksafe.startup.capability

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.device.environment.*
import java.util.Locale

/** Formatting and accessible view updates; never evaluates or mutates a sensor gate. */
internal class OfficialEnvironmentPresentation(
    private val reader: OfficialEnvironmentDisplayReader,
    private val evidence: () -> OfficialEnvironmentEvidenceStore,
    private val view: OfficialEnvironmentDisplayView,
    private val supportNotice: String,
    private val confirmAction: String,
) {
    fun officialEnvironmentMeasurementDetail(
        assessment: OfficialEnvironmentAssessment,
        includeMeasurements: Boolean = true,
    ): String {
        val details = buildList {
            if (
                OfficialEnvironmentFactor.GPS_QUALITY in assessment.blockingFactors ||
                reader.preflightActive
            ) {
                val profile = reader.profile
                val runtimeEpoch = if (!reader.preflightActive && reader.lifecycleAvailable)
                    reader.runtimeEpochOrNull() else null
                val runtimeGpsContext = runtimeEpoch != null
                val runtimeGpsEvidence = if (runtimeGpsContext) evidence().gpsEvidence?.takeIf { evidence ->
                    // The formatter may only describe evidence owned by this current assessment.
                    val status = assessment.factorStatuses[OfficialEnvironmentFactor.GPS_QUALITY]
                    val normalized = OfficialEnvironmentPolicy.assess(
                        assessment.epoch, assessment.assessedAtElapsedRealtimeMs,
                        evidence, null, null, profile,
                    ).factorStatuses[OfficialEnvironmentFactor.GPS_QUALITY]
                    assessment.epoch == runtimeEpoch && assessment.profileId != null &&
                        assessment.profileId == profile?.profileId && status != EnvironmentEvidenceStatus.UNKNOWN &&
                        evidence.status == status && normalized == status
                } else null
                val gpsDetail = if (runtimeGpsContext) runtimeGpsEvidence?.detail else evidence().gpsDiagnosticDetail
                // Runtime evidence owns a reason, not the preflight's old accuracy measurement.
                val accuracy = if (runtimeGpsContext) null else evidence().gpsDiagnosticAccuracyMeters
                val limit = profile?.maximumGpsHorizontalAccuracyMeters
                add(
                    when (gpsDetail) {
                        "GPS_ACCURACY_OUTSIDE_APPROVED_RANGE" ->
                            if (includeMeasurements && accuracy != null && limit != null) {
                                "위치: 현재 정확도 ${oneDecimal(accuracy)}m · 기준 " +
                                    "${oneDecimal(limit)}m 이하. 열린 하늘이 보이는 곳에서 잠시 기다리세요."
                            } else {
                                "위치: 위치 오차가 커서 방향 안내를 잠시 멈춥니다. 새 위치를 기다려 주세요."
                            }
                        "GPS_QUALITY_PASSED" -> if (reader.preflightActive) {
                            "위치: 정확도 기준을 통과했고 계속 갱신 중입니다."
                        } else if (!includeMeasurements) {
                            "위치: ${locationRecoveryActionMessage()}"
                        } else {
                            "위치: 마지막 측정은 통과했지만 전체 점검이 만료되어 다시 측정해야 합니다."
                        }
                        "TRUSTED_FIX_UNAVAILABLE" ->
                            "위치: 현재 위치를 확인하지 못했습니다. 새 위치를 기다려 주세요."
                        "INVALID_ACCURACY" -> if (!includeMeasurements) {
                            "위치: 위치 오차를 확인할 수 없어 방향 안내를 잠시 멈춥니다. 새 위치를 기다려 주세요."
                        } else "위치: 위치 오차 값을 확인하지 못해 새 위치를 기다리고 있습니다."
                        "STALE_OR_INVALID_TIMESTAMP" -> if (!includeMeasurements) {
                            "위치: 새로 확인된 위치가 없어 방향 안내를 잠시 멈춥니다. 새 위치를 기다려 주세요."
                        } else "위치: 최신 위치가 아니어서 새 위치를 기다리고 있습니다."
                        "LOCATION_REQUEST_FAILED" -> "위치: 위치 요청을 시작하지 못했습니다. 위치 서비스를 확인한 뒤 다시 시도해 주세요."
                        "LOCATION_SERVICE_DISABLED" -> "위치: 위치 서비스가 꺼져 있습니다. 위치 서비스를 켠 뒤 다시 시도해 주세요."
                        "LOCATION_PERMISSION_MISSING" -> "위치: 정확한 위치 권한이 없습니다. 설정에서 위치 권한을 허용해 주세요."
                        else -> if (!includeMeasurements) {
                            "위치: ${locationRecoveryActionMessage()}"
                        } else if (runtimeGpsContext) {
                            "위치: 신뢰할 수 있는 최신 위치를 기다리고 있습니다."
                        } else if (reader.preflightActive) {
                            "위치: 고정밀 위치를 받는 중입니다."
                        } else {
                            "위치: 새 측정이 필요합니다."
                        }
                    },
                )
            }
            if (
                OfficialEnvironmentFactor.CAMERA_QUALITY in assessment.blockingFactors ||
                reader.preflightActive
            ) {
                val observation = evidence().cameraDiagnosticObservation
                val profile = reader.cameraProfile
                add(
                    when (evidence().cameraDiagnosticReason) {
                        "CAMERA_PERMISSION_MISSING" ->
                            "카메라: 권한이 없습니다. 설정에서 카메라 권한을 허용해 주세요."
                        "CAMERA_RELEASE_TIMED_OUT", "PREVIOUS_CAMERA_RELEASE_TIMED_OUT" ->
                            "카메라: 이전 카메라 자원이 닫히지 않았습니다. 앱을 다시 시작한 뒤 점검하세요."
                        "PASSED" -> if (reader.preflightActive) {
                            "카메라: 영상·밝기·정면 각도 기준을 통과했고 계속 확인 중입니다."
                        } else if (!includeMeasurements) {
                            "카메라: 상태를 다시 점검해 주세요."
                        } else {
                            "카메라: 마지막 측정은 통과했지만 전체 점검이 만료되어 다시 측정해야 합니다."
                        }
                        "CAMERA_STABILIZING" -> if (!includeMeasurements) {
                            "카메라: 점검을 완료하지 못했습니다. 카메라를 다시 점검해 주세요."
                        } else "카메라: 안정된 화면을 연속 확인하는 중입니다. 휴대전화를 그대로 유지하세요."
                        CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE.name -> if (!includeMeasurements) {
                            "카메라: 밝기가 기준에 부족합니다. 더 밝은 곳으로 이동하세요."
                        } else "카메라: 현재 밝기 ${oneDecimal(observation?.normalizedBrightness)} · " +
                                "기준 ${oneDecimal(profile?.minimumNormalizedBrightness)} 이상입니다. 더 밝은 곳으로 이동하세요."
                        CameraFrameQualityReason.OCCLUSION_OUTSIDE_APPROVED_RANGE.name -> if (!includeMeasurements) {
                            "카메라: 앞을 가리는 물건이 있는지 확인해 주세요."
                        } else "카메라: 렌즈 가림 ${percentOneDecimal(observation?.occludedFraction)} · " +
                                "기준 ${percentOneDecimal(profile?.maximumOccludedFraction)} 이하입니다. 렌즈를 완전히 열어 주세요."
                        CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name,
                        CameraFrameQualityReason.MOUNT_ANGLE_NEAR_BOUNDARY.name,
                        CameraFrameQualityReason.MOUNT_ANGLE_RECHECK_PENDING.name -> if (!includeMeasurements) {
                            "카메라: 정면 각도가 기준에 맞지 않습니다. 휴대전화 뒷면 카메라가 앞을 향하도록 세우세요."
                        } else "카메라: 정면 각도 ${oneDecimal(observation?.mountPitchDegrees)}도 · " +
                                "기준 ${oneDecimal(profile?.minimumMountPitchDegrees)}도 이상 " +
                                "${oneDecimal(profile?.maximumMountPitchDegrees)}도 이하입니다. " +
                                "휴대전화 뒷면 카메라가 앞을 향하도록 세우세요."
                        CameraFrameQualityReason.FRAME_UNAVAILABLE.name,
                        "CAMERA_BIND_FAILED",
                        -> "카메라: 영상을 받지 못했습니다. 카메라를 다시 점검해 주세요."
                        "MOUNTING_SENSOR_UNAVAILABLE" ->
                            "카메라: 장착 각도를 확인하지 못했습니다. 중력 센서를 확인한 뒤 다시 점검하세요."
                        else -> if (!includeMeasurements) {
                            "카메라: 상태를 확인하지 못했습니다. 카메라를 다시 점검해 주세요."
                        } else if (reader.preflightActive) {
                            "카메라: 영상과 장착 센서 값을 받는 중입니다."
                        } else {
                            "카메라: 새 측정이 필요합니다."
                        }
                    },
                )
            }
        }
        return details.joinToString(separator = "\n", postfix = if (details.isEmpty()) "" else "\n")
    }

    fun officialEnvironmentStatusMessage(
        assessment: OfficialEnvironmentAssessment,
    ): String {
        if (reader.bypass) return "안내 시작 전 환경 점검을 생략합니다."
        if (reader.profile == null) {
            return "$supportNotice\n사용 불가\n" +
                "원인: 실제 기기·현장시험으로 승인된 카메라·환경 품질 프로필이 없습니다.\n" +
                "다음 행동: 승인 기록이 생길 때까지 WalkSafe 보행 안내를 시작하지 마세요."
        }
        val candidateNotice = if (
            reader.approvedForProduction == false
        ) {
            "출시 전 시험 기준입니다. 정식 지원 승인 결과가 아닙니다.\n"
        } else {
            ""
        }
        val supportedLabel = if (candidateNotice.isNotEmpty()) "시험 기준 통과" else "지원"
        val blocking = assessment.blockingFactors.joinToString(", ") { it.labelKo() }
        val measurement = officialEnvironmentMeasurementDetail(assessment)
        val locationQualityEnabled =
            reader.locationFeatureEnabled()
        val cameraQualityEnabled = reader.cameraFeaturesEnabled()
        val skippedQualityNotice = when {
            !locationQualityEnabled && !cameraQualityEnabled ->
                "기기 제한: 위치·카메라 품질 점검은 생략했습니다.\n"
            !locationQualityEnabled -> "기기 제한: 위치 품질 점검은 생략했습니다.\n"
            !cameraQualityEnabled -> "기기 제한: 카메라 품질 점검은 생략했습니다.\n"
            else -> ""
        }
        if (assessment.conditionallyAllowed) {
            return candidateNotice + "$supportNotice\n제한 상태에서 조건부 사용\n" +
                "활성화된 센서 품질은 기준을 충족했습니다. 날씨와 보도 등 현재 조건은 미확인입니다.\n" +
                "사용범위를 이해한 기록은 현장 안전의 증명이 아닙니다. 불확실하거나 범위를 벗어나면 멈추세요.\n" +
                skippedQualityNotice + measurement + "장착 미확인 시 아래에서 실제 장착 방식을 확인하세요."
        }
        val supportedReason = when {
            locationQualityEnabled && cameraQualityEnabled ->
                "밝고 건조하며 짙은 안개가 없는 일반 도심 보도와 위치·카메라 품질을 확인했습니다."
            locationQualityEnabled ->
                "밝고 건조하며 짙은 안개가 없는 일반 도심 보도와 위치 품질을 확인했습니다. " +
                    "제한된 카메라 품질 점검은 생략했습니다."
            cameraQualityEnabled ->
                "밝고 건조하며 짙은 안개가 없는 일반 도심 보도와 카메라 품질을 확인했습니다. " +
                    "제한된 위치 품질 점검은 생략했습니다."
            else ->
                "밝고 건조하며 짙은 안개가 없는 일반 도심 보도를 확인했습니다. " +
                    "제한된 위치·카메라 품질 점검은 생략했습니다."
        }
        return candidateNotice + when (assessment.support) {
            OfficialEnvironmentSupport.SUPPORTED ->
                "$supportNotice\n$supportedLabel\n" +
                    "원인: $supportedReason\n" +
                    measurement +
                    "다음 행동: 장착 방식을 아직 선택하지 않았다면 아래에서 선택하고, " +
                    "완료했다면 보행 시작 확인 버튼을 누르세요."
            OfficialEnvironmentSupport.LIMITED ->
                "$supportNotice\n" +
                    when (reader.phase) {
                        OfficialEnvironmentPreflightPhase.MEASURING -> "측정 중\n"
                        OfficialEnvironmentPreflightPhase.TIMED_OUT -> "점검 시간 초과\n"
                        OfficialEnvironmentPreflightPhase.FAILED -> "점검 실패\n"
                        OfficialEnvironmentPreflightPhase.IDLE -> "확인 필요\n"
                    } +
                    "원인: ${blocking.ifBlank { "공식 환경 조건" }}을 확인하지 못했습니다.\n" +
                    skippedQualityNotice +
                    measurement +
                    "다음 행동: 화면 안내에 따라 기다리거나 자세·장소를 조정하세요. " +
                    "점검이 끝났다면 다시 점검 버튼을 누르세요."
            OfficialEnvironmentSupport.UNSUPPORTED ->
                "$supportNotice\n" +
                    (if (reader.preflightActive) "조정 필요\n" else "사용 불가\n") +
                    "원인: ${blocking.ifBlank { "현재 환경" }}이 공식 지원 조건과 다릅니다.\n" +
                    skippedQualityNotice +
                    measurement +
                    "다음 행동: 안내된 문제를 바로잡는 동안 보행 안내를 시작하지 마세요."
        }
    }

/** Only measured blocking factors produce an automatic corrective instruction. */
    fun officialEnvironmentFailureSpeech(
        assessment: OfficialEnvironmentAssessment,
        factors: Set<OfficialEnvironmentFactor> = assessment.blockingFactors,
    ): String = officialEnvironmentMeasurementDetail(assessment, false).lineSequence()
        .filter { line ->
            (line.startsWith("위치:") && OfficialEnvironmentFactor.GPS_QUALITY in factors &&
                OfficialEnvironmentFactor.GPS_QUALITY in assessment.blockingFactors) ||
                (line.startsWith("카메라:") && OfficialEnvironmentFactor.CAMERA_QUALITY in factors &&
                    OfficialEnvironmentFactor.CAMERA_QUALITY in assessment.blockingFactors)
        }.joinToString(" ").trim()

    fun phoneMountingFailureSpeech(
        assessment: PhoneMountingAssessment,
        environment: OfficialEnvironmentAssessment,
    ): String {
        if (assessment.phase == PhoneMountingAssessmentPhase.ACTIVE &&
            assessment.status == PhoneMountingStatus.UNUSABLE
        ) {
            return "${assessment.accessibleReasonKo} ${assessment.accessibleActionKo} " +
                "카메라 인식·거리·위험 안내를 제한합니다. 위치 품질이 확인되면 경로 안내는 계속됩니다."
        }
        val cameraAction = officialEnvironmentFailureSpeech(
            environment, setOf(OfficialEnvironmentFactor.CAMERA_QUALITY),
        )
        if (cameraAction.isBlank()) return assessment.spokenGuidanceKo
        return if (assessment.phase == PhoneMountingAssessmentPhase.ACTIVE &&
            assessment.status == PhoneMountingStatus.CORRECTION_REQUIRED &&
            assessment.nextState.requiresExplicitCorrection
        ) {
            "$cameraAction 화면에서 장착 교정을 확인하세요."
        } else cameraAction
    }

    fun locationRecoveryActionMessage(): String =
        "현재 위치를 확인할 수 없어 방향 안내를 잠시 멈춥니다. 새 위치를 기다려 주세요."

    private fun oneDecimal(value: Double?): String =
        value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f", it) }
            ?: "확인 중"

    private fun percentOneDecimal(value: Double?): String =
        value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f%%", it * 100.0) }
            ?: "확인 중"

    private fun OfficialEnvironmentFactor.labelKo(): String = when (this) {
        OfficialEnvironmentFactor.GPS_QUALITY -> "위치 품질"
        OfficialEnvironmentFactor.CAMERA_QUALITY -> "카메라 품질"
        OfficialEnvironmentFactor.BRIGHT_TIME -> "밝은 시간"
        OfficialEnvironmentFactor.DRY_WEATHER -> "비·눈 없음"
        OfficialEnvironmentFactor.NO_DENSE_FOG -> "짙은 안개 없음"
        OfficialEnvironmentFactor.ORDINARY_URBAN_SIDEWALK -> "일반 도심 보도"
        OfficialEnvironmentFactor.NO_CONSTRUCTION -> "공사구간 아님"
        OfficialEnvironmentFactor.NO_SEVERE_CROWDING -> "심한 혼잡 없음"
        OfficialEnvironmentFactor.SUPPORT_LIMITS_NOTICE_ACKNOWLEDGED ->
            "지원범위와 제한사항 고지 확인"
    }

    fun updateOfficialEnvironmentUi() {
        if (!view.ready() || !reader.lifecycleAvailable) return
        val snapshot = reader.snapshot()
        val assessment = reader.assessment(snapshot.epoch)
        val message = officialEnvironmentStatusMessage(assessment)
        if (view.currentMessage() != message) view.showMessage(message)
        view.setConfirmEnabled(
                reader.profile != null &&
                reader.onboardingComplete() &&
                reader.foreground &&
                    snapshot.state in setOf(WalkSessionState.READY, WalkSessionState.PAUSED) &&
                    !reader.preflightActive &&
                    !reader.cameraCleanupPending
        )
        val text = when {
                reader.profile == null ->
                    "승인된 환경 프로필 없음"
                reader.preflightActive &&
                    assessment.canStartWalk ->
                    if (assessment.conditionallyAllowed) "센서 품질 통과 · 현재 환경은 미확인"
                    else "환경 측정 통과 · 장착 확인 또는 계속 버튼 사용"
                reader.preflightActive ->
                    "위치·카메라 자동 점검 중"
                reader.cameraCleanupPending ->
                    "이전 카메라 점검 정리 중"
                reader.phase == OfficialEnvironmentPreflightPhase.TIMED_OUT ->
                    "환경과 장착 다시 점검"
                evidence().userConfirmation?.epoch == snapshot.epoch ->
                    "환경 조건 다시 확인"
                reader.approvedForProduction == false ->
                    "출시 후보 환경 실제 점검"
                else -> confirmAction
            }
        view.setConfirmTextAndDescription(text)
    }
}
