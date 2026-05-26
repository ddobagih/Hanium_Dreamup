import { Loader2, MapPin, Mic, Navigation, RefreshCw, Save, Send, UserRound, Volume2, VolumeX } from "lucide-react";
import { labelForTwoModelDetection } from "@/lib/detector-v2";
import type { DetectionEvent, GpsFix } from "@/types/inference";
import type { TwoModelDetection } from "@/types/inference-v2";
import type { EmergencyContact } from "../hooks/useWalkSafeSettings";
import type { NavigationDestinationCandidate } from "../navigation-destination";
import { formatGps, formatPercent } from "../utils";

type VoiceRecordState = "idle" | "recording" | "uploading" | "error";
type ReportState = "idle" | "sending" | "sent" | "error";

type AssistPanelProps = {
  panelId?: string;
  detection: DetectionEvent | null;
  v2Detections: TwoModelDetection[];
  v2Primary: TwoModelDetection | null;
  v2Secondary: TwoModelDetection | null;
  riskActive: boolean;
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
  modeText: string;
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
  v2Primary,
  v2Secondary,
  riskActive,
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
  modeText,
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
  onInstallPwa,
  onApplyPwaUpdate,
  onSaveSettings,
  onClearSettings,
  onToggleSettingsExpanded
}: AssistPanelProps) {
  return (
    <section id={panelId} className="assist-panel" aria-label="보행 보조 상태와 신고">
      <div className="status-grid">
        <div className={`status-item current-risk ${riskActive ? "warning" : "safe"}`} aria-live="polite">
          <span className="status-label">현재 위험</span>
          <strong>{riskActive ? detectionLabel : "위험 요소 없음"}</strong>
          <small>
            {riskActive && detection
              ? `신뢰도 ${formatPercent(detection.confidence)}`
              : riskActive && v2Primary
                ? `primary · ${v2Primary.model_key === "custom_tactile" ? "tactile" : "general"} · ${formatPercent(v2Primary.confidence)}`
                : detectorStatusText}
          </small>
          {riskActive && v2Secondary ? <small>secondary · {labelForTwoModelDetection(v2Secondary)}</small> : null}
        </div>
        {isV2Mode ? (
          <>
            <div className="status-item channel-card tactile">
              <span className="status-label">tactile 채널</span>
              <strong>{v2Detections.filter((item) => item.model_key === "custom_tactile").length}개 감지</strong>
              <small>{v2Detections.filter((item) => item.model_key === "custom_tactile").map(labelForTwoModelDetection).join(" · ") || "대기"}</small>
            </div>
            <div className="status-item channel-card general">
              <span className="status-label">general 채널</span>
              <strong>{v2Detections.filter((item) => item.model_key === "coco_general").length}개 감지</strong>
              <small>{v2Detections.filter((item) => item.model_key === "coco_general").map(labelForTwoModelDetection).join(" · ") || "대기"}</small>
            </div>
          </>
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
        <div className="status-item" aria-live="polite">
          <span className="status-label">신고</span>
          <strong>{reportMessage}</strong>
          {lastDuplicateCount > 0 ? <small>중복 후보 {lastDuplicateCount}건</small> : null}
        </div>
        <div className="status-item" aria-live="polite">
          <span className="status-label">음성 명령</span>
          <strong>{voiceMessage}</strong>
          <small>{voiceResultText}</small>
        </div>
        <div className={`status-item navigation-card ${navigationActive ? "active" : ""}`} aria-live="polite">
          <span className="status-label">길안내</span>
          <strong>{navigationInstructionText}</strong>
          <small>{navigationStatusText}</small>
          <small>{navigationDetailText}</small>
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
      </div>

      {navigationDestinationCandidates.length > 0 ? (
        <section className="navigation-candidate-card" aria-label="목적지 후보 선택">
          <strong>목적지 후보 선택</strong>
          <div className="navigation-candidate-list" role="list" aria-live="polite">
            {navigationDestinationCandidates.map((candidate, index) => (
              <button
                key={candidate.id}
                className="navigation-candidate-button"
                type="button"
                onClick={() => onSelectNavigationCandidate(candidate.id)}
                aria-label={`${index + 1}번 목적지 ${candidate.label} 선택`}
                role="listitem"
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



      <section className="settings-card" aria-label="보호자와 보폭 자동 측정 설정">
        <div className="settings-card-header">
          <span>
            <UserRound aria-hidden="true" size={18} />
            초기 설정
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
        <div className="setup-checklist" aria-label="첫 실행 확인 목록">
          {setupChecklist.map((item) => (
            <span key={item.id} className={item.done ? "done" : undefined}>
              {item.done ? "완료" : "확인"} · {item.label}
            </span>
          ))}
        </div>
        <div className="settings-fields" hidden={!settingsExpanded}>
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
            <small>실기기 측정 완료가 아니라 local dry-run 보정 로그입니다.</small>
          </div>
          <div className="pwa-status-card" aria-live="polite">
            <span>앱 설치/오프라인</span>
            <strong>{isOnline ? "온라인" : "오프라인 셸 사용 중"}</strong>
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
        카메라/센서 재연결
      </button>
      <p className="assist-note">
        <MapPin aria-hidden="true" size={16} />
        {gpsError ? "위치 없음 상태" : `위치 정확도 ${gps?.accuracy_m?.toFixed(1) ?? "대기"}m`}
      </p>
      <p className="assist-note">
        <Navigation aria-hidden="true" size={16} />
        목걸이 착용 · {modeText}
      </p>
    </section>
  );
}
