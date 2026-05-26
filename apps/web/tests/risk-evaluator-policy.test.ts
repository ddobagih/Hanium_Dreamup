import {
  buildBBoxHistoryRiskContext,
  evaluateTwoModelDetectionRisk,
  trackingKeyForDetection,
  type BBoxHistorySample,
  type RiskEvaluationContext
} from "../app/_walksafe/risk-evaluator";
import {
  depthContextFromDetection,
  depthContextFromEstimate,
  mockDepthEstimate,
  resolveBrowserDepthCapability
} from "../app/_walksafe/risk-depth";
import { buildRoiRiskContext, evaluateRoiRisk } from "../app/_walksafe/risk-roi";
import {
  actionForRiskType,
  buildRiskGuidanceMessage,
  directionFromBBox,
  phraseForApproxSteps,
  phraseForBBoxVerticalPosition,
  selectRiskGuidanceCandidate
} from "../app/_walksafe/risk-guidance";
import { parseDetectFrameV2Payload } from "../lib/detect-api-v2";
import type { TwoModelDetection } from "../types/inference-v2";

type DetectionOverrides = Omit<Partial<TwoModelDetection>, "bbox"> & {
  bbox?: Partial<TwoModelDetection["bbox"]>;
};

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

function detection(overrides: DetectionOverrides = {}): TwoModelDetection {
  const bbox = {
    x: 0.2,
    y: 0.2,
    width: 0.2,
    height: 0.2,
    ...(overrides.bbox ?? {})
  };

  return {
    schema_version: "detect.v2",
    model_key: "coco_general",
    source_model: "policy-test",
    model_class_id: 0,
    class_name: "person",
    category: "vulnerable_road_user",
    confidence: 0.82,
    threshold_used: 0.25,
    captured_at: "2026-05-23T06:00:00.000Z",
    gps: null,
    heading: null,
    ...overrides,
    bbox
  };
}

function sample(detectionValue: TwoModelDetection, observedAtMs: number): BBoxHistorySample {
  return { detection: detectionValue, observed_at_ms: observedAtMs };
}

function explicitDepthContextFromDetection(detectionValue: TwoModelDetection): RiskEvaluationContext {
  return depthContextFromDetection(detectionValue);
}

function testDamagedTactileBlockIsReportOnly() {
  const result = evaluateTwoModelDetectionRisk(
    detection({
      model_key: "custom_tactile",
      model_class_id: 1,
      class_name: "damaged_tactile_block",
      category: "tactile_damage",
      confidence: 0.91
    })
  );

  assert(result.reportable, "damaged_tactile_block should be reportable");
  assert(!result.alertable, "damaged_tactile_block should not be user-alertable by default");
  assert(result.risk_type === "report_only_damage", "damaged_tactile_block should stay report_only_damage");
}

function testTactileDamageAreaIsAuxiliaryOnly() {
  const result = evaluateTwoModelDetectionRisk(
    detection({
      model_key: "custom_tactile",
      model_class_id: 2,
      class_name: "tactile_damage_area",
      category: "tactile_damage",
      confidence: 0.91
    })
  );

  assert(!result.reportable, "tactile_damage_area should be auxiliary, not reportable");
  assert(!result.alertable, "tactile_damage_area should not be user-alertable by default");
  assert(result.risk_type === "display_only", "tactile_damage_area should be display_only");
}

function testNormalTactileIsNoRisk() {
  const result = evaluateTwoModelDetectionRisk(
    detection({
      model_key: "custom_tactile",
      model_class_id: 0,
      class_name: "normal_tactile_block",
      category: "tactile_normal"
    })
  );

  assert(!result.reportable, "normal_tactile_block should not be reportable");
  assert(!result.alertable, "normal_tactile_block should not be alertable");
  assert(result.risk_type === "no_risk", "normal_tactile_block should be no_risk");
}

function testGeneralObjectWithoutHistoryIsDisplayOnly() {
  const result = evaluateTwoModelDetectionRisk(detection());

  assert(!result.reportable, "general object should not be reportable");
  assert(!result.alertable, "general object without history should not be alertable");
  assert(result.risk_type === "display_only", "general object without history should be display_only");
}

