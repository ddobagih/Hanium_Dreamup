/**
 * Renders the live camera surface and detection overlays without owning detection state.
 * Normalized boxes must be projected through object-fit: cover cropping before display.
 */
import { AlertTriangle, Camera } from "lucide-react";
import { useEffect, useState, type RefObject } from "react";
import { labelForTwoModelDetection } from "@/lib/detector-v2";
import { CLASS_LABELS, type DetectionEvent } from "@/types/inference";
import type { TwoModelDetection } from "@/types/inference-v2";
import type { DetectionAvailability } from "../detection-availability";

type CameraSurfaceProps = {
  videoRef: RefObject<HTMLVideoElement | null>;
  cameraReady: boolean;
  cameraError: string | null;
  cameraPermissionState: PermissionState | "unsupported" | "unknown";
  reconnectCameraAndSensors: () => void;
  permissionRequestMessage: string | null;
  detection: DetectionEvent | null;
  v2Detections: TwoModelDetection[];
  v2Primary: TwoModelDetection | null;
  riskActive: boolean;
  nonMetricAdvisoryActive: boolean;
  nonMetricAdvisoryCapabilityLabel: string | null;
  detectionAvailability: DetectionAvailability;
  modeText: string;
  isFakeV2Mode: boolean;
  riskText: string;
  detectorStatusText: string;
};

export type CameraOverlayBBox = {
  x: number;
  y: number;
  width: number;
  height: number;
};

export type CameraOverlayGeometry = {
  videoWidth: number;
  videoHeight: number;
  elementWidth: number;
  elementHeight: number;
};

function isPositiveFinite(value: number): boolean {
  return Number.isFinite(value) && value > 0;
}

export function projectBBoxForObjectFitCover(
  bbox: CameraOverlayBBox,
  geometry: CameraOverlayGeometry | null | undefined
): CameraOverlayBBox {
  if (
    !geometry ||
    !isPositiveFinite(geometry.videoWidth) ||
    !isPositiveFinite(geometry.videoHeight) ||
    !isPositiveFinite(geometry.elementWidth) ||
    !isPositiveFinite(geometry.elementHeight)
  ) {
    return { ...bbox };
  }

  const scale = Math.max(geometry.elementWidth / geometry.videoWidth, geometry.elementHeight / geometry.videoHeight);
  const renderedWidth = geometry.videoWidth * scale;
  const renderedHeight = geometry.videoHeight * scale;
  const offsetX = (geometry.elementWidth - renderedWidth) / 2;
  const offsetY = (geometry.elementHeight - renderedHeight) / 2;

  return {
    x: (offsetX + bbox.x * renderedWidth) / geometry.elementWidth,
    y: (offsetY + bbox.y * renderedHeight) / geometry.elementHeight,
    width: (bbox.width * renderedWidth) / geometry.elementWidth,
    height: (bbox.height * renderedHeight) / geometry.elementHeight
  };
}

function geometryForVideoElement(video: HTMLVideoElement | null): CameraOverlayGeometry | null {
  if (!video) {
    return null;
  }

  const rect = video.getBoundingClientRect();

  return {
    videoWidth: video.videoWidth,
    videoHeight: video.videoHeight,
    elementWidth: rect.width,
    elementHeight: rect.height
  };
}

function isSameGeometry(a: CameraOverlayGeometry | null, b: CameraOverlayGeometry | null): boolean {
  if (!a || !b) {
    return a === b;
  }

  return (
    a.videoWidth === b.videoWidth &&
    a.videoHeight === b.videoHeight &&
    a.elementWidth === b.elementWidth &&
    a.elementHeight === b.elementHeight
  );
}

function styleForOverlayBBox(bbox: CameraOverlayBBox, geometry: CameraOverlayGeometry | null) {
  const projected = projectBBoxForObjectFitCover(bbox, geometry);

  return {
    left: `${projected.x * 100}%`,
    top: `${projected.y * 100}%`,
    width: `${projected.width * 100}%`,
    height: `${projected.height * 100}%`
  };
}

