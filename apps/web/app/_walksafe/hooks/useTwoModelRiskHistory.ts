"use client";

import { useEffect, useId, useMemo } from "react";
import type { TwoModelDetection } from "@/types/inference-v2";
import { buildRoiRiskContext } from "../risk-roi";
import {
  buildBBoxHistoryRiskContext,
  trackingKeyForDetection,
  type BBoxHistorySample,
  type RiskObjectTracking,
  type RiskEvaluationContext
} from "../risk-evaluator";
import { depthContextFromDetection } from "../risk-depth";

const MAX_HISTORY_SAMPLES_PER_OBJECT = 12;
const historiesByHook = new Map<string, Map<string, BBoxHistorySample[]>>();

type UseTwoModelRiskHistoryOptions = {
  heading?: number | null;
  walkingSpeedMps?: number | null;
  motionStability?: number | null;
};

function historyKey(detection: TwoModelDetection): string {
  return trackingKeyForDetection(detection);
}

function observedAtMs(detection: TwoModelDetection): number {
  const parsed = Date.parse(detection.captured_at);
  return Number.isFinite(parsed) ? parsed : Date.now();
}

function buildExplicitDepthRiskContext(detection: TwoModelDetection): RiskEvaluationContext {
  return depthContextFromDetection(detection);
}

function withExplicitDepthContext(
  detection: TwoModelDetection,
  context: RiskEvaluationContext
): RiskEvaluationContext {
  const depthContext = buildExplicitDepthRiskContext(detection);
  if (!depthContext.depth) {
    return context;
  }

  return {
    ...context,
    depth: depthContext.depth
  };
}

function mergeTracking(
  historyTracking: RiskObjectTracking | undefined,
  roiTracking: RiskObjectTracking | undefined
): RiskObjectTracking | undefined {
  if (!historyTracking && !roiTracking) {
    return undefined;
  }

  return {
    ...roiTracking,
    ...historyTracking,
    stable_frames: Math.max(historyTracking?.stable_frames ?? 0, roiTracking?.stable_frames ?? 0),
    stable_ms: Math.max(historyTracking?.stable_ms ?? 0, roiTracking?.stable_ms ?? 0),
    blocking_path: historyTracking?.blocking_path ?? roiTracking?.blocking_path,
    projected_path_intersection: historyTracking?.projected_path_intersection ?? roiTracking?.projected_path_intersection
  };
}

function mergeRiskContexts(historyContext: RiskEvaluationContext, roiContext: RiskEvaluationContext): RiskEvaluationContext {
  const tracking = mergeTracking(historyContext.tracking, roiContext.tracking);
  return {
    ...roiContext,
    ...historyContext,
    imu: {
      ...roiContext.imu,
      ...historyContext.imu
    },
    segmentation: {
      ...roiContext.segmentation,
      ...historyContext.segmentation
    },
    ...(tracking ? { tracking } : {})
  };
}

export function useTwoModelRiskHistory(
  detection: TwoModelDetection | null,
  options: UseTwoModelRiskHistoryOptions = {}
): RiskEvaluationContext {
  const hookId = useId();

  const context = useMemo(() => {
    if (!detection) {
      return {};
    }

    const key = historyKey(detection);
    const sample = { detection, observed_at_ms: observedAtMs(detection) };
    const histories = historiesByHook.get(hookId) ?? new Map<string, BBoxHistorySample[]>();
    const previous = histories.get(key) ?? [];
    const historyContext = buildBBoxHistoryRiskContext(detection, [...previous, sample], {
      motion_stability: options.motionStability
    });
    const roiContext = buildRoiRiskContext(detection, {
      motion: {
        heading_deg: options.heading,
        walking_speed_mps: options.walkingSpeedMps
      }
    });

    return withExplicitDepthContext(detection, mergeRiskContexts(historyContext, roiContext));
  }, [detection, hookId, options.heading, options.motionStability, options.walkingSpeedMps]);

  useEffect(() => {
    if (!detection) {
      return;
    }

    const key = historyKey(detection);
    const sample = { detection, observed_at_ms: observedAtMs(detection) };
    const histories = historiesByHook.get(hookId) ?? new Map<string, BBoxHistorySample[]>();
    const previous = histories.get(key) ?? [];
    const last = previous.at(-1);
    const next =
      last?.detection.captured_at === detection.captured_at &&
      last.detection.bbox.x === detection.bbox.x &&
      last.detection.bbox.y === detection.bbox.y &&
      last.detection.bbox.width === detection.bbox.width &&
      last.detection.bbox.height === detection.bbox.height
        ? previous
        : [...previous, sample].slice(-MAX_HISTORY_SAMPLES_PER_OBJECT);

    histories.set(key, next);
    historiesByHook.set(hookId, histories);
  }, [detection, hookId]);

  useEffect(() => () => {
    historiesByHook.delete(hookId);
  }, [hookId]);

  return context;
}
