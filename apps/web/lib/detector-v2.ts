/**
 * Generates deterministic fake-v2 detections for demo presentation and policy testing only.
 * It does not run model inference or authorize persistent reporting.
 */
import { selectTwoModelPriorityDetections } from "@/lib/two-model-priority";
import type { GpsFixV2, TwoModelDetection } from "@/types/inference-v2";

const TACTILE_BOXES = [
  { x: 0.20, y: 0.56, width: 0.34, height: 0.20 },
  { x: 0.38, y: 0.48, width: 0.28, height: 0.18 },
  { x: 0.14, y: 0.60, width: 0.42, height: 0.16 }
];

const GENERAL_BOXES = [
  { x: 0.58, y: 0.30, width: 0.20, height: 0.34 },
  { x: 0.08, y: 0.36, width: 0.18, height: 0.28 },
  { x: 0.68, y: 0.52, width: 0.18, height: 0.18 }
];

export function labelForTwoModelDetection(detection: TwoModelDetection) {
  const labels: Record<string, string> = {
    tactile_damage_area: "점자블록 파손 영역",
    damaged_tactile_block: "파손 점자블록",
    normal_tactile_block: "정상 점자블록",
    car: "차량",
    bus: "버스",
    truck: "트럭",
    motorcycle: "오토바이",
    person: "보행자",
    bicycle: "자전거",
    "traffic light": "신호등",
    crosswalk: "횡단보도",
    curb_step: "보도 턱",
    uneven_sidewalk: "고르지 않은 보도",
    e_scooter_obstruction: "방치 킥보드",
    bench: "벤치"
  };

  return labels[detection.class_name] ?? detection.class_name;
}

export function createFakeTwoModelDetections(index: number, gps: GpsFixV2 | null, heading: number | null) {
  const capturedAt = new Date().toISOString();
  const unifiedClasses = [
    { className: "damaged_tactile_block", classId: 8, category: "tactile_damage", confidence: 0.86 + (index % 2) * 0.05, bbox: TACTILE_BOXES[index % TACTILE_BOXES.length] },
    { className: "normal_tactile_block", classId: 7, category: "tactile_normal", confidence: 0.72, bbox: TACTILE_BOXES[(index + 1) % TACTILE_BOXES.length] },
    { className: "crosswalk", classId: 9, category: "path_guidance", confidence: 0.78, bbox: { x: 0.10, y: 0.68, width: 0.74, height: 0.16 } },
    { className: "curb_step", classId: 10, category: "surface_hazard", confidence: 0.74, bbox: { x: 0.18, y: 0.58, width: 0.62, height: 0.14 } },
    { className: "uneven_sidewalk", classId: 11, category: "surface_hazard", confidence: 0.76, bbox: { x: 0.26, y: 0.54, width: 0.44, height: 0.22 } },
    { className: "e_scooter_obstruction", classId: 12, category: "obstruction", confidence: 0.82, bbox: GENERAL_BOXES[(index + 2) % GENERAL_BOXES.length] }
  ] as const;
  const generalClasses = [
    { className: "car", classId: 2, category: "vehicle", confidence: 0.76 + (index % 3) * 0.04 },
    { className: "person", classId: 0, category: "vulnerable_road_user", confidence: 0.68 },
    { className: "bicycle", classId: 1, category: "vulnerable_road_user", confidence: 0.70 },
    { className: "traffic light", classId: 6, category: "traffic_signal", confidence: 0.66 }
  ] as const;

  const primaryCustom = unifiedClasses[index % unifiedClasses.length];
  const primaryGeneral = generalClasses[index % generalClasses.length];
  const detections: TwoModelDetection[] = [
    {
      schema_version: "detect.v2",
      model_key: "unified_walksafe",
      source_model: "fake-unified-walksafe-v2",
      model_class_id: primaryCustom.classId,
      class_name: primaryCustom.className,
      category: primaryCustom.category,
      confidence: primaryCustom.confidence,
      bbox: primaryCustom.bbox,
      threshold_used: 0.35,
      captured_at: capturedAt,
      gps,
      heading
    },
    {
      schema_version: "detect.v2",
      model_key: "unified_walksafe",
      source_model: "fake-unified-walksafe-v2",
      model_class_id: primaryGeneral.classId,
      class_name: primaryGeneral.className,
      category: primaryGeneral.category,
      confidence: primaryGeneral.confidence,
      bbox: GENERAL_BOXES[index % GENERAL_BOXES.length],
      threshold_used: 0.35,
      captured_at: capturedAt,
      gps,
      heading
    },
    {
      schema_version: "detect.v2",
      model_key: "unified_walksafe",
      source_model: "fake-unified-walksafe-v2",
      model_class_id: 0,
      class_name: "person",
      category: "vulnerable_road_user",
      confidence: 0.68,
      bbox: GENERAL_BOXES[(index + 1) % GENERAL_BOXES.length],
      threshold_used: 0.35,
      captured_at: capturedAt,
      gps,
      heading
    }
  ];

  return {
    detections,
    ...selectTwoModelPriorityDetections(detections)
  };
}
