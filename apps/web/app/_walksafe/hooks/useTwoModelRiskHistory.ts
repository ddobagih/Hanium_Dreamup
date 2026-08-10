"use client";

/**
 * Builds instance-isolated temporal contexts for every detection in a frame.
 * IoU association keeps simultaneous same-class objects from sharing bbox history.
 */
import { useEffect, useId, useMemo } from "react";
import type { TwoModelDetection } from "@/types/inference-v2";
import type { FutureMotionProjection } from "../motion-projection";
import {
  advanceRiskInstanceTracks,
  EMPTY_RISK_INSTANCE_TRACKER_STATE,
  type RiskInstanceTrackerState
} from "../risk-instance-tracker";
import { buildRoiRiskContext } from "../risk-roi";
import {
  buildBBoxHistoryRiskContext,
  type RiskObjectTracking,
  type RiskEvaluationContext
} from "../risk-evaluator";
import { depthContextFromDetection } from "../risk-depth";

type UseTwoModelRiskHistoryOptions = {
  heading?: number | null;
  walkingSpeedMps?: number | null;
  motionStability?: number | null;
  futureMotion?: FutureMotionProjection | null;
};

export type TrackedRiskContext = {
  detection: TwoModelDetection;
  objectId: string;
  context: RiskEvaluationContext;
};

const trackerStatesByHook = new Map<string, RiskInstanceTrackerState>();

function withExplicitDepthContext(
  detection: TwoModelDetection,
  context: RiskEvaluationContext
): RiskEvaluationContext {
  const depthContext = depthContextFromDetection(detection);
  return depthContext.depth ? { ...context, depth: depthContext.depth } : context;
}

function mergeTracking(
  historyTracking: RiskObjectTracking | undefined,
  roiTracking: RiskObjectTracking | undefined,
  objectId: string
): RiskObjectTracking {
  return {
    ...roiTracking,
    ...historyTracking,
    object_id: objectId,
    stable_frames: Math.max(historyTracking?.stable_frames ?? 0, roiTracking?.stable_frames ?? 0),
    stable_ms: Math.max(historyTracking?.stable_ms ?? 0, roiTracking?.stable_ms ?? 0),
    blocking_path: historyTracking?.blocking_path ?? roiTracking?.blocking_path,
    projected_path_intersection: historyTracking?.projected_path_intersection ?? roiTracking?.projected_path_intersection
  };
}

function mergeRiskContexts(
  historyContext: RiskEvaluationContext,
  roiContext: RiskEvaluationContext,
  objectId: string
): RiskEvaluationContext {
  return {
    ...roiContext,
    ...historyContext,
    imu: { ...roiContext.imu, ...historyContext.imu },
    segmentation: { ...roiContext.segmentation, ...historyContext.segmentation },
    tracking: mergeTracking(historyContext.tracking, roiContext.tracking, objectId)
  };
}

export function useTwoModelRiskHistories(
  detections: readonly TwoModelDetection[],
  options: UseTwoModelRiskHistoryOptions = {}
): TrackedRiskContext[] {
  const hookId = useId();
  const trackerUpdate = useMemo(
    () => advanceRiskInstanceTracks(trackerStatesByHook.get(hookId) ?? EMPTY_RISK_INSTANCE_TRACKER_STATE, detections),
    [detections, hookId]
  );

  const contexts = useMemo(
    () =>
      trackerUpdate.assignments.map((assignment) => {
        const historyContext = buildBBoxHistoryRiskContext(assignment.detection, assignment.samples, {
          motion_stability: options.motionStability
        });
        const roiContext = buildRoiRiskContext(assignment.detection, {
          motion: {
            heading_deg: options.futureMotion?.headingDeg ?? options.heading,
            route_bearing_deg: options.futureMotion?.routeBearingDeg,
            walking_speed_mps: options.futureMotion?.speedMps ?? options.walkingSpeedMps,
            future_shift_x: options.futureMotion?.screenShiftX,
            prediction_horizon_s: options.futureMotion?.horizonS,
            projected_distance_m: options.futureMotion?.projectedDistanceM,
            confidence: options.futureMotion?.confidence
          }
        });
        const context = withExplicitDepthContext(
          assignment.detection,
          mergeRiskContexts(historyContext, roiContext, assignment.trackId)
        );
        return { detection: assignment.detection, objectId: assignment.trackId, context };
      }),
    [
      options.futureMotion,
      options.heading,
      options.motionStability,
      options.walkingSpeedMps,
      trackerUpdate.assignments
    ]
  );

  useEffect(() => {
    trackerStatesByHook.set(hookId, trackerUpdate.state);
  }, [hookId, trackerUpdate.state]);

  useEffect(() => () => {
    trackerStatesByHook.delete(hookId);
  }, [hookId]);

  return contexts;
}