function testApproachingHistoryRaisesAlert() {
  const first = detection({ bbox: { x: 0.3, y: 0.3, width: 0.14, height: 0.14 }, captured_at: "2026-05-23T06:00:00.000Z" });
  const second = detection({ bbox: { x: 0.28, y: 0.28, width: 0.20, height: 0.20 }, captured_at: "2026-05-23T06:00:01.000Z" });
  const third = detection({ bbox: { x: 0.25, y: 0.25, width: 0.30, height: 0.30 }, captured_at: "2026-05-23T06:00:02.000Z" });
  const context = buildBBoxHistoryRiskContext(third, [sample(first, 0), sample(second, 1000), sample(third, 2000)]);
  const result = evaluateTwoModelDetectionRisk(third, context);

  assert(context.tracking?.stable_frames === 3, "approaching history should have 3 stable frames");
  assert(context.tracking?.approaching === true, "increasing bbox history should mark approaching");
  assert(result.alertable, "approaching general object should be alertable");
  assert(!result.reportable, "approaching general object should not be reportable");
  assert(result.risk_type === "approaching_object", "approaching general object should be approaching_object");
}

function testShortHistoryDoesNotRaiseApproachingAlert() {
  const first = detection({ bbox: { x: 0.3, y: 0.3, width: 0.14, height: 0.14 }, captured_at: "2026-05-23T06:00:00.000Z" });
  const second = detection({ bbox: { x: 0.25, y: 0.25, width: 0.30, height: 0.30 }, captured_at: "2026-05-23T06:00:01.000Z" });
  const context = buildBBoxHistoryRiskContext(second, [sample(first, 0), sample(second, 1000)]);
  const result = evaluateTwoModelDetectionRisk(second, context);

  assert(context.tracking?.stable_frames === 2, "short approaching history should have only 2 stable frames");
  assert(context.tracking?.approaching === false, "short bbox history should not mark approaching");
  assert(!result.alertable, "short bbox history should not be alertable");
  assert(result.risk_type === "display_only", "short bbox history should remain display_only");
}

function testDiscontinuousBBoxHistoryDoesNotRaiseApproachingAlert() {
  const oldSample = detection({ bbox: { x: 0.05, y: 0.05, width: 0.12, height: 0.12 }, captured_at: "2026-05-23T06:00:00.000Z" });
  const second = detection({ bbox: { x: 0.72, y: 0.2, width: 0.20, height: 0.20 }, captured_at: "2026-05-23T06:00:01.000Z" });
  const third = detection({ bbox: { x: 0.7, y: 0.18, width: 0.30, height: 0.30 }, captured_at: "2026-05-23T06:00:02.000Z" });
  const context = buildBBoxHistoryRiskContext(third, [sample(oldSample, 0), sample(second, 1000), sample(third, 2000)]);
  const result = evaluateTwoModelDetectionRisk(third, context);

  assert(context.tracking?.stable_frames === 2, "discontinuous bbox history should restart the stable track");
  assert(context.tracking?.approaching === false, "discontinuous bbox history should not mark approaching");
  assert(!result.alertable, "discontinuous bbox history should not be alertable");
  assert(result.risk_type === "display_only", "discontinuous bbox history should remain display_only");
}

function testPathIntersectionRaisesBlockingAlert() {
  const target = detection({ class_name: "car", model_class_id: 1, category: "vehicle" });
  const result = evaluateTwoModelDetectionRisk(target, {
    tracking: { stable_frames: 3, projected_path_intersection: true },
    segmentation: { path_relation: "on_path" }
  });

  assert(result.alertable, "path-intersecting general object should be alertable");
  assert(!result.reportable, "path-intersecting general object should not be reportable");
  assert(result.risk_type === "blocking_object", "path-intersecting object should be blocking_object");
}

function testTtcBoundaryPolicy() {
  const target = detection({ class_name: "car", model_class_id: 1, category: "vehicle" });
  const boundary = evaluateTwoModelDetectionRisk(target, {
    tracking: { stable_frames: 3, approaching: true, time_to_collision_ms: 3500 }
  });
  const outsideBoundary = evaluateTwoModelDetectionRisk(target, {
    tracking: { stable_frames: 3, approaching: true, time_to_collision_ms: 3501 }
  });

  assert(boundary.alertable, "TTC 3500ms should still alert");
  assert(!outsideBoundary.alertable, "TTC 3501ms without other signals should not alert");
}

