import { isAutoReportV2Target, selectAutoReportV2Detection } from "../lib/auto-report-v2";
import type { TwoModelDetection } from "../types/inference-v2";

type DetectionOverrides = Partial<TwoModelDetection> & {
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

function main() {
  testDamagedTactileBlockIsAutoReportTarget();
  testUnifiedDamagedTactileBlockIsAutoReportTarget();
  testDamageAreaIsNotAutoReportTarget();
  testSelectsDamagedBlockOverDamageArea();
  testIgnoresGeneralObjects();
  console.log("auto-report v2 policy checks passed");
}

main();
