import { readFileSync } from "node:fs";
import path from "node:path";
import {
  estimateRecentStepSpeedMps,
  evaluateTactileRouteSupport,
  projectFutureMotion,
  resolveRouteBearing,
  updateAlphaBetaLocation
} from "../app/_walksafe/motion-projection";
import type { TwoModelDetection } from "../types/inference-v2";
import {
  advanceTactileObservationTracker,
  evaluateTactileRoutePolicy,
  selectTactileRouteDetection,
  TACTILE_ROUTE_POLICY_THRESHOLDS,
  type TactileRoutePolicyInput
} from "../app/_walksafe/tactile-route-policy";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

function normalTactile(centerX = 0.62): TwoModelDetection {
  return {
    schema_version: "detect.v2",
    model_key: "unified_walksafe",
    source_model: "motion-policy-test",
    model_class_id: 7,
    class_name: "normal_tactile_block",
    category: "tactile_normal",
    confidence: 0.8,
    bbox: { x: centerX - 0.1, y: 0.56, width: 0.2, height: 0.24 },
    threshold_used: 0.25,
    captured_at: "2026-07-11T00:00:00Z"
  };
}

function supervisedProjectionEvidence(centerX = 0.55) {
  return {
    source: "walksafe.camera_route_projection.v1" as const,
    routeId: "route-1",
    observedAtMs: 1_900,
    confidence: 0.9,
    corridorCenterX: centerX,
    corridorHalfWidth: 0.18
  };
}

function testAlphaBetaFilterRejectsLargeGpsJump() {
  const first = updateAlphaBetaLocation(null, {
    latitude: 37,
    longitude: 127,
    accuracyM: 5,
    speedMps: 1,
    headingDeg: 0,
    observedAtMs: 1000
  });
  assert(first !== null, "first valid GPS sample should initialize the filter");
  const second = updateAlphaBetaLocation(first, {
    latitude: 37.00001,
    longitude: 127,
    accuracyM: 5,
    speedMps: 1,
    headingDeg: 0,
    observedAtMs: 2000
  });
  assert(second !== null && second.lastObservationAccepted, "plausible walking sample should be accepted");
  const jump = updateAlphaBetaLocation(second, {
    latitude: 37.01,
    longitude: 127.01,
    accuracyM: 5,
    speedMps: 1,
    headingDeg: 0,
    observedAtMs: 3000
  });
  assert(jump !== null && !jump.lastObservationAccepted, "implausible GPS jump should be prediction-only");
  assert(Math.abs(jump.latitude - second.latitude) < 0.001, "rejected jump must not move the filter to the raw coordinate");
  assert(
    jump.accuracyM !== 5 || jump.accuracyM > second.accuracyM!,
    "rejected jump must not attach the rejected fix accuracy to prediction-only coordinates"
  );
}

function testRecentStepCadenceProducesBoundedSpeed() {
  const speed = estimateRecentStepSpeedMps([1000, 1500, 2000], 0.65, 2100);
  assert(speed !== null && Math.abs(speed - 1.3) < 0.001, "step cadence and calibrated step length should estimate speed");
  assert(estimateRecentStepSpeedMps([1000, 1500, 2000], 0.65, 5000) === null, "stale steps must not imply movement");
}

function testRouteBearingAndFourSecondProjection() {
  const routeBearing = resolveRouteBearing(
    {
      polyline: [
        { latitude: 37, longitude: 127 },
        { latitude: 37, longitude: 127.001 }
      ],
      steps: []
    },
    { latitude: 37, longitude: 127.0001 }
  );
  assert(routeBearing !== null && routeBearing > 89 && routeBearing < 91, "eastbound route should resolve to about 90 degrees");

  const motion = projectFutureMotion({
    gps: { latitude: 37, longitude: 127.0001, accuracy_m: 6, speed_mps: 1.1 },
    headingDeg: 45,
    routeBearingDeg: routeBearing,
    gpsSpeedMps: 1.1,
    stepSpeedMps: 1.3,
    motionStability: 0.9,
    horizonS: 4
  });
  assert(motion.horizonS === 4, "field projection should use the four second midpoint");
  assert(motion.projectedDistanceM > 4 && motion.projectedDistanceM < 6, "future distance should blend GPS and steps");
  assert(motion.screenShiftX > 0, "route to the right of the device heading should shift the future ROI right");
  assert(motion.speedSource === "gps_step", "both motion sources should be recorded");
}

