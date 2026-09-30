// S19-30: child process Gateway for the "Backend committed, Gateway killed" consent test.
// The Backend is a file-backed fake so its commit survives the Gateway process.
import { createHash } from "node:crypto";
import { existsSync, readFileSync, writeFileSync, writeSync } from "node:fs";
import path from "node:path";

import type { GatewayFetch } from "../src/backend.js";
import {
  authorizeIntegratedConsentRequest,
  CONSENT_CONTROL_SECRET_HEADER,
  CONSENT_INSTALLATION_HEADER,
  CONSENT_NETWORK_TRANSPORT_HEADER,
  CONSENT_POLICY_HEADER,
  CONSENT_RECEIPT_HEADER,
  CONSENT_REVISION_HEADER,
  INTEGRATED_CONSENT_ITEM_VERSIONS,
  INTEGRATED_CONSENT_POLICY_VERSION,
  type IntegratedConsentConfirmation
} from "../src/integrated-consent.js";
import { handleGatewayRequest } from "../src/routes.js";

export const CRASH_ACTOR = "consent-crash-actor";
export const CRASH_TOKEN = "consent-crash-token-12345678901234567890";
export const CRASH_INSTALLATION = "s19_30_crash_installation";
export const CRASH_REQUEST = "s19_30_crash_request_0001";
const CONTROL_SECRET = "c".repeat(64);
const SELECTIONS = {
  raw_source_collection: true,
  automatic_reporting: false,
  mobile_network_transfer: false,
  training_reuse: false
};

function backendLedgerPath(): string {
  return path.join(process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR!, "fake-backend-consent.json");
}

function backendEvents(): Record<string, { body: string; receipt: string }> {
  return existsSync(backendLedgerPath())
    ? JSON.parse(readFileSync(backendLedgerPath(), "utf8")) as Record<string, { body: string; receipt: string }>
    : {};
}

/** Idempotent by request_id like the real Backend: a replay returns 200 and the same receipt. */
function fakeBackend(onCommitted: (() => void) | null): GatewayFetch {
  return async (_input, init) => {
    const rawBody = String(init?.body ?? "");
    const body = JSON.parse(rawBody) as { request_id: string; client_revision: number };
    const events = backendEvents();
    const prior = events[body.request_id];
    if (prior && prior.body !== rawBody) {
      return Response.json({ detail: { code: "privacy_consent_request_conflict" } }, { status: 409 });
    }
    const receipt = prior?.receipt ?? createHash("sha256").update(rawBody).digest("hex");
    if (!prior) {
      events[body.request_id] = { body: rawBody, receipt };
      writeFileSync(backendLedgerPath(), JSON.stringify(events));
    }
    const payload = JSON.stringify({
      schema_version: "walksafe.privacy-consent-receipt.v2",
      request_id: body.request_id,
      client_revision: body.client_revision,
      receipt_sha256: receipt,
      recorded_at: "2026-09-29T12:05:00Z"
    });
    // The Backend has committed; the Gateway dies while reading the response, before its
    // own local consent write.
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (onCommitted) onCommitted();
        controller.enqueue(new TextEncoder().encode(payload));
        controller.close();
      }
    });
    return new Response(stream, {
      status: prior ? 200 : 201,
      headers: { "content-type": "application/json" }
    });
  };
}

async function login(ip: string): Promise<string> {
  const response = await handleGatewayRequest(new Request("http://127.0.0.1:8081/api/field-session", {
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": ip },
    body: JSON.stringify({ actor_id: CRASH_ACTOR, token: CRASH_TOKEN })
  }));
  if (response.status !== 200) throw new Error(`login ${response.status}`);
  return (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
}

function consentPut(cookie: string): Request {
  return new Request("http://127.0.0.1:8081/privacy/rights?control=integrated-consent", {
    method: "PUT",
    headers: {
      cookie,
      "content-type": "application/json",
      [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
    },
    body: JSON.stringify({
      schema_version: "walksafe.integrated-consent-request.v1",
      installation_id: CRASH_INSTALLATION,
      request_id: CRASH_REQUEST,
      policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
      item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
      client_revision: 1,
      expected_previous_backend_receipt_sha256: null,
      selections: SELECTIONS
    })
  });
}

const phase = process.argv[2];
if (phase === "commit-then-kill") {
  const cookie = await login("198.51.100.30");
  await handleGatewayRequest(consentPut(cookie), {
    fetchImpl: fakeBackend(() => {
      writeSync(1, "BACKEND_COMMITTED\n");
      process.kill(process.pid, "SIGKILL");
    })
  });
  // Not reached: the process is killed after the Backend commit.
  writeSync(1, "NOT_KILLED\n");
  process.exit(4);
}
if (phase === "restart-retry") {
  // A restarted Gateway (new process, new login) receives the client's durable retry of
  // the same request id and content.
  const cookie = await login("198.51.100.31");
  const response = await handleGatewayRequest(consentPut(cookie), { fetchImpl: fakeBackend(null) });
  const confirmation = await response.json() as IntegratedConsentConfirmation;
  const authorized = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: {
        [CONSENT_INSTALLATION_HEADER]: confirmation.installation_id,
        [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET,
        [CONSENT_NETWORK_TRANSPORT_HEADER]: "wifi",
        [CONSENT_POLICY_HEADER]: confirmation.policy_version,
        [CONSENT_REVISION_HEADER]: String(confirmation.revision),
        [CONSENT_RECEIPT_HEADER]: confirmation.backend_consent_receipt_sha256
      }
    }),
    ["raw_source_collection"],
    `${CRASH_ACTOR}\0generation:1`
  );
  writeSync(1, `${JSON.stringify({
    status: response.status,
    revision: confirmation.revision,
    receipt: confirmation.backend_consent_receipt_sha256,
    backend_receipt: backendEvents()[CRASH_REQUEST]?.receipt ?? null,
    backend_events: Object.keys(backendEvents()).length,
    authorized: authorized.error === undefined
  })}\n`);
}
