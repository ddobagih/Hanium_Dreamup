import { readFileSync } from "node:fs";
import {
  AUTO_REPORT_MIN_CONSECUTIVE_FRAMES,
  AUTO_REPORT_MIN_STABLE_MS,
  advanceAutoReportV2Gate,
  autoReportV2CooldownKey,
  canAutoReportV2,
  hasAutoReportGpsQuality,
  isAutoReportV2Target,
  parseAutoReportV2Cooldowns,
  selectAutoReportV2Detection,
  serializeAutoReportV2Cooldowns,
  shouldEmitReportUserFeedback
} from "../lib/auto-report-v2";
import { buildReportV2Metadata } from "../lib/report-api-v2";
import {
  allowServerV2FrameProcessing,
  allowServerV2ReportStorage,
  createServerV2PrivacyConsent,
  isServerV2FrameProcessingAllowed,
  isServerV2ReportStorageAllowed,
  withdrawServerV2FrameProcessing,
  withdrawServerV2ReportStorage
} from "../app/_walksafe/server-v2-privacy";
import type { GpsFixV2, TwoModelDetection } from "../types/inference-v2";

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
    model_key: "custom_tactile",
    source_model: "auto-report-policy-test",
    model_class_id: 1,
    class_name: "damaged_tactile_block",
    category: "tactile",
    confidence: 0.82,
    threshold_used: 0.75,
    captured_at: "2026-05-23T06:00:00.000Z",
    gps: null,
    heading: null,
    ...overrides,
    bbox
  };
}

function gps(accuracyM = 8): GpsFixV2 {
  return { latitude: 37.5, longitude: 127, accuracy_m: accuracyM };
}

function testDamagedTactileBlockIsAutoReportTarget() {
  assert(isAutoReportV2Target(detection()), "damaged_tactile_block should be auto-reportable");
}

function testUnifiedDamagedTactileBlockIsAutoReportTarget() {
  assert(
    isAutoReportV2Target(detection({ model_key: "unified_walksafe", source_model: "unified-policy-test", model_class_id: 8 })),
    "unified damaged_tactile_block should be auto-reportable"
  );
}

function testDamageAreaIsNotAutoReportTarget() {
  assert(
    !isAutoReportV2Target(detection({ class_name: "tactile_damage_area", model_class_id: 2 })),
    "tactile_damage_area should be auxiliary, not auto-reportable"
  );
}

function testSelectsDamagedBlockOverDamageArea() {
  const selected = selectAutoReportV2Detection([
    detection({ class_name: "tactile_damage_area", model_class_id: 2, confidence: 0.99 }),
    detection({ class_name: "damaged_tactile_block", model_class_id: 1, confidence: 0.75 })
  ]);

  assert(selected?.class_name === "damaged_tactile_block", "auto report should select damaged_tactile_block");
}

function testIgnoresGeneralObjects() {
  const selected = selectAutoReportV2Detection([
    detection({ model_key: "coco_general", class_name: "person", category: "general_obstacle", confidence: 0.99 })
  ]);

  assert(selected === null, "general objects should not be auto-reportable");
}

function testAutomaticReportRequiresHighQualityGps() {
  assert(hasAutoReportGpsQuality(gps(15)), "15m GPS should pass the auto-report quality gate");
  assert(!hasAutoReportGpsQuality(gps(15.01)), "GPS worse than 15m should fail the auto-report quality gate");
  assert(
    !hasAutoReportGpsQuality({ latitude: 37.5, longitude: 127, accuracy_m: null }),
    "GPS without an accuracy estimate should fail the auto-report quality gate"
  );
  const blocked = advanceAutoReportV2Gate(null, [detection()], gps(20));
  assert(blocked.reason === "gps_quality" && blocked.state === null, "poor GPS should reset automatic report stability");
}

function testReportMetadataDeclaresWebCoordinateGate() {
  const automatic = buildReportV2Metadata(detection(), "auto");
  const voice = buildReportV2Metadata(detection(), "voice");
  assert(automatic.coordinate_gate_status === "pass", "web report must declare its normalized coordinate gate");
  assert(
    automatic.bbox_coordinate_space === "normalized_camera_frame",
    "web report must identify the bbox coordinate space"
  );
  assert(automatic.auto_reported === true, "auto trigger must set auto_reported=true");
  assert(voice.auto_reported === false, "voice trigger must set auto_reported=false");
}

