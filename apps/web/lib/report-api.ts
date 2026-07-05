import type { DetectionEvent, DetectorSource, GpsFix, NormalizedBBox } from "@/types/inference";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8000";

export type ReportStatus = "new" | "reviewed" | "resolved";
export type ReportModelKey = "custom_tactile" | "coco_general" | "unified_walksafe";
export type ReportTrigger = "auto" | "voice";
export type ReportExportFormat = "csv" | "json" | "geojson";
export type ReportGeoJsonAggregate = "grid";

export type ReportStatusHistoryEntry = {
  from: ReportStatus;
  to: ReportStatus;
  at: string;
  note?: string | null;
  resolution_reason?: string | null;
};

export type ReportMetadata = Record<string, unknown> & {
  distance_m?: number | null;
  status_history?: ReportStatusHistoryEntry[];
  review_note?: string;
  resolution_reason?: string;
  trace_id?: string;
  payload_sha256?: string;
  image_sha256?: string;
  data_origin?: string;
  runtime_mode?: string;
  performance_excluded?: boolean;
  performance_exclusion_reason?: string;
};

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
  metadata: ReportMetadata;
  location_quality: "missing" | "low" | "medium" | "high";
  review_flags: string[];
  duplicate_report_ids: string[];
  created_at: string;
  updated_at: string;
};

export type ReportDemoFilterMode = "all" | "only_fake" | "exclude_fake";

export type ReportLocationBounds = {
  minLatitude: number;
  maxLatitude: number;
  minLongitude: number;
  maxLongitude: number;
};

export type AdminReportGridCluster = {
  key: string;
  count: number;
  fake: number;
  nonFake: number;
  centerLatitude: number;
  centerLongitude: number;
  bounds: ReportLocationBounds;
  statusCounts: Record<ReportStatus, number>;
  sourceCounts: Record<DetectorSource, number>;
};

export type AdminReportSummary = {
  fake: {
    total: number;
    fake: number;
    nonFake: number;
    sourceFake: number;
    sourceServer: number;
    sourceOnnx: number;
    sourceAndroid: number;
  };
  location: {
    located: number;
    missing: number;
    bounds: ReportLocationBounds | null;
    gridSizeDegrees: number;
    topClusters: AdminReportGridCluster[];
  };
  status: Record<ReportStatus, number>;
  source: Record<DetectorSource, number>;
  note?: string;
};

type AdminReportSummaryOptions = {
  gridSizeDegrees?: number;
  topClusterLimit?: number;
};

type ReportDemoFields = Pick<ReportResponse, "source" | "review_flags" | "metadata">;

export type ReportListParams = {
  limit?: number;
  status?: ReportStatus | "";
  class_name?: string | "";
  source?: DetectorSource | "";
  demo_filter?: ReportDemoFilterMode | "";
  model_key?: ReportModelKey | "";
  trigger?: ReportTrigger | "";
  auto_reported?: boolean | "";
  created_from?: string;
  created_to?: string;
  lat?: string;
  lng?: string;
  radius_m?: string;
};

export type ReportExportOptions = {
  redacted?: boolean;
  bom?: boolean;
  manifest?: boolean;
  aggregate?: ReportGeoJsonAggregate;
};

export type ReportStatusPatch = {
  status: ReportStatus;
  note?: string;
  resolution_reason?: string;
  expected_updated_at?: string;
};

export type DuplicateCheckResponse = {
  duplicate_report_ids: string[];
  reports: ReportResponse[];
};

export type DetectV2HealthResponse = Record<string, unknown>;

const DEFAULT_REPORT_GRID_SIZE_DEGREES = 0.001;
const DEFAULT_REPORT_TOP_CLUSTER_LIMIT = 5;

function hasDemoMetadata(metadata: ReportResponse["metadata"] | null | undefined) {
  if (!metadata || typeof metadata !== "object" || Array.isArray(metadata)) {
    return false;
  }

  const sourceModel = typeof metadata.source_model === "string" ? metadata.source_model.toLowerCase() : "";
  const detectorMode = typeof metadata.detector_mode === "string" ? metadata.detector_mode.toLowerCase() : "";
  const reviewFlags = Array.isArray(metadata.review_flags) ? metadata.review_flags : [];

  return (
    "fake_source" in metadata ||
    metadata.data_origin === "demo" ||
    reviewFlags.includes("fake_source") ||
    metadata.demo === true ||
    metadata.is_demo === true ||
    metadata.fake === true ||
    metadata.is_fake === true ||
    sourceModel.includes("fake") ||
    sourceModel.includes("demo") ||
    detectorMode.includes("fake") ||
    detectorMode.includes("demo")
  );
}

