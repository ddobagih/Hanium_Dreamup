"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { GpsFix } from "@/types/inference";
import { WALKSAFE_DEFAULT_STEP_LENGTH_M } from "../config";
import {
  buildStepLengthCalibrationLog,
  createStoredStepLengthCalibration,
  estimateStepLengthFromGpsAndMotion,
  parseStoredStepLengthCalibration,
  type GpsStepLengthSample,
  type StepLengthCalibrationLog,
  type StepLengthEstimate,
  type StoredStepLengthCalibration
} from "../step-length";

export type MotionPermissionStatus = "unsupported" | "prompt" | "granted" | "denied" | "listening";

export type AutoStepLengthState = StepLengthEstimate & {
  motionPermissionStatus: MotionPermissionStatus;
  motionSampleCount: number;
  motionStability: number;
  shakeScore: number;
  storedCalibrationActive: boolean;
  calibrationLog: StepLengthCalibrationLog;
  requestMotionPermission: () => Promise<MotionPermissionStatus>;
  resetCalibration: () => void;
};

type DeviceMotionPermissionEvent = typeof DeviceMotionEvent & {
  requestPermission?: () => Promise<"granted" | "denied">;
};

type MotionSample = {
  observedAtMs: number;
  magnitude: number;
  deltaFromGravity: number;
};

const STORAGE_KEY = "walksafe.stepLengthCalibration.v1";
const MAX_GPS_SAMPLES = 24;
const MAX_STEP_EVENTS = 120;
const MAX_MOTION_SAMPLES = 240;
const STEP_PEAK_THRESHOLD = 1.15;
const STEP_RESET_THRESHOLD = 0.75;
const MIN_STEP_INTERVAL_MS = 320;
const MOTION_STABILITY_WINDOW_MS = 1500;
const SHAKE_SCORE_FULL_DELTA = 3.2;

function safeReadStoredCalibration(): StoredStepLengthCalibration | null {
  if (typeof window === "undefined") {
    return null;
  }
  try {
    return parseStoredStepLengthCalibration(window.localStorage.getItem(STORAGE_KEY));
  } catch {
    return null;
  }
}

function safeWriteStoredCalibration(calibration: StoredStepLengthCalibration): boolean {
  if (typeof window === "undefined") {
    return false;
  }
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(calibration));
    return true;
  } catch {
    return false;
  }
}

function safeRemoveStoredCalibration(): boolean {
  if (typeof window === "undefined") {
    return false;
  }
  try {
    window.localStorage.removeItem(STORAGE_KEY);
    return true;
  } catch {
    return false;
  }
}

function initialEstimate(storedCalibration: StoredStepLengthCalibration | null): StepLengthEstimate {
  return estimateStepLengthFromGpsAndMotion([], [], WALKSAFE_DEFAULT_STEP_LENGTH_M, storedCalibration);
}

function accelerationMagnitude(event: DeviceMotionEvent): number | null {
  const acceleration = event.accelerationIncludingGravity ?? event.acceleration;
  const x = acceleration?.x;
  const y = acceleration?.y;
  const z = acceleration?.z;
  if (typeof x !== "number" || typeof y !== "number" || typeof z !== "number") {
    return null;
  }
  return Math.sqrt(x * x + y * y + z * z);
}

function getDeviceMotionPermissionEvent(): DeviceMotionPermissionEvent | null {
  if (typeof window === "undefined" || !("DeviceMotionEvent" in window)) {
    return null;
  }
  return window.DeviceMotionEvent as DeviceMotionPermissionEvent;
}

function summarizeMotionStability(samples: readonly MotionSample[], nowMs: number): { motionStability: number; shakeScore: number } {
  const recentSamples = samples.filter((sample) => nowMs - sample.observedAtMs <= MOTION_STABILITY_WINDOW_MS);
  if (recentSamples.length < 3) {
    return { motionStability: 1, shakeScore: 0 };
  }

  const averageDelta = recentSamples.reduce((sum, sample) => sum + sample.deltaFromGravity, 0) / recentSamples.length;
  const shakeScore = Math.min(1, averageDelta / SHAKE_SCORE_FULL_DELTA);
  return {
    motionStability: Math.round((1 - shakeScore) * 100) / 100,
    shakeScore: Math.round(shakeScore * 100) / 100
  };
}

function initialMotionPermissionStatus(): MotionPermissionStatus {
  const motionEvent = getDeviceMotionPermissionEvent();
  if (!motionEvent) {
    return "unsupported";
  }
  return typeof motionEvent.requestPermission === "function" ? "prompt" : "granted";
}

