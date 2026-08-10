/**
 * Owns browser geolocation and earth-referenced orientation subscriptions used by navigation and reports.
 * Relative device alpha is never exposed as a compass heading; unsupported or ambiguous readings stay null.
 */
import { useCallback, useEffect, useRef, useState } from "react";
import type { GpsFix } from "@/types/inference";
import {
  eventClaimsAbsoluteHeading,
  readScreenOrientationAngle,
  resolveAbsoluteHeadingDegrees,
  type CompassDeviceOrientationEvent
} from "../absolute-heading";
import { updateAlphaBetaLocation, type AlphaBetaLocationState } from "../motion-projection";
import {
  gpsFixExpiryDelayMs,
  headingFixExpiryDelayMs,
  isGpsFixFresh
} from "../gps-freshness";
import { deviceOrientationEventWithPermission, headingLabel } from "../utils";

export function useSensors(enabled = true) {
  const gpsWatchIdRef = useRef<number | null>(null);
  const gpsExpiryTimerRef = useRef<number | null>(null);
  const gpsObservedAtMsRef = useRef<number | null>(null);
  const headingObservedAtMsRef = useRef<number | null>(null);
  const headingExpiryTimerRef = useRef<number | null>(null);
  const locationFilterRef = useRef<AlphaBetaLocationState | null>(null);
  const headingRef = useRef<number | null>(null);
  const sensorsEnabledRef = useRef(enabled);
  const [sensorsStopped, setSensorsStopped] = useState(false);
  const sensorsEnabled = enabled && !sensorsStopped;
  const [gps, setGps] = useState<GpsFix | null>(null);
  const [gpsError, setGpsError] = useState<string | null>(null);
  const [heading, setHeading] = useState<number | null>(null);
  const [headingObservedAtMs, setHeadingObservedAtMs] = useState<number | null>(null);
  const [headingMessage, setHeadingMessage] = useState("센서 대기");
  const activeGps = sensorsEnabled ? gps : null;
  const activeGpsError = sensorsEnabled ? gpsError : null;
  const activeHeading = sensorsEnabled ? heading : null;
  const activeHeadingObservedAtMs = sensorsEnabled && activeHeading !== null ? headingObservedAtMs : null;
  const activeHeadingMessage = sensorsEnabled ? headingMessage : "센서 중지";
  const directionLabel = headingLabel(activeHeading);

  const clearHeadingState = useCallback((message: string) => {
    headingRef.current = null;
    headingObservedAtMsRef.current = null;
    if (headingExpiryTimerRef.current !== null) {
      window.clearTimeout(headingExpiryTimerRef.current);
      headingExpiryTimerRef.current = null;
    }
    setHeading(null);
    setHeadingObservedAtMs(null);
    setHeadingMessage(message);
  }, []);

  const getCurrentLocationMessage = useCallback(() => {
    if (!sensorsEnabled) {
      return "센서가 중지되어 현재 위치와 방향을 사용할 수 없습니다.";
    }
    if (activeGpsError) {
      return `현재 위치를 확인할 수 없습니다. ${activeGpsError}.`;
    }
    if (!activeGps) {
      return "현재 위치를 기다리는 중입니다.";
    }

    const accuracyText =
      activeGps.accuracy_m === null || activeGps.accuracy_m === undefined
        ? "정확도는 대기 중입니다."
        : `정확도는 ${activeGps.accuracy_m.toFixed(1)}미터입니다.`;
    const headingText = activeHeading === null ? "" : ` 방향은 ${directionLabel}입니다.`;
    return `현재 위치는 위도 ${activeGps.latitude.toFixed(5)}, 경도 ${activeGps.longitude.toFixed(5)}입니다. ${accuracyText}${headingText}`;
  }, [activeGps, activeGpsError, activeHeading, directionLabel, sensorsEnabled]);

  const startGpsWatch = useCallback(() => {
    if (!enabled) {
      return;
    }
    sensorsEnabledRef.current = true;
    setSensorsStopped(false);
    gpsObservedAtMsRef.current = null;
    if (gpsExpiryTimerRef.current !== null) {
      window.clearTimeout(gpsExpiryTimerRef.current);
      gpsExpiryTimerRef.current = null;
    }
    if (!navigator.geolocation) {
      locationFilterRef.current = null;
      setGps(null);
      setGpsError("GPS 미지원");
      return;
    }

    if (gpsWatchIdRef.current !== null) {
      navigator.geolocation.clearWatch(gpsWatchIdRef.current);
      gpsWatchIdRef.current = null;
    }
    locationFilterRef.current = null;

    setGpsError("위치 권한 요청 중");
    gpsWatchIdRef.current = navigator.geolocation.watchPosition(
      (position) => {
        if (!sensorsEnabledRef.current) {
          return;
        }
        const nowMs = Date.now();
        const observedAtMs = Number.isFinite(position.timestamp) ? position.timestamp : nowMs;
        if (!isGpsFixFresh(observedAtMs, nowMs)) {
          locationFilterRef.current = null;
          gpsObservedAtMsRef.current = null;
          setGps(null);
          setGpsError("위치 정보가 10초 이상 갱신되지 않았습니다.");
          return;
        }
        const rawSpeedMps =
          typeof position.coords.speed === "number" && Number.isFinite(position.coords.speed) ? position.coords.speed : null;
        const accuracyM =
          Number.isFinite(position.coords.accuracy) && position.coords.accuracy >= 0 ? position.coords.accuracy : null;
        const filtered = updateAlphaBetaLocation(locationFilterRef.current, {
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          accuracyM,
          speedMps: rawSpeedMps,
          headingDeg: headingRef.current,
          observedAtMs
        });
        if (!filtered) {
          return;
        }
        locationFilterRef.current = filtered;
        if (!filtered.lastObservationAccepted) {
          return;
        }
        gpsObservedAtMsRef.current = observedAtMs;
        if (gpsExpiryTimerRef.current !== null) window.clearTimeout(gpsExpiryTimerRef.current);
        const expireIfStale = () => {
          if (gpsObservedAtMsRef.current !== observedAtMs || !sensorsEnabledRef.current) return;
          const remainingMs = gpsFixExpiryDelayMs(observedAtMs, Date.now());
          if (remainingMs > 0) {
            gpsExpiryTimerRef.current = window.setTimeout(expireIfStale, remainingMs + 1);
            return;
          }
          gpsExpiryTimerRef.current = null;
          gpsObservedAtMsRef.current = null;
          locationFilterRef.current = null;
          setGps(null);
          setGpsError("위치 정보가 10초 이상 갱신되지 않았습니다.");
        };
        gpsExpiryTimerRef.current = window.setTimeout(
          expireIfStale,
          gpsFixExpiryDelayMs(observedAtMs, nowMs) + 1
        );
        setGps({
          latitude: filtered.latitude,
          longitude: filtered.longitude,
          accuracy_m: filtered.accuracyM,
          speed_mps: filtered.speedMps
        });
        setGpsError(null);
      },
      (error) => {
        if (!sensorsEnabledRef.current) {
          return;
        }
        // A failed watch no longer proves that the last fix is current. Fail closed for
        // navigation/reporting and require a fresh successful position callback.
        locationFilterRef.current = null;
        gpsObservedAtMsRef.current = null;
        if (gpsExpiryTimerRef.current !== null) {
          window.clearTimeout(gpsExpiryTimerRef.current);
          gpsExpiryTimerRef.current = null;
        }
        setGps(null);
        if (error.code === error.PERMISSION_DENIED) {
          setGpsError("위치 권한이 차단됐습니다. 브라우저 사이트 설정에서 위치를 허용해 주세요.");
          return;
        }
        if (error.code === error.POSITION_UNAVAILABLE) {
          setGpsError("현재 위치를 가져올 수 없습니다. 기기 위치 서비스가 켜져 있는지 확인해 주세요.");
          return;
        }
        if (error.code === error.TIMEOUT) {
          setGpsError("위치 응답 시간이 초과됐습니다. 실외나 창가에서 다시 시도해 주세요.");
          return;
        }
        setGpsError("위치 권한 필요");
      },
      {
        enableHighAccuracy: true,
        maximumAge: 3000,
        timeout: 10000
      }
    );
  }, [enabled]);

  const requestHeadingPermission = useCallback(async () => {
    if (!enabled) {
      return;
    }
    sensorsEnabledRef.current = true;
    setSensorsStopped(false);
    const orientationEvent = deviceOrientationEventWithPermission();
    if (!orientationEvent) {
      clearHeadingState("방향 센서 미지원");
      return;
    }

    if (typeof orientationEvent.requestPermission !== "function") {
      setHeadingMessage("방향 센서 대기");
      return;
    }

    setHeadingMessage("방향 센서 권한 요청 중");
    try {
      const permission = await orientationEvent.requestPermission(true);
      if (!sensorsEnabledRef.current) {
        return;
      }
      if (permission !== "granted") {
        clearHeadingState("방향 센서 권한 필요");
        return;
      }
      setHeadingMessage("방향 센서 대기");
    } catch {
      if (!sensorsEnabledRef.current) {
        return;
      }
      clearHeadingState("방향 센서 권한을 허용해야 방향 안내를 사용할 수 있습니다");
    }
  }, [clearHeadingState, enabled]);

  const stopSensors = useCallback(() => {
    sensorsEnabledRef.current = false;
    setSensorsStopped(true);
    if (gpsWatchIdRef.current !== null) {
      navigator.geolocation?.clearWatch(gpsWatchIdRef.current);
      gpsWatchIdRef.current = null;
    }
    locationFilterRef.current = null;
    gpsObservedAtMsRef.current = null;
    if (gpsExpiryTimerRef.current !== null) {
      window.clearTimeout(gpsExpiryTimerRef.current);
      gpsExpiryTimerRef.current = null;
    }
    headingRef.current = null;
    headingObservedAtMsRef.current = null;
    if (headingExpiryTimerRef.current !== null) {
      window.clearTimeout(headingExpiryTimerRef.current);
      headingExpiryTimerRef.current = null;
    }
    setGps(null);
    setGpsError(null);
    setHeading(null);
    setHeadingObservedAtMs(null);
    setHeadingMessage("센서 중지");
  }, []);

  useEffect(() => {
    sensorsEnabledRef.current = sensorsEnabled;
    if (sensorsEnabled) {
      return;
    }

    if (gpsWatchIdRef.current !== null) {
      navigator.geolocation?.clearWatch(gpsWatchIdRef.current);
      gpsWatchIdRef.current = null;
    }
    locationFilterRef.current = null;
    gpsObservedAtMsRef.current = null;
    if (gpsExpiryTimerRef.current !== null) {
      window.clearTimeout(gpsExpiryTimerRef.current);
      gpsExpiryTimerRef.current = null;
    }
    headingRef.current = null;
    headingObservedAtMsRef.current = null;
    if (headingExpiryTimerRef.current !== null) {
      window.clearTimeout(headingExpiryTimerRef.current);
      headingExpiryTimerRef.current = null;
    }

    const resetTimer = window.setTimeout(() => {
      setGps(null);
      setGpsError(null);
      setHeading(null);
      setHeadingObservedAtMs(null);
      setHeadingMessage("센서 중지");
    }, 0);
    return () => window.clearTimeout(resetTimer);
  }, [sensorsEnabled]);

  useEffect(() => {
    return () => {
      if (gpsWatchIdRef.current !== null) {
        navigator.geolocation.clearWatch(gpsWatchIdRef.current);
        gpsWatchIdRef.current = null;
      }
      if (gpsExpiryTimerRef.current !== null) {
        window.clearTimeout(gpsExpiryTimerRef.current);
        gpsExpiryTimerRef.current = null;
      }
      if (headingExpiryTimerRef.current !== null) {
        window.clearTimeout(headingExpiryTimerRef.current);
        headingExpiryTimerRef.current = null;
      }
    };
  }, []);

  useEffect(() => {
    if (!sensorsEnabled) {
      return;
    }
    if (!deviceOrientationEventWithPermission()) {
      const orientationTimer = window.setTimeout(() => setHeadingMessage("방향 센서 미지원"), 0);
      return () => window.clearTimeout(orientationTimer);
    }

    const onOrientation = (rawEvent: DeviceOrientationEvent) => {
      if (!sensorsEnabledRef.current) {
        return;
      }
      const event = rawEvent as CompassDeviceOrientationEvent;
      const nextHeading = resolveAbsoluteHeadingDegrees({
        eventType: event.type,
        alpha: event.alpha,
        beta: event.beta,
        gamma: event.gamma,
        absolute: event.absolute,
        webkitCompassHeading: event.webkitCompassHeading,
        webkitCompassAccuracy: event.webkitCompassAccuracy,
        screenOrientationAngle: readScreenOrientationAngle(window)
      });
      if (nextHeading === null) {
        if (eventClaimsAbsoluteHeading(event)) {
          clearHeadingState("절대 방향 정보를 확인할 수 없습니다");
        } else if (headingRef.current === null) {
          setHeadingMessage("절대 방향 센서 대기");
        }
        return;
      }
      headingRef.current = nextHeading;
      const observedAtMs = Date.now();
      headingObservedAtMsRef.current = observedAtMs;
      setHeading(nextHeading);
      setHeadingObservedAtMs(observedAtMs);
      setHeadingMessage("절대 방향 센서 수신 중");
      if (headingExpiryTimerRef.current === null) {
        const expireIfStale = () => {
          const latestObservedAtMs = headingObservedAtMsRef.current;
          if (latestObservedAtMs === null || !sensorsEnabledRef.current) {
            headingExpiryTimerRef.current = null;
            return;
          }
          const remainingMs = headingFixExpiryDelayMs(latestObservedAtMs, Date.now());
          if (remainingMs > 0) {
            headingExpiryTimerRef.current = window.setTimeout(expireIfStale, remainingMs + 1);
            return;
          }
          headingExpiryTimerRef.current = null;
          headingRef.current = null;
          headingObservedAtMsRef.current = null;
          setHeading(null);
          setHeadingObservedAtMs(null);
          setHeadingMessage("방향 정보가 3초 이상 갱신되지 않아 안내를 일시 중지합니다");
        };
        headingExpiryTimerRef.current = window.setTimeout(
          expireIfStale,
          headingFixExpiryDelayMs(observedAtMs, observedAtMs) + 1
        );
      }
    };
    const onScreenOrientationChange = () => {
      if (!sensorsEnabledRef.current) {
        return;
      }
      clearHeadingState("화면 회전 후 절대 방향 센서 대기");
    };

    window.addEventListener("deviceorientation", onOrientation);
    window.addEventListener("deviceorientationabsolute", onOrientation as EventListener);
    window.addEventListener("orientationchange", onScreenOrientationChange);
    window.screen.orientation?.addEventListener("change", onScreenOrientationChange);
    return () => {
      window.removeEventListener("deviceorientation", onOrientation);
      window.removeEventListener("deviceorientationabsolute", onOrientation as EventListener);
      window.removeEventListener("orientationchange", onScreenOrientationChange);
      window.screen.orientation?.removeEventListener("change", onScreenOrientationChange);
    };
  }, [clearHeadingState, sensorsEnabled]);

  return {
    gps: activeGps,
    gpsError: activeGpsError,
    heading: activeHeading,
    headingObservedAtMs: activeHeadingObservedAtMs,
    headingMessage: activeHeadingMessage,
    directionLabel,
    startGpsWatch,
    stopSensors,
    requestHeadingPermission,
    getCurrentLocationMessage
  };
}
