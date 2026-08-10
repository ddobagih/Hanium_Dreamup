import {
  WALKSAFE_CORE_CLASS_NAMES,
  buildBBoxHistoryRiskContext,
  evaluateTwoModelDetectionRisk,
  formatBBoxTtcStatusText,
  formatModelEstimateStatusText,
  isStableTracking,
  isWalkSafeCoreClassName,
  modelEstimateForRiskContext,
  shouldVibrateForRiskLevel,
  trackingKeyForDetection,
  ttcRiskBucketForMilliseconds,
  type BBoxHistorySample,
  type RiskEvaluationContext
} from "../app/_walksafe/risk-evaluator";
import {
  EMPTY_RISK_INSTANCE_TRACKER_STATE,
  advanceRiskInstanceTracks
} from "../app/_walksafe/risk-instance-tracker";
import {
  depthContextFromDetection,
  depthContextFromEstimate,
  mockDepthEstimate,
  resolveBrowserDepthCapability
} from "../app/_walksafe/risk-depth";
import {
  estimatePseudoDepthFromHistory,
  estimateSensorDepthForBBox,
  polygonFromBBox,
  roiSamplePointsFromBBox
} from "../app/_walksafe/depth-estimator";
import { buildRoiRiskContext, evaluateRoiRisk } from "../app/_walksafe/risk-roi";
import { advanceDetectionPresence } from "../app/_walksafe/detection-presence-policy";
import {
  actionForRiskType,
  buildRiskGuidanceMessage,
  directionFromBBox,
  isMetricDistanceGuidanceSource,
  phraseForApproxSteps,
  phraseForBBoxVerticalPosition,
  selectRiskGuidanceCandidate
} from "../app/_walksafe/risk-guidance";
import { parseDetectFrameV2Payload } from "../lib/detect-api-v2";
import {
  detectionAvailabilityLabel,
  detectionAvailabilitySpeech
} from "../app/_walksafe/detection-availability";
import {
  RISK_FEEDBACK_SEQUENCE_CAPACITY,
  RISK_FEEDBACK_SEQUENCE_TTL_MS,
  advanceRiskFeedbackFailure,
  claimRiskFeedbackForDelivery,
  completeRiskFeedback,
  createRiskFeedbackSequence,
  enqueueRiskFeedback
} from "../app/_walksafe/risk-feedback-sequencer";
import {
  EMPTY_NON_METRIC_ADVISORY_CONTINUITY_STATE,
  NON_METRIC_HAZARD_ADVISORY_CAPABILITY_LABEL,
  NON_METRIC_HAZARD_ADVISORY_THRESHOLDS,
  NON_METRIC_HAZARD_ADVISORY_TIER,
  advanceNonMetricAdvisoryContinuity,
  evaluateNonMetricHazardAdvisory,
  isNonMetricHazardAdvisoryRuntimeActive,
  resolveNonMetricAdvisoryActivity,
  selectNonMetricHazardAdvisories
} from "../app/_walksafe/nonmetric-hazard-advisory";
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

function testUnavailableDetectionNeverClaimsNoRisk() {
  assert(detectionAvailabilityLabel("available") === "위험 요소 없음", "only a fresh successful inference may show no risk");
  for (const status of ["unavailable", "paused", "error"] as const) {
    assert(detectionAvailabilityLabel(status) !== "위험 요소 없음", `${status} must not be presented as safe`);
    assert(detectionAvailabilitySpeech(status)?.includes("직접 확인") ?? false, `${status} needs fail-safe spoken guidance`);
  }
}

function sample(detectionValue: TwoModelDetection, observedAtMs: number): BBoxHistorySample {
  return { detection: detectionValue, observed_at_ms: observedAtMs };
}

function explicitDepthContextFromDetection(detectionValue: TwoModelDetection): RiskEvaluationContext {
  return depthContextFromDetection(detectionValue);
}

function stablePathContext(overrides: RiskEvaluationContext = {}): RiskEvaluationContext {
  return {
    ...overrides,
    segmentation: { path_relation: "on_path", ...overrides.segmentation },
    tracking: {
      stable_frames: 3,
      stable_ms: 700,
      blocking_path: true,
      projected_path_intersection: true,
      ...overrides.tracking
    }
  };
}

function testAllThirteenClassesRemainCore() {
  assert(WALKSAFE_CORE_CLASS_NAMES.length === 13, "WalkSafe should keep exactly 13 core classes");
  for (const className of WALKSAFE_CORE_CLASS_NAMES) {
    assert(isWalkSafeCoreClassName(className), `${className} should remain a core class`);
  }
  assert(!isWalkSafeCoreClassName("bench"), "legacy bench should not enter the 13-class core contract");
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

function testUnifiedDamagedTactileBlockIsReportOnly() {
  const result = evaluateTwoModelDetectionRisk(
    detection({
      model_key: "unified_walksafe",
      model_class_id: 8,
      class_name: "damaged_tactile_block",
      category: "tactile_damage",
      confidence: 0.91
    })
  );

  assert(result.reportable, "unified damaged_tactile_block should be reportable");
  assert(!result.alertable, "unified damaged_tactile_block should not be user-alertable by default");
  assert(result.risk_type === "report_only_damage", "unified damaged_tactile_block should stay report_only_damage");
}

function testUnifiedSurfaceHazardsRequireStablePathEvidence() {
  const curbDetection = detection({
    model_key: "unified_walksafe",
    model_class_id: 10,
    class_name: "curb_step",
    category: "surface_hazard",
    confidence: 0.88
  });
  const unevenDetection = detection({
    model_key: "unified_walksafe",
    model_class_id: 11,
    class_name: "uneven_sidewalk",
    category: "surface_hazard",
    confidence: 0.88
  });
  const oneFrame = evaluateTwoModelDetectionRisk(curbDetection, stablePathContext({ tracking: { stable_frames: 1 } }));
  const stableOffPath = evaluateTwoModelDetectionRisk(
    curbDetection,
    stablePathContext({ segmentation: { path_relation: "off_path" }, tracking: { blocking_path: false, projected_path_intersection: false } })
  );
  const curb = evaluateTwoModelDetectionRisk(curbDetection, stablePathContext());
  const uneven = evaluateTwoModelDetectionRisk(unevenDetection, stablePathContext());

  assert(!oneFrame.alertable, "one surface-hazard frame must not alert");
  assert(!stableOffPath.alertable, "stable surface hazard outside the path ROI must not alert");
  assert(curb.alertable, "stable on-path curb_step should be alertable");
  assert(curb.risk_type === "surface_hazard", "curb_step should be surface_hazard");
  assert(curb.risk_level === "medium", "stable on-path surface hazard without STOP TTC should be warning level");
  assert(uneven.alertable, "stable on-path uneven_sidewalk should be alertable");
  assert(uneven.risk_type === "surface_hazard", "uneven_sidewalk should be surface_hazard");
}

function testUnifiedEScooterObstructionRequiresStablePathEvidence() {
  const target = detection({
    model_key: "unified_walksafe",
    model_class_id: 12,
    class_name: "e_scooter_obstruction",
    category: "obstruction",
    confidence: 0.89
  });
  const oneFrame = evaluateTwoModelDetectionRisk(target, stablePathContext({ tracking: { stable_frames: 1 } }));
  const result = evaluateTwoModelDetectionRisk(target, stablePathContext());

  assert(!oneFrame.alertable, "one e_scooter_obstruction frame must not alert");
  assert(result.alertable, "stable on-path e_scooter_obstruction should be alertable");
  assert(result.risk_type === "path_obstacle", "e_scooter_obstruction should be path_obstacle");
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
    tracking: { stable_frames: 3, stable_ms: 700, projected_path_intersection: true },
    segmentation: { path_relation: "on_path" }
  });

  assert(result.alertable, "path-intersecting general object should be alertable");
  assert(!result.reportable, "path-intersecting general object should not be reportable");
  assert(result.risk_type === "blocking_object", "path-intersecting object should be blocking_object");
}

