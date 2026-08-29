import assert from "node:assert/strict";
import { test } from "node:test";

import {
  FIRST_RUN_ROUTE_PATHS,
  isFirstRunRoute,
  proxyFirstRunRequest
} from "../src/first-run.js";

function post(path: string, body: unknown = { email: "a@example.org" }): Request {
  return new Request(`http://127.0.0.1:8081${path}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body)
  });
}

test("exactly the four pre-login stages are routed", () => {
  assert.deepEqual(
    [...FIRST_RUN_ROUTE_PATHS],
    [
      "/api/first-run/signups",
      "/api/first-run/signups/verify",
      "/api/first-run/signups/activate",
      "/api/first-run/signups/login"
    ]
  );
  assert.equal(isFirstRunRoute("/api/first-run/signups"), true);
  assert.equal(isFirstRunRoute("/api/first-run/signups/other"), false);
  assert.equal(isFirstRunRoute("/api/field-session"), false);
});

test("only POST is accepted", async () => {
  const response = await proxyFirstRunRequest(
    new Request("http://127.0.0.1:8081/api/first-run/signups", { method: "GET" })
  );
  assert.equal(response.status, 405);
});

test("an unknown first-run path is not proxied", async () => {
  const response = await proxyFirstRunRequest(post("/api/first-run/nope"));
  assert.equal(response.status, 404);
});

test("an oversized body is refused before reaching the backend", async () => {
  let called = false;
  const response = await proxyFirstRunRequest(
    post("/api/first-run/signups", { email: "a".repeat(5000) }),
    async () => {
      called = true;
      return new Response("{}", { status: 200 });
    }
  );
  assert.equal(response.status, 413);
  assert.equal(called, false);
});

test("the request is forwarded without requiring a field session", async () => {
  // 4·5·7·8단계는 계정이 생기기 전이므로 세션 쿠키 없이도 상류로 나가야 한다.
  let seenUrl = "";
  let seenMethod = "";
  const response = await proxyFirstRunRequest(post("/api/first-run/signups"), async (url, init) => {
    seenUrl = String(url);
    seenMethod = String(init?.method ?? "");
    return Response.json({ receipt_sha256: "a".repeat(64) }, { status: 201 });
  });
  assert.equal(seenMethod, "POST");
  assert.match(seenUrl, /\/first-run\/signups$/);
  assert.equal(response.status, 201);
});

test("an upstream auth failure is not exposed verbatim to the client", async () => {
  const response = await proxyFirstRunRequest(post("/api/first-run/signups"), async () =>
    Response.json({ detail: "internal token rejected" }, { status: 401 })
  );
  assert.equal(response.status, 502);
  assert.equal((await response.json()).detail.code, "gateway_upstream_auth_failed");
});