export function isFakeOrDemoReport(report: ReportDemoFields): boolean {
  return (
    report.source === "fake" ||
    report.review_flags.includes("fake_source") ||
    hasDemoMetadata(report.metadata)
  );
}

export function filterReportsByDemoMode<T extends ReportDemoFields>(
  reports: readonly T[],
  mode: ReportDemoFilterMode
): T[] {
  if (mode === "only_fake") {
    return reports.filter(isFakeOrDemoReport);
  }

  if (mode === "exclude_fake") {
    return reports.filter((report) => !isFakeOrDemoReport(report));
  }

  return [...reports];
}

function isUsableGps(gps: GpsFix | null | undefined): gps is GpsFix {
  return (
    !!gps &&
    Number.isFinite(gps.latitude) &&
    Number.isFinite(gps.longitude) &&
    gps.latitude >= -90 &&
    gps.latitude <= 90 &&
    gps.longitude >= -180 &&
    gps.longitude <= 180
  );
}

export function summarizeAdminReports(
  reports: readonly ReportResponse[],
  options: AdminReportSummaryOptions = {}
): AdminReportSummary {
  const gridSizeDegrees =
    options.gridSizeDegrees !== undefined && options.gridSizeDegrees > 0
      ? options.gridSizeDegrees
      : DEFAULT_REPORT_GRID_SIZE_DEGREES;
  const topClusterLimit =
    options.topClusterLimit !== undefined && options.topClusterLimit >= 0
      ? Math.floor(options.topClusterLimit)
      : DEFAULT_REPORT_TOP_CLUSTER_LIMIT;

  const sourceCounts: Record<DetectorSource, number> = { fake: 0, onnx: 0, server: 0, android: 0 };
  const statusCounts: Record<ReportStatus, number> = { new: 0, reviewed: 0, resolved: 0 };
  const clusters = new Map<
    string,
    {
      count: number;
      fake: number;
      nonFake: number;
      latitudeSum: number;
      longitudeSum: number;
      bounds: ReportLocationBounds;
      statusCounts: Record<ReportStatus, number>;
      sourceCounts: Record<DetectorSource, number>;
    }
  >();
  let fake = 0;
  let located = 0;
  let bounds: ReportLocationBounds | null = null;

  for (const report of reports) {
    sourceCounts[report.source] += 1;
    statusCounts[report.status] += 1;

    const isFake = isFakeOrDemoReport(report);
    if (isFake) {
      fake += 1;
    }

    if (!isUsableGps(report.gps)) {
      continue;
    }

    const latitude = report.gps.latitude;
    const longitude = report.gps.longitude;
    located += 1;
    bounds = bounds
      ? {
          minLatitude: Math.min(bounds.minLatitude, latitude),
          maxLatitude: Math.max(bounds.maxLatitude, latitude),
          minLongitude: Math.min(bounds.minLongitude, longitude),
          maxLongitude: Math.max(bounds.maxLongitude, longitude)
        }
      : {
          minLatitude: latitude,
          maxLatitude: latitude,
          minLongitude: longitude,
          maxLongitude: longitude
        };

    const latitudeCell = Math.floor(latitude / gridSizeDegrees);
    const longitudeCell = Math.floor(longitude / gridSizeDegrees);
    const key = `${latitudeCell}:${longitudeCell}`;
    const clusterBounds = {
      minLatitude: latitudeCell * gridSizeDegrees,
      maxLatitude: (latitudeCell + 1) * gridSizeDegrees,
      minLongitude: longitudeCell * gridSizeDegrees,
      maxLongitude: (longitudeCell + 1) * gridSizeDegrees
    };
    const cluster =
      clusters.get(key) ??
      {
        count: 0,
        fake: 0,
        nonFake: 0,
        latitudeSum: 0,
        longitudeSum: 0,
        bounds: clusterBounds,
        statusCounts: { new: 0, reviewed: 0, resolved: 0 },
        sourceCounts: { fake: 0, onnx: 0, server: 0, android: 0 }
      };

    cluster.count += 1;
    cluster.latitudeSum += latitude;
    cluster.longitudeSum += longitude;
    cluster.statusCounts[report.status] += 1;
    cluster.sourceCounts[report.source] += 1;
    if (isFake) {
      cluster.fake += 1;
    } else {
      cluster.nonFake += 1;
    }
    clusters.set(key, cluster);
  }

  return {
    fake: {
      total: reports.length,
      fake,
      nonFake: reports.length - fake,
      sourceFake: sourceCounts.fake,
      sourceServer: sourceCounts.server,
      sourceOnnx: sourceCounts.onnx,
      sourceAndroid: sourceCounts.android
    },
    location: {
      located,
      missing: reports.length - located,
      bounds,
      gridSizeDegrees,
      topClusters: Array.from(clusters.entries())
        .map(([key, cluster]) => ({
          key,
          count: cluster.count,
          fake: cluster.fake,
          nonFake: cluster.nonFake,
          centerLatitude: cluster.latitudeSum / cluster.count,
          centerLongitude: cluster.longitudeSum / cluster.count,
          bounds: cluster.bounds,
          statusCounts: cluster.statusCounts,
          sourceCounts: cluster.sourceCounts
        }))
        .sort((first, second) => second.count - first.count || second.fake - first.fake || first.key.localeCompare(second.key))
        .slice(0, topClusterLimit)
    },
    status: statusCounts,
    source: sourceCounts,
    note: "Summary is computed from the current loaded report list."
  };
}

