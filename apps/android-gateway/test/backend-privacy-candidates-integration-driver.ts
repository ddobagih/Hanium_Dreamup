/** Opt-in isolated Postgres test drives a real Node Gateway and real Backend HTTP codecs. */
import assert from "node:assert/strict";
import { generateKeyPairSync, randomBytes, sign } from "node:crypto";
import { once } from "node:events";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { createGatewayServer } from "../src/node-adapter.js";
import { type GatewayFetch } from "../src/backend.js";
import { INTEGRATED_CONSENT_ITEM_VERSIONS, INTEGRATED_CONSENT_POLICY_VERSION,
  CONSENT_CONTROL_SECRET_HEADER } from "../src/integrated-consent.js";
import { DELETION_ACCESS_SECRET_HEADER, deviceDeletionEvidenceSha256V2,
  privacyDeletionV2LedgerPathForTests } from "../src/privacy-deletion-v2.js";
import { parseAccountDeletionStatusV2, parseAccountDeletionStatusViewV2 } from "../src/privacy-deletion-codec.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const backend = new URL(process.env.CANDIDATE_TEST_BACKEND_ORIGIN!);
assert.equal(backend.protocol, "http:"); assert.equal(backend.hostname, "127.0.0.1");
assert.ok(Number(backend.port) > 0 && ![8000, 8081, 9001].includes(Number(backend.port)));
assert.equal(backend.pathname, "/"); assert.equal(backend.search + backend.hash + backend.username + backend.password, "");
const nativeFetch = globalThis.fetch;
globalThis.fetch = async () => { throw new Error("unconfigured integration transport"); };
const allowed = new Set(["/account-signup-policy", "/account-enrollments/email-otp", "/accounts",
  "/accounts/authenticate", "/accounts/session-current", "/privacy/consent-events", "/privacy/installation-evidence-key"]);
