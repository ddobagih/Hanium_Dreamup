"use client";

/**
 * Runs the human-review console for persisted reports. Filters are shared with
 * the server summary/export query; export always means the whole current
 * filter, not selected-row merging or automatic submission to an agency.
 */

import { CheckCircle2, Clock3, Download, Filter, Image as ImageIcon, MapPin, RefreshCw, ShieldCheck, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  agencyExportFilters,
  filterReportsByDemoMode,
  getDetectV2Health,
  getReportSummary,
  isFakeOrDemoReport,
  listReports,
  reportExportUrl,
  reportImageUrl,
  summarizeAdminReports,
  type AdminReportGridCluster,
  type ReportListParams,
  type ReportExportFormat,
  type ReportDemoFilterMode,
  type ReportLocationBounds,
  type ReportModelKey,
  type ReportResponse,
  type ReportStatus,
  type ReportStatusHistoryEntry,
  type ReportTrigger,
  updateReportStatus
} from "@/lib/report-api";
import { CLASS_LABELS, DETECTION_CLASS_NAMES, type DetectorSource } from "@/types/inference";
import { koreanCalendarDayEnd, koreanCalendarDayStart } from "@/lib/admin-report-filters";
import { GatewaySessionGate } from "../_walksafe/components/GatewaySessionGate";
import { useGatewaySession } from "../_walksafe/hooks/useGatewaySession";
import { buildAdminHeatmapCells } from "../_walksafe/admin-heatmap";

const STATUS_LABELS: Record<ReportStatus, string> = {
  new: "신규",
  reviewed: "현장 조치 대상",
  resolved: "처리 완료"
};

const STATUS_WORKFLOW_GUIDANCE: Record<ReportStatus, { description: string; nextAction: string }> = {
  new: {
    description: "아직 운영자가 확인하지 않은 신고입니다.",
    nextAction: "사진, 정확 위치, 중복 후보를 확인한 뒤 현장 조치 대상으로 옮깁니다."
  },
  reviewed: {
    description: "운영자가 검토를 마치고 현장 확인 또는 기관 조치가 필요하다고 분류한 신고입니다.",
    nextAction: "데모/저신뢰 신고를 제외하고 조치 완료 여부가 확인되면 처리 완료로 표시합니다."
  },
  resolved: {
    description: "검수 또는 조치가 끝난 신고입니다.",
    nextAction: "추가 이슈가 확인된 경우에만 상태를 되돌려 재검토합니다."
  }
};

const SOURCE_LABELS: Record<DetectorSource, string> = {
  fake: "Fake",
  onnx: "ONNX",
  server: "Server",
  android: "Android"
};

const MODEL_KEY_LABELS: Record<ReportModelKey, string> = {
  custom_tactile: "보도블록 손상",
  coco_general: "일반 객체",
  unified_walksafe: "통합 객체"
};
const REPORT_MODEL_FILTER_OPTIONS: ReportModelKey[] = ["unified_walksafe", "custom_tactile", "coco_general"];

const TRIGGER_LABELS: Record<ReportTrigger, string> = {
  auto: "자동",
  voice: "음성 요청"
};

const LOCATION_QUALITY_LABELS: Record<ReportResponse["location_quality"], string> = {
  missing: "위치 없음",
  low: "위치 정확도 낮음",
  medium: "위치 정확도 보통",
  high: "위치 정확도 높음"
};

const REVIEW_FLAG_LABELS: Record<string, string> = {
  fake_source: "데모 탐지",
  low_confidence: "신뢰도 낮음",
  missing_location: "위치 없음",
  low_location_accuracy: "위치 정확도 낮음",
  missing_heading: "방향 정보 없음",
  coordinate_gate_pending: "좌표 정합 대기",
  duplicate_candidate: "중복 후보"
};

const STATUS_SORT_ORDER: Record<ReportStatus, number> = {
  new: 0,
  reviewed: 1,
  resolved: 2
};

const ADMIN_CLASS_LABELS: Record<string, string> = {
  ...CLASS_LABELS,
  tactile_damage_area: "점자블록 파손 영역",
  damaged_tactile_block: "점자블록 파손",
  crosswalk: "횡단보도",
  curb_step: "보도 턱",
  uneven_sidewalk: "고르지 않은 보도",
  e_scooter_obstruction: "방치 킥보드"
};

const V2_METADATA_KEYS = [
  "schema_version",
  "model_key",
  "source_model",
  "trigger",
  "auto_reported",
  "reporter_user_id",
  "ingested_by_actor_id",
  "actor_provenance",
  "duplicate_report_ids",
  "distance_m",
  "threshold_used",
  "coordinate_gate_status",
  "trace_id",
  "payload_sha256",
  "image_sha256",
  "data_origin",
  "runtime_mode",
  "performance_excluded",
  "performance_exclusion_reason"
] as const;
const EXPORT_FORMAT_LABELS: Record<ReportExportFormat, string> = {
  csv: "CSV",
  json: "JSON",
  geojson: "GeoJSON"
};

const DEMO_FILTER_LABELS: Record<ReportDemoFilterMode, string> = {
  all: "전체",
  only_fake: "Fake/Demo만",
  exclude_fake: "Fake/Demo 제외"
};

type V2MetadataEntry = { key: string; value: string };

function reportClassLabel(className: string) {
  return ADMIN_CLASS_LABELS[className] ?? className;
}

function formatMetadataValue(value: unknown) {
  if (typeof value === "boolean") {
    return value ? "true" : "false";
  }
  if (typeof value === "string" || typeof value === "number") {
    return String(value);
  }
  if (value === null) {
    return "null";
  }
  return JSON.stringify(value);
}

