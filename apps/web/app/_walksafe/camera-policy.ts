/** Pure fail-closed readiness policy for the pedestrian-facing camera stream. */
export class CameraFrameUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "CameraFrameUnavailableError";
  }
}

export function isLiveRearCameraTrack(
  readyState: MediaStreamTrackState,
  facingMode: string | undefined
): boolean {
  return readyState === "live" && facingMode === "environment";
}

export type CameraFrameObservation = {
  generation: number;
  mediaTime: number;
  presentedFrames: number | null;
};

export function isFreshCameraFrameObservation(
  previous: CameraFrameObservation | null,
  next: CameraFrameObservation
): boolean {
  if (
    !Number.isInteger(next.generation) ||
    next.generation < 0 ||
    !Number.isFinite(next.mediaTime) ||
    next.mediaTime < 0 ||
    (next.presentedFrames !== null &&
      (!Number.isInteger(next.presentedFrames) || next.presentedFrames < 0))
  ) {
    return false;
  }
  if (!previous || previous.generation !== next.generation) {
    return true;
  }
  if (next.mediaTime <= previous.mediaTime) {
    return false;
  }
  return (
    previous.presentedFrames === null ||
    next.presentedFrames === null ||
    next.presentedFrames > previous.presentedFrames
  );
}