const trace: Array<{ path: string; status: number }> = [];
const upstream: GatewayFetch = async (input, init) => {
  const source = new URL(String(input));
  assert.equal(source.origin, "http://127.0.0.1:8000");
  assert.equal(source.username + source.password + source.search + source.hash, "");
  assert.ok(allowed.has(source.pathname) || /^\/privacy\/account-deletions(?:\/[A-Za-z0-9_-]{16,128}\/(?:status|device-evidence))?$/.test(source.pathname), source.pathname);
  const response = await nativeFetch(new URL(source.pathname, backend), init);
  trace.push({ path: source.pathname, status: response.status }); return response;
};
await configureTestStateEncryption(path.join(process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR!, "keyring.json"));
// Resolve the isolated fixture path while its test helper is enabled; all HTTP runs in production mode.
const ledgerPath = privacyDeletionV2LedgerPathForTests();
Object.assign(process.env, { NODE_ENV: "production", WALKSAFE_ENVIRONMENT: "test",
  WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "true", WALKSAFE_FIELD_ACCESS_TTL_SECONDS: "60",
  WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "unlimited", WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "unlimited",
  WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip" });
const server = createGatewayServer({ fetchImpl: upstream });
server.listen(0, "127.0.0.1"); await once(server, "listening");
const address = server.address(); assert.ok(address && typeof address !== "string");
const gateway = `http://127.0.0.1:${address.port}`;
let ip = 10;
const call = (route: string, init: RequestInit = {}) => nativeFetch(new URL(route, gateway), {
  ...init, headers: { "cf-connecting-ip": `198.51.100.${++ip}`, ...Object.fromEntries(new Headers(init.headers)) },
  signal: AbortSignal.timeout(45_000)
});
const json = (route: string, body: unknown, headers: HeadersInit = {}, method = "POST") => call(route, {
  method, headers: { "content-type": "application/json", ...Object.fromEntries(new Headers(headers)) }, body: JSON.stringify(body)
});
const expect = async (response: Response, code: number) => {
  const text = await response.text(); assert.equal(response.status, code, text);
  assert.equal(response.headers.get("cache-control"), "no-store"); return text ? JSON.parse(text) : null;
};
type Session = { actor_id: string; account_generation: number; device_id: string; family_id: string; rotation: number; refresh_token: string; cookie: string };
const passwordBody = (remember: boolean, device?: string) => ({ grant_type: "password",
  email: process.env.CANDIDATE_TEST_EMAIL, password: process.env.CANDIDATE_TEST_PASSWORD, remember_me: remember,
  ...(device ? { device_id: device, session_mode: "long_lived" } : {}) });
const session = async (response: Response): Promise<Session> => {
  const cookie = response.headers.get("set-cookie") ?? ""; assert.match(cookie, /; Secure(?:;|$)/);
  const body = await expect(response, 200); assert.ok(Number.isSafeInteger(body.account_generation));
  return { ...body, cookie: cookie.split(";", 1)[0]! };
};
try {
  const policy = await expect(await call("/api/account-signup-policy"), 200);
  const enrollment = await expect(await json("/api/account-enrollments/email-otp", {
    schema_version: "walksafe.account-enrollment-email-otp.v1", email: process.env.CANDIDATE_TEST_EMAIL,
    date_of_birth: "1990-01-01", request_id: `candidate_enrollment_${randomBytes(12).toString("hex")}`
  }), 202);
  const account = await expect(await json("/api/accounts", {
    schema_version: "walksafe.account-create.v1", enrollment_handle: enrollment.enrollment_handle,
    otp_code: process.env.CANDIDATE_TEST_OTP, password: process.env.CANDIDATE_TEST_PASSWORD,
    consent: { schema_version: "walksafe.signup-consent.v1",
      document_versions: Object.fromEntries(Object.entries(policy.documents as Record<string, { version: string }>).map(([key, doc]) => [key, doc.version])),
      selections: Object.fromEntries(Object.entries(policy.documents as Record<string, { required: boolean }>).map(([key, doc]) => [key, doc.required])) }
  }), 201);
  const short = await json("/api/field-session", passwordBody(false));
  assert.match(short.headers.get("set-cookie") ?? "", /^walksafe_field_session=v6\..*; Secure(?:;|$)/);
  const shortCookie = short.headers.get("set-cookie")!.split(";", 1)[0]!;
  await expect(short, 200);
  const shortLogout = await call("/api/field-session", { method: "DELETE", headers: { cookie: shortCookie } });
  assert.match(shortLogout.headers.get("set-cookie") ?? "", /; Secure(?:;|$)/); await expect(shortLogout, 204);
  const owner = await session(await json("/api/field-session", passwordBody(true, "candidate-lost-refresh-owner")));
  const survivor = await session(await json("/api/field-session", passwordBody(true, "candidate-surviving-family")));
  const proof = { actor_id: owner.actor_id, device_id: owner.device_id, family_id: owner.family_id,
    rotation: owner.rotation, refresh_token: owner.refresh_token };
  await expect(await json("/api/field-session", { grant_type: "refresh_token", ...proof }), 200);
  for (let count = 0; count < 2; count += 1) await expect(await json("/api/field-session", proof, {}, "DELETE"), 204);
  const survivorStatus = await expect(await call("/api/field-session", { headers: { cookie: survivor.cookie } }), 200);
  assert.equal(survivorStatus.authenticated, true); assert.equal(survivorStatus.account_generation, account.account_generation);
  const stillCurrent = await session(await json("/api/field-session", { grant_type: "refresh_token", actor_id: survivor.actor_id,
    device_id: survivor.device_id, family_id: survivor.family_id, rotation: survivor.rotation, refresh_token: survivor.refresh_token }));
  assert.equal(stillCurrent.rotation, 1);
  const current = await session(await json("/api/field-session", passwordBody(true, "candidate-deletion-installation-device")));
  assert.equal(current.account_generation, account.account_generation);
  const installation = `candidate_installation_${randomBytes(12).toString("hex")}`;
  const controlSecret = randomBytes(32).toString("hex");
  const consent = await expect(await json("/privacy/rights?control=integrated-consent", {
    schema_version: "walksafe.integrated-consent-request.v1", installation_id: installation,
    request_id: `candidate_consent_${randomBytes(12).toString("hex")}`, policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
    item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS, client_revision: 1, expected_previous_backend_receipt_sha256: null,
    selections: { raw_source_collection: false, automatic_reporting: false, mobile_network_transfer: false, training_reuse: false }
  }, { cookie: current.cookie, [CONSENT_CONTROL_SECRET_HEADER]: controlSecret }, "PUT"), 201);
  assert.equal(consent.current, true);
  const keys = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const publicKey = keys.publicKey.export({ format: "der", type: "spki" }).toString("base64url");
  const keyRoute = "/privacy/rights?control=installation-evidence-key";
  const controlHeaders = { cookie: current.cookie, [CONSENT_CONTROL_SECRET_HEADER]: controlSecret };
  for (let count = 0; count < 2; count += 1) await expect(await json(keyRoute, { installation_id: installation, public_key: publicKey }, controlHeaders, "PUT"), 204);
  const otherKey = generateKeyPairSync("ec", { namedCurve: "prime256v1" }).publicKey.export({ format: "der", type: "spki" }).toString("base64url");
  const keyConflict = await expect(await json(keyRoute, { installation_id: installation, public_key: otherKey }, controlHeaders, "PUT"), 409);
  assert.equal(keyConflict.detail.code, "installation_evidence_key_conflict");
  const requestId = `candidate_deletion_${randomBytes(12).toString("hex")}`;
  const capability = randomBytes(32).toString("base64url");
  const recovery = { [DELETION_ACCESS_SECRET_HEADER]: capability };
  const accepted = await expect(await json("/privacy/account-deletions", { schema_version: "walksafe.account-deletion-request.v2",
    request_id: requestId, client_revision: 1, confirmation: "DELETE_MY_ACCOUNT" }, { cookie: current.cookie, ...recovery }), 202);
  assert.ok(parseAccountDeletionStatusV2(accepted));
  const evidence = { schema_version: "walksafe.device-deletion-evidence.v2" as const, request_id: requestId,
    tombstone_id: accepted.tombstone_id, request_receipt_sha256: accepted.request_receipt_sha256,
    installation_id: installation, evidence_id: `candidate_evidence_${randomBytes(12).toString("hex")}`,
    client_revision: 1, expected_status_revision: accepted.revision, item: "device_untransmitted_data" as const,
    result: "DELETED" as const, completed_at: new Date(Math.floor(Date.now() / 1000) * 1000).toISOString().replace(".000Z", "Z") };
  const evidenceSha = deviceDeletionEvidenceSha256V2(evidence);
  const signature = sign("sha256", Buffer.from(`walksafe/device-deletion-evidence-signature/v1\0${evidenceSha}`, "ascii"), keys.privateKey).toString("base64url");
  const recorded = await expect(await json(`/privacy/account-deletions/${requestId}/device-evidence`, {
    ...evidence, evidence_sha256: evidenceSha, installation_signature: signature }, recovery), 200);
  assert.ok(parseAccountDeletionStatusV2(recorded));
  const ledgerBefore = await readFile(ledgerPath, "utf8");
  const statusRoute = `/privacy/account-deletions/${requestId}/status`;
  const view = await expect(await call(statusRoute, { headers: { ...recovery, "x-walksafe-deletion-status-view": "device-verification.v1" } }), 200);
  assert.ok(parseAccountDeletionStatusViewV2(view)); assert.equal(view.device_verification.deleted_count, 1);
  assert.equal(view.revision, recorded.revision);
  assert.equal(await readFile(ledgerPath, "utf8"), ledgerBefore);
  const plain = await expect(await call(statusRoute, { headers: recovery }), 200);
  assert.ok(parseAccountDeletionStatusV2(plain)); assert.equal("device_verification" in plain, false);
  assert.equal(await readFile(ledgerPath, "utf8"), ledgerBefore);
  process.stdout.write(`${JSON.stringify({ phase: "completed", trace, observations: {
    signup_policy: true, account_created: true, short_cookie_secure_internal_http: true,
    refresh_response_loss_own_logout_idempotent: true, other_family_survives: true,
    long_session_generation: true, confirmed_installation_key_first_wins: true,
    signed_device_evidence_accepted: true, dynamic_device_view_canonical_ledger_unchanged: true
  } })}\n`);
} finally {
  await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
}
