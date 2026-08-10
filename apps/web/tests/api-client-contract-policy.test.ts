import { existsSync, readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { DETECT_V1_SAFETY_DEADLINE_MS, parseDetectFramePayload, parseDetectHealthPayload } from "../lib/detect-api";
import {
  DETECT_V2_SAFETY_DEADLINE_MS,
  isCompleteDetectV2Frame,
  parseDetectFrameV2Payload
} from "../lib/detect-api-v2";
import { notifyGatewaySessionInvalid } from "../lib/gateway-session-client";
import { parseDestinationSearchPayload, parseWalkingRoutePayload } from "../lib/navigation-api";
import { parseVoiceSttPayload, speechConfidence, voiceExecutionAllowed } from "../lib/voice-api";
import { reportImageUrl, type ReportResponse } from "../lib/report-api";
import type { WalkingRouteRequest } from "../types/navigation";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function assertThrows(callback: () => unknown, message: string) {
  try {
    callback();
  } catch {
    return;
  }
  throw new Error(message);
}

function filesRecursively(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const entryPath = path.join(directory, entry.name);
    return entry.isDirectory() ? filesRecursively(entryPath) : [entryPath];
  });
}

function testDetectV2DeadlineMatchesPublishedResultLifetime() {
  assert(DETECT_V1_SAFETY_DEADLINE_MS === 1800, "legacy detect transport must also abort at its 1.8 second safety deadline");
  assert(DETECT_V2_SAFETY_DEADLINE_MS === 1800, "detect-v2 transport must abort at the 1.8 second safety deadline");
  const legacyHookSource = readFileSync("app/_walksafe/hooks/useDetectionV1.ts", "utf8");
  const hookSource = readFileSync("app/_walksafe/hooks/useDetectionV2.ts", "utf8");
  assert(
    legacyHookSource.includes("DETECT_V1_SAFETY_DEADLINE_MS") && !legacyHookSource.includes("SERVER_V1_STALE_RESULT_MS"),
    "the legacy detection loop and transport must share one safety deadline"
  );
  assert(
    hookSource.includes("DETECT_V2_SAFETY_DEADLINE_MS"),
    "the detection loop and transport must share one safety deadline"
  );
  assert(
    !hookSource.includes("SERVER_V2_STALE_RESULT_MS"),
    "the detection loop must not retain a second independently drifting stale deadline"
  );
}

const capturedAt = "2026-07-11T03:00:00.000Z";
const gps = { latitude: 37.5665, longitude: 126.978, accuracy_m: 8, speed_mps: 1.1 };

function v1Detection(overrides: Record<string, unknown> = {}) {
  return {
    class_id: 0,
    class_name: "damaged_tactile_block",
    confidence: 0.91,
    bbox: { x: 0.1, y: 0.2, width: 0.3, height: 0.4 },
    captured_at: "2026-07-11T03:00:00+00:00",
    source: "server",
    gps: { latitude: gps.latitude, longitude: gps.longitude, accuracy_m: gps.accuracy_m },
    heading: 180,
    ...overrides
  };
}

function v2Detection(overrides: Record<string, unknown> = {}) {
  return {
    schema_version: "detect.v2",
    model_key: "unified_walksafe",
    source_model: "walksafe/model-v1",
    model_class_id: 8,
    class_name: "damaged_tactile_block",
    category: "tactile_damage",
    confidence: 0.91,
    bbox: { x: 0.1, y: 0.2, width: 0.3, height: 0.4 },
    distance_m: null,
    distance_source: null,
    distance_confidence: null,
    approach_state: null,
    threshold_used: 0.7,
    captured_at: "2026-07-11T03:00:00+00:00",
    gps: { latitude: gps.latitude, longitude: gps.longitude, accuracy_m: gps.accuracy_m },
    heading: 180,
    ...overrides
  };
}