function testTtcBoundaryPolicy() {
  const target = detection({ class_name: "car", model_class_id: 1, category: "vehicle" });
  const stopBoundary = evaluateTwoModelDetectionRisk(target, {
    tracking: {
      stable_frames: 3,
      stable_ms: 700,
      approaching: true,
      time_to_collision_ms: 3000,
      time_to_collision_source: "bbox_scale_heuristic",
      distance_m: 1.5,
      distance_source: "model_estimate",
      distance_confidence: 0.8
    }
  });
  const warningBoundary = evaluateTwoModelDetectionRisk(target, {
    tracking: {
      stable_frames: 3,
      stable_ms: 700,
      approaching: true,
      time_to_collision_ms: 10_000,
      time_to_collision_source: "bbox_scale_heuristic",
      distance_m: 2.5,
      distance_source: "model_estimate",
      distance_confidence: 0.8
    }
  });
  const trustedStopBoundary = evaluateTwoModelDetectionRisk(target, {
    tracking: {
      stable_frames: 3,
      stable_ms: 700,
      approaching: true,
      time_to_collision_ms: 3000,
      time_to_collision_source: "trusted_depth"
    }
  });
  const awareBoundary = evaluateTwoModelDetectionRisk(target, {
    tracking: {
      stable_frames: 3,
      stable_ms: 700,
      approaching: true,
      time_to_collision_ms: 30_000,
      time_to_collision_source: "bbox_scale_heuristic",
      distance_m: 4,
      distance_source: "model_estimate",
      distance_confidence: 0.8
    }
  });
  const outsideBoundary = evaluateTwoModelDetectionRisk(target, {
    tracking: {
      stable_frames: 3,
      stable_ms: 700,
      approaching: true,
      time_to_collision_ms: 30_001,
      time_to_collision_source: "bbox_scale_heuristic",
      distance_m: 4,
      distance_source: "model_estimate",
      distance_confidence: 0.8
    }
  });

  assert(ttcRiskBucketForMilliseconds(3000) === "stop", "TTC 3000ms should be STOP");
  assert(ttcRiskBucketForMilliseconds(3001) === "warning", "TTC 3001ms should be WARNING");
  assert(ttcRiskBucketForMilliseconds(10_001) === "aware", "TTC 10001ms should be AWARE");
  assert(ttcRiskBucketForMilliseconds(30_001) === "none", "TTC beyond 30 seconds should be none");
  assert(stopBoundary.alertable && stopBoundary.risk_level === "medium", "bbox TTC must not create STOP/high severity by itself");
  assert(trustedStopBoundary.alertable && trustedStopBoundary.risk_level === "high", "trusted depth TTC may create STOP/high severity");
  assert(warningBoundary.alertable && warningBoundary.risk_level === "medium", "WARNING TTC should speak without STOP severity");
  assert(!awareBoundary.alertable && awareBoundary.risk_level === "low", "AWARE TTC should remain an internal state");
  assert(!outsideBoundary.alertable, "TTC beyond 30 seconds should not alert without another path signal");
}

function testModelEstimateRequiresStableConfidenceAndIsClearlyLabeled() {
  const stableEstimate: RiskEvaluationContext = {
    tracking: {
      stable_frames: 3,
      stable_ms: 700,
      approaching: true,
      distance_m: 2.4,
      distance_source: "model_estimate",
      distance_confidence: 0.72,
      time_to_collision_ms: 8_000,
      time_to_collision_source: "bbox_scale_heuristic"
    }
  };
  const unstableEstimate: RiskEvaluationContext = {
    tracking: {
      stable_frames: 2,
      distance_m: 2.4,
      distance_source: "model_estimate",
      distance_confidence: 0.9
    }
  };
  const lowConfidenceEstimate: RiskEvaluationContext = {
    tracking: {
      stable_frames: 3,
      stable_ms: 700,
      distance_m: 2.4,
      distance_source: "model_estimate",
      distance_confidence: 0.49
    }
  };

  assert(modelEstimateForRiskContext(stableEstimate)?.distance_m === 2.4, "stable confident model estimate should be usable");
  assert(modelEstimateForRiskContext(unstableEstimate) === null, "model estimate before three frames should be rejected");
  assert(modelEstimateForRiskContext(lowConfidenceEstimate) === null, "low-confidence model estimate should be rejected");
  const statusText = formatModelEstimateStatusText(stableEstimate);
  assert(statusText === "단안 bbox 추정 거리 약 2.4미터", "diagnostic UI estimate must explicitly say it is a monocular bbox estimate");
  assert(!statusText.includes("실제"), "model estimate must never be described as actual sensor distance");
  assert(
    formatBBoxTtcStatusText(stableEstimate) === "bbox 크기 변화 추정 충돌 여유 약 8.0초",
    "UI must identify bbox TTC as a heuristic"
  );
}

function testStableTrackingRequiresFramesAndElapsedTime() {
  assert(!isStableTracking({ stable_frames: 3, stable_ms: 699 }), "three fast frames alone must not pass stability");
  assert(!isStableTracking({ stable_frames: 2, stable_ms: 1000 }), "elapsed time alone must not pass stability");
  assert(isStableTracking({ stable_frames: 3, stable_ms: 700 }), "both stability gates should pass at the boundary");
}

