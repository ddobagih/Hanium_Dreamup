"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { MutableRefObject } from "react";
import type { TwoModelDetection } from "@/types/inference-v2";
import { estimateSensorDepthForBBox, type DepthEstimateResult, type DepthFrameSampler } from "../depth-estimator";

export type XrDepthUsage = "cpu-optimized" | "gpu-optimized";
export type XrDepthDataFormat = "luminance-alpha" | "float32" | "unsigned-short";

export type DepthSensorStatus = "idle" | "checking" | "unsupported" | "requesting" | "running" | "stopping" | "stopped" | "error";

export type DepthSensorSamplePoint = {
  x: number;
  y: number;
};

export type DepthSensorSample = {
  timestampMs: number;
  observedAtMs: number;
  viewIndex: number;
  viewCount: number;
  width: number | null;
  height: number | null;
  distanceM: number | null;
  samplePoint: DepthSensorSamplePoint;
  rawValueToMeters: number | null;
  depthUsage: XrDepthUsage | null;
  depthDataFormat: XrDepthDataFormat | null;
  depthActive: boolean | null;
};

export type DepthSensorState = {
  status: DepthSensorStatus;
  message: string;
  supported: boolean | null;
  frameCount: number;
  lastFrameAtMs: number | null;
  latestSample: DepthSensorSample | null;
  error: string | null;
};

export type UseDepthSensorOptions = {
  motionStability?: number | null;
  samplePoint?: Partial<DepthSensorSamplePoint>;
};

export type UseDepthSensorResult = DepthSensorState & {
  state: DepthSensorState;
  latestSampleRef: MutableRefObject<DepthSensorSample | null>;
  getLatestSample: () => DepthSensorSample | null;
  startDepthSensor: () => Promise<boolean>;
  stopDepthSensor: () => Promise<void>;
  checkDepthSupport: () => Promise<boolean>;
  estimateDepthForDetection: (detection: TwoModelDetection) => DepthEstimateResult | null;
  canStart: boolean;
  isRunning: boolean;
};

type XrSessionMode = "immersive-ar";
type XrReferenceSpaceType = "local" | "viewer";

type XrDepthSessionInit = {
  requiredFeatures: readonly ["depth-sensing"];
  depthSensing: {
    usagePreference: readonly ["cpu-optimized"];
    dataFormatPreference: readonly XrDepthDataFormat[];
    matchDepthView: boolean;
  };
};

type NavigatorWithXr = Navigator & {
  xr?: {
    isSessionSupported?: (mode: XrSessionMode) => Promise<boolean>;
    requestSession?: (mode: XrSessionMode, options: XrDepthSessionInit) => Promise<XrSessionLike>;
  };
};

type XrSessionLike = {
  depthUsage?: XrDepthUsage;
  depthDataFormat?: XrDepthDataFormat;
  depthActive?: boolean | null;
  requestAnimationFrame: (callback: (time: number, frame: XrFrameLike) => void) => number;
  cancelAnimationFrame?: (handle: number) => void;
  requestReferenceSpace: (type: XrReferenceSpaceType) => Promise<unknown>;
  updateRenderState?: (state: unknown) => void;
  end: () => Promise<void>;
  addEventListener?: (type: "end", listener: EventListener, options?: AddEventListenerOptions) => void;
  removeEventListener?: (type: "end", listener: EventListener) => void;
};

type XrFrameLike = {
  getViewerPose?: (referenceSpace: unknown) => { views: unknown[] } | null;
  getDepthInformation?: (view: unknown) => XrCpuDepthInformationLike | null;
};

type XrCpuDepthInformationLike = {
  width?: number;
  height?: number;
  rawValueToMeters?: number;
  getDepthInMeters?: (x: number, y: number) => number;
};

const DEFAULT_SAMPLE_POINT: DepthSensorSamplePoint = { x: 0.5, y: 0.5 };
const DEPTH_STATE_UPDATE_INTERVAL_MS = 250;
const DEPTH_SNAPSHOT_GRID_SIZE = 7;
const DEPTH_SESSION_INIT = {
  requiredFeatures: ["depth-sensing"],
  depthSensing: {
    usagePreference: ["cpu-optimized"],
    dataFormatPreference: ["luminance-alpha", "float32"],
    matchDepthView: true
  }
} as const satisfies XrDepthSessionInit;

