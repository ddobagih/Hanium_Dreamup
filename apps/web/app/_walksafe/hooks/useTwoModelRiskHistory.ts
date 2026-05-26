"use client";

import { useEffect, useId, useMemo } from "react";
import type { TwoModelDetection } from "@/types/inference-v2";
import {
  buildBBoxHistoryRiskContext,
  trackingKeyForDetection,
  type BBoxHistorySample,
  type RiskEvaluationContext
} from "../risk-evaluator";
import { depthContextFromDetection } from "../risk-depth";

const MAX_HISTORY_SAMPLES_PER_OBJECT = 12;
const historiesByHook = new Map<string, Map<string, BBoxHistorySample[]>>();

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

export function useTwoModelRiskHistory(detection: TwoModelDetection | null): RiskEvaluationContext {
  const hookId = useId();

  const context = useMemo(() => {
    if (!detection) {
      return {};
    }

    const key = historyKey(detection);
    const sample = { detection, observed_at_ms: observedAtMs(detection) };
    const histories = historiesByHook.get(hookId) ?? new Map<string, BBoxHistorySample[]>();
    const previous = histories.get(key) ?? [];

    return withExplicitDepthContext(detection, buildBBoxHistoryRiskContext(detection, [...previous, sample]));
  }, [detection, hookId]);

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
