/**
 * Applies destination-candidate safety policy before navigation state is allowed to advance.
 * Ambiguous or out-of-range results require explicit user selection instead of silent auto-navigation.
 */
import type { DestinationSearchResponse, DestinationSearchResult } from "../../types/navigation";

export type NavigationDestinationPolicyStatus =
  | "no_candidates"
  | "single_candidate"
  | "multiple_candidates"
  | "distance_unknown"
  | "out_of_range";

export type NavigationDestinationCandidate = {
  id: string;
  name: string;
  label: string;
  addressLabel: string | null;
  distanceLabel: string | null;
  speechLabel: string;
  result: DestinationSearchResult;
};

export type NavigationDestinationPolicy = {
  status: NavigationDestinationPolicyStatus;
  autoNavigationAllowed: boolean;
  canSelectCandidate: boolean;
  selectedCandidate: DestinationSearchResult | null;
  candidates: NavigationDestinationCandidate[];
  totalCandidateCount: number;
  hiddenCandidateCount: number;
  outOfRangeCount: number;
  unknownDistanceCount: number;
  message: string;
  speechMessage: string;
};

const DEFAULT_CANDIDATE_LIMIT = 3;

function compactText(value: string | null | undefined): string | null {
  const compacted = value?.replace(/\s+/g, " ").trim() ?? "";
  return compacted.length > 0 ? compacted : null;
}

function destinationName(result: DestinationSearchResult): string {
  return compactText(result.name) ?? "이름 없는 장소";
}

function destinationAddressLabel(result: DestinationSearchResult): string | null {
  return compactText(result.road_address) ?? compactText(result.address);
}

function destinationCandidateLabel(result: DestinationSearchResult): string {
  const name = destinationName(result);
  const address = destinationAddressLabel(result);
  return address ? `${name} · ${address}` : name;
}

function destinationSpeechLabel(result: DestinationSearchResult): string {
  const name = boundedSpeechText(destinationName(result), 40, "이름 미상");
  const address = boundedSpeechText(destinationAddressLabel(result), 60, "주소 미상");
  return `${name}, ${address}, ${destinationDistanceLabel(result) ?? "거리 미상"}`;
}

function boundedSpeechText(value: string | null, maxLength: number, fallback: string): string {
  const normalized = compactText(value);
  if (!normalized) return fallback;
  return normalized.length <= maxLength ? normalized : `${normalized.slice(0, maxLength - 1)}…`;
}

function destinationDistanceLabel(result: DestinationSearchResult): string | null {
  if (typeof result.distance_m !== "number" || !Number.isFinite(result.distance_m) || result.distance_m < 0) {
    return null;
  }
  if (result.distance_m >= 1000) {
    return `${(result.distance_m / 1000).toFixed(1)}km`;
  }
  return `${Math.round(result.distance_m)}m`;
}

function destinationCandidate(result: DestinationSearchResult): NavigationDestinationCandidate {
  return {
    id: result.id,
    name: destinationName(result),
    label: destinationCandidateLabel(result),
    addressLabel: destinationAddressLabel(result),
    distanceLabel: destinationDistanceLabel(result),
    speechLabel: destinationSpeechLabel(result),
    result
  };
}

function candidateLimit(limit: number | undefined): number {
  if (limit === undefined || !Number.isFinite(limit)) {
    return DEFAULT_CANDIDATE_LIMIT;
  }
  return Math.max(1, Math.floor(limit));
}

function candidateRangeStatus(
  result: DestinationSearchResult,
  maxDistanceM: number | null | undefined
): "in_range" | "out_of_range" | "unknown" {
  if (typeof result.distance_m !== "number" || !Number.isFinite(result.distance_m) || result.distance_m < 0) {
    return "unknown";
  }
  if (maxDistanceM === null || maxDistanceM === undefined || !Number.isFinite(maxDistanceM) || maxDistanceM <= 0) {
    return "in_range";
  }
  return result.distance_m <= maxDistanceM ? "in_range" : "out_of_range";
}