function testOnlyStopRiskVibrates() {
  assert(!shouldVibrateForRiskLevel("none"), "NONE must not vibrate");
  assert(!shouldVibrateForRiskLevel("low"), "AWARE must not vibrate");
  assert(!shouldVibrateForRiskLevel("medium"), "WARNING must not vibrate");
  assert(shouldVibrateForRiskLevel("high"), "STOP must vibrate");
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
  assert(actionForRiskType("blocking_object") === "주의해서 피하세요", "medium blocker should avoid STOP wording");
  assert(actionForRiskType("blocking_object", "high") === "멈추세요", "only high blocker should ask stop");
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
    distanceSource: "sensor_depth",
    stepLengthM: 0.5,
    label: "사람"
  });

  assert(message === "전방 약 3보 앞 사람. 주의해서 피하세요.", "medium guidance should include direction, distance, label, and caution action");
}

function testRiskGuidanceMessageOmitsStepsWithoutDistance() {
  const message = buildRiskGuidanceMessage({
    riskType: "blocking_object",
    bbox: { x: 0.42, y: 0.2, width: 0.2, height: 0.2 },
    stepLengthM: 0.5,
    label: "사람"
  });

  assert(message === "전방 사람. 주의해서 피하세요.", "guidance should keep direction/action and omit steps without distance");
}

function testRiskGuidanceMessageRejectsNonMetricDistanceSources() {
  const webxr = buildRiskGuidanceMessage({
    riskType: "blocking_object",
    bbox: { x: 0.42, y: 0.2, width: 0.2, height: 0.2 },
    distanceM: 1.4,
    distanceSource: "webxr",
    stepLengthM: 0.5,
    label: "사람"
  });
  const modelEstimate = buildRiskGuidanceMessage({
    riskType: "blocking_object",
    bbox: { x: 0.42, y: 0.2, width: 0.2, height: 0.2 },
    distanceM: 1.4,
    distanceSource: "model_estimate",
    stepLengthM: 0.5,
    label: "사람"
  });
  const polygonTrend = buildRiskGuidanceMessage({
    riskType: "blocking_object",
    bbox: { x: 0.42, y: 0.2, width: 0.2, height: 0.2 },
    distanceM: 1.4,
    distanceSource: "polygon_trend",
    stepLengthM: 0.5,
    label: "사람"
  });

  assert(isMetricDistanceGuidanceSource("sensor_depth"), "sensor_depth should be allowed for metric/step guidance");
  assert(!isMetricDistanceGuidanceSource("webxr"), "unmapped WebXR depth must not drive metric/step guidance");
  assert(!isMetricDistanceGuidanceSource("model_estimate"), "model_estimate should not be allowed for metric/step guidance");
  assert(!isMetricDistanceGuidanceSource("polygon_trend"), "polygon_trend should not be allowed for metric/step guidance");
  assert(webxr === "전방 사람. 주의해서 피하세요.", "unmapped WebXR depth should omit approximate step guidance");
  assert(modelEstimate === "전방 사람. 주의해서 피하세요.", "model_estimate should omit approximate step guidance");
  assert(polygonTrend === "전방 사람. 주의해서 피하세요.", "polygon_trend should omit approximate step guidance");
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
  assert(parsed.detections.length === 2, "invalid distance and bbox items should be dropped instead of normalized");
  assert(parsed.detections[0].distance_m === 1.4, "parser should preserve explicit non-negative distance_m");
  assert(parsed.detections[1].distance_m === null, "parser should keep missing distance_m as null");
  assert(parsed.audit.parser_drop_count === 3, "invalid distance and bbox items should remain visible in parser audit");
  assert(!parsed.detections.some((item) => ["bicycle", "bus", "truck"].includes(item.class_name)), "invalid items must fail closed");
}

function testDetectV2ParserAcceptsUnifiedWalksafeModelKey() {
  const parsed = parseDetectFrameV2Payload(
    {
      schema_version: "detect.v2",
      detections: [
        detection({
          model_key: "unified_walksafe",
          source_model: "unified-policy-test",
          model_class_id: 8,
          class_name: "damaged_tactile_block",
          category: "tactile_damage"
        })
      ]
    },
    { captured_at: "2026-05-23T06:00:00.000Z", gps: null, heading: null }
  );

  assert(parsed !== null, "valid detect v2 payload should parse");
  assert(parsed.detections.length === 1, "unified_walksafe detection should not be dropped");
  assert(parsed.detections[0].model_key === "unified_walksafe", "parser should preserve unified model key");
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
  assert(parsed.detections.length === 1, "invalid distance metadata should drop the whole detection");
  assert(parsed.detections[0].distance_source === "sensor_depth", "trusted distance source should be preserved");
  assert(parsed.detections[0].distance_confidence === 0.82, "distance confidence should be preserved");
  assert(parsed.audit.parser_drop_count === 1, "invalid distance metadata should be recorded in parser audit");
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
    distanceSource: context.depth?.source,
    stepLengthM: 0.5,
    label: "사람"
  });

  assert(context.depth?.distance_m === 1.4, "explicit detection distance_m should become depth context");
  assert(message === "전방 약 3보 앞 사람. 주의해서 피하세요.", "explicit distance_m should enable approximate step guidance");
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

function testDepthEstimatorSamplesFreshSensorRoiMedian() {
  const bbox = { x: 0.4, y: 0.4, width: 0.2, height: 0.2 };
  const points = roiSamplePointsFromBBox(bbox, { gridSize: 3 });
  const polygon = polygonFromBBox(bbox, 0.2);
  const result = estimateSensorDepthForBBox(
    bbox,
    {
      observedAtMs: 1000,
      getDepthInMeters: (x, y) => (x > 0.55 && y > 0.55 ? 8 : 1.24)
    },
    { nowMs: 1200, gridSize: 3, detectionConfidence: 1, motionStability: 1 }
  );

  assert(points.length === 9, "3x3 depth ROI should create 9 sample points");
  assert(polygon.length === 4, "bbox polygon fallback should create a 4-point polygon");
  assert(result?.source === "sensor_depth", "fresh sensor depth should produce sensor_depth source");
  assert(result.distance_m === 1.24, "sensor depth should use trimmed median distance");
  assert(result.confidence >= 0.5, "fresh stable sensor depth should be trusted enough");
}

function testDepthEstimatorRejectsStaleSensorFrame() {
  const result = estimateSensorDepthForBBox(
    { x: 0.4, y: 0.4, width: 0.2, height: 0.2 },
    { observedAtMs: 1000, getDepthInMeters: () => 1.2 },
    { nowMs: 2001, maxFrameAgeMs: 900 }
  );

  assert(result === null, "stale sensor depth frame should not produce distance");
}

