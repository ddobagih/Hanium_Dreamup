export const DETECTION_CLASS_NAMES = [
  "damaged_tactile_block",
  "parked_kickboard_bicycle",
  "construction_obstacle",
  "pothole"
] as const;

export type ClassId = 0 | 1 | 2 | 3;
export type DetectionClassName = (typeof DETECTION_CLASS_NAMES)[ClassId];
export type DetectorSource = "fake" | "onnx" | "server" | "android";

export type NormalizedBBox = {
  x: number;
  y: number;
  width: number;
  height: number;
};

export type GpsFix = {
  latitude: number;
  longitude: number;
  accuracy_m: number | null;
  speed_mps?: number | null;
};

export type DetectionEvent = {
  class_id: ClassId;
  class_name: DetectionClassName;
  confidence: number;
  bbox: NormalizedBBox;
  captured_at: string;
  source: DetectorSource;
  gps: GpsFix | null;
  heading: number | null;
};

export const CLASS_LABELS: Record<DetectionClassName, string> = {
  damaged_tactile_block: "점자블록 파손",
  parked_kickboard_bicycle: "방치 킥보드/자전거",
  construction_obstacle: "공사 장애물",
  pothole: "노면 파임"
};

export function classNameForId(classId: ClassId): DetectionClassName {
  return DETECTION_CLASS_NAMES[classId];
}
