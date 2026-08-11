import {
  buildNavigationGuidePrompt,
  estimateWalkingSpeedMps,
  resolveAutoRerouteDecision,
  resolveNavigationRouteRequestGate,
  WALKSAFE_ROUTE_REQUEST_COOLDOWN_MS,
  type NavigationGuidePrompt
} from "../app/_walksafe/hooks/useNavigationGuidance";
import {
  WALKSAFE_DEFAULT_STEP_LENGTH_M,
  WALKSAFE_GUIDE_SOON_RADIUS_M,
  WALKSAFE_GUIDE_TURN_RADIUS_M
} from "../app/_walksafe/config";
import { reportExportUrl, type ReportExportFormat, type ReportListParams } from "../lib/report-api";
import { resolveVoiceFeedbackState, shouldApplyNavigationStatusMessage } from "../app/_walksafe/voice-priority";
import type { RoutePoint, WalkingRouteGuidePoint } from "../types/navigation";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}


function assertSearchParam(url: URL, key: string, expected: string) {
  assert(url.searchParams.get(key) === expected, `${key} should be ${expected}`);
}

function parseExportUrl(filters: ReportListParams, format: ReportExportFormat) {
  return new URL(reportExportUrl(filters, format));
}

function testReportExportUrlSupportsFormats() {
  const formats: ReportExportFormat[] = ["csv", "json", "geojson"];

  for (const format of formats) {
    const url = parseExportUrl({}, format);
    assert(url.pathname === "/reports/export", `${format} export path should target report export endpoint`);
    assertSearchParam(url, "format", format);
    assert(url.searchParams.get("limit") === null, `${format} export should not include list limit`);
  }
}

function testReportExportUrlIncludesFilters() {
  const url = parseExportUrl(
    {
      limit: 50,
      status: "reviewed",
      class_name: "damaged_tactile_block",
      source: "onnx",
      model_key: "custom_tactile",
      trigger: "auto",
      auto_reported: false,
      created_from: "2026-05-24T00:00:00Z",
      created_to: "2026-05-24T23:59:59Z",
      lat: "37.5665",
      lng: "126.978",
      radius_m: "300"
    },
    "json"
  );

  assertSearchParam(url, "format", "json");
  assertSearchParam(url, "status", "reviewed");
  assertSearchParam(url, "class_name", "damaged_tactile_block");
  assertSearchParam(url, "source", "onnx");
  assertSearchParam(url, "model_key", "custom_tactile");
  assertSearchParam(url, "trigger", "auto");
  assertSearchParam(url, "auto_reported", "false");
  assertSearchParam(url, "created_from", "2026-05-24T00:00:00Z");
  assertSearchParam(url, "created_to", "2026-05-24T23:59:59Z");
  assertSearchParam(url, "lat", "37.5665");
  assertSearchParam(url, "lng", "126.978");
  assertSearchParam(url, "radius_m", "300");
  assert(url.searchParams.get("limit") === null, "export URL should ignore list limit filter");
}

function testReportExportUrlOmitsEmptyFilters() {
  const url = parseExportUrl(
    {
      status: "",
      class_name: "",
      source: "",
      model_key: "",
      trigger: "",
      auto_reported: "",
      created_from: undefined,
      created_to: undefined,
      lat: undefined,
      lng: undefined,
      radius_m: undefined
    },
    "geojson"
  );

  assertSearchParam(url, "format", "geojson");
  assert(Array.from(url.searchParams.keys()).length === 1, "empty export filters should be omitted");
}

function point(latitude: number, longitude: number): RoutePoint {
  return { latitude, longitude };
}

function guide(instruction: string | null = "좌회전"): WalkingRouteGuidePoint {
  return {
    index: 1,
    point: point(37.0, 127.0),
    instruction,
    turn_type: null,
    point_type: "Point",
    distance_from_start_m: null,
    remaining_distance_m: null
  };
}

function guideFixture(overrides: Partial<WalkingRouteGuidePoint>): WalkingRouteGuidePoint {
  return {
    ...guide(null),
    ...overrides,
    point: overrides.point ?? guide().point
  };
}