function testTactileGuidanceRequiresRouteAlignedFutureRoi() {
  const alignedMotion = projectFutureMotion({
    gps: { latitude: 37, longitude: 127, accuracy_m: 5, speed_mps: 1.1 },
    headingDeg: 20,
    routeBearingDeg: 35,
    gpsSpeedMps: 1.1,
    stepSpeedMps: 1.2,
    motionStability: 0.9,
    horizonS: 4
  });
  const defaultClosed = evaluateTactileRouteSupport([normalTactile(0.55)], alignedMotion);
  assert(
    !defaultClosed.supported && defaultClosed.reason === "supervised_disabled",
    "release/default tactile local steering must remain fail-closed"
  );
  const flagOnly = evaluateTactileRouteSupport([normalTactile(0.55)], alignedMotion, {
    supervisedFieldEnabled: true,
    expectedRouteId: "route-1",
    projectionEvidence: null,
    nowMs: 2_000
  });
  assert(
    !flagOnly.supported && flagOnly.reason === "projection_unavailable",
    "a supervised flag without camera-route projection evidence must stay on TMAP"
  );
  const options = {
    supervisedFieldEnabled: true,
    expectedRouteId: "route-1",
    projectionEvidence: supervisedProjectionEvidence(),
    nowMs: 2_000
  };
  const supported = evaluateTactileRouteSupport([normalTactile(0.55)], alignedMotion, options);
  assert(supported.supported, "fresh supervised camera-route projection may admit auxiliary tactile evidence");

  const outside = evaluateTactileRouteSupport([normalTactile(0.05)], alignedMotion, options);
  assert(!outside.supported && outside.reason === "outside_future_roi", "off-corridor tactile detection must not guide");

  const wrongRoute = evaluateTactileRouteSupport([normalTactile(0.55)], alignedMotion, {
    ...options,
    expectedRouteId: "route-2"
  });
  assert(
    !wrongRoute.supported && wrongRoute.reason === "projection_invalid",
    "projection evidence from another TMAP route must fail closed"
  );

  const mismatchedMotion = projectFutureMotion({
    gps: { latitude: 37, longitude: 127, accuracy_m: 5, speed_mps: 1.1 },
    headingDeg: 0,
    routeBearingDeg: 100,
    gpsSpeedMps: 1.1,
    stepSpeedMps: 1.2,
    horizonS: 4
  });
  const mismatched = evaluateTactileRouteSupport([normalTactile(0.7)], mismatchedMotion, options);
  assert(!mismatched.supported && mismatched.reason === "route_not_visible", "route outside camera heading must block tactile guidance");
}

function testSharedTactileRoutePolicyFixtures() {
  const fixture = JSON.parse(
    readFileSync(path.resolve(process.cwd(), "../../tests/fixtures/navigation/tactile_route_policy_cases.json"), "utf8")
  ) as {
    thresholds: {
      minimum_confidence: number;
      minimum_stable_frames: number;
      minimum_stable_ms: number;
      maximum_detection_age_ms: number;
      maximum_route_heading_delta_deg: number;
      maximum_gps_accuracy_m: number;
      steering_dead_zone: number;
    };
    cases: Array<TactileRoutePolicyInput & {
      id: string;
      expected: { mode: string; steering: string | null; reason: string };
    }>;
  };
  assert(fixture.thresholds.minimum_confidence === TACTILE_ROUTE_POLICY_THRESHOLDS.minimumConfidence, "fixture and Web confidence thresholds must match");
  assert(fixture.thresholds.minimum_stable_frames === TACTILE_ROUTE_POLICY_THRESHOLDS.minimumStableFrames, "fixture and Web stable-frame thresholds must match");
  assert(fixture.thresholds.minimum_stable_ms === TACTILE_ROUTE_POLICY_THRESHOLDS.minimumStableMs, "fixture and Web stable-time thresholds must match");
  assert(fixture.thresholds.maximum_detection_age_ms === TACTILE_ROUTE_POLICY_THRESHOLDS.maximumDetectionAgeMs, "fixture and Web stale thresholds must match");
  assert(fixture.thresholds.maximum_route_heading_delta_deg === TACTILE_ROUTE_POLICY_THRESHOLDS.maximumRouteHeadingDeltaDeg, "fixture and Web heading thresholds must match");
  assert(fixture.thresholds.maximum_gps_accuracy_m === TACTILE_ROUTE_POLICY_THRESHOLDS.maximumGpsAccuracyM, "fixture and Web GPS thresholds must match");
  assert(fixture.thresholds.steering_dead_zone === TACTILE_ROUTE_POLICY_THRESHOLDS.steeringDeadZone, "fixture and Web steering thresholds must match");

  for (const fixtureCase of fixture.cases) {
    const actual = evaluateTactileRoutePolicy(fixtureCase);
    assert(actual.mode === fixtureCase.expected.mode, `${fixtureCase.id}: route mode should match the shared fixture`);
    assert(actual.steering === fixtureCase.expected.steering, `${fixtureCase.id}: steering should match the shared fixture`);
    assert(actual.reason === fixtureCase.expected.reason, `${fixtureCase.id}: reason should match the shared fixture`);
  }
}

