import { createImageUploadAdmission, defaultImageUploadAdmission } from "./image-upload-admission.js";
import { createRefreshAdmission, defaultRefreshAdmission } from "./refresh-admission.js";
import { createSpeechAdmission, defaultSpeechAdmission } from "./speech-relay.js";
import { createRawWriteAdmission, defaultRawWriteAdmission } from "./raw-collection-relay.js";
import { createServerCapacityRuntime, defaultServerCapacityRuntime } from "./server-capacity.js";

/** One process-wide composition per server; feature handlers receive only their own owner. */
export function createGatewayRuntime(clock: () => number = () => Date.now()) {
  return {
    imageUpload: createImageUploadAdmission(clock),
    refresh: createRefreshAdmission(clock),
    speech: createSpeechAdmission(clock),
    rawWrite: createRawWriteAdmission(),
    capacity: createServerCapacityRuntime(clock)
  };
}

export type GatewayRuntime = ReturnType<typeof createGatewayRuntime>;

/** Compatibility for direct callers and the existing focused admission test hooks. */
export const defaultGatewayRuntime: GatewayRuntime = {
  imageUpload: defaultImageUploadAdmission,
  refresh: defaultRefreshAdmission,
  speech: defaultSpeechAdmission,
  rawWrite: defaultRawWriteAdmission,
  capacity: defaultServerCapacityRuntime
};