function testAutomaticReportRequiresConfidenceAndThreeDistinctFrames() {
  const first = advanceAutoReportV2Gate(
    null,
    [detection({ captured_at: "2026-05-23T06:00:00.000Z" })],
    gps()
  );
  const duplicateFrame = advanceAutoReportV2Gate(
    first.state,
    [detection({ captured_at: "2026-05-23T06:00:00.000Z" })],
    gps()
  );
  const second = advanceAutoReportV2Gate(
    duplicateFrame.state,
    [detection({ captured_at: "2026-05-23T06:00:00.500Z", bbox: { x: 0.21 } })],
    gps()
  );
  const third = advanceAutoReportV2Gate(
    second.state,
    [detection({ captured_at: "2026-05-23T06:00:01.000Z", bbox: { x: 0.22 } })],
    gps()
  );

  assert(first.state?.consecutiveFrames === 1 && first.target === null, "first frame should only start stability");
  assert(duplicateFrame.state?.consecutiveFrames === 1, "the same captured frame must not advance stability");
  assert(second.state?.consecutiveFrames === 2 && second.target === null, "second frame should still wait");
  assert(third.state?.consecutiveFrames === AUTO_REPORT_MIN_CONSECUTIVE_FRAMES, "third frame should complete stability");
  assert(third.target?.class_name === "damaged_tactile_block", "only stable damaged tactile block should be submitted");

  const lowConfidence = advanceAutoReportV2Gate(
    third.state,
    [detection({ confidence: 0.69, captured_at: "2026-05-23T06:00:01.500Z" })],
    gps()
  );
  assert(lowConfidence.reason === "confidence" && lowConfidence.state === null, "low confidence should reset stability");
}

function testAutomaticReportAlsoRequiresMinimumStableDuration() {
  const first = advanceAutoReportV2Gate(
    null,
    [detection({ captured_at: "2026-05-23T06:00:00.000Z" })],
    gps()
  );
  const second = advanceAutoReportV2Gate(
    first.state,
    [detection({ captured_at: "2026-05-23T06:00:00.250Z", bbox: { x: 0.21 } })],
    gps()
  );
  const tooFastThird = advanceAutoReportV2Gate(
    second.state,
    [detection({ captured_at: "2026-05-23T06:00:00.500Z", bbox: { x: 0.22 } })],
    gps()
  );
  const stableFourth = advanceAutoReportV2Gate(
    tooFastThird.state,
    [detection({ captured_at: `2026-05-23T06:00:00.${AUTO_REPORT_MIN_STABLE_MS}Z`, bbox: { x: 0.23 } })],
    gps()
  );

  assert(tooFastThird.state?.consecutiveFrames === 3, "three unique frames should still be counted");
  assert(tooFastThird.target === null && tooFastThird.reason === "stabilizing", "three frames inside 700ms must not report");
  assert(stableFourth.target !== null, "the same candidate may report only after both frame and duration gates pass");
}

function testAutomaticReportRequiresTheSameSpatialCandidate() {
  const first = advanceAutoReportV2Gate(null, [detection()], gps());
  const moved = advanceAutoReportV2Gate(
    first.state,
    [
      detection({
        captured_at: "2026-05-23T06:00:00.500Z",
        bbox: { x: 0.72, y: 0.72, width: 0.12, height: 0.12 }
      })
    ],
    gps()
  );
  assert(moved.state?.consecutiveFrames === 1, "a non-overlapping candidate should start a new stability sequence");
}

function testAutomaticReportRequiresContinuousGps() {
  const first = advanceAutoReportV2Gate(null, [detection()], gps());
  const jumpedGps = { ...gps(), latitude: 37.51 };
  const jumped = advanceAutoReportV2Gate(
    first.state,
    [detection({ captured_at: "2026-05-23T06:00:00.500Z" })],
    jumpedGps
  );

  assert(jumped.reason === "gps_continuity", "a large GPS jump must reset automatic report stability");
  assert(jumped.state?.consecutiveFrames === 1, "a new location must begin a new three-frame sequence");
}

function testAllReportOutcomesEmitAccessibleFeedback() {
  assert(shouldEmitReportUserFeedback("auto"), "automatic report success or failure needs nonvisual feedback");
  assert(shouldEmitReportUserFeedback("voice"), "explicit voice report needs completion feedback");
}