function testTrackingKeyAndStaleReset() {
  const first = detection({
    source_model: "model-a",
    bbox: { x: 0.3, y: 0.3, width: 0.14, height: 0.14 },
    captured_at: "2026-05-23T06:00:00.000Z"
  });
  const otherModel = detection({
    source_model: "model-b",
    bbox: { x: 0.28, y: 0.28, width: 0.22, height: 0.22 },
    captured_at: "2026-05-23T06:00:01.000Z"
  });
  const stale = detection({
    source_model: "model-a",
    bbox: { x: 0.26, y: 0.26, width: 0.28, height: 0.28 },
    captured_at: "2026-05-23T06:00:05.000Z"
  });
  const context = buildBBoxHistoryRiskContext(stale, [sample(first, 1000), sample(otherModel, 2000), sample(stale, 5000)]);

  assert(trackingKeyForDetection(first) !== trackingKeyForDetection(otherModel), "tracking key should include source_model");
  assert(context.tracking?.stable_frames === 1, "stale gap should reset bbox history track");
  assert(context.tracking?.approaching === false, "stale reset should not mark approaching");
}

function testJitterConstantAndDecreasingBBoxDoNotApproach() {
  const jitter1 = detection({ bbox: { x: 0.3, y: 0.3, width: 0.2, height: 0.2 }, captured_at: "2026-05-23T06:00:00.000Z" });
  const jitter2 = detection({ bbox: { x: 0.302, y: 0.3, width: 0.205, height: 0.2 }, captured_at: "2026-05-23T06:00:01.000Z" });
  const jitter3 = detection({ bbox: { x: 0.301, y: 0.3, width: 0.198, height: 0.2 }, captured_at: "2026-05-23T06:00:02.000Z" });
  const constant = detection({ bbox: { x: 0.3, y: 0.3, width: 0.2, height: 0.2 }, captured_at: "2026-05-23T06:00:02.000Z" });
  const decreasing = detection({ bbox: { x: 0.32, y: 0.32, width: 0.14, height: 0.14 }, captured_at: "2026-05-23T06:00:02.000Z" });

  const jitterContext = buildBBoxHistoryRiskContext(jitter3, [sample(jitter1, 0), sample(jitter2, 1000), sample(jitter3, 2000)]);
  const constantContext = buildBBoxHistoryRiskContext(constant, [sample(jitter1, 0), sample(jitter1, 1000), sample(constant, 2000)]);
  const decreasingContext = buildBBoxHistoryRiskContext(decreasing, [sample(jitter3, 0), sample(jitter2, 1000), sample(decreasing, 2000)]);

  assert(jitterContext.tracking?.approaching === false, "bbox jitter should not mark approaching");
  assert(constantContext.tracking?.approaching === false, "constant bbox area should not mark approaching");
  assert(decreasingContext.tracking?.approaching === false, "decreasing bbox area should not mark approaching");
}

function testRiskGuidanceHelpers() {
  assert(directionFromBBox({ x: 0.05, y: 0.2, width: 0.2, height: 0.2 }) === "left", "left bbox should map to left");
  assert(directionFromBBox({ x: 0.4, y: 0.2, width: 0.2, height: 0.2 }) === "front", "center bbox should map to front");
  assert(directionFromBBox({ x: 0.72, y: 0.2, width: 0.2, height: 0.2 }) === "right", "right bbox should map to right");
  assert(actionForRiskType("blocking_object") === "멈추세요", "blocking_object should ask stop");
  assert(actionForRiskType("approaching_object") === "피하세요", "approaching_object should ask avoidance");
  assert(actionForRiskType("surface_hazard") === "천천히 이동하세요", "surface_hazard should ask slow movement");
  assert(directionFromBBox({ x: 0.29, y: 0.2, width: 0.2, height: 0.2 }) === "left", "0.39 center should map to left");
  assert(directionFromBBox({ x: 0.3, y: 0.2, width: 0.2, height: 0.2 }) === "front", "0.40 center should map to front");
  assert(directionFromBBox({ x: 0.5, y: 0.2, width: 0.2, height: 0.2 }) === "front", "0.60 center should map to front");
  assert(directionFromBBox({ x: 0.51, y: 0.2, width: 0.2, height: 0.2 }) === "right", "0.61 center should map to right");
  assert(directionFromBBox({ x: 0.9, y: 0.2, width: 0.2, height: 0.2 }) === "unknown", "overflow bbox should be unknown");
  assert(phraseForBBoxVerticalPosition({ x: 0.4, y: 0.72, width: 0.2, height: 0.16 }) === "발밑", "bottom bbox should map to foot phrase");
  assert(phraseForBBoxVerticalPosition({ x: 0.4, y: 0.61, width: 0.2, height: 0.16 }) === "하단", "lower bbox should map to lower phrase");
  assert(phraseForApproxSteps(1.4) === "약 2보 앞", "distance should be converted to approximate steps");
  assert(phraseForApproxSteps(0.2) === "바로 앞", "very close distance should be announced as 바로 앞");
  assert(phraseForApproxSteps(1.4, 0.5) === "약 3보 앞", "custom step length should affect approximate steps");
  assert(phraseForApproxSteps(null, 0.5) === null, "missing distance should not create approximate steps");
}

