import {
  normalizeScreenOrientationAngle,
  readScreenOrientationAngle,
  resolveAbsoluteHeadingDegrees
} from "../app/_walksafe/absolute-heading";
import { projectFutureMotion } from "../app/_walksafe/motion-projection";
import {
  isFreshCameraFrameObservation,
  isLiveRearCameraTrack
} from "../app/_walksafe/camera-policy";
import {
  GPS_FIX_MAX_AGE_MS,
  GPS_FIX_MAX_FUTURE_SKEW_MS,
  HEADING_FIX_MAX_AGE_MS,
  gpsFixExpiryDelayMs,
  isHeadingFixFresh,
  isGpsFixFresh
} from "../app/_walksafe/gps-freshness";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

function testRelativeAlphaFailsClosed() {
  const heading = resolveAbsoluteHeadingDegrees({
    eventType: "deviceorientation",
    alpha: 45,
    beta: 90,
    gamma: 0,
    absolute: false,
    screenOrientationAngle: 0
  });
  assert(heading === null, "relative deviceorientation alpha must never become a compass heading");

  const motion = projectFutureMotion({
    gps: { latitude: 37, longitude: 127, accuracy_m: 5, speed_mps: 1 },
    headingDeg: heading,
    routeBearingDeg: 90,
    gpsSpeedMps: 1,
    stepSpeedMps: 1,
    motionStability: 0.9,
    horizonS: 4
  });
  assert(motion.headingDeg === null, "relative heading must stay null in future-motion context");
  assert(motion.headingRouteDeltaDeg === null && motion.screenShiftX === 0, "unknown heading must not shift the ROI");
}

function testStandardAbsoluteOrientationUsesCameraFacingVector() {
  const east = resolveAbsoluteHeadingDegrees({
    eventType: "deviceorientation",
    alpha: 270,
    beta: 90,
    gamma: 0,
    absolute: true,
    screenOrientationAngle: 0
  });
  assert(east === 90, "vertical back-of-screen vector should face east for this absolute orientation");

  const rotated = resolveAbsoluteHeadingDegrees({
    eventType: "deviceorientationabsolute",
    alpha: 270,
    beta: 90,
    gamma: 0,
    absolute: false,
    screenOrientationAngle: 90
  });
  assert(rotated === 90, "screen UI rotation must not rotate the physical camera-facing vector");

  const flat = resolveAbsoluteHeadingDegrees({
    eventType: "deviceorientationabsolute",
    alpha: 270,
    beta: 0,
    gamma: 0,
    absolute: true,
    screenOrientationAngle: 0
  });
  assert(flat === null, "a screen-back vector with no horizontal component must fail closed");
}

function testWebkitCompassRequiresUsableAbsoluteReading() {
  const heading = resolveAbsoluteHeadingDegrees({
    eventType: "deviceorientation",
    alpha: 123,
    beta: 90,
    gamma: 0,
    absolute: false,
    webkitCompassHeading: 25,
    webkitCompassAccuracy: 8,
    screenOrientationAngle: -90
  });
  assert(heading === 295, "webkit compass heading should be corrected for legacy screen orientation");

  const uncalibrated = resolveAbsoluteHeadingDegrees({
    eventType: "deviceorientation",
    alpha: 123,
    beta: 90,
    gamma: 0,
    absolute: false,
    webkitCompassHeading: 25,
    webkitCompassAccuracy: -1,
    screenOrientationAngle: 0
  });
  assert(uncalibrated === null, "uncalibrated WebKit compass data must fail closed");

  const inaccurate = resolveAbsoluteHeadingDegrees({
    eventType: "deviceorientation",
    alpha: 123,
    beta: 90,
    gamma: 0,
    absolute: false,
    webkitCompassHeading: 25,
    webkitCompassAccuracy: 36,
    screenOrientationAngle: 0
  });
  assert(inaccurate === null, "poor WebKit compass accuracy must not admit route-aligned tactile steering");
}

