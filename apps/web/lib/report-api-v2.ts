import type { GpsFixV2, NormalizedBBoxV2, TwoModelDetection } from "@/types/inference-v2";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8000";

export type AutoReportV2Metadata = TwoModelDetection & {
  trigger: ReportV2Trigger;
  auto_reported: boolean;
};

export type ReportV2Trigger = "auto" | "voice";

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
  created_at: string;
  updated_at: string;
};

async function parseApiJson<T>(response: Response, fallbackMessage: string): Promise<T> {
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

export async function submitReportV2(
  detection: TwoModelDetection,
  image: Blob,
  trigger: ReportV2Trigger
): Promise<ReportV2Response> {
  const metadata: AutoReportV2Metadata = {
    ...detection,
    trigger,
    auto_reported: trigger === "auto"
  };

  const body = new FormData();
  body.append("metadata", JSON.stringify(metadata));
  body.append("image", image, `walksafe-auto-v2-${Date.now()}.jpg`);

  const response = await fetch(`${API_BASE_URL}/reports/v2`, {
    method: "POST",
    body
  });

  return parseApiJson<ReportV2Response>(response, trigger === "auto" ? "자동 신고 업로드 실패" : "음성 신고 업로드 실패");
}

export async function submitAutoReportV2(detection: TwoModelDetection, image: Blob): Promise<ReportV2Response> {
  return submitReportV2(detection, image, "auto");
}
