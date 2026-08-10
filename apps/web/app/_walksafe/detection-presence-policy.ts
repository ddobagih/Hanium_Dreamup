export const REQUIRED_CONSECUTIVE_EMPTY_DETECTION_FRAMES = 2;

export type DetectionPresenceState<T> = {
  detections: T[];
  consecutiveEmptyFrames: number;
};

export function advanceDetectionPresence<T>(
  previous: DetectionPresenceState<T>,
  nextDetections: T[],
  requiredEmptyFrames = REQUIRED_CONSECUTIVE_EMPTY_DETECTION_FRAMES
): DetectionPresenceState<T> {
  if (nextDetections.length > 0) {
    return { detections: nextDetections, consecutiveEmptyFrames: 0 };
  }

  const emptyFrames = previous.consecutiveEmptyFrames + 1;
  if (previous.detections.length > 0 && emptyFrames < Math.max(1, Math.floor(requiredEmptyFrames))) {
    return { detections: previous.detections, consecutiveEmptyFrames: emptyFrames };
  }
  return { detections: [], consecutiveEmptyFrames: emptyFrames };
}