function testDamagedTactileBlocksNormalLocalRouteCandidate() {
  const normal = { ...normalTactile(0.5), captured_at: "2026-07-11T00:00:01Z" };
  const damaged: TwoModelDetection = {
    ...normal,
    model_class_id: 8,
    class_name: "damaged_tactile_block",
    category: "tactile_damage",
    confidence: 0.7,
    captured_at: "2026-07-11T00:00:00Z"
  };
  const selected = selectTactileRouteDetection([normal, damaged], { centerX: 0.5, halfWidth: 0.2 }, true);

  assert(selected?.class_name === "damaged_tactile_block", "fresh corridor damage must block a newer normal tactile local path");
  assert(
    selectTactileRouteDetection([normal, damaged], { centerX: 0.5, halfWidth: 0.2 }, false) === null,
    "motion support denial must prevent every tactile local-route candidate"
  );
}

function testTactileTrackerRequiresDistinctStableFrames() {
  const first = advanceTactileObservationTracker(
    null,
    { className: "normal_tactile_block", capturedAtMs: 1000, bbox: { x: 0.4, y: 0.5, width: 0.2, height: 0.3 } },
    1050
  );
  const duplicate = advanceTactileObservationTracker(
    first,
    { className: "normal_tactile_block", capturedAtMs: 1000, bbox: { x: 0.4, y: 0.5, width: 0.2, height: 0.3 } },
    1200
  );
  const second = advanceTactileObservationTracker(
    duplicate,
    { className: "normal_tactile_block", capturedAtMs: 1400, bbox: { x: 0.42, y: 0.51, width: 0.2, height: 0.3 } },
    1450
  );
  const third = advanceTactileObservationTracker(
    second,
    { className: "normal_tactile_block", capturedAtMs: 1800, bbox: { x: 0.41, y: 0.5, width: 0.2, height: 0.3 } },
    1850
  );

  assert(duplicate?.stableFrames === 1, "a retained duplicate frame must not inflate tactile stability");
  assert(third?.stableFrames === 3, "three distinct nearby observations should satisfy the frame count");
  assert((third?.lastCapturedAtMs ?? 0) - (third?.firstCapturedAtMs ?? 0) === 800, "stable time should use capture timestamps");

  const alternatingObject = advanceTactileObservationTracker(
    second,
    { className: "normal_tactile_block", capturedAtMs: 1800, bbox: { x: 0.68, y: 0.5, width: 0.2, height: 0.3 } },
    1850
  );
  assert(alternatingObject?.stableFrames === 1, "a different normal tactile object must restart stability tracking");
}

function testTactileTrackerRejectsFutureClockEvidence() {
  const future = advanceTactileObservationTracker(
    null,
    { className: "normal_tactile_block", capturedAtMs: 1001, bbox: { x: 0.4, y: 0.5, width: 0.2, height: 0.3 } },
    1000
  );
  assert(future === null, "any future capture timestamp must not become stable tactile route evidence");
}

function main() {
  testAlphaBetaFilterRejectsLargeGpsJump();
  testRecentStepCadenceProducesBoundedSpeed();
  testRouteBearingAndFourSecondProjection();
  testTactileGuidanceRequiresRouteAlignedFutureRoi();
  testSharedTactileRoutePolicyFixtures();
  testDamagedTactileBlocksNormalLocalRouteCandidate();
  testTactileTrackerRequiresDistinctStableFrames();
  testTactileTrackerRejectsFutureClockEvidence();
  console.log("motion projection and tactile route support policy checks passed");
}

main();