function shortHash(value: string) {
  return /^[a-f0-9]{64}$/i.test(value) ? value.slice(0, 12) : value;
}

function getMetadataDistanceM(metadata: ReportResponse["metadata"] | null | undefined) {
  if (!metadata || typeof metadata !== "object" || Array.isArray(metadata)) {
    return null;
  }

  const distanceM = metadata.distance_m;
  return typeof distanceM === "number" && Number.isFinite(distanceM) && distanceM >= 0 ? distanceM : null;
}

function formatDistanceM(distanceM: number) {
  if (Number.isInteger(distanceM)) {
    return `${distanceM}m`;
  }

  return `${distanceM.toFixed(distanceM < 10 ? 1 : 0)}m`;
}

function getV2MetadataEntries(metadata: ReportResponse["metadata"] | null | undefined): V2MetadataEntry[] {
  if (!metadata || typeof metadata !== "object" || Array.isArray(metadata)) {
    return [];
  }

  return V2_METADATA_KEYS.flatMap<V2MetadataEntry>((key) => {
    if (!(key in metadata)) {
      return [];
    }

    if (key === "distance_m") {
      const distanceM = getMetadataDistanceM(metadata);
      return distanceM === null ? [] : [{ key, value: formatDistanceM(distanceM) }];
    }

    const rawValue = metadata[key];
    const value = typeof rawValue === "string" ? shortHash(rawValue) : formatMetadataValue(rawValue);
    return value === undefined ? [] : [{ key, value }];
  });
}

function getV2ListSummary(metadata: ReportResponse["metadata"] | null | undefined) {
  if (!metadata || typeof metadata !== "object" || Array.isArray(metadata)) {
    return null;
  }

  const modelKey = typeof metadata.model_key === "string" ? metadata.model_key : null;
  const trigger = typeof metadata.trigger === "string" ? metadata.trigger : null;
  const autoReported = typeof metadata.auto_reported === "boolean" ? metadata.auto_reported : null;
  const distanceM = getMetadataDistanceM(metadata);
  const parts = [
    modelKey,
    trigger ? `trigger=${trigger}` : null,
    autoReported !== null ? `auto=${autoReported ? "true" : "false"}` : null,
    distanceM !== null ? `거리 ${formatDistanceM(distanceM)}` : null
  ].filter(Boolean);

  return parts.length > 0 ? parts.join(" · ") : null;
}

function getStatusHistory(metadata: ReportResponse["metadata"] | null | undefined): ReportStatusHistoryEntry[] {
  if (!metadata || !Array.isArray(metadata.status_history)) {
    return [];
  }
  return metadata.status_history.filter(
    (entry): entry is ReportStatusHistoryEntry =>
      !!entry &&
      typeof entry === "object" &&
      typeof entry.from === "string" &&
      typeof entry.to === "string" &&
      typeof entry.at === "string"
  );
}

type SortMode = "latest" | "confidence" | "status";

type FilterState = {
  status: ReportStatus | "";
  class_name: string;
  source: DetectorSource | "";
  model_key: ReportModelKey | "";
  trigger: ReportTrigger | "";
  auto_reported: "" | "true" | "false";
  performance_excluded: "" | "true" | "false";
  created_from: string;
  created_to: string;
  lat: string;
  lng: string;
  radius_m: string;
};

const EMPTY_FILTERS: FilterState = {
  status: "",
  class_name: "",
  source: "",
  model_key: "",
  trigger: "",
  auto_reported: "",
  performance_excluded: "",
  created_from: "",
  created_to: "",
  lat: "",
  lng: "",
  radius_m: ""
};

const ADMIN_CLASS_FILTER_OPTIONS = Array.from(new Set([
  ...DETECTION_CLASS_NAMES,
  "tactile_damage_area",
  "damaged_tactile_block",
  "crosswalk",
  "curb_step",
  "uneven_sidewalk",
  "e_scooter_obstruction"
]));
const ADMIN_PAGE_SIZE = 50;

function formatDate(value: string) {
  return new Intl.DateTimeFormat("ko-KR", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  }).format(new Date(value));
}

function formatGps(report: ReportResponse) {
  if (!report.gps) {
    return "위치 없음";
  }
  return `${report.gps.latitude.toFixed(5)}, ${report.gps.longitude.toFixed(5)}`;
}

function formatBounds(bounds: ReportLocationBounds | null) {
  if (!bounds) {
    return "좌표 범위는 위치가 있는 신고가 있을 때 표시됩니다.";
  }

  return `위도 ${bounds.minLatitude.toFixed(5)}~${bounds.maxLatitude.toFixed(5)}, 경도 ${bounds.minLongitude.toFixed(
    5
  )}~${bounds.maxLongitude.toFixed(5)}`;
}

function formatClusterBounds(cluster: AdminReportGridCluster) {
  return `격자 ${cluster.bounds.minLatitude.toFixed(4)}~${cluster.bounds.maxLatitude.toFixed(4)}, ${cluster.bounds.minLongitude.toFixed(
    4
  )}~${cluster.bounds.maxLongitude.toFixed(4)}`;
}

