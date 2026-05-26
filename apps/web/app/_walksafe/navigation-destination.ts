import type { DestinationSearchResponse, DestinationSearchResult } from "../../types/navigation";

export type NavigationDestinationPolicyStatus = "no_candidates" | "single_candidate" | "multiple_candidates" | "out_of_range";

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
  return destinationName(result);
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

function isCandidateInRange(result: DestinationSearchResult, maxDistanceM: number | null | undefined): boolean {
  if (maxDistanceM === null || maxDistanceM === undefined || !Number.isFinite(maxDistanceM) || maxDistanceM <= 0) {
    return true;
  }
  if (typeof result.distance_m !== "number" || !Number.isFinite(result.distance_m)) {
    return true;
  }
  return result.distance_m <= maxDistanceM;
}

export function resolveNavigationDestinationPolicy(
  response: DestinationSearchResponse,
  options: { candidateLimit?: number; maxCandidateDistanceM?: number | null } = {}
): NavigationDestinationPolicy {
  const originalResults = response.results;
  const results = originalResults.filter((result) => isCandidateInRange(result, options.maxCandidateDistanceM));
  const outOfRangeCount = originalResults.length - results.length;

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
      message: `“${query}” 검색 결과가 없습니다. 목적지를 더 자세히 다시 말해 주세요.`,
      speechMessage: `${query} 검색 결과가 없습니다. 목적지를 더 자세히 다시 말해 주세요.`
    };
  }

  if (results.length === 1) {
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
      message: `${candidate.label}로 안내할 수 있습니다.`,
      speechMessage: `${candidate.speechLabel}로 안내할 수 있습니다.`
    };
  }

  const visibleCandidates = results.slice(0, candidateLimit(options.candidateLimit)).map(destinationCandidate);
  const moreCount = results.length - visibleCandidates.length;
  const displayList = visibleCandidates.map((candidate, index) => `${index + 1}. ${candidate.label}`).join("\n");
  const moreText = moreCount > 0 ? `\n외 ${moreCount}개 결과가 더 있습니다.` : "";
  const speechList = visibleCandidates.map((candidate, index) => `${index + 1}번 ${candidate.speechLabel}`).join(", ");

  return {
    status: "multiple_candidates",
    autoNavigationAllowed: false,
    canSelectCandidate: true,
    selectedCandidate: null,
    candidates: visibleCandidates,
    totalCandidateCount: originalResults.length,
    hiddenCandidateCount: Math.max(0, moreCount),
    outOfRangeCount,
    message: `검색 결과가 ${results.length}개입니다. 원하는 목적지를 선택해 주세요.\n${displayList}${moreText}`,
    speechMessage: `검색 결과가 ${results.length}개입니다. ${speechList} 중에서 선택해 주세요.`
  };
}
