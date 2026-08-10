"use client";

/** Owns same-origin field/admin login status without exposing backend service credentials to the browser. */
import { useCallback, useEffect, useRef, useState } from "react";
import { GATEWAY_SESSION_INVALID_EVENT } from "@/lib/gateway-session-client";

export type GatewaySessionAccess = "field" | "admin";
export type GatewaySessionState = "checking" | "required" | "authenticated" | "error";
const GATEWAY_SESSION_REQUEST_TIMEOUT_MS = 8_000;

async function fetchGatewaySession(input: string, init: RequestInit = {}): Promise<Response> {
  const abortController = new AbortController();
  const timeoutId = window.setTimeout(() => abortController.abort(), GATEWAY_SESSION_REQUEST_TIMEOUT_MS);
  try {
    return await fetch(input, { ...init, signal: abortController.signal });
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") {
      throw new Error("인증 서버 응답 시간이 초과됐습니다.");
    }
    throw error;
  } finally {
    window.clearTimeout(timeoutId);
  }
}

export function useGatewaySession(access: GatewaySessionAccess) {
  const checkGenerationRef = useRef(0);
  const [state, setState] = useState<GatewaySessionState>("checking");
  const [message, setMessage] = useState("접근 권한을 확인하는 중입니다.");
  const [actorId, setActorId] = useState<string | null>(null);
  const endpoint = `/api/${access}-session`;

  const decodeStatus = useCallback(async (response: Response) => {
    const payload = (await response.json()) as { authenticated?: boolean; actor_id?: string | null; message?: string };
    if (!response.ok) throw new Error(payload.message || "접근 권한 확인에 실패했습니다.");
    return payload;
  }, []);

  const checkSession = useCallback(async () => {
    checkGenerationRef.current += 1;
    const checkGeneration = checkGenerationRef.current;
    if (typeof navigator !== "undefined" && navigator.onLine === false) {
      setActorId(null);
      setState("error");
      setMessage("오프라인에서는 세션을 확인할 수 없어 보행 보조 기능을 열지 않습니다.");
      return;
    }
    try {
      const response = await fetchGatewaySession(endpoint, { cache: "no-store" });
      const payload = await decodeStatus(response);
      if (checkGenerationRef.current !== checkGeneration) return;
      setState(payload.authenticated ? "authenticated" : "required");
      setActorId(payload.actor_id ?? null);
      setMessage(payload.authenticated ? "인증됨" : "테스트 접근 토큰이 필요합니다.");
    } catch (error) {
      if (checkGenerationRef.current !== checkGeneration) return;
      setState("error");
      setMessage(error instanceof Error ? error.message : "접근 권한 확인에 실패했습니다.");
    }
  }, [decodeStatus, endpoint]);

  useEffect(() => {
    const timer = window.setTimeout(() => void checkSession(), 0);
    return () => {
      window.clearTimeout(timer);
      checkGenerationRef.current += 1;
    };
  }, [checkSession]);

  useEffect(() => {
    const revalidate = () => void checkSession();
    const failClosedOffline = () => {
      checkGenerationRef.current += 1;
      setActorId(null);
      setState("error");
      setMessage("오프라인에서는 세션을 확인할 수 없어 보행 보조 기능을 열지 않습니다.");
    };
    const invalidate = () => {
      setActorId(null);
      setState("checking");
      setMessage("세션 만료 여부를 확인하는 중입니다.");
      void checkSession();
    };
    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") revalidate();
    };
    window.addEventListener("focus", revalidate);
    window.addEventListener("online", revalidate);
    window.addEventListener("offline", failClosedOffline);
    window.addEventListener(GATEWAY_SESSION_INVALID_EVENT, invalidate);
    document.addEventListener("visibilitychange", onVisibilityChange);
    const intervalId = window.setInterval(revalidate, 60_000);
    return () => {
      window.removeEventListener("focus", revalidate);
      window.removeEventListener("online", revalidate);
      window.removeEventListener("offline", failClosedOffline);
      window.removeEventListener(GATEWAY_SESSION_INVALID_EVENT, invalidate);
      document.removeEventListener("visibilitychange", onVisibilityChange);
      window.clearInterval(intervalId);
    };
  }, [checkSession]);

  const refresh = useCallback(async () => {
    setState("checking");
    setMessage("접근 권한을 확인하는 중입니다.");
    await checkSession();
  }, [checkSession]);

  const authenticate = useCallback(
    async (token: string, requestedActorId = "") => {
      checkGenerationRef.current += 1;
      setState("checking");
      setMessage("토큰을 확인하는 중입니다.");
      try {
        const response = await fetchGatewaySession(endpoint, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ token, actor_id: requestedActorId.trim() }),
          cache: "no-store"
        });
        if (!response.ok) {
          const payload = (await response.json().catch(() => null)) as { message?: string } | null;
          setState("required");
          setMessage(payload?.message || (response.status === 429 ? "인증 시도가 잠시 차단되었습니다." : "계정 ID 또는 토큰이 올바르지 않습니다."));
          return;
        }
        window.location.reload();
      } catch {
        setState("error");
        setMessage("인증 서버에 연결할 수 없습니다.");
      }
    },
    [endpoint]
  );

  const logout = useCallback(async () => {
    checkGenerationRef.current += 1;
    setMessage("로그아웃하는 중입니다.");
    try {
      const response = await fetchGatewaySession(endpoint, { method: "DELETE", cache: "no-store" });
      if (!response.ok) throw new Error("로그아웃에 실패했습니다.");
      setActorId(null);
      setState("required");
      setMessage("로그아웃했습니다.");
      window.location.reload();
    } catch (error) {
      setState("error");
      setMessage(error instanceof Error ? error.message : "로그아웃에 실패했습니다.");
    }
  }, [endpoint]);

  return { state, message, actorId, authenticate, logout, refresh };
}
