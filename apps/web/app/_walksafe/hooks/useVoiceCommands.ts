import { useCallback, useEffect, useMemo, useRef, useState, type Dispatch, type SetStateAction } from "react";
import { speechConfidence, uploadSpeechStt, VOICE_API_BASE, type VoiceIntent, type VoiceSttResponse } from "@/lib/voice-api";
import { VOICE_INTENT_CONFIDENCE_THRESHOLD, VOICE_RECORDING_MAX_MS } from "../config";
import { speak, stopSpeaking, vibrate } from "../feedback";
import { destinationFromSlots, formatPercent, preferredAudioMimeType } from "../utils";

export type VoiceRecordState = "idle" | "recording" | "uploading" | "error";

type VoiceActionResult = { ok: boolean; message: string };

type UseVoiceCommandsOptions = {
  speechEnabled: boolean;
  setSpeechEnabled: Dispatch<SetStateAction<boolean>>;
  setVoiceMessage: (message: string) => void;
  isReportSending: () => boolean;
  onCreateReport: () => Promise<void>;
  onSetDestination?: (destination: string) => void | VoiceActionResult | Promise<void | VoiceActionResult>;
  onSelectDestinationCandidateByIndex?: (candidateIndex: number) => VoiceActionResult | Promise<VoiceActionResult>;
  onStartNavigation?: () => Promise<VoiceActionResult>;
  onStopNavigation?: () => void;
  getLastStatusMessage: () => string | null;
  setLastStatusMessage: (message: string) => void;
  getCurrentLocationMessage: () => string;
  hasGps: boolean;
  hasNavigationDestination?: boolean;
};

function candidateIndexFromSlots(slots: Record<string, unknown>): number | null {
  const value = slots.candidate_index ?? slots.candidateIndex ?? slots.index;
  if (typeof value === "number" && Number.isInteger(value) && value >= 1) {
    return value;
  }
  if (typeof value === "string" && /^[1-9]\d*$/.test(value.trim())) {
    return Number(value);
  }
  return null;
}