function testDetectParsersFailClosed() {
  const context = { captured_at: capturedAt, gps, heading: 180 };
  const parsedV1 = parseDetectFramePayload(
    {
      model_status: "ready",
      model_version: "model-v1",
      detections: [v1Detection()]
    },
    context
  );
  assert(parsedV1?.detections.length === 1, "a valid v1 frame should parse");
  assert(parsedV1.detections[0].gps?.speed_mps === 1.1, "v1 must bind the response to the request context");
  assert(
    parseDetectFramePayload(
      {
        model_status: "ready",
        model_version: "model-v1",
        detections: [v1Detection(), v1Detection({ confidence: 1.2 })]
      },
      context
    ) === null,
    "a malformed v1 item must invalidate the complete frame instead of becoming a false no-hazard result"
  );

  const mismatchedV1 = parseDetectFramePayload(
    { model_status: "ready", model_version: "model-v1", detections: [v1Detection({ heading: 181 })] },
    context
  );
  assert(mismatchedV1 === null, "v1 must reject a frame whose heading differs from the submitted frame context");

  const parsedV2 = parseDetectFrameV2Payload(
    {
      schema_version: "detect.v2",
      detections: [
        v2Detection(),
        v2Detection({ confidence: -0.1 }),
        v2Detection({ model_class_id: -1 }),
        v2Detection({ model_class_id: 10_000 }),
        v2Detection({ source_model: "https://example.com/model.pt" }),
        v2Detection({ source_model: "fake-unified-walksafe-v2" }),
        v2Detection({ distance_confidence: 1.1 }),
        v2Detection({ confidence: 0.6, threshold_used: 0.7 }),
        v2Detection({ gps: { latitude: 0, longitude: 0, accuracy_m: 8 } })
      ]
    },
    context
  );
  assert(parsedV2?.detections.length === 1, "v2 must reject invalid ranges and mismatched frame context");
  assert(parsedV2.audit.parser_drop_count === 8, "v2 parser audit must count every rejected item");
  assert(!isCompleteDetectV2Frame(parsedV2), "a partially parsed v2 frame must not be published as a complete safety result");
  const mismatchedUnifiedClass = parseDetectFrameV2Payload(
    {
      schema_version: "detect.v2",
      detections: [v2Detection({ model_class_id: 7, class_name: "damaged_tactile_block" })]
    },
    context
  );
  assert(
    mismatchedUnifiedClass?.audit.parser_drop_reasons[0]?.reason === "model_class_contract_mismatch" &&
      !isCompleteDetectV2Frame(mismatchedUnifiedClass),
    "unified model class ID/name/category mismatches must fail the 13-class runtime contract"
  );

  assert(parseDetectHealthPayload({ model_status: "ready", model_version: "model-v1", reason: null })?.model_status === "ready", "ready health requires a model version");
  assert(parseDetectHealthPayload({ model_status: "ready", model_version: "", reason: null }) === null, "ready health must reject an empty model version");
  assert(parseDetectHealthPayload({ model_status: "unavailable", model_version: null, reason: "model_not_configured" })?.model_status === "unavailable", "unavailable health requires an explicit reason");
  assert(parseDetectHealthPayload({ model_status: "unavailable", model_version: null, reason: "" }) === null, "unavailable health must reject an empty reason");
}

const walkingRouteContractText = readFileSync("../../contracts/fixtures/walking-route-v1.json", "utf8");
const walkingRouteContract = JSON.parse(walkingRouteContractText) as {
  request: WalkingRouteRequest;
  response: Record<string, unknown>;
};
const routeRequest = walkingRouteContract.request;

function routePayload(overrides: Record<string, unknown> = {}) {
  return {
    ...(JSON.parse(walkingRouteContractText) as { response: Record<string, unknown> }).response,
    ...overrides
  };
}

