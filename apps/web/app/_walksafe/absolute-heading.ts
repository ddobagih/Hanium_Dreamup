/**
 * Resolves only earth-referenced orientation readings into the compass heading of
 * the current screen top. Relative alpha values must never reach navigation or ROI policy.
 */

export type CompassDeviceOrientationEvent = DeviceOrientationEvent & {
  readonly webkitCompassHeading?: number | null;
  readonly webkitCompassAccuracy?: number | null;
};

export type AbsoluteHeadingSample = {
  eventType: string;
  alpha: number | null;
  beta: number | null;
  gamma: number | null;
  absolute: boolean;
  webkitCompassHeading?: number | null;
  webkitCompassAccuracy?: number | null;
  screenOrientationAngle: number | null;
};

export const MAX_WEBKIT_COMPASS_ACCURACY_DEG = 35;

type WindowWithLegacyOrientation = Window & { readonly orientation?: number };

function normalizedDegrees(value: number): number {
  return ((value % 360) + 360) % 360;
}

export function normalizeScreenOrientationAngle(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return null;
  }
  const normalized = normalizedDegrees(value);
  return normalized === 0 || normalized === 90 || normalized === 180 || normalized === 270
    ? normalized
    : null;
}

export function readScreenOrientationAngle(windowObject: Window): number | null {
  const modern = normalizeScreenOrientationAngle(windowObject.screen?.orientation?.angle);
  if (modern !== null) {
    return modern;
  }
  return normalizeScreenOrientationAngle((windowObject as WindowWithLegacyOrientation).orientation);
}

function validCompassAngle(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= 360;
}

function validBeta(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= -180 && value < 180;
}

function validGamma(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= -90 && value < 90;
}

/** W3C A.1: horizontal heading of the vector pointing out of the back of the screen. */
export function compassHeadingFromAbsoluteOrientation(alpha: number, beta: number, gamma: number): number | null {
  if (!validCompassAngle(alpha) || !validBeta(beta) || !validGamma(gamma)) return null;
  const degreesToRadians = Math.PI / 180;
  const x = beta * degreesToRadians;
  const y = gamma * degreesToRadians;
  const z = alpha * degreesToRadians;
  const vectorX = -Math.cos(z) * Math.sin(y) - Math.sin(z) * Math.sin(x) * Math.cos(y);
  const vectorY = -Math.sin(z) * Math.sin(y) + Math.cos(z) * Math.sin(x) * Math.cos(y);
  if (Math.hypot(vectorX, vectorY) < 1e-6) return null;
  return normalizedDegrees((Math.atan2(vectorX, vectorY) * 180) / Math.PI);
}

/**
 * `webkitCompassHeading` is already a magnetic-north screen-top heading and is
 * adjusted for UI rotation. Standard absolute readings use W3C's full
 * alpha/beta/gamma back-of-screen vector, which is independent of UI rotation.
 */
export function resolveAbsoluteHeadingDegrees(sample: AbsoluteHeadingSample): number | null {
  const screenAngle = normalizeScreenOrientationAngle(sample.screenOrientationAngle);

  const webkitHeading = sample.webkitCompassHeading;
  if (webkitHeading !== undefined && webkitHeading !== null) {
    const accuracy = sample.webkitCompassAccuracy;
    if (
      screenAngle === null ||
      !validCompassAngle(webkitHeading) ||
      (accuracy !== undefined &&
        accuracy !== null &&
        (!Number.isFinite(accuracy) || accuracy < 0 || accuracy > MAX_WEBKIT_COMPASS_ACCURACY_DEG))
    ) {
      return null;
    }
    return Math.round(normalizedDegrees(webkitHeading + screenAngle)) % 360;
  }

  const hasStandardAbsoluteReference = sample.absolute || sample.eventType === "deviceorientationabsolute";
  if (
    !hasStandardAbsoluteReference
    || !validCompassAngle(sample.alpha)
    || !validBeta(sample.beta)
    || !validGamma(sample.gamma)
  ) {
    return null;
  }
  const heading = compassHeadingFromAbsoluteOrientation(sample.alpha, sample.beta, sample.gamma);
  return heading === null ? null : Math.round(heading) % 360;
}

export function eventClaimsAbsoluteHeading(event: CompassDeviceOrientationEvent): boolean {
  return (
    event.type === "deviceorientationabsolute" ||
    event.absolute === true ||
    event.webkitCompassHeading !== undefined
  );
}
