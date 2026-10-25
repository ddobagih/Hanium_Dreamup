import { gatewayTrustedClientIp } from "./auth.js";
import { ACTOR_ID_HEADER } from "./backend-contract.js";

const IMAGE_UPLOAD_RATE_WINDOW_MS = 60_000;

const IMAGE_UPLOAD_GLOBAL_RATE_LIMIT = 120;

const IMAGE_UPLOAD_ACTOR_RATE_LIMIT = 12;

const IMAGE_UPLOAD_IP_RATE_LIMIT = 30;

/**
 * S19-49: conservative process-wide default. Raising it needs a deployment memory budget
 * (measured peak per maximum upload x N); it is not raised here.
 */
const IMAGE_UPLOAD_MAX_CONCURRENCY = 1;

/** S19-49: one upload per actor, so one account can never hold several global slots. */
const IMAGE_UPLOAD_MAX_CONCURRENCY_PER_ACTOR = 1;

export type ImageUploadAdmission =
  | { release: () => void; error?: never }
  | { release?: never; error: Response };

function imageUploadAdmissionError(
  status: number,
  code: string,
  message: string,
  retryAfter: number
): Response {
  return Response.json(
    { detail: { code, message } },
    {
      status,
      headers: {
        "cache-control": "no-store",
        "retry-after": String(Math.max(1, retryAfter))
      }
    }
  );
}

function imageUploadClientIp(request: Request): string {
  return gatewayTrustedClientIp(request) ?? "unbound";
}

