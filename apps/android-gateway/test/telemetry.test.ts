import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";

import {
  handleGatewayRequest,
  PUBLIC_GATEWAY_ROUTES
} from "../src/routes.js";
import {
  GATEWAY_ROUTE_TEMPLATES,
  GATEWAY_TELEMETRY_MAX_LATENCY_MS,
  GATEWAY_TELEMETRY_SCHEMA,
  gatewayRouteTemplate,
  observeGatewayResponseDelivery,
  recordGatewayRequestFinished,
  recordGatewayRequestPrepared,
  type GatewayRouteTemplate,
  type GatewayTelemetryEvent
} from "../src/telemetry.js";

const CANONICAL_REQUEST_ID = "018f2b63-8fb8-7cc2-98a1-4a4fd27c3001";
const GENERATED_UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

test("S19-54 telemetry only classifies RAW chunk indexes accepted by routing", () => {
  const id = "3f2b8c1e-4d5a-4b6c-8d7e-9f0a1b2c3d4e";
  const path = (index: number) =>
    `http://gateway.invalid/api/raw-collections/${id}/objects/${id}/chunks/${index}`;
  assert.equal(gatewayRouteTemplate(new Request(path(2047))),
    "/api/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}");
  assert.equal(gatewayRouteTemplate(new Request(path(2048))), null);
});

// R19-GB-01: walking and report v2 routes run the walk-ownership gate; keep this
// file's ledger and login locks out of shared defaults or the source checkout.
let stateDirectory = "";
let previousLedgerPath: string | undefined;
let previousRateLimitDirectory: string | undefined;

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-telemetry-test-"));
  previousLedgerPath = process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH;
  previousRateLimitDirectory = process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR;
  process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH = path.join(stateDirectory, "field-walk-ledger.json");
  process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = stateDirectory;
});

after(async () => {
  if (previousLedgerPath === undefined) delete process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH;
  else process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH = previousLedgerPath;
  if (previousRateLimitDirectory === undefined) delete process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR;
  else process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = previousRateLimitDirectory;
  await rm(stateDirectory, { recursive: true, force: true });
});

type RouteCase = {
  template: GatewayRouteTemplate;
  request: () => Request;
};