function testRiskGuidanceMessageIncludesDirectionDistanceAndAction() {
  const message = buildRiskGuidanceMessage({
    riskType: "blocking_object",
    bbox: { x: 0.42, y: 0.2, width: 0.2, height: 0.2 },
    distanceM: 1.4,
    stepLengthM: 0.5,
    label: "사람"
  });

  assert(message === "전방 약 3보 앞 사람. 멈추세요.", "guidance message should include direction, custom steps, label, and action");
}

function testRiskGuidanceMessageOmitsStepsWithoutDistance() {
  const message = buildRiskGuidanceMessage({
    riskType: "blocking_object",
    bbox: { x: 0.42, y: 0.2, width: 0.2, height: 0.2 },
    stepLengthM: 0.5,
    label: "사람"
  });

  assert(message === "전방 사람. 멈추세요.", "guidance message should keep direction/action and omit steps without distance");
}

function testDetectV2ParserPreservesExplicitDistanceOnly() {
  const parsed = parseDetectFrameV2Payload(
    {
      schema_version: "detect.v2",
      detections: [
        detection({ distance_m: 1.4 }),
        detection({ class_name: "car", model_class_id: 1, category: "vehicle" }),
        detection({ class_name: "bicycle", model_class_id: 4, category: "vulnerable_road_user", distance_m: -1 }),
        detection({ class_name: "bus", model_class_id: 5, category: "vehicle", distance_m: 51 }),
        detection({ class_name: "truck", model_class_id: 6, category: "vehicle", bbox: { x: 0.9, width: 0.2 } })
      ]
    },
    { captured_at: "2026-05-23T06:00:00.000Z", gps: null, heading: null }
  );

  assert(parsed !== null, "valid detect v2 payload should parse");
  assert(parsed.detections[0].distance_m === 1.4, "parser should preserve explicit non-negative distance_m");
  assert(parsed.detections[1].distance_m === null, "parser should keep missing distance_m as null");
  assert(parsed.detections[2].distance_m === null, "parser should not preserve negative distance_m");
  assert(parsed.detections[3].distance_m === null, "parser should not preserve unrealistic distance_m");
  assert(!parsed.detections.some((item) => item.class_name === "truck"), "parser should drop invalid overflow bbox");
}

function testDetectV2ParserPreservesTrustedDistanceMetadata() {
  const parsed = parseDetectFrameV2Payload(
    {
      schema_version: "detect.v2",
      detections: [
        detection({ distance_m: 1.4, distance_source: "sensor_depth", distance_confidence: 0.82 }),
        detection({ class_name: "car", model_class_id: 1, category: "vehicle", distance_m: 1.4, distance_source: "bad_source" as never, distance_confidence: 1.5 })
      ]
    },
    { captured_at: "2026-05-23T06:00:00.000Z", gps: null, heading: null }
  );

  assert(parsed !== null, "valid detect v2 payload should parse");
  assert(parsed.detections[0].distance_source === "sensor_depth", "trusted distance source should be preserved");
  assert(parsed.detections[0].distance_confidence === 0.82, "distance confidence should be preserved");
  assert(parsed.detections[1].distance_source === null, "unknown distance source should be dropped");
  assert(parsed.detections[1].distance_confidence === 1, "distance confidence should be clamped to unit interval");
}