function testDepthEstimatorRejectsSparseSensorSamples() {
  const bbox = { x: 0.4, y: 0.4, width: 0.2, height: 0.2 };
  const points = roiSamplePointsFromBBox(bbox, { gridSize: 3 });
  let fewSampleCalls = 0;
  const fewSamples = estimateSensorDepthForBBox(
    bbox,
    {
      observedAtMs: 1000,
      getDepthInMeters: () => {
        fewSampleCalls += 1;
        return fewSampleCalls <= 3 ? 1.2 : null;
      }
    },
    { nowMs: 1100, gridSize: 3, minValidSamples: 4, minValidRatio: 0.2 }
  );
  let lowRatioCalls = 0;
  const lowRatio = estimateSensorDepthForBBox(
    bbox,
    {
      observedAtMs: 1000,
      getDepthInMeters: () => {
        lowRatioCalls += 1;
        return lowRatioCalls <= 4 ? 1.2 : null;
      }
    },
    { nowMs: 1100, gridSize: 3, minValidSamples: 2, minValidRatio: 0.5 }
  );

  assert(points.length === 9, "sparse depth sample test should use 3x3 ROI");
  assert(fewSamples === null, "sensor depth should reject frames below minValidSamples");
  assert(lowRatio === null, "sensor depth should reject frames below minValidRatio");
}

function testPseudoDepthMarksModelEstimateApproaching() {
  const first = detection({
    class_name: "car",
    model_class_id: 1,
    category: "vehicle",
    bbox: { x: 0.3, y: 0.3, width: 0.14, height: 0.14 }
  });
  const second = detection({
    class_name: "car",
    model_class_id: 1,
    category: "vehicle",
    bbox: { x: 0.28, y: 0.28, width: 0.2, height: 0.2 }
  });
  const third = detection({
    class_name: "car",
    model_class_id: 1,
    category: "vehicle",
    bbox: { x: 0.25, y: 0.25, width: 0.3, height: 0.3 }
  });
  const result = estimatePseudoDepthFromHistory(third, [sample(first, 1000), sample(second, 2000), sample(third, 3000)]);
  const message = buildRiskGuidanceMessage({
    riskType: "approaching_object",
    bbox: third.bbox,
    distanceM: result?.distance_m,
    distanceSource: result?.source,
    stepLengthM: 0.5,
    label: "차량"
  });

  assert(result?.source === "model_estimate", "pseudo depth should be marked model_estimate");
  assert(result.approach_state === "approaching", "growing bbox history should mark approaching");
  assert(result.distance_m > 0, "pseudo depth should compute an internal meter estimate");
  assert(message === "전방 차량. 피하세요.", "model_estimate distance should stay internal and not create step guidance");
}

function testPseudoDepthLowMotionStabilityIsConservative() {
  const first = detection({
    class_name: "car",
    model_class_id: 1,
    category: "vehicle",
    bbox: { x: 0.3, y: 0.3, width: 0.14, height: 0.14 }
  });
  const second = detection({
    class_name: "car",
    model_class_id: 1,
    category: "vehicle",
    bbox: { x: 0.28, y: 0.28, width: 0.2, height: 0.2 }
  });
  const third = detection({
    class_name: "car",
    model_class_id: 1,
    category: "vehicle",
    bbox: { x: 0.25, y: 0.25, width: 0.3, height: 0.3 }
  });
  const result = estimatePseudoDepthFromHistory(third, [sample(first, 1000), sample(second, 2000), sample(third, 3000)], {
    motionStability: 0.2
  });

  assert(result?.source === "model_estimate", "low-motion pseudo depth should still expose internal model_estimate source");
  assert(result.approach_state === "unknown", "low motion stability should make pseudo-depth approach unknown");
  assert(result.confidence <= 0.35, "low motion stability should cap pseudo-depth confidence");
}

function testRiskDepthTrustsOnlySensorDepthAtConfidenceBoundary() {
  const trusted = depthContextFromDetection(detection({ distance_m: 1.4, distance_source: "sensor_depth", distance_confidence: 0.5 }));
  const lowConfidence = depthContextFromDetection(detection({ distance_m: 1.4, distance_source: "sensor_depth", distance_confidence: 0.49 }));
  const modelEstimate = depthContextFromDetection(detection({ distance_m: 1.4, distance_source: "model_estimate", distance_confidence: 0.99 }));

  assert(trusted.depth?.source === "sensor_depth", "sensor_depth at 0.5 confidence should be trusted");
  assert(lowConfidence.depth === undefined, "sensor_depth below 0.5 confidence should be ignored");
  assert(modelEstimate.depth === undefined, "model_estimate should not become trusted depth context");
}

function testSensorDepthCloseObjectCanAlertWithStepGuidance() {
  const target = detection({
    distance_m: 1.2,
    distance_source: "sensor_depth",
    distance_confidence: 0.8,
    bbox: { x: 0.4, y: 0.2, width: 0.2, height: 0.2 }
  });
  const depthContext = depthContextFromDetection(target);
  const unstableRisk = evaluateTwoModelDetectionRisk(target, depthContext);
  const context: RiskEvaluationContext = {
    ...depthContext,
    tracking: { stable_frames: 3, stable_ms: 700 }
  };
  const risk = evaluateTwoModelDetectionRisk(target, context);
  const message = buildRiskGuidanceMessage({
    riskType: risk.risk_type,
    riskLevel: risk.risk_level,
    bbox: target.bbox,
    distanceM: context.depth?.distance_m,
    distanceSource: context.depth?.source,
    stepLengthM: 0.5,
    label: "보행자",
    fallback: risk.recommended_message
  });

  assert(!unstableRisk.alertable, "sensor depth must not bypass temporal stability");
  assert(risk.alertable, "stable close sensor_depth object should be alertable");
  assert(risk.risk_type === "blocking_object", "close sensor_depth should become blocking_object");
  assert(message === "전방 약 2보 앞 보행자. 멈추세요.", "sensor_depth should enable step guidance");
}

function testModelEstimateCloseDistanceDoesNotRaiseBlockingSeverity() {
  const target = detection({
    class_name: "car",
    model_class_id: 1,
    category: "vehicle"
  });
  const risk = evaluateTwoModelDetectionRisk(target, {
    tracking: {
      stable_frames: 3,
      stable_ms: 700,
      projected_path_intersection: true,
      distance_m: 0.8,
      distance_source: "model_estimate",
      distance_confidence: 0.95
    }
  });

  assert(risk.alertable, "path-intersecting model_estimate object should still alert");
  assert(risk.risk_type === "blocking_object", "path-intersecting model_estimate object should stay blocking");
  assert(risk.risk_level === "medium", "model_estimate pseudo distance should not promote close blocking severity to high");
}