function allowedRouteCases(secret: string, deletionId: string): RouteCase[] {
  const headers = {
    authorization: `Bearer ${secret}`,
    "x-request-id": CANONICAL_REQUEST_ID
  };
  return [
    ...["GET", "POST", "PUT"].map(method => ({
      template: "/api/field-diagnostics/preference",
      request: () => new Request("http://127.0.0.1:8081/api/field-diagnostics/preference", {
        method, headers
      })
    })),
    {
      template: "/api/field-diagnostics/sessions/{session_id}/batches",
      request: () => new Request("http://127.0.0.1:8081/api/field-diagnostics/sessions/11111111-1111-4111-8111-111111111111/batches", {
        method: "POST", headers
      })
    },
    {
      template: "/api/field-diagnostics/sessions/{session_id}",
      request: () => new Request("http://127.0.0.1:8081/api/field-diagnostics/sessions/11111111-1111-4111-8111-111111111111", { headers })
    },
    {
      template: "/api/account-signup-policy",
      request: () => new Request("http://127.0.0.1:8081/api/account-signup-policy", { headers })
    },
    {
      template: "/api/account-enrollments/email-otp",
      request: () => new Request(
        "http://127.0.0.1:8081/api/account-enrollments/email-otp",
        {
          method: "POST",
          headers: { ...headers, "content-type": "application/json" },
          body: JSON.stringify({
            schema_version: "walksafe.account-enrollment-email-otp.v1",
            email: secret,
            date_of_birth: "2000-01-01",
            request_id: deletionId
          })
        }
      )
    },
    {
      template: "/api/accounts",
      request: () => new Request("http://127.0.0.1:8081/api/accounts", {
        method: "POST",
        headers: { ...headers, "content-type": "application/json" },
        body: JSON.stringify({ password: secret, enrollment_handle: secret })
      })
    },
    {
      template: "/api/field-session",
      request: () => new Request("http://127.0.0.1:8081/api/field-session", { headers })
    },
    {
      template: "/api/field-session",
      request: () => new Request("http://127.0.0.1:8081/api/field-session", {
        method: "POST",
        headers: { ...headers, "content-type": "application/json" },
        body: JSON.stringify({ actor_id: secret, token: secret })
      })
    },
    {
      template: "/api/field-session",
      request: () => new Request("http://127.0.0.1:8081/api/field-session", {
        method: "DELETE",
        headers
      })
    },
    {
      template: "/api/field-walk",
      request: () => new Request("http://127.0.0.1:8081/api/field-walk", { headers })
    },
    {
      template: "/api/field-walk",
      request: () => new Request("http://127.0.0.1:8081/api/field-walk", {
        method: "POST",
        headers: { ...headers, "content-type": "application/json" },
        body: JSON.stringify({ request_id: secret })
      })
    },
    {
      template: "/api/speech/stt",
      request: () => new Request("http://127.0.0.1:8081/api/speech/stt", {
        method: "POST",
        headers: { ...headers, "content-type": "audio/wav" },
        body: secret
      })
    },
    {
      template: "/api/speech/tts",
      request: () => new Request("http://127.0.0.1:8081/api/speech/tts", {
        method: "POST",
        headers: { ...headers, "content-type": "application/json" },
        body: JSON.stringify({
          schema_version: "walksafe.speech-tts-request.v1",
          text: secret,
          request_id: CANONICAL_REQUEST_ID
        })
      })
    },
    {
      template: "/api/navigation/walking",
      request: () => new Request("http://127.0.0.1:8081/api/navigation/walking", {
        method: "POST",
        headers: { ...headers, "content-type": "application/json" },
        body: JSON.stringify({ start_x: secret, start_y: secret, token: secret })
      })
    },
    {
      template: "/api/navigation/destinations/search",
      request: () => new Request(
        `http://127.0.0.1:8081/api/navigation/destinations/search?query=${encodeURIComponent(secret)}`,
        { headers }
      )
    },
    {
      template: "/api/reports/v2",
      request: () => new Request("http://127.0.0.1:8081/api/reports/v2", {
        method: "POST",
        headers,
        body: secret
      })
    },
    {
      template: "/api/reports/v2/{report_id}/status",
      request: () => new Request(
        "http://127.0.0.1:8081/api/reports/v2/11111111-1111-4111-8111-111111111111/status",
        { headers }
      )
    },
    {
      template: "/api/reports/mine",
      request: () => new Request("http://127.0.0.1:8081/api/reports/mine", { headers })
    },
    {
      template: "/api/reports/mine/requests/history",
      request: () => new Request(
        "http://127.0.0.1:8081/api/reports/mine/requests/history?limit=10&cursor=opaque",
        { headers }
      )
    },
    {
      template: "/api/reports/mine/deletions/{request_id}",
      request: () => new Request(
        "http://127.0.0.1:8081/api/reports/mine/deletions/22222222-2222-4222-8222-222222222222",
        { headers }
      )
    },
    {
      template: "/api/reports/mine/{report_id}",
      request: () => new Request(
        "http://127.0.0.1:8081/api/reports/mine/11111111-1111-4111-8111-111111111111",
        { headers }
      )
    },
    {
      template: "/api/reports/mine/{report_id}/content",
      request: () => new Request(
        "http://127.0.0.1:8081/api/reports/mine/11111111-1111-4111-8111-111111111111/content",
        { headers }
      )
    },
    {
      template: "/api/reports/mine/{report_id}/corrections",
      request: () => new Request(
        "http://127.0.0.1:8081/api/reports/mine/11111111-1111-4111-8111-111111111111/corrections",
        {
          method: "POST",
          headers: { ...headers, "content-type": "application/json" },
          body: JSON.stringify({
            expected_revision: 0,
            idempotency_key: CANONICAL_REQUEST_ID,
            user_description: secret
          })
        }
      )
    },
    {
      template: "/api/reports/mine/{report_id}/requests",
      request: () => new Request(
        "http://127.0.0.1:8081/api/reports/mine/11111111-1111-4111-8111-111111111111/requests",
        {
          method: "POST",
          headers: { ...headers, "content-type": "application/json" },
          body: JSON.stringify({ client_request_id: deletionId })
        }
      )
    },
    {
      template: "/api/reports/mine/{report_id}/requests/{request_id}",
      request: () => new Request(
        "http://127.0.0.1:8081/api/reports/mine/11111111-1111-4111-8111-111111111111/requests/22222222-2222-4222-8222-222222222222",
        { headers }
      )
    },
    {
      template: "/api/raw-collections/{collection_id}/manifest",
      request: () => new Request(
        "http://127.0.0.1:8081/api/raw-collections/00000000-0000-0000-0000-000000000001/manifest",
        { method: "PUT", headers }
      )
    },
    {
      template: "/api/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}",
      request: () => new Request(
        "http://127.0.0.1:8081/api/raw-collections/00000000-0000-0000-0000-000000000001/objects/00000000-0000-0000-0000-000000000002/chunks/2047",
        { method: "PUT", headers }
      )
    },
    {
      template: "/api/raw-collections/{collection_id}",
      request: () => new Request(
        "http://127.0.0.1:8081/api/raw-collections/00000000-0000-0000-0000-000000000001",
        { headers }
      )
    },
    {
      template: "/api/raw-collections/{collection_id}/commit",
      request: () => new Request(
        "http://127.0.0.1:8081/api/raw-collections/00000000-0000-0000-0000-000000000001/commit",
        { method: "POST", headers }
      )
    },
    {
      template: "/privacy/rights",
      request: () => new Request(
        "http://127.0.0.1:8081/privacy/rights", { method: "HEAD", headers }
      )
    },
    {
      template: "/privacy/rights",
      request: () => new Request(
        "http://127.0.0.1:8081/privacy/rights?control=installation-evidence-key", {
          method: "PUT", headers: { ...headers, "content-type": "application/json" },
          body: JSON.stringify({ installation_id: deletionId, public_key: secret })
        }
      )
    },
    {
      template: "/privacy/rights",
      request: () => new Request(
        `http://127.0.0.1:8081/privacy/rights?token=${encodeURIComponent(secret)}`,
        { headers }
      )
    },
    {
      template: "/privacy/rights",
      request: () => new Request(
        "http://127.0.0.1:8081/privacy/rights?control=integrated-consent",
        {
          method: "PUT",
          headers: { ...headers, "content-type": "application/json" },
          body: JSON.stringify({ account_id: secret, token: secret })
        }
      )
    },
    {
      template: "/privacy/account-deletions",
      request: () => new Request("http://127.0.0.1:8081/privacy/account-deletions", {
        method: "POST",
        headers: { ...headers, "content-type": "application/json" },
        body: JSON.stringify({ request_id: deletionId, account_id: secret })
      })
    },
    {
      template: "/privacy/account-deletions/{request_id}/status",
      request: () => new Request(
        `http://127.0.0.1:8081/privacy/account-deletions/${deletionId}/status`,
        { headers }
      )
    },
    {
      template: "/privacy/account-deletions/{request_id}/device-evidence",
      request: () => new Request(
        `http://127.0.0.1:8081/privacy/account-deletions/${deletionId}/device-evidence`,
        {
          method: "POST",
          headers: { ...headers, "content-type": "application/json" },
          body: JSON.stringify({ request_id: deletionId, installation_id: secret })
        }
      )
    }
  ];
}