function testExplicitDetectionDistanceFeedsDepthGuidance() {
  const target = detection({
    distance_m: 1.4,
    distance_source: "sensor_depth",
    distance_confidence: 0.82,
    bbox: { x: 0.42, y: 0.2, width: 0.2, height: 0.2 }
  });
  const context = explicitDepthContextFromDetection(target);
  const message = buildRiskGuidanceMessage({
    riskType: "blocking_object",
    bbox: target.bbox,
    distanceM: context.depth?.distance_m,
    stepLengthM: 0.5,
    label: "사람"
  });

  assert(context.depth?.distance_m === 1.4, "explicit detection distance_m should become depth context");
  assert(message === "전방 약 3보 앞 사람. 멈추세요.", "explicit distance_m should enable approximate step guidance");
  assert(explicitDepthContextFromDetection(detection()).depth === undefined, "missing distance_m should not create depth context");
}

function testBrowserDepthGateAndPolicy() {
  const disabled = resolveBrowserDepthCapability({ enabled: false, navigatorLike: null });
  const unsupported = resolveBrowserDepthCapability({ enabled: true, navigatorLike: {} as Navigator });
  const ready = resolveBrowserDepthCapability({
    enabled: true,
    navigatorLike: { xr: { isSessionSupported: async () => true } } as unknown as Navigator
  });
  const mock = mockDepthEstimate(1.2, 0.75);
  const context = depthContextFromEstimate(mock);
  const untrustedContext = depthContextFromEstimate({ distance_m: 1.2, source: "model_estimate", confidence: 0.9 });

  assert(disabled.status === "disabled", "browser depth should be disabled by default");
  assert(unsupported.status === "unsupported", "browser depth should be unsupported without WebXR probe");
  assert(ready.status === "ready", "browser depth bridge should be ready when WebXR probe exists");
  assert(context.depth?.source === "manual_fixture", "mock depth should produce manual_fixture context");
  assert(untrustedContext.depth === undefined, "model_estimate should not enable step guidance as trusted depth");
}

function testRoiCenterLowerObjectRaisesBlockingAlert() {
  const target = detection({
    class_name: "bench",
    category: "street_furniture",
    bbox: { x: 0.42, y: 0.56, width: 0.18, height: 0.22 }
  });
  const roi = evaluateRoiRisk(target);
  const result = evaluateTwoModelDetectionRisk(target, buildRoiRiskContext(target));

  assert(roi.pathRelation === "on_path", "center lower bbox should be on_path");
  assert(roi.blockingPath, "center lower bbox should block path");
  assert(result.alertable, "on_path ROI object should be alertable through risk evaluator");
  assert(result.risk_type === "blocking_object", "on_path ROI object should become blocking_object");
}

function testRoiEdgeObjectIsNearPathNotAlertableByItself() {
  const target = detection({
    class_name: "bicycle",
    category: "vulnerable_road_user",
    bbox: { x: 0.14, y: 0.58, width: 0.16, height: 0.22 }
  });
  const roi = evaluateRoiRisk(target);
  const result = evaluateTwoModelDetectionRisk(target, buildRoiRiskContext(target));

  assert(roi.pathRelation === "near_path", "edge lower bbox should be near_path");
  assert(!roi.blockingPath, "near_path bbox should not be blocking by itself");
  assert(!result.alertable, "near_path without approach history should not alert by itself");
}

function testRoiSmallUpperObjectIsOffPath() {
  const target = detection({
    class_name: "person",
    category: "vulnerable_road_user",
    bbox: { x: 0.46, y: 0.08, width: 0.08, height: 0.08 }
  });
  const roi = evaluateRoiRisk(target);

  assert(roi.pathRelation === "off_path", "small upper bbox should be off_path");
  assert(!roi.projectedPathIntersection, "small upper bbox should not intersect projected path");
}

