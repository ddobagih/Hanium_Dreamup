/**
 * Presents assist, navigation, report and settings state produced by the page-level hooks.
 * Domain transitions stay in those hooks; this component only dispatches the supplied actions.
 */
import { Loader2, Mic, RefreshCw, Save, Send, UserRound, Volume2, VolumeX } from "lucide-react";
import { useEffect, useState } from "react";
import type { DetectionEvent, GpsFix } from "@/types/inference";
import type { TwoModelDetection } from "@/types/inference-v2";
import type { EmergencyContact } from "../hooks/useWalkSafeSettings";
import type { NavigationDestinationCandidate } from "../navigation-destination";
import type { SpeechOutputStatus } from "../feedback";
import { detectionAvailabilityLabel, type DetectionAvailability } from "../detection-availability";
import { formatGps, formatPercent } from "../utils";
import { AssistiveAnnouncement } from "./AssistiveAnnouncement";

type VoiceRecordState = "idle" | "recording" | "uploading" | "error";
type ReportState = "idle" | "sending" | "sent" | "error";

type AssistPanelProps = {
  panelId?: string;
  detection: DetectionEvent | null;
  v2Detections: TwoModelDetection[];
  activeV2RiskDetection: TwoModelDetection | null;
  riskActive: boolean;
  detectionAvailability: DetectionAvailability;
  isV2Mode: boolean;
  detectionLabel: string;
  detectorStatusText: string;
  gps: GpsFix | null;
  gpsError: string | null;
  directionLabel: string;
  heading: number | null;
  headingMessage: string;
  reportMessage: string;
  lastDuplicateCount: number;
  voiceMessage: string;
  voiceResultText: string;
  navigationActive: boolean;
  navigationStatusText: string;
  navigationInstructionText: string;
  navigationDetailText: string;
  nonMetricAdvisoryCapabilityLabel?: string | null;
  nonMetricAdvisoryActive?: boolean;
  nonMetricAdvisoryMessage?: string | null;
  navigationDestinationCandidates: NavigationDestinationCandidate[];
  navigationHiddenDestinationCandidateCount: number;
  navigationCanShowMoreDestinations: boolean;
  navigationSearchActive: boolean;
  onSelectNavigationCandidate: (candidateId: string) => void;
  onShowMoreNavigationCandidates: () => void;
  onCancelNavigationSearch: () => void;
  onRetryNavigationSearch: () => void;
  onStopNavigation: () => void;
  speechEnabled: boolean;
  speechOutputStatus: SpeechOutputStatus;
  detectionSafetyAlertMessage: string | null;
  detectionSafetyFallbackRequired: boolean;
  handleSpeechToggle: () => void;
  voiceState: VoiceRecordState;
  voiceSupported: boolean;
  voiceButtonLabel: string;
  voiceButtonHelp: string;
  handleVoiceCommandButton: () => void;
  handleReport: () => void;
  canReport: boolean;
  reportDisabledReason: string;
  reportState: ReportState;
  reportButtonLabel: string;
  reportHelpText: string;
  reconnectCameraAndSensors: () => void;
  permissionRequestMessage: string | null;
  guardianSummary: string;
  settingsMessage: string;
  settingsExpanded: boolean;
  setupChecklist: { id: string; label: string; done: boolean }[];
  settingsForm: {
    emergencyContacts: EmergencyContact[];
  };
  stepLengthSummary: string;
  stepLengthDetail: string;
  motionPermissionLabel: string;
  motionSampleCount: number;
  stepLengthConfidence: number;
  storedStepCalibrationActive: boolean;
  depthStatusText: string;
  depthDetailText: string;
  depthSensorStatus: string;
  depthFrameCount: number;
  disableDepthSensorControls?: boolean;
  isOnline: boolean;
  pwaInstallMessage: string;
  pwaUpdateMessage: string;
  swVersion: string | null;
  canInstallPwa: boolean;
  canApplyPwaUpdate: boolean;
  onEmergencyContactNameChange: (contactId: string, value: string) => void;
  onEmergencyContactPhoneChange: (contactId: string, value: string) => void;
  onAddEmergencyContact: () => void;
  onRemoveEmergencyContact: (contactId: string) => void;
  onRequestMotionPermission: () => void;
  onResetStepLengthCalibration: () => void;
  onRequestDepthSensor: () => void;
  onStopDepthSensor: () => void;
  onInstallPwa: () => void;
  onApplyPwaUpdate: () => void;
  onSaveSettings: () => void;
  onClearSettings: () => void;
  onToggleSettingsExpanded: () => void;
};