export function useAutoStepLength(gps: GpsFix | null): AutoStepLengthState {
  const initialStoredCalibration = useMemo(() => safeReadStoredCalibration(), []);
  const gpsSamplesRef = useRef<GpsStepLengthSample[]>([]);
  const stepEventsRef = useRef<number[]>([]);
  const motionSamplesRef = useRef<MotionSample[]>([]);
  const previousMotionDeltaRef = useRef(0);
  const lastStepAtMsRef = useRef(0);
  const [storedCalibration, setStoredCalibration] = useState<StoredStepLengthCalibration | null>(() => initialStoredCalibration);
  const [estimate, setEstimate] = useState<StepLengthEstimate>(() => initialEstimate(initialStoredCalibration));
  const [motionPermissionStatus, setMotionPermissionStatus] = useState<MotionPermissionStatus>(() => initialMotionPermissionStatus());
  const [motionSampleCount, setMotionSampleCount] = useState(0);
  const [motionStability, setMotionStability] = useState(1);
  const [shakeScore, setShakeScore] = useState(0);
  const [calibrationLog, setCalibrationLog] = useState<StepLengthCalibrationLog>(() =>
    buildStepLengthCalibrationLog(initialEstimate(initialStoredCalibration), 0)
  );

  const refreshEstimate = useCallback(() => {
    const nextEstimate = estimateStepLengthFromGpsAndMotion(
      gpsSamplesRef.current,
      stepEventsRef.current,
      WALKSAFE_DEFAULT_STEP_LENGTH_M,
      storedCalibration
    );
    const nextLog = buildStepLengthCalibrationLog(nextEstimate, motionSamplesRef.current.length);
    setEstimate(nextEstimate);
    setCalibrationLog(nextLog);

    const nextStoredCalibration = createStoredStepLengthCalibration(nextEstimate);
    if (nextStoredCalibration) {
      safeWriteStoredCalibration(nextStoredCalibration);
      setStoredCalibration(nextStoredCalibration);
    }
  }, [storedCalibration]);

  const requestMotionPermission = useCallback(async (): Promise<MotionPermissionStatus> => {
    const motionEvent = getDeviceMotionPermissionEvent();
    if (!motionEvent) {
      setMotionPermissionStatus("unsupported");
      return "unsupported";
    }

    if (typeof motionEvent.requestPermission !== "function") {
      setMotionPermissionStatus("granted");
      return "granted";
    }

    try {
      const result = await motionEvent.requestPermission();
      const status: MotionPermissionStatus = result === "granted" ? "granted" : "denied";
      setMotionPermissionStatus(status);
      return status;
    } catch {
      setMotionPermissionStatus("denied");
      return "denied";
    }
  }, []);

  const resetCalibration = useCallback(() => {
    safeRemoveStoredCalibration();
    gpsSamplesRef.current = [];
    stepEventsRef.current = [];
    motionSamplesRef.current = [];
    previousMotionDeltaRef.current = 0;
    lastStepAtMsRef.current = 0;
    setStoredCalibration(null);
    setMotionSampleCount(0);
    setMotionStability(1);
    setShakeScore(0);
    const nextEstimate = initialEstimate(null);
    setEstimate(nextEstimate);
    setCalibrationLog(buildStepLengthCalibrationLog(nextEstimate, 0));
  }, []);

  useEffect(() => {
    if (!gps) {
      return;
    }

    gpsSamplesRef.current = [
      ...gpsSamplesRef.current,
      {
        latitude: gps.latitude,
        longitude: gps.longitude,
        accuracy_m: gps.accuracy_m,
        speed_mps: gps.speed_mps ?? null,
        observedAtMs: Date.now()
      }
    ].slice(-MAX_GPS_SAMPLES);
    refreshEstimate();
  }, [gps, refreshEstimate]);

  useEffect(() => {
    if (typeof window === "undefined" || !getDeviceMotionPermissionEvent()) {
      return;
    }
    if (motionPermissionStatus !== "granted" && motionPermissionStatus !== "listening") {
      return;
    }

    const onMotion = (event: DeviceMotionEvent) => {
      const magnitude = accelerationMagnitude(event);
      if (magnitude === null) {
        return;
      }

      const nowMs = Date.now();
      const motionDelta = Math.abs(magnitude - 9.81);
      motionSamplesRef.current = [
        ...motionSamplesRef.current,
        { observedAtMs: nowMs, magnitude, deltaFromGravity: motionDelta }
      ].slice(-MAX_MOTION_SAMPLES);
      const motionSummary = summarizeMotionStability(motionSamplesRef.current, nowMs);
      setMotionSampleCount(motionSamplesRef.current.length);
      setMotionStability(motionSummary.motionStability);
      setShakeScore(motionSummary.shakeScore);
      setMotionPermissionStatus("listening");

      const wasBelowReset = previousMotionDeltaRef.current < STEP_RESET_THRESHOLD;
      const enoughInterval = nowMs - lastStepAtMsRef.current >= MIN_STEP_INTERVAL_MS;
      previousMotionDeltaRef.current = motionDelta;

      if (motionDelta < STEP_PEAK_THRESHOLD || !wasBelowReset || !enoughInterval) {
        return;
      }

      lastStepAtMsRef.current = nowMs;
      stepEventsRef.current = [...stepEventsRef.current, nowMs].slice(-MAX_STEP_EVENTS);
      refreshEstimate();
    };

    window.addEventListener("devicemotion", onMotion);
    return () => window.removeEventListener("devicemotion", onMotion);
  }, [motionPermissionStatus, refreshEstimate]);

  return {
    ...estimate,
    motionPermissionStatus,
    motionSampleCount,
    motionStability,
    shakeScore,
    storedCalibrationActive: estimate.source === "stored_calibration",
    calibrationLog,
    requestMotionPermission,
    resetCalibration
  };
}