function testNavigationParserFailClosed() {
  const route = parseWalkingRoutePayload(routePayload(), routeRequest);
  assert(route.summary.distance_m === 120, "valid route should preserve its summary");
  assert(route.steps[0]?.turn_type === 11, "Web must consume the backend-generated numeric TMAP turn type");
  assert(route.steps[0]?.facility_type === 15, "Web must consume the backend-generated numeric facility type");
  assertThrows(
    () => parseWalkingRoutePayload(routePayload({ provider_result_code: 1 }), routeRequest),
    "non-success provider result must be rejected"
  );
  assertThrows(
    () => parseWalkingRoutePayload(routePayload({ provider: "kakao_mobility" }), routeRequest),
    "non-TMAP walking-route providers must be rejected"
  );
  assertThrows(
    () => parseWalkingRoutePayload(routePayload({ polyline: [{ latitude: 0, longitude: 0 }, routeRequest.destination] }), routeRequest),
    "route geometry disconnected from the requested origin must be rejected"
  );
  assertThrows(
    () => parseWalkingRoutePayload(routePayload({ summary: { distance_m: -1, duration_s: 100 } }), routeRequest),
    "negative route summary must be rejected"
  );
  assertThrows(() => parseWalkingRoutePayload(routePayload({ steps: [] }), routeRequest), "route without a walking step must be rejected");
  assertThrows(() => parseWalkingRoutePayload(routePayload({ guide_points: [] }), routeRequest), "route without an actionable guide point must be rejected");
  assertThrows(
    () =>
      parseWalkingRoutePayload(
        routePayload({
          guide_points: [
            {
              index: 0,
              point: { latitude: 0, longitude: 0 },
              instruction: "좌회전",
              turn_type: 12,
              point_type: "GP",
              facility_type: null,
              distance_from_start_m: 60,
              remaining_distance_m: 60
            }
          ]
        }),
        routeRequest
      ),
    "a guide point far away from the TMAP polyline must be rejected"
  );
  assertThrows(
    () =>
      parseWalkingRoutePayload(
        routePayload({
          guide_points: [
            {
              index: 0,
              point: { latitude: 37.0008, longitude: 127 },
              instruction: "직진",
              turn_type: null,
              point_type: "GP",
              facility_type: null,
              distance_from_start_m: 96,
              remaining_distance_m: 24
            },
            {
              index: 1,
              point: { latitude: 37.0002, longitude: 127 },
              instruction: "좌회전",
              turn_type: 12,
              point_type: "GP",
              facility_type: null,
              distance_from_start_m: 24,
              remaining_distance_m: 96
            }
          ]
        }),
        routeRequest
      ),
    "TMAP guide points that move backward along the route must be rejected"
  );

  const destinations = parseDestinationSearchPayload(
    {
      schema_version: "walksafe.destination_search.v1",
      provider: "tmap_poi",
      query: "서울역",
      results: [
        {
          id: "poi-1",
          name: "서울역",
          point: { latitude: 37.5547, longitude: 126.9706, name: "서울역" },
          address: null,
          road_address: "서울 중구 한강대로 405",
          category: "교통",
          result_type: "poi",
          distance_m: 120
        }
      ]
    },
    " 서울역 ",
    5
  );
  assert(destinations.results.length === 1, "valid destination response should parse");
  assertThrows(
    () => parseDestinationSearchPayload({ ...destinations, query: "다른 검색어" }, "서울역", 5),
    "destination response query must match the request context"
  );
  assertThrows(
    () =>
      parseDestinationSearchPayload(
        {
          ...destinations,
          results: [
            destinations.results[0],
            { ...destinations.results[0], name: "동명이지만 다른 후보" }
          ]
        },
        "서울역",
        5
      ),
    "duplicate destination IDs must be rejected before numbered selection can target the wrong candidate"
  );
}

function executableVoicePayload(overrides: Record<string, unknown> = {}) {
  return {
    transcript: "신고해",
    normalized: "신고해",
    intent: "create_report",
    confidence: 0.9,
    score: 0.9,
    acoustic_confidence: 0.75,
    avg_logprob: -0.2,
    no_speech_probability: 0.05,
    acoustic_execution_allowed: true,
    slots: {},
    action: "execute",
    should_execute: true,
    reason: null,
    prompt: null,
    language: "ko",
    duration_sec: 0.5,
    model: "medium",
    segments: [],
    ...overrides
  };
}

