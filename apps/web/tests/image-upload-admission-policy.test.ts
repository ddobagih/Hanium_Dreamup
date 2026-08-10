import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

type PostRoute = { POST: (request: Request) => Promise<Response> };
type SessionRoute = { POST: (request: Request) => Promise<Response> };

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function cookiePair(setCookie: string): string {
  return setCookie.split(";", 1)[0];
}

async function withTimeout<T>(promise: Promise<T>, message: string, timeoutMs = 1_000): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      promise,
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => reject(new Error(message)), timeoutMs);
      })
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

function imageRequest(url: string, cookie: string, clientIp: string, byte = 1, signal?: AbortSignal): Request {
  const form = new FormData();
  form.set("image", new Blob([new Uint8Array([byte])], { type: "image/jpeg" }), `frame-${byte}.jpg`);
  return new Request(url, {
    method: "POST",
    headers: { cookie, "cf-connecting-ip": clientIp },
    body: form,
    signal
  });
}

function streamingImageRequest(
  url: string,
  cookie: string,
  clientIp: string,
  body: ReadableStream<Uint8Array>,
  signal?: AbortSignal
): Request {
  return new Request(
    url,
    {
      method: "POST",
      headers: {
        cookie,
        "cf-connecting-ip": clientIp,
        "content-type": "multipart/form-data; boundary=image-admission-test"
      },
      body,
      signal,
      duplex: "half"
    } as RequestInit & { duplex: "half" }
  );
}

function observeBodyReader(request: Request): { opened: () => boolean; waitUntilOpened: Promise<void> } {
  const body = request.body;
  assert(body !== null, "image request must carry a body");
  const originalGetReader = body.getReader.bind(body);
  let bodyReaderOpened = false;
  let markOpened!: () => void;
  const waitUntilOpened = new Promise<void>((resolve) => {
    markOpened = resolve;
  });
  Object.defineProperty(body, "getReader", {
    configurable: true,
    value: () => {
      bodyReaderOpened = true;
      markOpened();
      return originalGetReader();
    }
  });
  return { opened: () => bodyReaderOpened, waitUntilOpened };
}

async function login(
  route: SessionRoute,
  url: string,
  actorId: string,
  token: string,
  clientIp: string
): Promise<string> {
  const response = await route.POST(
    new Request(url, {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": clientIp },
      body: JSON.stringify({ actor_id: actorId, token })
    })
  );
  assert(response.status === 204, `session login failed for ${actorId}: ${response.status}`);
  const cookie = cookiePair(response.headers.get("set-cookie") ?? "");
  assert(cookie.includes("="), `session login did not issue a cookie for ${actorId}`);
  return cookie;
}