export function resolveNavigationDestinationPolicy(
  response: DestinationSearchResponse,
  options: { candidateLimit?: number; maxCandidateDistanceM?: number | null } = {}
): NavigationDestinationPolicy {
  const originalResults = response.results;
  const rangeStatuses = originalResults.map((result) => candidateRangeStatus(result, options.maxCandidateDistanceM));
  const results = originalResults.filter((_, index) => rangeStatuses[index] !== "out_of_range");
  const outOfRangeCount = rangeStatuses.filter((status) => status === "out_of_range").length;
  const unknownDistanceCount = rangeStatuses.filter((status) => status === "unknown").length;

  if (results.length === 0) {
    const query = compactText(response.query) ?? "목적지";
    const maxDistanceM = options.maxCandidateDistanceM;
    if (outOfRangeCount > 0 && typeof maxDistanceM === "number" && Number.isFinite(maxDistanceM)) {
      const maxDistanceLabel = destinationDistanceLabel({
        id: "range",
        name: "range",
        point: { latitude: 0, longitude: 0 },
        distance_m: maxDistanceM
      });
      return {
        status: "out_of_range",
        autoNavigationAllowed: false,
        canSelectCandidate: false,
        selectedCandidate: null,
        candidates: [],
        totalCandidateCount: originalResults.length,
        hiddenCandidateCount: 0,
        outOfRangeCount,
        unknownDistanceCount,
        message: `“${query}” 후보 ${outOfRangeCount}개가 ${maxDistanceLabel ?? "허용 범위"} 밖입니다. 더 가까운 목적지를 다시 검색해 주세요.`,
        speechMessage: `${query} 후보가 허용 범위 밖입니다. 더 가까운 목적지를 다시 말해 주세요.`
      };
    }
    return {
      status: "no_candidates",
      autoNavigationAllowed: false,
      canSelectCandidate: false,
      selectedCandidate: null,
      candidates: [],
      totalCandidateCount: 0,
      hiddenCandidateCount: 0,
      outOfRangeCount,
      unknownDistanceCount,
      message: `“${query}” 검색 결과가 없습니다. 목적지를 더 자세히 다시 말해 주세요.`,
      speechMessage: `${query} 검색 결과가 없습니다. 목적지를 더 자세히 다시 말해 주세요.`
    };
  }

  if (results.length === 1 && unknownDistanceCount === 0) {
    const candidate = destinationCandidate(results[0]);
    return {
      status: "single_candidate",
      autoNavigationAllowed: true,
      canSelectCandidate: true,
      selectedCandidate: candidate.result,
      candidates: [candidate],
      totalCandidateCount: originalResults.length,
      hiddenCandidateCount: 0,
      outOfRangeCount,
      unknownDistanceCount,
      message: `${candidate.label}로 안내할 수 있습니다.`,
      speechMessage: `${candidate.speechLabel}로 안내할 수 있습니다.`
    };
  }

  if (results.length === 1) {
    const candidate = destinationCandidate(results[0]);
    return {
      status: "distance_unknown",
      autoNavigationAllowed: false,
      canSelectCandidate: true,
      selectedCandidate: null,
      candidates: [candidate],
      totalCandidateCount: originalResults.length,
      hiddenCandidateCount: 0,
      outOfRangeCount,
      unknownDistanceCount,
      message: `${candidate.label}의 현재 위치 기준 거리를 확인할 수 없습니다. 목적지가 맞는지 직접 선택해 주세요.`,
      speechMessage: `${candidate.speechLabel}의 거리를 확인할 수 없습니다. 목적지가 맞으면 1번을 선택해 주세요.`
    };
  }

  const visibleCandidates = results.slice(0, candidateLimit(options.candidateLimit)).map(destinationCandidate);
  const moreCount = results.length - visibleCandidates.length;
  const displayList = visibleCandidates.map((candidate, index) => `${index + 1}. ${candidate.label}`).join("\n");
  const moreText = moreCount > 0 ? `\n외 ${moreCount}개 결과가 더 있습니다.` : "";
  const speechList = visibleCandidates.map((candidate, index) => `${index + 1}번 ${candidate.speechLabel}`).join(", ");
  const moreSpeech = moreCount > 0 ? ` 나머지 ${moreCount}개는 화면의 더 보기에서 확인할 수 있습니다.` : "";

  return {
    status: "multiple_candidates",
    autoNavigationAllowed: false,
    canSelectCandidate: true,
    selectedCandidate: null,
    candidates: visibleCandidates,
    totalCandidateCount: originalResults.length,
    hiddenCandidateCount: Math.max(0, moreCount),
    outOfRangeCount,
    unknownDistanceCount,
    message: `검색 결과가 ${results.length}개입니다. 원하는 목적지를 선택해 주세요.\n${displayList}${moreText}`,
    speechMessage: `검색 결과가 ${results.length}개입니다. ${speechList}.${moreSpeech} 원하는 번호를 말씀해 주세요.`
  };
}