export function AssistPanel({
  panelId,
  detection,
  v2Detections,
  activeV2RiskDetection,
  riskActive,
  detectionAvailability,
  isV2Mode,
  detectionLabel,
  detectorStatusText,
  gps,
  gpsError,
  directionLabel,
  heading,
  headingMessage,
  reportMessage,
  lastDuplicateCount,
  voiceMessage,
  voiceResultText,
  navigationActive,
  navigationStatusText,
  navigationInstructionText,
  navigationDetailText,
  nonMetricAdvisoryCapabilityLabel = null,
  nonMetricAdvisoryActive = false,
  nonMetricAdvisoryMessage = null,
  navigationDestinationCandidates,
  navigationHiddenDestinationCandidateCount,
  navigationCanShowMoreDestinations,
  navigationSearchActive,
  onSelectNavigationCandidate,
  onShowMoreNavigationCandidates,
  onCancelNavigationSearch,
  onRetryNavigationSearch,
  onStopNavigation,
  speechEnabled,
  speechOutputStatus,
  detectionSafetyAlertMessage,
  detectionSafetyFallbackRequired,
  handleSpeechToggle,
  voiceState,
  voiceSupported,
  voiceButtonLabel,
  voiceButtonHelp,
  handleVoiceCommandButton,
  handleReport,
  canReport,
  reportDisabledReason,
  reportState,
  reportButtonLabel,
  reportHelpText,
  reconnectCameraAndSensors,
  permissionRequestMessage,
  guardianSummary,
  settingsMessage,
  settingsExpanded,
  setupChecklist,
  settingsForm,
  stepLengthSummary,
  stepLengthDetail,
  motionPermissionLabel,
  motionSampleCount,
  stepLengthConfidence,
  storedStepCalibrationActive,
  depthStatusText,
  depthDetailText,
  depthSensorStatus,
  depthFrameCount,
  disableDepthSensorControls = false,
  isOnline,
  pwaInstallMessage,
  pwaUpdateMessage,
  swVersion,
  canInstallPwa,
  canApplyPwaUpdate,
  onEmergencyContactNameChange,
  onEmergencyContactPhoneChange,
  onAddEmergencyContact,
  onRemoveEmergencyContact,
  onRequestMotionPermission,
  onResetStepLengthCalibration,
  onRequestDepthSensor,
  onStopDepthSensor,
  onInstallPwa,
  onApplyPwaUpdate,
  onSaveSettings,
  onClearSettings,
  onToggleSettingsExpanded
}: AssistPanelProps) {
  const hasReportStatus = isV2Mode || reportState !== "idle" || lastDuplicateCount > 0;
  const hasVoiceStatus = voiceState !== "idle" || Boolean(voiceResultText);
  const hasNavigationStatus = navigationActive || navigationSearchActive || navigationDestinationCandidates.length > 0;
  const depthSensorRunning = depthSensorStatus === "running" || depthSensorStatus === "active";
  const ttsFallbackActive = speechEnabled && (speechOutputStatus !== "available" || detectionSafetyFallbackRequired);
  const assistiveRiskActive = riskActive || detectionSafetyAlertMessage !== null;
  const voiceInteractionMessage =
    voiceState === "idle" && !voiceResultText
      ? null
      : [voiceMessage, voiceResultText].filter(Boolean).join(". ");
  const [liveVoiceInteraction, setLiveVoiceInteraction] = useState<string | null>(null);

  useEffect(() => {
    const activationTimer = window.setTimeout(
      () => setLiveVoiceInteraction(voiceInteractionMessage),
      0
    );
    const expirationTimer = voiceInteractionMessage
      ? window.setTimeout(() => setLiveVoiceInteraction(null), 5_000)
      : null;
    return () => {
      window.clearTimeout(activationTimer);
      if (expirationTimer !== null) window.clearTimeout(expirationTimer);
    };
  }, [voiceInteractionMessage, voiceState]);

  return (
    <section id={panelId} className="assist-panel" aria-label="보행 보조 상태와 신고">
      <AssistiveAnnouncement
        enabled={ttsFallbackActive}
        riskActive={assistiveRiskActive}
        riskMessage={riskActive
          ? `현재 위험. 잠시 멈추고 주변을 확인하세요. ${detectionLabel}.`
          : detectionSafetyAlertMessage ?? ""}
        interactionMessage={liveVoiceInteraction}
        navigationMessage={hasNavigationStatus ? `${navigationInstructionText}. ${navigationStatusText}` : null}
      />
      {ttsFallbackActive ? (
        <p className="permission-request-note" role="status" aria-live="polite">
          브라우저 음성 출력이 확인되지 않아 화면 읽기 안내를 유지합니다.
        </p>
      ) : null}
      <div className="status-grid">
        <div
          className={`status-item current-risk ${riskActive ? "warning" : !nonMetricAdvisoryActive && detectionAvailability === "available" ? "safe" : ""}`}
          role={!speechEnabled ? (assistiveRiskActive ? "alert" : "status") : undefined}
          aria-live={!speechEnabled ? (assistiveRiskActive ? "assertive" : "polite") : undefined}
          aria-atomic={!speechEnabled ? "true" : undefined}
        >
          <span className="status-label">현재 위험</span>
          <strong>
            {riskActive
              ? detectionLabel
              : nonMetricAdvisoryActive
                ? "카메라 보조 경고 확인"
                : detectionAvailabilityLabel(detectionAvailability)}
          </strong>
          <small>
            {riskActive && detection
              ? `신뢰도 ${formatPercent(detection.confidence)}`
              : riskActive && activeV2RiskDetection
                ? `위험 판정 객체 · ${activeV2RiskDetection.model_key === "custom_tactile" || activeV2RiskDetection.class_name.includes("tactile_block") || activeV2RiskDetection.class_name === "tactile_damage_area" ? "tactile" : "general"} · ${formatPercent(activeV2RiskDetection.confidence)}`
                : isV2Mode
                  ? `감지 ${v2Detections.length}개 · ${detectorStatusText}`
                  : detectorStatusText}
          </small>
        </div>
        {nonMetricAdvisoryActive && nonMetricAdvisoryMessage ? (
          <div className="status-item" role="status" aria-live="polite" aria-atomic="true">
            <span className="status-label">카메라 보조</span>
            <strong>{nonMetricAdvisoryMessage}</strong>
            <small>{nonMetricAdvisoryCapabilityLabel}</small>
          </div>
        ) : null}
        <div className="status-item">
          <span className="status-label">위치</span>
          <strong>{gpsError ?? formatGps(gps)}</strong>
          <small>{gps?.accuracy_m === null || gps?.accuracy_m === undefined ? "정확도 대기" : `정확도 ${gps.accuracy_m.toFixed(1)}m`}</small>
        </div>
        <div className="status-item">
          <span className="status-label">방향</span>
          <strong>{directionLabel}</strong>
          <small>{heading === null ? headingMessage : `${heading}도`}</small>
        </div>
        <div className="status-item">
          <span className="status-label">깊이</span>
          <strong>{depthStatusText}</strong>
          <small>{depthDetailText}</small>
          {depthSensorRunning ? <small>센서 프레임 {depthFrameCount}개 수신</small> : null}
        </div>
        {hasReportStatus ? (
          <div
            className="status-item"
            role={reportState === "error" ? "alert" : "status"}
            aria-live={reportState === "error" ? "assertive" : "polite"}
            aria-atomic="true"
          >
            <span className="status-label">신고</span>
            <strong>{reportMessage}</strong>
            {lastDuplicateCount > 0 ? <small>중복 후보 {lastDuplicateCount}건</small> : null}
          </div>
        ) : null}
        {hasVoiceStatus ? (
          <div className="status-item" role={!speechEnabled ? "status" : undefined} aria-live={!speechEnabled ? "polite" : undefined}>
            <span className="status-label">음성 명령</span>
            <strong>{voiceMessage}</strong>
            <small>{voiceResultText}</small>
          </div>
        ) : null}
        {hasNavigationStatus ? (
          <div
            className={`status-item navigation-card ${navigationActive ? "active" : ""}`}
            role={!speechEnabled ? "status" : undefined}
            aria-live={!speechEnabled ? "polite" : undefined}
          >
          <span className="status-label">길안내</span>
          <strong>{navigationInstructionText}</strong>
          <small>{navigationStatusText}</small>
          <small>{navigationDetailText}</small>
          {nonMetricAdvisoryCapabilityLabel ? (
            <small>{nonMetricAdvisoryCapabilityLabel}</small>
          ) : null}
          {navigationActive ? (
            <button className="inline-action" type="button" onClick={onStopNavigation}>
              길안내 중지
            </button>
          ) : null}
          {navigationSearchActive ? (
            <button className="inline-action" type="button" onClick={onCancelNavigationSearch}>
              검색 취소
            </button>
          ) : null}
          </div>
        ) : null}
      </div>

      {navigationDestinationCandidates.length > 0 ? (
        <section className="navigation-candidate-card" aria-label="목적지 후보 선택">
          <strong>목적지 후보 선택</strong>
          <div className="navigation-candidate-list" aria-live="polite">
            {navigationDestinationCandidates.map((candidate, index) => (
              <button
                key={candidate.id}
                className="navigation-candidate-button"
                type="button"
                onClick={() => onSelectNavigationCandidate(candidate.id)}
                aria-label={`${index + 1}번 목적지 ${candidate.label} 선택`}
              >
                <span>{index + 1}</span>
                <b>{candidate.name}</b>
                {candidate.addressLabel || candidate.distanceLabel ? (
                  <small>{[candidate.addressLabel, candidate.distanceLabel].filter(Boolean).join(" · ")}</small>
                ) : null}
              </button>
            ))}
          </div>
          <div className="navigation-candidate-actions">
            {navigationCanShowMoreDestinations ? (
              <button className="inline-action" type="button" onClick={onShowMoreNavigationCandidates}>
                더 보기 {navigationHiddenDestinationCandidateCount}개
              </button>
            ) : null}
            <button className="inline-action" type="button" onClick={onRetryNavigationSearch}>
              다시 검색
            </button>
            <button className="inline-action" type="button" onClick={onCancelNavigationSearch}>
              취소
            </button>
          </div>
          <p>여러 장소가 검색되면 자동 길안내를 시작하지 않습니다.</p>
        </section>
      ) : null}

      <button
        className="voice-button"
        type="button"
        aria-pressed={speechEnabled}
        aria-label={speechEnabled ? "음성 안내 켜짐. 탭하면 꺼집니다." : "음성 안내 꺼짐. 탭하면 켜집니다."}
        onClick={handleSpeechToggle}
      >
        <span>
          {speechEnabled ? <Volume2 aria-hidden="true" size={22} /> : <VolumeX aria-hidden="true" size={22} />}
          {speechEnabled ? "음성 켜짐" : "음성 꺼짐"}
        </span>
        <small>위험 탐지 시 음성으로 경고합니다</small>
      </button>

      <button
        className="voice-button"
        type="button"
        aria-pressed={voiceState === "recording"}
        aria-label={`${voiceButtonLabel}. ${voiceButtonHelp}`}
        onClick={handleVoiceCommandButton}
        disabled={!voiceSupported || voiceState === "uploading"}
      >
        <span>
          {voiceState === "uploading" ? <Loader2 className="spin" aria-hidden="true" size={22} /> : <Mic aria-hidden="true" size={22} />}
          {voiceButtonLabel}
        </span>
        <small>{voiceButtonHelp}</small>
      </button>



      <section className={`settings-card ${settingsExpanded ? "expanded" : "collapsed"}`} aria-label="보호자와 보폭 자동 측정 설정">
        <div className="settings-card-header">
          <span>
            <UserRound aria-hidden="true" size={18} />
            설정
          </span>
          <small>{guardianSummary}</small>
          <button
            className="inline-action"
            type="button"
            aria-expanded={settingsExpanded}
            onClick={onToggleSettingsExpanded}
          >
            {settingsExpanded ? "접기" : "펼치기"}
          </button>
        </div>
        {settingsExpanded ? (
          <>
            <div className="setup-checklist" aria-label="첫 실행 확인 목록">
              {setupChecklist.map((item) => (
                <span key={item.id} className={item.done ? "done" : undefined}>
                  {item.done ? "완료" : "확인"} · {item.label}
                </span>
              ))}
            </div>
            <div className="settings-fields">
              <div className="emergency-contact-list" aria-label="긴급 연락처 목록">
                {settingsForm.emergencyContacts.map((contact, index) => (
                  <fieldset key={contact.id} className="emergency-contact-row">
                    <legend>{index === 0 ? "주 긴급 연락처" : `추가 연락처 ${index + 1}`}</legend>
                    <label>
                      이름
                      <input
                        type="text"
                        value={contact.name}
                        onChange={(event) => onEmergencyContactNameChange(contact.id, event.target.value)}
                        placeholder="예: 홍길동"
                        autoComplete="off"
                      />
                    </label>
                    <label>
                      전화번호
                      <input
                        type="tel"
                        value={contact.phone}
                        onChange={(event) => onEmergencyContactPhoneChange(contact.id, event.target.value)}
                        placeholder="예: 010-0000-0000"
                        autoComplete="tel"
                      />
                    </label>
                    {settingsForm.emergencyContacts.length > 1 ? (
                      <button className="inline-action" type="button" onClick={() => onRemoveEmergencyContact(contact.id)}>
                        이 연락처 삭제
                      </button>
                    ) : null}
                  </fieldset>
                ))}
                {settingsForm.emergencyContacts.length < 3 ? (
                  <button className="inline-action" type="button" onClick={onAddEmergencyContact}>
                    연락처 추가
                  </button>
                ) : null}
              </div>
              <div className="step-length-readout" aria-live="polite">
                <span>보폭 자동 측정</span>
                <strong>{stepLengthSummary}</strong>
                <small>{stepLengthDetail}</small>
                <small>
                  움직임 권한 {motionPermissionLabel} · 샘플 {motionSampleCount}개 · 신뢰도 {Math.round(stepLengthConfidence * 100)}%
                  {storedStepCalibrationActive ? " · 저장값 사용 중" : ""}
                </small>
                <div className="settings-actions compact">
                  <button className="inline-action" type="button" onClick={onRequestMotionPermission}>
                    움직임 권한/측정 시작
                  </button>
                  <button className="inline-action" type="button" onClick={onResetStepLengthCalibration}>
                    보폭 재보정
                  </button>
                </div>
                <small>정지 중 GPS 흔들림은 보폭 보정에서 제외합니다.</small>
              </div>
              <div className="pwa-status-card" aria-live="polite">
                <span>앱 설치/오프라인</span>
                <strong>{isOnline ? "온라인 · 서버 기능 사용 가능" : "오프라인 화면만 사용 가능"}</strong>
                {!isOnline ? <small>탐지·신고·길안내·음성 명령은 연결 복구 전까지 중지됩니다.</small> : null}
                <small>{pwaInstallMessage}</small>
                <small>{pwaUpdateMessage}</small>
                {swVersion ? <small>서비스워커 버전 {swVersion}</small> : null}
                <div className="settings-actions compact">
                  <button className="inline-action" type="button" onClick={onInstallPwa} disabled={!canInstallPwa}>
                    앱 설치
                  </button>
                  <button className="inline-action" type="button" onClick={onApplyPwaUpdate} disabled={!canApplyPwaUpdate}>
                    오프라인 셸 업데이트 적용
                  </button>
                </div>
              </div>
            </div>
            <div className="settings-actions">
              <button className="control-button secondary" type="button" onClick={onSaveSettings}>
                <Save aria-hidden="true" size={18} />
                설정 저장
              </button>
              <button className="control-button secondary" type="button" onClick={onClearSettings}>
                연락처 지우기
              </button>
            </div>
            <p>{settingsMessage}</p>
            <p className="assist-note">보호자 연락처는 report/STT payload로 전송하지 않으며 실제 전화/SMS도 발신하지 않습니다.</p>
          </>
        ) : (
          <p>초기 설정, 보폭 보정, 앱 설치는 필요할 때만 펼쳐서 확인합니다.</p>
        )}
      </section>

      <button
        className="report-button"
        type="button"
        onClick={handleReport}
        disabled={!canReport}
        aria-describedby="report-button-help"
        aria-label={reportDisabledReason || "현재 탐지된 위험을 신고합니다."}
      >
        {reportState === "sending" ? (
          <Loader2 className="spin" aria-hidden="true" size={24} />
        ) : (
          <Send aria-hidden="true" size={24} />
        )}
        <span className="button-stack">
          <span>{reportButtonLabel}</span>
          <small id="report-button-help">{reportHelpText}</small>
        </span>
      </button>

      <button className="control-button secondary sensor-action" type="button" onClick={reconnectCameraAndSensors}>
        <RefreshCw aria-hidden="true" size={20} />
        카메라/GPS 권한 요청
      </button>
      {disableDepthSensorControls && !depthSensorRunning ? (
        <p className="permission-request-note" role="status">
          WebXR 깊이는 브라우저 AR 실험 기능이라 현재 실기기 카메라/서버 인식 테스트에서는 비활성화했습니다.
        </p>
      ) : (
        <button
          className="control-button secondary sensor-action"
          type="button"
          onClick={depthSensorRunning ? onStopDepthSensor : onRequestDepthSensor}
        >
          <RefreshCw aria-hidden="true" size={20} />
          {depthSensorRunning ? "WebXR 깊이 중지" : "WebXR 깊이 시작"}
        </button>
      )}
      {permissionRequestMessage ? (
        <p className="permission-request-note" role="status" aria-live="assertive">
          {permissionRequestMessage}
        </p>
      ) : null}
    </section>
  );
}
