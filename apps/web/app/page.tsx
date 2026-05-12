"use client";

import { AlertTriangle, Camera, Loader2, MapPin, Send, Volume2, VolumeX } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createFakeDetection } from "@/lib/detector";
import { submitReport } from "@/lib/report-api";
import { CLASS_LABELS, type DetectionEvent, type GpsFix } from "@/types/inference";

const DETECTOR_MODE = process.env.NEXT_PUBLIC_DETECTOR_MODE ?? "fake";
const SPEECH_COOLDOWN_MS = 6000;

function formatPercent(value: number) {
  return `${Math.round(value * 100)}%`;
}

function formatGps(gps: GpsFix | null) {
  if (!gps) {
    return "위치 대기 중";
  }
  return `${gps.latitude.toFixed(5)}, ${gps.longitude.toFixed(5)}`;
}

export default function Home() {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const detectionIndexRef = useRef(0);
  const lastSpeechRef = useRef<{ key: string; time: number }>({ key: "", time: 0 });

  const [cameraReady, setCameraReady] = useState(false);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const [gps, setGps] = useState<GpsFix | null>(null);
  const [gpsError, setGpsError] = useState<string | null>(null);
  const [heading, setHeading] = useState<number | null>(null);
  const [detection, setDetection] = useState<DetectionEvent | null>(null);
  const [speechEnabled, setSpeechEnabled] = useState(true);
  const [reportState, setReportState] = useState<"idle" | "sending" | "sent" | "error">("idle");
  const [reportMessage, setReportMessage] = useState("신고 대기 중");

  const detectionLabel = detection ? CLASS_LABELS[detection.class_name] : "탐지 대기";
  const riskText = detection ? `${detectionLabel} ${formatPercent(detection.confidence)}` : "위험 요소 없음";

  const startCamera = useCallback(async () => {
    if (!navigator.mediaDevices?.getUserMedia) {
      setCameraError("이 브라우저는 카메라 입력을 지원하지 않습니다.");
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
      setCameraError("카메라 권한을 허용해야 보행 화면을 사용할 수 있습니다.");
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
    if (DETECTOR_MODE !== "fake") {
      return;
    }

    const intervalId = window.setInterval(() => {
      detectionIndexRef.current += 1;
      setDetection(createFakeDetection(detectionIndexRef.current, gps, heading));
    }, 2800);

    return () => window.clearInterval(intervalId);
  }, [gps, heading]);

  useEffect(() => {
    if (!speechEnabled || !detection) {
      return;
    }

    const now = Date.now();
    const key = detection.class_name;
    if (lastSpeechRef.current.key === key && now - lastSpeechRef.current.time < SPEECH_COOLDOWN_MS) {
      return;
    }

    lastSpeechRef.current = { key, time: now };
    const utterance = new SpeechSynthesisUtterance(`${CLASS_LABELS[detection.class_name]} 감지. 전방을 확인하세요.`);
    utterance.lang = "ko-KR";
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(utterance);
  }, [detection, speechEnabled]);

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
    if (!detection) {
      setReportState("error");
      setReportMessage("신고할 탐지 결과가 없습니다.");
      return;
    }

    setReportState("sending");
    setReportMessage("신고 전송 중");

    try {
      const image = await captureFrame();
      const response = await submitReport({ ...detection, gps, heading }, image);
      setReportState("sent");
      setReportMessage(`신고 저장 완료: ${response.id.slice(0, 8)}`);
    } catch (error) {
      setReportState("error");
      setReportMessage(error instanceof Error ? error.message : "신고 전송 실패");
    }
  }, [captureFrame, detection, gps, heading]);

  const reportButtonLabel = useMemo(() => {
    if (reportState === "sending") {
      return "전송 중";
    }
    return "현재 위험 신고";
  }, [reportState]);

  return (
    <main className="assist-shell">
      <section className="camera-surface" aria-label="보행 보조 카메라 화면">
        <video ref={videoRef} className="camera-video" muted playsInline aria-label="후면 카메라 미리보기" />
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
          <div className="risk-pill" aria-live="polite">
            <AlertTriangle aria-hidden="true" size={18} />
            <span>{riskText}</span>
          </div>
          <div className="mode-pill">{DETECTOR_MODE === "fake" ? "Fake 탐지" : "모델 연결 대기"}</div>
        </div>
      </section>

      <section className="assist-panel" aria-label="보행 보조 상태와 신고">
        <div className="status-grid">
          <div className="status-item">
            <span className="status-label">현재 위험</span>
            <strong>{detectionLabel}</strong>
          </div>
          <div className="status-item">
            <span className="status-label">GPS</span>
            <strong>{gpsError ?? formatGps(gps)}</strong>
          </div>
          <div className="status-item">
            <span className="status-label">방향</span>
            <strong>{heading === null ? "대기 중" : `${heading}도`}</strong>
          </div>
          <div className="status-item">
            <span className="status-label">신고</span>
            <strong>{reportMessage}</strong>
          </div>
        </div>

        <div className="control-row">
          <button className="control-button secondary" type="button" onClick={() => setSpeechEnabled((value) => !value)}>
            {speechEnabled ? <Volume2 aria-hidden="true" size={22} /> : <VolumeX aria-hidden="true" size={22} />}
            {speechEnabled ? "음성 켜짐" : "음성 꺼짐"}
          </button>
          <button className="control-button secondary" type="button" onClick={startCamera}>
            <MapPin aria-hidden="true" size={22} />
            센서 갱신
          </button>
        </div>

        <button className="report-button" type="button" onClick={handleReport} disabled={reportState === "sending"}>
          {reportState === "sending" ? <Loader2 className="spin" aria-hidden="true" size={24} /> : <Send aria-hidden="true" size={24} />}
          {reportButtonLabel}
        </button>
      </section>
    </main>
  );
}