function testVoiceParserFailClosed() {
  const executable = parseVoiceSttPayload(executableVoicePayload());
  assert(executable.should_execute === true, "coherent explicit execute policy should parse");
  assert(voiceExecutionAllowed(executable), "execution requires explicit passing acoustic evidence");
  assert(
    parseVoiceSttPayload(
      executableVoicePayload({
        transcript: "다음 경로 뭐야",
        normalized: "다음 경로 뭐야",
        intent: "next_navigation_instruction"
      })
    ).intent === "next_navigation_instruction",
    "the next-instruction query must remain an executable typed intent"
  );
  assert(
    parseVoiceSttPayload(
      executableVoicePayload({
        transcript: "목적지 취소",
        normalized: "목적지 취소",
        intent: "cancel_destination"
      })
    ).intent === "cancel_destination",
    "destination cancellation must remain an executable typed intent"
  );
  assert(
    parseVoiceSttPayload(
      executableVoicePayload({
        transcript: "목적지 변경",
        normalized: "목적지 변경",
        intent: "set_destination",
        confidence: 0.6,
        score: 0.6,
        slots: {},
        action: "reprompt",
        should_execute: false,
        reason: "missing_destination",
        prompt: "변경할 목적지를 다시 말씀해 주세요."
      })
    ).action === "reprompt",
    "a destination-change reprompt must parse without an executable destination slot"
  );
  assert(speechConfidence({ confidence: Number.NaN, score: 0.9 }) === 0, "NaN confidence must fail closed even when score is present");
  assert(speechConfidence({ confidence: Number.POSITIVE_INFINITY, score: 0.9 }) === 0, "infinite confidence must fail closed");
  assert(speechConfidence({ score: 0.9 }) === 0.9, "a valid score may be used when confidence is absent");
  assertThrows(
    () => parseVoiceSttPayload(executableVoicePayload({ action: "reprompt" })),
    "action and should_execute must agree"
  );
  assertThrows(
    () => parseVoiceSttPayload(executableVoicePayload({ confidence: Number.NaN })),
    "non-finite confidence must be rejected"
  );
  assertThrows(
    () => parseVoiceSttPayload(executableVoicePayload({ intent: "unknown" })),
    "unknown intent must never be executable"
  );
  assertThrows(
    () => parseVoiceSttPayload(executableVoicePayload({ acoustic_execution_allowed: false })),
    "an executable policy must fail closed when acoustic evidence is rejected"
  );
  assertThrows(
    () => parseVoiceSttPayload(executableVoicePayload({ no_speech_probability: null })),
    "an executable policy must include no-speech evidence"
  );
  assertThrows(() => parseVoiceSttPayload(executableVoicePayload({ transcript: "" })), "empty transcript must be rejected");
  assertThrows(() => parseVoiceSttPayload(executableVoicePayload({ prompt: "x".repeat(301) })), "oversized prompt must be rejected");
}