function promptAt(distanceM: number, speedMps: number | null): NavigationGuidePrompt | null {
  return buildNavigationGuidePrompt({
    guide: guide(),
    distanceM,
    speedMps,
    routeId: "test-route"
  });
}

function testTurnTypeFallbackUsesRightTurn() {
  const prompt = buildNavigationGuidePrompt({
    guide: { ...guide(null), turn_type: 13 },
    distanceM: 3,
    speedMps: 1.2,
    routeId: "test-route"
  });

  assert(prompt?.prompt === "지금 우회전하세요.", "turn_type should drive speech when instruction is missing");
}

function testUnknownTurnTypeFallsBackToInstruction() {
  const prompt = buildNavigationGuidePrompt({
    guide: { ...guide("우회전"), turn_type: 999 },
    distanceM: 3,
    speedMps: 1.2,
    routeId: "test-route"
  });

  assert(prompt?.prompt === "지금 우회전하세요.", "unknown turn_type should fall back to instruction text");
}

function testMoveOnlyGuideIsNotSpoken() {
  const prompt = buildNavigationGuidePrompt({
    guide: { ...guide("73m 이동"), turn_type: 200, point_type: "SP" },
    distanceM: 0,
    speedMps: 1.2,
    routeId: "test-route"
  });

  assert(prompt === null, "start or move-only guide point should not create a turn prompt");
}

function testOfflineTimingFixturesCoverActionBoundaries() {
  const leftNow = buildNavigationGuidePrompt({
    guide: guideFixture({ index: 11, instruction: "좌회전", turn_type: null }),
    distanceM: WALKSAFE_GUIDE_TURN_RADIUS_M,
    speedMps: 1.2,
    routeId: "offline-fixture-route"
  });
  assert(leftNow?.stage === "now", "left turn fixture should speak now at turn radius boundary");
  assert(leftNow.prompt === "지금 좌회전하세요.", "left turn fixture should use now speech");

  const rightSoon = buildNavigationGuidePrompt({
    guide: guideFixture({ index: 12, instruction: null, turn_type: 13 }),
    distanceM: WALKSAFE_GUIDE_SOON_RADIUS_M,
    speedMps: null,
    routeId: "offline-fixture-route"
  });
  assert(rightSoon?.stage === "soon3", "right turn fixture should speak soon at soon radius boundary without speed");
  assert(rightSoon.prompt === "곧 우회전입니다. 약 12보 앞입니다.", "right turn fixture should use step-only soon speech");

  const crosswalkPrepare = buildNavigationGuidePrompt({
    guide: guideFixture({ index: 13, instruction: null, turn_type: 211 }),
    distanceM: 12,
    speedMps: 1.2,
    routeId: "offline-fixture-route"
  });
  assert(crosswalkPrepare?.stage === "prepare10", "crosswalk fixture should speak prepare at 10 second ETA boundary");
  assert(crosswalkPrepare.prompt === "10초 뒤 횡단보도입니다. 약 18보 앞입니다.", "crosswalk fixture should use crossing speech");

  const destinationPrepare = buildNavigationGuidePrompt({
    guide: guideFixture({ index: 14, instruction: null, turn_type: 201 }),
    distanceM: WALKSAFE_DEFAULT_STEP_LENGTH_M * 20,
    speedMps: null,
    routeId: "offline-fixture-route"
  });
  assert(destinationPrepare?.stage === "prepare10", "destination fixture should speak at 20 step boundary without speed");
  assert(destinationPrepare.prompt === "약 20보 앞에 목적지입니다.", "destination fixture should use arrival step speech");
}

function testSpeedEstimateClampsOutlier() {
  const previous = { point: point(37.0, 127.0), observedAtMs: 0, accuracyM: 5 };
  const next = { point: point(37.001, 127.0), observedAtMs: 1000, accuracyM: 5 };

  assert(estimateWalkingSpeedMps(previous, next) === null, "large GPS jump should be ignored");
}

function testSpeedEstimateUsesValidSample() {
  const previous = { point: point(37.0, 127.0), observedAtMs: 0, accuracyM: 5 };
  const next = { point: point(37.00001, 127.0), observedAtMs: 1000, accuracyM: 5 };

  const speed = estimateWalkingSpeedMps(previous, next);
  assert(speed !== null && speed > 1 && speed < 1.2, "valid GPS movement should estimate walking speed");
}

