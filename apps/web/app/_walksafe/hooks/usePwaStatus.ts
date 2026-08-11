"use client";

import { useCallback, useEffect, useMemo, useState } from "react";

export type PwaInstallState = "unsupported" | "browser" | "available" | "standalone" | "installed";
export type PwaUpdateState = "unsupported" | "idle" | "checking" | "ready" | "applied" | "error";

type BeforeInstallPromptEvent = Event & {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed"; platform: string }>;
};

export function isWalkSafePwaEnabled(rawValue = process.env.NEXT_PUBLIC_WALKSAFE_PWA_ENABLED): boolean {
  return rawValue === "true";
}

export function isStandaloneDisplayMode(matchMediaResult: boolean, navigatorStandalone: unknown): boolean {
  return matchMediaResult || navigatorStandalone === true;
}

export function describePwaInstallState(state: PwaInstallState): string {
  switch (state) {
    case "available":
      return "앱 설치 가능";
    case "standalone":
      return "앱 설치됨 · standalone 실행 중";
    case "installed":
      return "앱 설치됨";
    case "browser":
      return "브라우저 실행 중 · 설치 프롬프트 대기";
    default:
      return "앱 설치 상태 확인 불가";
  }
}

export function describePwaUpdateState(state: PwaUpdateState, version: string | null): string {
  const suffix = version ? ` · SW ${version}` : "";
  switch (state) {
    case "ready":
      return `새 오프라인 셸 업데이트 준비${suffix}`;
    case "checking":
      return `오프라인 셸 확인 중${suffix}`;
    case "applied":
      return `새 오프라인 셸 적용 요청 완료${suffix}`;
    case "error":
      return "오프라인 셸 등록 실패";
    case "unsupported":
      return "이 브라우저는 오프라인 셸을 지원하지 않습니다.";
    default:
      return `오프라인 셸 준비${suffix}`;
  }
}

function initialPwaUpdateState(pwaEnabled: boolean): PwaUpdateState {
  if (!pwaEnabled) {
    return "unsupported";
  }
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) {
    return "unsupported";
  }
  return "checking";
}

export function usePwaStatus() {
  const pwaEnabled = isWalkSafePwaEnabled();
  const [isOnline, setIsOnline] = useState(true);
  const [installState, setInstallState] = useState<PwaInstallState>("unsupported");
  const [updateState, setUpdateState] = useState<PwaUpdateState>(() => initialPwaUpdateState(pwaEnabled));
  const [swVersion, setSwVersion] = useState<string | null>(null);
  const [deferredPrompt, setDeferredPrompt] = useState<BeforeInstallPromptEvent | null>(null);
  const [registration, setRegistration] = useState<ServiceWorkerRegistration | null>(null);

  useEffect(() => {
    if (typeof navigator === "undefined" || typeof window === "undefined") {
      return;
    }
    const updateOnlineState = () => setIsOnline(navigator.onLine);
    updateOnlineState();
    window.addEventListener("online", updateOnlineState);
    window.addEventListener("offline", updateOnlineState);
    return () => {
      window.removeEventListener("online", updateOnlineState);
      window.removeEventListener("offline", updateOnlineState);
    };
  }, []);

  useEffect(() => {
    if (!pwaEnabled) {
      return;
    }
    if (typeof window === "undefined" || typeof navigator === "undefined") {
      return;
    }
    const media = window.matchMedia?.("(display-mode: standalone)");
    const updateInstallState = () => {
      const standalone = isStandaloneDisplayMode(Boolean(media?.matches), (navigator as Navigator & { standalone?: boolean }).standalone);
      setInstallState(standalone ? "standalone" : deferredPrompt ? "available" : "browser");
    };
    const onBeforeInstallPrompt = (event: Event) => {
      event.preventDefault();
      setDeferredPrompt(event as BeforeInstallPromptEvent);
      setInstallState("available");
    };
    const onAppInstalled = () => {
      setDeferredPrompt(null);
      setInstallState("installed");
    };

    updateInstallState();
    window.addEventListener("beforeinstallprompt", onBeforeInstallPrompt);
    window.addEventListener("appinstalled", onAppInstalled);
    media?.addEventListener?.("change", updateInstallState);
    return () => {
      window.removeEventListener("beforeinstallprompt", onBeforeInstallPrompt);
      window.removeEventListener("appinstalled", onAppInstalled);
      media?.removeEventListener?.("change", updateInstallState);
    };
  }, [deferredPrompt, pwaEnabled]);

  useEffect(() => {
    if (!pwaEnabled) {
      return;
    }
    if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) {
      return;
    }

    let cancelled = false;
    navigator.serviceWorker
      .register("/sw.js")
      .then((nextRegistration) => {
        if (cancelled) {
          return;
        }
        setRegistration(nextRegistration);
        setUpdateState(nextRegistration.waiting ? "ready" : "idle");

        const requestVersion = (worker: ServiceWorker | null) => {
          if (!worker) {
            return;
          }
          const channel = new MessageChannel();
          channel.port1.onmessage = (event) => {
            if (typeof event.data?.version === "string") {
              setSwVersion(event.data.version);
            }
          };
          worker.postMessage({ type: "GET_VERSION" }, [channel.port2]);
        };

        requestVersion(nextRegistration.active ?? nextRegistration.waiting ?? nextRegistration.installing);
        nextRegistration.addEventListener("updatefound", () => {
          const installing = nextRegistration.installing;
          installing?.addEventListener("statechange", () => {
            if (installing.state === "installed" && navigator.serviceWorker.controller) {
              setUpdateState("ready");
            }
          });
        });
      })
      .catch(() => {
        if (!cancelled) {
          setUpdateState("error");
        }
      });

    const onMessage = (event: MessageEvent) => {
      if (typeof event.data?.version === "string") {
        setSwVersion(event.data.version);
      }
    };
    navigator.serviceWorker.addEventListener("message", onMessage);
    return () => {
      cancelled = true;
      navigator.serviceWorker.removeEventListener("message", onMessage);
    };
  }, [pwaEnabled]);

  const installApp = useCallback(async () => {
    if (!deferredPrompt) {
      return false;
    }
    await deferredPrompt.prompt();
    const choice = await deferredPrompt.userChoice;
    setDeferredPrompt(null);
    setInstallState(choice.outcome === "accepted" ? "installed" : "browser");
    return choice.outcome === "accepted";
  }, [deferredPrompt]);

  const applyServiceWorkerUpdate = useCallback(() => {
    if (!registration?.waiting) {
      return false;
    }
    registration.waiting.postMessage({ type: "SKIP_WAITING" });
    setUpdateState("applied");
    return true;
  }, [registration]);

  const installMessage = useMemo(() => describePwaInstallState(installState), [installState]);
  const updateMessage = useMemo(() => describePwaUpdateState(updateState, swVersion), [swVersion, updateState]);

  return {
    isOnline,
    installState,
    updateState,
    swVersion,
    installMessage,
    updateMessage,
    canInstallPwa: installState === "available",
    canApplyServiceWorkerUpdate: updateState === "ready",
    installApp,
    applyServiceWorkerUpdate
  };
}
