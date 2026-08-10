/**
 * Owns MediaRecorder lifecycle, STT upload and the side effects for accepted voice intents.
 * Low-confidence, unknown or explicitly rejected intents are reprompted without executing commands.
 */
import { useCallback, useEffect, useMemo, useRef, useState, type Dispatch, type SetStateAction } from "react";
import {
  speechConfidence,
  uploadSpeechStt,
  voiceExecutionAllowed,
  type VoiceIntent,
  type VoiceSttResponse
} from "@/lib/voice-api";
import { VOICE_INTENT_CONFIDENCE_THRESHOLD, VOICE_RECORDING_MAX_MS } from "../config";
import {
  getActiveSpeechPriority,
  setSpeechRecognitionActive,
  speak,
  stopSpeaking,
  vibrate,
  WALKSAFE_URGENT_SPEECH_EVENT
} from "../feedback";
import { executeNavigationVoiceIntent, type VoiceActionResult } from "../voice-intent-executor";
import { formatPercent, preferredAudioMimeType } from "../utils";

export type VoiceRecordState = "idle" | "recording" | "uploading" | "error";

export function shouldCancelVoiceSessionForVisibility(
  visibilityState: DocumentVisibilityState,
  voiceState: VoiceRecordState
): boolean {
  return visibilityState !== "visible" && (voiceState === "recording" || voiceState === "uploading");
}

export function shouldBlockVoiceRecordingForSafety(safetyAlertActive: boolean): boolean {
  return safetyAlertActive;
}

type UseVoiceCommandsOptions = {
  speechEnabled: boolean;
  setSpeechEnabled: Dispatch<SetStateAction<boolean>>;
  setVoiceMessage: (message: string) => void;
  isReportSending: () => boolean;
  onCreateReport: () => Promise<void>;
  onSetDestination?: (destination: string) => void | VoiceActionResult | Promise<void | VoiceActionResult>;
  onCancelDestination: () => VoiceActionResult;
  onSelectDestinationCandidateByIndex?: (candidateIndex: number) => VoiceActionResult | Promise<VoiceActionResult>;
  onStartNavigation?: () => Promise<VoiceActionResult>;
  onStopNavigation?: () => void | VoiceActionResult;
  onGetNextNavigationInstruction?: () => VoiceActionResult;
  getLastStatusMessage: () => string | null;
  setLastStatusMessage: (message: string) => void;
  getCurrentLocationMessage: () => string;
  hasGps: boolean;
  hasNavigationDestination?: boolean;
  safetyAlertActive?: boolean;
};