function testRoiCenterLowerObjectRaisesBlockingAlert() {
  const target = detection({
    class_name: "bench",
    category: "street_furniture",
    bbox: { x: 0.42, y: 0.56, width: 0.18, height: 0.22 }
  });
  const roi = evaluateRoiRisk(target);
  const oneFrameContext = buildRoiRiskContext(target);
  const oneFrameResult = evaluateTwoModelDetectionRisk(target, oneFrameContext);
  const result = evaluateTwoModelDetectionRisk(target, {
    ...oneFrameContext,
    tracking: { ...oneFrameContext.tracking, stable_frames: 3, stable_ms: 700 }
  });

  assert(roi.pathRelation === "on_path", "center lower bbox should be on_path");
  assert(roi.blockingPath, "center lower bbox should block path");
  assert(oneFrameContext.tracking?.stable_frames === undefined, "ROI geometry must not manufacture stable frame history");
  assert(!oneFrameResult.alertable, "one on-path ROI frame must not bypass temporal stability");
  assert(result.alertable, "three stable on-path frames should be alertable through risk evaluator");
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

function testFutureRouteProjectionShiftsGeneralObstacleRoi() {
  const target = detection({
    class_name: "car",
    model_class_id: 2,
    category: "vehicle",
    bbox: { x: 0.66, y: 0.56, width: 0.2, height: 0.22 }
  });
  const baseline = evaluateRoiRisk(target);
  const projected = evaluateRoiRisk(target, {
    debug: true,
    motion: {
      heading_deg: 0,
      route_bearing_deg: 90,
      walking_speed_mps: 1.2,
      future_shift_x: 0.18,
      prediction_horizon_s: 4,
      projected_distance_m: 4.8,
      confidence: 0.8
    }
  });

  assert(baseline.pathRelation === "near_path", "object should begin beside the unshifted center corridor");
  assert(projected.pathRelation === "on_path", "four-second route projection should move the corridor onto the object");
  assert(projected.debug?.predictionHorizonS === 4, "ROI debug should retain the field projection horizon");
  assert(projected.debug?.projectedDistanceM === 4.8, "ROI debug should retain projected walking distance");
}

function testRiskGuidancePriorityAndHighRiskPhrase() {
  const lowConfidenceBlocker = detection({
    class_name: "bench",
    category: "street_furniture",
    confidence: 0.7,
    bbox: { x: 0.42, y: 0.58, width: 0.18, height: 0.24 }
  });
  const blockerContext = buildRoiRiskContext(lowConfidenceBlocker);
  const stableBlockerContext: RiskEvaluationContext = {
    ...blockerContext,
    tracking: { ...blockerContext.tracking, stable_frames: 3, stable_ms: 700 }
  };
  const displayOnly = detection({
    class_name: "traffic light",
    model_class_id: 9,
    category: "path_guidance",
    confidence: 0.99
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
      risk: evaluateTwoModelDetectionRisk(lowConfidenceBlocker, stableBlockerContext),
      context: stableBlockerContext,
      confidence: lowConfidenceBlocker.confidence,
      index: 0
    },
    {
      item: displayOnly,
      risk: evaluateTwoModelDetectionRisk(displayOnly),
      context: {},
      confidence: displayOnly.confidence,
      index: 1
    },
    {
      item: highRiskApproacher,
      risk: evaluateTwoModelDetectionRisk(highRiskApproacher, {
        tracking: {
          stable_frames: 3,
          stable_ms: 700,
          approaching: true,
          time_to_collision_ms: 1500,
          time_to_collision_source: "trusted_depth"
        }
      }),
      context: {
        tracking: {
          stable_frames: 3,
          stable_ms: 700,
          approaching: true,
          time_to_collision_ms: 1500,
          time_to_collision_source: "trusted_depth"
        }
      },
      confidence: highRiskApproacher.confidence,
      index: 2
    }
  ]);
  const phrase = buildRiskGuidanceMessage({
    riskType: "approaching_object",
    riskLevel: "high",
    bbox: highRiskApproacher.bbox,
    label: "차량"
  });

  assert(selected?.item.class_name === "car", "third candidate must be evaluated and win by higher risk level");
  assert(phrase === "전방 차량. 멈추고 피하세요.", "high approaching risk should use stronger action phrase");
}

function testIoUTrackerSeparatesSimultaneousSameClassObjects() {
  const firstLeft = detection({
    captured_at: "2026-05-23T06:00:00.000Z",
    bbox: { x: 0.1, y: 0.3, width: 0.2, height: 0.2 }
  });
  const firstRight = detection({
    captured_at: "2026-05-23T06:00:00.000Z",
    bbox: { x: 0.7, y: 0.3, width: 0.2, height: 0.2 }
  });
  const firstUpdate = advanceRiskInstanceTracks(
    EMPTY_RISK_INSTANCE_TRACKER_STATE,
    [firstLeft, firstRight],
    Date.parse(firstLeft.captured_at)
  );
  const secondRight = detection({
    captured_at: "2026-05-23T06:00:00.800Z",
    bbox: { x: 0.68, y: 0.3, width: 0.22, height: 0.2 }
  });
  const secondLeft = detection({
    captured_at: "2026-05-23T06:00:00.800Z",
    bbox: { x: 0.11, y: 0.3, width: 0.21, height: 0.2 }
  });
  const secondUpdate = advanceRiskInstanceTracks(
    firstUpdate.state,
    [secondRight, secondLeft],
    Date.parse(secondRight.captured_at)
  );

  assert(firstUpdate.assignments[0].trackId !== firstUpdate.assignments[1].trackId, "same-class instances need separate IDs");
  assert(
    secondUpdate.assignments[0].trackId === firstUpdate.assignments[1].trackId,
    "right object should retain its IoU-associated track when detection order changes"
  );
  assert(
    secondUpdate.assignments[1].trackId === firstUpdate.assignments[0].trackId,
    "left object should retain its IoU-associated track when detection order changes"
  );
  assert(secondUpdate.assignments.every((assignment) => assignment.samples.length === 2), "each instance history should grow independently");
}

function testSingleEmptyFrameDoesNotClearPublishedDetection() {
  const target = detection();
  const visible = advanceDetectionPresence({ detections: [], consecutiveEmptyFrames: 0 }, [target]);
  const firstMiss = advanceDetectionPresence(visible, []);
  const confirmedEmpty = advanceDetectionPresence(firstMiss, []);

  assert(firstMiss.detections[0] === target, "one negative frame should retain the last detection for confirmation");
  assert(firstMiss.consecutiveEmptyFrames === 1, "the first negative frame should remain observable");
  assert(confirmedEmpty.detections.length === 0, "two consecutive negative frames should clear the published detection");
}

