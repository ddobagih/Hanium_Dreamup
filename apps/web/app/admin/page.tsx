"use client";

import { CheckCircle2, Clock3, Filter, Image as ImageIcon, MapPin, RefreshCw, ShieldCheck, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import {
  listReports,
  reportImageUrl,
  type ReportListParams,
  type ReportResponse,
  type ReportStatus,
  updateReportStatus
} from "@/lib/report-api";
import { CLASS_LABELS, DETECTION_CLASS_NAMES, type DetectionClassName, type DetectorSource } from "@/types/inference";

const STATUS_LABELS: Record<ReportStatus, string> = {
  new: "신규",
  reviewed: "검토 중",
  resolved: "처리 완료"
};

const SOURCE_LABELS: Record<DetectorSource, string> = {
  fake: "Fake",
  onnx: "ONNX",
  server: "Server"
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

type SortMode = "latest" | "confidence" | "status";

type FilterState = {
  status: ReportStatus | "";
  class_name: DetectionClassName | "";
  source: DetectorSource | "";
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
  created_from: "",
  created_to: "",
  lat: "",
  lng: "",
  radius_m: ""
};

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

export default function AdminReportsPage() {
  const [filters, setFilters] = useState<FilterState>(EMPTY_FILTERS);
  const [reports, setReports] = useState<ReportResponse[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [sortMode, setSortMode] = useState<SortMode>("latest");
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [updatingId, setUpdatingId] = useState<string | null>(null);

  const sortedReports = useMemo(() => {
    const nextReports = [...reports];
    if (sortMode === "confidence") {
      return nextReports.sort((first, second) => second.confidence - first.confidence);
    }
    if (sortMode === "status") {
      return nextReports.sort((first, second) => STATUS_SORT_ORDER[first.status] - STATUS_SORT_ORDER[second.status]);
    }
    return nextReports.sort((first, second) => Date.parse(second.created_at) - Date.parse(first.created_at));
  }, [reports, sortMode]);

  const selectedReport = useMemo(() => reports.find((report) => report.id === selectedId) ?? null, [reports, selectedId]);

  const buildParams = useCallback((): ReportListParams => {
    const hasRadius = filters.lat !== "" && filters.lng !== "" && filters.radius_m !== "";
    return {
      limit: 50,
      status: filters.status,
      class_name: filters.class_name,
      source: filters.source,
      created_from: toDateTimeStart(filters.created_from),
      created_to: toDateTimeEnd(filters.created_to),
      lat: hasRadius ? filters.lat : undefined,
      lng: hasRadius ? filters.lng : undefined,
      radius_m: hasRadius ? filters.radius_m : undefined
    };
  }, [filters]);

  const refreshReports = useCallback(async () => {
    setIsLoading(true);
    setError(null);

    try {
      const nextReports = await listReports(buildParams());
      setReports(nextReports);
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

  const handleStatusChange = async (reportId: string, status: ReportStatus) => {
    setUpdatingId(reportId);
    setError(null);

    try {
      const updated = await updateReportStatus(reportId, status);
      setReports((current) => current.map((report) => (report.id === reportId ? updated : report)));
      setSelectedId(updated.id);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "상태 변경에 실패했습니다.");
    } finally {
      setUpdatingId(null);
    }
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
              setFilters((current) => ({ ...current, class_name: event.target.value as DetectionClassName | "" }))
            }
          >
            <option value="">전체</option>
            {DETECTION_CLASS_NAMES.map((className) => (
              <option key={className} value={className}>
                {CLASS_LABELS[className]}
              </option>
            ))}
          </select>
        </label>
        <label>
          소스
          <select
            value={filters.source}
            onChange={(event) => setFilters((current) => ({ ...current, source: event.target.value as DetectorSource | "" }))}
          >
            <option value="">전체</option>
            <option value="fake">Fake</option>
            <option value="onnx">ONNX</option>
            <option value="server">Server</option>
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
          {reports.length === 0 && !isLoading ? <div className="empty-list">조건에 맞는 신고가 없습니다.</div> : null}
          {sortedReports.map((report) => (
            <button
              key={report.id}
              className={`report-row ${selectedReport?.id === report.id ? "selected" : ""}`}
              type="button"
              aria-label={`${CLASS_LABELS[report.class_name]}, ${STATUS_LABELS[report.status]}, 신뢰도 ${Math.round(
                report.confidence * 100
              )}%, ${formatGps(report)}`}
              aria-pressed={selectedReport?.id === report.id}
              onClick={() => setSelectedId((current) => (current === report.id ? null : report.id))}
            >
              <span className={`status-dot ${report.status}`} aria-hidden="true" />
              <span>
                <strong>{CLASS_LABELS[report.class_name]}</strong>
                <small>
                  {STATUS_LABELS[report.status]} · {SOURCE_LABELS[report.source]} · {formatDate(report.created_at)}
                </small>
                <small className={report.location_quality === "missing" ? "missing-location" : undefined}>
                  {formatGps(report)} · {LOCATION_QUALITY_LABELS[report.location_quality]}
                </small>
              </span>
              <b>{Math.round(report.confidence * 100)}%</b>
            </button>
          ))}
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
                <img src={reportImageUrl(selectedReport)} alt={`${CLASS_LABELS[selectedReport.class_name]} 신고 이미지`} />
              </div>
              <div className="detail-summary">
                <div>
                  <span className="status-label">위험 유형</span>
                  <h2>{CLASS_LABELS[selectedReport.class_name]}</h2>
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
                  {selectedReport.review_flags.length === 0 ? <span>추가 검토 표시 없음</span> : null}
                  {selectedReport.review_flags.map((flag) => (
                    <span key={flag}>{REVIEW_FLAG_LABELS[flag] ?? flag}</span>
                  ))}
                </div>
              </div>
              <h3 className="status-action-title">상태 변경</h3>
              <div className="status-actions" aria-label="신고 상태 변경">
                {(["new", "reviewed", "resolved"] as const).map((status) => (
                  <button
                    key={status}
                    type="button"
                    className={selectedReport.status === status ? "active" : ""}
                    aria-pressed={selectedReport.status === status}
                    disabled={updatingId === selectedReport.id}
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