const INITIAL_STATE: DepthSensorState = {
  status: "idle",
  message: "WebXR depth 대기 · 버튼 입력 후 시작",
  supported: null,
  frameCount: 0,
  lastFrameAtMs: null,
  latestSample: null,
  error: null
};

function navigatorWithXr(): NavigatorWithXr | null {
  return typeof navigator === "undefined" ? null : (navigator as NavigatorWithXr);
}

function clamp01(value: number | undefined, fallback: number): number {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return fallback;
  }
  return Math.min(1, Math.max(0, value));
}

function normalizeSamplePoint(samplePoint: Partial<DepthSensorSamplePoint> | undefined): DepthSensorSamplePoint {
  return {
    x: clamp01(samplePoint?.x, DEFAULT_SAMPLE_POINT.x),
    y: clamp01(samplePoint?.y, DEFAULT_SAMPLE_POINT.y)
  };
}

function depthSensorErrorMessage(error: unknown): string {
  if (error instanceof DOMException) {
    if (error.name === "NotSupportedError") {
      return "이 브라우저/기기는 WebXR immersive-ar depth-sensing을 지원하지 않습니다.";
    }
    if (error.name === "NotAllowedError" || error.name === "SecurityError") {
      return "WebXR/공간 추적 권한이 거부됐거나 HTTPS 보안 컨텍스트가 아닙니다.";
    }
    if (error.name === "InvalidStateError") {
      return "WebXR depth 세션 상태가 유효하지 않습니다. 세션을 중지한 뒤 다시 시작해 주세요.";
    }
  }

  return error instanceof Error && error.message ? error.message : "WebXR depth 세션 처리에 실패했습니다.";
}

function isUnsupportedDepthError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "NotSupportedError";
}

function safeReadSessionValue<T>(readValue: () => T): T | null {
  try {
    return readValue();
  } catch {
    return null;
  }
}

function readDepthInMeters(depthInfo: XrCpuDepthInformationLike, x: number, y: number): number | null {
  try {
    const distanceM = depthInfo.getDepthInMeters?.(x, y) ?? null;
    return typeof distanceM === "number" && Number.isFinite(distanceM) && distanceM >= 0 ? distanceM : null;
  } catch {
    return null;
  }
}

async function requestReferenceSpace(session: XrSessionLike): Promise<unknown> {
  try {
    return await session.requestReferenceSpace("local");
  } catch {
    return session.requestReferenceSpace("viewer");
  }
}

function createWebGlLayer(session: XrSessionLike): unknown {
  if (typeof document === "undefined" || typeof window === "undefined") {
    return null;
  }

  const canvas = document.createElement("canvas");
  const gl = canvas.getContext("webgl", { xrCompatible: true } as WebGLContextAttributes) as WebGLRenderingContext | null;
  const layerConstructor = (window as typeof window & { XRWebGLLayer?: new (session: XrSessionLike, gl: WebGLRenderingContext) => unknown }).XRWebGLLayer;
  if (!gl || !layerConstructor) {
    return null;
  }

  return new layerConstructor(session, gl);
}

