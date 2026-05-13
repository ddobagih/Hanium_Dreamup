"use client";

import { AlertTriangle, Camera, Loader2, MapPin, Navigation, RefreshCw, Send, Volume2, VolumeX } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createFakeDetection } from "@/lib/detector";
import { checkDuplicateReports, submitReport } from "@/lib/report-api";
import { CLASS_LABELS, type DetectionClassName, type DetectionEvent, type GpsFix } from "@/types/inference";

const DETECTOR_MODE = process.env.NEXT_PUBLIC_DETECTOR_MODE ?? "fake";
const SPEECH_COOLDOWN_MS = 6000;

const RISK_ALERTS: Record<DetectionClassName, { speech: string; vibration: VibratePattern; silentVibration: VibratePattern }> = {
  damaged_tactile_block: {
    speech: "점자블록 파손. 발밑 주의.",
    vibration: [180, 80, 180],
    silentVibration: [420, 120, 420, 120, 420]
  },
  parked_kickboard_bicycle: {
    speech: "전방 장애물. 천천히 이동.",
    vibration: [260, 120, 120],
    silentVibration: [500, 160, 260, 160, 260]
  },
  construction_obstacle: {
    speech: "공사 장애물. 우회하세요.",
    vibration: [300, 100, 300],
    silentVibration: [520, 140, 520, 140, 260]
  },
  pothole: {
    speech: "노면 파임. 발밑 주의.",
    vibration: [120, 70, 120, 70, 300],
    silentVibration: [300, 100, 300, 100, 520]
  }
};

function speak(message: string) {
  if (!("speechSynthesis" in window)) {
    return;
  }

  const utterance = new SpeechSynthesisUtterance(message);
  utterance.lang = "ko-KR";
  utterance.rate = 0.95;
  window.speechSynthesis.cancel();
  window.speechSynthesis.speak(utterance);
}

function vibrate(pattern: VibratePattern) {
  if ("vibrate" in navigator) {
    navigator.vibrate(pattern);
  }
}

function formatPercent(value: number) {
  return `${Math.round(value * 100)}%`;
}

function formatGps(gps: GpsFix | null) {
  if (!gps) {
    return "위치 대기 중";
  }
  return `${gps.latitude.toFixed(5)}, ${gps.longitude.toFixed(5)}`;
}

function headingLabel(value: number | null) {
  if (value === null) {
    return "대기 중";
  }

  const normalized = ((value % 360) + 360) % 360;
  const directions = ["북", "북동", "동", "남동", "남", "남서", "서", "북서"];
  return directions[Math.round(normalized / 45) % directions.length];
}

function alertForDetection(detection: DetectionEvent, speechEnabled: boolean) {
  const alert = RISK_ALERTS[detection.class_name];
  if (detection.confidence >= 0.9) {
    return {
      speech: `${alert.speech} 강한 위험 신호.`,
      vibration: speechEnabled ? [...(alert.vibration as number[]), 120, 360] : [...(alert.silentVibration as number[]), 160, 520]
    };
  }

  return {
    speech: alert.speech,
    vibration: speechEnabled ? alert.vibration : alert.silentVibration
  };
}