function depthLabelForDetection(item: TwoModelDetection): string {
  if (typeof item.distance_m === "number" && item.distance_source === "sensor_depth") {
    return ` · ${item.distance_m.toFixed(1)}m`;
  }

  if (item.approach_state === "approaching") {
    return " · 접근";
  }

  if (item.distance_source === "model_estimate") {
    return " · 추세";
  }

  return "";
}

export function CameraSurface({
  videoRef,
  cameraReady,
  cameraError,
  cameraPermissionState,
  reconnectCameraAndSensors,
  permissionRequestMessage,
  detection,
  v2Detections,
  v2Primary,
  riskActive,
  nonMetricAdvisoryActive,
  nonMetricAdvisoryCapabilityLabel,
  detectionAvailability,
  modeText,
  isFakeV2Mode,
  riskText,
  detectorStatusText
}: CameraSurfaceProps) {
  const [overlayGeometry, setOverlayGeometry] = useState<CameraOverlayGeometry | null>(null);

  useEffect(() => {
    const video = videoRef.current;

    if (!video) {
      setOverlayGeometry(null);
      return;
    }

    const updateGeometry = () => {
      const nextGeometry = geometryForVideoElement(video);
      setOverlayGeometry((currentGeometry) => (isSameGeometry(currentGeometry, nextGeometry) ? currentGeometry : nextGeometry));
    };

    updateGeometry();

    const resizeObserver = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(updateGeometry);

    resizeObserver?.observe(video);
    video.addEventListener("loadedmetadata", updateGeometry);
    window.addEventListener("resize", updateGeometry);

    return () => {
      resizeObserver?.disconnect();
      video.removeEventListener("loadedmetadata", updateGeometry);
      window.removeEventListener("resize", updateGeometry);
    };
  }, [cameraReady, videoRef]);

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
          <span>{cameraError ?? "아래 버튼을 눌러 카메라 권한을 요청하세요. 모바일 Chrome에서는 버튼 클릭 후에만 권한 팝업이 안정적으로 뜹니다."}</span>
          <button className="control-button secondary" type="button" onClick={reconnectCameraAndSensors}>
            <Camera aria-hidden="true" size={20} />
            카메라 권한 요청
          </button>
          <span className="permission-request-note">
            카메라 권한 상태: {cameraPermissionState}. prompt면 버튼을 누를 때 팝업이 떠야 하고, denied면 Chrome 사이트 설정에서 직접 허용해야 합니다.
          </span>
          {permissionRequestMessage ? <span className="permission-request-note">{permissionRequestMessage}</span> : null}
        </div>
      ) : null}

      {detection ? (
        <div
          className="detection-box v1"
          style={styleForOverlayBBox(detection.bbox, overlayGeometry)}
          aria-hidden="true"
        >
          <span>{CLASS_LABELS[detection.class_name]}</span>
        </div>
      ) : null}

      {v2Detections.map((item) => (
        <div
          key={`${item.model_key}-${item.model_class_id}-${item.bbox.x}-${item.bbox.y}`}
          className={`detection-box ${item.model_key === "custom_tactile" || item.class_name.includes("tactile_block") || item.class_name === "tactile_damage_area" ? "tactile" : "general"} ${item === v2Primary ? "primary" : "secondary"}`}
          style={styleForOverlayBBox(item.bbox, overlayGeometry)}
          aria-hidden="true"
        >
          <span>
            {isFakeV2Mode ? `데모 · ${labelForTwoModelDetection(item)}` : labelForTwoModelDetection(item)}
            {depthLabelForDetection(item)}
          </span>
        </div>
      ))}

      <div className="top-status">
        <div
          className={`risk-pill ${riskActive ? "danger" : !nonMetricAdvisoryActive && detectionAvailability === "available" ? "safe" : ""}`}
        >
          <AlertTriangle aria-hidden="true" size={18} />
          <span>{riskText}</span>
        </div>
        <span>{riskActive ? "전방 확인" : nonMetricAdvisoryActive ? nonMetricAdvisoryCapabilityLabel : detectorStatusText}</span>
      </div>
    </section>
  );
}