type BackendSummaryBounds = {
  min_latitude: number;
  max_latitude: number;
  min_longitude: number;
  max_longitude: number;
};

type BackendSummaryCluster = {
  key: string;
  count: number;
  fake: number;
  non_fake: number;
  center_latitude: number;
  center_longitude: number;
  bounds: BackendSummaryBounds;
  status_counts: Record<ReportStatus, number>;
  source_counts: Record<DetectorSource, number>;
};

type BackendReportSummaryResponse = {
  total: number;
  fake: number;
  non_fake: number;
  located: number;
  missing_location: number;
  bounds: BackendSummaryBounds | null;
  status_counts: Record<ReportStatus, number>;
  source_counts: Record<DetectorSource, number>;
  grid_size_degrees: number;
  top_clusters: BackendSummaryCluster[];
  note?: string;
};

function normalizeBounds(bounds: BackendSummaryBounds | null): ReportLocationBounds | null {
  if (!bounds) {
    return null;
  }
  return {
    minLatitude: bounds.min_latitude,
    maxLatitude: bounds.max_latitude,
    minLongitude: bounds.min_longitude,
    maxLongitude: bounds.max_longitude
  };
}

function normalizeBackendSummary(summary: BackendReportSummaryResponse): AdminReportSummary {
  const sourceCounts = {
    fake: summary.source_counts.fake ?? 0,
    onnx: summary.source_counts.onnx ?? 0,
    server: summary.source_counts.server ?? 0,
    android: summary.source_counts.android ?? 0
  };
  return {
    fake: {
      total: summary.total,
      fake: summary.fake,
      nonFake: summary.non_fake,
      sourceFake: sourceCounts.fake,
      sourceServer: sourceCounts.server,
      sourceOnnx: sourceCounts.onnx,
      sourceAndroid: sourceCounts.android
    },
    location: {
      located: summary.located,
      missing: summary.missing_location,
      bounds: normalizeBounds(summary.bounds),
      gridSizeDegrees: summary.grid_size_degrees,
      topClusters: summary.top_clusters.map((cluster) => ({
        key: cluster.key,
        count: cluster.count,
        fake: cluster.fake,
        nonFake: cluster.non_fake,
        centerLatitude: cluster.center_latitude,
        centerLongitude: cluster.center_longitude,
        bounds: normalizeBounds(cluster.bounds) ?? {
          minLatitude: cluster.center_latitude,
          maxLatitude: cluster.center_latitude,
          minLongitude: cluster.center_longitude,
          maxLongitude: cluster.center_longitude
        },
        statusCounts: {
          new: cluster.status_counts.new ?? 0,
          reviewed: cluster.status_counts.reviewed ?? 0,
          resolved: cluster.status_counts.resolved ?? 0
        },
        sourceCounts: {
          fake: cluster.source_counts.fake ?? 0,
          onnx: cluster.source_counts.onnx ?? 0,
          server: cluster.source_counts.server ?? 0,
          android: cluster.source_counts.android ?? 0
        }
      }))
    },
    status: {
      new: summary.status_counts.new ?? 0,
      reviewed: summary.status_counts.reviewed ?? 0,
      resolved: summary.status_counts.resolved ?? 0
    },
    source: sourceCounts,
    note: summary.note
  };
}

