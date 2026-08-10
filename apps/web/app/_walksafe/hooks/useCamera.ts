/**
 * Owns the camera stream and produces bounded JPEG snapshots for inference and reporting.
 * Rear-camera constraints are preferred, with a generic-camera retry only for constraint failures.
 */
import { useCallback, useEffect, useRef, useState } from "react";
import {
  CameraFrameUnavailableError,
  isFreshCameraFrameObservation,
  isLiveRearCameraTrack,
  type CameraFrameObservation
} from "../camera-policy";

type UseCameraOptions = {
  onCameraUnavailable?: () => void;
  onCameraError?: () => void;
};

const CAPTURE_MAX_EDGE_PX = 960;
const CAPTURE_JPEG_QUALITY = 0.78;
const FRESH_FRAME_TIMEOUT_MS = 800;

function cameraPermissionMessage(error: unknown): string {
  const suffix = error instanceof DOMException ? ` (${error.name})` : "";
  if (error instanceof DOMException) {
    if (error.name === "NotAllowedError" || error.name === "SecurityError") {
      return `카메라 권한 요청이 브라우저에서 거부됐습니다${suffix}. 주소창 사이트 설정에서 카메라를 허용한 뒤 다시 눌러주세요.`;
    }
    if (error.name === "NotFoundError" || error.name === "DevicesNotFoundError") {
      return `사용 가능한 카메라를 찾지 못했습니다${suffix}.`;
    }
    if (error.name === "NotReadableError" || error.name === "TrackStartError") {
      return `다른 앱이 카메라를 사용 중일 수 있습니다${suffix}. 카메라 앱을 닫고 다시 시도해 주세요.`;
    }
    if (error.name === "OverconstrainedError" || error.name === "ConstraintNotSatisfiedError") {
      return `후면 카메라 조건을 맞추지 못했습니다${suffix}. 기본 카메라로 다시 시도해 주세요.`;
    }
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return `카메라 권한을 허용해야 보행 화면을 사용할 수 있습니다${suffix}.`;
}

async function requestCameraStream(): Promise<MediaStream> {
  return navigator.mediaDevices.getUserMedia({
    audio: false,
    video: {
      facingMode: { exact: "environment" },
      width: { ideal: 1280 },
      height: { ideal: 720 }
    }
  });
}

export function useCamera({ onCameraUnavailable, onCameraError }: UseCameraOptions = {}) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const cameraGenerationRef = useRef(0);
  const lastCapturedFrameRef = useRef<CameraFrameObservation | null>(null);
  const [cameraReady, setCameraReady] = useState(false);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const [cameraPermissionState, setCameraPermissionState] = useState<PermissionState | "unsupported" | "unknown">("unknown");

  const stopCamera = useCallback(() => {
    cameraGenerationRef.current += 1;
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    lastCapturedFrameRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
    setCameraReady(false);
  }, []);

  const refreshCameraPermissionState = useCallback(async () => {
    if (!navigator.permissions?.query) {
      setCameraPermissionState("unsupported");
      return;
    }

    try {
      const status = await navigator.permissions.query({ name: "camera" as PermissionName });
      setCameraPermissionState(status.state);
    } catch {
      setCameraPermissionState("unknown");
    }
  }, []);

  const startCamera = useCallback(async (): Promise<boolean> => {
    if (!navigator.mediaDevices?.getUserMedia) {
      setCameraReady(false);
      setCameraError("이 브라우저는 카메라 입력을 지원하지 않습니다. Safari 또는 Chrome에서 다시 열어주세요.");
      onCameraUnavailable?.();
      return false;
    }

    setCameraReady(false);
    setCameraError("카메라 권한 요청 중입니다. 브라우저 권한 팝업에서 허용을 눌러주세요.");

    stopCamera();
    const requestGeneration = cameraGenerationRef.current;
    let requestedStream: MediaStream | null = null;
    try {
      const stream = await requestCameraStream();
      requestedStream = stream;
      if (cameraGenerationRef.current !== requestGeneration) {
        stream.getTracks().forEach((track) => track.stop());
        return false;
      }

      const videoTracks = stream.getVideoTracks();
      const rearTrack = videoTracks.length === 1 ? videoTracks[0] : null;
      if (!rearTrack || !isLiveRearCameraTrack(rearTrack.readyState, rearTrack.getSettings().facingMode)) {
        stream.getTracks().forEach((track) => track.stop());
        throw new Error("보행 안전을 위해 활성 후면 카메라가 필요합니다. 기기의 후면 카메라를 허용해 주세요.");
      }

      streamRef.current = stream;
      const video = videoRef.current;
      if (!video) {
        streamRef.current = null;
        stream.getTracks().forEach((track) => track.stop());
        return false;
      }
      video.srcObject = stream;
      video.muted = true;
      video.playsInline = true;
      rearTrack.addEventListener(
        "ended",
        () => {
          if (cameraGenerationRef.current !== requestGeneration || streamRef.current !== stream) {
            return;
          }
          cameraGenerationRef.current += 1;
          stream.getTracks().forEach((track) => track.stop());
          streamRef.current = null;
          if (videoRef.current?.srcObject === stream) {
            videoRef.current.srcObject = null;
          }
          setCameraReady(false);
          setCameraError("후면 카메라 연결이 중단됐습니다. 카메라를 다시 연결해 주세요.");
          onCameraError?.();
        },
        { once: true }
      );
      await video.play();
      if (
        cameraGenerationRef.current !== requestGeneration ||
        streamRef.current !== stream ||
        videoRef.current !== video ||
        video.srcObject !== stream
      ) {
        if (video.srcObject === stream) {
          video.srcObject = null;
        }
        stream.getTracks().forEach((track) => track.stop());
        return false;
      }
      setCameraReady(true);
      setCameraError(null);
      setCameraPermissionState("granted");
      void refreshCameraPermissionState();
      return true;
    } catch (error) {
      if (cameraGenerationRef.current !== requestGeneration) {
        requestedStream?.getTracks().forEach((track) => track.stop());
        return false;
      }
      stopCamera();
      setCameraReady(false);
      setCameraError(cameraPermissionMessage(error));
      void refreshCameraPermissionState();
      onCameraError?.();
      return false;
    }
  }, [onCameraError, onCameraUnavailable, refreshCameraPermissionState, stopCamera]);

  const captureFrame = useCallback(async () => {
    const video = videoRef.current;
    if (!video || video.videoWidth === 0 || video.videoHeight === 0) {
      throw new Error("카메라 프레임을 아직 캡처할 수 없습니다.");
    }

    const generation = cameraGenerationRef.current;
    const observation = await new Promise<CameraFrameObservation>((resolve, reject) => {
      if (typeof video.requestVideoFrameCallback !== "function") {
        resolve({ generation, mediaTime: video.currentTime, presentedFrames: null });
        return;
      }

      let callbackId: number | null = null;
      const timeoutId = window.setTimeout(() => {
        if (callbackId !== null && typeof video.cancelVideoFrameCallback === "function") {
          video.cancelVideoFrameCallback(callbackId);
        }
        reject(new CameraFrameUnavailableError("카메라 영상이 갱신되지 않아 탐지를 일시 중지했습니다."));
      }, FRESH_FRAME_TIMEOUT_MS);
      callbackId = video.requestVideoFrameCallback((_now, metadata) => {
        window.clearTimeout(timeoutId);
        resolve({
          generation,
          mediaTime: metadata.mediaTime,
          presentedFrames: metadata.presentedFrames
        });
      });
    });

    if (
      generation !== cameraGenerationRef.current ||
      videoRef.current !== video ||
      !isFreshCameraFrameObservation(lastCapturedFrameRef.current, observation)
    ) {
      throw new CameraFrameUnavailableError("동일하거나 오래된 카메라 프레임은 새 탐지로 사용하지 않습니다.");
    }
    lastCapturedFrameRef.current = observation;

    const scale = Math.min(1, CAPTURE_MAX_EDGE_PX / Math.max(video.videoWidth, video.videoHeight));
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(video.videoWidth * scale));
    canvas.height = Math.max(1, Math.round(video.videoHeight * scale));
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
        CAPTURE_JPEG_QUALITY
      );
    });
  }, []);

  useEffect(() => {
    const permissionTimer = window.setTimeout(() => {
      void refreshCameraPermissionState();
    }, 0);

    return () => {
      window.clearTimeout(permissionTimer);
      stopCamera();
    };
  }, [refreshCameraPermissionState, stopCamera]);

  return {
    videoRef,
    cameraReady,
    cameraError,
    cameraPermissionState,
    refreshCameraPermissionState,
    startCamera,
    stopCamera,
    captureFrame
  };
}