/** Owns one server's admission/cache lifetime; never constructed per request. */
export function createImageUploadAdmission(clock: () => number = () => Date.now()) {


  const imageUploadRateEvents = new Map<string, number[]>();


  let imageUploadsInFlight = 0;


  const imageUploadsInFlightByActor = new Map<string, number>();


  let imageUploadMaxConcurrencyForTests: number | null = null;


  function imageUploadMaxConcurrency(): number {
    return imageUploadMaxConcurrencyForTests ?? IMAGE_UPLOAD_MAX_CONCURRENCY;
  }


  /** Test-only: exercises per-actor fairness when the global limit is above one. */
  function setImageUploadMaxConcurrencyForTests(value: number | null): void {
    if (process.env.NODE_ENV === "production") {
      throw new Error("image upload concurrency override is unavailable in production");
    }
    if (value !== null && (!Number.isSafeInteger(value) || value < 1)) {
      throw new Error("invalid image upload concurrency override");
    }
    imageUploadMaxConcurrencyForTests = value;
  }


  function pruneImageUploadRateEvents(nowMs: number): void {
    const cutoff = nowMs - IMAGE_UPLOAD_RATE_WINDOW_MS;
    for (const [key, events] of imageUploadRateEvents) {
      const current = events.filter((eventAt) => eventAt > cutoff && eventAt <= nowMs);
      if (current.length === 0) imageUploadRateEvents.delete(key);
      else imageUploadRateEvents.set(key, current);
    }
  }


  /**
   * S19-32: the rate part of the report admission, checked before the consent lookup without
   * taking a slot or recording an event. The global/actor slot is taken by
   * acquireImageUploadAdmission only after the first consent check (still before the body is
   * opened), so a report waiting on a slow consent lock never holds the one global slot.
   */
  function imageUploadRateLimitedResponse(
    request: Request,
    fieldHeaders: Headers,
    nowMs = clock()
  ): Response | null {
    if (!Number.isFinite(nowMs) || nowMs < 0) return null;
    pruneImageUploadRateEvents(nowMs);
    const actorId = fieldHeaders.get(ACTOR_ID_HEADER)?.trim() || "unbound";
    const clientIp = imageUploadClientIp(request);
    for (const [key, limit] of [
      ["image:global", IMAGE_UPLOAD_GLOBAL_RATE_LIMIT],
      [`image:actor:${actorId}`, IMAGE_UPLOAD_ACTOR_RATE_LIMIT],
      [`image:ip:${clientIp}`, IMAGE_UPLOAD_IP_RATE_LIMIT]
    ] as Array<[string, number]>) {
      const events = imageUploadRateEvents.get(key) ?? [];
      if (events.length >= limit) {
        const retryAfter = Math.ceil((events[0]! + IMAGE_UPLOAD_RATE_WINDOW_MS - nowMs) / 1000);
        return imageUploadAdmissionError(
          429,
          "image_upload_rate_limited",
          "Image upload rate limit exceeded.",
          retryAfter
        );
      }
    }
    return null;
  }


  function acquireImageUploadAdmission(
    request: Request,
    fieldHeaders: Headers,
    nowMs = clock()
  ): ImageUploadAdmission {
    if (!Number.isFinite(nowMs) || nowMs < 0) {
      return {
        error: imageUploadAdmissionError(
          503,
          "image_admission_unavailable",
          "Image upload admission clock is invalid.",
          1
        )
      };
    }
    pruneImageUploadRateEvents(nowMs);
    const actorId = fieldHeaders.get(ACTOR_ID_HEADER)?.trim() || "unbound";
    const clientIp = imageUploadClientIp(request);
    const limits: Array<[string, number]> = [
      ["image:global", IMAGE_UPLOAD_GLOBAL_RATE_LIMIT],
      [`image:actor:${actorId}`, IMAGE_UPLOAD_ACTOR_RATE_LIMIT],
      [`image:ip:${clientIp}`, IMAGE_UPLOAD_IP_RATE_LIMIT]
    ];
    for (const [key, limit] of limits) {
      const events = imageUploadRateEvents.get(key) ?? [];
      if (events.length >= limit) {
        const retryAfter = Math.ceil((events[0]! + IMAGE_UPLOAD_RATE_WINDOW_MS - nowMs) / 1000);
        return {
          error: imageUploadAdmissionError(
            429,
            "image_upload_rate_limited",
            "Image upload rate limit exceeded.",
            retryAfter
          )
        };
      }
    }
    if (
      (imageUploadsInFlightByActor.get(actorId) ?? 0) >= IMAGE_UPLOAD_MAX_CONCURRENCY_PER_ACTOR ||
      imageUploadsInFlight >= imageUploadMaxConcurrency()
    ) {
      return {
        error: imageUploadAdmissionError(
          503,
          "image_upload_busy",
          "Image upload capacity is busy. Retry shortly.",
          1
        )
      };
    }
    for (const [key] of limits) {
      const events = imageUploadRateEvents.get(key) ?? [];
      events.push(nowMs);
      imageUploadRateEvents.set(key, events);
    }
    imageUploadsInFlight += 1;
    imageUploadsInFlightByActor.set(actorId, (imageUploadsInFlightByActor.get(actorId) ?? 0) + 1);
    let released = false;
    return {
      release: () => {
        if (released) return;
        released = true;
        imageUploadsInFlight = Math.max(0, imageUploadsInFlight - 1);
        const remaining = (imageUploadsInFlightByActor.get(actorId) ?? 1) - 1;
        if (remaining > 0) imageUploadsInFlightByActor.set(actorId, remaining);
        else imageUploadsInFlightByActor.delete(actorId);
      }
    };
  }


  function resetImageUploadAdmissionForTests(): void {
    imageUploadRateEvents.clear();
    imageUploadsInFlight = 0;
    imageUploadsInFlightByActor.clear();
  }
  return { setImageUploadMaxConcurrencyForTests, imageUploadRateLimitedResponse, acquireImageUploadAdmission, resetImageUploadAdmissionForTests };
}

export type ImageUploadAdmissionController = ReturnType<typeof createImageUploadAdmission>;
export const defaultImageUploadAdmission = createImageUploadAdmission();
export const setImageUploadMaxConcurrencyForTests: ImageUploadAdmissionController["setImageUploadMaxConcurrencyForTests"] = (...args) => defaultImageUploadAdmission.setImageUploadMaxConcurrencyForTests(...args);
export const imageUploadRateLimitedResponse: ImageUploadAdmissionController["imageUploadRateLimitedResponse"] = (...args) => defaultImageUploadAdmission.imageUploadRateLimitedResponse(...args);
export const acquireImageUploadAdmission: ImageUploadAdmissionController["acquireImageUploadAdmission"] = (...args) => defaultImageUploadAdmission.acquireImageUploadAdmission(...args);
export const resetImageUploadAdmissionForTests: ImageUploadAdmissionController["resetImageUploadAdmissionForTests"] = (...args) => defaultImageUploadAdmission.resetImageUploadAdmissionForTests(...args);