function testEqualSeverityRiskFeedbackUsesBoundedFifo() {
  let sequence = createRiskFeedbackSequence<string>();
  sequence = enqueueRiskFeedback(sequence, { key: "a", severityRank: 2, lastSeenAtMs: 1_000, payload: "a" }, 1_000).state;
  sequence = enqueueRiskFeedback(sequence, { key: "b", severityRank: 2, lastSeenAtMs: 1_010, payload: "b-old" }, 1_010).state;
  sequence = enqueueRiskFeedback(sequence, { key: "c", severityRank: 2, lastSeenAtMs: 1_020, payload: "c" }, 1_020).state;
  const full = enqueueRiskFeedback(sequence, { key: "d", severityRank: 2, lastSeenAtMs: 1_030, payload: "d" }, 1_030);

  assert(RISK_FEEDBACK_SEQUENCE_CAPACITY === 3, "risk feedback capacity must include one active and two pending actions");
  assert(full.outcome === "dropped_capacity", "a full equal-severity queue must retain its established FIFO");
  assert(full.state.pending.map((item) => item.key).join(",") === "b,c", "equal-severity arrivals must keep FIFO order");

  const refreshed = enqueueRiskFeedback(
    full.state,
    { key: "b", severityRank: 2, lastSeenAtMs: 1_500, payload: "b-new" },
    1_500
  );
  assert(refreshed.outcome === "refreshed", "a repeated risk key must refresh instead of duplicating");
  assert(refreshed.state.pending[0].payload === "b-new", "key dedupe must retain the freshest action payload");

  const afterA = completeRiskFeedback(refreshed.state, "a");
  const claimedB = claimRiskFeedbackForDelivery(afterA, {
    nowMs: 1_500 + RISK_FEEDBACK_SEQUENCE_TTL_MS,
    isCurrent: (item) => item.key === "b"
  });
  assert(claimedB.item?.key === "b", "last-seen refresh at the TTL boundary must keep the oldest FIFO item deliverable");
  const afterB = completeRiskFeedback(claimedB.state, "b");
  const skippedC = claimRiskFeedbackForDelivery(afterB, {
    nowMs: 1_500 + RISK_FEEDBACK_SEQUENCE_TTL_MS,
    isCurrent: () => false
  });
  assert(skippedC.item === null, "delivery-time lifecycle/gate rejection must skip an otherwise queued risk");
}

function testHigherSeverityPreemptsAndCanEvictLowestPendingRisk() {
  let sequence = createRiskFeedbackSequence<string>();
  sequence = enqueueRiskFeedback(sequence, { key: "medium-active", severityRank: 2, lastSeenAtMs: 1_000, payload: "m" }, 1_000).state;
  sequence = enqueueRiskFeedback(sequence, { key: "low-old", severityRank: 1, lastSeenAtMs: 1_010, payload: "l1" }, 1_010).state;
  sequence = enqueueRiskFeedback(sequence, { key: "low-new", severityRank: 1, lastSeenAtMs: 1_020, payload: "l2" }, 1_020).state;
  const preempted = enqueueRiskFeedback(
    sequence,
    { key: "high", severityRank: 3, lastSeenAtMs: 1_030, payload: "h" },
    1_030
  );
  assert(preempted.outcome === "preempted", "only a higher-severity risk may preempt the active action");
  assert(preempted.state.active?.key === "high", "the higher-severity risk must become active immediately");

  const promoted = enqueueRiskFeedback(
    preempted.state,
    { key: "medium-new", severityRank: 2, lastSeenAtMs: 1_040, payload: "m2" },
    1_040
  );
  assert(promoted.outcome === "queued_replacing_lower", "a higher pending severity may evict the lowest pending risk");
  assert(promoted.state.pending.map((item) => item.key).join(",") === "low-old,medium-new", "eviction must preserve established FIFO peers");
}

function testActiveSeverityDoesNotDowngradeWhileDeliveryIsInFlight() {
  let sequence = createRiskFeedbackSequence<string>();
  sequence = enqueueRiskFeedback(sequence, { key: "same", severityRank: 3, lastSeenAtMs: 1_000, payload: "high" }, 1_000).state;
  const refreshed = enqueueRiskFeedback(
    sequence,
    { key: "same", severityRank: 2, lastSeenAtMs: 1_100, payload: "medium" },
    1_100
  );
  assert(refreshed.state.active?.severityRank === 3, "an in-flight high warning must retain its claimed severity when the next frame downgrades");
  const equalHigh = enqueueRiskFeedback(
    refreshed.state,
    { key: "other-high", severityRank: 3, lastSeenAtMs: 1_200, payload: "peer" },
    1_200
  );
  assert(equalHigh.outcome === "queued", "another high warning must remain FIFO instead of preempting a downgraded active key");
}

function testRiskFeedbackFailureRetryHasTerminalBoundary() {
  let retryCount = 0;
  let decision = advanceRiskFeedbackFailure(retryCount);
  assert(!decision.terminal && decision.delayMs === 600, "first speech failure must schedule the bounded base retry");
  retryCount = decision.retryCount;
  decision = advanceRiskFeedbackFailure(retryCount);
  assert(!decision.terminal && decision.delayMs === 1_200, "second speech failure must use bounded backoff");
  decision = advanceRiskFeedbackFailure(decision.retryCount);
  assert(decision.terminal && decision.delayMs === null, "third speech failure must release the queue instead of retrying forever");
}

function nonMetricContinuity(trackId: string, consecutiveFrames = 3, stableMs = 800) {
  return {
    trackId,
    consecutiveFrames,
    stableMs,
    firstCapturedAtMs: 0,
    lastCapturedAtMs: stableMs
  };
}

function testNonMetricHazardAdvisoryRequiresFreshStableCameraEvidence() {
  const nowMs = Date.parse("2026-05-23T06:00:01.000Z");
  const target = detection({
    class_name: "car",
    model_class_id: 1,
    category: "vehicle",
    captured_at: new Date(nowMs - 100).toISOString(),
    bbox: { x: 0.05, y: 0.44, width: 0.24, height: 0.42 }
  });
  const context: RiskEvaluationContext = {
    tracking: { object_id: "car-left", stable_frames: 3, stable_ms: 800 }
  };
  const advisory = evaluateNonMetricHazardAdvisory(target, context, {
    nowMs,
    motionStability: 0.8,
    continuity: nonMetricContinuity("car-left")
  });

  assert(advisory?.direction === "left", "stable lower-left camera evidence should produce a left advisory");
  assert(advisory?.riskLevel === "low", "non-metric camera evidence must remain below metric safety warnings");
  assert(advisory !== null && !("vibration" in advisory), "a low non-metric advisory must not carry haptic authority");
  assert(
    advisory?.continuity.consecutiveFrames === 3 && advisory.continuity.stableMs === 800,
    "the accepted advisory must expose its distinct-frame and elapsed-time continuity evidence"
  );
  assert(advisory?.message.includes("카메라 기준 왼쪽") ?? false, "advisory must identify camera-relative direction");
  assert(advisory?.message.includes("TMAP 길 안내를 기준") ?? false, "advisory must preserve TMAP as the route authority");
  assert(
    !/(미터|\d+보|따라가|안전 경로|우회|자동 신고)/.test(advisory?.message ?? ""),
    "non-metric advisory must not claim distance, steps, steering, safety, detours, or reporting"
  );
}

