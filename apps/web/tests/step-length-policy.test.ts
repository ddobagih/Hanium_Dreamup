import {
  createStoredStepLengthCalibration,
  estimateStepLengthFromGpsAndMotion,
  measureValidWalkingDistanceM,
  parseStoredStepLengthCalibration,
  summarizeStepLengthEvidence,
  type GpsStepLengthSample
} from "../app/_walksafe/step-length";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

function sample(latitude: number, observedAtMs: number): GpsStepLengthSample {
  return { latitude, longitude: 127, accuracy_m: 8, observedAtMs };
}

function testWaitsForEnoughWalkingEvidence() {
  const result = estimateStepLengthFromGpsAndMotion([sample(37, 0), sample(37.00001, 1000)], [500], 0.65);

  assert(result.status === "collecting", "short movement should keep collecting");
  assert(result.source === "default", "short movement should keep default source");
  assert(result.stepLengthM === 0.65, "short movement should keep default step length");
  assert(result.confidence === 0, "short movement should not claim confidence");
}

function testEstimatesStepLengthFromGpsDistanceAndStepCount() {
  const samples = [sample(37, 0), sample(37.000036, 4000), sample(37.000072, 8000)];
  const steps = [500, 1000, 1500, 2200, 2800, 3500, 4300, 5100, 5900, 6700, 7500, 7900];
  const result = estimateStepLengthFromGpsAndMotion(samples, steps, 0.65);

  assert(result.status === "estimated", "enough GPS and motion evidence should estimate");
  assert(result.source === "gps_motion", "estimated value should use gps_motion source");
  assert(result.stepLengthM > 0.55 && result.stepLengthM < 0.85, "estimated step length should be plausible");
  assert(result.confidence >= 0.45, "estimated value should expose confidence");
}

function testIgnoresGpsJumpTooFastForWalking() {
  const samples = [sample(37, 0), sample(37.001, 1000)];
  const distance = measureValidWalkingDistanceM(samples);
  const evidence = summarizeStepLengthEvidence(samples, [500]);

  assert(distance === 0, "GPS jump faster than walking should be ignored");
  assert(evidence.ignoredSegmentCount === 1, "ignored GPS jump should be counted as outlier");
}

function testStoredCalibrationTtlAndFallback() {
  const now = 100_000;
  const stored = createStoredStepLengthCalibration(
    estimateStepLengthFromGpsAndMotion(
      [sample(37, 0), sample(37.000036, 4000), sample(37.000072, 8000)],
      [500, 1000, 1500, 2200, 2800, 3500, 4300, 5100, 5900, 6700, 7500, 7900],
      0.65
    ),
    now
  );
  assert(stored !== null, "good calibration should be persisted");
  const parsed = parseStoredStepLengthCalibration(JSON.stringify(stored), now + 1000);
  const expired = parseStoredStepLengthCalibration(JSON.stringify(stored), stored.expiresAtMs + 1);
  const fallback = estimateStepLengthFromGpsAndMotion([], [], 0.65, parsed);

  assert(parsed !== null, "unexpired stored calibration should parse");
  assert(expired === null, "expired stored calibration should be dropped");
  assert(fallback.source === "stored_calibration", "stored calibration should be used while new samples collect");
  assert(fallback.status === "fallback", "stored calibration should be labelled fallback");
}

function main() {
  testWaitsForEnoughWalkingEvidence();
  testEstimatesStepLengthFromGpsDistanceAndStepCount();
  testIgnoresGpsJumpTooFastForWalking();
  testStoredCalibrationTtlAndFallback();
  console.log("step length policy checks passed");
}

main();