export function reportImageUrl(report: ReportResponse): string {
  if (report.image_path.startsWith("http")) {
    return report.image_path;
  }
  return `${API_BASE_URL}${report.image_path}`;
}

function appendParam(params: URLSearchParams, key: string, value: string | number | boolean | undefined) {
  if (value !== undefined && value !== "") {
    params.set(key, String(value));
  }
}

function assertOnline(message: string) {
  if (typeof navigator !== "undefined" && navigator.onLine === false) {
    throw new Error(message);
  }
}

function buildReportSearchParams(filters: ReportListParams, includeLimit: boolean) {
  const params = new URLSearchParams();
  if (includeLimit) {
    appendParam(params, "limit", filters.limit ?? 50);
  }
  appendParam(params, "status", filters.status);
  appendParam(params, "class_name", filters.class_name);
  appendParam(params, "source", filters.source);
  appendParam(params, "demo_filter", filters.demo_filter);
  appendParam(params, "model_key", filters.model_key);
  appendParam(params, "trigger", filters.trigger);
  appendParam(params, "auto_reported", filters.auto_reported);
  appendParam(params, "created_from", filters.created_from);
  appendParam(params, "created_to", filters.created_to);
  appendParam(params, "lat", filters.lat);
  appendParam(params, "lng", filters.lng);
  appendParam(params, "radius_m", filters.radius_m);

  return params;
}

function appendExportFormatParam(params: URLSearchParams, format: ReportExportFormat) {
  params.set("format", format);
}

export function reportExportUrl(
  filters: ReportListParams = {},
  format: ReportExportFormat = "csv",
  options: ReportExportOptions = {}
): string {
  const params = buildReportSearchParams(filters, false);
  appendExportFormatParam(params, format);
  appendParam(params, "redacted", options.redacted);
  appendParam(params, "bom", options.bom);
  appendParam(params, "manifest", options.manifest);
  appendParam(params, "aggregate", options.aggregate);
  const query = params.toString();
  return `${API_BASE_URL}/reports/export${query ? `?${query}` : ""}`;
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
  assertOnline("오프라인 상태입니다. 신고 API 요청을 보내지 않습니다.");
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
  assertOnline("오프라인 상태입니다. 중복 신고 확인 요청을 보내지 않습니다.");
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
  assertOnline("오프라인 상태입니다. 신고 목록 API 요청을 보내지 않습니다.");
  const params = buildReportSearchParams(filters, true);

  const response = await fetch(`${API_BASE_URL}/reports?${params.toString()}`, {
    cache: "no-store"
  });

  return parseApiJson<ReportResponse[]>(response, "신고 목록 불러오기 실패");
}

export async function getReportSummary(filters: ReportListParams = {}): Promise<AdminReportSummary> {
  assertOnline("오프라인 상태입니다. 신고 요약 API 요청을 보내지 않습니다.");
  const params = buildReportSearchParams(filters, false);
  params.set("grid_size_degrees", String(DEFAULT_REPORT_GRID_SIZE_DEGREES));
  params.set("top_limit", String(DEFAULT_REPORT_TOP_CLUSTER_LIMIT));

  const response = await fetch(`${API_BASE_URL}/reports/summary?${params.toString()}`, {
    cache: "no-store"
  });

  return normalizeBackendSummary(await parseApiJson<BackendReportSummaryResponse>(response, "신고 요약 불러오기 실패"));
}

export async function getDetectV2Health(): Promise<DetectV2HealthResponse> {
  assertOnline("오프라인 상태입니다. 모델 상태 API 요청을 보내지 않습니다.");
  const response = await fetch(`${API_BASE_URL}/detect/v2/health`, {
    cache: "no-store"
  });

  return parseApiJson<DetectV2HealthResponse>(response, "모델 상태 확인 실패");
}

export async function updateReportStatus(reportId: string, patch: ReportStatus | ReportStatusPatch): Promise<ReportResponse> {
  assertOnline("오프라인 상태입니다. 신고 상태 변경 요청을 보내지 않습니다.");
  const body = typeof patch === "string" ? { status: patch } : patch;
  const response = await fetch(`${API_BASE_URL}/reports/${reportId}/status`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(body)
  });

  return parseApiJson<ReportResponse>(response, "신고 상태 변경 실패");
}