function testNonMetricHazardAdvisoryRejectsWeakStaleMetricAndNonHazardEvidence() {
  const nowMs = Date.parse("2026-05-23T06:00:02.000Z");
  const target = detection({
    class_name: "person",
    category: "vulnerable_road_user",
    captured_at: new Date(nowMs - 100).toISOString(),
    bbox: { x: 0.41, y: 0.42, width: 0.2, height: 0.42 }
  });
  const stableContext: RiskEvaluationContext = {
    tracking: { object_id: "person-front", stable_frames: 3, stable_ms: 800 }
  };
  const oneFrame = evaluateNonMetricHazardAdvisory(target, {
    tracking: { object_id: "person-front", stable_frames: 99, stable_ms: 5_000 }
  }, { nowMs, motionStability: 0.9, continuity: nonMetricContinuity("person-front", 1, 0) });
  const stale = evaluateNonMetricHazardAdvisory(
    { ...target, captured_at: new Date(nowMs - NON_METRIC_HAZARD_ADVISORY_THRESHOLDS.maximumDetectionAgeMs - 1).toISOString() },
    stableContext,
    { nowMs, motionStability: 0.9, continuity: nonMetricContinuity("person-front") }
  );
  const shaky = evaluateNonMetricHazardAdvisory(target, stableContext, {
    nowMs,
    motionStability: 0.2,
    continuity: nonMetricContinuity("person-front")
  });
  const noImu = evaluateNonMetricHazardAdvisory(target, stableContext, {
    nowMs,
    motionStability: null,
    continuity: nonMetricContinuity("person-front")
  });
  const metric = evaluateNonMetricHazardAdvisory(target, {
    ...stableContext,
    depth: { source: "sensor_depth", distance_m: 1.2, confidence: 0.8 }
  }, { nowMs, motionStability: 0.9, continuity: nonMetricContinuity("person-front") });
  const crosswalk = evaluateNonMetricHazardAdvisory(
    { ...target, class_name: "crosswalk", category: "path_guidance" },
    stableContext,
    { nowMs, motionStability: 0.9, continuity: nonMetricContinuity("person-front") }
  );
  const damagedTactile = evaluateNonMetricHazardAdvisory(
    { ...target, class_name: "damaged_tactile_block", category: "tactile_damage" },
    stableContext,
    { nowMs, motionStability: 0.9, continuity: nonMetricContinuity("person-front") }
  );

  assert(oneFrame === null, "one frame must never produce a non-metric hazard advisory");
  assert(stale === null, "stale detections must not produce a non-metric hazard advisory");
  assert(shaky === null, "very unstable camera motion must pause non-metric hazard advisory");
  assert(noImu !== null, "missing optional IMU stability must not block the camera-only advisory tier");
  assert(metric === null, "trusted metric depth should stay on the metric risk path without duplicate advisory");
  assert(crosswalk === null, "path guidance classes are not local obstacle advisories");
  assert(damagedTactile === null, "camera advisory must not enter the separate damaged-tactile report pipeline");
}

function testNormalTactileAdvisoryOnlyReportsCameraSideAndTmapAuthority() {
  const nowMs = Date.parse("2026-05-23T06:00:01.000Z");
  const tactile = evaluateNonMetricHazardAdvisory(detection({
    model_key: "unified_walksafe",
    model_class_id: 7,
    class_name: "normal_tactile_block",
    category: "tactile_normal",
    confidence: 0.82,
    captured_at: new Date(nowMs - 100).toISOString(),
    bbox: { x: 0.7, y: 0.5, width: 0.24, height: 0.32 }
  }), {
    tracking: { object_id: "tactile-right", stable_frames: 3, stable_ms: 800 }
  }, { nowMs, motionStability: 0.9, continuity: nonMetricContinuity("tactile-right") });

  assert(NON_METRIC_HAZARD_ADVISORY_CAPABILITY_LABEL === "카메라 보조 경고 · TMAP 경로 유지", "degraded capability must be visible in plain language");
  assert(tactile?.tier === NON_METRIC_HAZARD_ADVISORY_TIER, "web degraded output must identify CAMERA_NON_METRIC_ADVISORY tier");
  assert(tactile?.message.includes("오른쪽에 점자블록") ?? false, "normal tactile advisory should say only the detected camera side");
  assert(tactile?.message.includes("TMAP 길 안내를 기준") ?? false, "normal tactile advisory must keep TMAP authoritative without telling the user to continue walking");
  assert(!/(따라가|정렬|안전|미터|\d+보)/.test(tactile?.message ?? ""), "normal tactile advisory must not become local steering or metric guidance");
  assert(tactile !== null && !("reportable" in tactile) && !("autoReport" in tactile), "advisory events must not expose report authority");
}

function testNonMetricHazardAdvisoryIsBoundedPerCameraDirection() {
  const nowMs = Date.parse("2026-05-23T06:00:01.000Z");
  const contexts = [
    { x: 0.04, objectId: "left-small", confidence: 0.7, width: 0.18 },
    { x: 0.08, objectId: "left-large", confidence: 0.9, width: 0.28 },
    { x: 0.02, objectId: "alertable-left", confidence: 0.99, width: 0.36, riskAlertable: true },
    { x: 0.4, objectId: "front", confidence: 0.8, width: 0.2 },
    { x: 0.72, objectId: "right", confidence: 0.8, width: 0.2 }
  ].map((item, index) => ({
    detection: detection({
      class_name: index === 2 ? "person" : "car",
      model_class_id: index === 2 ? 0 : 1,
      category: index === 2 ? "vulnerable_road_user" : "vehicle",
      confidence: item.confidence,
      captured_at: new Date(nowMs - 100).toISOString(),
      bbox: { x: item.x, y: 0.44, width: item.width, height: 0.42 }
    }),
    context: {
      tracking: { object_id: item.objectId, stable_frames: 3, stable_ms: 800 }
    } satisfies RiskEvaluationContext,
    riskAlertable: item.riskAlertable ?? false,
    continuity: nonMetricContinuity(item.objectId)
  }));
  const advisories = selectNonMetricHazardAdvisories(contexts, { nowMs, motionStability: 0.9 });

  assert(advisories.length === 3, "non-metric advisory should be bounded to one candidate per camera direction");
  assert(advisories.some((item) => item.key.includes("left-large")), "the strongest same-direction candidate should win");
  assert(!advisories.some((item) => item.key.includes("alertable-left")), "an existing risk warning must not also emit a low advisory");
  assert(advisories.map((item) => item.direction).join(",") === "front,left,right", "front advisory should lead a deterministic direction order");
}

