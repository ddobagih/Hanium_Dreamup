import {
  classNameForId,
  type ClassId,
  type DetectionEvent,
  type GpsFix,
  type NormalizedBBox
} from "@/types/inference";

const FAKE_BOXES: NormalizedBBox[] = [
  { x: 0.18, y: 0.46, width: 0.38, height: 0.24 },
  { x: 0.48, y: 0.34, width: 0.34, height: 0.32 },
  { x: 0.12, y: 0.28, width: 0.42, height: 0.38 },
  { x: 0.34, y: 0.58, width: 0.30, height: 0.20 }
];

export function createFakeDetection(index: number, gps: GpsFix | null, heading: number | null): DetectionEvent {
  const classId = (index % 4) as ClassId;

  return {
    class_id: classId,
    class_name: classNameForId(classId),
    confidence: 0.78 + (index % 3) * 0.06,
    bbox: FAKE_BOXES[classId],
    captured_at: new Date().toISOString(),
    source: "fake",
    gps,
    heading
  };
}
