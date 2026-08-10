import {
  buildRouteMeasure,
  evaluateRouteArrivalStatus,
  evaluateOffRouteStatus,
  projectPointToRoute,
  stabilizeRouteArrivalStatus,
  stabilizeOffRouteStatus,
  type RouteProgressEvaluation
} from "../app/_walksafe/route-progress";
import type { RoutePoint, WalkingRouteResponse, WalkingRouteStep } from "../types/navigation";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

function point(latitude: number, longitude: number): RoutePoint {
  return { latitude, longitude };
}

const START = point(37.0, 127.0);
const MID = point(37.001, 127.0);
const END = point(37.002, 127.0);

function step(index: number, points: RoutePoint[]): WalkingRouteStep {
  return {
    index,
    distance_m: 0,
    duration_s: 0,
    points,
    instruction: null,
    road_name: null,
    turn_type: null,
    facility_type: null
  };
}

function routeFixture(overrides: Partial<WalkingRouteResponse> = {}): WalkingRouteResponse {
  return {
    schema_version: "walksafe.walking_route.v1",
    provider: "tmap_pedestrian",
    provider_route_id: "route-progress-policy-test",
    priority: "RECOMMEND",
    summary: {
      distance_m: 222,
      duration_s: 180
    },
    polyline: [START, MID, END],
    steps: [],
    guide_points: [],
    provider_result_code: 0,
    provider_result_message: "OK",
    ...overrides
  };
}

function assertMeasured(result: RouteProgressEvaluation): asserts result is RouteProgressEvaluation & {
  distanceToRouteM: number;
  distanceFromStartM: number;
  progressRatio: number;
} {
  assert(result.distanceToRouteM !== null, "distanceToRouteM should be measured");
  assert(result.distanceFromStartM !== null, "distanceFromStartM should be measured");
  assert(result.progressRatio !== null, "progressRatio should be measured");
}

function progressSample(
  status: RouteProgressEvaluation["status"],
  overrides: Partial<RouteProgressEvaluation> = {}
): RouteProgressEvaluation {
  const reason =
    overrides.reason ??
    (status === "on_route"
      ? "within_threshold"
      : status === "off_route"
        ? "outside_threshold"
        : status === "off_route_candidate"
          ? "accuracy_overlap"
          : "empty_route");

  return {
    status,
    distanceToRouteM: status === "unknown" ? null : status === "on_route" ? 5 : 40,
    distanceFromStartM: status === "unknown" ? null : 30,
    progressRatio: status === "unknown" ? null : 0.3,
    thresholdM: 25,
    accuracyM: 5,
    reason,
    ...overrides
  };
}

function testRouteOnStepsFallbackMeasuresProgress() {
  const route = routeFixture({
    polyline: [],
    steps: [step(0, [START, MID]), step(1, [MID, END])]
  });
  const measure = buildRouteMeasure(route);
  const projection = projectPointToRoute(point(37.0005, 127.0), measure);
  const result = evaluateOffRouteStatus({
    measure,
    currentPoint: point(37.0005, 127.0),
    accuracyM: 5
  });

  assert(measure.source === "steps", "empty polyline should fall back to step points");
  assert(projection !== null, "point on route should be projectable");
  assert(result.status === "on_route", "point on route should be on_route");
  assertMeasured(result);
  assert(result.distanceToRouteM < 1, "point on route should be within 1m of route");
  assert(result.distanceFromStartM > 50 && result.distanceFromStartM < 60, "projection should measure distance from start");
  assert(result.progressRatio > 0.2 && result.progressRatio < 0.3, "projection should expose route progress ratio");
}

function testNearRouteWithinDefaultThresholdStaysOnRoute() {
  const result = evaluateOffRouteStatus({
    route: routeFixture(),
    currentPoint: point(37.0005, 127.0001),
    accuracyM: 5
  });

  assert(result.status === "on_route", "near route point should stay on_route under default 25m threshold");
  assertMeasured(result);
  assert(result.distanceToRouteM > 5 && result.distanceToRouteM < 15, "near route fixture should be about 9m from route");
}

function testFarRouteExceedsDefaultThresholdBecomesOffRoute() {
  const result = evaluateOffRouteStatus({
    route: routeFixture(),
    currentPoint: point(37.0005, 127.001),
    accuracyM: 5
  });

  assert(result.status === "off_route", "far route point with good accuracy should be off_route");
  assertMeasured(result);
  assert(result.distanceToRouteM > 70, "far route fixture should be clearly outside default threshold");
}

function testPoorAccuracyDoesNotImmediatelyConfirmOffRoute() {
  const result = evaluateOffRouteStatus({
    route: routeFixture(),
    currentPoint: point(37.0005, 127.0005),
    accuracyM: 80
  });

  assert(result.status === "off_route_candidate", "poor accuracy should produce candidate instead of confirmed off_route");
  assertMeasured(result);
  assert(result.distanceToRouteM > 25, "poor-accuracy fixture should still be measured outside route threshold");
}