function createDepthFrameSampler(depthInfo: XrCpuDepthInformationLike, observedAtMs: number): DepthFrameSampler | null {
  if (typeof depthInfo.getDepthInMeters !== "function") {
    return null;
  }

  const samples: Array<{ x: number; y: number; distanceM: number }> = [];
  for (let row = 0; row < DEPTH_SNAPSHOT_GRID_SIZE; row += 1) {
    const y = row / (DEPTH_SNAPSHOT_GRID_SIZE - 1);
    for (let column = 0; column < DEPTH_SNAPSHOT_GRID_SIZE; column += 1) {
      const x = column / (DEPTH_SNAPSHOT_GRID_SIZE - 1);
      const distanceM = readDepthInMeters(depthInfo, x, y);
      if (distanceM !== null) {
        samples.push({ x, y, distanceM });
      }
    }
  }

  if (samples.length === 0) {
    return null;
  }

  return {
    observedAtMs,
    getDepthInMeters: (x, y) => {
      const normalizedX = clamp01(x, DEFAULT_SAMPLE_POINT.x);
      const normalizedY = clamp01(y, DEFAULT_SAMPLE_POINT.y);
      let nearest = samples[0];
      let nearestDistance = Number.POSITIVE_INFINITY;

      for (const sample of samples) {
        const distance = (sample.x - normalizedX) ** 2 + (sample.y - normalizedY) ** 2;
        if (distance < nearestDistance) {
          nearest = sample;
          nearestDistance = distance;
        }
      }

      return nearest?.distanceM ?? null;
    }
  };
}

function createDepthSensorSample({
  depthInfo,
  observedAtMs,
  samplePoint,
  session,
  timestampMs,
  viewCount,
  viewIndex
}: {
  depthInfo: XrCpuDepthInformationLike;
  observedAtMs: number;
  samplePoint: DepthSensorSamplePoint;
  session: XrSessionLike;
  timestampMs: number;
  viewCount: number;
  viewIndex: number;
}): DepthSensorSample {
  return {
    timestampMs,
    observedAtMs,
    viewIndex,
    viewCount,
    width: typeof depthInfo.width === "number" ? depthInfo.width : null,
    height: typeof depthInfo.height === "number" ? depthInfo.height : null,
    distanceM: readDepthInMeters(depthInfo, samplePoint.x, samplePoint.y),
    samplePoint,
    rawValueToMeters: typeof depthInfo.rawValueToMeters === "number" ? depthInfo.rawValueToMeters : null,
    depthUsage: safeReadSessionValue(() => session.depthUsage ?? null),
    depthDataFormat: safeReadSessionValue(() => session.depthDataFormat ?? null),
    depthActive: safeReadSessionValue(() => session.depthActive ?? null)
  };
}