async function main() {
  const rateLimitDirectory = await mkdtemp(path.join(os.tmpdir(), "walksafe-image-admission-test-"));
  const fieldActors = ["image-busy", "image-upstream", "image-rotation", "image-oversize", "image-error"];
  const fieldTokens = new Map(
    fieldActors.map((actor, index) => [actor, `named-field-image-token-${index}-12345678901234567890`])
  );

  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    WALKSAFE_WEB_START_MODE: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    VOICE_API_BASE_URL: "http://127.0.0.1:9001",
    WALKSAFE_FIELD_TEST_TOKEN: "internal-field-image-token-12345678901234567890",
    WALKSAFE_ADMIN_TOKEN: "internal-admin-image-token-12345678901234567890",
    WALKSAFE_GATEWAY_SESSION_SECRET: "image-admission-session-secret-12345678901234567890",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: rateLimitDirectory
  });
  process.env.WALKSAFE_FIELD_ACCOUNTS_JSON = JSON.stringify(
    fieldActors.map((actor) => ({ actor_id: actor, token: fieldTokens.get(actor) }))
  );
  const adminActor = "image-admin";
  const adminToken = "named-admin-image-token-12345678901234567890";
  process.env.WALKSAFE_ADMIN_ACCOUNTS_JSON = JSON.stringify([{ actor_id: adminActor, token: adminToken }]);

  const backend = await import("../app/api/_backend");
  const fieldSession = await import("../app/api/field-session/route");
  const adminSession = await import("../app/api/admin-session/route");
  const detect = await import("../app/api/detect/route");
  const detectV2 = await import("../app/api/detect/v2/route");
  const reports = await import("../app/api/reports/route");
  const reportsV2 = await import("../app/api/reports/v2/route");

  const fieldCookies = new Map<string, string>();
  for (const [index, actor] of fieldActors.entries()) {
    fieldCookies.set(
      actor,
      await login(
        fieldSession,
        "http://localhost/api/field-session",
        actor,
        fieldTokens.get(actor) ?? "",
        `198.18.0.${index + 1}`
      )
    );
  }
  const adminCookie = await login(
    adminSession,
    "http://localhost/api/admin-session",
    adminActor,
    adminToken,
    "198.18.0.100"
  );

  const originalFetch = globalThis.fetch;
  const successfulFetch = (async () => Response.json({ ok: true })) as typeof fetch;
  globalThis.fetch = successfulFetch;
  try {
    const unauthorizedRequest = imageRequest(
      "https://field.example/api/detect",
      "",
      "203.0.113.1"
    );
    const unauthorizedBody = observeBodyReader(unauthorizedRequest);
    const unauthorized = await detect.POST(unauthorizedRequest);
    assert(unauthorized.status === 401, "image admission must run only after field authentication");
    assert(!unauthorizedBody.opened(), "authentication failure must not open the multipart body reader");

    const busyCookie = fieldCookies.get("image-busy") ?? "";
    const stalledAbort = new AbortController();
    const stalledBody = new ReadableStream<Uint8Array>({
      pull: () => new Promise<void>(() => undefined)
    });
    const stalledRequest = streamingImageRequest(
      "https://field.example/api/detect",
      busyCookie,
      "203.0.113.10",
      stalledBody,
      stalledAbort.signal
    );
    const stalledReader = observeBodyReader(stalledRequest);
    const stalledUpload = detect.POST(stalledRequest);
    try {
      await withTimeout(stalledReader.waitUntilOpened, "the first image route did not open its body reader");

      const unaffectedGet = await reports.GET(
        new Request("https://field.example/api/reports", {
          headers: { cookie: adminCookie, "cf-connecting-ip": "203.0.113.11" }
        })
      );
      assert(unaffectedGet.status === 200, "reports GET must not share image upload admission");

      const busyRequest = imageRequest(
        "https://field.example/api/reports",
        busyCookie,
        "203.0.113.12",
        2
      );
      const busyBody = observeBodyReader(busyRequest);
      const busy = await reports.POST(busyRequest);
      assert(busy.status === 503, "a second image route must fail while the shared upload slot is held");
      assert(busy.headers.get("cache-control") === "no-store", "busy image response must be non-cacheable");
      assert(busy.headers.get("retry-after") === "1", "busy image response must include Retry-After");
      assert(!busyBody.opened(), "busy admission must return before opening another route's body reader");
    } finally {
      stalledAbort.abort();
    }
    const cancelledStalled = await withTimeout(stalledUpload, "stalled image body did not observe cancellation");
    assert(cancelledStalled.status === 499, "aborted image body read must return the bounded cancellation response");
    const postAbort = await detectV2.POST(
      imageRequest("https://field.example/api/detect/v2", busyCookie, "203.0.113.13", 3)
    );
    assert(postAbort.status === 200, "body-read abort must release the shared image slot");

    const upstreamCookie = fieldCookies.get("image-upstream") ?? "";
    const upstreamAbort = new AbortController();
    let markUpstreamStarted!: () => void;
    const upstreamStarted = new Promise<void>((resolve) => {
      markUpstreamStarted = resolve;
    });
    globalThis.fetch = ((
      _input: string | URL | Request,
      init?: RequestInit
    ): Promise<Response> => {
      markUpstreamStarted();
      return new Promise<Response>((_resolve, reject) => {
        const signal = init?.signal;
        if (signal?.aborted) {
          reject(signal.reason);
          return;
        }
        signal?.addEventListener("abort", () => reject(signal.reason), { once: true });
      });
    }) as typeof fetch;
    const upstreamUpload = detectV2.POST(
      imageRequest(
        "https://field.example/api/detect/v2",
        upstreamCookie,
        "203.0.113.20",
        4,
        upstreamAbort.signal
      )
    );
    try {
      await withTimeout(upstreamStarted, "image upload did not reach the upstream request");
      const upstreamBusyRequest = imageRequest(
        "https://field.example/api/reports",
        upstreamCookie,
        "203.0.113.21",
        5
      );
      const upstreamBusyBody = observeBodyReader(upstreamBusyRequest);
      const upstreamBusy = await reports.POST(upstreamBusyRequest);
      assert(upstreamBusy.status === 503, "image slot must remain held until the upstream request ends");
      assert(!upstreamBusyBody.opened(), "upstream-wait busy response must precede multipart body reads");
    } finally {
      upstreamAbort.abort();
    }
    const upstreamCancelled = await withTimeout(upstreamUpload, "aborted upstream image request did not finish");
    assert(upstreamCancelled.status === 499, "client abort during image upstream wait must be bounded");
    globalThis.fetch = successfulFetch;
    const postUpstreamAbort = await reportsV2.POST(
      imageRequest("https://field.example/api/reports/v2", upstreamCookie, "203.0.113.22", 6)
    );
    assert(postUpstreamAbort.status === 200, "upstream abort must release the image upload slot");

    const rotationCookie = fieldCookies.get("image-rotation") ?? "";
    const imageRoutes: Array<[string, PostRoute]> = [
      ["detect", detect],
      ["detect/v2", detectV2],
      ["reports", reports],
      ["reports/v2", reportsV2]
    ];
    for (let index = 0; index < 12; index += 1) {
      const [routePath, route] = imageRoutes[index % imageRoutes.length];
      const admitted = await route.POST(
        imageRequest(
          `https://field.example/api/${routePath}`,
          rotationCookie,
          `203.0.113.${30 + index}`,
          10 + index
        )
      );
      assert(admitted.status === 200, "actor allowance must be shared successfully across all image endpoints");
    }
    const rotationOverflowRequest = imageRequest(
      "https://field.example/api/detect",
      rotationCookie,
      "203.0.113.50",
      30
    );
    const rotationOverflowBody = observeBodyReader(rotationOverflowRequest);
    const rotationOverflow = await detect.POST(rotationOverflowRequest);
    assert(rotationOverflow.status === 429, "rotating image endpoints must not bypass the common actor rate cap");
    assert(rotationOverflow.headers.get("cache-control") === "no-store", "image rate response must be non-cacheable");
    assert(Number(rotationOverflow.headers.get("retry-after")) > 0, "image rate response must include Retry-After");
    assert(!rotationOverflowBody.opened(), "actor rate rejection must happen before multipart body reads");

    const oversizeCookie = fieldCookies.get("image-oversize") ?? "";
    let oversizeCancelCalled = false;
    const oversizeBody = new ReadableStream<Uint8Array>({
      start: (controller) => controller.enqueue(new Uint8Array(backend.IMAGE_MULTIPART_LIMIT_BYTES + 1)),
      cancel: () => {
        oversizeCancelCalled = true;
        return new Promise<void>(() => undefined);
      }
    });
    const oversize = await withTimeout(
      reports.POST(
        streamingImageRequest(
          "https://field.example/api/reports",
          oversizeCookie,
          "203.0.113.60",
          oversizeBody
        )
      ),
      "oversize image upload waited for an uncooperative stream cancellation"
    );
    assert(oversize.status === 413, "oversize image body must return 413");
    assert(oversizeCancelCalled, "oversize image body must request stream cancellation");
    const postOversize = await reportsV2.POST(
      imageRequest("https://field.example/api/reports/v2", oversizeCookie, "203.0.113.61", 31)
    );
    assert(postOversize.status === 200, "oversize image body must release the shared slot");

    const errorCookie = fieldCookies.get("image-error") ?? "";
    globalThis.fetch = (async () => {
      throw new Error("upstream unavailable");
    }) as typeof fetch;
    const upstreamError = await detect.POST(
      imageRequest("https://field.example/api/detect", errorCookie, "203.0.113.70", 32)
    );
    assert(upstreamError.status === 502, "image upstream error must remain a bounded gateway response");
    globalThis.fetch = successfulFetch;
    const postUpstreamError = await detectV2.POST(
      imageRequest("https://field.example/api/detect/v2", errorCookie, "203.0.113.71", 33)
    );
    assert(postUpstreamError.status === 200, "image upstream error must release the shared slot");

    const ipRateNow = Date.now() + 60_001;
    const sharedIp = "198.51.100.200";
    for (let index = 0; index < 30; index += 1) {
      const admitted = backend.acquireImageUploadAdmission(
        new Request("https://field.example/api/detect", {
          headers: { "cf-connecting-ip": sharedIp }
        }),
        new Headers({ [backend.ACTOR_ID_HEADER]: `image-ip-actor-${index}` }),
        ipRateNow
      );
      assert(!admitted.error, "distinct actors must be admitted up to the common image IP allowance");
      admitted.release();
    }
    const ipRateLimited = backend.acquireImageUploadAdmission(
      new Request("https://field.example/api/reports/v2", {
        headers: { "cf-connecting-ip": sharedIp }
      }),
      new Headers({ [backend.ACTOR_ID_HEADER]: "image-ip-overflow" }),
      ipRateNow
    );
    assert(ipRateLimited.error?.status === 429, "image IP rate cap must reject the next shared-address upload");
    assert(ipRateLimited.error?.headers.get("cache-control") === "no-store", "IP rate response must be non-cacheable");
    assert(ipRateLimited.error?.headers.get("retry-after") === "60", "IP rate response must expose its window");

    const globalRateNow = ipRateNow + 60_001;
    for (let index = 0; index < 120; index += 1) {
      const admitted = backend.acquireImageUploadAdmission(
        new Request("https://field.example/api/detect", {
          headers: { "cf-connecting-ip": `198.51.100.${index + 1}` }
        }),
        new Headers({ [backend.ACTOR_ID_HEADER]: `image-global-actor-${index}` }),
        globalRateNow
      );
      assert(!admitted.error, "rotating image actors and IPs must be admitted only to the global allowance");
      admitted.release();
    }
    const globalRateLimited = backend.acquireImageUploadAdmission(
      new Request("https://field.example/api/reports", {
        headers: { "cf-connecting-ip": "198.51.100.250" }
      }),
      new Headers({ [backend.ACTOR_ID_HEADER]: "image-global-overflow" }),
      globalRateNow
    );
    assert(globalRateLimited.error?.status === 429, "rotating actors and IPs must not bypass the image global cap");
    assert(globalRateLimited.error?.headers.get("cache-control") === "no-store", "global rate response must be non-cacheable");
    assert(globalRateLimited.error?.headers.get("retry-after") === "60", "global rate response must expose its window");
  } finally {
    globalThis.fetch = originalFetch;
    await rm(rateLimitDirectory, { recursive: true, force: true });
  }

  console.log("image upload admission policy checks passed");
}

void main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
