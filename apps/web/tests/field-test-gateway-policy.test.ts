import { chmod, mkdtemp, readdir, rm, symlink, utimes, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { createHash, createHmac } from "node:crypto";
import { NAVIGATION_API_BASE } from "../lib/navigation-api";
import { VOICE_API_BASE } from "../lib/voice-api";
import { DETECT_API_BASE } from "../lib/detect-api";
import { reportExportUrl } from "../lib/report-api";
import {
  isTransitionalAndroidApiPath,
  TRANSITIONAL_ANDROID_API_PATHS
} from "../legacy-runtime-boundary";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function cookiePair(setCookie: string): string {
  return setCookie.split(";", 1)[0];
}

async function main() {
  assert(
    TRANSITIONAL_ANDROID_API_PATHS.join(",") ===
      "/api/field-session,/api/navigation/walking,/api/navigation/destinations/search,/api/reports/v2",
    "the temporary Android BFF allowlist must remain exact"
  );
  for (const pathname of TRANSITIONAL_ANDROID_API_PATHS) {
    assert(isTransitionalAndroidApiPath(pathname), `Android BFF route must remain reachable: ${pathname}`);
  }
  for (const pathname of ["/", "/admin", "/manifest.webmanifest", "/api/admin-session", "/api/detect/v2"]) {
    assert(!isTransitionalAndroidApiPath(pathname), `Legacy Web surface must stay closed: ${pathname}`);
  }
  assert(NAVIGATION_API_BASE === "/api", "navigation must use the same-origin /api gateway");
  assert(VOICE_API_BASE === "/api", "voice must use the same-origin /api gateway");
  assert(DETECT_API_BASE === "/api", "detect must default to the same-origin /api gateway");
  assert(reportExportUrl({}, "json").startsWith("/api/reports/export"), "reports must default to same-origin");

  process.env.WALKSAFE_FIELD_TEST_TOKEN = "field-token-for-gateway-tests-123456";
  process.env.WALKSAFE_ADMIN_TOKEN = "admin-token-for-gateway-tests-123456";
  process.env.WALKSAFE_GATEWAY_SESSION_SECRET = "gateway-actor-assertion-secret-for-tests-123456789";
  process.env.BACKEND_API_BASE_URL = "http://127.0.0.1:8000";
  process.env.VOICE_API_BASE_URL = "http://127.0.0.1:9001";
  process.env.VOICE_SERVICE_TOKEN = "dedicated-voice-service-token-for-tests-123456";
  process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = await mkdtemp(path.join(os.tmpdir(), "walksafe-gateway-rate-test-"));
  process.env.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER = "cf-connecting-ip";

  const auth = await import("../app/api/_gateway-auth");
  const proxy = await import("../app/api/_backend");
  const requestBody = await import("../app/api/_request-body");
  const fieldSession = await import("../app/api/field-session/route");
  const adminSession = await import("../app/api/admin-session/route");
  const voiceStt = await import("../app/api/speech/stt/route");

  const originalFetch = globalThis.fetch;
  process.env.WALKSAFE_FIELD_ACCOUNTS_JSON = JSON.stringify([
    { actor_id: "voice-reuse-test", token: "named-browser-token-for-voice-reuse-123456" }
  ]);
  assert(
    auth.isGatewayCredential("named-browser-token-for-voice-reuse-123456"),
    "dedicated Voice credentials must not reuse named browser account tokens"
  );
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  assert(proxy.backendUploadPath(["report-01.jpg"]) === "/uploads/report-01.jpg", "a flat report image filename should be accepted");
  assert(proxy.backendUploadPath(["..", "secret.jpg"]) === null, "nested or traversal upload paths must fail closed at the Web gateway");
  assert(proxy.backendUploadPath(["secret.txt"]) === null, "non-image upload paths must fail closed at the Web gateway");
  try {
    globalThis.fetch = (async (_input: string | URL | Request, init?: RequestInit) =>
      await new Promise<Response>((_resolve, reject) => {
        const keepAlive = setTimeout(() => reject(new Error("test timeout did not abort fetch")), 1_000);
        init?.signal?.addEventListener(
          "abort",
          () => {
            clearTimeout(keepAlive);
            reject(new DOMException("aborted", "AbortError"));
          },
          { once: true }
        );
      })) as typeof fetch;
    const timedOut = await proxy.fetchBackend(
      new Request("https://field.example/api/reports"),
      "http://127.0.0.1:8000/reports",
      {},
      5
    );
    assert(timedOut.status === 504, "upstream requests must terminate with a bounded gateway timeout");
    globalThis.fetch = (async () => {
      throw new Error("internal backend path /home/operator/private-model.pt");
    }) as typeof fetch;
    const unavailable = await proxy.fetchBackend(
      new Request("https://field.example/api/reports"),
      "http://127.0.0.1:8000/reports"
    );
    const unavailableBody = await unavailable.text();
    assert(unavailable.status === 502, "failed upstream connections must return a bounded gateway error");
    assert(
      !unavailableBody.includes("/home/operator") && unavailableBody.includes("upstream request failed"),
      "gateway errors must not expose upstream exception details or local paths"
    );
  } finally {
    globalThis.fetch = originalFetch;
  }

  const validMultipart = new FormData();
  validMultipart.set("metadata", "{}");
  const bounded = await proxy.readBoundedMultipartFormData(
    new Request("https://field.example/api/reports", { method: "POST", body: validMultipart }),
    1024
  );
  assert(bounded.formData?.get("metadata") === "{}", "bounded multipart parser must preserve valid fields");

  const exportResponse = await proxy.toBackendResponse(
    new Response("[]", {
      headers: {
        "content-type": "application/json",
        "x-walksafe-audit-id": "11111111-1111-4111-8111-111111111111",
        "x-walksafe-actor-id": "operator.kim",
        "x-walksafe-demo-filter": "exclude_fake",
        "x-walksafe-export-profile": "agency",
        "x-walksafe-location-precision": "exact-report"
      }
    })
  );
  assert(
    exportResponse.headers.get("x-walksafe-audit-id") === "11111111-1111-4111-8111-111111111111" &&
      exportResponse.headers.get("x-walksafe-actor-id") === "operator.kim" &&
      exportResponse.headers.get("x-walksafe-export-profile") === "agency" &&
      exportResponse.headers.get("x-walksafe-location-precision") === "exact-report",
    "same-origin proxy must preserve the bounded export audit header allowlist"
  );

  const declaredOversize = await proxy.readBoundedMultipartFormData(
    new Request("https://field.example/api/reports", {
      method: "POST",
      headers: {
        "content-type": "multipart/form-data; boundary=walksafe-test",
        "content-length": "9"
      },
      body: new Uint8Array([1])
    }),
    8
  );
  assert(declaredOversize.error?.status === 413, "oversized declared multipart requests must fail before parsing");

  const streamedOversize = await proxy.readBoundedMultipartFormData(
    new Request("https://field.example/api/reports", {
      method: "POST",
      headers: { "content-type": "multipart/form-data; boundary=walksafe-test" },
      body: new Uint8Array(9)
    }),
    8
  );
  assert(streamedOversize.error?.status === 413, "chunked multipart bodies must be capped while reading");

  let uncooperativeCancelCalled = false;
  const uncooperativeOversizeBody = new ReadableStream<Uint8Array>({
    start: (controller) => controller.enqueue(new Uint8Array(9)),
    cancel: () => {
      uncooperativeCancelCalled = true;
      return new Promise<void>(() => undefined);
    }
  });
  const uncooperativeCancelTimeout = Symbol("uncooperative-cancel-timeout");
  const uncooperativeOversize = await Promise.race([
    proxy.readBoundedMultipartFormData(
      new Request(
        "https://field.example/api/reports",
        {
          method: "POST",
          headers: { "content-type": "multipart/form-data; boundary=walksafe-test" },
          body: uncooperativeOversizeBody,
          duplex: "half"
        } as RequestInit & { duplex: "half" }
      ),
      8
    ),
    new Promise<typeof uncooperativeCancelTimeout>((resolve) =>
      setTimeout(() => resolve(uncooperativeCancelTimeout), 100)
    )
  ]);
  assert(
    uncooperativeOversize !== uncooperativeCancelTimeout,
    "an uncooperative stream cancel must not delay the known 413 response"
  );
  assert(uncooperativeOversize.error?.status === 413, "uncooperative streamed oversize must remain a 413");
  assert(uncooperativeCancelCalled, "oversize handling must still request upstream body cancellation");

  const stalledTextBody = new ReadableStream<Uint8Array>({
    pull: () => new Promise<void>(() => undefined)
  });
  const stalledTextKeepAlive = setInterval(() => undefined, 10);
  const stalledTextResult = await requestBody
    .readBoundedTextBody(
      new Request("https://field.example/api/navigation/walking", {
        method: "POST",
        body: stalledTextBody,
        duplex: "half"
      } as RequestInit & { duplex: "half" }),
      1024,
      5
    )
    .finally(() => clearInterval(stalledTextKeepAlive));
  assert(stalledTextResult.error?.status === 408, "a stalled generic request body must terminate at its deadline");

  let textCancelCalled = false;
  const uncooperativeTextBody = new ReadableStream<Uint8Array>({
    start: (controller) => controller.enqueue(new Uint8Array(5)),
    cancel: () => {
      textCancelCalled = true;
      return new Promise<void>(() => undefined);
    }
  });
  const textOversizeTimeout = Symbol("text-oversize-timeout");
  const textOversize = await Promise.race([
    requestBody.readBoundedTextBody(
      new Request("https://field.example/api/navigation/walking", {
        method: "POST",
        body: uncooperativeTextBody,
        duplex: "half"
      } as RequestInit & { duplex: "half" }),
      4,
      100
    ),
    new Promise<typeof textOversizeTimeout>((resolve) => setTimeout(() => resolve(textOversizeTimeout), 200))
  ]);
  assert(textOversize !== textOversizeTimeout, "an uncooperative cancel must not delay the generic 413 response");
  assert(textOversize.error?.status === 413, "streamed generic oversize must return 413");
  assert(textCancelCalled, "generic oversize handling must request upstream cancellation");

  const loginAbort = new AbortController();
  const stalledLoginBody = new ReadableStream<Uint8Array>({
    pull: () => new Promise<void>(() => undefined)
  });
  const abortedLogin = fieldSession.POST(
    new Request("https://field.example/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": "203.0.113.9"
      },
      body: stalledLoginBody,
      duplex: "half",
      signal: loginAbort.signal
    } as RequestInit & { duplex: "half" })
  );
  setTimeout(() => loginAbort.abort(), 5);
  assert((await abortedLogin).status === 499, "the production login route must release a cancelled body read");

  const anonymousRequest = new Request("https://field.example/api/detect/v2");
  const anonymousDenied = proxy.authorizeProxyRequest(anonymousRequest, "field");
  assert(anonymousDenied?.status === 401, "field proxy must reject an anonymous request");

  for (const [label, headers] of [
    ["missing", { "content-type": "application/json" }],
    ["invalid", { "content-type": "application/json", "cf-connecting-ip": "not-an-ip" }],
    ["multiple", { "content-type": "application/json", "cf-connecting-ip": "203.0.113.1, 198.51.100.1" }]
  ] as const) {
    const untrustedLogin = await fieldSession.POST(
      new Request("https://field.example/api/field-session", {
        method: "POST",
        headers,
        body: JSON.stringify({ token: process.env.WALKSAFE_FIELD_TEST_TOKEN })
      })
    );
    const untrustedBody = (await untrustedLogin.json()) as { code?: string };
    assert(untrustedLogin.status === 400, `${label} trusted client IP must fail closed before credential checking`);
    assert(
      untrustedBody.code === "gateway_trusted_client_ip_required",
      `${label} trusted client IP must not enter a shared limiter bucket`
    );
  }

  const badLogin = await fieldSession.POST(
    new Request("https://field.example/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-forwarded-proto": "https",
        "cf-connecting-ip": "203.0.113.10"
      },
      body: JSON.stringify({ token: "wrong" })
    })
  );
  assert(badLogin.status === 401, "wrong field token must be rejected");

  const fieldLogin = await fieldSession.POST(
    new Request("https://field.example/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-forwarded-proto": "https",
        "cf-connecting-ip": "203.0.113.10"
      },
      body: JSON.stringify({ token: process.env.WALKSAFE_FIELD_TEST_TOKEN })
    })
  );
  const fieldSetCookie = fieldLogin.headers.get("set-cookie") ?? "";
  assert(fieldLogin.status === 204, "valid field token must create a session");
  assert(fieldSetCookie.includes("HttpOnly"), "field cookie must be HttpOnly");
  assert(fieldSetCookie.includes("SameSite=Strict"), "field cookie must use SameSite=Strict");
  assert(fieldSetCookie.includes("Secure"), "HTTPS field cookie must be Secure");
  assert(!fieldSetCookie.includes(process.env.WALKSAFE_FIELD_TEST_TOKEN), "raw field token must not enter the cookie");

  const fieldRequest = new Request("https://field.example/api/detect/v2", {
    headers: { cookie: cookiePair(fieldSetCookie) }
  });
  assert(auth.isFieldSessionAuthorized(fieldRequest), "derived field cookie must authorize the session");
  assert(proxy.authorizeProxyRequest(fieldRequest, "field") === null, "field session must pass field proxy gate");
  assert(proxy.authorizeProxyRequest(fieldRequest, "admin")?.status === 403, "field session must not pass admin proxy gate");
  const fieldHeaders = proxy.proxyRequestHeaders(fieldRequest, "field");
  assert(
    fieldHeaders.get(proxy.FIELD_TEST_TOKEN_HEADER) === process.env.WALKSAFE_FIELD_TEST_TOKEN,
    "field token must only be attached on the server-side backend request"
  );
  assert(fieldHeaders.get("x-walksafe-actor-id") === "field-shared", "field actor must reach backend audit headers");
  const fieldAssertion = fieldHeaders.get(proxy.ACTOR_ASSERTION_HEADER) ?? "";
  const [, fieldUnix, fieldSignature] = fieldAssertion.split(".");
  assert(fieldAssertion.startsWith("v1."), "field actor must carry a versioned backend assertion");
  assert(
    fieldSignature ===
      createHmac("sha256", process.env.WALKSAFE_GATEWAY_SESSION_SECRET)
        .update(`walksafe-backend-actor-v1:field:field-shared:${fieldUnix}`)
        .digest("base64url"),
    "field assertion must bind role, actor and timestamp"
  );
  assert(!fieldHeaders.has(proxy.ADMIN_TOKEN_HEADER), "field request must not receive admin token");
  const voiceForm = new FormData();
  voiceForm.set("audio", new Blob([new Uint8Array([1, 2, 3])], { type: "audio/wav" }), "sample.wav");
  let voiceUpstreamHeaders = new Headers();
  let voiceRedirect: RequestRedirect | undefined;
  try {
    globalThis.fetch = (async (_input: string | URL | Request, init?: RequestInit) => {
      voiceUpstreamHeaders = new Headers(init?.headers);
      voiceRedirect = init?.redirect;
      return Response.json({ transcript: "신고해", intent: "create_report" });
    }) as typeof fetch;
    const voiceResponse = await voiceStt.POST(
      new Request("https://field.example/api/speech/stt", {
        method: "POST",
        headers: { cookie: cookiePair(fieldSetCookie), "cf-connecting-ip": "203.0.113.50" },
        body: voiceForm
      })
    );
    assert(voiceResponse.status === 200, "authenticated field session must reach the Voice BFF route");
  } finally {
    globalThis.fetch = originalFetch;
  }
  assert(
    voiceUpstreamHeaders.get(proxy.VOICE_SERVICE_TOKEN_HEADER) === process.env.VOICE_SERVICE_TOKEN,
    "Voice BFF must attach the dedicated service token"
  );
  assert(voiceUpstreamHeaders.get(proxy.ACTOR_ID_HEADER) === "field-shared", "Voice BFF must forward the session actor");
  assert(
    voiceUpstreamHeaders.get(proxy.VOICE_CLIENT_IP_HEADER) === "203.0.113.50",
    "Voice BFF must only forward the configured trusted client IP header"
  );
  assert(
    !voiceUpstreamHeaders.has(proxy.FIELD_TEST_TOKEN_HEADER) && !voiceUpstreamHeaders.has(proxy.ADMIN_TOKEN_HEADER),
    "Voice BFF must not reuse backend field/admin credentials"
  );
  assert(voiceRedirect === "error", "Voice BFF must not forward its service token across an upstream redirect");

  const firstVoiceAbort = new AbortController();
  let markFirstVoiceStarted!: () => void;
  const firstVoiceStarted = new Promise<void>((resolve) => {
    markFirstVoiceStarted = resolve;
  });
  let concurrentVoiceUpstreamCalls = 0;
  try {
    globalThis.fetch = ((
      _input: string | URL | Request,
      init?: RequestInit
    ): Promise<Response> => {
      concurrentVoiceUpstreamCalls += 1;
      if (concurrentVoiceUpstreamCalls === 1) {
        markFirstVoiceStarted();
        return new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener(
            "abort",
            () => reject(new DOMException("aborted", "AbortError")),
            { once: true }
          );
        });
      }
      return Promise.resolve(Response.json({ transcript: "신고해", intent: "create_report" }));
    }) as typeof fetch;

    const firstConcurrentForm = new FormData();
    firstConcurrentForm.set("audio", new Blob([new Uint8Array([1])], { type: "audio/wav" }), "first.wav");
    const firstConcurrentVoice = voiceStt.POST(
      new Request("https://field.example/api/speech/stt", {
        method: "POST",
        headers: { cookie: cookiePair(fieldSetCookie), "cf-connecting-ip": "203.0.113.60" },
        body: firstConcurrentForm,
        signal: firstVoiceAbort.signal
      })
    );
    await firstVoiceStarted;

    const secondConcurrentForm = new FormData();
    secondConcurrentForm.set("audio", new Blob([new Uint8Array([2])], { type: "audio/wav" }), "second.wav");
    const secondConcurrentRequest = new Request("https://field.example/api/speech/stt", {
      method: "POST",
      headers: { cookie: cookiePair(fieldSetCookie), "cf-connecting-ip": "203.0.113.61" },
      body: secondConcurrentForm
    });
    const secondBody = secondConcurrentRequest.body;
    assert(secondBody !== null, "second concurrent Voice request must carry a body");
    const originalGetReader = secondBody.getReader.bind(secondBody);
    let secondBodyRead = false;
    Object.defineProperty(secondBody, "getReader", {
      configurable: true,
      value: () => {
        secondBodyRead = true;
        return originalGetReader();
      }
    });
    const busyVoice = await voiceStt.POST(secondConcurrentRequest);
    assert(busyVoice.status === 503, "a second Voice upload must be rejected before buffering");
    assert(busyVoice.headers.get("retry-after") === "1", "busy Voice admission must provide retry guidance");
    assert(!secondBodyRead, "busy Voice admission must not open the second multipart body reader");
    assert(concurrentVoiceUpstreamCalls === 1, "busy Voice admission must not reach the Voice service");

    firstVoiceAbort.abort();
    const cancelledVoice = await firstConcurrentVoice;
    assert(cancelledVoice.status === 499, "client cancellation must release the active Voice upload slot");

    const replacementForm = new FormData();
    replacementForm.set("audio", new Blob([new Uint8Array([3])], { type: "audio/wav" }), "replacement.wav");
    const replacementVoice = await voiceStt.POST(
      new Request("https://field.example/api/speech/stt", {
        method: "POST",
        headers: { cookie: cookiePair(fieldSetCookie), "cf-connecting-ip": "203.0.113.62" },
        body: replacementForm
      })
    );
    assert(replacementVoice.status === 200, "a replacement Voice upload must start after cancellation cleanup");
    assert(Number(concurrentVoiceUpstreamCalls) === 2, "only the active and replacement Voice uploads may reach upstream");
  } finally {
    firstVoiceAbort.abort();
    globalThis.fetch = originalFetch;
  }

  const bodyReadAbort = new AbortController();
  let markBodyReadStarted!: () => void;
  const bodyReadStarted = new Promise<void>((resolve) => {
    markBodyReadStarted = resolve;
  });
  const stalledVoiceBody = new ReadableStream<Uint8Array>({
    pull: () => {
      markBodyReadStarted();
      return new Promise<void>(() => undefined);
    }
  });
  try {
    globalThis.fetch = (async () => Response.json({ transcript: "신고해", intent: "create_report" })) as typeof fetch;
    const stalledVoiceRequest = new Request(
      "https://field.example/api/speech/stt",
      {
        method: "POST",
        headers: {
          cookie: cookiePair(fieldSetCookie),
          "cf-connecting-ip": "203.0.113.70",
          "content-type": "multipart/form-data; boundary=stalled-route"
        },
        body: stalledVoiceBody,
        signal: bodyReadAbort.signal,
        duplex: "half"
      } as RequestInit & { duplex: "half" }
    );
    const stalledVoice = voiceStt.POST(stalledVoiceRequest);
    await bodyReadStarted;
    bodyReadAbort.abort();
    const stalledVoiceResponse = await stalledVoice;
    assert(stalledVoiceResponse.status === 499, "body-read cancellation must return before its multipart deadline");

    const postAbortForm = new FormData();
    postAbortForm.set("audio", new Blob([new Uint8Array([4])], { type: "audio/wav" }), "post-abort.wav");
    const postAbortReplacement = await voiceStt.POST(
      new Request("https://field.example/api/speech/stt", {
        method: "POST",
        headers: { cookie: cookiePair(fieldSetCookie), "cf-connecting-ip": "203.0.113.71" },
        body: postAbortForm
      })
    );
    assert(postAbortReplacement.status === 200, "body-read cancellation must release the route admission slot");
  } finally {
    bodyReadAbort.abort();
    globalThis.fetch = originalFetch;
  }

  let routeOversizeCancelCalled = false;
  let routeOversizeUpstreamCalls = 0;
  try {
    globalThis.fetch = (async () => {
      routeOversizeUpstreamCalls += 1;
      return Response.json({ transcript: "신고해", intent: "create_report" });
    }) as typeof fetch;
    const routeOversizeBody = new ReadableStream<Uint8Array>({
      start: (controller) => controller.enqueue(new Uint8Array(proxy.AUDIO_MULTIPART_LIMIT_BYTES + 1)),
      cancel: () => {
        routeOversizeCancelCalled = true;
        return new Promise<void>(() => undefined);
      }
    });
    const routeOversizeTimeout = Symbol("route-oversize-timeout");
    const routeOversizeResult = await Promise.race([
      voiceStt.POST(
        new Request(
          "https://field.example/api/speech/stt",
          {
            method: "POST",
            headers: {
              cookie: cookiePair(fieldSetCookie),
              "cf-connecting-ip": "203.0.113.72",
              "content-type": "multipart/form-data; boundary=oversize-route"
            },
            body: routeOversizeBody,
            duplex: "half"
          } as RequestInit & { duplex: "half" }
        )
      ),
      new Promise<typeof routeOversizeTimeout>((resolve) =>
        setTimeout(() => resolve(routeOversizeTimeout), 500)
      )
    ]);
    assert(routeOversizeResult !== routeOversizeTimeout, "oversize Voice route must not wait for stream cancel");
    assert(routeOversizeResult.status === 413, "oversize Voice route must return the bounded 413 response");
    assert(routeOversizeCancelCalled, "oversize Voice route must request body cancellation");
    assert(routeOversizeUpstreamCalls === 0, "oversize Voice body must not reach the Voice service");

    const routeOversizeReplacementForm = new FormData();
    routeOversizeReplacementForm.set(
      "audio",
      new Blob([new Uint8Array([5])], { type: "audio/wav" }),
      "post-oversize.wav"
    );
    const routeOversizeReplacement = await voiceStt.POST(
      new Request("https://field.example/api/speech/stt", {
        method: "POST",
        headers: { cookie: cookiePair(fieldSetCookie), "cf-connecting-ip": "203.0.113.73" },
        body: routeOversizeReplacementForm
      })
    );
    assert(routeOversizeReplacement.status === 200, "oversize 413 must release the Voice admission slot");
    assert(Number(routeOversizeUpstreamCalls) === 1, "only the post-oversize replacement may reach Voice upstream");
  } finally {
    globalThis.fetch = originalFetch;
  }

  const rateHeaders = new Headers({
    [proxy.ACTOR_ID_HEADER]: "voice-rate-test-actor",
    [proxy.VOICE_CLIENT_IP_HEADER]: "203.0.113.90"
  });
  const rateNow = Date.now();
  for (let index = 0; index < 12; index += 1) {
    const admitted = proxy.acquireVoiceSttUploadAdmission(rateHeaders, rateNow);
    assert(!admitted.error, "the configured per-actor Voice rate must admit its exact allowance");
    admitted.release();
    admitted.release();
  }
  const voiceActorRateLimited = proxy.acquireVoiceSttUploadAdmission(rateHeaders, rateNow);
  assert(voiceActorRateLimited.error?.status === 429, "the next same-window actor request must be rate limited");
  assert(voiceActorRateLimited.error?.headers.get("retry-after") === "60", "Voice rate limit must expose its window retry");
  const afterRateWindow = proxy.acquireVoiceSttUploadAdmission(rateHeaders, rateNow + 60_001);
  assert(!afterRateWindow.error, "expired Voice rate events must be pruned");
  afterRateWindow.release();

  const ipRateNow = rateNow + 120_001;
  const sharedIp = "203.0.113.91";
  for (let index = 0; index < 30; index += 1) {
    const admitted = proxy.acquireVoiceSttUploadAdmission(
      new Headers({
        [proxy.ACTOR_ID_HEADER]: `voice-ip-actor-${index}`,
        [proxy.VOICE_CLIENT_IP_HEADER]: sharedIp
      }),
      ipRateNow
    );
    assert(!admitted.error, "distinct actors must be admitted up to the configured per-IP allowance");
    admitted.release();
  }
  const voiceIpRateLimited = proxy.acquireVoiceSttUploadAdmission(
    new Headers({
      [proxy.ACTOR_ID_HEADER]: "voice-ip-actor-overflow",
      [proxy.VOICE_CLIENT_IP_HEADER]: sharedIp
    }),
    ipRateNow
  );
  assert(voiceIpRateLimited.error?.status === 429, "the next same-window IP request must be rate limited");

  const globalRateNow = rateNow + 240_002;
  for (let index = 0; index < 120; index += 1) {
    const admitted = proxy.acquireVoiceSttUploadAdmission(
      new Headers({
        [proxy.ACTOR_ID_HEADER]: `voice-global-actor-${index}`,
        [proxy.VOICE_CLIENT_IP_HEADER]: `2001:db8::${index + 1}`
      }),
      globalRateNow
    );
    assert(!admitted.error, "rotating actors and IPs must be admitted only up to the global allowance");
    admitted.release();
  }
  const voiceGlobalRateLimited = proxy.acquireVoiceSttUploadAdmission(
    new Headers({
      [proxy.ACTOR_ID_HEADER]: "voice-global-overflow",
      [proxy.VOICE_CLIENT_IP_HEADER]: "2001:db8::ffff"
    }),
    globalRateNow
  );
  assert(voiceGlobalRateLimited.error?.status === 429, "the global bucket must stop rotating rate keys");

  const stalledMultipart = new ReadableStream<Uint8Array>({
    pull: () => new Promise<void>(() => undefined)
  });
  const stalledMultipartRequest = new Request(
    "https://field.example/api/speech/stt",
    {
      method: "POST",
      headers: { "content-type": "multipart/form-data; boundary=stalled" },
      body: stalledMultipart,
      duplex: "half"
    } as RequestInit & { duplex: "half" }
  );
  const stalledMultipartKeepAlive = setInterval(() => undefined, 10);
  const stalledMultipartResult = await proxy
    .readBoundedMultipartFormData(stalledMultipartRequest, 1024, 5)
    .finally(() => clearInterval(stalledMultipartKeepAlive));
  assert(
    stalledMultipartResult.error?.status === 408,
    "a stalled multipart body must release admission through a bounded read deadline"
  );

  const configuredVoiceToken = process.env.VOICE_SERVICE_TOKEN;
  delete process.env.VOICE_SERVICE_TOKEN;
  const unavailableVoiceForm = new FormData();
  unavailableVoiceForm.set("audio", new Blob([new Uint8Array([1])], { type: "audio/wav" }), "sample.wav");
  const unavailableVoice = await voiceStt.POST(
    new Request("https://field.example/api/speech/stt", {
      method: "POST",
      headers: { cookie: cookiePair(fieldSetCookie) },
      body: unavailableVoiceForm
    })
  );
  assert(unavailableVoice.status === 503, "Voice BFF must fail closed when its dedicated token is absent");
  process.env.VOICE_SERVICE_TOKEN = configuredVoiceToken;
  process.env.VOICE_SERVICE_TOKEN = process.env.WALKSAFE_FIELD_TEST_TOKEN;
  assert(proxy.voiceProxyRequestHeaders(fieldRequest) === null, "Voice BFF must reject reuse of the backend field token");
  process.env.VOICE_SERVICE_TOKEN = configuredVoiceToken;
  const emailAssertion = proxy.createBackendActorAssertion("admin", "operator@example.com", 1783728000);
  assert(
    emailAssertion ===
      `v1.1783728000.${createHmac("sha256", process.env.WALKSAFE_GATEWAY_SESSION_SECRET)
        .update("walksafe-backend-actor-v1:admin:operator@example.com:1783728000")
        .digest("base64url")}`,
    "email-style actor IDs must use the same signed backend contract"
  );

  const adminLogin = await adminSession.POST(
    new Request("http://localhost/api/admin-session", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": "127.0.0.1" },
      body: JSON.stringify({ token: process.env.WALKSAFE_ADMIN_TOKEN })
    })
  );
  const adminSetCookie = adminLogin.headers.get("set-cookie") ?? "";
  assert(adminLogin.status === 204, "valid admin token must create a session");
  assert(!adminSetCookie.includes("Secure"), "localhost HTTP cookie must remain usable without Secure");
  assert(!adminSetCookie.includes(process.env.WALKSAFE_ADMIN_TOKEN), "raw admin token must not enter the cookie");

  const adminRequest = new Request("http://localhost/api/reports", {
    headers: { cookie: cookiePair(adminSetCookie) }
  });
  assert(proxy.authorizeProxyRequest(adminRequest, "admin") === null, "admin session must pass admin proxy gate");
  assert(proxy.authorizeProxyRequest(adminRequest, "field")?.status === 403, "admin session must not impersonate a field user");
  const adminHeaders = proxy.proxyRequestHeaders(adminRequest, "admin");
  assert(adminHeaders.get(proxy.ADMIN_TOKEN_HEADER) === process.env.WALKSAFE_ADMIN_TOKEN, "admin backend header must be attached");
  assert(!adminHeaders.has(proxy.FIELD_TEST_TOKEN_HEADER), "admin request must not also attach field token");
  assert(adminHeaders.get("x-walksafe-actor-id") === "admin-shared", "admin actor must reach backend audit headers");
  const adminAssertion = adminHeaders.get(proxy.ACTOR_ASSERTION_HEADER) ?? "";
  const [, adminUnix, adminSignature] = adminAssertion.split(".");
  assert(
    adminSignature ===
      createHmac("sha256", process.env.WALKSAFE_GATEWAY_SESSION_SECRET)
        .update(`walksafe-backend-actor-v1:admin:admin-shared:${adminUnix}`)
        .digest("base64url"),
    "admin assertion must not be reusable as a field assertion"
  );

  const reportListRoute = await import("../app/api/reports/route");
  const reportDetailRoute = await import("../app/api/reports/[reportId]/route");
  const reportSummaryRoute = await import("../app/api/reports/summary/route");
  const reportDuplicateCheckRoute = await import("../app/api/reports/duplicate-check/route");
  const reportImageRoute = await import("../app/api/uploads/[...path]/route");
  const sensitiveReads: Array<{ url: string; headers: Headers }> = [];
  try {
    globalThis.fetch = (async (input: string | URL | Request, init?: RequestInit) => {
      sensitiveReads.push({ url: String(input), headers: new Headers(init?.headers) });
      return new Response(String(input).includes("/uploads/") ? new Uint8Array([1, 2, 3]) : "{}", {
        headers: { "content-type": String(input).includes("/uploads/") ? "image/jpeg" : "application/json" }
      });
    }) as typeof fetch;
    await (
      await reportListRoute.GET(adminRequest)
    ).arrayBuffer();
    await (
      await reportDetailRoute.GET(
        new Request("http://localhost/api/reports/11111111-1111-4111-8111-111111111111", {
          headers: { cookie: cookiePair(adminSetCookie) }
        }),
        { params: Promise.resolve({ reportId: "11111111-1111-4111-8111-111111111111" }) }
      )
    ).arrayBuffer();
    await (
      await reportSummaryRoute.GET(
        new Request("http://localhost/api/reports/summary", {
          headers: { cookie: cookiePair(adminSetCookie) }
        })
      )
    ).arrayBuffer();
    await (
      await reportImageRoute.GET(
        new Request("http://localhost/api/uploads/report-01.jpg", {
          headers: { cookie: cookiePair(adminSetCookie) }
        }),
        { params: Promise.resolve({ path: ["report-01.jpg"] }) }
      )
    ).arrayBuffer();
    await (
      await reportDuplicateCheckRoute.GET(
        new Request(
          "http://localhost/api/reports/duplicate-check?class_name=pothole&captured_at=2026-07-16T00%3A00%3A00Z&lat=37.5&lng=127",
          { headers: { cookie: cookiePair(adminSetCookie) } }
        )
      )
    ).arrayBuffer();
  } finally {
    globalThis.fetch = originalFetch;
  }
  assert(sensitiveReads.length === 5, "each sensitive admin read must reach the backend once");
  assert(
    sensitiveReads.map(({ headers }) => headers.get("x-walksafe-read-purpose")).join(",") ===
      "admin_report_list,admin_report_detail,admin_report_summary,admin_report_image,admin_report_duplicate_check",
    "report list, detail, summary, image, and duplicate-check routes must carry distinct durable audit purposes"
  );
  assert(
    sensitiveReads.every(({ headers }) => headers.get("x-walksafe-actor-id") === "admin-shared"),
    "every sensitive read purpose must remain bound to the authenticated admin actor"
  );

  const dualSessionRequest = new Request("https://field.example/api/reports", {
    headers: { cookie: `${cookiePair(fieldSetCookie)}; ${cookiePair(adminSetCookie)}` }
  });
  assert(proxy.authorizeProxyRequest(dualSessionRequest, "field") === null, "field access must survive an admin cookie");
  assert(proxy.authorizeProxyRequest(dualSessionRequest, "admin") === null, "admin access must survive a field cookie");
  assert(
    proxy.proxyRequestHeaders(dualSessionRequest, "field").has(proxy.FIELD_TEST_TOKEN_HEADER),
    "dual-cookie field proxy must forward only the field credential"
  );
  assert(
    proxy.proxyRequestHeaders(dualSessionRequest, "admin").has(proxy.ADMIN_TOKEN_HEADER),
    "dual-cookie admin proxy must forward only the admin credential"
  );

  const fieldStatus = await fieldSession.GET(fieldRequest);
  const fieldStatusBody = (await fieldStatus.json()) as { authenticated: boolean };
  assert(fieldStatusBody.authenticated, "field session status must recognize a valid cookie");
  const adminAsFieldStatus = await fieldSession.GET(adminRequest);
  const adminAsFieldStatusBody = (await adminAsFieldStatus.json()) as { authenticated: boolean };
  assert(!adminAsFieldStatusBody.authenticated, "admin cookie must not satisfy field session status");

  const burstAddress = "198.51.100.76";
  const burstResponses = await Promise.all(
    Array.from({ length: 12 }, () =>
      fieldSession.POST(
        new Request("https://field.example/api/field-session", {
          method: "POST",
          headers: { "content-type": "application/json", "cf-connecting-ip": burstAddress },
          body: JSON.stringify({ token: "wrong" })
        })
      )
    )
  );
  assert(
    burstResponses.filter((response) => response.status === 401).length === 5,
    "parallel login failures must not exceed the atomic five-attempt budget"
  );
  assert(
    burstResponses.filter((response) => response.status === 429).length === 7,
    "parallel attempts after the fifth failure must be rate limited"
  );

  const rateLimitAddress = "198.51.100.77";
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const rejected = await fieldSession.POST(
      new Request("https://field.example/api/field-session", {
        method: "POST",
        headers: { "content-type": "application/json", "cf-connecting-ip": rateLimitAddress },
        body: JSON.stringify({ token: "wrong" })
      })
    );
    assert(rejected.status === 401, `failed login ${attempt + 1} must be rejected`);
  }
  const rateLimited = await fieldSession.POST(
    new Request("https://field.example/api/field-session", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": rateLimitAddress },
      body: JSON.stringify({ token: process.env.WALKSAFE_FIELD_TEST_TOKEN })
    })
  );
  assert(rateLimited.status === 429, "the sixth login attempt from one address must be rate limited");
  assert(Number(rateLimited.headers.get("retry-after")) > 0, "rate limit response must include Retry-After");

  const blockedBodyAbort = new AbortController();
  const blockedBody = new ReadableStream<Uint8Array>({
    pull: () => new Promise<void>(() => undefined)
  });
  const blockedBodyRequest = fieldSession.POST(
    new Request("https://field.example/api/field-session", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": rateLimitAddress },
      body: blockedBody,
      duplex: "half",
      signal: blockedBodyAbort.signal
    } as RequestInit & { duplex: "half" })
  );
  const blockedBeforeBody = await Promise.race([
    blockedBodyRequest,
    new Promise<null>((resolve) => setTimeout(() => resolve(null), 100))
  ]);
  if (!blockedBeforeBody) blockedBodyAbort.abort();
  assert(blockedBeforeBody?.status === 429, "a blocked client must be rejected before its stalled body is read");

  const authModulePath = require.resolve("../app/api/_gateway-auth");
  delete require.cache[authModulePath];
  const restartedAuth = require(authModulePath) as typeof auth;
  const restartRateLimited = await restartedAuth.gatewayLoginRateLimitResponse(
    "field",
    new Request("https://field.example/api/field-session", {
      headers: { "cf-connecting-ip": rateLimitAddress }
    })
  );
  assert(restartRateLimited?.status === 429, "login attempt limits must survive a Web process module restart");
  assert(
    (await auth.gatewayLoginRateLimitResponse(
      "field",
      new Request("https://field.example/api/field-session", {
        headers: { "cf-connecting-ip": "198.51.100.78" }
      })
    )) === null,
    "one client address must not rate limit a different trusted client address"
  );
  assert(
    (await readdir(process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR)).some((name) => name.endsWith(".log")),
    "failed login history must be persisted outside process memory"
  );
  const expiredRateFile = path.join(
    process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR,
    `field-${createHash("sha256").update(`field:${rateLimitAddress}`).digest("hex")}.log`
  );
  await utimes(expiredRateFile, new Date(0), new Date(0));
  const pruneClock = Date.now;
  Date.now = () => pruneClock() + 21 * 60 * 1000;
  try {
    await auth.gatewayLoginRateLimitResponse(
      "field",
      new Request("https://field.example/api/field-session", {
        headers: { "cf-connecting-ip": "198.51.100.79" }
      })
    );
  } finally {
    Date.now = pruneClock;
  }
  assert(
    !(await readdir(process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR)).includes(path.basename(expiredRateFile)),
    "expired attempt files for clients that never return must be pruned"
  );

  let releaseHeldLoginLock: () => void = () => undefined;
  let loginLockAcquired: () => void = () => undefined;
  const loginLockWasAcquired = new Promise<void>((resolve) => {
    loginLockAcquired = resolve;
  });
  const heldLoginLock = auth.withGatewayLoginLock(
    async () => {
      loginLockAcquired();
      await new Promise<void>((resolve) => {
        releaseHeldLoginLock = resolve;
      });
      return new Response(null, { status: 204 });
    },
    auth.gatewayLoginBusyResponse
  );
  await loginLockWasAcquired;
  const forcedRelease = setTimeout(() => releaseHeldLoginLock(), 1500);
  const lockWaitStartedAt = Date.now();
  const busyLogin = await auth.withGatewayLoginLock(
    async () => new Response(null, { status: 204 }),
    auth.gatewayLoginBusyResponse
  );
  const lockWaitElapsedMs = Date.now() - lockWaitStartedAt;
  clearTimeout(forcedRelease);
  releaseHeldLoginLock();
  await heldLoginLock;
  assert(busyLogin.status === 503, "a saturated login-state lock must fail closed instead of waiting indefinitely");
  assert(lockWaitElapsedMs < 1400, "login-state lock wait must have a bounded deadline");

  const primaryRateLimitDirectory = process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR;
  const globalRateLimitDirectory = await mkdtemp(path.join(os.tmpdir(), "walksafe-gateway-global-rate-test-"));
  process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = globalRateLimitDirectory;
  for (let attempt = 0; attempt < 120; attempt += 1) {
    const response = await fieldSession.POST(
      new Request("https://field.example/api/field-session", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "cf-connecting-ip": `198.18.${Math.floor(attempt / 250)}.${(attempt % 250) + 1}`
        },
        body: JSON.stringify({ token: "wrong" })
      })
    );
    assert(response.status === 401, `global login allowance ${attempt + 1} must admit the bounded attempt`);
  }
  const globalRateLimited = await fieldSession.POST(
    new Request("https://field.example/api/field-session", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": "198.19.0.1" },
      body: JSON.stringify({ token: process.env.WALKSAFE_FIELD_TEST_TOKEN })
    })
  );
  assert(globalRateLimited.status === 429, "rotating trusted addresses must not bypass the durable global login bucket");

  const capacityDirectory = await mkdtemp(path.join(os.tmpdir(), "walksafe-gateway-capacity-test-"));
  await Promise.all(
    Array.from({ length: 256 }, async (_value, index) => {
      const digest = createHash("sha256").update(`capacity-${index}`).digest("hex");
      await writeFile(path.join(capacityDirectory, `field-${digest}.log`), `${Date.now()}\n`, { mode: 0o600 });
    })
  );
  process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = capacityDirectory;
  const capacityRejected = await auth.gatewayLoginRateLimitResponse(
    "field",
    new Request("https://field.example/api/field-session", {
      headers: { "cf-connecting-ip": "203.0.113.252" }
    })
  );
  assert(capacityRejected?.status === 503, "login attempt file cardinality must have a hard fail-closed cap");
  process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = primaryRateLimitDirectory;
  await Promise.all([
    rm(globalRateLimitDirectory, { recursive: true, force: true }),
    rm(capacityDirectory, { recursive: true, force: true })
  ]);

  const logout = await fieldSession.DELETE(fieldRequest);
  assert(logout.status === 204, "field logout must succeed");
  assert(logout.headers.get("set-cookie")?.includes("Max-Age=0") === true, "logout must expire the session cookie");
  assert(!auth.isFieldSessionAuthorized(fieldRequest), "logout must revoke replay of the previous field cookie server-side");
  assert(proxy.authorizeProxyRequest(fieldRequest, "field")?.status === 401, "a logged-out field cookie must not reach the proxy");

  const originalNow = Date.now;
  Date.now = () => originalNow() + 13 * 60 * 60 * 1000;
  try {
    assert(!auth.isFieldSessionAuthorized(fieldRequest), "field session must expire server-side after its bounded lifetime");
  } finally {
    Date.now = originalNow;
  }

  const fieldToken = process.env.WALKSAFE_FIELD_TEST_TOKEN;
  const adminToken = process.env.WALKSAFE_ADMIN_TOKEN;
  delete process.env.WALKSAFE_FIELD_TEST_TOKEN;
  delete process.env.WALKSAFE_ADMIN_TOKEN;
  delete process.env.WALKSAFE_ALLOW_INSECURE_LOCAL_DEV;
  assert(
    proxy.authorizeProxyRequest(new Request("http://localhost/api/health"), "field")?.status === 503,
    "missing tokens must fail closed by default"
  );
  process.env.WALKSAFE_ALLOW_INSECURE_LOCAL_DEV = "true";
  assert(
    proxy.authorizeProxyRequest(new Request("http://localhost/api/health"), "field") === null,
    "local bypass must require an explicit opt-in"
  );
  process.env.WALKSAFE_FIELD_TEST_TOKEN = fieldToken;
  process.env.WALKSAFE_ADMIN_TOKEN = adminToken;

  process.env.WALKSAFE_GATEWAY_SESSION_SECRET = "session-secret-for-account-tests-1234567890";
  process.env.WALKSAFE_ADMIN_ACCOUNTS_JSON = JSON.stringify([
    { actor_id: "operator.kim", token: "operator-kim-token-for-tests-123456" },
    { actor_id: "operator.lee", token: "operator-lee-token-for-tests-123456" }
  ]);
  const missingActorLogin = await adminSession.POST(
    new Request("https://field.example/api/admin-session", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.40" },
      body: JSON.stringify({ token: "operator-kim-token-for-tests-123456" })
    })
  );
  assert(missingActorLogin.status === 401, "configured account login must require its actor id");
  const accountLogin = await adminSession.POST(
    new Request("https://field.example/api/admin-session", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.41" },
      body: JSON.stringify({ actor_id: "operator.kim", token: "operator-kim-token-for-tests-123456" })
    })
  );
  assert(accountLogin.status === 204, "valid named admin account must create a session");
  const accountCookie = cookiePair(accountLogin.headers.get("set-cookie") ?? "");
  const accountRequest = new Request("https://field.example/api/reports", { headers: { cookie: accountCookie } });
  const accountStatus = await adminSession.GET(accountRequest);
  const accountStatusBody = (await accountStatus.json()) as { actor_id?: string; authenticated?: boolean };
  assert(accountStatusBody.authenticated === true, "named account session must authenticate");
  assert(accountStatusBody.actor_id === "operator.kim", "named account session must retain its audit actor");
  assert(
    proxy.proxyRequestHeaders(accountRequest, "admin").get("x-walksafe-actor-id") === "operator.kim",
    "named account actor must be forwarded to the backend"
  );

  process.env.WALKSAFE_ADMIN_ACCOUNTS_JSON = JSON.stringify([
    { actor_id: "anonymous", token: "anonymous-operator-token-for-tests-123456" }
  ]);
  assert(
    auth.verifyGatewayCredential("admin", "anonymous", "anonymous-operator-token-for-tests-123456") === null,
    "reserved anonymous identities must not become named operator accounts"
  );
  process.env.WALKSAFE_ADMIN_ACCOUNTS_JSON = JSON.stringify([
    { actor_id: "operator.kim", token: "operator-kim-token-for-tests-123456" },
    { actor_id: "operator.lee", token: "operator-lee-token-for-tests-123456" }
  ]);

  const secureRateLimitDirectory = process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR;
  const insecureDirectory = await mkdtemp(path.join(os.tmpdir(), "walksafe-gateway-insecure-"));
  await chmod(insecureDirectory, 0o755);
  process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = insecureDirectory;
  let insecureDirectoryRejected = false;
  try {
    await auth.gatewayLoginRateLimitResponse(
      "field",
      new Request("https://field.example/api/field-session", { headers: { "cf-connecting-ip": "203.0.113.60" } })
    );
  } catch {
    insecureDirectoryRejected = true;
  }
  assert(insecureDirectoryRejected, "world-readable rate-limit directories must fail closed");

  const symlinkTarget = await mkdtemp(path.join(os.tmpdir(), "walksafe-gateway-target-"));
  const symlinkDirectory = `${symlinkTarget}-link`;
  await symlink(symlinkTarget, symlinkDirectory, "dir");
  process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = symlinkDirectory;
  let symlinkRejected = false;
  try {
    await auth.gatewayLoginRateLimitResponse(
      "field",
      new Request("https://field.example/api/field-session", { headers: { "cf-connecting-ip": "203.0.113.61" } })
    );
  } catch {
    symlinkRejected = true;
  }
  assert(symlinkRejected, "symlinked rate-limit directories must fail closed");
  process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = secureRateLimitDirectory;
  await Promise.all([
    rm(insecureDirectory, { recursive: true, force: true }),
    rm(symlinkDirectory, { force: true }),
    rm(symlinkTarget, { recursive: true, force: true })
  ]);

  const originalNodeEnv = process.env.NODE_ENV;
  const originalFieldAccounts = process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  const originalSessionSecret = process.env.WALKSAFE_GATEWAY_SESSION_SECRET;
  Object.assign(process.env, { NODE_ENV: "production" });
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  delete process.env.WALKSAFE_GATEWAY_SESSION_SECRET;
  assert(
    !auth.isGatewayAccessConfigured("field"),
    "production must not treat the backend shared field token as a browser login account"
  );
  assert(
    auth.verifyGatewayCredential("field", "field-shared", fieldToken ?? "") === null,
    "production shared-token fallback must fail closed"
  );
  process.env.WALKSAFE_FIELD_ACCOUNTS_JSON = JSON.stringify([
    { actor_id: "tester.kim", token: "tester-kim-browser-token-for-tests-123456" }
  ]);
  assert(!auth.isGatewayAccessConfigured("field"), "production named accounts still require a separate session secret");
  process.env.WALKSAFE_GATEWAY_SESSION_SECRET = "tester-kim-browser-token-for-tests-123456";
  assert(!auth.isGatewayAccessConfigured("field"), "session secret must not reuse a named account credential");
  process.env.WALKSAFE_GATEWAY_SESSION_SECRET = "independent-production-session-secret-1234567890";
  assert(auth.isGatewayAccessConfigured("field"), "named accounts plus an independent session secret should configure production");
  assert(
    auth.verifyGatewayCredential("field", "tester.kim", "tester-kim-browser-token-for-tests-123456") === "tester.kim",
    "production named account credential should remain usable"
  );
  const sharedRoleToken = "cross-role-browser-token-for-tests-123456";
  process.env.WALKSAFE_FIELD_ACCOUNTS_JSON = JSON.stringify([
    { actor_id: "tester.kim", token: sharedRoleToken }
  ]);
  process.env.WALKSAFE_ADMIN_ACCOUNTS_JSON = JSON.stringify([
    { actor_id: "operator.kim", token: sharedRoleToken }
  ]);
  assert(
    !auth.isGatewayAccessConfigured("field") && !auth.isGatewayAccessConfigured("admin"),
    "production must reject one browser credential reused across field and admin roles"
  );
  assert(
    auth.verifyGatewayCredential("field", "tester.kim", sharedRoleToken) === null &&
      auth.verifyGatewayCredential("admin", "operator.kim", sharedRoleToken) === null,
    "a cross-role browser credential must not authenticate either role"
  );
  process.env.WALKSAFE_FIELD_ACCOUNTS_JSON = JSON.stringify([
    { actor_id: "tester.kim", token: adminToken }
  ]);
  process.env.WALKSAFE_ADMIN_ACCOUNTS_JSON = JSON.stringify([
    { actor_id: "operator.kim", token: "operator-kim-browser-token-for-tests-123456" }
  ]);
  assert(
    !auth.isGatewayAccessConfigured("field"),
    "a browser credential must not reuse the other role's internal service token"
  );
  process.env.WALKSAFE_FIELD_ACCOUNTS_JSON = JSON.stringify([
    { actor_id: "tester.kim", token: "tester-kim-browser-token-for-tests-123456" }
  ]);
  const productionMissingIp = await fieldSession.POST(
    new Request("https://field.example/api/field-session", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ actor_id: "tester.kim", token: "tester-kim-browser-token-for-tests-123456" })
    })
  );
  assert(productionMissingIp.status === 400, "production login route must reject requests outside the trusted edge contract");
  const productionLogin = await fieldSession.POST(
    new Request("https://field.example/api/field-session", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.90" },
      body: JSON.stringify({ actor_id: "tester.kim", token: "tester-kim-browser-token-for-tests-123456" })
    })
  );
  assert(productionLogin.status === 204, "production login route must accept one valid edge-overwritten client IP");
  process.env.WALKSAFE_FIELD_ACCOUNTS_JSON = JSON.stringify([
    { actor_id: "tester.kim", token: fieldToken }
  ]);
  assert(!auth.isGatewayAccessConfigured("field"), "browser account token must not reuse the backend service credential");
  process.env.WALKSAFE_FIELD_ACCOUNTS_JSON = JSON.stringify([
    { actor_id: "tester.kim", token: "tester-kim-browser-token-for-tests-123456" }
  ]);
  process.env.WALKSAFE_ALLOW_INSECURE_LOCAL_DEV = "true";
  assert(!auth.isInsecureLocalGatewayBypassAllowed(), "local-dev bypass must remain disabled in production");
  if (originalNodeEnv === undefined) Reflect.deleteProperty(process.env, "NODE_ENV");
  else Object.assign(process.env, { NODE_ENV: originalNodeEnv });
  if (originalFieldAccounts === undefined) delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  else process.env.WALKSAFE_FIELD_ACCOUNTS_JSON = originalFieldAccounts;
  if (originalSessionSecret === undefined) delete process.env.WALKSAFE_GATEWAY_SESSION_SECRET;
  else process.env.WALKSAFE_GATEWAY_SESSION_SECRET = originalSessionSecret;
  delete process.env.WALKSAFE_ALLOW_INSECURE_LOCAL_DEV;

  console.log("field-test gateway policy checks passed");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
