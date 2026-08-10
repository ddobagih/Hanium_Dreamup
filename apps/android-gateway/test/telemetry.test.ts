import assert from "node:assert/strict";
import { test } from "node:test";

import { handleGatewayRequest } from "../src/routes.js";
import {
  GATEWAY_TELEMETRY_SCHEMA,
  type GatewayTelemetryEvent
} from "../src/telemetry.js";

test("proxy and privacy failures emit only versioned allowlisted telemetry fields", async () => {
  const previousNodeEnv = process.env.NODE_ENV;
  const previousFieldToken = process.env.WALKSAFE_FIELD_TEST_TOKEN;
  const previousAccounts = process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  const previousPrivacyUrl = process.env.WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL;
  const events: GatewayTelemetryEvent[] = [];
  const secret = "raw-secret-token-password-and-pii@example.org";
  process.env.NODE_ENV = "production";
  delete process.env.WALKSAFE_FIELD_TEST_TOKEN;
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  delete process.env.WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL;
  try {
    const proxy = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/navigation/walking", {
        method: "POST",
        headers: {
          authorization: `Bearer ${secret}`,
          "content-type": "application/json",
          "x-correlation-id": secret
        },
        body: JSON.stringify({ password: secret, raw_location: secret })
      }),
      { telemetrySink: event => events.push(event) }
    );
    assert.equal(proxy.status, 503);

    const privacy = await handleGatewayRequest(
      new Request(`http://127.0.0.1:8081/privacy/rights?token=${encodeURIComponent(secret)}`),
      { telemetrySink: event => events.push(event) }
    );
    assert.equal(privacy.status, 503);
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

  assert.equal(events.length, 2);
  assert.deepEqual(
    events.map(event => event.event_name),
    ["walksafe.gateway.proxy.failed", "walksafe.gateway.privacy.failed"]
  );
  for (const event of events) {
    assert.equal(event.schema_version, GATEWAY_TELEMETRY_SCHEMA);
    assert.equal(event.severity, "ERROR");
    assert.match(
      event.correlation_id,
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
    );
    assert.deepEqual(Object.keys(event).sort(), [
      "correlation_id",
      "event_name",
      "failure_code",
      "http_status",
      "method",
      "route",
      "schema_version",
      "severity"
    ]);
  }
  assert.doesNotMatch(JSON.stringify(events), /raw-secret|password|example\.org|authorization/i);
});

test("telemetry sink failure does not change the gateway response contract", async () => {
  const previousNodeEnv = process.env.NODE_ENV;
  const previousPrivacyUrl = process.env.WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL;
  process.env.NODE_ENV = "production";
  delete process.env.WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL;
  try {
    const response = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/privacy/rights"),
      { telemetrySink: () => { throw new Error("monitor unavailable"); } }
    );
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), {
      code: "privacy_rights_channel_unavailable"
    });
    assert.equal(response.headers.get("cache-control"), "no-store");
  } finally {
    if (previousNodeEnv === undefined) delete process.env.NODE_ENV;
    else process.env.NODE_ENV = previousNodeEnv;
    if (previousPrivacyUrl === undefined) delete process.env.WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL;
    else process.env.WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL = previousPrivacyUrl;
  }
});
