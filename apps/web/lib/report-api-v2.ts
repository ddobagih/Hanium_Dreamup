/** Builds strict v2 report metadata and uploads the exact detection frame through the same-origin gateway. */
import type { GpsFixV2, NormalizedBBoxV2, TwoModelDetection } from "@/types/inference-v2";
import { notifyGatewaySessionInvalid } from "./gateway-session-client";

const API_BASE_URL = "/api";
const REPORT_UPLOAD_TIMEOUT_MS = 12_000;

export type AutoReportV2Metadata = TwoModelDetection & {
  trigger: ReportV2Trigger;
  auto_reported: boolean;
  coordinate_gate_status: "pass";
  bbox_coordinate_space: "normalized_camera_frame";
};

export type ReportV2Trigger = "auto" | "voice";

export function buildReportV2Metadata(
  detection: TwoModelDetection,
  trigger: ReportV2Trigger
): AutoReportV2Metadata {
  return {
    ...detection,
    trigger,
    auto_reported: trigger === "auto",
    coordinate_gate_status: "pass",
    bbox_coordinate_space: "normalized_camera_frame"
  };
}

export type ReportV2Response = {
  id: string;
  status: string;
  class_id: number;
  class_name: string;
  confidence: number;
  bbox: NormalizedBBoxV2;
  captured_at: string;
  source: string;
  gps: GpsFixV2 | null;
  heading: number | null;
  image_path: string;
  image_content_type: string;
  metadata: Record<string, unknown>;
  location_quality: string;
  review_flags: string[];
  duplicate_report_ids: string[];
  duplicate_count?: number;
  created_at: string;
  updated_at: string;
};

async function parseApiJson<T>(response: Response, fallbackMessage: string): Promise<T> {
  notifyGatewaySessionInvalid(response);
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `${fallbackMessage} (${response.status})`);
  }

  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.includes("application/json")) {
    throw new Error(`${fallbackMessage}: 백엔드 API 응답을 확인해 주세요.`);
  }

  return response.json() as Promise<T>;
}

function assertOnline() {
  if (typeof navigator !== "undefined" && navigator.onLine === false) {
    throw new Error("오프라인 상태입니다. 신고 API 요청을 보내지 않습니다.");
  }
}

export async function submitReportV2(
  detection: TwoModelDetection,
  image: Blob,
  trigger: ReportV2Trigger,
  signal?: AbortSignal
): Promise<ReportV2Response> {
  assertOnline();
  const metadata = buildReportV2Metadata(detection, trigger);

  const body = new FormData();
  body.append("metadata", JSON.stringify(metadata));
  body.append("image", image, `walksafe-auto-v2-${Date.now()}.jpg`);

  const abortController = new AbortController();
  const abortFromCaller = () => abortController.abort();
  if (signal?.aborted) abortController.abort();
  signal?.addEventListener("abort", abortFromCaller, { once: true });
  const timeoutId = globalThis.setTimeout(() => abortController.abort(), REPORT_UPLOAD_TIMEOUT_MS);
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}/reports/v2`, {
      method: "POST",
      body,
      signal: abortController.signal
    });
  } catch (error) {
    const aborted =
      (typeof DOMException !== "undefined" && error instanceof DOMException && error.name === "AbortError") ||
      (error instanceof Error && error.name === "AbortError");
    if (aborted) {
      throw new Error(signal?.aborted ? "신고 업로드가 취소됐습니다." : "신고 업로드 시간이 초과됐습니다.");
    }
    throw error;
  } finally {
    globalThis.clearTimeout(timeoutId);
    signal?.removeEventListener("abort", abortFromCaller);
  }

  return parseApiJson<ReportV2Response>(response, trigger === "auto" ? "자동 신고 업로드 실패" : "음성 신고 업로드 실패");
}

export async function submitAutoReportV2(detection: TwoModelDetection, image: Blob): Promise<ReportV2Response> {
  return submitReportV2(detection, image, "auto");
}
