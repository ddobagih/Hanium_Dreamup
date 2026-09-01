import assert from "node:assert/strict";
import { test } from "node:test";

import {
  handleGatewayRequest,
  PUBLIC_GATEWAY_ROUTES
} from "../src/routes.js";
import {
  GATEWAY_ROUTE_TEMPLATES,
  GATEWAY_TELEMETRY_MAX_LATENCY_MS,
  GATEWAY_TELEMETRY_SCHEMA,
  recordGatewayRequestCompleted,
  type GatewayRouteTemplate,
  type GatewayTelemetryEvent
} from "../src/telemetry.js";

const CANONICAL_REQUEST_ID = "018f2b63-8fb8-7cc2-98a1-4a4fd27c3001";
const GENERATED_UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

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

    assert.equal(events.length, cases.length);
    assert.deepEqual(
      events.map(event => event.route_template),
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
    assert.equal(event.event_name, "walksafe.gateway.request.completed");
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
      "http_status",
      "is_5xx",
      "latency_ms",
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
  assert.equal(events[0]!.correlation_id, responseId);
  assert.doesNotMatch(JSON.stringify(events), /account-identifier|example\.org/);
});

test("completion latency is integer-clamped and 5xx is explicit", () => {
  const events: GatewayTelemetryEvent[] = [];
  const response = Response.json({}, { status: 503 });
  recordGatewayRequestCompleted(
    CANONICAL_REQUEST_ID,
    "/privacy/rights",
    response,
    -10.4,
    "UNKNOWN",
    event => events.push(event)
  );
  recordGatewayRequestCompleted(
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
    { telemetrySink: () => { throw new Error("monitor unavailable"); } }
  );

  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), {
    code: "privacy_rights_channel_unavailable"
  });
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.match(response.headers.get("x-request-id") ?? "", GENERATED_UUID);
});
