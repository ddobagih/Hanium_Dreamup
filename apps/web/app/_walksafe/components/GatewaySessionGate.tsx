"use client";

import { KeyRound } from "lucide-react";
import { useState, type FormEvent } from "react";
import type { GatewaySessionAccess, GatewaySessionState } from "../hooks/useGatewaySession";

type GatewaySessionGateProps = {
  access: GatewaySessionAccess;
  state: GatewaySessionState;
  message: string;
  onAuthenticate: (token: string, actorId?: string) => Promise<void>;
  onRetry: () => Promise<void>;
};

export function GatewaySessionGate({ access, state, message, onAuthenticate, onRetry }: GatewaySessionGateProps) {
  const [token, setToken] = useState("");
  const [actorId, setActorId] = useState("");
  const busy = state === "checking";
  const title = access === "admin" ? "관리자 인증" : "현장 테스트 인증";

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const value = token.trim();
    if (!value || busy) return;
    setToken("");
    void onAuthenticate(value, actorId.trim());
  };

  return (
    <main className="gateway-session-shell">
      <form className="gateway-session-form" onSubmit={submit}>
        <KeyRound aria-hidden="true" size={28} />
        <h1>WalkSafe Assist</h1>
        <strong>{title}</strong>
        <label htmlFor={`${access}-actor-id`}>계정 ID (계정형 운영 설정 시)</label>
        <input
          id={`${access}-actor-id`}
          type="text"
          autoComplete="username"
          value={actorId}
          onChange={(event) => setActorId(event.target.value)}
          maxLength={64}
          disabled={busy}
        />
        <label htmlFor={`${access}-access-token`}>접근 토큰</label>
        <input
          id={`${access}-access-token`}
          type="password"
          autoComplete="current-password"
          value={token}
          onChange={(event) => setToken(event.target.value)}
          disabled={busy}
        />
        <button type="submit" disabled={busy || !token.trim()}>
          {busy ? "확인 중" : "접속"}
        </button>
        {state === "error" ? (
          <button type="button" className="gateway-session-retry" onClick={() => void onRetry()}>
            다시 확인
          </button>
        ) : null}
        <small role={state === "error" ? "alert" : "status"}>{message}</small>
      </form>
    </main>
  );
}