function testNonMetricContinuityResetsAcrossAnEmptyRawInferenceFrame() {
  const trackId = "person-front";
  const first = advanceNonMetricAdvisoryContinuity(
    EMPTY_NON_METRIC_ADVISORY_CONTINUITY_STATE,
    { sequence: 1, observedAtMs: 1_000, hasDetections: true },
    [{ trackId, capturedAtMs: 1_000 }]
  );
  const empty = advanceNonMetricAdvisoryContinuity(
    first.state,
    { sequence: 2, observedAtMs: 1_400, hasDetections: false },
    // The UI presence policy may still publish this prior detection object on the first raw miss.
    [{ trackId, capturedAtMs: 1_000 }]
  );
  const afterGap = advanceNonMetricAdvisoryContinuity(
    empty.state,
    { sequence: 3, observedAtMs: 1_800, hasDetections: true },
    [{ trackId, capturedAtMs: 1_800 }]
  );
  const secondConsecutive = advanceNonMetricAdvisoryContinuity(
    afterGap.state,
    { sequence: 4, observedAtMs: 2_200, hasDetections: true },
    [{ trackId, capturedAtMs: 2_200 }]
  );
  const thirdConsecutive = advanceNonMetricAdvisoryContinuity(
    secondConsecutive.state,
    { sequence: 5, observedAtMs: 2_600, hasDetections: true },
    [{ trackId, capturedAtMs: 2_600 }]
  );

  assert(empty.evidence.length === 0, "an empty raw inference frame must clear advisory continuity immediately");
  assert(afterGap.evidence[0]?.consecutiveFrames === 1, "positive-empty-positive must restart at one frame");
  assert(secondConsecutive.evidence[0]?.consecutiveFrames === 2, "only the next distinct positive frame may advance continuity");
  assert(
    thirdConsecutive.evidence[0]?.consecutiveFrames === 3 && thirdConsecutive.evidence[0]?.stableMs === 800,
    "three distinct consecutive positive frames over 700ms may satisfy advisory stability"
  );
}

function testNonMetricHazardRuntimeGateRequiresLiveRearCameraNavigationAndVisibleSession() {
  const activeGate = {
    cameraReady: true,
    rearCamera: true,
    navigationActive: true,
    tmapGuidanceAvailable: true,
    detectionAvailable: true,
    sessionActive: true,
    pageVisible: true
  };
  assert(isNonMetricHazardAdvisoryRuntimeActive(activeGate), "all runtime safety gates should enable advisory");
  for (const key of Object.keys(activeGate) as Array<keyof typeof activeGate>) {
    assert(
      !isNonMetricHazardAdvisoryRuntimeActive({ ...activeGate, [key]: false }),
      `${key} loss must disable non-metric advisory immediately`
    );
  }
}

function testNonMetricAdvisoryDoesNotLatchTheExistingRiskGate() {
  const advisoryOnly = resolveNonMetricAdvisoryActivity(false, 1);
  const existingRisk = resolveNonMetricAdvisoryActivity(true, 1);

  assert(!advisoryOnly.riskActive && advisoryOnly.advisoryActive, "persistent camera advisory must stay outside the voice-command safety risk gate");
  assert(existingRisk.riskActive && existingRisk.advisoryActive, "an independent existing risk must keep its original safety authority");
}

function main() {
  testUnavailableDetectionNeverClaimsNoRisk();
  testAllThirteenClassesRemainCore();
  testDamagedTactileBlockIsReportOnly();
  testUnifiedDamagedTactileBlockIsReportOnly();
  testUnifiedSurfaceHazardsRequireStablePathEvidence();
  testUnifiedEScooterObstructionRequiresStablePathEvidence();
  testTactileDamageAreaIsAuxiliaryOnly();
  testNormalTactileIsNoRisk();
  testGeneralObjectWithoutHistoryIsDisplayOnly();
  testApproachingHistoryRaisesAlert();
  testShortHistoryDoesNotRaiseApproachingAlert();
  testDiscontinuousBBoxHistoryDoesNotRaiseApproachingAlert();
  testPathIntersectionRaisesBlockingAlert();
  testTtcBoundaryPolicy();
  testModelEstimateRequiresStableConfidenceAndIsClearlyLabeled();
  testOnlyStopRiskVibrates();
  testStableTrackingRequiresFramesAndElapsedTime();
  testTrackingKeyAndStaleReset();
  testJitterConstantAndDecreasingBBoxDoNotApproach();
  testRiskGuidanceHelpers();
  testRiskGuidanceMessageIncludesDirectionDistanceAndAction();
  testRiskGuidanceMessageOmitsStepsWithoutDistance();
  testRiskGuidanceMessageRejectsNonMetricDistanceSources();
  testDetectV2ParserPreservesExplicitDistanceOnly();
  testDetectV2ParserAcceptsUnifiedWalksafeModelKey();
  testDetectV2ParserPreservesTrustedDistanceMetadata();
  testExplicitDetectionDistanceFeedsDepthGuidance();
  testBrowserDepthGateAndPolicy();
  testDepthEstimatorSamplesFreshSensorRoiMedian();
  testDepthEstimatorRejectsStaleSensorFrame();
  testDepthEstimatorRejectsSparseSensorSamples();
  testPseudoDepthMarksModelEstimateApproaching();
  testPseudoDepthLowMotionStabilityIsConservative();
  testRiskDepthTrustsOnlySensorDepthAtConfidenceBoundary();
  testSensorDepthCloseObjectCanAlertWithStepGuidance();
  testModelEstimateCloseDistanceDoesNotRaiseBlockingSeverity();
  testRoiCenterLowerObjectRaisesBlockingAlert();
  testRoiEdgeObjectIsNearPathNotAlertableByItself();
  testRoiSmallUpperObjectIsOffPath();
  testRoiDebugMotionAndNonBlockingClasses();
  testFutureRouteProjectionShiftsGeneralObstacleRoi();
  testRiskGuidancePriorityAndHighRiskPhrase();
  testIoUTrackerSeparatesSimultaneousSameClassObjects();
  testSingleEmptyFrameDoesNotClearPublishedDetection();
  testEqualSeverityRiskFeedbackUsesBoundedFifo();
  testHigherSeverityPreemptsAndCanEvictLowestPendingRisk();
  testActiveSeverityDoesNotDowngradeWhileDeliveryIsInFlight();
  testRiskFeedbackFailureRetryHasTerminalBoundary();
  testNonMetricHazardAdvisoryRequiresFreshStableCameraEvidence();
  testNonMetricHazardAdvisoryRejectsWeakStaleMetricAndNonHazardEvidence();
  testNormalTactileAdvisoryOnlyReportsCameraSideAndTmapAuthority();
  testNonMetricHazardAdvisoryIsBoundedPerCameraDirection();
  testNonMetricContinuityResetsAcrossAnEmptyRawInferenceFrame();
  testNonMetricHazardRuntimeGateRequiresLiveRearCameraNavigationAndVisibleSession();
  testNonMetricAdvisoryDoesNotLatchTheExistingRiskGate();
  console.log("risk evaluator policy checks passed");
}

main();