function testRoiDebugMotionAndNonBlockingClasses() {
  const shiftedCenter = detection({
    class_name: "bench",
    category: "street_furniture",
    bbox: { x: 0.29, y: 0.56, width: 0.12, height: 0.24 }
  });
  const withUnknownMotion = evaluateRoiRisk(shiftedCenter, {
    debug: true,
    centerBandMinX: 0.4,
    centerBandMaxX: 0.6,
    motion: { heading_deg: null, route_bearing_deg: 0, walking_speed_mps: null }
  });
  const withMotion = evaluateRoiRisk(shiftedCenter, {
    debug: true,
    centerBandMinX: 0.4,
    centerBandMaxX: 0.6,
    motion: { heading_deg: -90, route_bearing_deg: 0, walking_speed_mps: 1.0 }
  });
  const trafficLight = evaluateRoiRisk(
    detection({
      class_name: "traffic light",
      category: "traffic_signal",
      bbox: { x: 0.42, y: 0.56, width: 0.18, height: 0.22 }
    }),
    { debug: true }
  );

  assert(withUnknownMotion.pathRelation === "near_path", "unknown heading/speed should not shift the ROI center band");
  assert(withUnknownMotion.debug?.motionShiftX === 0, "unknown motion should keep shift at 0");
  assert(withMotion.pathRelation === "on_path", "known motion should project ROI center band within configured shift");
  assert(withMotion.debug?.headingKnown === true && withMotion.debug.speedKnown === true, "debug should expose motion readiness");
  assert(trafficLight.pathRelation === "off_path", "traffic light should be excluded from blocking ROI");
  assert(trafficLight.debug?.excludedByClass === true, "debug should expose non-blocking class exclusion");
}

function testRiskGuidancePriorityAndHighRiskPhrase() {
  const lowConfidenceBlocker = detection({
    class_name: "bench",
    category: "street_furniture",
    confidence: 0.7,
    bbox: { x: 0.42, y: 0.58, width: 0.18, height: 0.24 }
  });
  const highRiskApproacher = detection({
    class_name: "car",
    model_class_id: 1,
    category: "vehicle",
    confidence: 0.81,
    bbox: { x: 0.4, y: 0.2, width: 0.2, height: 0.2 }
  });
  const selected = selectRiskGuidanceCandidate([
    {
      item: lowConfidenceBlocker,
      risk: evaluateTwoModelDetectionRisk(lowConfidenceBlocker, buildRoiRiskContext(lowConfidenceBlocker)),
      context: buildRoiRiskContext(lowConfidenceBlocker),
      confidence: lowConfidenceBlocker.confidence,
      index: 0
    },
    {
      item: highRiskApproacher,
      risk: evaluateTwoModelDetectionRisk(highRiskApproacher, {
        tracking: { stable_frames: 3, approaching: true, time_to_collision_ms: 1500 }
      }),
      context: { tracking: { stable_frames: 3, approaching: true, time_to_collision_ms: 1500 } },
      confidence: highRiskApproacher.confidence,
      index: 1
    }
  ]);
  const phrase = buildRiskGuidanceMessage({
    riskType: "approaching_object",
    riskLevel: "high",
    bbox: highRiskApproacher.bbox,
    label: "차량"
  });

  assert(selected?.item.class_name === "car", "higher risk level candidate should win over lower risk blocker");
  assert(phrase === "전방 차량. 멈추고 피하세요.", "high approaching risk should use stronger action phrase");
}

function main() {
  testDamagedTactileBlockIsReportOnly();
  testTactileDamageAreaIsAuxiliaryOnly();
  testNormalTactileIsNoRisk();
  testGeneralObjectWithoutHistoryIsDisplayOnly();
  testApproachingHistoryRaisesAlert();
  testShortHistoryDoesNotRaiseApproachingAlert();
  testDiscontinuousBBoxHistoryDoesNotRaiseApproachingAlert();
  testPathIntersectionRaisesBlockingAlert();
  testTtcBoundaryPolicy();
  testTrackingKeyAndStaleReset();
  testJitterConstantAndDecreasingBBoxDoNotApproach();
  testRiskGuidanceHelpers();
  testRiskGuidanceMessageIncludesDirectionDistanceAndAction();
  testRiskGuidanceMessageOmitsStepsWithoutDistance();
  testDetectV2ParserPreservesExplicitDistanceOnly();
  testDetectV2ParserPreservesTrustedDistanceMetadata();
  testExplicitDetectionDistanceFeedsDepthGuidance();
  testBrowserDepthGateAndPolicy();
  testRoiCenterLowerObjectRaisesBlockingAlert();
  testRoiEdgeObjectIsNearPathNotAlertableByItself();
  testRoiSmallUpperObjectIsOffPath();
  testRoiDebugMotionAndNonBlockingClasses();
  testRiskGuidancePriorityAndHighRiskPhrase();
  console.log("risk evaluator policy checks passed");
}

main();