test("every allowed method emits one bounded completion event and propagates request id", async () => {
  const previousNodeEnv = process.env.NODE_ENV;
  const previousFieldToken = process.env.WALKSAFE_FIELD_TEST_TOKEN;
  const previousAccounts = process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  const previousPrivacyUrl = process.env.WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL;
  const secret = "raw-secret-token-password-pii@example.org";
  const deletionId = "DeletionRequestIdSensitive0001";
  const events: GatewayTelemetryEvent[] = [];
  process.env.NODE_ENV = "test";
  delete process.env.WALKSAFE_FIELD_TEST_TOKEN;
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  delete process.env.WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL;
  try {
    const cases = allowedRouteCases(secret, deletionId);
    assert.deepEqual(PUBLIC_GATEWAY_ROUTES, GATEWAY_ROUTE_TEMPLATES);
    assert.deepEqual(
      new Set(cases.map(item => item.template)),
      new Set(GATEWAY_ROUTE_TEMPLATES)
    );
    for (const item of cases) {
      const response = await handleGatewayRequest(item.request(), {
        telemetrySink: event => events.push(event)
      });
      assert.equal(response.headers.get("x-request-id"), CANONICAL_REQUEST_ID);
    }

    // S19-56: one "prepared" event per request; "finished" follows body delivery.
    const prepared = events.filter(event => event.event_name === "walksafe.gateway.request.prepared");
    assert.equal(prepared.length, cases.length);
    assert.deepEqual(
      prepared.map(event => event.route_template),
      cases.map(item => item.template)
    );
  } finally {
    if (previousNodeEnv === undefined) delete process.env.NODE_ENV;
    else process.env.NODE_ENV = previousNodeEnv;
    if (previousFieldToken === undefined) delete process.env.WALKSAFE_FIELD_TEST_TOKEN;
    else process.env.WALKSAFE_FIELD_TEST_TOKEN = previousFieldToken;
    if (previousAccounts === undefined) delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
    else process.env.WALKSAFE_FIELD_ACCOUNTS_JSON = previousAccounts;
    if (previousPrivacyUrl === undefined) delete process.env.WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL;
    else process.env.WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL = previousPrivacyUrl;
  }

  for (const event of events) {
    assert.equal(event.schema_version, GATEWAY_TELEMETRY_SCHEMA);
    assert.ok(
      (event.event_name === "walksafe.gateway.request.prepared" && event.outcome === "prepared") ||
      (event.event_name === "walksafe.gateway.request.finished" && event.outcome !== "prepared")
    );
    assert.equal(event.correlation_id, CANONICAL_REQUEST_ID);
    assert.ok(Number.isInteger(event.http_status));
    assert.ok(event.http_status >= 200 && event.http_status <= 599);
    assert.ok(Number.isInteger(event.latency_ms));
    assert.ok(event.latency_ms >= 0 && event.latency_ms <= GATEWAY_TELEMETRY_MAX_LATENCY_MS);
    assert.equal(event.is_5xx, event.http_status >= 500);
    assert.equal(event.capacity_level, "UNKNOWN");
    assert.deepEqual(Object.keys(event).sort(), [
      "capacity_level",
      "correlation_id",
      "event_name",
      "first_byte_ms",
      "http_status",
      "is_5xx",
      "latency_ms",
      "outcome",
      "route_template",
      "schema_version"
    ]);
  }
  assert.doesNotMatch(
    JSON.stringify(events),
    /raw-secret|password|example\.org|authorization|DeletionRequestIdSensitive|start_x|account_id/i
  );
});