export function useVoiceCommands({
  speechEnabled,
  setSpeechEnabled,
  setVoiceMessage,
  isReportSending,
  onCreateReport,
  onSetDestination,
  onCancelDestination,
  onSelectDestinationCandidateByIndex,
  onStartNavigation,
  onStopNavigation,
  onGetNextNavigationInstruction,
  getLastStatusMessage,
  setLastStatusMessage,
  getCurrentLocationMessage,
  hasGps,
  hasNavigationDestination = false,
  safetyAlertActive = false
}: UseVoiceCommandsOptions) {
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const voiceChunksRef = useRef<Blob[]>([]);
  const voiceStreamRef = useRef<MediaStream | null>(null);
  const voiceStopTimerRef = useRef<number | null>(null);
  const voiceSessionSequenceRef = useRef(0);
  const voiceUploadAbortRef = useRef<AbortController | null>(null);

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

  const cancelVoiceRecording = useCallback(
    (message: string) => {
      setSpeechRecognitionActive(false);
      voiceSessionSequenceRef.current += 1;
      voiceUploadAbortRef.current?.abort();
      voiceUploadAbortRef.current = null;
      const recorder = mediaRecorderRef.current;
      clearVoiceStopTimer();
      voiceChunksRef.current = [];
      if (recorder && recorder.state !== "inactive") {
        recorder.ondataavailable = null;
        recorder.onstop = null;
        recorder.onerror = null;
        recorder.stop();
      }
      cleanupVoiceRecording();
      setVoiceState("error");
      setVoiceMessage(message);
      vibrate([120, 80, 120]);
    },
    [cleanupVoiceRecording, clearVoiceStopTimer, setVoiceMessage]
  );

  const stopVoiceSession = useCallback(() => {
    voiceSessionSequenceRef.current += 1;
    voiceUploadAbortRef.current?.abort();
    voiceUploadAbortRef.current = null;
    setSpeechRecognitionActive(false);
    stopSpeaking();
    const recorder = mediaRecorderRef.current;
    if (recorder && recorder.state !== "inactive") {
      recorder.ondataavailable = null;
      recorder.onstop = null;
      recorder.onerror = null;
      recorder.stop();
    }
    voiceChunksRef.current = [];
    cleanupVoiceRecording();
    setVoiceState("idle");
  }, [cleanupVoiceRecording]);

  useEffect(() => {
    const interruptForRisk = () => {
      if (voiceState === "recording" || voiceState === "uploading") {
        cancelVoiceRecording("위험 안내를 우선하여 음성 명령을 취소했습니다. 안전을 확인한 뒤 다시 말씀해 주세요.");
      }
    };
    window.addEventListener(WALKSAFE_URGENT_SPEECH_EVENT, interruptForRisk);
    return () => window.removeEventListener(WALKSAFE_URGENT_SPEECH_EVENT, interruptForRisk);
  }, [cancelVoiceRecording, voiceState]);

  useEffect(() => {
    if (!safetyAlertActive || (voiceState !== "recording" && voiceState !== "uploading")) {
      return;
    }
    const timer = window.setTimeout(() => {
      cancelVoiceRecording("위험 안내를 우선하여 음성 명령을 취소했습니다. 안전을 확인한 뒤 다시 말씀해 주세요.");
    }, 0);
    return () => window.clearTimeout(timer);
  }, [cancelVoiceRecording, safetyAlertActive, voiceState]);

  useEffect(() => {
    const onVisibilityChange = () => {
      if (shouldCancelVoiceSessionForVisibility(document.visibilityState, voiceState)) {
        cancelVoiceRecording("앱이 백그라운드로 전환되어 음성 명령을 취소했습니다. 다시 말씀해 주세요.");
      }
      if (document.visibilityState !== "visible") {
        stopSpeaking();
      }
    };
    const onPageHide = () => {
      if (voiceState === "recording" || voiceState === "uploading") {
        cancelVoiceRecording("화면을 벗어나 음성 명령을 취소했습니다.");
      }
      stopSpeaking();
    };

    document.addEventListener("visibilitychange", onVisibilityChange);
    window.addEventListener("pagehide", onPageHide);
    return () => {
      document.removeEventListener("visibilitychange", onVisibilityChange);
      window.removeEventListener("pagehide", onPageHide);
    };
  }, [cancelVoiceRecording, voiceState]);

  useEffect(() => {
    return () => {
      voiceSessionSequenceRef.current += 1;
      voiceUploadAbortRef.current?.abort();
      voiceUploadAbortRef.current = null;
      setSpeechRecognitionActive(false);
      stopSpeaking();
      const recorder = mediaRecorderRef.current;
      if (recorder && recorder.state !== "inactive") {
        recorder.ondataavailable = null;
        recorder.onstop = null;
        recorder.onerror = null;
        recorder.stop();
      }
      voiceChunksRef.current = [];
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

      if (
        !voiceExecutionAllowed(result) ||
        confidence < VOICE_INTENT_CONFIDENCE_THRESHOLD
      ) {
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

      const handled = await executeNavigationVoiceIntent(result, {
        destination,
        hasNavigationDestination,
        speechEnabled,
        setDestination,
        setNavigationActive,
        setVoiceMessage,
        setLastStatusMessage,
        onSetDestination,
        onCancelDestination,
        onSelectDestinationCandidateByIndex,
        onStartNavigation,
        onStopNavigation,
        onGetNextNavigationInstruction,
        speak,
        vibrate
      });
      if (!handled) {
        const message = "지원하지 않는 음성 명령입니다. 다시 말씀해 주세요.";
        setVoiceMessage(message);
        vibrate([120, 80, 120]);
        if (speechEnabled) speak(message);
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
      onCancelDestination,
      onGetNextNavigationInstruction,
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

  const handleVoiceRecordingStop = useCallback(async (sessionSequence: number) => {
    clearVoiceStopTimer();
    voiceStreamRef.current?.getTracks().forEach((track) => track.stop());
    voiceStreamRef.current = null;
    mediaRecorderRef.current = null;

    const mimeType = voiceChunksRef.current[0]?.type || "audio/webm";
    const audio = new Blob(voiceChunksRef.current, { type: mimeType });
    voiceChunksRef.current = [];

    if (voiceSessionSequenceRef.current !== sessionSequence || document.visibilityState !== "visible") {
      setSpeechRecognitionActive(false);
      return;
    }

    if (audio.size === 0) {
      setVoiceState("error");
      setVoiceMessage("녹음이 비어 있습니다. 다시 말씀해 주세요.");
      setSpeechRecognitionActive(false);
      vibrate([120, 80, 120]);
      return;
    }

    setVoiceState("uploading");
    setVoiceMessage("음성 명령 분석 중");
    setVoiceTranscript(null);
    setVoiceIntent(null);
    setVoiceConfidence(null);

    const uploadAbortController = new AbortController();
    voiceUploadAbortRef.current?.abort();
    voiceUploadAbortRef.current = uploadAbortController;
    try {
      const result = await uploadSpeechStt(audio, uploadAbortController.signal);
      if (
        voiceSessionSequenceRef.current !== sessionSequence ||
        uploadAbortController.signal.aborted ||
        document.visibilityState !== "visible"
      ) {
        return;
      }
      const confidence = speechConfidence(result);
      setVoiceTranscript(result.transcript || "인식 문장 없음");
      setVoiceIntent(result.intent);
      setVoiceConfidence(confidence);
      setSpeechRecognitionActive(false);
      await handleVoiceIntent(result);
      if (voiceSessionSequenceRef.current === sessionSequence && !uploadAbortController.signal.aborted) {
        setVoiceState("idle");
      }
    } catch (error) {
      if (voiceSessionSequenceRef.current !== sessionSequence || uploadAbortController.signal.aborted) {
        return;
      }
      setVoiceState("error");
      setVoiceMessage(error instanceof Error ? error.message : "음성 명령 처리에 실패했습니다.");
      vibrate([240, 120, 240]);
    } finally {
      setSpeechRecognitionActive(false);
      if (voiceUploadAbortRef.current === uploadAbortController) {
        voiceUploadAbortRef.current = null;
      }
    }
  }, [clearVoiceStopTimer, handleVoiceIntent, setVoiceMessage]);

  const stopVoiceRecording = useCallback(() => {
    const recorder = mediaRecorderRef.current;
    if (!recorder || recorder.state === "inactive") {
      voiceSessionSequenceRef.current += 1;
      setSpeechRecognitionActive(false);
      cleanupVoiceRecording();
      setVoiceState("idle");
      return;
    }

    clearVoiceStopTimer();
    recorder.stop();
  }, [cleanupVoiceRecording, clearVoiceStopTimer]);

  const startVoiceRecording = useCallback(async () => {
    if (shouldBlockVoiceRecordingForSafety(safetyAlertActive) || getActiveSpeechPriority() === "risk") {
      setVoiceState("error");
      setVoiceMessage("현재 위험 안내가 끝난 뒤 음성 명령을 사용해 주세요.");
      vibrate([220, 90, 220]);
      return;
    }
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
    if (document.visibilityState !== "visible") {
      setVoiceState("error");
      setVoiceMessage("앱 화면이 보일 때만 음성 명령을 녹음할 수 있습니다.");
      return;
    }

    stopSpeaking();
    setSpeechRecognitionActive(true);
    const sessionSequence = voiceSessionSequenceRef.current + 1;
    voiceSessionSequenceRef.current = sessionSequence;
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
      if (voiceSessionSequenceRef.current !== sessionSequence || document.visibilityState !== "visible") {
        stream.getTracks().forEach((track) => track.stop());
        setSpeechRecognitionActive(false);
        return;
      }
      const mimeType = preferredAudioMimeType();
      const recorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream);

      voiceStreamRef.current = stream;
      mediaRecorderRef.current = recorder;
      const onAudioTrackEnded = () => {
        if (mediaRecorderRef.current?.state === "recording") {
          cancelVoiceRecording("마이크 사용이 중단되어 음성 명령을 취소했습니다. 다시 말씀해 주세요.");
        }
      };
      stream.getAudioTracks().forEach((track) => track.addEventListener("ended", onAudioTrackEnded, { once: true }));
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          voiceChunksRef.current.push(event.data);
        }
      };
      recorder.onerror = () => {
        setSpeechRecognitionActive(false);
        voiceSessionSequenceRef.current += 1;
        voiceChunksRef.current = [];
        setVoiceState("error");
        setVoiceMessage("녹음 중 오류가 발생했습니다.");
        cleanupVoiceRecording();
        vibrate([160, 90, 160]);
      };
      recorder.onstop = () => {
        void handleVoiceRecordingStop(sessionSequence);
      };

      recorder.start();
      voiceStopTimerRef.current = window.setTimeout(() => {
        if (mediaRecorderRef.current?.state === "recording") {
          mediaRecorderRef.current.stop();
        }
      }, VOICE_RECORDING_MAX_MS);
    } catch (error) {
      setSpeechRecognitionActive(false);
      cleanupVoiceRecording();
      setVoiceState("error");
      setVoiceMessage(error instanceof DOMException && error.name === "NotAllowedError" ? "마이크 권한이 필요합니다." : "마이크를 시작할 수 없습니다.");
      vibrate([160, 90, 160]);
    }
  }, [cancelVoiceRecording, cleanupVoiceRecording, handleVoiceRecordingStop, safetyAlertActive, setVoiceMessage]);

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
          : "",
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
    stopVoiceSession,
    handleSpeechToggle,
    handleVoiceCommandButton
  };
}