function testMissingOrInvalidScreenOrientationFailsClosed() {
  assert(normalizeScreenOrientationAngle(-90) === 270, "legacy clockwise orientation should normalize to 270 degrees");
  assert(normalizeScreenOrientationAngle(45) === null, "non-orthogonal screen angle is not a trusted screen orientation");
  const modernWindow = {
    screen: { orientation: { angle: 90 } },
    orientation: -90
  } as unknown as Window;
  assert(readScreenOrientationAngle(modernWindow) === 90, "standard Screen Orientation angle should take precedence");
  const legacyWindow = {
    screen: {},
    orientation: -90
  } as unknown as Window;
  assert(readScreenOrientationAngle(legacyWindow) === 270, "legacy clockwise screen orientation should remain usable");
  const missing = resolveAbsoluteHeadingDegrees({
    eventType: "deviceorientationabsolute",
    alpha: 0,
    beta: 90,
    gamma: 0,
    absolute: true,
    screenOrientationAngle: null
  });
  assert(missing === 0, "standard camera-facing heading must not depend on UI screen rotation metadata");
  const missingWebkitFrame = resolveAbsoluteHeadingDegrees({
    eventType: "deviceorientation",
    alpha: null,
    beta: null,
    gamma: null,
    absolute: false,
    webkitCompassHeading: 20,
    screenOrientationAngle: null
  });
  assert(missingWebkitFrame === null, "WebKit screen-top heading without a known screen frame must fail closed");
}

function testWalkingCameraRequiresLiveEnvironmentTrack() {
  assert(isLiveRearCameraTrack("live", "environment"), "a live rear camera track should pass");
  assert(!isLiveRearCameraTrack("live", "user"), "a front camera track must fail closed");
  assert(!isLiveRearCameraTrack("ended", "environment"), "an ended rear track must not stay camera-ready");
  assert(!isLiveRearCameraTrack("live", undefined), "unknown facing mode must not be assumed to be rear-facing");
}

function testCameraFrameMustAdvanceWithinTheSameStream() {
  const first = { generation: 3, mediaTime: 1, presentedFrames: 10 };
  assert(isFreshCameraFrameObservation(null, first), "the first valid frame in a stream should pass");
  assert(
    !isFreshCameraFrameObservation(first, { generation: 3, mediaTime: 1, presentedFrames: 11 }),
    "a repeated media timestamp must not become a new inference frame"
  );
  assert(
    !isFreshCameraFrameObservation(first, { generation: 3, mediaTime: 1.1, presentedFrames: 10 }),
    "a repeated presented-frame counter must fail closed"
  );
  assert(
    isFreshCameraFrameObservation(first, { generation: 3, mediaTime: 1.1, presentedFrames: 11 }),
    "both media time and presented-frame counter advancing should pass"
  );
  assert(
    isFreshCameraFrameObservation(first, { generation: 4, mediaTime: 0, presentedFrames: 1 }),
    "a restarted camera generation may reset its media clock"
  );
}

function testGpsFixFreshnessExpiresAtTenSeconds() {
  const observedAt = 1_000;
  assert(isGpsFixFresh(observedAt, observedAt + GPS_FIX_MAX_AGE_MS - 1), "a fix inside the 10 second window should be fresh");
  assert(!isGpsFixFresh(observedAt, observedAt + GPS_FIX_MAX_AGE_MS), "a 10 second old fix must fail closed");
  assert(gpsFixExpiryDelayMs(observedAt, observedAt + 4_000) === 6_000, "expiry scheduling must use observation age");
  assert(gpsFixExpiryDelayMs(Number.NaN, observedAt) === 0, "invalid observation time must expire immediately");
  assert(
    !isGpsFixFresh(observedAt + GPS_FIX_MAX_FUTURE_SKEW_MS + 1, observedAt),
    "a materially future-dated GPS fix must fail closed"
  );
}

function testHeadingFreshnessExpiresAtThreeSeconds() {
  const observedAt = 1_000;
  assert(
    isHeadingFixFresh(observedAt, observedAt + HEADING_FIX_MAX_AGE_MS - 1),
    "a heading inside the freshness window should pass"
  );
  assert(
    !isHeadingFixFresh(observedAt, observedAt + HEADING_FIX_MAX_AGE_MS),
    "a three-second-old heading must fail closed"
  );
  assert(!isHeadingFixFresh(null, observedAt), "a heading without an observation timestamp must fail closed");
}

function main() {
  testRelativeAlphaFailsClosed();
  testStandardAbsoluteOrientationUsesCameraFacingVector();
  testWebkitCompassRequiresUsableAbsoluteReading();
  testMissingOrInvalidScreenOrientationFailsClosed();
  testWalkingCameraRequiresLiveEnvironmentTrack();
  testCameraFrameMustAdvanceWithinTheSameStream();
  testGpsFixFreshnessExpiresAtTenSeconds();
  testHeadingFreshnessExpiresAtThreeSeconds();
  console.log("absolute sensor heading policy checks passed");
}

main();