function testTenSecondBoundaryPromptUsesSecondsAndSteps() {
  const prompt = promptAt(12, 1.2);

  assert(prompt?.stage === "prepare10", "ETA exactly 10 seconds should create prepare prompt");
  assert(prompt.prompt.includes("10초 뒤 좌회전 준비"), "prepare prompt should prefer seconds");
  assert(prompt.prompt.includes("보 앞"), "prepare prompt should include steps");
}

function testSoonBoundaryBeatsPreparePrompt() {
  const prompt = promptAt(4.8, 1.6);

  assert(prompt?.stage === "soon3", "ETA exactly 3 seconds should create soon prompt when outside turn radius");
  assert(prompt.prompt.includes("곧 좌회전"), "soon prompt should be friendly");
}

function testNowBoundaryBeatsSoonPrompt() {
  const prompt = promptAt(4, 1.6);

  assert(prompt?.stage === "now", "turn radius boundary should use now prompt before soon prompt");
  assert(prompt.prompt === "지금 좌회전하세요.", "turn prompt should be immediate");
}

function testDefaultStepLengthPolicyUses065m() {
  const prompt = promptAt(13, null);

  assert(prompt?.stage === "prepare10", "20 default steps should create prepare prompt without speed");
  assert(prompt.steps === 20, "default step length should be 0.65m when env is unset or invalid");
  assert(prompt.prompt === "약 20보 앞에서 좌회전.", "step fallback prompt should use default step count");
}

function testRouteRequestGateAllowsFirstManualRequest() {
  const gate = resolveNavigationRouteRequestGate({
    requestInFlight: false,
    lastRequestStartedAtMs: null,
    nowMs: 1000
  });

  assert(gate.allowed, "first explicit navigation command should be allowed");
  assert(gate.message === null, "allowed route request should not have a blocking message");
}

function testRouteRequestGateBlocksInFlightRequest() {
  const gate = resolveNavigationRouteRequestGate({
    requestInFlight: true,
    lastRequestStartedAtMs: null,
    nowMs: 1000
  });

  assert(!gate.allowed, "route request in progress should block duplicate live route request");
  assert(gate.message?.includes("이미 경로 요청 중") ?? false, "in-flight guard should explain duplicate route request");
}

function testRouteRequestGateBlocksCooldownRequest() {
  const gate = resolveNavigationRouteRequestGate({
    requestInFlight: false,
    lastRequestStartedAtMs: 1000,
    nowMs: 1000 + WALKSAFE_ROUTE_REQUEST_COOLDOWN_MS - 1000
  });

  assert(!gate.allowed, "manual reroute should respect route request cooldown");
  assert(gate.remainingCooldownMs === 1000, "cooldown gate should report remaining wait time");
  assert(gate.message === "재탐색은 1초 후 다시 시도해 주세요.", "cooldown message should be user-safe");
}

function testRouteRequestGateAllowsAfterCooldown() {
  const gate = resolveNavigationRouteRequestGate({
    requestInFlight: false,
    lastRequestStartedAtMs: 1000,
    nowMs: 1000 + WALKSAFE_ROUTE_REQUEST_COOLDOWN_MS
  });

  assert(gate.allowed, "manual reroute should be allowed after cooldown");
}

function autoRerouteBase(overrides: Partial<Parameters<typeof resolveAutoRerouteDecision>[0]> = {}) {
  return resolveAutoRerouteDecision({
    confirmedOffRoute: true,
    destination: point(37.002, 127.0),
    currentGpsSample: { point: point(37.00001, 127.0), observedAtMs: 2000, accuracyM: 8 },
    previousGpsSample: { point: point(37.0, 127.0), observedAtMs: 1000, accuracyM: 8 },
    requestInFlight: false,
    lastRequestStartedAtMs: null,
    nowMs: 3000,
    autoRerouteCount: 0,
    maxAutoReroutes: 2,
    cooldownMs: 10000,
    maxAccuracyM: 35,
    maxGpsJumpM: 50,
    ...overrides
  });
}

