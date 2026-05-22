import { useCallback, useEffect, useRef, useState } from "react";
import type { GpsFix } from "@/types/inference";
import { deviceOrientationEventWithPermission, headingLabel } from "../utils";

export function useSensors() {
  const gpsWatchIdRef = useRef<number | null>(null);
  const [gps, setGps] = useState<GpsFix | null>(null);
  const [gpsError, setGpsError] = useState<string | null>(null);
  const [heading, setHeading] = useState<number | null>(null);
  const [headingMessage, setHeadingMessage] = useState("센서 대기");
  const directionLabel = headingLabel(heading);

  const getCurrentLocationMessage = useCallback(() => {
    if (gpsError) {
      return `현재 위치를 확인할 수 없습니다. ${gpsError}.`;
    }
    if (!gps) {
      return "현재 위치를 기다리는 중입니다.";
    }

    const accuracyText =
      gps.accuracy_m === null || gps.accuracy_m === undefined ? "정확도는 대기 중입니다." : `정확도는 ${gps.accuracy_m.toFixed(1)}미터입니다.`;
    const headingText = heading === null ? "" : ` 방향은 ${directionLabel}입니다.`;
    return `현재 위치는 위도 ${gps.latitude.toFixed(5)}, 경도 ${gps.longitude.toFixed(5)}입니다. ${accuracyText}${headingText}`;
  }, [directionLabel, gps, gpsError, heading]);

  const startGpsWatch = useCallback(() => {
    if (!navigator.geolocation) {
      setGpsError("GPS 미지원");
      return;
    }

    if (gpsWatchIdRef.current !== null) {
      navigator.geolocation.clearWatch(gpsWatchIdRef.current);
      gpsWatchIdRef.current = null;
    }

    setGpsError(null);
    gpsWatchIdRef.current = navigator.geolocation.watchPosition(
      (position) => {
        setGps({
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          accuracy_m: position.coords.accuracy
        });
        setGpsError(null);
      },
      () => {
        setGpsError("위치 권한 필요");
      },
      {
        enableHighAccuracy: true,
        maximumAge: 3000,
        timeout: 10000
      }
    );
  }, []);

  const requestHeadingPermission = useCallback(async () => {
    const orientationEvent = deviceOrientationEventWithPermission();
    if (!orientationEvent) {
      setHeading(null);
      setHeadingMessage("방향 센서 미지원");
      return;
    }

    if (typeof orientationEvent.requestPermission !== "function") {
      setHeadingMessage("방향 센서 대기");
      return;
    }

    try {
      const permission = await orientationEvent.requestPermission();
      if (permission !== "granted") {
        setHeading(null);
      }
      setHeadingMessage(permission === "granted" ? "방향 센서 대기" : "방향 센서 권한 필요");
    } catch {
      setHeading(null);
      setHeadingMessage("방향 센서를 시작할 수 없습니다");
    }
  }, []);

  useEffect(() => {
    const gpsTimer = window.setTimeout(() => startGpsWatch(), 0);
    return () => {
      window.clearTimeout(gpsTimer);
      if (gpsWatchIdRef.current !== null) {
        navigator.geolocation.clearWatch(gpsWatchIdRef.current);
        gpsWatchIdRef.current = null;
      }
    };
  }, [startGpsWatch]);

  useEffect(() => {
    if (!deviceOrientationEventWithPermission()) {
      const orientationTimer = window.setTimeout(() => setHeadingMessage("방향 센서 미지원"), 0);
      return () => window.clearTimeout(orientationTimer);
    }

    const onOrientation = (event: DeviceOrientationEvent) => {
      if (typeof event.alpha === "number") {
        setHeading(Math.round(event.alpha) % 360);
        setHeadingMessage("방향 센서 수신 중");
      }
    };

    window.addEventListener("deviceorientation", onOrientation);
    return () => window.removeEventListener("deviceorientation", onOrientation);
  }, []);

  return {
    gps,
    gpsError,
    heading,
    headingMessage,
    directionLabel,
    startGpsWatch,
    requestHeadingPermission,
    getCurrentLocationMessage
  };
}
