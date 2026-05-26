import { resolveNavigationDestinationPolicy } from "../app/_walksafe/navigation-destination";
import type { DestinationSearchResponse, DestinationSearchResult } from "../types/navigation";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

function result(overrides: Partial<DestinationSearchResult> = {}): DestinationSearchResult {
  return {
    id: "poi-1",
    name: "서울역",
    point: { latitude: 37.5547, longitude: 126.9706, name: "서울역" },
    address: "서울 중구 봉래동2가 122-11",
    road_address: "서울 중구 한강대로 405",
    category: "교통",
    distance_m: 120,
    ...overrides
  };
}

function response(results: DestinationSearchResult[], query = "서울역"): DestinationSearchResponse {
  return {
    schema_version: "walksafe.destination_search.v1",
    provider: "tmap_poi",
    query,
    results
  };
}

function testNoCandidateBlocksAutoNavigationAndAsksAgain() {
  const policy = resolveNavigationDestinationPolicy(response([], "없는 장소"));

  assert(policy.status === "no_candidates", "empty result should use no_candidates status");
  assert(policy.autoNavigationAllowed === false, "empty result must block automatic navigation");
  assert(policy.canSelectCandidate === false, "empty result should not be selectable");
  assert(policy.selectedCandidate === null, "empty result should not select a candidate");
  assert(policy.candidates.length === 0, "empty result should return no candidates");
  assert(policy.message.includes("없는 장소"), "empty result message should include query");
  assert(policy.message.includes("다시"), "empty result message should ask the user again");
}

function testSingleCandidateCanBeSelected() {
  const candidate = result({ id: "seoul-station" });
  const policy = resolveNavigationDestinationPolicy(response([candidate]));

  assert(policy.status === "single_candidate", "single result should use single_candidate status");
  assert(policy.autoNavigationAllowed === true, "single result can proceed to navigation");
  assert(policy.canSelectCandidate === true, "single result should be selectable");
  assert(policy.selectedCandidate?.id === "seoul-station", "single result should return selected candidate");
  assert(policy.candidates.length === 1, "single result should return one candidate summary");
}

function testMultipleCandidatesBlockAutoNavigationAndSummarizeThree() {
  const policy = resolveNavigationDestinationPolicy(
    response([
      result({ id: "poi-1", name: "서울역" }),
      result({ id: "poi-2", name: "서울역 공항철도" }),
      result({ id: "poi-3", name: "서울역 버스환승센터" }),
      result({ id: "poi-4", name: "서울역 서부" })
    ])
  );

  assert(policy.status === "multiple_candidates", "multiple results should use multiple_candidates status");
  assert(policy.autoNavigationAllowed === false, "multiple results must block automatic navigation");
  assert(policy.selectedCandidate === null, "multiple results should not select a candidate automatically");
  assert(policy.candidates.length === 3, "multiple results should summarize three candidates by default");
  assert(policy.hiddenCandidateCount === 1, "multiple results should expose hidden candidate count");
  assert(policy.message.includes("1. 서울역"), "display message should include numbered candidates");
  assert(policy.message.includes("외 1개"), "display message should mention hidden candidate count");
  assert(!policy.message.includes("서울역 서부 ·"), "display message should not expand the fourth candidate");
  assert(policy.speechMessage.includes("1번 서울역"), "speech message should include short numbered candidates");
  assert(!policy.speechMessage.includes("한강대로"), "speech message should stay short and omit addresses");
}

function testAddressDisplayPrefersRoadAddress() {
  const policy = resolveNavigationDestinationPolicy(
    response([result({ road_address: "서울 중구 세종대로 110", address: "서울 중구 태평로1가 31" })])
  );
  const candidate = policy.candidates[0];

  assert(candidate.addressLabel === "서울 중구 세종대로 110", "address display should prefer road_address");
  assert(candidate.label === "서울역 · 서울 중구 세종대로 110", "candidate label should combine name and preferred address");
}

function testCandidateLabelFallsBackToAddressAndTrims() {
  const policy = resolveNavigationDestinationPolicy(
    response([result({ name: "  시청역  ", road_address: "  ", address: "  서울 중구 정동 5-5  " })])
  );
  const candidate = policy.candidates[0];

  assert(candidate.name === "시청역", "candidate name should be trimmed");
  assert(candidate.addressLabel === "서울 중구 정동 5-5", "address display should fall back to address");
  assert(candidate.label === "시청역 · 서울 중구 정동 5-5", "candidate label should use fallback address");
}

function testCandidateDistanceLabelIsSeparateFromNameLabel() {
  const policy = resolveNavigationDestinationPolicy(response([result({ distance_m: 1530 })]));
  const candidate = policy.candidates[0];

  assert(candidate.label === "서울역 · 서울 중구 한강대로 405", "candidate label should keep stable name/address text");
  assert(candidate.distanceLabel === "1.5km", "candidate distance should be exposed separately for UI");
}

function testOutOfRangeCandidateBlocksSelection() {
  const policy = resolveNavigationDestinationPolicy(response([result({ id: "far-poi", distance_m: 40000 })]), {
    maxCandidateDistanceM: 30000
  });

  assert(policy.status === "out_of_range", "far-only result should use out_of_range status");
  assert(policy.autoNavigationAllowed === false, "out-of-range result must block automatic navigation");
  assert(policy.canSelectCandidate === false, "out-of-range result should not be selectable");
  assert(policy.candidates.length === 0, "out-of-range candidates should not be returned as selectable candidates");
  assert(policy.outOfRangeCount === 1, "out-of-range count should be exposed");
}

function main() {
  testNoCandidateBlocksAutoNavigationAndAsksAgain();
  testSingleCandidateCanBeSelected();
  testMultipleCandidatesBlockAutoNavigationAndSummarizeThree();
  testAddressDisplayPrefersRoadAddress();
  testCandidateLabelFallsBackToAddressAndTrims();
  testCandidateDistanceLabelIsSeparateFromNameLabel();
  testOutOfRangeCandidateBlocksSelection();
  console.log("navigation destination policy checks passed");
}

main();
