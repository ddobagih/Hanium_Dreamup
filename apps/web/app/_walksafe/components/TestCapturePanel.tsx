"use client";

/**
 * Collects opt-in development snapshots for detector-policy review. Captures
 * require an explicit UI consent state and the guarded test-log endpoint; they
 * are not user reports, training ingestion, or field-performance evidence.
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { labelForTwoModelDetection } from "@/lib/detector-v2";
import type { DetectionEvent, GpsFix } from "@/types/inference";
import type { DetectV2RequestAudit, TwoModelDetection } from "@/types/inference-v2";
import { DETECTOR_MODE } from "../config";

type TestVerdict = "snapshot" | "correct" | "wrong";

const TEST_CAPTURE_MAX_IMAGE_BYTES = 4 * 1024 * 1024;
const TEST_CAPTURE_MAX_METADATA_BYTES = 128 * 1024;

type TestCapturePanelProps = {
  cameraReady: boolean;
  captureFrame: () => Promise<Blob>;
  detection: DetectionEvent | null;
  v2Detections: TwoModelDetection[];
  v2Primary: TwoModelDetection | null;
  detectorStatusText: string;
  riskText: string;
  depthStatusText: string;
  depthDetailText: string;
  gps: GpsFix | null;
  heading: number | null;
  stepLengthDetail: string;
  detectionV2Audit: DetectV2RequestAudit | null;
  onRevokeConsent: () => void | Promise<void>;
};

const EXPECTED_TARGETS = [
  "person",
  "bicycle",
  "car",
  "motorcycle",
  "bus",
  "truck",
  "traffic light",
  "normal_tactile_block",
  "damaged_tactile_block",
  "crosswalk",
  "curb_step",
  "uneven_sidewalk",
  "e_scooter_obstruction",
  "no_object",
  "other"
];

function detectionSummary(detections: TwoModelDetection[]): string {
  if (detections.length === 0) {
    return "감지 없음";
  }
  return detections
    .slice(0, 3)
    .map((item) => `${labelForTwoModelDetection(item)} ${Math.round(item.confidence * 100)}%`)
    .join(" · ");
}

type RectSnapshot = {
  x: number;
  y: number;
  width: number;
  height: number;
  top: number;
  right: number;
  bottom: number;
  left: number;
};

function snapshotRect(rect: DOMRect | null | undefined): RectSnapshot | null {
  if (!rect) {
    return null;
  }

  return {
    x: rect.x,
    y: rect.y,
    width: rect.width,
    height: rect.height,
    top: rect.top,
    right: rect.right,
    bottom: rect.bottom,
    left: rect.left
  };
}

function currentCameraMetadata() {
  const video = document.querySelector<HTMLVideoElement>("video.camera-video");
  const surface = document.querySelector<HTMLElement>(".camera-surface");

  return {
    video: video
      ? {
          video_width: video.videoWidth,
          video_height: video.videoHeight,
          ready_state: video.readyState,
          paused: video.paused,
          ended: video.ended,
          muted: video.muted,
          element_rect: snapshotRect(video.getBoundingClientRect())
        }
      : null,
    camera_surface_rect: snapshotRect(surface?.getBoundingClientRect())
  };
}

function auditSummary(audit: DetectV2RequestAudit | null): string {
  if (!audit) {
    return "탐지 감사 대기";
  }

  const rawCount = audit.raw_detection_count ?? "?";
  const errorText = audit.error_code ? ` · 오류 ${audit.error_code}` : "";
  return `raw ${rawCount} / parsed ${audit.parsed_detection_count} / drop ${audit.parser_drop_count} · ${audit.latency_ms}ms${errorText}`;
}

export function TestCapturePanel({
  cameraReady,
  captureFrame,
  detection,
  v2Detections,
  v2Primary,
  detectorStatusText,
  riskText,
  depthStatusText,
  depthDetailText,
  gps,
  heading,
  stepLengthDetail,
  detectionV2Audit,
  onRevokeConsent
}: TestCapturePanelProps) {
  const [expectedTarget, setExpectedTarget] = useState(EXPECTED_TARGETS[0]);
  const [expectedDistanceM, setExpectedDistanceM] = useState("");
  const [note, setNote] = useState("");
  const [message, setMessage] = useState("테스트 장면에서 맞음/틀림을 누르면 현재 프레임과 탐지 상태를 저장합니다.");
  const [submitting, setSubmitting] = useState(false);
  const captureAbortRef = useRef<AbortController | null>(null);

  const summary = useMemo(() => detectionSummary(v2Detections), [v2Detections]);
  const auditText = useMemo(() => auditSummary(detectionV2Audit), [detectionV2Audit]);

  useEffect(() => () => captureAbortRef.current?.abort(), []);

  const submit = async (verdict: TestVerdict) => {
    if (submitting) {
      return;
    }
    setSubmitting(true);
    setMessage("테스트 스냅샷 저장 중...");
    captureAbortRef.current?.abort();
    const abortController = new AbortController();
    captureAbortRef.current = abortController;
    try {
      const image = cameraReady ? await captureFrame() : null;
      const metadata = {
        schema_version: "walksafe.phone-test.v1",
        captured_at: new Date().toISOString(),
        verdict,
        expected_target: expectedTarget,
        expected_distance_m: expectedDistanceM ? Number(expectedDistanceM) : null,
        note,
        detector_status: detectorStatusText,
        risk_text: riskText,
        depth_status: depthStatusText,
        depth_detail: depthDetailText,
        step_length_detail: stepLengthDetail,
        detector_mode: DETECTOR_MODE,
        api_base_url: "/api",
        detect_v2_audit: detectionV2Audit,
        camera_metadata: currentCameraMetadata(),
        v1_detection: detection,
        v2_primary: v2Primary,
        v2_detections: v2Detections,
        gps,
        heading,
        viewport: {
          width: window.innerWidth,
          height: window.innerHeight,
          device_pixel_ratio: window.devicePixelRatio
        }
      };
      const metadataText = JSON.stringify(metadata);
      if (new Blob([metadataText]).size > TEST_CAPTURE_MAX_METADATA_BYTES) {
        throw new Error(`메타데이터 용량 초과 (${Math.ceil(metadataText.length / 1024)}KB)`);
      }
      const body = new FormData();
      body.append("metadata", metadataText);
      if (image) {
        if (image.size > TEST_CAPTURE_MAX_IMAGE_BYTES) {
          throw new Error(`이미지 크기 초과: ${(image.size / (1024 * 1024)).toFixed(2)}MB`);
        }
        if (image.type !== "image/jpeg") {
          throw new Error(`이미지 형식 오류: ${image.type || "unknown"}`);
        }
        body.append("image", image, `walksafe-phone-test-${Date.now()}.jpg`);
      }
      const response = await fetch("/api/walksafe-test-log", {
        method: "POST",
        body,
        credentials: "same-origin",
        cache: "no-store",
        signal: abortController.signal,
        headers: { "x-test-capture-consent": "true" }
      });
      if (!response.ok) {
        throw new Error(`저장 실패 ${response.status}`);
      }
      const result = (await response.json()) as { id?: string };
      setMessage(`저장됨 · ${result.id ?? "id 없음"}`);
    } catch (error) {
      if (!abortController.signal.aborted) {
        setMessage(error instanceof Error ? error.message : "테스트 스냅샷 저장 실패");
      }
    } finally {
      if (captureAbortRef.current === abortController) {
        captureAbortRef.current = null;
        setSubmitting(false);
      }
    }
  };

  const revokeConsent = () => {
    captureAbortRef.current?.abort();
    captureAbortRef.current = null;
    void onRevokeConsent();
  };

  return (
    <section className="test-capture-panel" aria-label="실기기 테스트 기록">
      <div>
        <span className="status-label">테스트 판정</span>
        <strong>{summary}</strong>
        <small>박스가 실제 물체에 맞는지, 라벨이 맞는지, 다른 곳으로 돌렸을 때 박스가 사라지는지 보고 판정하세요.</small>
        <small>탐지 감사: {auditText}</small>
        <small>이미지·정확 GPS·방향·탐지 결과·기기/브라우저 정보는 서버에 최대 7일 저장되며 동의 철회 시 계정 저장본 삭제를 요청합니다.</small>
      </div>
      <label>
        기대 대상
        <select value={expectedTarget} onChange={(event) => setExpectedTarget(event.target.value)}>
          {EXPECTED_TARGETS.map((target) => (
            <option key={target} value={target}>
              {target}
            </option>
          ))}
        </select>
      </label>
      <label>
        기준 거리 m, 선택
        <input
          inputMode="decimal"
          placeholder="예: 1.5"
          value={expectedDistanceM}
          onChange={(event) => setExpectedDistanceM(event.target.value)}
        />
      </label>
      <label>
        메모
        <textarea
          placeholder="예: 걸으면서 3초 비췄는데 사람 박스가 늦게 뜸"
          value={note}
          onChange={(event) => setNote(event.target.value)}
        />
      </label>
      <div className="test-capture-actions">
        <button type="button" onClick={() => void submit("correct")} disabled={submitting || !cameraReady}>
          맞음 저장
        </button>
        <button type="button" onClick={() => void submit("wrong")} disabled={submitting || !cameraReady}>
          틀림 저장
        </button>
        <button type="button" onClick={() => void submit("snapshot")} disabled={submitting || !cameraReady}>
          스냅샷만
        </button>
        <button type="button" onClick={revokeConsent}>
          수집 동의 철회·패널 닫기
        </button>
      </div>
      <small>{message}</small>
    </section>
  );
}
