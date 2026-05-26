import { AlertTriangle, Camera } from "lucide-react";
import type { RefObject } from "react";
import { labelForTwoModelDetection } from "@/lib/detector-v2";
import { CLASS_LABELS, type DetectionEvent } from "@/types/inference";
import type { TwoModelDetection } from "@/types/inference-v2";

type CameraSurfaceProps = {
  videoRef: RefObject<HTMLVideoElement | null>;
  cameraReady: boolean;
  cameraError: string | null;
  reconnectCameraAndSensors: () => void;
  detection: DetectionEvent | null;
  v2Detections: TwoModelDetection[];
  v2Primary: TwoModelDetection | null;
  riskActive: boolean;
  modeText: string;
  isFakeV2Mode: boolean;
  riskText: string;
  detectorStatusText: string;
};

export function CameraSurface({
  videoRef,
  cameraReady,
  cameraError,
  reconnectCameraAndSensors,
  detection,
  v2Detections,
  v2Primary,
  riskActive,
  modeText,
  isFakeV2Mode,
  riskText,
  detectorStatusText
}: CameraSurfaceProps) {
  return (
    <section className="camera-surface" aria-label="보행 보조 카메라 화면">
      <video ref={videoRef} className="camera-video" muted playsInline aria-label="후면 카메라 미리보기" />
      <header className="app-header">
        <div>
          <strong>WalkSafe Assist</strong>
          <span>목걸이 착용 보행 감지</span>
        </div>
        <div className="header-actions">
          <span className="mode-pill demo">{modeText}</span>
          <a href="/admin">관리자</a>
        </div>
      </header>
      {!cameraReady ? (
        <div className="camera-fallback">
          <Camera aria-hidden="true" size={40} />
          <strong>카메라 준비 중</strong>
          <span>{cameraError ?? "후면 카메라 권한을 확인하고 있습니다."}</span>
          <button className="control-button secondary" type="button" onClick={reconnectCameraAndSensors}>
            <Camera aria-hidden="true" size={20} />
            카메라/센서 다시 연결
          </button>
        </div>
      ) : null}

      {detection ? (
        <div
          className="detection-box v1"
          style={{
            left: `${detection.bbox.x * 100}%`,
            top: `${detection.bbox.y * 100}%`,
            width: `${detection.bbox.width * 100}%`,
            height: `${detection.bbox.height * 100}%`
          }}
          aria-hidden="true"
        >
          <span>{CLASS_LABELS[detection.class_name]}</span>
        </div>
      ) : null}

      {v2Detections.map((item) => (
        <div
          key={`${item.model_key}-${item.model_class_id}-${item.bbox.x}-${item.bbox.y}`}
          className={`detection-box ${item.model_key === "custom_tactile" ? "tactile" : "general"} ${item === v2Primary ? "primary" : "secondary"}`}
          style={{
            left: `${item.bbox.x * 100}%`,
            top: `${item.bbox.y * 100}%`,
            width: `${item.bbox.width * 100}%`,
            height: `${item.bbox.height * 100}%`
          }}
          aria-hidden="true"
        >
          <span>{isFakeV2Mode ? `데모 · ${labelForTwoModelDetection(item)}` : labelForTwoModelDetection(item)}</span>
        </div>
      ))}

      <div className="top-status">
        <div
          className={`risk-pill ${riskActive ? "danger" : "safe"}`}
          role={riskActive ? "alert" : "status"}
          aria-live={riskActive ? "assertive" : "polite"}
        >
          <AlertTriangle aria-hidden="true" size={18} />
          <span>{riskText}</span>
        </div>
        <span>{riskActive ? "전방 확인" : detectorStatusText}</span>
      </div>
    </section>
  );
}