function testUnknownAccuracyNeverConfirmsOffRoute() {
  const result = evaluateOffRouteStatus({
    route: routeFixture(),
    currentPoint: point(37.0005, 127.001),
    accuracyM: null
  });

  assert(result.status === "unknown", "unknown GPS accuracy must never confirm off_route");
  assert(result.reason === "accuracy_uncertain", "unknown GPS accuracy should explain why confirmation is blocked");
}

function testEmptyRouteIsUnknown() {
  const result = evaluateOffRouteStatus({
    route: routeFixture({
      summary: { distance_m: 0, duration_s: 0 },
      polyline: [],
      steps: []
    }),
    currentPoint: point(37.0005, 127.0),
    accuracyM: 5
  });

  assert(result.status === "unknown", "empty route should be unknown");
  assert(result.distanceToRouteM === null, "empty route should not report distanceToRouteM");
  assert(result.distanceFromStartM === null, "empty route should not report distanceFromStartM");
  assert(result.progressRatio === null, "empty route should not report progressRatio");
}

function testCrossingRouteUsesPreviousProgressBranch() {
  const west = point(37, 126.999);
  const crossing = point(37, 127);
  const north = point(37.001, 127);
  const east = point(37, 127.001);
  const route = routeFixture({ polyline: [west, crossing, north, crossing, east] });
  const measure = buildRouteMeasure(route);
  const outbound = projectPointToRoute(crossing, measure, { previousDistanceFromStartM: 70 });
  const returning = projectPointToRoute(crossing, measure, { previousDistanceFromStartM: 280 });

  assert(outbound !== null && outbound.distanceFromStartM < 150, "early progress must stay on the first crossing branch");
  assert(returning !== null && returning.distanceFromStartM > 200, "return progress must stay on the later crossing branch");
}

function testContinuityWindowRejectsLargeForwardJump() {
  const crossing = point(37, 127);
  const route = routeFixture({
    polyline: [point(37, 126.999), crossing, point(37.001, 127), crossing, point(37, 127.001)]
  });
  const result = evaluateOffRouteStatus({
    route,
    currentPoint: crossing,
    accuracyM: 5,
    previousDistanceFromStartM: 60,
    maxAdvanceM: 80
  });

  assertMeasured(result);
  assert(result.distanceFromStartM < 150, "a crossing must not jump to a much later guide segment");
}

function testSingleOffRouteIsCandidateUntilStabilized() {
  const result = stabilizeOffRouteStatus([progressSample("off_route")]);

  assert(result.status === "off_route_candidate", "single off_route sample should remain a candidate");
  assert(result.confirmed === false, "single off_route sample should not be confirmed");
  assert(result.consecutiveOffRouteCount === 1, "single off_route sample should count as one candidate");
}

function testConsecutiveOffRouteConfirmsStabilizedStatus() {
  const result = stabilizeOffRouteStatus([progressSample("off_route"), progressSample("off_route")]);

  assert(result.status === "off_route", "consecutive off_route samples should confirm off_route");
  assert(result.confirmed === true, "consecutive off_route samples should be confirmed");
  assert(result.consecutiveOffRouteCount === 2, "confirmed off_route should expose consecutive count");
}

function testOnRouteImmediatelyRecoversStabilizedStatus() {
  const result = stabilizeOffRouteStatus([
    progressSample("off_route"),
    progressSample("off_route"),
    progressSample("on_route")
  ]);

  assert(result.status === "on_route", "latest on_route sample should recover immediately");
  assert(result.confirmed === false, "recovered on_route should clear off_route confirmation");
  assert(result.consecutiveOffRouteCount === 0, "recovered on_route should reset consecutive count");
}

function testUnknownBreaksOffRouteConfirmation() {
  const result = stabilizeOffRouteStatus([
    progressSample("off_route"),
    progressSample("unknown"),
    progressSample("off_route")
  ]);

  assert(result.status === "off_route_candidate", "unknown between off_route samples should prevent confirmation");
  assert(result.confirmed === false, "unknown sample should keep stabilized off_route unconfirmed");
  assert(result.consecutiveOffRouteCount === 1, "unknown sample should reset consecutive count");
}

function testAccuracyUncertainDoesNotConfirmOffRoute() {
  const result = stabilizeOffRouteStatus([
    progressSample("off_route"),
    progressSample("off_route_candidate", { reason: "accuracy_uncertain", accuracyM: 80 }),
    progressSample("off_route")
  ]);

  assert(result.status === "off_route_candidate", "accuracy_uncertain should prevent off_route confirmation");
  assert(result.confirmed === false, "accuracy_uncertain should keep off_route unconfirmed");
  assert(result.consecutiveOffRouteCount === 1, "accuracy_uncertain should reset consecutive count");
}

