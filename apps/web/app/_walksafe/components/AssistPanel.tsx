import { Loader2, MapPin, Mic, Navigation, RefreshCw, Send, Volume2, VolumeX } from "lucide-react";
import { labelForTwoModelDetection } from "@/lib/detector-v2";
import type { DetectionEvent, GpsFix } from "@/types/inference";
import type { TwoModelDetection } from "@/types/inference-v2";
import { formatGps, formatPercent } from "../utils";

type VoiceRecordState = "idle" | "recording" | "uploading" | "error";
type ReportState = "idle" | "sending" | "sent" | "error";

type AssistPanelProps = {
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
};

export function AssistPanel({
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
  modeText
}: AssistPanelProps) {
  return (
    <section className="assist-panel" aria-label="보행 보조 상태와 신고">
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
      </div>

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
