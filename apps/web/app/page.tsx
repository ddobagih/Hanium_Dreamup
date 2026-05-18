"use client";

import { AlertTriangle, Camera, Loader2, MapPin, Mic, Navigation, RefreshCw, Send, Volume2, VolumeX } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { detectFrame, DETECT_API_BASE, fetchDetectHealth } from "@/lib/detect-api";
import { createFakeDetection } from "@/lib/detector";
import { checkDuplicateReports, submitReport } from "@/lib/report-api";
import { speechConfidence, uploadSpeechStt, VOICE_API_BASE, type VoiceIntent, type VoiceSttResponse } from "@/lib/voice-api";
import { CLASS_LABELS, type DetectionClassName, type DetectionEvent, type GpsFix } from "@/types/inference";

const DETECTOR_MODE = process.env.NEXT_PUBLIC_DETECTOR_MODE ?? "fake";
const SERVER_DETECT_INTERVAL_MS = 2800;
const SPEECH_COOLDOWN_MS = 6000;
const VOICE_RECORDING_MAX_MS = 5000;
const VOICE_INTENT_CONFIDENCE_THRESHOLD = 0.7;
const INITIAL_DETECTOR_MESSAGE =
  DETECTOR_MODE === "server" ? `서버 ${DETECT_API_BASE}` : DETECTOR_MODE === "fake" ? "데모 탐지 대기" : `탐지 모드 확인 필요: ${DETECTOR_MODE}`;

type VoiceRecordState = "idle" | "recording" | "uploading" | "error";
type DeviceOrientationEventWithPermission = typeof DeviceOrientationEvent & {
  requestPermission?: () => Promise<PermissionState>;
};

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

function preferredAudioMimeType() {
  if (typeof MediaRecorder === "undefined" || typeof MediaRecorder.isTypeSupported !== "function") {
    return "";
  }

  return (
    ["audio/webm;codecs=opus", "audio/webm", "audio/mp4"].find((mimeType) => MediaRecorder.isTypeSupported(mimeType)) ?? ""
  );
}

function destinationFromSlots(slots: Record<string, unknown>) {
  for (const key of ["destination", "place", "target"]) {
    const value = slots[key];
    if (typeof value === "string" && value.trim()) {
      return value.trim();
    }
  }
  return "";
}

function deviceOrientationEventWithPermission() {
  if (typeof window === "undefined" || !("DeviceOrientationEvent" in window)) {
    return null;
  }

  return window.DeviceOrientationEvent as DeviceOrientationEventWithPermission;
}