function testSessionInvalidationAndLifecycleGuards() {
  const eventTarget = new EventTarget();
  let invalidations = 0;
  eventTarget.addEventListener("walksafe-session-invalid", () => {
    invalidations += 1;
  });
  const hadWindow = Reflect.has(globalThis, "window");
  const previousWindow = Reflect.get(globalThis, "window");
  Reflect.set(globalThis, "window", eventTarget);
  try {
    notifyGatewaySessionInvalid({ status: 403 });
    notifyGatewaySessionInvalid({ status: 401 });
  } finally {
    if (hadWindow) {
      Reflect.set(globalThis, "window", previousWindow);
    } else {
      Reflect.deleteProperty(globalThis, "window");
    }
  }
  assert(invalidations === 1, "only HTTP 401 should invalidate the gateway session");
  assert(
    reportImageUrl({ image_path: "https://tracker.example/private.jpg" } as ReportResponse) === "/api/uploads/invalid",
    "report images must not bypass the same-origin upload gateway"
  );

  for (const relativePath of ["lib/detect-api.ts", "lib/detect-api-v2.ts", "lib/navigation-api.ts", "lib/voice-api.ts", "lib/report-api.ts", "lib/report-api-v2.ts"]) {
    const source = readFileSync(path.join(process.cwd(), relativePath), "utf8");
    assert(source.includes("notifyGatewaySessionInvalid(response)"), `${relativePath} must forward HTTP 401 to the session gate`);
    assert(!source.includes("NEXT_PUBLIC_API_BASE_URL"), `${relativePath} must not allow a public backend URL override`);
    assert(!source.includes("NEXT_PUBLIC_VOICE_API_BASE"), `${relativePath} must not allow a public voice URL override`);
  }
  const cameraSource = readFileSync(path.join(process.cwd(), "app/_walksafe/hooks/useCamera.ts"), "utf8");
  assert(cameraSource.indexOf("await video.play()") < cameraSource.lastIndexOf("cameraGenerationRef.current !== requestGeneration"), "camera must re-check its generation after play resolves");
  const depthSource = readFileSync(path.join(process.cwd(), "app/_walksafe/hooks/useDepthSensor.ts"), "utf8");
  assert(depthSource.indexOf('await xr.requestSession("immersive-ar", DEPTH_SESSION_INIT)') < depthSource.indexOf("sessionGenerationRef.current !== requestGeneration"), "late XR sessions must be rejected by generation");
  const riskFeedbackSource = readFileSync(path.join(process.cwd(), "app/_walksafe/hooks/useRiskFeedback.ts"), "utf8");
  const sequentialRiskFeedbackSource = readFileSync(
    path.join(process.cwd(), "app/_walksafe/hooks/useSequentialRiskFeedback.ts"),
    "utf8"
  );
  const riskFeedbackSequencerSource = readFileSync(
    path.join(process.cwd(), "app/_walksafe/risk-feedback-sequencer.ts"),
    "utf8"
  );
  assert(!riskFeedbackSource.includes("formatBBoxTtcStatusText"), "bbox heuristic TTC must not be composed into spoken feedback");
  assert(!riskFeedbackSource.includes("modelEstimateForRiskContext"), "monocular metric distance must not be composed into spoken feedback");
  const reportV2Source = readFileSync(path.join(process.cwd(), "lib/report-api-v2.ts"), "utf8");
  assert(reportV2Source.includes("REPORT_UPLOAD_TIMEOUT_MS"), "report upload must have a bounded timeout");
  const reportSource = readFileSync(path.join(process.cwd(), "lib/report-api.ts"), "utf8");
  assert(reportSource.includes("REPORT_API_TIMEOUT_MS"), "legacy report and admin API requests must have a bounded client timeout");
  const detectProxySource = readFileSync(path.join(process.cwd(), "app/api/detect/v2/route.ts"), "utf8");
  assert(detectProxySource.includes("fetchBackend(request,"), "detect proxy must use the bounded, cancellation-aware backend fetch");
  for (const proxyPath of [
    "app/api/navigation/walking/route.ts",
    "app/api/navigation/destinations/search/route.ts"
  ]) {
    assert(!existsSync(path.join(process.cwd(), proxyPath)), `${proxyPath} must remain extracted from Next runtime`);
  }
  const apiRoutePaths = filesRecursively(path.join(process.cwd(), "app/api")).filter((filePath) => filePath.endsWith("route.ts"));
  for (const routePath of apiRoutePaths) {
    const source = readFileSync(routePath, "utf8");
    assert(!source.includes("await fetch("), `${path.relative(process.cwd(), routePath)} must not bypass the shared upstream deadline`);
    if (source.includes("backendUrl(") || source.includes("voiceUrl(")) {
      assert(source.includes("fetchBackend(request,"), `${path.relative(process.cwd(), routePath)} must use fetchBackend for every upstream request`);
    }
  }
  const detectorConfigSource = readFileSync(path.join(process.cwd(), "app/_walksafe/config.ts"), "utf8");
  assert(
    detectorConfigSource.includes('process.env.NEXT_PUBLIC_DETECTOR_MODE ?? "server-v2"'),
    "an omitted detector mode must use the real v2 backend instead of silently producing fake safety output"
  );
  const gatewaySessionHookSource = readFileSync(path.join(process.cwd(), "app/_walksafe/hooks/useGatewaySession.ts"), "utf8");
  assert(
    gatewaySessionHookSource.includes("GATEWAY_SESSION_REQUEST_TIMEOUT_MS") &&
      !gatewaySessionHookSource.includes("await fetch(endpoint"),
    "gateway authentication checks and mutations must not wait forever"
  );
  const telemetryHookSource = readFileSync(path.join(process.cwd(), "app/_walksafe/hooks/useFieldTestTelemetry.ts"), "utf8");
  assert(
    telemetryHookSource.includes("FIELD_TELEMETRY_REQUEST_TIMEOUT_MS") &&
      telemetryHookSource.includes('eventType === "heartbeat" && pendingTelemetryRequests.size > 0'),
    "field telemetry must bound each request and avoid accumulating overlapping heartbeats"
  );
  const detectionV2Source = readFileSync(path.join(process.cwd(), "app/_walksafe/hooks/useDetectionV2.ts"), "utf8");
  const pageSource = readFileSync(path.join(process.cwd(), "app/page.tsx"), "utf8");
  assert(
    pageSource.includes("risk_active: riskActive") &&
      pageSource.includes("non_metric_advisory_active: nonMetricAdvisoryActive") &&
      pageSource.includes("non_metric_advisory_tier: nonMetricAdvisoryTier") &&
      pageSource.includes("non_metric_advisory_direction: nonMetricAdvisoryDirection") &&
      pageSource.includes("non_metric_advisory_consecutive_frames: nonMetricAdvisoryConsecutiveFrames") &&
      pageSource.includes("non_metric_advisory_stable_ms: nonMetricAdvisoryStableMs") &&
      pageSource.includes("non_metric_advisory_max_gps_accuracy_m: WALKSAFE_GUIDANCE_MAX_GPS_ACCURACY_M") &&
      pageSource.includes("non_metric_advisory_metric: false") &&
      pageSource.includes("non_metric_advisory_tmap_authoritative: true") &&
      pageSource.includes("non_metric_advisory_reports_allowed: false"),
    "field telemetry must preserve advisory activity and authority separately from riskActive"
  );
  assert(
    riskFeedbackSource.includes("nonMetricAdvisoryTier: activeNonMetricAdvisory?.tier ?? null") &&
      riskFeedbackSource.includes("nonMetricAdvisoryDirection: activeNonMetricAdvisory?.direction ?? null") &&
      riskFeedbackSource.includes("nonMetricAdvisoryConsecutiveFrames: activeNonMetricAdvisory?.continuity.consecutiveFrames ?? null") &&
      riskFeedbackSource.includes("nonMetricAdvisoryStableMs: activeNonMetricAdvisory?.continuity.stableMs ?? null"),
    "the active non-metric advisory must expose its tier, direction, and continuity evidence"
  );
  assert(
    !riskFeedbackSource.includes("vibration: advisory.vibration"),
    "low non-metric advisories must not transfer haptic authority into the serialized feedback queue"
  );
  const fieldTelemetryRouteSource = readFileSync(path.join(process.cwd(), "app/api/walksafe-field-log/route.ts"), "utf8");
  const runtimeSourceIdentitySource = readFileSync(
    path.join(process.cwd(), "app/api/_runtime-source-identity.ts"),
    "utf8"
  );
  assert(
    fieldTelemetryRouteSource.includes("verifiedRuntimeSourceCommit()") &&
      fieldTelemetryRouteSource.includes("server_source_commit: serverSourceCommit") &&
      fieldTelemetryRouteSource.includes("field_log_source_identity_not_ready") &&
      runtimeSourceIdentitySource.includes('join(cwd, "BUILD_ID")') &&
      runtimeSourceIdentitySource.includes('join(cwd, distDirectory, "BUILD_ID")') &&
      runtimeSourceIdentitySource.includes('distDirectory !== ".next"') &&
      runtimeSourceIdentitySource.includes("present.every((candidate) => candidate.value === configuredCommit)"),
    "field telemetry must bind production records to the exact matching server build identity"
  );
  assert(
    detectionV2Source.includes('void latestSubmitReportRef.current(autoReportGate.target, "auto"'),
    "automatic report upload must not block the next detection frame"
  );
  assert(
    detectionV2Source.indexOf("latestInferenceFrameCallbackRef.current?.(depthDetections, capturedAt)") <
      detectionV2Source.indexOf("publishedPresence = advanceDetectionPresence") &&
      pageSource.includes("onInferenceFrame: recordNonMetricInferenceFrame") &&
      pageSource.includes("nonMetricInferenceFrame,"),
    "raw empty inference frames must reset non-metric continuity before the UI hold policy can preserve an old detection"
  );
  const autoReportHookSource = readFileSync(path.join(process.cwd(), "app/_walksafe/hooks/useAutoReportV2.ts"), "utf8");
  assert(autoReportHookSource.includes("resetAutoReportV2Session"), "logout must have an explicit auto-report session reset");
  assert(
    autoReportHookSource.includes("sessionStorage.removeItem(AUTO_REPORT_COOLDOWN_STORAGE_KEY)"),
    "auto-report cooldown must not leak to the next actor on a shared device"
  );
  assert(
    !detectionV2Source.includes('await latestSubmitReportRef.current(autoReportGate.target, "auto"'),
    "detection loop must never await automatic report network completion"
  );
  const voiceHookSource = readFileSync(path.join(process.cwd(), "app/_walksafe/hooks/useVoiceCommands.ts"), "utf8");
  assert(voiceHookSource.includes("voiceSessionSequenceRef.current !== sessionSequence"), "late STT results must be generation-guarded");
  assert(voiceHookSource.includes("voiceUploadAbortRef.current?.abort()"), "cancelled voice sessions must abort pending STT uploads");
  assert(voiceHookSource.includes("executeNavigationVoiceIntent(result,"), "accepted navigation intents must use the executable dispatch layer");
  assert(voiceHookSource.includes('intent === "repeat_last"'), "repeat-last must remain wired to browser TTS");
  assert(voiceHookSource.includes("setSpeechRecognitionActive(true)"), "STT recording must suppress non-risk TTS");
  assert(voiceHookSource.includes("WALKSAFE_URGENT_SPEECH_EVENT"), "risk speech must interrupt recording or pending STT");
  const feedbackSource = readFileSync(path.join(process.cwd(), "app/_walksafe/feedback.ts"), "utf8");
  assert(feedbackSource.includes("shouldBlockSpeechDuringRecognition"), "only risk TTS may preempt an active STT session");
  assert(feedbackSource.includes("shouldPreemptSpeech"), "browser TTS must use the central speech priority policy");
  assert(riskFeedbackSource.includes('speak(navigationSpeechPrompt, "navigation", {'), "route TTS must declare navigation priority");
  assert(sequentialRiskFeedbackSource.includes('speechPayload.speechPriority ?? "risk"'), "hazard and unavailable TTS must default to risk priority");
  assert(riskFeedbackSource.includes('speechPriority: "advisory"'), "camera-only non-metric TTS must stay below TMAP speech priority");
  assert(
    sequentialRiskFeedbackSource.includes(
      "shouldStopSpeechOwnedByDelivery(getActiveSpeechPriority(), active.payload.speechPriority)"
    ),
    "cancelling a stale advisory retry must not stop TMAP speech owned by another priority"
  );
  assert(sequentialRiskFeedbackSource.includes("sequenceRef"), "hazard TTS must not repeatedly preempt an accepted pending warning");
  assert(sequentialRiskFeedbackSource.includes("advanceRiskFeedbackFailure"), "hazard TTS failures must use a bounded terminal retry policy");
  assert(riskFeedbackSequencerSource.includes('outcome: "preempted"'), "a severity escalation must preempt an older active warning");
  assert(riskFeedbackSequencerSource.includes("RISK_FEEDBACK_SEQUENCE_CAPACITY = 3"), "equal-severity warnings must use a small bounded queue");
  assert(
    riskFeedbackSource.includes("Date.parse(detection.captured_at)") &&
      riskFeedbackSource.includes("Date.parse(candidate.item.captured_at)"),
    "risk queue freshness must come from the current detector frame instead of unrelated rerenders"
  );
  assert(
    sequentialRiskFeedbackSource.includes('document.addEventListener("visibilitychange", stopWhenHidden)') &&
      sequentialRiskFeedbackSource.includes('window.addEventListener("pagehide", stopOnPageHide)') &&
      sequentialRiskFeedbackSource.includes('document.removeEventListener("visibilitychange", stopWhenHidden)') &&
      sequentialRiskFeedbackSource.includes('window.removeEventListener("pagehide", stopOnPageHide)') &&
      sequentialRiskFeedbackSource.includes("clearForLifecycle(false);"),
    "hidden, pagehide, and unmount lifecycles must clear queued risk output and stop owned speech"
  );
  const lifecycleCancellationCalls = sequentialRiskFeedbackSource.match(/active\?\.payload\.observer\?\.onCancel\?\.\(\)/g) ?? [];
  assert(
    sequentialRiskFeedbackSource.includes("clearForLifecycle(true)") &&
      sequentialRiskFeedbackSource.includes("notifyActiveCancellation && mountedRef.current && ownedRiskSpeech") &&
      lifecycleCancellationCalls.length === 1,
    "hidden/pagehide cancellation must restore the active availability alert fallback exactly once while unmount stays silent"
  );
  const availabilityCancelIndex = riskFeedbackSource.indexOf("onCancel: () => updateDetectionSafetyDelivery");
  const availabilityCancelBlock = riskFeedbackSource.slice(availabilityCancelIndex, availabilityCancelIndex + 240);
  assert(
    availabilityCancelIndex >= 0 && availabilityCancelBlock.includes('"pending"') &&
      riskFeedbackSource.includes('detectionSafetyAlert.delivery !== "spoken"'),
    "a lifecycle-cancelled availability warning must return to the visible fallback-required state"
  );
  const vibrationActionIndex = sequentialRiskFeedbackSource.indexOf("vibrate(payload.vibration ?? null)");
  const speechActionIndex = sequentialRiskFeedbackSource.indexOf("speak(speechPayload.message, speechPayload.speechPriority");
  assert(
    vibrationActionIndex >= 0 && speechActionIndex > vibrationActionIndex,
    "risk vibration and speech must start inside one serialized action boundary"
  );
  assert(
    riskFeedbackSource.includes("for (const candidate of evaluatedV2Candidates)"),
    "simultaneous alertable detections must all enter bounded sequential arbitration"
  );
  assert(riskFeedbackSource.includes("DetectionSafetyAlertState"), "detector outages must use render-visible managed risk delivery");
  assert(riskFeedbackSource.includes("detectionSafetyAlertMessage"), "detector outages must remain available to the live fallback");
  assert(riskFeedbackSource.includes("detectionSafetyFallbackRequired"), "other successful speech must not hide an unresolved detector outage fallback");
  assert(riskFeedbackSource.includes("navigationSpeechTransitionRef"), "a re-entered navigation safety state must bypass historical cooldown");
  assert(riskFeedbackSource.includes("WALKSAFE_SPEECH_ARBITRATION_STATUS_EVENT"), "route TTS must resume after a longer interaction finishes");
  const sensorHookSource = readFileSync(path.join(process.cwd(), "app/_walksafe/hooks/useSensors.ts"), "utf8");
  const gpsErrorBlock = sensorHookSource.slice(sensorHookSource.indexOf("(error) => {"), sensorHookSource.indexOf("enableHighAccuracy: true"));
  assert(gpsErrorBlock.includes("setGps(null)"), "geolocation errors must clear a stale last-known fix");
  assert(sensorHookSource.includes("gpsFixExpiryDelayMs"), "geolocation must expire a silently stale watchPosition fix");
  assert(sensorHookSource.includes("!filtered.lastObservationAccepted"), "a rejected GPS jump must not refresh the report location");
}

function main() {
  testDetectV2DeadlineMatchesPublishedResultLifetime();
  testDetectParsersFailClosed();
  testNavigationParserFailClosed();
  testVoiceParserFailClosed();
  testSessionInvalidationAndLifecycleGuards();
  console.log("API client contract policy checks passed");
}

main();