export function useDepthSensor({ motionStability = 1, samplePoint }: UseDepthSensorOptions = {}): UseDepthSensorResult {
  const samplePointX = samplePoint?.x;
  const samplePointY = samplePoint?.y;
  const sessionRef = useRef<XrSessionLike | null>(null);
  const referenceSpaceRef = useRef<unknown>(null);
  const animationFrameRef = useRef<number | null>(null);
  const endListenerRef = useRef<{ session: XrSessionLike; listener: EventListener } | null>(null);
  const latestSamplerRef = useRef<DepthFrameSampler | null>(null);
  const latestSampleRef = useRef<DepthSensorSample | null>(null);
  const motionStabilityRef = useRef(motionStability);
  const samplePointRef = useRef(normalizeSamplePoint({ x: samplePointX, y: samplePointY }));
  const frameCountRef = useRef(0);
  const lastStateUpdateAtRef = useRef(0);
  const [state, setState] = useState<DepthSensorState>(INITIAL_STATE);

  useEffect(() => {
    motionStabilityRef.current = motionStability;
  }, [motionStability]);

  useEffect(() => {
    samplePointRef.current = normalizeSamplePoint({ x: samplePointX, y: samplePointY });
  }, [samplePointX, samplePointY]);

  const clearActiveSession = useCallback(() => {
    const session = sessionRef.current;
    if (session && animationFrameRef.current !== null && typeof session.cancelAnimationFrame === "function") {
      session.cancelAnimationFrame(animationFrameRef.current);
    }

    if (endListenerRef.current) {
      endListenerRef.current.session.removeEventListener?.("end", endListenerRef.current.listener);
      endListenerRef.current = null;
    }

    animationFrameRef.current = null;
    sessionRef.current = null;
    referenceSpaceRef.current = null;
    latestSamplerRef.current = null;
  }, []);

  const stopDepthSensor = useCallback(async () => {
    const session = sessionRef.current;
    if (!session) {
      clearActiveSession();
      setState((current) => ({
        ...current,
        status: current.status === "idle" ? "idle" : "stopped",
        message: current.status === "idle" ? current.message : "WebXR depth 세션이 중지됐습니다."
      }));
      return;
    }

    setState((current) => ({ ...current, status: "stopping", message: "WebXR depth 세션 중지 중입니다." }));
    clearActiveSession();

    try {
      await session.end();
      setState((current) => ({ ...current, status: "stopped", message: "WebXR depth 세션이 중지됐습니다.", error: null }));
    } catch (error) {
      setState((current) => ({
        ...current,
        status: "error",
        message: "WebXR depth 세션 중지 중 오류가 발생했습니다.",
        error: depthSensorErrorMessage(error)
      }));
    }
  }, [clearActiveSession]);

  const checkDepthSupport = useCallback(async (): Promise<boolean> => {
    const xr = navigatorWithXr()?.xr;
    if (typeof window !== "undefined" && !window.isSecureContext) {
      setState((current) => ({
        ...current,
        status: "unsupported",
        message: "WebXR depth는 HTTPS 또는 localhost 보안 컨텍스트에서만 사용할 수 있습니다.",
        supported: false,
        error: null
      }));
      return false;
    }
    if (typeof xr?.isSessionSupported !== "function" || typeof xr.requestSession !== "function") {
      setState((current) => ({
        ...current,
        status: "unsupported",
        message: "이 브라우저는 navigator.xr depth 세션 요청을 지원하지 않습니다.",
        supported: false,
        error: null
      }));
      return false;
    }

    setState((current) => ({ ...current, status: "checking", message: "WebXR immersive-ar 지원 확인 중", error: null }));
    try {
      const supported = await xr.isSessionSupported("immersive-ar");
      setState((current) => ({
        ...current,
        status: supported ? "idle" : "unsupported",
        message: supported
          ? "immersive-ar probe 통과 · 실제 depth-sensing은 시작 버튼에서 확인됩니다."
          : "이 기기는 WebXR immersive-ar 세션을 지원하지 않습니다.",
        supported,
        error: null
      }));
      return supported;
    } catch (error) {
      setState((current) => ({
        ...current,
        status: "unsupported",
        message: "WebXR depth 지원 확인 실패",
        supported: false,
        error: depthSensorErrorMessage(error)
      }));
      return false;
    }
  }, []);

  const startDepthSensor = useCallback(async (): Promise<boolean> => {
    if (sessionRef.current) {
      setState((current) => ({ ...current, status: "running", message: "WebXR depth 수신 중", error: null }));
      return true;
    }

    if (typeof window === "undefined" || !window.isSecureContext) {
      setState((current) => ({
        ...current,
        status: "unsupported",
        message: "WebXR depth는 HTTPS 또는 localhost 보안 컨텍스트에서만 사용할 수 있습니다.",
        supported: false,
        error: null
      }));
      return false;
    }

    const xr = navigatorWithXr()?.xr;
    if (typeof xr?.requestSession !== "function") {
      setState((current) => ({
        ...current,
        status: "unsupported",
        message: "이 브라우저는 navigator.xr.requestSession을 제공하지 않습니다.",
        supported: false,
        error: null
      }));
      return false;
    }

    setState((current) => ({ ...current, status: "requesting", message: "WebXR depth-sensing 권한 요청 중", error: null }));
    let requestedSession: XrSessionLike | null = null;

    try {
      requestedSession = await xr.requestSession("immersive-ar", DEPTH_SESSION_INIT);
      const referenceSpace = await requestReferenceSpace(requestedSession);
      sessionRef.current = requestedSession;
      referenceSpaceRef.current = referenceSpace;
      frameCountRef.current = 0;
      lastStateUpdateAtRef.current = 0;

      try {
        const layer = createWebGlLayer(requestedSession);
        if (layer && typeof requestedSession.updateRenderState === "function") {
          requestedSession.updateRenderState({ baseLayer: layer });
        }
      } catch {
        // 일부 브라우저는 depth 정보는 제공해도 XRWebGLLayer 구성이 실패할 수 있다.
        // CPU depth sampling 자체와 직접 관련 없는 렌더 레이어 실패는 세션 시작을 막지 않는다.
      }

      const handleEnded: EventListener = () => {
        if (sessionRef.current !== requestedSession) {
          return;
        }
        clearActiveSession();
        setState((current) => ({ ...current, status: "stopped", message: "WebXR depth 세션이 종료됐습니다." }));
      };
      requestedSession.addEventListener?.("end", handleEnded, { once: true });
      endListenerRef.current = { session: requestedSession, listener: handleEnded };

      const onFrame = (time: number, frame: XrFrameLike) => {
        const activeSession = sessionRef.current;
        const activeReferenceSpace = referenceSpaceRef.current;
        if (!activeSession || !activeReferenceSpace) {
          return;
        }

        animationFrameRef.current = activeSession.requestAnimationFrame(onFrame);
        const pose = frame.getViewerPose?.(activeReferenceSpace);
        const views = pose?.views ?? [];
        for (let viewIndex = 0; viewIndex < views.length; viewIndex += 1) {
          const depthInfo = frame.getDepthInformation?.(views[viewIndex]);
          if (typeof depthInfo?.getDepthInMeters !== "function") {
            continue;
          }

          const observedAtMs = Date.now();
          const sample = createDepthSensorSample({
            depthInfo,
            observedAtMs,
            samplePoint: samplePointRef.current,
            session: activeSession,
            timestampMs: time,
            viewCount: views.length,
            viewIndex
          });
          const sampler = createDepthFrameSampler(depthInfo, observedAtMs);
          frameCountRef.current += 1;
          latestSampleRef.current = sample;
          latestSamplerRef.current = sampler;

          if (lastStateUpdateAtRef.current === 0 || observedAtMs - lastStateUpdateAtRef.current >= DEPTH_STATE_UPDATE_INTERVAL_MS) {
            lastStateUpdateAtRef.current = observedAtMs;
            setState((current) => ({
              ...current,
              status: "running",
              message:
                sample.distanceM === null
                  ? "WebXR depth 프레임 수신 중 · 샘플 거리 없음"
                  : `WebXR depth 프레임 수신 중 · 중앙 ${sample.distanceM.toFixed(2)}m`,
              supported: true,
              frameCount: frameCountRef.current,
              lastFrameAtMs: observedAtMs,
              latestSample: sample,
              error: null
            }));
          }
          break;
        }
      };

      animationFrameRef.current = requestedSession.requestAnimationFrame(onFrame);
      setState((current) => ({
        ...current,
        status: "running",
        message: "WebXR depth 세션이 시작됐습니다. depth 프레임을 대기합니다.",
        supported: true,
        error: null
      }));
      return true;
    } catch (error) {
      clearActiveSession();
      if (requestedSession) {
        void requestedSession.end().catch(() => undefined);
      }
      const message = depthSensorErrorMessage(error);
      setState((current) => ({
        ...current,
        status: isUnsupportedDepthError(error) ? "unsupported" : "error",
        message,
        supported: isUnsupportedDepthError(error) ? false : current.supported,
        error: message
      }));
      return false;
    }
  }, [clearActiveSession]);

  const estimateDepthForDetection = useCallback((detection: TwoModelDetection): DepthEstimateResult | null => {
    return estimateSensorDepthForBBox(detection.bbox, latestSamplerRef.current, {
      motionStability: motionStabilityRef.current,
      detectionConfidence: detection.confidence
    });
  }, []);

  const getLatestSample = useCallback(() => latestSampleRef.current, []);

  useEffect(() => {
    return () => {
      const session = sessionRef.current;
      clearActiveSession();
      void session?.end().catch(() => undefined);
    };
  }, [clearActiveSession]);

  return {
    ...state,
    state,
    latestSampleRef,
    getLatestSample,
    startDepthSensor,
    stopDepthSensor,
    checkDepthSupport,
    estimateDepthForDetection,
    canStart: state.status !== "checking" && state.status !== "requesting" && state.status !== "running" && state.status !== "stopping",
    isRunning: state.status === "running"
  };
}