export default function Home() {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const gpsWatchIdRef = useRef<number | null>(null);
  const detectionIndexRef = useRef(0);
  const lastAlertRef = useRef<{ key: string; time: number }>({ key: "", time: 0 });
  const reportStateRef = useRef<"idle" | "sending" | "sent" | "error">("idle");
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const voiceChunksRef = useRef<Blob[]>([]);
  const voiceStreamRef = useRef<MediaStream | null>(null);
  const voiceStopTimerRef = useRef<number | null>(null);
  const lastStatusMessageRef = useRef<string | null>(null);

  const [cameraReady, setCameraReady] = useState(false);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const [gps, setGps] = useState<GpsFix | null>(null);
  const [gpsError, setGpsError] = useState<string | null>(null);
  const [heading, setHeading] = useState<number | null>(null);
  const [headingMessage, setHeadingMessage] = useState("센서 대기");
  const [detection, setDetection] = useState<DetectionEvent | null>(null);
  const [detectorMessage, setDetectorMessage] = useState(INITIAL_DETECTOR_MESSAGE);
  const [detectorBusy, setDetectorBusy] = useState(false);
  const [speechEnabled, setSpeechEnabled] = useState(true);
  const [reportState, setReportState] = useState<"idle" | "sending" | "sent" | "error">("idle");
  const [reportMessage, setReportMessage] = useState("신고 대기 중");
  const [lastDuplicateCount, setLastDuplicateCount] = useState(0);
  const [voiceSupported, setVoiceSupported] = useState(true);
  const [voiceState, setVoiceState] = useState<VoiceRecordState>("idle");
  const [voiceMessage, setVoiceMessage] = useState("음성 명령 대기");
  const [voiceTranscript, setVoiceTranscript] = useState<string | null>(null);
  const [voiceIntent, setVoiceIntent] = useState<VoiceIntent | null>(null);
  const [voiceConfidence, setVoiceConfidence] = useState<number | null>(null);
  const [destination, setDestination] = useState("");
  const [navigationActive, setNavigationActive] = useState(false);

  const detectionLabel = detection ? CLASS_LABELS[detection.class_name] : "탐지 대기";
  const riskText = detection ? `${detectionLabel} ${formatPercent(detection.confidence)}` : "위험 요소 없음";
  const directionLabel = headingLabel(heading);
  const modeText = DETECTOR_MODE === "fake" ? "데모 탐지 모드" : DETECTOR_MODE === "server" ? "서버 탐지 모드" : "모델 연결 대기";
  const detectorStatusText = DETECTOR_MODE === "server" && detectorBusy && !detection ? "서버 탐지 중" : detectorMessage;
  const canReport = Boolean(detection) && cameraReady && reportState !== "sending";
  const voiceResultText = voiceTranscript
    ? `${voiceTranscript} · ${voiceIntent ?? "unknown"}${voiceConfidence === null ? "" : ` ${formatPercent(voiceConfidence)}`}`
    : destination
      ? `${navigationActive ? "안내 준비" : "목적지 저장"} · ${destination}`
      : `서버 ${VOICE_API_BASE}`;
  const reportDisabledReason = !cameraReady
    ? "카메라가 준비되면 신고할 수 있습니다."
    : !detection
      ? "탐지된 위험이 없습니다. 위험이 감지되면 신고할 수 있습니다."
      : "";
  const reportHelpText = reportDisabledReason || reportMessage;

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

  const clearVoiceStopTimer = useCallback(() => {
    if (voiceStopTimerRef.current !== null) {
      window.clearTimeout(voiceStopTimerRef.current);
      voiceStopTimerRef.current = null;
    }
  }, []);

  const cleanupVoiceRecording = useCallback(() => {
    clearVoiceStopTimer();
    voiceStreamRef.current?.getTracks().forEach((track) => track.stop());
    voiceStreamRef.current = null;
    mediaRecorderRef.current = null;
  }, [clearVoiceStopTimer]);

  const startCamera = useCallback(async () => {
    if (!navigator.mediaDevices?.getUserMedia) {
      setCameraError("이 브라우저는 카메라 입력을 지원하지 않습니다.");
      setDetection(null);
      setReportMessage("카메라 준비 필요");
      return;
    }

    try {
      streamRef.current?.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
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

  const reconnectCameraAndSensors = useCallback(() => {
    void startCamera();
    startGpsWatch();
    void requestHeadingPermission();
  }, [requestHeadingPermission, startCamera, startGpsWatch]);

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
    return () => cleanupVoiceRecording();
  }, [cleanupVoiceRecording]);

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
        setHeading(Math.round(event.alpha));
        setHeadingMessage("방향 센서 수신 중");
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
      setDetectorMessage(`${CLASS_LABELS[nextDetection.class_name]} 데모 감지`);
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

    lastStatusMessageRef.current = `현재 위험. ${CLASS_LABELS[detection.class_name]}. 신뢰도 ${formatPercent(detection.confidence)}.`;
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
    if (!detection) {
      lastStatusMessageRef.current = `탐지 상태. ${detectorMessage}.`;
    }
  }, [detection, detectorMessage]);

  useEffect(() => {
    reportStateRef.current = reportState;
    lastStatusMessageRef.current = `신고 상태. ${reportMessage}.`;
  }, [reportMessage, reportState]);

  useEffect(() => {
    if (gps || gpsError) {
      lastStatusMessageRef.current = getCurrentLocationMessage();
    }
  }, [getCurrentLocationMessage, gps, gpsError]);

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

  useEffect(() => {
    if (DETECTOR_MODE !== "server" || !cameraReady) {
      return;
    }

    let stopped = false;
    let inFlight = false;
    let intervalId: number | null = null;

    const setIdleReportMessage = (message: string) => {
      if (reportStateRef.current !== "sending") {
        setReportState("idle");
        setLastDuplicateCount(0);
        setReportMessage(message);
      }
    };

    const clearServerDetection = (message: string) => {
      setDetection(null);
      setDetectorMessage(message);
      setIdleReportMessage(message);
    };

    const runServerDetection = async () => {
      if (inFlight) {
        return;
      }

      inFlight = true;
      setDetectorBusy(true);
      try {
        const health = await fetchDetectHealth();
        if (stopped) {
          return;
        }
        if (health.model_status !== "ready") {
          clearServerDetection(health.reason ? `서버 모델 미준비: ${health.reason}` : "서버 모델 미준비");
          return;
        }

        const capturedAt = new Date().toISOString();
        const image = await captureFrame();
        const result = await detectFrame(image, { captured_at: capturedAt, gps, heading });
        if (stopped) {
          return;
        }

        const nextDetection = result.detections.reduce<DetectionEvent | null>(
          (bestDetection, currentDetection) => (!bestDetection || currentDetection.confidence > bestDetection.confidence ? currentDetection : bestDetection),
          null
        );
        setDetection(nextDetection);

        if (nextDetection) {
          const message = `${CLASS_LABELS[nextDetection.class_name]} 서버 감지`;
          setDetectorMessage(message);
          setIdleReportMessage(`${CLASS_LABELS[nextDetection.class_name]} 신고 가능`);
        } else {
          const message = `서버 연결됨 · 위험 없음 (${result.model_version})`;
          setDetectorMessage(message);
          setIdleReportMessage("서버 탐지 결과 없음");
        }
      } catch (error) {
        if (stopped) {
          return;
        }
        clearServerDetection(error instanceof Error ? error.message : "서버 탐지 실패");
      } finally {
        inFlight = false;
        if (!stopped) {
          setDetectorBusy(false);
        }
      }
    };

    void runServerDetection();
    intervalId = window.setInterval(() => {
      void runServerDetection();
    }, SERVER_DETECT_INTERVAL_MS);

    return () => {
      stopped = true;
      if (intervalId !== null) {
        window.clearInterval(intervalId);
      }
    };
  }, [cameraReady, captureFrame, gps, heading]);

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

  const handleVoiceIntent = useCallback(
    async (result: VoiceSttResponse) => {
      const confidence = speechConfidence(result);
      const intent = result.intent;

      if (confidence < VOICE_INTENT_CONFIDENCE_THRESHOLD || intent === "unknown") {
        const message = intent === "unknown" ? "명령을 이해하지 못했습니다." : "명령 신뢰도가 낮습니다.";
        setVoiceMessage(`${message} 다시 말씀해 주세요.`);
        vibrate([120, 80, 120]);
        if (speechEnabled) {
          speak("다시 말씀해 주세요.");
        }
        return;
      }

      if (intent === "create_report") {
        if (reportStateRef.current === "sending") {
          setVoiceMessage("이미 신고 전송 중입니다.");
          vibrate(80);
          return;
        }
        setVoiceMessage("음성 명령: 현재 위험 신고");
        await handleReport();
        return;
      }

      if (intent === "voice_on") {
        setSpeechEnabled(true);
        setVoiceMessage("음성 안내 켜짐");
        vibrate(60);
        speak("음성 안내 켜짐");
        return;
      }

      if (intent === "voice_off") {
        setVoiceMessage("음성 안내 꺼짐");
        vibrate([80, 60, 80]);
        speak("음성 안내 꺼짐");
        setSpeechEnabled(false);
        return;
      }

      if (intent === "repeat_last") {
        const message = lastStatusMessageRef.current ?? "반복할 상태가 없습니다.";
        setVoiceMessage("최근 상태 반복");
        if (message === "반복할 상태가 없습니다.") {
          vibrate([120, 80, 120]);
        }
        if (speechEnabled) {
          speak(message);
        }
        return;
      }

      if (intent === "get_current_location") {
        const message = getCurrentLocationMessage();
        lastStatusMessageRef.current = message;
        setVoiceMessage(gps ? "현재 위치 확인 완료" : "현재 위치 확인 대기");
        vibrate(80);
        if (speechEnabled) {
          speak(message);
        }
        return;
      }

      if (intent === "set_destination") {
        const nextDestination = destinationFromSlots(result.slots);
        if (nextDestination) {
          setDestination(nextDestination);
          setNavigationActive(false);
        }
        const message = nextDestination ? `${nextDestination} 목적지 저장` : "목적지를 다시 말씀해 주세요.";
        setVoiceMessage(message);
        vibrate(70);
        if (speechEnabled) {
          speak(nextDestination ? `${nextDestination} 목적지를 저장했습니다.` : "목적지를 다시 말씀해 주세요.");
        }
        return;
      }

      if (intent === "start_navigation") {
        if (!destination) {
          setVoiceMessage("목적지를 먼저 말씀해 주세요.");
          vibrate([120, 80, 120]);
          if (speechEnabled) {
            speak("목적지를 먼저 말씀해 주세요.");
          }
          return;
        }

        setNavigationActive(true);
        setVoiceMessage(`${destination} 안내 시작 준비`);
        vibrate([70, 50, 120]);
        if (speechEnabled) {
          speak("길 안내를 시작합니다.");
        }
      }
    },
    [destination, getCurrentLocationMessage, gps, handleReport, speechEnabled]
  );

  const handleVoiceRecordingStop = useCallback(async () => {
    clearVoiceStopTimer();
    voiceStreamRef.current?.getTracks().forEach((track) => track.stop());
    voiceStreamRef.current = null;
    mediaRecorderRef.current = null;

    const mimeType = voiceChunksRef.current[0]?.type || "audio/webm";
    const audio = new Blob(voiceChunksRef.current, { type: mimeType });
    voiceChunksRef.current = [];

    if (audio.size === 0) {
      setVoiceState("error");
      setVoiceMessage("녹음이 비어 있습니다. 다시 말씀해 주세요.");
      vibrate([120, 80, 120]);
      return;
    }

    setVoiceState("uploading");
    setVoiceMessage("음성 명령 분석 중");
    setVoiceTranscript(null);
    setVoiceIntent(null);
    setVoiceConfidence(null);

    try {
      const result = await uploadSpeechStt(audio);
      const confidence = speechConfidence(result);
      setVoiceTranscript(result.transcript || "인식 문장 없음");
      setVoiceIntent(result.intent);
      setVoiceConfidence(confidence);
      await handleVoiceIntent(result);
      setVoiceState("idle");
    } catch (error) {
      setVoiceState("error");
      setVoiceMessage(error instanceof Error ? error.message : "음성 명령 처리에 실패했습니다.");
      vibrate([240, 120, 240]);
    }
  }, [clearVoiceStopTimer, handleVoiceIntent]);

  const stopVoiceRecording = useCallback(() => {
    const recorder = mediaRecorderRef.current;
    if (!recorder || recorder.state === "inactive") {
      cleanupVoiceRecording();
      setVoiceState("idle");
      return;
    }

    clearVoiceStopTimer();
    recorder.stop();
  }, [cleanupVoiceRecording, clearVoiceStopTimer]);

  const startVoiceRecording = useCallback(async () => {
    if (
      typeof navigator === "undefined" ||
      !navigator.mediaDevices ||
      typeof navigator.mediaDevices.getUserMedia !== "function" ||
      typeof MediaRecorder === "undefined"
    ) {
      setVoiceSupported(false);
      setVoiceState("error");
      setVoiceMessage("이 브라우저는 음성 녹음을 지원하지 않습니다.");
      return;
    }

    setVoiceState("recording");
    setVoiceMessage("말씀하세요");
    setVoiceTranscript(null);
    setVoiceIntent(null);
    setVoiceConfidence(null);
    voiceChunksRef.current = [];

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true
        },
        video: false
      });
      const mimeType = preferredAudioMimeType();
      const recorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream);

      voiceStreamRef.current = stream;
      mediaRecorderRef.current = recorder;
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          voiceChunksRef.current.push(event.data);
        }
      };
      recorder.onerror = () => {
        voiceChunksRef.current = [];
        setVoiceState("error");
        setVoiceMessage("녹음 중 오류가 발생했습니다.");
        cleanupVoiceRecording();
        vibrate([160, 90, 160]);
      };
      recorder.onstop = () => {
        void handleVoiceRecordingStop();
      };

      recorder.start();
      voiceStopTimerRef.current = window.setTimeout(() => {
        if (mediaRecorderRef.current?.state === "recording") {
          mediaRecorderRef.current.stop();
        }
      }, VOICE_RECORDING_MAX_MS);
    } catch (error) {
      cleanupVoiceRecording();
      setVoiceState("error");
      setVoiceMessage(error instanceof DOMException && error.name === "NotAllowedError" ? "마이크 권한이 필요합니다." : "마이크를 시작할 수 없습니다.");
      vibrate([160, 90, 160]);
    }
  }, [cleanupVoiceRecording, handleVoiceRecordingStop]);

  const handleVoiceCommandButton = useCallback(() => {
    if (voiceState === "recording") {
      stopVoiceRecording();
      return;
    }
    if (voiceState === "uploading" || !voiceSupported) {
      return;
    }

    void startVoiceRecording();
  }, [startVoiceRecording, stopVoiceRecording, voiceState, voiceSupported]);

  const reportButtonLabel = useMemo(() => {
    if (reportState === "sending") {
      return "전송 중";
    }
    if (reportState === "error") {
      return "다시 신고";
    }
    return "현재 위험 신고";
  }, [reportState]);

  const voiceButtonLabel = useMemo(() => {
    if (!voiceSupported) {
      return "음성 명령 미지원";
    }
    if (voiceState === "recording") {
      return "녹음 종료";
    }
    if (voiceState === "uploading") {
      return "분석 중";
    }
    if (voiceState === "error") {
      return "다시 말하기";
    }
    return "음성 명령";
  }, [voiceState, voiceSupported]);

  const voiceButtonHelp = useMemo(() => {
    if (!voiceSupported) {
      return "MediaRecorder 지원 브라우저가 필요합니다";
    }
    if (voiceState === "recording") {
      return "짧게 말한 뒤 다시 누르세요";
    }
    if (voiceState === "uploading") {
      return "STT 서버로 전송 중";
    }
    return "탭해서 한국어 명령을 녹음합니다";
  }, [voiceState, voiceSupported]);

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
            <button className="control-button secondary" type="button" onClick={reconnectCameraAndSensors}>
              <Camera aria-hidden="true" size={20} />
              카메라/센서 다시 연결
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
          <span>{detection ? "전방 확인" : detectorStatusText}</span>
        </div>
      </section>

      <section className="assist-panel" aria-label="보행 보조 상태와 신고">
        <div className="status-grid">
          <div className={`status-item current-risk ${detection ? "warning" : "safe"}`} aria-live="polite">
            <span className="status-label">현재 위험</span>
            <strong>{detectionLabel}</strong>
            <small>{detection ? `신뢰도 ${formatPercent(detection.confidence)}` : detectorStatusText}</small>
          </div>
          <div className="status-item">
            <span className="status-label">위치</span>
            <strong>{gpsError ?? formatGps(gps)}</strong>
            <small>{gps?.accuracy_m === null || gps?.accuracy_m === undefined ? "정확도 대기" : `정확도 ${gps.accuracy_m.toFixed(1)}m`}</small>
          </div>
          <div className="status-item">
            <span className="status-label">방향</span>
            <strong>{directionLabel}</strong>
            <small>{heading === null ? headingMessage : `${heading}도`}</small>
          </div>
          <div className="status-item" aria-live="polite">
            <span className="status-label">신고</span>
            <strong>{reportMessage}</strong>
            {lastDuplicateCount > 0 ? <small>중복 후보 {lastDuplicateCount}건</small> : null}
          </div>
          <div className="status-item" aria-live="polite">
            <span className="status-label">음성 명령</span>
            <strong>{voiceMessage}</strong>
            <small>{voiceResultText}</small>
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
          className="voice-button"
          type="button"
          aria-pressed={voiceState === "recording"}
          aria-label={`${voiceButtonLabel}. ${voiceButtonHelp}`}
          onClick={handleVoiceCommandButton}
          disabled={!voiceSupported || voiceState === "uploading"}
        >
          <span>
            {voiceState === "uploading" ? <Loader2 className="spin" aria-hidden="true" size={22} /> : <Mic aria-hidden="true" size={22} />}
            {voiceButtonLabel}
          </span>
          <small>{voiceButtonHelp}</small>
        </button>

        <button
          className="report-button"
          type="button"
          onClick={handleReport}
          disabled={!canReport}
          aria-describedby="report-button-help"
          aria-label={reportDisabledReason || "현재 탐지된 위험을 신고합니다."}
        >
          {reportState === "sending" ? (
            <Loader2 className="spin" aria-hidden="true" size={24} />
          ) : (
            <Send aria-hidden="true" size={24} />
          )}
          <span className="button-stack">
            <span>{reportButtonLabel}</span>
            <small id="report-button-help">{reportHelpText}</small>
          </span>
        </button>

        <button className="control-button secondary sensor-action" type="button" onClick={reconnectCameraAndSensors}>
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
