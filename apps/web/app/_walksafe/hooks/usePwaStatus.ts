"use client";

/**
 * Tracks installability and service-worker lifecycle only when PWA support is explicitly enabled.
 * The worker provides an app shell; online API availability is reported separately and is not implied.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

export type PwaInstallState = "unsupported" | "browser" | "available" | "install_requested" | "standalone";
export type PwaUpdateState = "unsupported" | "idle" | "checking" | "ready" | "applying" | "applied" | "error";
export type ScreenWakeLockState = "idle" | "requesting" | "active" | "unsupported" | "denied" | "released" | "error";

type ScreenWakeLockSentinel = {
  readonly released: boolean;
  release: () => Promise<void>;
  addEventListener: (type: "release", listener: () => void, options?: { once?: boolean }) => void;
};

type ScreenWakeLockManager = {
  request: (type: "screen") => Promise<ScreenWakeLockSentinel>;
};

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

export function resolvePwaInstallState({
  standalone,
  promptAvailable,
  installRequested
}: {
  standalone: boolean;
  promptAvailable: boolean;
  installRequested: boolean;
}): PwaInstallState {
  if (standalone) return "standalone";
  if (installRequested) return "install_requested";
  return promptAvailable ? "available" : "browser";
}

export function isWalkSafeServiceWorkerScript(scriptUrl: string, origin: string): boolean {
  try {
    const parsed = new URL(scriptUrl, origin);
    return parsed.origin === origin && parsed.pathname === "/sw.js";
  } catch {
    return false;
  }
}

export function describePwaInstallState(state: PwaInstallState): string {
  switch (state) {
    case "available":
      return "앱 설치 가능";
    case "install_requested":
      return "설치 요청 수락 · 홈 화면에서 standalone 실행 확인 필요";
    case "standalone":
      return "설치 실행 확인됨 · standalone 실행 중";
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
    case "applying":
      return `새 오프라인 셸 적용 확인 중${suffix}`;
    case "applied":
      return `새 오프라인 셸 적용 완료 · 안전할 때 화면을 새로고침하세요${suffix}`;
    case "error":
      return "오프라인 셸 등록 또는 적용 실패";
    case "unsupported":
      return "이 브라우저는 오프라인 셸을 지원하지 않습니다.";
    default:
      return `오프라인 셸 준비${suffix}`;
  }
}

export function describeScreenWakeLockState(state: ScreenWakeLockState): string {
  switch (state) {
    case "active":
      return "화면 켜짐 유지 활성 · 보행 보조가 foreground에서 실행 중입니다.";
    case "requesting":
      return "화면 켜짐 유지 권한을 요청 중입니다.";
    case "unsupported":
      return "이 브라우저는 화면 켜짐 유지를 지원하지 않습니다. 화면이 잠기면 보행 보조가 즉시 중지됩니다.";
    case "denied":
      return "화면 켜짐 유지가 거부됐습니다. 화면이 잠기면 보행 보조가 즉시 중지됩니다.";
    case "released":
      return "화면 켜짐 유지가 해제됐습니다. 화면을 켠 상태에서 다시 요청해 주세요.";
    case "error":
      return "화면 켜짐 유지에 실패했습니다. 화면이 잠기면 보행 보조가 즉시 중지됩니다.";
    default:
      return "화면 켜짐 유지 대기";
  }
}

export function shouldWarnForScreenWakeLock(state: ScreenWakeLockState): boolean {
  return state === "unsupported" || state === "denied" || state === "released" || state === "error";
}

export function useScreenWakeLock(active: boolean) {
  const [state, setState] = useState<ScreenWakeLockState>("idle");
  const [retrySequence, setRetrySequence] = useState(0);

  useEffect(() => {
    let cancelled = false;
    let sentinel: ScreenWakeLockSentinel | null = null;
    const publishState = (nextState: ScreenWakeLockState) => {
      globalThis.queueMicrotask(() => {
        if (!cancelled) setState(nextState);
      });
    };
    const cleanup = () => {
      cancelled = true;
      if (sentinel && !sentinel.released) void sentinel.release();
    };

    if (!active) {
      publishState("idle");
      return cleanup;
    }
    if (typeof navigator === "undefined" || typeof document === "undefined") {
      publishState("unsupported");
      return cleanup;
    }
    const manager = (navigator as Navigator & { wakeLock?: ScreenWakeLockManager }).wakeLock;
    if (!manager?.request) {
      publishState("unsupported");
      return cleanup;
    }
    if (document.visibilityState !== "visible") {
      publishState("released");
      return cleanup;
    }

    publishState("requesting");
    void manager.request("screen").then((nextSentinel) => {
      if (cancelled) {
        void nextSentinel.release();
        return;
      }
      sentinel = nextSentinel;
      setState("active");
      nextSentinel.addEventListener("release", () => {
        if (!cancelled) setState("released");
      }, { once: true });
    }).catch((error: unknown) => {
      if (cancelled) return;
      setState(error instanceof DOMException && error.name === "NotAllowedError" ? "denied" : "error");
    });

    return cleanup;
  }, [active, retrySequence]);

  const retry = useCallback(() => setRetrySequence((sequence) => sequence + 1), []);
  return {
    state,
    message: describeScreenWakeLockState(state),
    warning: shouldWarnForScreenWakeLock(state),
    canRetry: state === "denied" || state === "released" || state === "error",
    retry
  };
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

function loadedNextStaticAssetUrls(): string[] {
  if (typeof window === "undefined") return [];
  const resourceUrls = window.performance
    .getEntriesByType("resource")
    .map((entry) => entry.name);
  const documentUrls = [
    ...Array.from(document.scripts, (item) => item.src),
    ...Array.from(document.querySelectorAll<HTMLLinkElement>('link[href*="/_next/static/"]'), (item) => item.href)
  ];
  return [...new Set([...resourceUrls, ...documentUrls])].filter((value) => {
    try {
      const url = new URL(value, window.location.origin);
      return url.origin === window.location.origin && url.pathname.startsWith("/_next/static/");
    } catch {
      return false;
    }
  });
}

async function cacheLoadedNextStaticAssets(registration: ServiceWorkerRegistration): Promise<boolean> {
  const worker = registration.active;
  if (!worker) return false;
  const urls = loadedNextStaticAssetUrls();
  if (urls.length === 0) return false;
  return new Promise<boolean>((resolve) => {
    const channel = new MessageChannel();
    const timeoutId = window.setTimeout(() => resolve(false), 5_000);
    channel.port1.onmessage = (event) => {
      window.clearTimeout(timeoutId);
      resolve(event.data?.ok === true && Number(event.data?.cached) > 0);
    };
    worker.postMessage({ type: "CACHE_LOADED_STATIC_ASSETS", urls }, [channel.port2]);
  });
}

export function usePwaStatus() {
  const pwaEnabled = isWalkSafePwaEnabled();
  const [isOnline, setIsOnline] = useState(true);
  const [installState, setInstallState] = useState<PwaInstallState>("unsupported");
  const [updateState, setUpdateState] = useState<PwaUpdateState>(() => initialPwaUpdateState(pwaEnabled));
  const [swVersion, setSwVersion] = useState<string | null>(null);
  const [deferredPrompt, setDeferredPrompt] = useState<BeforeInstallPromptEvent | null>(null);
  const [registration, setRegistration] = useState<ServiceWorkerRegistration | null>(null);
  const updateApplyRequestedRef = useRef(false);
  const updateApplyTimerRef = useRef<number | null>(null);

  useEffect(() => {
    if (typeof navigator === "undefined" || typeof window === "undefined") {
      return;
    }
    document.documentElement.dataset.walksafeHydrated = "true";
    document.documentElement.dataset.walksafeOfflineShellReady = "false";
    const updateOnlineState = () => setIsOnline(navigator.onLine);
    updateOnlineState();
    window.addEventListener("online", updateOnlineState);
    window.addEventListener("offline", updateOnlineState);
    return () => {
      delete document.documentElement.dataset.walksafeHydrated;
      delete document.documentElement.dataset.walksafeOfflineShellReady;
      window.removeEventListener("online", updateOnlineState);
      window.removeEventListener("offline", updateOnlineState);
    };
  }, []);

  useEffect(() => {
    if (!pwaEnabled) {
      if (typeof window !== "undefined" && typeof navigator !== "undefined" && "serviceWorker" in navigator) {
        void navigator.serviceWorker.getRegistrations().then((registrations) =>
          Promise.all(
            registrations
              .filter((item) => isWalkSafeServiceWorkerScript(item.active?.scriptURL ?? item.waiting?.scriptURL ?? "", window.location.origin))
              .map((item) => item.unregister())
          )
        );
        if ("caches" in window) {
          void window.caches.keys().then((keys) =>
            Promise.all(keys.filter((key) => key.startsWith("walksafe-assist-")).map((key) => window.caches.delete(key)))
          );
        }
      }
      return;
    }
    if (typeof window === "undefined" || typeof navigator === "undefined") {
      return;
    }
    const media = window.matchMedia?.("(display-mode: standalone)");
    const isCurrentlyStandalone = () =>
      isStandaloneDisplayMode(Boolean(media?.matches), (navigator as Navigator & { standalone?: boolean }).standalone);
    const updateInstallState = () => {
      const standalone = isCurrentlyStandalone();
      setInstallState((current) => resolvePwaInstallState({
        standalone,
        promptAvailable: Boolean(deferredPrompt),
        installRequested: current === "install_requested"
      }));
    };
    const onBeforeInstallPrompt = (event: Event) => {
      event.preventDefault();
      setDeferredPrompt(event as BeforeInstallPromptEvent);
      setInstallState(resolvePwaInstallState({
        standalone: isCurrentlyStandalone(),
        promptAvailable: true,
        installRequested: false
      }));
    };
    const onAppInstalled = () => {
      setDeferredPrompt(null);
      setInstallState(resolvePwaInstallState({
        standalone: isCurrentlyStandalone(),
        promptAvailable: false,
        installRequested: true
      }));
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
      .register("/sw.js", { updateViaCache: "none" })
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
        void navigator.serviceWorker.ready.then(async (readyRegistration) => {
          await new Promise((resolve) => window.setTimeout(resolve, 500));
          document.documentElement.dataset.walksafeOfflineShellReady = String(
            await cacheLoadedNextStaticAssets(readyRegistration)
          );
        });
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
    const onControllerChange = () => {
      if (!updateApplyRequestedRef.current) {
        return;
      }
      updateApplyRequestedRef.current = false;
      if (updateApplyTimerRef.current !== null) {
        window.clearTimeout(updateApplyTimerRef.current);
        updateApplyTimerRef.current = null;
      }
      setUpdateState("applied");
      document.documentElement.dataset.walksafeOfflineShellReady = "false";

      const worker = navigator.serviceWorker.controller;
      if (worker) {
        const channel = new MessageChannel();
        channel.port1.onmessage = (event) => {
          if (typeof event.data?.version === "string") {
            setSwVersion(event.data.version);
          }
        };
        worker.postMessage({ type: "GET_VERSION" }, [channel.port2]);
      }
      void navigator.serviceWorker.ready.then(async (readyRegistration) => {
        document.documentElement.dataset.walksafeOfflineShellReady = String(
          await cacheLoadedNextStaticAssets(readyRegistration)
        );
      });
    };
    navigator.serviceWorker.addEventListener("message", onMessage);
    navigator.serviceWorker.addEventListener("controllerchange", onControllerChange);
    return () => {
      cancelled = true;
      navigator.serviceWorker.removeEventListener("message", onMessage);
      navigator.serviceWorker.removeEventListener("controllerchange", onControllerChange);
      updateApplyRequestedRef.current = false;
      if (updateApplyTimerRef.current !== null) {
        window.clearTimeout(updateApplyTimerRef.current);
        updateApplyTimerRef.current = null;
      }
    };
  }, [pwaEnabled]);

  const installApp = useCallback(async () => {
    if (!deferredPrompt) {
      return false;
    }
    await deferredPrompt.prompt();
    const choice = await deferredPrompt.userChoice;
    setDeferredPrompt(null);
    const standalone = typeof window !== "undefined" && typeof navigator !== "undefined" &&
      isStandaloneDisplayMode(
        Boolean(window.matchMedia?.("(display-mode: standalone)").matches),
        (navigator as Navigator & { standalone?: boolean }).standalone
      );
    setInstallState(resolvePwaInstallState({
      standalone,
      promptAvailable: false,
      installRequested: choice.outcome === "accepted"
    }));
    return choice.outcome === "accepted";
  }, [deferredPrompt]);

  const applyServiceWorkerUpdate = useCallback(() => {
    if (!registration?.waiting) {
      return false;
    }
    updateApplyRequestedRef.current = true;
    document.documentElement.dataset.walksafeOfflineShellReady = "false";
    registration.waiting.postMessage({ type: "SKIP_WAITING" });
    setUpdateState("applying");
    if (updateApplyTimerRef.current !== null) {
      window.clearTimeout(updateApplyTimerRef.current);
    }
    updateApplyTimerRef.current = window.setTimeout(() => {
      if (updateApplyRequestedRef.current) {
        updateApplyRequestedRef.current = false;
        setUpdateState("error");
      }
      updateApplyTimerRef.current = null;
    }, 10000);
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