export default function Home() {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const detectionIndexRef = useRef(0);
  const lastAlertRef = useRef<{ key: string; time: number }>({ key: "", time: 0 });
  const reportStateRef = useRef<"idle" | "sending" | "sent" | "error">("idle");

  const [cameraReady, setCameraReady] = useState(false);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const [gps, setGps] = useState<GpsFix | null>(null);
  const [gpsError, setGpsError] = useState<string | null>(null);
  const [heading, setHeading] = useState<number | null>(null);
  const [detection, setDetection] = useState<DetectionEvent | null>(null);
  const [speechEnabled, setSpeechEnabled] = useState(true);
  const [reportState, setReportState] = useState<"idle" | "sending" | "sent" | "error">("idle");
  const [reportMessage, setReportMessage] = useState("신고 대기 중");
  const [lastDuplicateCount, setLastDuplicateCount] = useState(0);

  const detectionLabel = detection ? CLASS_LABELS[detection.class_name] : "탐지 대기";
  const riskText = detection ? `${detectionLabel} ${formatPercent(detection.confidence)}` : "위험 요소 없음";
  const directionLabel = headingLabel(heading);
  const modeText = DETECTOR_MODE === "fake" ? "데모 탐지 모드" : "모델 연결 대기";
  const canReport = Boolean(detection) && cameraReady && reportState !== "sending";
  const reportDisabledReason = !cameraReady
    ? "카메라가 준비되면 신고할 수 있습니다."
    : !detection
      ? "탐지된 위험이 없습니다. 위험이 감지되면 신고할 수 있습니다."
      : "";

  const startCamera = useCallback(async () => {
    if (!navigator.mediaDevices?.getUserMedia) {
      setCameraError("이 브라우저는 카메라 입력을 지원하지 않습니다.");
      setDetection(null);
      setReportMessage("카메라 준비 필요");
      return;
    }

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: {
          facingMode: { ideal: "environment" },
          width: { ideal: 1280 },
          height: { ideal: 720 }
        }
      });

      streamRef.current = stream;
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        await videoRef.current.play();
      }
      setCameraReady(true);
      setCameraError(null);
    } catch {
      setCameraReady(false);
      setDetection(null);
      setCameraError("카메라 권한을 허용해야 보행 화면을 사용할 수 있습니다.");
      setReportState("idle");
      setReportMessage("카메라 준비 필요");
    }
  }, []);

  useEffect(() => {
    const cameraTimer = window.setTimeout(() => {
      void startCamera();
    }, 0);

    if ("serviceWorker" in navigator) {
      navigator.serviceWorker.register("/sw.js").catch(() => undefined);
    }

    return () => {
      window.clearTimeout(cameraTimer);
      streamRef.current?.getTracks().forEach((track) => track.stop());
    };
  }, [startCamera]);

  useEffect(() => {
    if (!navigator.geolocation) {
      const gpsTimer = window.setTimeout(() => setGpsError("GPS 미지원"), 0);
      return () => window.clearTimeout(gpsTimer);
    }

    const watchId = navigator.geolocation.watchPosition(
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

    return () => navigator.geolocation.clearWatch(watchId);
  }, []);

  useEffect(() => {
    const onOrientation = (event: DeviceOrientationEvent) => {
      if (typeof event.alpha === "number") {
        setHeading(Math.round(event.alpha));
      }
    };

    window.addEventListener("deviceorientation", onOrientation);
    return () => window.removeEventListener("deviceorientation", onOrientation);
  }, []);

  useEffect(() => {
    if (DETECTOR_MODE !== "fake" || !cameraReady) {
      return;
    }

    const intervalId = window.setInterval(() => {
      detectionIndexRef.current += 1;
      const nextDetection = createFakeDetection(detectionIndexRef.current, gps, heading);
      setDetection(nextDetection);
      if (reportStateRef.current !== "sending") {
        setReportState("idle");
        setLastDuplicateCount(0);
        setReportMessage(`${CLASS_LABELS[nextDetection.class_name]} 신고 가능`);
      }
    }, 2800);

    return () => window.clearInterval(intervalId);
  }, [cameraReady, gps, heading]);

  useEffect(() => {
    if (!detection) {
      return;
    }

    const now = Date.now();
    const key = detection.class_name;
    if (lastAlertRef.current.key === key && now - lastAlertRef.current.time < SPEECH_COOLDOWN_MS) {
      return;
    }

    lastAlertRef.current = { key, time: now };
    const alert = alertForDetection(detection, speechEnabled);
    vibrate(alert.vibration);
    if (speechEnabled) {
      speak(alert.speech);
    }
  }, [detection, speechEnabled]);

  useEffect(() => {
    reportStateRef.current = reportState;
  }, [reportState]);

  const captureFrame = useCallback(async () => {
    const video = videoRef.current;
    if (!video || video.videoWidth === 0 || video.videoHeight === 0) {
      throw new Error("카메라 프레임을 아직 캡처할 수 없습니다.");
    }

    const canvas = document.createElement("canvas");
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    const context = canvas.getContext("2d");
    if (!context) {
      throw new Error("캡처 캔버스를 만들 수 없습니다.");
    }

    context.drawImage(video, 0, 0, canvas.width, canvas.height);

    return new Promise<Blob>((resolve, reject) => {
      canvas.toBlob(
        (blob) => {
          if (blob) {
            resolve(blob);
          } else {
            reject(new Error("이미지 캡처에 실패했습니다."));
          }
        },
        "image/jpeg",
        0.86
      );
    });
  }, []);

  const handleReport = useCallback(async () => {
    if (!detection || !cameraReady) {
      setReportState("error");
      setReportMessage(reportDisabledReason || "신고할 탐지 결과가 없습니다.");
      return;
    }

    setReportState("sending");
    setLastDuplicateCount(0);
    setReportMessage(gps ? "유사 신고 확인 중" : "신고 전송 중");

    try {
      const reportSnapshot: DetectionEvent = {
        ...detection,
        captured_at: new Date().toISOString(),
        gps,
        heading
      };
      let duplicateCount = 0;
      try {
        const duplicateCheck = await checkDuplicateReports(reportSnapshot);
        duplicateCount = duplicateCheck?.duplicate_report_ids.length ?? 0;
      } catch {
        setReportMessage("중복 확인 실패 · 신고 저장 중");
      }

      if (duplicateCount > 0) {
        setLastDuplicateCount(duplicateCount);
        setReportMessage(`유사 신고 ${duplicateCount}건 확인 · 저장 중`);
        if (speechEnabled) {
          speak("유사 신고 있음. 저장합니다.");
        }
      } else {
        setReportMessage("신고 전송 중");
      }

      const image = await captureFrame();
      const response = await submitReport(reportSnapshot, image);
      const responseDuplicateCount = response.duplicate_report_ids.length || duplicateCount;
      setLastDuplicateCount(responseDuplicateCount);
      setReportState("sent");
      setReportMessage(
        responseDuplicateCount > 0
          ? `신고 저장 완료 · 유사 신고 ${responseDuplicateCount}건`
          : `신고 저장 완료: ${response.id.slice(0, 8)}`
      );
      vibrate(responseDuplicateCount > 0 ? [90, 70, 90, 70, 180] : [80, 70, 180]);
      if (speechEnabled) {
        speak(responseDuplicateCount > 0 ? "신고 저장 완료. 유사 신고 있음." : "신고 저장 완료");
      }
    } catch (error) {
      setReportState("error");
      setReportMessage(error instanceof Error ? `${error.message} · 다시 신고 가능` : "신고 전송 실패 · 다시 신고 가능");
      vibrate([420, 160, 420]);
      if (speechEnabled) {
        speak("신고 실패. 다시 누르세요.");
      }
    }
  }, [cameraReady, captureFrame, detection, gps, heading, reportDisabledReason, speechEnabled]);

  const handleSpeechToggle = useCallback(() => {
    setSpeechEnabled((current) => {
      const next = !current;
      vibrate(60);
      speak(next ? "음성 안내 켜짐" : "음성 안내 꺼짐");
      return next;
    });
  }, []);

  const reportButtonLabel = useMemo(() => {
    if (reportState === "sending") {
      return "전송 중";
    }
    if (reportState === "error") {
      return "다시 신고";
    }
    return "현재 위험 신고";
  }, [reportState]);

  return (
    <main className="assist-shell">
      <section className="camera-surface" aria-label="보행 보조 카메라 화면">
        <video ref={videoRef} className="camera-video" muted playsInline aria-label="후면 카메라 미리보기" />
        <header className="app-header">
          <div>
            <strong>WalkSafe Assist</strong>
            <span>목걸이 착용 보행 감지</span>
          </div>
          <div className="header-actions">
            <span className="mode-pill demo">{modeText}</span>
            <a href="/admin">관리자</a>
          </div>
        </header>
        {!cameraReady ? (
          <div className="camera-fallback">
            <Camera aria-hidden="true" size={40} />
            <strong>카메라 준비 중</strong>
            <span>{cameraError ?? "후면 카메라 권한을 확인하고 있습니다."}</span>
            <button className="control-button secondary" type="button" onClick={startCamera}>
              <Camera aria-hidden="true" size={20} />
              카메라 다시 연결
            </button>
          </div>
        ) : null}

        {detection ? (
          <div
            className="detection-box"
            style={{
              left: `${detection.bbox.x * 100}%`,
              top: `${detection.bbox.y * 100}%`,
              width: `${detection.bbox.width * 100}%`,
              height: `${detection.bbox.height * 100}%`
            }}
            aria-hidden="true"
          >
            <span>{CLASS_LABELS[detection.class_name]}</span>
          </div>
        ) : null}

        <div className="top-status">
          <div
            className={`risk-pill ${detection ? "danger" : "safe"}`}
            role={detection ? "alert" : "status"}
            aria-live={detection ? "assertive" : "polite"}
          >
            <AlertTriangle aria-hidden="true" size={18} />
            <span>{riskText}</span>
          </div>
          <span>{detection ? "전방 확인" : "탐지 대기"}</span>
        </div>
      </section>

      <section className="assist-panel" aria-label="보행 보조 상태와 신고">
        <div className="status-grid">
          <div className={`status-item current-risk ${detection ? "warning" : "safe"}`} aria-live="polite">
            <span className="status-label">현재 위험</span>
            <strong>{detectionLabel}</strong>
            <small>{detection ? `신뢰도 ${formatPercent(detection.confidence)}` : "탐지 대기 중"}</small>
          </div>
          <div className="status-item">
            <span className="status-label">위치</span>
            <strong>{gpsError ?? formatGps(gps)}</strong>
            <small>{gps?.accuracy_m === null || gps?.accuracy_m === undefined ? "정확도 대기" : `정확도 ${gps.accuracy_m.toFixed(1)}m`}</small>
          </div>
          <div className="status-item">
            <span className="status-label">방향</span>
            <strong>{directionLabel}</strong>
            <small>{heading === null ? "센서 대기" : `${heading}도`}</small>
          </div>
          <div className="status-item">
            <span className="status-label">신고</span>
            <strong>{reportMessage}</strong>
            {lastDuplicateCount > 0 ? <small>중복 후보 {lastDuplicateCount}건</small> : null}
          </div>
        </div>

        <button
          className="voice-button"
          type="button"
          aria-pressed={speechEnabled}
          aria-label={speechEnabled ? "음성 안내 켜짐. 탭하면 꺼집니다." : "음성 안내 꺼짐. 탭하면 켜집니다."}
          onClick={handleSpeechToggle}
        >
          <span>
            {speechEnabled ? <Volume2 aria-hidden="true" size={22} /> : <VolumeX aria-hidden="true" size={22} />}
            {speechEnabled ? "음성 켜짐" : "음성 꺼짐"}
          </span>
          <small>위험 탐지 시 음성으로 경고합니다</small>
        </button>

        <button
          className="report-button"
          type="button"
          onClick={handleReport}
          disabled={!canReport}
          aria-label={reportDisabledReason || "현재 탐지된 위험을 신고합니다."}
        >
          {reportState === "sending" ? (
            <Loader2 className="spin" aria-hidden="true" size={24} />
          ) : (
            <Send aria-hidden="true" size={24} />
          )}
          <span className="button-stack">
            <span>{reportButtonLabel}</span>
            <small>{reportDisabledReason || reportMessage}</small>
          </span>
        </button>

        <button className="control-button secondary sensor-action" type="button" onClick={startCamera}>
          <RefreshCw aria-hidden="true" size={20} />
          카메라/센서 재연결
        </button>
        <p className="assist-note">
          <MapPin aria-hidden="true" size={16} />
          {gpsError ? "위치 없음 상태" : `위치 정확도 ${gps?.accuracy_m?.toFixed(1) ?? "대기"}m`}
        </p>
        <p className="assist-note">
          <Navigation aria-hidden="true" size={16} />
          목걸이 착용 · {modeText}
        </p>
      </section>
    </main>
  );
}
