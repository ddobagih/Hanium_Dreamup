import { useCallback, useEffect, useRef, useState } from "react";

type UseCameraOptions = {
  onCameraUnavailable?: () => void;
  onCameraError?: () => void;
};

export function useCamera({ onCameraUnavailable, onCameraError }: UseCameraOptions = {}) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const [cameraReady, setCameraReady] = useState(false);
  const [cameraError, setCameraError] = useState<string | null>(null);

  const stopCamera = useCallback(() => {
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
  }, []);

  const startCamera = useCallback(async () => {
    if (!navigator.mediaDevices?.getUserMedia) {
      setCameraReady(false);
      setCameraError("이 브라우저는 카메라 입력을 지원하지 않습니다.");
      onCameraUnavailable?.();
      return;
    }

    try {
      stopCamera();
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
      onCameraError?.();
    }
  }, [onCameraError, onCameraUnavailable, stopCamera]);

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
    const cameraTimer = window.setTimeout(() => {
      void startCamera();
    }, 0);

    return () => {
      window.clearTimeout(cameraTimer);
      stopCamera();
    };
  }, [startCamera, stopCamera]);

  return {
    videoRef,
    cameraReady,
    cameraError,
    startCamera,
    stopCamera,
    captureFrame
  };
}
