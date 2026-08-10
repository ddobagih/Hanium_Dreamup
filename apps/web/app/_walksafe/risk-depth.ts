/**
 * Admits only trusted, sufficiently confident metric depth into the shared risk context.
 * Browser capability means a session may be requested, not that depth is already active.
 */
import type { DetectionDistanceSource, TwoModelDetection } from "@/types/inference-v2";
import type { RiskEvaluationContext } from "./risk-evaluator";

export type BrowserDepthCapabilityStatus = "disabled" | "unsupported" | "ready";

export type BrowserDepthCapability = {
  status: BrowserDepthCapabilityStatus;
  source: DetectionDistanceSource | null;
  reason: string;
};

export type DepthEstimate = {
  distance_m: number;
  source: DetectionDistanceSource;
  confidence: number;
};

type NavigatorWithXr = Navigator & {
  xr?: {
    isSessionSupported?: (mode: string) => Promise<boolean>;
  };
};

type BrowserDepthCapabilityOptions = {
  enabled?: boolean;
  navigatorLike?: NavigatorWithXr | null;
};

const TRUSTED_DEPTH_SOURCES = new Set<DetectionDistanceSource>(["sensor_depth", "manual_fixture"]);
const MIN_DEPTH_CONFIDENCE = 0.5;
const MAX_DEPTH_M = 50;

function isValidDepthNumber(value: number): boolean {
  return Number.isFinite(value) && value >= 0 && value <= MAX_DEPTH_M;
}

function isTrustedDepthSource(source: DetectionDistanceSource | null | undefined): source is DetectionDistanceSource {
  return typeof source === "string" && TRUSTED_DEPTH_SOURCES.has(source);
}

function isUsableDepthEstimate(estimate: DepthEstimate): boolean {
  return (
    isValidDepthNumber(estimate.distance_m) &&
    isTrustedDepthSource(estimate.source) &&
    Number.isFinite(estimate.confidence) &&
    estimate.confidence >= MIN_DEPTH_CONFIDENCE &&
    estimate.confidence <= 1
  );
}

export function resolveBrowserDepthCapability({
  enabled = false,
  navigatorLike = typeof navigator === "undefined" ? null : (navigator as NavigatorWithXr)
}: BrowserDepthCapabilityOptions = {}): BrowserDepthCapability {
  if (!enabled) {
    return {
      status: "disabled",
      source: null,
      reason: "브라우저 depth bridge는 기본 비활성화 상태입니다."
    };
  }

  if (typeof navigatorLike?.xr?.isSessionSupported !== "function") {
    return {
      status: "unsupported",
      source: null,
      reason: "이 브라우저는 WebXR depth capability probe를 제공하지 않습니다."
    };
  }

  return {
    status: "ready",
    source: "sensor_depth",
    reason: "WebXR capability probe가 존재합니다. 실제 세션 시작은 별도 사용자 제스처/권한 뒤에만 가능합니다."
  };
}

export function depthContextFromEstimate(estimate: DepthEstimate | null | undefined): RiskEvaluationContext {
  if (!estimate || !isUsableDepthEstimate(estimate)) {
    return {};
  }

  return {
    depth: {
      distance_m: estimate.distance_m,
      confidence: estimate.confidence,
      source: estimate.source
    }
  };
}

export function depthContextFromDetection(detection: TwoModelDetection): RiskEvaluationContext {
  const distanceM = detection.distance_m;
  if (typeof distanceM !== "number" || !isValidDepthNumber(distanceM)) {
    return {};
  }

  return depthContextFromEstimate({
    distance_m: distanceM,
    source: detection.distance_source ?? "unknown",
    confidence: detection.distance_confidence ?? 0
  });
}

export function mockDepthEstimate(distanceM: number, confidence = 0.8): DepthEstimate | null {
  const estimate = {
    distance_m: distanceM,
    source: "manual_fixture" as const,
    confidence
  };

  return isUsableDepthEstimate(estimate) ? estimate : null;
}