test("invalid inbound request id is replaced and the event uses the response id", async () => {
  const events: GatewayTelemetryEvent[] = [];
  const invalidId = "account-identifier@example.org";
  const response = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/privacy/rights", {
      headers: { "x-request-id": invalidId }
    }),
    { telemetrySink: event => events.push(event) }
  );

  const responseId = response.headers.get("x-request-id") ?? "";
  assert.match(responseId, GENERATED_UUID);
  assert.equal(events.length, 1);
  assert.equal(events[0]!.event_name, "walksafe.gateway.request.prepared");
  assert.equal(events[0]!.correlation_id, responseId);
  assert.doesNotMatch(JSON.stringify(events), /account-identifier|example\.org/);
});

test("completion latency is integer-clamped and 5xx is explicit", () => {
  const events: GatewayTelemetryEvent[] = [];
  const response = Response.json({}, { status: 503 });
  recordGatewayRequestPrepared(
    CANONICAL_REQUEST_ID,
    "/privacy/rights",
    response,
    -10.4,
    "UNKNOWN",
    event => events.push(event)
  );
  recordGatewayRequestPrepared(
    CANONICAL_REQUEST_ID,
    "/privacy/rights",
    response,
    Number.POSITIVE_INFINITY,
    "UNKNOWN",
    event => events.push(event)
  );

  assert.deepEqual(events.map(event => event.latency_ms), [0, GATEWAY_TELEMETRY_MAX_LATENCY_MS]);
  assert.deepEqual(events.map(event => event.is_5xx), [true, true]);
});

