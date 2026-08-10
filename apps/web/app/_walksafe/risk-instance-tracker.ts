import type { TwoModelDetection } from "@/types/inference-v2";
import {
  trackingKeyForDetection,
  type BBoxHistorySample
} from "./risk-evaluator";

export const RISK_TRACK_MAX_AGE_MS = 2_500;
export const RISK_TRACK_MAX_SAMPLES = 12;
export const RISK_TRACK_MIN_IOU = 0.1;

export type RiskInstanceTrack = {
  id: string;
  baseKey: string;
  lastSeenAtMs: number;
  samples: BBoxHistorySample[];
};

export type RiskInstanceTrackerState = {
  nextId: number;
  tracks: RiskInstanceTrack[];
};

export type RiskInstanceAssignment = {
  detection: TwoModelDetection;
  trackId: string;
  samples: BBoxHistorySample[];
};

export type RiskInstanceTrackerUpdate = {
  state: RiskInstanceTrackerState;
  assignments: RiskInstanceAssignment[];
};

export const EMPTY_RISK_INSTANCE_TRACKER_STATE: RiskInstanceTrackerState = {
  nextId: 1,
  tracks: []
};

function observedAtMs(detection: TwoModelDetection, fallbackNowMs: number): number {
  const parsed = Date.parse(detection.captured_at);
  return Number.isFinite(parsed) ? parsed : fallbackNowMs;
}

function bboxArea(detection: TwoModelDetection): number {
  return Math.max(0, detection.bbox.width) * Math.max(0, detection.bbox.height);
}

export function detectionBBoxIou(first: TwoModelDetection, second: TwoModelDetection): number {
  const firstRight = first.bbox.x + first.bbox.width;
  const firstBottom = first.bbox.y + first.bbox.height;
  const secondRight = second.bbox.x + second.bbox.width;
  const secondBottom = second.bbox.y + second.bbox.height;
  const intersectionWidth = Math.max(0, Math.min(firstRight, secondRight) - Math.max(first.bbox.x, second.bbox.x));
  const intersectionHeight = Math.max(0, Math.min(firstBottom, secondBottom) - Math.max(first.bbox.y, second.bbox.y));
  const intersectionArea = intersectionWidth * intersectionHeight;
  const unionArea = bboxArea(first) + bboxArea(second) - intersectionArea;
  return unionArea > 0 ? intersectionArea / unionArea : 0;
}

function isDuplicateSample(previous: BBoxHistorySample | undefined, detection: TwoModelDetection): boolean {
  if (!previous) {
    return false;
  }

  return (
    previous.detection.captured_at === detection.captured_at &&
    previous.detection.bbox.x === detection.bbox.x &&
    previous.detection.bbox.y === detection.bbox.y &&
    previous.detection.bbox.width === detection.bbox.width &&
    previous.detection.bbox.height === detection.bbox.height
  );
}

/**
 * Greedily associates same-class detections with the highest-IoU unmatched track.
 * This deliberately stays small: it prevents histories for simultaneous instances from
 * being merged, without pretending to be a re-identification model across occlusion.
 */
export function advanceRiskInstanceTracks(
  previous: RiskInstanceTrackerState,
  detections: readonly TwoModelDetection[],
  fallbackNowMs = Date.now()
): RiskInstanceTrackerUpdate {
  const observedTimes = detections.map((detection) => observedAtMs(detection, fallbackNowMs));
  const frameTime = observedTimes.length > 0 ? Math.max(...observedTimes) : fallbackNowMs;
  const previousTracks = previous.tracks.filter(
    (track) => Math.abs(frameTime - track.lastSeenAtMs) <= RISK_TRACK_MAX_AGE_MS
  );
  const pairs: Array<{ detectionIndex: number; trackIndex: number; iou: number }> = [];

  detections.forEach((detection, detectionIndex) => {
    const baseKey = trackingKeyForDetection(detection);
    previousTracks.forEach((track, trackIndex) => {
      const lastDetection = track.samples.at(-1)?.detection;
      if (!lastDetection || track.baseKey !== baseKey) {
        return;
      }
      const iou = detectionBBoxIou(lastDetection, detection);
      if (iou >= RISK_TRACK_MIN_IOU) {
        pairs.push({ detectionIndex, trackIndex, iou });
      }
    });
  });

  pairs.sort((first, second) => second.iou - first.iou);
  const matchedDetectionIndexes = new Set<number>();
  const matchedTrackIndexes = new Set<number>();
  const trackIndexForDetection = new Map<number, number>();
  for (const pair of pairs) {
    if (matchedDetectionIndexes.has(pair.detectionIndex) || matchedTrackIndexes.has(pair.trackIndex)) {
      continue;
    }
    matchedDetectionIndexes.add(pair.detectionIndex);
    matchedTrackIndexes.add(pair.trackIndex);
    trackIndexForDetection.set(pair.detectionIndex, pair.trackIndex);
  }

  let nextId = previous.nextId;
  const nextTracks = previousTracks.map((track) => ({ ...track, samples: [...track.samples] }));
  const assignments: RiskInstanceAssignment[] = [];

  detections.forEach((detection, detectionIndex) => {
    const observedAt = observedTimes[detectionIndex];
    const existingTrackIndex = trackIndexForDetection.get(detectionIndex);
    let track: RiskInstanceTrack;
    if (existingTrackIndex === undefined) {
      const baseKey = trackingKeyForDetection(detection);
      track = {
        id: `${baseKey}:instance-${nextId}`,
        baseKey,
        lastSeenAtMs: observedAt,
        samples: []
      };
      nextId += 1;
      nextTracks.push(track);
    } else {
      track = nextTracks[existingTrackIndex];
    }

    const lastSample = track.samples.at(-1);
    if (!isDuplicateSample(lastSample, detection)) {
      track.samples = [...track.samples, { detection, observed_at_ms: observedAt }].slice(-RISK_TRACK_MAX_SAMPLES);
    }
    track.lastSeenAtMs = observedAt;
    assignments.push({ detection, trackId: track.id, samples: track.samples });
  });

  return {
    state: { nextId, tracks: nextTracks },
    assignments
  };
}