function testArrivalStatusUsesRouteEndpointAndAccuracy() {
  const result = evaluateRouteArrivalStatus({
    route: routeFixture(),
    currentPoint: point(37.00201, 127.0),
    progressRatio: 1,
    accuracyM: 5,
    arrivalRadiusM: 8
  });

  assert(result.reached, "point near route endpoint should be considered arrived");
  assert(result.distanceToDestinationM !== null && result.distanceToDestinationM < 2, "arrival distance should be measured");
}

function testArrivalStatusRejectsFarEndpoint() {
  const result = evaluateRouteArrivalStatus({
    route: routeFixture(),
    currentPoint: point(37.001, 127.0),
    progressRatio: 0.5,
    accuracyM: 5,
    arrivalRadiusM: 8
  });

  assert(!result.reached, "mid-route point should not be considered arrived");
  assert(result.distanceToDestinationM !== null && result.distanceToDestinationM > 100, "far arrival distance should be measured");
}

function testArrivalStatusRequiresKnownGoodAccuracy() {
  const unknown = evaluateRouteArrivalStatus({
    route: routeFixture(),
    currentPoint: point(37.00201, 127.0),
    progressRatio: 1,
    accuracyM: null,
    arrivalRadiusM: 8,
    maxAccuracyM: 20
  });
  const poor = evaluateRouteArrivalStatus({
    route: routeFixture(),
    currentPoint: point(37.00201, 127.0),
    progressRatio: 1,
    accuracyM: 40,
    arrivalRadiusM: 8,
    maxAccuracyM: 20
  });

  assert(!unknown.reached && unknown.reason === "accuracy_unknown", "unknown accuracy must block arrival");
  assert(!poor.reached && poor.reason === "accuracy_poor", "accuracy above the cap must block arrival");
}

function testArrivalRequiresConsecutiveEligibleSamples() {
  const reached = evaluateRouteArrivalStatus({
    route: routeFixture(),
    currentPoint: point(37.00201, 127.0),
    progressRatio: 1,
    accuracyM: 5,
    arrivalRadiusM: 8,
    maxAccuracyM: 20
  });
  const outside = evaluateRouteArrivalStatus({
    route: routeFixture(),
    currentPoint: point(37.001, 127.0),
    progressRatio: 0.5,
    accuracyM: 5,
    arrivalRadiusM: 8,
    maxAccuracyM: 20
  });

  assert(!stabilizeRouteArrivalStatus([reached]).reached, "one in-radius sample must not confirm arrival");
  assert(stabilizeRouteArrivalStatus([reached, reached]).reached, "two consecutive in-radius samples should confirm arrival");
  assert(
    !stabilizeRouteArrivalStatus([reached, outside, reached]).reached,
    "an outside-radius sample must reset arrival confirmation"
  );
}

function testArrivalRequiresEndProgressAndUsesRequestedDestination() {
  const nearDestinationAtRouteStart = evaluateRouteArrivalStatus({
    route: routeFixture({ polyline: [point(37, 127), point(37.003, 127), point(37.00004, 127)] }),
    destination: point(37.00004, 127),
    currentPoint: point(37.00004, 127),
    progressRatio: 0.01,
    accuracyM: 2,
    arrivalRadiusM: 8
  });
  const snappedEndpoint = evaluateRouteArrivalStatus({
    route: routeFixture(),
    destination: point(37.0028, 127),
    currentPoint: END,
    progressRatio: 1,
    accuracyM: 2,
    arrivalRadiusM: 8
  });

  assert(
    !nearDestinationAtRouteStart.reached && nearDestinationAtRouteStart.reason === "route_progress_incomplete",
    "being near a looping route destination before end progress must not finish navigation"
  );
  assert(!snappedEndpoint.reached, "reaching a TMAP endpoint far from the requested POI must not claim arrival");
}

function main() {
  testRouteOnStepsFallbackMeasuresProgress();
  testNearRouteWithinDefaultThresholdStaysOnRoute();
  testFarRouteExceedsDefaultThresholdBecomesOffRoute();
  testPoorAccuracyDoesNotImmediatelyConfirmOffRoute();
  testUnknownAccuracyNeverConfirmsOffRoute();
  testEmptyRouteIsUnknown();
  testCrossingRouteUsesPreviousProgressBranch();
  testContinuityWindowRejectsLargeForwardJump();
  testSingleOffRouteIsCandidateUntilStabilized();
  testConsecutiveOffRouteConfirmsStabilizedStatus();
  testOnRouteImmediatelyRecoversStabilizedStatus();
  testUnknownBreaksOffRouteConfirmation();
  testAccuracyUncertainDoesNotConfirmOffRoute();
  testArrivalStatusUsesRouteEndpointAndAccuracy();
  testArrivalStatusRejectsFarEndpoint();
  testArrivalStatusRequiresKnownGoodAccuracy();
  testArrivalRequiresConsecutiveEligibleSamples();
  testArrivalRequiresEndProgressAndUsesRequestedDestination();
  console.log("route progress policy checks passed");
}

main();
