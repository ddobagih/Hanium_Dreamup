import type { DetectionClassName, DetectionEvent, DetectorSource, GpsFix, NormalizedBBox } from "@/types/inference";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8000";

export type ReportStatus = "new" | "reviewed" | "resolved";

export type ReportResponse = {
  id: string;
  status: ReportStatus;
  class_id: number;
  class_name: DetectionClassName;
  confidence: number;
  bbox: NormalizedBBox;
  captured_at: string;
  source: DetectorSource;
  gps: GpsFix | null;
  heading: number | null;
  image_path: string;
  image_content_type: string;
  metadata: Record<string, unknown>;
  created_at: string;
  updated_at: string;
};

export type ReportListParams = {
  limit?: number;
  status?: ReportStatus | "";
  class_name?: DetectionClassName | "";
  source?: DetectorSource | "";
  created_from?: string;
  created_to?: string;
  lat?: string;
  lng?: string;
  radius_m?: string;
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

export async function submitReport(detection: DetectionEvent, image: Blob): Promise<ReportResponse> {
  const body = new FormData();
  body.append("metadata", JSON.stringify(detection));
  body.append("image", image, `walksafe-${Date.now()}.jpg`);

  const response = await fetch(`${API_BASE_URL}/reports`, {
    method: "POST",
    body
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Report upload failed with ${response.status}`);
  }

  return response.json() as Promise<ReportResponse>;
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

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Report list failed with ${response.status}`);
  }

  return response.json() as Promise<ReportResponse[]>;
}

export async function updateReportStatus(reportId: string, status: ReportStatus): Promise<ReportResponse> {
  const response = await fetch(`${API_BASE_URL}/reports/${reportId}/status`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ status })
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Report status update failed with ${response.status}`);
  }

  return response.json() as Promise<ReportResponse>;
}