export function useVoiceCommands({
  speechEnabled,
  setSpeechEnabled,
  setVoiceMessage,
  isReportSending,
  onCreateReport,
  onSetDestination,
  onSelectDestinationCandidateByIndex,
  onStartNavigation,
  onStopNavigation,
  getLastStatusMessage,
  setLastStatusMessage,
  getCurrentLocationMessage,
  hasGps,
  hasNavigationDestination = false
}: UseVoiceCommandsOptions) {
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const voiceChunksRef = useRef<Blob[]>([]);
  const voiceStreamRef = useRef<MediaStream | null>(null);
  const voiceStopTimerRef = useRef<number | null>(null);

  const [voiceSupported, setVoiceSupported] = useState(true);
  const [voiceState, setVoiceState] = useState<VoiceRecordState>("idle");
  const [voiceTranscript, setVoiceTranscript] = useState<string | null>(null);
  const [voiceIntent, setVoiceIntent] = useState<VoiceIntent | null>(null);
  const [voiceConfidence, setVoiceConfidence] = useState<number | null>(null);
  const [destination, setDestination] = useState("");
  const [navigationActive, setNavigationActive] = useState(false);

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

  useEffect(() => {
    return () => {
      stopSpeaking();
      cleanupVoiceRecording();
    };
  }, [cleanupVoiceRecording]);

  const handleSpeechToggle = useCallback(() => {
    setSpeechEnabled((current) => {
      const next = !current;
      vibrate(60);
      speak(next ? "음성 안내 켜짐" : "음성 안내 꺼짐");
      return next;
    });
  }, [setSpeechEnabled]);

  const handleVoiceIntent = useCallback(
    async (result: VoiceSttResponse) => {
      const confidence = speechConfidence(result);
      const intent = result.intent;

      if (result.should_execute === false || confidence < VOICE_INTENT_CONFIDENCE_THRESHOLD || intent === "unknown") {
        const message = result.prompt ?? (intent === "unknown" ? "명령을 이해하지 못했습니다. 다시 말씀해 주세요." : "명령 신뢰도가 낮습니다. 다시 말씀해 주세요.");
        setVoiceMessage(message);
        vibrate([120, 80, 120]);
        if (speechEnabled) {
          speak(message);
        }
        return;
      }

      if (intent === "create_report") {
        if (isReportSending()) {
          setVoiceMessage("이미 신고 전송 중입니다.");
          vibrate(80);
          return;
        }
        await onCreateReport();
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
        const message = getLastStatusMessage() ?? "반복할 상태가 없습니다.";
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
        setLastStatusMessage(message);
        setVoiceMessage(hasGps ? "현재 위치 확인 완료" : "현재 위치 확인 대기");
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
          const destinationResult = await onSetDestination?.(nextDestination);
          if (destinationResult) {
            setVoiceMessage(destinationResult.message);
            vibrate(destinationResult.ok ? 70 : [120, 80, 120]);
            if (speechEnabled) {
              speak(destinationResult.message);
            }
            return;
          }
        }
        const message = nextDestination ? `${nextDestination} 목적지 저장` : "목적지를 다시 말씀해 주세요.";
        setVoiceMessage(message);
        vibrate(70);
        if (speechEnabled) {
          speak(nextDestination ? `${nextDestination} 목적지를 저장했습니다.` : "목적지를 다시 말씀해 주세요.");
        }
        return;
      }

      if (intent === "select_destination_candidate") {
        const candidateIndex = candidateIndexFromSlots(result.slots);
        if (candidateIndex === null) {
          const message = "몇 번째 목적지를 선택할지 다시 말씀해 주세요.";
          setVoiceMessage(message);
          vibrate([120, 80, 120]);
          if (speechEnabled) {
            speak(message);
          }
          return;
        }

        if (!onSelectDestinationCandidateByIndex) {
          const message = "선택할 목적지 후보가 없습니다. 목적지를 먼저 말씀해 주세요.";
          setVoiceMessage(message);
          vibrate([120, 80, 120]);
          if (speechEnabled) {
            speak(message);
          }
          return;
        }

        const selectionResult = await onSelectDestinationCandidateByIndex(candidateIndex);
        setNavigationActive(false);
        setVoiceMessage(selectionResult.message);
        vibrate(selectionResult.ok ? 70 : [120, 80, 120]);
        if (speechEnabled) {
          speak(selectionResult.message);
        }
        return;
      }

      if (intent === "stop_navigation") {
        onStopNavigation?.();
        setNavigationActive(false);
        const message = "길안내를 중지했습니다.";
        setVoiceMessage(message);
        vibrate([80, 50, 80]);
        if (speechEnabled) {
          speak(message);
        }
        return;
      }

      if (intent === "start_navigation" || intent === "reroute_navigation") {
        if (!destination && !hasNavigationDestination) {
          setVoiceMessage("목적지를 먼저 말씀해 주세요.");
          vibrate([120, 80, 120]);
          if (speechEnabled) {
            speak("목적지를 먼저 말씀해 주세요.");
          }
          return;
        }

        if (onStartNavigation) {
          const result = await onStartNavigation();
          setNavigationActive(result.ok);
          setVoiceMessage(result.message);
          vibrate(result.ok ? [70, 50, 120] : [120, 80, 120]);
          if (speechEnabled && !result.ok) {
            speak(result.message);
          }
          return;
        }

        setNavigationActive(true);
        setVoiceMessage(intent === "reroute_navigation" ? "재탐색 준비" : `${destination} 안내 시작 준비`);
        vibrate([70, 50, 120]);
        if (speechEnabled) {
          speak(intent === "reroute_navigation" ? "경로를 다시 확인합니다." : "길 안내를 시작합니다.");
        }
      }
    },
    [
      destination,
      getCurrentLocationMessage,
      getLastStatusMessage,
      hasNavigationDestination,
      hasGps,
      isReportSending,
      onCreateReport,
      onSelectDestinationCandidateByIndex,
      onSetDestination,
      onStartNavigation,
      onStopNavigation,
      setLastStatusMessage,
      setSpeechEnabled,
      setVoiceMessage,
      speechEnabled
    ]
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
  }, [clearVoiceStopTimer, handleVoiceIntent, setVoiceMessage]);

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

    stopSpeaking();
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
  }, [cleanupVoiceRecording, handleVoiceRecordingStop, setVoiceMessage]);

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

  const voiceResultText = useMemo(
    () =>
      voiceTranscript
        ? `${voiceTranscript} · ${voiceIntent ?? "unknown"}${voiceConfidence === null ? "" : ` ${formatPercent(voiceConfidence)}`}`
        : destination
          ? `${navigationActive ? "안내 준비" : "목적지 저장"} · ${destination}`
          : `서버 ${VOICE_API_BASE}`,
    [destination, navigationActive, voiceConfidence, voiceIntent, voiceTranscript]
  );

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

  return {
    voiceSupported,
    voiceState,
    voiceResultText,
    voiceButtonLabel,
    voiceButtonHelp,
    handleSpeechToggle,
    handleVoiceCommandButton
  };
}
