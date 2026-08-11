import type { GpsFixV2, TwoModelDetection } from "@/types/inference-v2";

const AUTO_REPORT_CLASSES = new Set(["damaged_tactile_block"]);
const AUTO_REPORT_MODEL_KEYS = new Set(["custom_tactile", "unified_walksafe"]);
export const AUTO_REPORT_COOLDOWN_MS = 10 * 60 * 1000;
export const AUTO_REPORT_LOCATION_PRECISION = 4;

export function isAutoReportV2Target(detection: TwoModelDetection): boolean {
  return AUTO_REPORT_MODEL_KEYS.has(detection.model_key) && AUTO_REPORT_CLASSES.has(detection.class_name);
}

export function selectAutoReportV2Detection(detections: readonly TwoModelDetection[]): TwoModelDetection | null {
  return detections
    .filter(isAutoReportV2Target)
    .sort((a, b) => b.confidence - a.confidence)[0] ?? null;
}

export function autoReportV2CooldownKey(detection: TwoModelDetection, gps: GpsFixV2): string {
  return [
    detection.class_name,
    gps.latitude.toFixed(AUTO_REPORT_LOCATION_PRECISION),
    gps.longitude.toFixed(AUTO_REPORT_LOCATION_PRECISION)
  ].join(":");
}

export function canAutoReportV2(
  cooldowns: ReadonlyMap<string, number>,
  detection: TwoModelDetection,
  gps: GpsFixV2,
  now = Date.now()
): boolean {
  const lastReportedAt = cooldowns.get(autoReportV2CooldownKey(detection, gps));
  return lastReportedAt === undefined || now - lastReportedAt >= AUTO_REPORT_COOLDOWN_MS;
}
