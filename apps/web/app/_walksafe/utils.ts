import type { GpsFix } from "@/types/inference";

export type DeviceOrientationEventWithPermission = typeof DeviceOrientationEvent & {
  requestPermission?: () => Promise<PermissionState>;
};

export function formatPercent(value: number) {
  return `${Math.round(value * 100)}%`;
}

export function formatGps(gps: GpsFix | null) {
  if (!gps) {
    return "위치 대기 중";
  }
  return `${gps.latitude.toFixed(5)}, ${gps.longitude.toFixed(5)}`;
}

export function headingLabel(value: number | null) {
  if (value === null) {
    return "대기 중";
  }

  const normalized = ((value % 360) + 360) % 360;
  const directions = ["북", "북동", "동", "남동", "남", "남서", "서", "북서"];
  return directions[Math.round(normalized / 45) % directions.length];
}

export function preferredAudioMimeType() {
  if (typeof MediaRecorder === "undefined" || typeof MediaRecorder.isTypeSupported !== "function") {
    return "";
  }

  return (
    ["audio/webm;codecs=opus", "audio/webm", "audio/mp4"].find((mimeType) => MediaRecorder.isTypeSupported(mimeType)) ?? ""
  );
}

export function destinationFromSlots(slots: Record<string, unknown>) {
  for (const key of ["destination", "place", "target"]) {
    const value = slots[key];
    if (typeof value === "string" && value.trim()) {
      return value.trim();
    }
  }
  return "";
}

export function deviceOrientationEventWithPermission() {
  if (typeof window === "undefined" || !("DeviceOrientationEvent" in window)) {
    return null;
  }

  return window.DeviceOrientationEvent as DeviceOrientationEventWithPermission;
}