function testAutoRerouteAllowsStableConfirmedOffRoute() {
  const decision = autoRerouteBase();

  assert(decision.allowed, "stable confirmed off-route should allow automatic live reroute");
  assert(decision.reason === "allowed", "allowed auto reroute should expose allowed reason");
}

function testAutoRerouteBlocksPoorGpsAccuracy() {
  const decision = autoRerouteBase({
    currentGpsSample: { point: point(37.00001, 127.0), observedAtMs: 2000, accuracyM: 80 }
  });

  assert(!decision.allowed, "poor GPS accuracy must block automatic live reroute");
  assert(decision.reason === "gps_accuracy_poor", "poor GPS accuracy should expose reason");
}

function testAutoRerouteBlocksGpsJump() {
  const decision = autoRerouteBase({
    currentGpsSample: { point: point(37.002, 127.0), observedAtMs: 2000, accuracyM: 8 }
  });

  assert(!decision.allowed, "GPS jump must block automatic live reroute");
  assert(decision.reason === "gps_jump", "GPS jump should expose reason");
  assert(decision.gpsJumpM !== null && decision.gpsJumpM > 50, "GPS jump distance should be reported");
}

function testAutoRerouteBlocksInFlightCooldownAndMaxCount() {
  assert(
    autoRerouteBase({ requestInFlight: true }).reason === "request_in_flight",
    "in-flight route request should block duplicate auto reroute"
  );
  assert(
    autoRerouteBase({ lastRequestStartedAtMs: 2500, nowMs: 3000 }).reason === "cooldown",
    "recent route request should block auto reroute cooldown"
  );
  assert(
    autoRerouteBase({ autoRerouteCount: 2 }).reason === "max_count",
    "auto reroute should stop after max count"
  );
}


function testRiskVoiceHasPriorityOverNavigationGuidance() {
  const state = resolveVoiceFeedbackState({
    speechEnabled: true,
    riskActive: true,
    navigationSpeechPrompt: "지금 좌회전하세요."
  });

  assert(state === "risk_alert", "active risk should keep risk alert priority over navigation speech");
}

function testNavigationVoicePlaysOnlyWhenNoRisk() {
  const state = resolveVoiceFeedbackState({
    speechEnabled: true,
    riskActive: false,
    navigationSpeechPrompt: "지금 좌회전하세요."
  });

  assert(state === "navigation_guidance", "navigation speech should play when speech is enabled and no risk is active");
}

function testRiskStatusHasPriorityOverNavigationStatus() {
  assert(
    !shouldApplyNavigationStatusMessage("길안내 상태. 경로 안내 중.", true),
    "navigation status should not overwrite active risk status"
  );
  assert(
    shouldApplyNavigationStatusMessage("길안내 상태. 경로 안내 중.", false),
    "navigation status can update when no risk is active"
  );
}

function main() {
  testRiskVoiceHasPriorityOverNavigationGuidance();
  testNavigationVoicePlaysOnlyWhenNoRisk();
  testRiskStatusHasPriorityOverNavigationStatus();
  testReportExportUrlSupportsFormats();
  testReportExportUrlIncludesFilters();
  testReportExportUrlOmitsEmptyFilters();
  testSpeedEstimateClampsOutlier();
  testSpeedEstimateUsesValidSample();
  testTenSecondBoundaryPromptUsesSecondsAndSteps();
  testSoonBoundaryBeatsPreparePrompt();
  testNowBoundaryBeatsSoonPrompt();
  testDefaultStepLengthPolicyUses065m();
  testRouteRequestGateAllowsFirstManualRequest();
  testRouteRequestGateBlocksInFlightRequest();
  testRouteRequestGateBlocksCooldownRequest();
  testRouteRequestGateAllowsAfterCooldown();
  testAutoRerouteAllowsStableConfirmedOffRoute();
  testAutoRerouteBlocksPoorGpsAccuracy();
  testAutoRerouteBlocksGpsJump();
  testAutoRerouteBlocksInFlightCooldownAndMaxCount();
  testTurnTypeFallbackUsesRightTurn();
  testUnknownTurnTypeFallsBackToInstruction();
  testMoveOnlyGuideIsNotSpoken();
  testOfflineTimingFixturesCoverActionBoundaries();
  console.log("navigation guidance, voice priority, and report export policy checks passed");
}

main();
