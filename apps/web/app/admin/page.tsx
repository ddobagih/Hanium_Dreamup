"use client";

import { CheckCircle2, Clock3, Download, Filter, Image as ImageIcon, MapPin, RefreshCw, ShieldCheck, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import {
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

const STATUS_LABELS: Record<ReportStatus, string> = {
  new: "신규",
  reviewed: "검토 중",
  resolved: "처리 완료"
};

const STATUS_WORKFLOW_GUIDANCE: Record<ReportStatus, { description: string; nextAction: string }> = {
  new: {
    description: "아직 운영자가 확인하지 않은 신고입니다.",
    nextAction: "사진, 위치, 중복 후보를 확인한 뒤 검토 중으로 옮깁니다."
  },
  reviewed: {
    description: "운영자가 현장 조치 필요성을 검토 중인 신고입니다.",
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
  server: "Server"
};

const MODEL_KEY_LABELS: Record<ReportModelKey, string> = {
  custom_tactile: "보도블록 손상",
  coco_general: "일반 객체"
};

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
  missing_heading: "방향 정보 없음"
};

const STATUS_SORT_ORDER: Record<ReportStatus, number> = {
  new: 0,
  reviewed: 1,
  resolved: 2
};

const ADMIN_CLASS_LABELS: Record<string, string> = {
  ...CLASS_LABELS,
  tactile_damage_area: "점자블록 파손 영역",
  damaged_tactile_block: "점자블록 파손"
};

const V2_METADATA_KEYS = [
  "schema_version",
  "model_key",
  "source_model",
  "trigger",
  "auto_reported",
  "distance_m",
  "trace_id",
  "payload_sha256",
  "image_sha256",
  "data_origin",
  "runtime_mode",
  "performance_excluded"
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
  created_from: "",
  created_to: "",
  lat: "",
  lng: "",
  radius_m: ""
};

const ADMIN_CLASS_FILTER_OPTIONS = Array.from(new Set([
  ...DETECTION_CLASS_NAMES,
  "tactile_damage_area",
  "damaged_tactile_block"
]));

function toDateTimeStart(value: string) {
  return value ? `${value}T00:00:00Z` : undefined;
}

function toDateTimeEnd(value: string) {
  return value ? `${value}T23:59:59Z` : undefined;
}

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
  const selectedStatusHistory = useMemo(
    () => (selectedReport ? getStatusHistory(selectedReport.metadata) : []),
    [selectedReport]
  );

  const buildParams = useCallback(
    (includeLimit = true): ReportListParams => {
      const hasRadius = filters.lat !== "" && filters.lng !== "" && filters.radius_m !== "";
      return {
        limit: includeLimit ? 50 : undefined,
        status: filters.status,
        class_name: filters.class_name,
        source: filters.source,
        demo_filter: demoFilterMode,
        model_key: filters.model_key,
        trigger: filters.trigger,
        auto_reported: filters.auto_reported === "" ? "" : filters.auto_reported === "true",
        created_from: toDateTimeStart(filters.created_from),
        created_to: toDateTimeEnd(filters.created_to),
        lat: hasRadius ? filters.lat : undefined,
        lng: hasRadius ? filters.lng : undefined,
        radius_m: hasRadius ? filters.radius_m : undefined
      };
    },
    [filters, demoFilterMode]
  );

  const exportHrefs = useMemo(() => {
    const params = buildParams(false);
    return {
      csv: reportExportUrl(params, "csv"),
      json: reportExportUrl(params, "json"),
      geojson: reportExportUrl(params, "geojson")
    } satisfies Record<ReportExportFormat, string>;
  }, [buildParams]);
  const publicGeojsonHref = useMemo(() => reportExportUrl(buildParams(false), "geojson", { redacted: true }), [buildParams]);
  const manifestJsonHref = useMemo(() => reportExportUrl(buildParams(false), "json", { manifest: true }), [buildParams]);
  const agencyExportHref = useMemo(
    () =>
      reportExportUrl(
        {
          status: "reviewed",
          demo_filter: "exclude_fake",
          model_key: "custom_tactile"
        },
        "geojson",
        { redacted: true }
      ),
    []
  );

  const refreshReports = useCallback(async () => {
    setIsLoading(true);
    setError(null);

    try {
      const [nextReports, nextSummary] = await Promise.all([
        listReports(buildParams()),
        getReportSummary(buildParams(false))
      ]);
      setReports(nextReports);
      setServerSummary(nextSummary);
      setSelectedId((current) => {
        if (current && nextReports.some((report) => report.id === current)) {
          return current;
        }
        return null;
      });
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "신고 목록을 불러오지 못했습니다.");
    } finally {
      setIsLoading(false);
    }
  }, [buildParams]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void refreshReports();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [refreshReports]);

  useEffect(() => {
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
  }, []);

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
      setReports((current) => current.map((report) => (report.id === reportId ? updated : report)));
      setSelectedId(updated.id);
    } catch (caught) {
      setError(
        caught instanceof Error
          ? `${caught.message} 최신 상태를 확인한 뒤 다시 시도해 주세요.`
          : "상태 변경에 실패했습니다."
      );
    } finally {
      setUpdatingId(null);
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
              <option value="confidence">신뢰도순</option>
              <option value="status">상태순</option>
            </select>
          </label>
          <button className="admin-action" type="button" onClick={refreshReports} disabled={isLoading}>
            <RefreshCw className={isLoading ? "spin" : undefined} aria-hidden="true" size={20} />
            새로고침
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
            <option value="reviewed">검토 중</option>
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
            {(["custom_tactile", "coco_general"] as const).map((modelKey) => (
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
        <a className="admin-filter-button" href={agencyExportHref}>
          <Download aria-hidden="true" size={18} />
          기관 제출 후보(redacted)
        </a>
        <a className="admin-filter-button" href={publicGeojsonHref}>
          <Download aria-hidden="true" size={18} />
          공개용 GeoJSON
        </a>
        <a className="admin-filter-button" href={manifestJsonHref}>
          <Download aria-hidden="true" size={18} />
          Export manifest
        </a>
        {demoFilterMode !== "exclude_fake" ? (
          <span className="admin-export-warning">Export 주의: Fake/Demo 포함 가능 · 기관 제출 후보는 reviewed+비Demo만 사용하세요.</span>
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
            {activeSummary.fake.sourceOnnx}건
          </small>
          <small>
            상태별: 신규 {activeSummary.status.new}건 · 검토 중 {activeSummary.status.reviewed}건 · 처리 완료{" "}
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
          </div>
        </div>
        <div>
          <strong>서버 위치 요약</strong>
          <span>
            필터 전체 기준 위치 있음 {activeSummary.location.located}건 · 위치 없음{" "}
            {activeSummary.location.missing}건
          </span>
          <small>{formatBounds(activeSummary.location.bounds)}</small>
          <small>목록 50건 제한과 무관한 `/reports/summary` 결과입니다.</small>
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
                    상태: 신규 {cluster.statusCounts.new} · 검토 {cluster.statusCounts.reviewed} · 완료{" "}
                    {cluster.statusCounts.resolved}
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
            <strong>신고 {sortedReports.length}건</strong>
            <span>{isLoading ? "불러오는 중" : "목록에서 신고를 선택하세요"}</span>
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
                  {isFakeOrDemoReport(selectedReport) ? <span>Fake/Demo · 제출/성능 제외</span> : null}
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
