import type { DetectionEvent, DetectorSource, GpsFix, NormalizedBBox } from "@/types/inference";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8000";

export type ReportStatus = "new" | "reviewed" | "resolved";

export type ReportResponse = {
  id: string;
  status: ReportStatus;
  class_id: number;
  class_name: string;
  confidence: number;
  bbox: NormalizedBBox;
  captured_at: string;
  source: DetectorSource;
  gps: GpsFix | null;
  heading: number | null;
  image_path: string;
  image_content_type: string;
  metadata: Record<string, unknown>;
  location_quality: "missing" | "low" | "medium" | "high";
  review_flags: string[];
  duplicate_report_ids: string[];
  created_at: string;
  updated_at: string;
};

export type ReportListParams = {
  limit?: number;
  status?: ReportStatus | "";
  class_name?: string | "";
  source?: DetectorSource | "";
  created_from?: string;
  created_to?: string;
  lat?: string;
  lng?: string;
  radius_m?: string;
};

export type DuplicateCheckResponse = {
  duplicate_report_ids: string[];
  reports: ReportResponse[];
};

export function reportImageUrl(report: ReportResponse): string {
  if (report.image_path.startsWith("http")) {
    return report.image_path;
  }
  return `${API_BASE_URL}${report.image_path}`;
}

function appendParam(params: URLSearchParams, key: string, value: string | number | undefined) {
  if (value !== undefined && value !== "") {
    params.set(key, String(value));
  }
}

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

export async function submitReport(detection: DetectionEvent, image: Blob): Promise<ReportResponse> {
  const body = new FormData();
  body.append("metadata", JSON.stringify(detection));
  body.append("image", image, `walksafe-${Date.now()}.jpg`);

  const response = await fetch(`${API_BASE_URL}/reports`, {
    method: "POST",
    body
  });

  return parseApiJson<ReportResponse>(response, "신고 업로드 실패");
}

export async function checkDuplicateReports(detection: DetectionEvent): Promise<DuplicateCheckResponse | null> {
  if (!detection.gps) {
    return null;
  }

  const params = new URLSearchParams({
    class_name: detection.class_name,
    captured_at: detection.captured_at,
    lat: String(detection.gps.latitude),
    lng: String(detection.gps.longitude),
    radius_m: "25",
    minutes: "10"
  });

  const response = await fetch(`${API_BASE_URL}/reports/duplicate-check?${params.toString()}`, {
    cache: "no-store"
  });

  return parseApiJson<DuplicateCheckResponse>(response, "중복 신고 확인 실패");
}

export async function listReports(filters: ReportListParams = {}): Promise<ReportResponse[]> {
  const params = new URLSearchParams();
  appendParam(params, "limit", filters.limit ?? 50);
  appendParam(params, "status", filters.status);
  appendParam(params, "class_name", filters.class_name);
  appendParam(params, "source", filters.source);
  appendParam(params, "created_from", filters.created_from);
  appendParam(params, "created_to", filters.created_to);
  appendParam(params, "lat", filters.lat);
  appendParam(params, "lng", filters.lng);
  appendParam(params, "radius_m", filters.radius_m);

  const response = await fetch(`${API_BASE_URL}/reports?${params.toString()}`, {
    cache: "no-store"
  });

  return parseApiJson<ReportResponse[]>(response, "신고 목록 불러오기 실패");
}

export async function updateReportStatus(reportId: string, status: ReportStatus): Promise<ReportResponse> {
  const response = await fetch(`${API_BASE_URL}/reports/${reportId}/status`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ status })
  });

  return parseApiJson<ReportResponse>(response, "신고 상태 변경 실패");
}