test("telemetry sink failure does not change the gateway response contract", async () => {
  const response = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/privacy/rights"),
    { privacyRightsRequestUrl: "", telemetrySink: () => { throw new Error("monitor unavailable"); } }
  );

  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), {
    code: "privacy_rights_channel_unavailable"
  });
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.match(response.headers.get("x-request-id") ?? "", GENERATED_UUID);
});

test("S19-56 telemetry separates response preparation from delivery completion", async () => {
  const events: GatewayTelemetryEvent[] = [];
  const response = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/privacy/rights", {
      headers: { "x-request-id": CANONICAL_REQUEST_ID }
    }),
    { privacyRightsRequestUrl: "", telemetrySink: event => events.push(event) }
  );
  // Preparing the Response object is not delivery.
  assert.deepEqual(
    events.map(event => event.event_name),
    ["walksafe.gateway.request.prepared"]
  );
  await response.json();
  assert.deepEqual(
    events.map(event => [event.event_name, (event as { outcome?: string }).outcome]),
    [
      ["walksafe.gateway.request.prepared", "prepared"],
      ["walksafe.gateway.request.finished", "completed"]
    ]
  );
  assert.ok(GATEWAY_TELEMETRY_MAX_LATENCY_MS >= 65_000, "TTS 65 s must not be clipped");
});

test("S19-56 delivery outcomes: stream failure after headers, consumer abort, unclipped 70 s latency", async () => {
  const outcomes: Array<[string, number, number | null]> = [];
  let clock = 1_000;
  const record = (outcome: string, elapsedMs: number, firstByteMs: number | null) => {
    outcomes.push([outcome, elapsedMs, firstByteMs]);
  };
  // A normal response object whose body fails after the first chunk.
  let pulls = 0;
  const failing = new Response(new ReadableStream<Uint8Array>({
    pull(controller) {
      pulls += 1;
      clock += 5;
      if (pulls === 1) controller.enqueue(new TextEncoder().encode("{\"partial\":"));
      else controller.error(new Error("upstream stream failed"));
    }
  }), { status: 200, headers: { "content-type": "application/json" } });
  const observedFailing = observeGatewayResponseDelivery(failing, 1_000, record, () => clock);
  assert.equal(observedFailing.status, 200);
  assert.equal(outcomes.length, 0, "status 200 alone is not a completed delivery");
  await assert.rejects(observedFailing.text());
  assert.equal(outcomes.length, 1);
  assert.equal(outcomes[0]![0], "stream_error");
  assert.equal(outcomes[0]![1], 10);
  // The first byte reached the consumer before the failure.
  assert.ok(outcomes[0]![2] !== null && outcomes[0]![2]! > 0 && outcomes[0]![2]! <= 10);

  // The transport (node-adapter writeWebResponse) cancels the body when the client closes.
  outcomes.length = 0;
  let sourceCancelled: unknown = null;
  const streaming = new Response(new ReadableStream<Uint8Array>({
    pull(controller) { controller.enqueue(new Uint8Array([1, 2, 3])); },
    cancel(reason) { sourceCancelled = reason; }
  }));
  const observedStreaming = observeGatewayResponseDelivery(streaming, 1_000, record, () => clock);
  const reader = observedStreaming.body!.getReader();
  await reader.read();
  await reader.cancel("client response socket closed");
  assert.equal(sourceCancelled, "client response socket closed");
  assert.deepEqual(outcomes.map(entry => entry[0]), ["aborted"]);

  // Bodyless responses finish immediately; completed bodies finish once.
  outcomes.length = 0;
  observeGatewayResponseDelivery(new Response(null, { status: 204 }), 1_000, record, () => clock);
  const complete = observeGatewayResponseDelivery(Response.json({ ok: true }), 1_000, record, () => clock);
  assert.deepEqual(await complete.json(), { ok: true });
  assert.deepEqual(outcomes.map(entry => entry[0]), ["completed", "completed"]);

  const events: GatewayTelemetryEvent[] = [];
  recordGatewayRequestFinished(
    CANONICAL_REQUEST_ID, "/api/speech/tts", Response.json({}), "completed",
    70_000.4, 69_000, "UNKNOWN", event => events.push(event)
  );
  assert.equal(events[0]!.latency_ms, 70_000);
  assert.equal(events[0]!.first_byte_ms, 69_000);
});