export default function AdminReportsPage() {
  const gatewaySession = useGatewaySession("admin");
  const adminRequestGenerationRef = useRef(0);
  const adminMutationGenerationRef = useRef(0);
  const [filters, setFilters] = useState<FilterState>(EMPTY_FILTERS);
  const [reports, setReports] = useState<ReportResponse[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [sortMode, setSortMode] = useState<SortMode>("latest");
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [updatingId, setUpdatingId] = useState<string | null>(null);
  const [modelHealthLabel, setModelHealthLabel] = useState<string | null>(null);
  const [demoFilterMode, setDemoFilterMode] = useState<ReportDemoFilterMode>("all");
  const [serverSummary, setServerSummary] = useState<ReturnType<typeof summarizeAdminReports> | null>(null);
  const [reviewNote, setReviewNote] = useState("");
  const [resolutionReason, setResolutionReason] = useState("");
  const [agencyExportAuditId, setAgencyExportAuditId] = useState("");
  const [pageOffset, setPageOffset] = useState(0);
  const [hasNextPage, setHasNextPage] = useState(false);

  useEffect(() => {
    adminRequestGenerationRef.current += 1;
    adminMutationGenerationRef.current += 1;
    if (gatewaySession.state === "authenticated") {
      return;
    }

    const timer = window.setTimeout(() => {
      setFilters(EMPTY_FILTERS);
      setReports([]);
      setSelectedId(null);
      setIsLoading(false);
      setError(null);
      setUpdatingId(null);
      setModelHealthLabel(null);
      setServerSummary(null);
      setReviewNote("");
      setResolutionReason("");
      setPageOffset(0);
      setHasNextPage(false);
    }, 0);
    return () => window.clearTimeout(timer);
  }, [gatewaySession.state]);

  const visibleReports = useMemo(
    () => filterReportsByDemoMode(reports, demoFilterMode),
    [reports, demoFilterMode]
  );

  const sortedReports = useMemo(() => {
    const nextReports = [...visibleReports];
    if (sortMode === "confidence") {
      return nextReports.sort((first, second) => second.confidence - first.confidence);
    }
    if (sortMode === "status") {
      return nextReports.sort((first, second) => STATUS_SORT_ORDER[first.status] - STATUS_SORT_ORDER[second.status]);
    }
    return nextReports.sort((first, second) => Date.parse(second.created_at) - Date.parse(first.created_at));
  }, [visibleReports, sortMode]);

  const selectedReport = useMemo(
    () => visibleReports.find((report) => report.id === selectedId) ?? null,
    [visibleReports, selectedId]
  );
  const selectedV2MetadataEntries = useMemo(
    () => (selectedReport ? getV2MetadataEntries(selectedReport.metadata) : []),
    [selectedReport]
  );

  const visibleReportSummary = useMemo(() => summarizeAdminReports(visibleReports), [visibleReports]);
  const activeSummary = serverSummary ?? visibleReportSummary;
  const heatmapCells = useMemo(
    () => buildAdminHeatmapCells(activeSummary.location.topClusters, activeSummary.location.bounds),
    [activeSummary.location.bounds, activeSummary.location.topClusters]
  );
  const selectedStatusHistory = useMemo(
    () => (selectedReport ? getStatusHistory(selectedReport.metadata) : []),
    [selectedReport]
  );

  const buildParams = useCallback(
    (includeLimit = true): ReportListParams => {
      const hasRadius = filters.lat !== "" && filters.lng !== "" && filters.radius_m !== "";
      return {
        limit: includeLimit ? ADMIN_PAGE_SIZE + 1 : undefined,
        offset: includeLimit ? pageOffset : undefined,
        status: filters.status,
        class_name: filters.class_name,
        source: filters.source,
        demo_filter: demoFilterMode,
        model_key: filters.model_key,
        trigger: filters.trigger,
        auto_reported: filters.auto_reported === "" ? "" : filters.auto_reported === "true",
        performance_excluded:
          filters.performance_excluded === "" ? "" : filters.performance_excluded === "true",
        created_from: koreanCalendarDayStart(filters.created_from),
        created_to: koreanCalendarDayEnd(filters.created_to),
        lat: hasRadius ? filters.lat : undefined,
        lng: hasRadius ? filters.lng : undefined,
        radius_m: hasRadius ? filters.radius_m : undefined
      };
    },
    [filters, demoFilterMode, pageOffset]
  );

  const exportHrefs = useMemo(() => {
    const params = buildParams(false);
    return {
      csv: reportExportUrl(params, "csv"),
      json: reportExportUrl(params, "json"),
      geojson: reportExportUrl(params, "geojson")
    } satisfies Record<ReportExportFormat, string>;
  }, [buildParams]);
  const publicGeojsonHref = useMemo(
    () => reportExportUrl(buildParams(false), "geojson", { profile: "minimum" }),
    [buildParams]
  );
  const gridGeojsonHref = useMemo(
    () => reportExportUrl(buildParams(false), "geojson", { profile: "internal", aggregate: "grid" }),
    [buildParams]
  );
  const manifestJsonHref = useMemo(() => reportExportUrl(buildParams(false), "json", { manifest: true }), [buildParams]);
  const agencyFilters = useMemo(() => agencyExportFilters(buildParams(false)), [buildParams]);
  const reviewedDamageExportHref = useMemo(
    () =>
      reportExportUrl(
        agencyFilters,
        "csv",
        { profile: "agency", auditId: agencyExportAuditId }
      ),
    [agencyExportAuditId, agencyFilters]
  );
  const reviewedDamageManifestHref = useMemo(
    () =>
      reportExportUrl(
        agencyFilters,
        "json",
        { profile: "agency", manifest: true, auditId: agencyExportAuditId }
      ),
    [agencyExportAuditId, agencyFilters]
  );

  const refreshReports = useCallback(async () => {
    if (gatewaySession.state !== "authenticated") {
      return;
    }
    const requestGeneration = ++adminRequestGenerationRef.current;
    setIsLoading(true);
    setError(null);

    try {
      const [nextReports, nextSummary] = await Promise.all([
        listReports(buildParams()),
        getReportSummary(buildParams(false))
      ]);
      if (requestGeneration !== adminRequestGenerationRef.current) {
        return;
      }
      const pageReports = nextReports.slice(0, ADMIN_PAGE_SIZE);
      setReports(pageReports);
      setHasNextPage(nextReports.length > ADMIN_PAGE_SIZE);
      setServerSummary(nextSummary);
      setSelectedId((current) => {
        if (current && pageReports.some((report) => report.id === current)) {
          return current;
        }
        return null;
      });
    } catch (caught) {
      if (requestGeneration !== adminRequestGenerationRef.current) {
        return;
      }
      setError(caught instanceof Error ? caught.message : "신고 목록을 불러오지 못했습니다.");
    } finally {
      if (requestGeneration === adminRequestGenerationRef.current) {
        setIsLoading(false);
      }
    }
  }, [buildParams, gatewaySession.state]);

  useEffect(() => {
    const timer = window.setTimeout(() => setPageOffset(0), 0);
    return () => window.clearTimeout(timer);
  }, [demoFilterMode, filters]);

  useEffect(() => {
    if (gatewaySession.state !== "authenticated") {
      return;
    }
    const timer = window.setTimeout(() => {
      void refreshReports();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [gatewaySession.state, refreshReports]);

  useEffect(() => {
    if (gatewaySession.state !== "authenticated") {
      return;
    }
    let ignore = false;

    async function refreshModelHealth() {
      try {
        const health = await getDetectV2Health();
        if (ignore) {
          return;
        }

        const mode = typeof health.mode === "string" ? `mode=${health.mode}` : null;
        const status = typeof health.status === "string" ? `status=${health.status}` : null;
        const reason = typeof health.reason === "string" && health.reason ? `reason=${health.reason}` : null;
        setModelHealthLabel([mode, status, reason].filter(Boolean).join(" · ") || "응답 있음");
      } catch {
        if (!ignore) {
          setModelHealthLabel("확인 실패");
        }
      }
    }

    void refreshModelHealth();

    return () => {
      ignore = true;
    };
  }, [gatewaySession.state]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (!selectedReport) {
        setReviewNote("");
        setResolutionReason("");
        return;
      }

      setReviewNote(typeof selectedReport.metadata.review_note === "string" ? selectedReport.metadata.review_note : "");
      setResolutionReason(
        typeof selectedReport.metadata.resolution_reason === "string" ? selectedReport.metadata.resolution_reason : ""
      );
    }, 0);
    return () => window.clearTimeout(timer);
  }, [selectedReport]);

  const handleStatusChange = async (reportId: string, status: ReportStatus) => {
    if (gatewaySession.state !== "authenticated") {
      return;
    }
    adminRequestGenerationRef.current += 1;
    const mutationGeneration = ++adminMutationGenerationRef.current;
    const currentReport = reports.find((report) => report.id === reportId);
    setUpdatingId(reportId);
    setError(null);

    try {
      const updated = await updateReportStatus(reportId, {
        status,
        note: reviewNote.trim() || undefined,
        resolution_reason: status === "resolved" ? resolutionReason.trim() || undefined : undefined,
        expected_updated_at: currentReport?.updated_at
      });
      if (mutationGeneration !== adminMutationGenerationRef.current) {
        return;
      }
      setReports((current) => current.map((report) => (report.id === reportId ? updated : report)));
      setSelectedId(updated.id);
      void refreshReports();
    } catch (caught) {
      if (mutationGeneration !== adminMutationGenerationRef.current) {
        return;
      }
      setError(
        caught instanceof Error
          ? `${caught.message} 최신 상태를 확인한 뒤 다시 시도해 주세요.`
          : "상태 변경에 실패했습니다."
      );
    } finally {
      if (mutationGeneration === adminMutationGenerationRef.current) {
        setUpdatingId(null);
      }
    }
  };

  const applyClusterFilter = (cluster: AdminReportGridCluster) => {
    setFilters((current) => ({
      ...current,
      lat: cluster.centerLatitude.toFixed(6),
      lng: cluster.centerLongitude.toFixed(6),
      radius_m: "150"
    }));
  };

  if (gatewaySession.state !== "authenticated") {
    return (
      <GatewaySessionGate
        access="admin"
        state={gatewaySession.state}
        message={gatewaySession.message}
        onAuthenticate={gatewaySession.authenticate}
        onRetry={gatewaySession.refresh}
      />
    );
  }

  return (
    <main className="admin-shell">
      <header className="admin-header">
        <div>
          <p className="admin-kicker">WalkSafe 운영</p>
          <h1>신고 관리</h1>
        </div>
        <div className="admin-header-actions">
          <label>
            <span>정렬</span>
            <select value={sortMode} onChange={(event) => setSortMode(event.target.value as SortMode)}>
              <option value="latest">최신순</option>
              <option value="confidence">현재 페이지 신뢰도순</option>
              <option value="status">현재 페이지 상태순</option>
            </select>
          </label>
          <button className="admin-action" type="button" onClick={refreshReports} disabled={isLoading}>
            <RefreshCw className={isLoading ? "spin" : undefined} aria-hidden="true" size={20} />
            새로고침
          </button>
          <button className="admin-action" type="button" onClick={() => void gatewaySession.logout()}>
            {gatewaySession.actorId ? `${gatewaySession.actorId} 로그아웃` : "로그아웃"}
          </button>
        </div>
      </header>

      <section className="admin-filters" aria-label="신고 목록 필터">
        <label>
          상태
          <select
            value={filters.status}
            onChange={(event) => setFilters((current) => ({ ...current, status: event.target.value as ReportStatus | "" }))}
          >
            <option value="">전체</option>
            <option value="new">신규</option>
            <option value="reviewed">현장 조치 대상</option>
            <option value="resolved">처리 완료</option>
          </select>
        </label>
        <label>
          위험 유형
          <select
            value={filters.class_name}
            onChange={(event) =>
              setFilters((current) => ({ ...current, class_name: event.target.value }))
            }
          >
            <option value="">전체</option>
            {ADMIN_CLASS_FILTER_OPTIONS.map((className) => (
              <option key={className} value={className}>
                {reportClassLabel(className)}
              </option>
            ))}
          </select>
        </label>
        <label>
          소스
          <select
            value={filters.source}
            onChange={(event) => {
              setDemoFilterMode("all");
              setFilters((current) => ({ ...current, source: event.target.value as DetectorSource | "" }));
            }}
          >
            <option value="">전체</option>
            <option value="fake">Fake</option>
            <option value="onnx">ONNX</option>
            <option value="server">Server</option>
            <option value="android">Android</option>
          </select>
        </label>
        <label>
          모델
          <select
            value={filters.model_key}
            onChange={(event) =>
              setFilters((current) => ({ ...current, model_key: event.target.value as ReportModelKey | "" }))
            }
          >
            <option value="">전체</option>
            {REPORT_MODEL_FILTER_OPTIONS.map((modelKey) => (
              <option key={modelKey} value={modelKey}>
                {MODEL_KEY_LABELS[modelKey]}({modelKey})
              </option>
            ))}
          </select>
        </label>
        <label>
          트리거
          <select
            value={filters.trigger}
            onChange={(event) =>
              setFilters((current) => ({ ...current, trigger: event.target.value as ReportTrigger | "" }))
            }
          >
            <option value="">전체</option>
            {(["auto", "voice"] as const).map((trigger) => (
              <option key={trigger} value={trigger}>
                {TRIGGER_LABELS[trigger]}({trigger})
              </option>
            ))}
          </select>
        </label>
        <label>
          자동 신고
          <select
            value={filters.auto_reported}
            onChange={(event) =>
              setFilters((current) => ({ ...current, auto_reported: event.target.value as FilterState["auto_reported"] }))
            }
          >
            <option value="">전체</option>
            <option value="true">자동 신고</option>
            <option value="false">사용자 요청</option>
          </select>
        </label>
        <label>
          성능 집계
          <select
            value={filters.performance_excluded}
            onChange={(event) =>
              setFilters((current) => ({
                ...current,
                performance_excluded: event.target.value as FilterState["performance_excluded"]
              }))
            }
          >
            <option value="">전체</option>
            <option value="false">집계 포함</option>
            <option value="true">집계 제외</option>
          </select>
        </label>
        <label>
          시작일
          <input
            type="date"
            value={filters.created_from}
            onChange={(event) => setFilters((current) => ({ ...current, created_from: event.target.value }))}
          />
        </label>
        <label>
          종료일
          <input
            type="date"
            value={filters.created_to}
            onChange={(event) => setFilters((current) => ({ ...current, created_to: event.target.value }))}
          />
        </label>
        <label>
          위도
          <input
            inputMode="decimal"
            placeholder="37.5665"
            value={filters.lat}
            onChange={(event) => setFilters((current) => ({ ...current, lat: event.target.value }))}
          />
        </label>
        <label>
          경도
          <input
            inputMode="decimal"
            placeholder="126.978"
            value={filters.lng}
            onChange={(event) => setFilters((current) => ({ ...current, lng: event.target.value }))}
          />
        </label>
        <label>
          반경 m
          <input
            inputMode="numeric"
            placeholder="300"
            value={filters.radius_m}
            onChange={(event) => setFilters((current) => ({ ...current, radius_m: event.target.value }))}
          />
        </label>
        <button className="admin-filter-button" type="button" onClick={refreshReports}>
          <Filter aria-hidden="true" size={18} />
          적용
        </button>
        {(["csv", "json", "geojson"] as const).map((format) => (
          <a key={format} className="admin-filter-button" href={exportHrefs[format]}>
            <Download aria-hidden="true" size={18} />
            {EXPORT_FORMAT_LABELS[format]} 내보내기
          </a>
        ))}
        <button
          className="admin-filter-button"
          type="button"
          onClick={() => setAgencyExportAuditId(window.crypto.randomUUID())}
        >
          {agencyExportAuditId ? "기관 제출 묶음 ID 새로 만들기" : "기관 제출 묶음 준비"}
        </button>
        <a
          aria-disabled={!agencyExportAuditId}
          className="admin-filter-button"
          href={reviewedDamageExportHref}
          onClick={(event) => {
            if (!agencyExportAuditId) event.preventDefault();
          }}
        >
          <Download aria-hidden="true" size={18} />
          기관 제출용 손상 점자블록(정확 위치·최소정보)
        </a>
        <a
          aria-disabled={!agencyExportAuditId}
          className="admin-filter-button"
          href={reviewedDamageManifestHref}
          onClick={(event) => {
            if (!agencyExportAuditId) event.preventDefault();
          }}
        >
          <Download aria-hidden="true" size={18} />
          기관 제출용 export manifest
        </a>
        <a className="admin-filter-button" href={publicGeojsonHref}>
          <Download aria-hidden="true" size={18} />
          공개용 최소정보 GeoJSON(위치 축약)
        </a>
        <a className="admin-filter-button" href={gridGeojsonHref}>
          <Download aria-hidden="true" size={18} />
          정확 좌표 기반 0.001° 집계 GeoJSON
        </a>
        <a className="admin-filter-button" href={manifestJsonHref}>
          <Download aria-hidden="true" size={18} />
          Export manifest
        </a>
        <span className="admin-export-warning">
          기관 전송은 자동화하지 않습니다. 정확 위치 export와 manifest를 제출한 뒤 외부 접수번호를 receipt CLI로 기록해야 합니다.
        </span>
        {demoFilterMode !== "exclude_fake" ? (
          <span className="admin-export-warning">Export 주의: Fake/Demo 포함 가능 · 외부 자동 제출 기능은 없습니다.</span>
        ) : null}
        {modelHealthLabel ? <span className="admin-model-health">모델 상태: {modelHealthLabel}</span> : null}
      </section>

      <section className="admin-ops-summary" aria-label="운영 데이터 요약">
        <div className={activeSummary.fake.fake > 0 ? "admin-demo-warning" : undefined}>
          <strong>Fake/Demo 운영 분리</strong>
          <span>
            서버 요약: Fake/Demo {activeSummary.fake.fake}건 · 비Fake {activeSummary.fake.nonFake}건 · 전체{" "}
            {activeSummary.fake.total}건
          </span>
          <small>
            소스별: Fake {activeSummary.fake.sourceFake}건 · Server {activeSummary.fake.sourceServer}건 · ONNX{" "}
            {activeSummary.fake.sourceOnnx}건 · Android {activeSummary.fake.sourceAndroid}건
          </small>
          <small>
            상태별: 신규 {activeSummary.status.new}건 · 현장 조치 대상 {activeSummary.status.reviewed}건 · 처리 완료{" "}
            {activeSummary.status.resolved}건
          </small>
          <small>
            화면 표시: {visibleReports.length}건 · {DEMO_FILTER_LABELS[demoFilterMode]}
          </small>
          {activeSummary.note ? <small>{activeSummary.note}</small> : null}
          {activeSummary.fake.fake > 0 ? (
            <small className="admin-export-warning">
              Export 주의: CSV/JSON/GeoJSON 내보내기도 현재 Fake/Demo 필터를 서버 파라미터로 함께 적용합니다.
            </small>
          ) : null}
          <div className="admin-quick-filters" aria-label="소스 빠른 필터">
            <button
              type="button"
              className={demoFilterMode === "all" && filters.source === "" ? "active" : undefined}
              onClick={() => {
                setDemoFilterMode("all");
                setFilters((current) => ({ ...current, source: "" }));
              }}
            >
              전체
            </button>
            <button
              type="button"
              className={demoFilterMode === "only_fake" ? "active" : undefined}
              onClick={() => {
                setDemoFilterMode("only_fake");
                setFilters((current) => ({ ...current, source: "" }));
              }}
            >
              Fake/Demo만 보기
            </button>
            <button
              type="button"
              className={demoFilterMode === "exclude_fake" ? "active" : undefined}
              onClick={() => {
                setDemoFilterMode("exclude_fake");
                setFilters((current) => ({ ...current, source: "" }));
              }}
            >
              Fake/Demo 제외
            </button>
            <button
              type="button"
              className={demoFilterMode === "all" && filters.source === "server" ? "active" : undefined}
              onClick={() => {
                setDemoFilterMode("all");
                setFilters((current) => ({ ...current, source: "server" }));
              }}
            >
              Server만 보기
            </button>
            <button
              type="button"
              className={demoFilterMode === "all" && filters.source === "android" ? "active" : undefined}
              onClick={() => {
                setDemoFilterMode("all");
                setFilters((current) => ({ ...current, source: "android" }));
              }}
            >
              Android만 보기
            </button>
          </div>
        </div>
        <div>
          <strong>서버 위치 요약</strong>
          <span>
            필터 전체 기준 위치 있음 {activeSummary.location.located}건 · 위치 없음{" "}
            {activeSummary.location.missing}건
          </span>
          <small>{formatBounds(activeSummary.location.bounds)}</small>
          <small>페이지당 {ADMIN_PAGE_SIZE}건 목록과 무관한 `/reports/summary` 전체 필터 결과입니다.</small>
        </div>
        <div>
          <strong>격자 클러스터 Top 5</strong>
          <small>
            외부 지도 SDK 없이 좌표를 {activeSummary.location.gridSizeDegrees.toFixed(3)}° 격자로 묶은 정적
            요약입니다.
          </small>
          {activeSummary.location.topClusters.length > 0 ? (
            <ol className="admin-cluster-list">
              {activeSummary.location.topClusters.map((cluster, index) => (
                <li key={cluster.key}>
                  <span>
                    #{index + 1} {cluster.count}건 · Fake/Demo {cluster.fake}건 · 비Fake {cluster.nonFake}건
                  </span>
                  <small>
                    중심 {cluster.centerLatitude.toFixed(5)}, {cluster.centerLongitude.toFixed(5)} ·{" "}
                    {formatClusterBounds(cluster)}
                  </small>
                  <small>
                    상태: 신규 {cluster.statusCounts.new} · 현장 조치 대상 {cluster.statusCounts.reviewed} · 완료{" "}
                    {cluster.statusCounts.resolved}
                  </small>
                  <small>
                    소스: Fake {cluster.sourceCounts.fake} · Server {cluster.sourceCounts.server} · ONNX{" "}
                    {cluster.sourceCounts.onnx} · Android {cluster.sourceCounts.android}
                  </small>
                  <button className="admin-cluster-filter" type="button" onClick={() => applyClusterFilter(cluster)}>
                    이 클러스터 반경 필터 적용
                  </button>
                </li>
              ))}
            </ol>
          ) : (
            <small>표시할 위치 좌표가 없습니다.</small>
          )}
        </div>
      </section>

      <section className="admin-heatmap-section" aria-label="신고 위치 격자 heatmap">
        <div className="admin-heatmap-header">
          <div>
            <strong>신고 위치 Heatmap</strong>
            <small>현재 필터의 상위 격자 {heatmapCells.length}개 · 위쪽이 북쪽</small>
          </div>
          <a href={gridGeojsonHref}>정확 좌표 기반 0.001° 관리자 집계 GeoJSON 열기</a>
        </div>
        {heatmapCells.length > 0 ? (
          <div className="admin-heatmap-plot" role="group" aria-label="신고 위치 밀도 격자">
            {heatmapCells.map((cell) => {
              const fakeDominant = cell.fakeRatio > 0.5;
              const alpha = 0.2 + cell.intensity * 0.65;
              return (
                <button
                  key={cell.cluster.key}
                  className={fakeDominant ? "admin-heatmap-cell fake" : "admin-heatmap-cell"}
                  type="button"
                  aria-label={`${cell.cluster.count}건 격자, Fake/Demo ${cell.cluster.fake}건, 반경 필터 적용`}
                  title={`${cell.cluster.count}건 · 중심 ${cell.cluster.centerLatitude.toFixed(5)}, ${cell.cluster.centerLongitude.toFixed(5)}`}
                  onClick={() => applyClusterFilter(cell.cluster)}
                  style={{
                    left: `${cell.leftPercent}%`,
                    top: `${cell.topPercent}%`,
                    width: `${cell.widthPercent}%`,
                    height: `${cell.heightPercent}%`,
                    backgroundColor: fakeDominant ? `rgba(185, 52, 34, ${alpha})` : `rgba(11, 107, 88, ${alpha})`
                  }}
                >
                  <span>{cell.cluster.count}</span>
                </button>
              );
            })}
          </div>
        ) : (
          <div className="admin-heatmap-empty">표시할 위치 좌표가 없습니다.</div>
        )}
        <div className="admin-heatmap-legend" aria-label="Heatmap 범례">
          <span><i className="non-fake" aria-hidden="true" />비Fake 중심</span>
          <span><i className="fake" aria-hidden="true" />Fake/Demo 중심</span>
          <small>격자를 누르면 해당 중심 반경 150m 필터가 입력됩니다.</small>
        </div>
      </section>

      {error ? (
        <div className="admin-error" role="alert">
          <span>{error}</span>
          <button type="button" onClick={() => setError(null)}>
            <X aria-hidden="true" size={18} />
            닫기
          </button>
        </div>
      ) : null}

      <section className="admin-content">
        <div className="report-list" aria-label="신고 목록">
          <div className="report-list-header">
            <strong>신고 {sortedReports.length}건 · {Math.floor(pageOffset / ADMIN_PAGE_SIZE) + 1}페이지</strong>
            <span>
              {isLoading
                ? "불러오는 중"
                : sortMode === "latest"
                  ? "목록에서 신고를 선택하세요"
                  : "선택한 정렬은 현재 페이지 50건에만 적용됩니다"}
            </span>
          </div>
          <div className="admin-pagination" aria-label="신고 목록 페이지 이동">
            <button
              type="button"
              disabled={isLoading || pageOffset === 0}
              onClick={() => setPageOffset((current) => Math.max(0, current - ADMIN_PAGE_SIZE))}
            >
              이전 페이지
            </button>
            <button
              type="button"
              disabled={isLoading || !hasNextPage}
              onClick={() => setPageOffset((current) => current + ADMIN_PAGE_SIZE)}
            >
              다음 페이지
            </button>
          </div>
          {visibleReports.length === 0 && !isLoading ? <div className="empty-list">조건에 맞는 신고가 없습니다.</div> : null}
          {sortedReports.map((report) => {
            const v2ListSummary = getV2ListSummary(report.metadata);
            const fakeOrDemo = isFakeOrDemoReport(report);
            return (
              <button
                key={report.id}
                className={`report-row ${selectedReport?.id === report.id ? "selected" : ""}`}
                type="button"
                aria-label={`${reportClassLabel(report.class_name)}, ${STATUS_LABELS[report.status]}, 신뢰도 ${Math.round(
                  report.confidence * 100
                )}%, ${formatGps(report)}`}
                aria-pressed={selectedReport?.id === report.id}
                onClick={() => setSelectedId((current) => (current === report.id ? null : report.id))}
              >
                <span className={`status-dot ${report.status}`} aria-hidden="true" />
                <span>
                  <strong>{reportClassLabel(report.class_name)}</strong>
                  <small>
                    {STATUS_LABELS[report.status]} · {SOURCE_LABELS[report.source]} · {formatDate(report.created_at)}
                  </small>
                  {fakeOrDemo ? <small className="admin-demo-badge">Fake/Demo · 성능/evidence 집계 제외</small> : null}
                  {v2ListSummary ? <small>v2 · {v2ListSummary}</small> : null}
                  <small className={report.location_quality === "missing" ? "missing-location" : undefined}>
                    {formatGps(report)} · {LOCATION_QUALITY_LABELS[report.location_quality]}
                  </small>
                </span>
                <b>{Math.round(report.confidence * 100)}%</b>
              </button>
            );
          })}
        </div>

        <div className="report-detail" aria-label="신고 상세">
          {selectedReport ? (
            <>
              <div className="detail-top">
                <div>
                  <span className="status-label">신고 상세</span>
                  <strong>#{selectedReport.id.slice(0, 8)}</strong>
                </div>
                <button className="detail-close" type="button" onClick={() => setSelectedId(null)}>
                  <X aria-hidden="true" size={20} />
                  <span className="sr-only">상세 패널 닫기</span>
                </button>
              </div>
              <div className="detail-image-frame">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={reportImageUrl(selectedReport)} alt={`${reportClassLabel(selectedReport.class_name)} 신고 이미지`} />
              </div>
              <div className="detail-summary">
                <div>
                  <span className="status-label">위험 유형</span>
                  <h2>{reportClassLabel(selectedReport.class_name)}</h2>
                </div>
                <span className={`detail-status ${selectedReport.status}`}>{STATUS_LABELS[selectedReport.status]}</span>
              </div>
              <div className="detail-grid">
                <div>
                  <Clock3 aria-hidden="true" size={18} />
                  <span>{formatDate(selectedReport.created_at)}</span>
                </div>
                <div>
                  <MapPin aria-hidden="true" size={18} />
                  <span>{formatGps(selectedReport)}</span>
                </div>
                <div>
                  <ImageIcon aria-hidden="true" size={18} />
                  <span>{selectedReport.image_content_type}</span>
                </div>
                <div>
                  <ShieldCheck aria-hidden="true" size={18} />
                  <span>{SOURCE_LABELS[selectedReport.source]}</span>
                </div>
              </div>
              <div className="review-flags" aria-label="검토 플래그">
                <strong>검토 표시</strong>
                <div>
	                  <span>{LOCATION_QUALITY_LABELS[selectedReport.location_quality]}</span>
	                  {isFakeOrDemoReport(selectedReport) ? <span>Fake/Demo</span> : null}
	                  {selectedReport.metadata?.performance_excluded === true ? <span>성능 집계 제외</span> : null}
                  {selectedReport.duplicate_report_ids.length > 0 ? (
                    <span>
                      중복 후보 {selectedReport.duplicate_report_ids.map((id) => id.slice(0, 8)).join(", ")}
                    </span>
                  ) : null}
                  {selectedReport.review_flags.length === 0 ? <span>추가 검토 표시 없음</span> : null}
                  {selectedReport.review_flags.map((flag) => (
                    <span key={flag}>{REVIEW_FLAG_LABELS[flag] ?? flag}</span>
                  ))}
                </div>
              </div>
              {selectedV2MetadataEntries.length > 0 ? (
                <div className="review-flags" aria-label="v2 메타데이터">
                  <strong>v2 메타데이터</strong>
                  <div>
                    {selectedV2MetadataEntries.map(({ key, value }) => (
                      <span key={key}>
                        {key}: {value}
                      </span>
                    ))}
                  </div>
                </div>
              ) : null}
              <div className="status-guidance" aria-label="상태별 검수 안내">
                <strong>{STATUS_LABELS[selectedReport.status]} 단계 안내</strong>
                <p>{STATUS_WORKFLOW_GUIDANCE[selectedReport.status].description}</p>
                <small>다음 조치: {STATUS_WORKFLOW_GUIDANCE[selectedReport.status].nextAction}</small>
              </div>
              <div className="status-review-note" aria-label="검수 메모">
                <label>
                  검수 메모
                  <textarea
                    value={reviewNote}
                    onChange={(event) => setReviewNote(event.target.value)}
                    placeholder="사진/위치/중복 확인 내용을 남깁니다."
                    maxLength={500}
                  />
                </label>
                <label>
                  처리 사유
                  <textarea
                    value={resolutionReason}
                    onChange={(event) => setResolutionReason(event.target.value)}
                    placeholder="처리 완료로 바꿀 때 사유를 남깁니다."
                    maxLength={500}
                  />
                </label>
                <small>상태 변경 시 현재 updated_at을 함께 보내 충돌을 감지합니다.</small>
              </div>
              {selectedStatusHistory.length > 0 ? (
                <div className="status-history" aria-label="상태 변경 이력">
                  <strong>상태 변경 이력</strong>
                  <ol>
                    {selectedStatusHistory.slice().reverse().map((entry, index) => (
                      <li key={`${entry.at}-${index}`}>
                        <span>
                          {STATUS_LABELS[entry.from]} → {STATUS_LABELS[entry.to]} · {formatDate(entry.at)}
                        </span>
                        {entry.actor_id ? <small>처리자: {entry.actor_id}</small> : null}
                        {entry.note ? <small>메모: {entry.note}</small> : null}
                        {entry.resolution_reason ? <small>처리 사유: {entry.resolution_reason}</small> : null}
                      </li>
                    ))}
                  </ol>
                </div>
              ) : null}
              <h3 className="status-action-title">상태 변경</h3>
              <div className="status-actions" aria-label="신고 상태 변경">
                {(["new", "reviewed", "resolved"] as const).map((status) => (
                  <button
                    key={status}
                    type="button"
                    className={selectedReport.status === status ? "active" : ""}
                    aria-pressed={selectedReport.status === status}
                    disabled={
                      updatingId === selectedReport.id || (selectedReport.status === "resolved" && status === "new")
                    }
                    onClick={() => void handleStatusChange(selectedReport.id, status)}
                  >
                    <CheckCircle2 aria-hidden="true" size={18} />
                    {STATUS_LABELS[status]}
                  </button>
                ))}
              </div>
            </>
          ) : (
            <div className="empty-detail">신고를 선택하면 상세 정보가 표시됩니다.</div>
          )}
        </div>
      </section>
    </main>
  );
}