function testCooldownCanSurviveSameTabReload() {
  const now = Date.parse("2026-05-23T06:05:00.000Z");
  const target = detection();
  const location = gps();
  const key = autoReportV2CooldownKey(target, location);
  const original = new Map([[key, now - 1_000]]);
  const restored = parseAutoReportV2Cooldowns(serializeAutoReportV2Cooldowns(original, now), now);

  assert(!canAutoReportV2(restored, target, location, now), "restored session cooldown should suppress a duplicate auto report");
  assert(parseAutoReportV2Cooldowns("not-json", now).size === 0, "corrupt session data should be ignored safely");
  assert(
    parseAutoReportV2Cooldowns(JSON.stringify([[key, now - 10 * 60 * 1000]]), now).size === 0,
    "expired cooldown entries should be discarded"
  );
}

function testServerV2PrivacyConsentIsFailClosedAndPurposeSeparated() {
  const initial = createServerV2PrivacyConsent();
  assert(!isServerV2FrameProcessingAllowed(initial), "server-v2 frame processing must default to off");
  assert(!isServerV2ReportStorageAllowed(initial), "persistent report storage must default to off");
  assert(
    !isServerV2FrameProcessingAllowed({ frameProcessingAllowed: "true", reportStorageAllowed: true } as never),
    "truthy or malformed processing consent must fail closed"
  );

  const reportWithoutProcessing = allowServerV2ReportStorage(initial);
  assert(
    !isServerV2ReportStorageAllowed(reportWithoutProcessing),
    "report storage consent must not activate before frame processing consent"
  );

  const processingOnly = allowServerV2FrameProcessing(initial);
  assert(isServerV2FrameProcessingAllowed(processingOnly), "frame processing consent should enable server detection");
  assert(
    !isServerV2ReportStorageAllowed(processingOnly),
    "frame processing consent must not imply persistent automatic report consent"
  );

  const bothAllowed = allowServerV2ReportStorage(processingOnly);
  assert(isServerV2ReportStorageAllowed(bothAllowed), "a second explicit consent should enable report storage");

  const reportWithdrawn = withdrawServerV2ReportStorage(bothAllowed);
  assert(
    isServerV2FrameProcessingAllowed(reportWithdrawn),
    "withdrawing report storage must leave consented server detection active"
  );
  assert(!isServerV2ReportStorageAllowed(reportWithdrawn), "report withdrawal must stop report storage");

  const processingWithdrawn = withdrawServerV2FrameProcessing();
  assert(!isServerV2FrameProcessingAllowed(processingWithdrawn), "processing withdrawal must stop detection transfer");
  assert(!isServerV2ReportStorageAllowed(processingWithdrawn), "processing withdrawal must also stop dependent reports");
}

function testServerV2RetentionPolicyIsExecutable() {
  const canonicalRetentionPolicy = readFileSync("../../docs/operations/data_retention_policy.md", "utf8");
  const retentionImplementation = readFileSync("../../scripts/check_report_retention_dry_run.py", "utf8");

  assert(
    canonicalRetentionPolicy.includes("| 실제 신고 row | 180일 |") &&
      canonicalRetentionPolicy.includes("| 업로드 이미지 | 180일 |") &&
      retentionImplementation.includes('"active": 180') &&
      retentionImplementation.includes('"resolved": 180'),
    "the 180-day UI disclosure must stay backed by canonical policy and executable retention buckets"
  );
}

function main() {
  testDamagedTactileBlockIsAutoReportTarget();
  testUnifiedDamagedTactileBlockIsAutoReportTarget();
  testDamageAreaIsNotAutoReportTarget();
  testSelectsDamagedBlockOverDamageArea();
  testIgnoresGeneralObjects();
  testAutomaticReportRequiresHighQualityGps();
  testReportMetadataDeclaresWebCoordinateGate();
  testAutomaticReportRequiresConfidenceAndThreeDistinctFrames();
  testAutomaticReportAlsoRequiresMinimumStableDuration();
  testAutomaticReportRequiresTheSameSpatialCandidate();
  testAutomaticReportRequiresContinuousGps();
  testAllReportOutcomesEmitAccessibleFeedback();
  testCooldownCanSurviveSameTabReload();
  testServerV2PrivacyConsentIsFailClosedAndPurposeSeparated();
  testServerV2RetentionPolicyIsExecutable();
  console.log("auto-report v2 policy checks passed");
}

main();
