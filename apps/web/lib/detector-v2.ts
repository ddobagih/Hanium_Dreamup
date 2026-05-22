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
    bench: "벤치"
  };

  return labels[detection.class_name] ?? detection.class_name;
}

export function createFakeTwoModelDetections(index: number, gps: GpsFixV2 | null, heading: number | null) {
  const capturedAt = new Date().toISOString();
  const tactileDamage = index % 3 !== 1;
  const primaryTactileClass = tactileDamage ? "tactile_damage_area" : "normal_tactile_block";
  const tactileDetections: TwoModelDetection[] = [
    {
      schema_version: "detect.v2",
      model_key: "custom_tactile",
      source_model: "fake-custom-tactile-v2",
      model_class_id: tactileDamage ? 2 : 0,
      class_name: primaryTactileClass,
      category: tactileDamage ? "tactile_damage" : "tactile_normal",
      confidence: tactileDamage ? 0.86 + (index % 2) * 0.05 : 0.82,
      bbox: TACTILE_BOXES[index % TACTILE_BOXES.length],
      threshold_used: 0.55,
      captured_at: capturedAt,
      gps,
      heading
    },
    {
      schema_version: "detect.v2",
      model_key: "custom_tactile",
      source_model: "fake-custom-tactile-v2",
      model_class_id: 0,
      class_name: "normal_tactile_block",
      category: "tactile_normal",
      confidence: 0.72,
      bbox: TACTILE_BOXES[(index + 1) % TACTILE_BOXES.length],
      threshold_used: 0.55,
      captured_at: capturedAt,
      gps,
      heading
    }
  ];

  const generalClassIds = {
    person: 0,
    car: 1,
    bicycle: 4,
    "traffic light": 6,
    bench: 7
  } as const;
  const generalClasses = ["car", "person", "bicycle", "traffic light", "bench"] as const;
  const primaryGeneralClass = generalClasses[index % generalClasses.length];
  const generalDetections: TwoModelDetection[] = [
    {
      schema_version: "detect.v2",
      model_key: "coco_general",
      source_model: "fake-coco-general-v2",
      model_class_id: generalClassIds[primaryGeneralClass],
      class_name: primaryGeneralClass,
      category:
        primaryGeneralClass === "car"
          ? "vehicle"
          : primaryGeneralClass === "person" || primaryGeneralClass === "bicycle"
            ? "vulnerable_road_user"
            : primaryGeneralClass === "traffic light"
              ? "traffic_signal"
              : "street_furniture",
      confidence: 0.76 + (index % 3) * 0.04,
      bbox: GENERAL_BOXES[index % GENERAL_BOXES.length],
      threshold_used: 0.35,
      captured_at: capturedAt,
      gps,
      heading
    },
    {
      schema_version: "detect.v2",
      model_key: "coco_general",
      source_model: "fake-coco-general-v2",
      model_class_id: generalClassIds.person,
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

  const detections = [...tactileDetections, ...generalDetections];
  return {
    detections,
    ...selectTwoModelPriorityDetections(detections)
  };
}
