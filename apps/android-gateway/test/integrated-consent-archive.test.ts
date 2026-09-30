import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { chmod, link, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";
import {
  authorizeIntegratedConsentRequest, CONSENT_CONTROL_SECRET_HEADER,
  CONSENT_INSTALLATION_HEADER, CONSENT_NETWORK_TRANSPORT_HEADER, CONSENT_POLICY_HEADER,
  CONSENT_RECEIPT_HEADER, CONSENT_REVISION_HEADER, INTEGRATED_CONSENT_ITEM_VERSIONS,
  INTEGRATED_CONSENT_MAX_STATE_BYTES, INTEGRATED_CONSENT_POLICY_VERSION,
  integratedConsentAuditSegmentPaths, setIntegratedConsentStorageFaultForTests,
  type IntegratedConsentConfirmation
} from "../src/integrated-consent.js";
import { type GatewayFetch } from "../src/backend.js";
import { handleGatewayRequest } from "../src/routes.js";
import { resetGatewayStateEncryptionForTests, setGatewayStateCommitFaultForTests } from "../src/encrypted-json-store.js";
import { assertGatewayManagedStateReady, runGatewayStateMaintenance } from "../src/state-encryption-maintenance.js";
import { configureTestStateEncryption, decryptTestStateFile, encryptTestStateFile } from "./state-encryption-fixture.js";

type Event = Record<string, any>;
type State = { schema_version: number; installation_id: string; control_secret_sha256: string;
  field_actor_binding: { actor_sha256: string; consent_revision: number; bound_at: string } | null;
  events: Event[]; revision?: number;
  audit_archive?: { segment_count: number; archived_events: number; last_segment_sha256: string } };
const secret = "a".repeat(64);
const actor = "archive-actor-a";
const otherActor = "archive-actor-b";
const binding = `${actor}\0generation:1`;
const otherBinding = `${otherActor}\0generation:1`;
const allTrue = { raw_source_collection: true, automatic_reporting: true, mobile_network_transfer: true, training_reuse: true };
const allFalse = { raw_source_collection: false, automatic_reporting: false, mobile_network_transfer: false, training_reuse: false };
let root = "";
let keyringPath = "";
let cookie = "";
let otherCookie = "";
let backendCalls = 0;
const backendRequests = new Map<string, { body: string; receipt: string }>();
const latest = new Map<string, IntegratedConsentConfirmation>();
function digest(value: string): string { return createHash("sha256").update(value).digest("hex"); }
function canonical(value: any): string {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(",")}}`;
}
function statePath(installation: string): string {
  return path.join(root, "integrated-consent", `${digest(`integrated-consent\0${installation}`)}.json`);
}
async function readState(installation: string): Promise<State> {
  const file = statePath(installation);
  return decryptTestStateFile<State>({ kind: "integrated-consent", recordId: path.basename(file) }, await readFile(file, "utf8"), INTEGRATED_CONSENT_MAX_STATE_BYTES);
}
async function writeState(installation: string, state: State): Promise<void> {
  const file = statePath(installation);
  await writeFile(file, encryptTestStateFile({ kind: "integrated-consent", recordId: path.basename(file) }, state, INTEGRATED_CONSENT_MAX_STATE_BYTES), { mode: 0o600 });
}
const fetchImpl: GatewayFetch = async (input, init) => {
  if (init?.method === "GET") {
    const installation = new URL(String(input)).searchParams.get("installation_id")!;
    const current = latest.get(installation)!;
    return Response.json({ schema_version: "walksafe.integrated-consent-bootstrap.v1", status: "READY", source: "CURRENT_CONSENT",
      installation_id: installation, policy_version: INTEGRATED_CONSENT_POLICY_VERSION, item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
      client_revision_floor: current.client_revision, selections: current.selections,
      source_receipt_sha256: current.backend_consent_receipt_sha256,
      expected_previous_backend_receipt_sha256: current.backend_consent_receipt_sha256 });
  }
  backendCalls += 1;
  const body = String(init?.body);
  const payload = JSON.parse(body);
  const key = `${payload.installation_id}:${payload.request_id}`;
  const prior = backendRequests.get(key);
  if (prior && prior.body !== body) return Response.json({ detail: { code: "privacy_consent_request_conflict" } }, { status: 409 });
  const receipt = prior?.receipt ?? digest(body);
  backendRequests.set(key, { body, receipt });
  return Response.json({ schema_version: "walksafe.privacy-consent-receipt.v2", request_id: payload.request_id,
    client_revision: payload.client_revision, receipt_sha256: receipt, recorded_at: "2026-09-30T00:00:00Z" }, { status: prior ? 200 : 201 });
};
async function save(installation: string, requestId: string, revision: number, selections = allTrue, useCookie = cookie,
  expected = latest.get(installation)?.backend_consent_receipt_sha256 ?? null): Promise<Response> {
  const response = await handleGatewayRequest(new Request("http://127.0.0.1:8081/privacy/rights?control=integrated-consent", {
    method: "PUT", headers: { cookie: useCookie, "content-type": "application/json", [CONSENT_CONTROL_SECRET_HEADER]: secret },
    body: JSON.stringify({ schema_version: "walksafe.integrated-consent-request.v1", installation_id: installation,
      request_id: requestId, policy_version: INTEGRATED_CONSENT_POLICY_VERSION, item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
      client_revision: revision, expected_previous_backend_receipt_sha256: expected, selections })
  }), { fetchImpl });
  if (response.ok) latest.set(installation, await response.clone().json() as IntegratedConsentConfirmation);
  return response;
}
async function code(response: Response): Promise<string> { return (await response.json() as any).detail.code; }
function receiptHeaders(value: IntegratedConsentConfirmation): Record<string, string> {
  return { [CONSENT_INSTALLATION_HEADER]: value.installation_id, [CONSENT_CONTROL_SECRET_HEADER]: secret,
    [CONSENT_NETWORK_TRANSPORT_HEADER]: "wifi", [CONSENT_POLICY_HEADER]: value.policy_version,
    [CONSENT_REVISION_HEADER]: String(value.revision), [CONSENT_RECEIPT_HEADER]: value.backend_consent_receipt_sha256 };
}
async function seed(installation: string, count = 4096, legacyPrefix = 0, lastOtherActor = false, archivedOtherActor = false): Promise<Event[]> {
  assert.equal((await save(installation, `${installation}_initial_request`, 1)).status, 201);
  const state = await readState(installation);
  const template = state.events[0]!;
  let previous: string | null = null;
  state.events = Array.from({ length: count }, (_, index) => {
    const { gateway_audit_record_sha256: _hash, ...fields } = template;
    let event: Event;
    if (index < legacyPrefix) {
      event = { request_id: `${installation}_legacy_${String(index).padStart(8, "0")}`, policy_version: "FP-013-1.1.0",
        item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS, client_revision: index + 1, revision: index + 1,
        selections: allTrue, confirmed_at: template.confirmed_at, previous_receipt_sha256: previous };
      previous = digest(canonical({ installation_id: installation, ...event }));
      return { ...event, receipt_sha256: previous };
    }
    event = { ...fields, request_id: `${installation}_seed_${String(index).padStart(8, "0")}`,
      client_revision: index + 1, revision: index + 1, previous_gateway_audit_record_sha256: previous,
      ...((lastOtherActor && index === count - 1) || (archivedOtherActor && index === 1) ? {
        client_revision: archivedOtherActor ? 7 : 1, field_actor_binding_sha256: digest(`integrated-consent-field-actor\0${otherBinding}`)
      } : {}) };
    previous = digest(canonical({ installation_id: installation, ...event }));
    return { ...event, gateway_audit_record_sha256: previous };
  });
  state.field_actor_binding = { actor_sha256: state.events.at(-1)!.field_actor_binding_sha256,
    consent_revision: count, bound_at: template.confirmed_at };
  await writeState(installation, state);
  return structuredClone(state.events);
}
async function history(installation: string): Promise<Event[]> {
  const result: Event[] = [];
  for (const { segmentPath } of integratedConsentAuditSegmentPaths().filter(entry => entry.statePath === statePath(installation))) {
    const value = decryptTestStateFile<{ events: Event[] }>({ kind: "integrated-consent", recordId: path.basename(segmentPath) }, await readFile(segmentPath, "utf8"), INTEGRATED_CONSENT_MAX_STATE_BYTES);
    result.push(...value.events);
  }
  result.push(...(await readState(installation)).events);
  return result;
}
before(async () => {
  root = await mkdtemp(path.join(tmpdir(), "consent-archive-regression-"));
  keyringPath = path.join(root, "keyring.json");
  Object.assign(process.env, { NODE_ENV: "test", WALKSAFE_GATEWAY_RATE_LIMIT_DIR: root,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(root, "walk-ledger.json"),
    WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED: "true", WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_FIELD_TEST_TOKEN: "archive-backend-token-12345678901234567890",
    WALKSAFE_GATEWAY_SESSION_SECRET: "archive-session-secret-123456789012345678901234567890",
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([{ actor_id: actor, token: "archive-token-a-12345678901234567890" },
      { actor_id: otherActor, token: "archive-token-b-12345678901234567890" }]) });
  await configureTestStateEncryption(keyringPath, [{ keyId: "old-key", status: "active", fillByte: 0x41 }]);
  const login = async (id: string, token: string) => {
    const response = await handleGatewayRequest(new Request("http://127.0.0.1:8081/api/field-session", { method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": "198.51.100.111" }, body: JSON.stringify({ actor_id: id, token }) }));
    assert.equal(response.status, 200);
    return response.headers.get("set-cookie")!.split(";", 1)[0]!;
  };
  cookie = await login(actor, "archive-token-a-12345678901234567890");
  otherCookie = await login(otherActor, "archive-token-b-12345678901234567890");
});
after(async () => { setIntegratedConsentStorageFaultForTests(null); setGatewayStateCommitFaultForTests(null);
  await rm(root, { recursive: true, force: true }); });

test("S19-29 archive preserves legacy/replay/conflict/actor floors and current consumer gates", async () => {
  const installation = "archive_identity";
  const originals = await seed(installation, 4100, 2, true);
  const before = backendCalls;
  const staleActor = await save(installation, "archive_identity_stale_floor", 4099, allFalse);
  assert.equal(staleActor.status, 409);
  assert.equal(await code(staleActor), "integrated_consent_client_revision_stale");
  assert.equal(backendCalls, before);
  assert.equal((await save(installation, "archive_identity_withdrawal", 4100, allFalse)).status, 201);
  const withdrawn = latest.get(installation)!;
  assert.equal(withdrawn.revision, 4101);
  const denied = await authorizeIntegratedConsentRequest(new Request("http://127.0.0.1:8081/reports/v2", { headers: receiptHeaders(withdrawn) }), ["automatic_reporting"], binding);
  assert.equal(denied.error?.status, 403);
  assert.equal(await code(denied.error!), "integrated_consent_selection_denied");
  assert.equal((await save(installation, "archive_identity_reconsent", 4101)).status, 201);
  const granted = latest.get(installation)!;
  assert.equal(granted.revision, 4102);
  const accepted = await authorizeIntegratedConsentRequest(new Request("http://127.0.0.1:8081/reports/v2", { headers: receiptHeaders(granted) }), ["automatic_reporting"], binding);
  assert.equal(accepted.error, undefined);
  const current = await handleGatewayRequest(new Request(`http://127.0.0.1:8081/privacy/rights?control=integrated-consent&installation_id=${installation}&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`, {
    headers: { cookie, [CONSENT_CONTROL_SECRET_HEADER]: secret }
  }), { fetchImpl });
  assert.equal(current.status, 200);
  assert.deepEqual(await current.json(), granted);
  assert.deepEqual((await history(installation)).slice(0, 4100), originals);
  const old = originals[10]!;
  assert.equal(await code(await save(installation, old.request_id, old.client_revision, allFalse, cookie, old.expected_previous_backend_receipt_sha256)), "integrated_consent_request_conflict");
  assert.equal(await code(await save(installation, old.request_id, old.client_revision, old.selections, cookie, old.expected_previous_backend_receipt_sha256)), "integrated_consent_request_stale");
  const legacy = originals[0]!;
  assert.equal(await code(await save(installation, legacy.request_id, legacy.client_revision)), "integrated_consent_reconsent_required");
  assert.equal(await code(await save(installation, "archive_identity_new_stale_floor", 4100)), "integrated_consent_client_revision_stale");
  // The other actor's floor remains 1 despite global revisions over 4,000.
  assert.equal((await save(installation, "archive_identity_actor_b_revision2", 2, allFalse, otherCookie)).status, 201);
  assert.equal(await code(await save(installation, old.request_id, old.client_revision, old.selections, cookie, old.expected_previous_backend_receipt_sha256)), "integrated_consent_actor_reconsent_required");
});

test("S19-29 a returning actor retains its floor when all its events are archived", async () => {
  const installation = "archived_actor_floor";
  await seed(installation, 4096, 0, false, true);
  assert.equal((await save(installation, "archived_actor_floor_rotation", 4097)).status, 201);
  assert.ok((await readState(installation)).events.every(event => event.field_actor_binding_sha256 !== digest(`integrated-consent-field-actor\0${otherBinding}`)));
  const before = backendCalls;
  assert.equal(await code(await save(installation, "archived_actor_floor_stale_request", 7, allFalse, otherCookie)), "integrated_consent_client_revision_stale");
  assert.equal(backendCalls, before);
  assert.equal((await save(installation, "archived_actor_floor_new_request", 8, allFalse, otherCookie)).status, 201);
  assert.equal(latest.get(installation)!.revision, 4098);
});

test("S19-29 actor floor validation crosses the archive/current boundary", async () => {
  const installation = "archived_floor_validation";
  await seed(installation);
  assert.equal((await save(installation, "archived_floor_validation_rotation", 4097)).status, 201);
  const original = await readState(installation);
  const corrupted = structuredClone(original);
  let previous = corrupted.events[0]!.previous_gateway_audit_record_sha256;
  corrupted.events = corrupted.events.map((event, index) => {
    const { gateway_audit_record_sha256: _hash, ...fields } = event;
    const modified = { ...fields, client_revision: index + 1, previous_gateway_audit_record_sha256: previous };
    previous = digest(canonical({ installation_id: installation, ...modified }));
    return { ...modified, gateway_audit_record_sha256: previous };
  });
  try {
    await writeState(installation, corrupted);
    const before = backendCalls;
    assert.equal((await save(installation, "archived_floor_validation_denied", 4098)).status, 503);
    assert.equal(backendCalls, before);
  } finally { await writeState(installation, original); }
});

for (const stage of ["archive_open", "archive_file_fsync", "archive_rename", "archive_directory_fsync", "current_file_fsync", "current_rename"] as const) {
  test(`S19-29 ${stage} blocks grants before Backend and exact retry retains every event`, async () => {
    const installation = `failure_${stage}`;
    const originals = await seed(installation);
    const before = backendCalls;
    setIntegratedConsentStorageFaultForTests(stage);
    let response: Response;
    try { response = await save(installation, `${installation}_grant_request`, 4097); }
    finally { setIntegratedConsentStorageFaultForTests(null); }
    assert.equal(response.status, 503);
    assert.equal(backendCalls, before);
    assert.deepEqual((await readState(installation)).events, originals);
    assert.equal((await save(installation, `${installation}_grant_request`, 4097)).status, 201);
    assert.deepEqual((await history(installation)).slice(0, 4096), originals);
  });
}

for (const stage of ["archive_open", "archive_file_fsync", "archive_rename", "archive_directory_fsync"] as const) {
  test(`S19-29 ${stage} preserves withdrawal reserve and can recover an archive-only commit`, async () => {
    const installation = `reserve_${stage}`;
    const originals = await seed(installation);
    setIntegratedConsentStorageFaultForTests(stage);
    try {
      assert.equal((await save(installation, `${installation}_withdrawal`, 4097, allFalse)).status, 201);
      assert.equal((await readState(installation)).events.length, 4097);
      const before = backendCalls;
      assert.equal((await save(installation, `${installation}_blocked_grant`, 4098)).status, 503);
      assert.equal(backendCalls, before);
    } finally { setIntegratedConsentStorageFaultForTests(null); }
    assert.equal((await save(installation, `${installation}_blocked_grant`, 4098)).status, 201);
    assert.deepEqual((await history(installation)).slice(0, 4096), originals);
  });
}

test("S19-29 Backend success followed by current fsync failure is outcome_unknown with idempotent retry", async () => {
  const installation = "postbackend_write_failure";
  setIntegratedConsentStorageFaultForTests("current_file_fsync");
  let failed: Response;
  try { failed = await save(installation, "postbackend_write_failure_request", 1); }
  finally { setIntegratedConsentStorageFaultForTests(null); }
  assert.equal(failed.status, 503);
  assert.equal(await code(failed), "integrated_consent_outcome_unknown");
  assert.equal((await save(installation, "postbackend_write_failure_request", 1)).status, 200);
});

test("S19-29 compact current directory-fsync failure stays unknown before Backend, then retries", async () => {
  const installation = "compact_directory_failure";
  const originals = await seed(installation);
  const before = backendCalls;
  setGatewayStateCommitFaultForTests("integrated-consent");
  let failed: Response;
  try { failed = await save(installation, "compact_directory_failure_request", 4097); }
  finally { setGatewayStateCommitFaultForTests(null); }
  assert.equal(failed.status, 503);
  assert.equal(await code(failed), "integrated_consent_outcome_unknown");
  assert.equal(backendCalls, before);
  assert.equal((await save(installation, "compact_directory_failure_request", 4097)).status, 201);
  assert.deepEqual((await history(installation)).slice(0, 4096), originals);
});

test("S19-29 repeated archival and a fresh process keep revision and original audit hashes", async () => {
  const installation = "multiple_archives";
  const originals = await seed(installation);
  assert.equal((await save(installation, "multiple_archives_rotation_1", 4097)).status, 201);
  for (let round = 2; round <= 3; round += 1) {
    const state = await readState(installation);
    let measuredBytes = Buffer.byteLength(JSON.stringify(state));
    while (measuredBytes + 4096 <= 4 * 1024 * 1024) {
      const last = state.events.at(-1)!;
      const { gateway_audit_record_sha256: _hash, ...fields } = last;
      const next = { ...fields, request_id: `multiple_archives_fill_${round}_${state.events.length}`,
        client_revision: last.client_revision + 1, revision: last.revision + 1,
        previous_gateway_audit_record_sha256: last.gateway_audit_record_sha256 };
      const filled = { ...next, gateway_audit_record_sha256: digest(canonical({ installation_id: installation, ...next })) };
      state.events.push(filled);
      measuredBytes += Buffer.byteLength(JSON.stringify(filled)) + 1;
      state.revision = next.revision;
      state.field_actor_binding!.consent_revision = next.revision;
    }
    await writeState(installation, state);
    assert.equal((await save(installation, `multiple_archives_rotation_${round}`, state.events.at(-1)!.client_revision + 1)).status, 201);
  }
  const state = await readState(installation);
  assert.equal(state.audit_archive?.segment_count, 3);
  assert.equal(state.events.length, 2);
  assert.deepEqual((await history(installation)).slice(0, 4096), originals);
  const current = latest.get(installation)!;
  const output = execFileSync(process.execPath, ["--input-type=module", "--eval", `
    import { authorizeIntegratedConsentRequest } from ${JSON.stringify(new URL("../src/integrated-consent.js", import.meta.url).href)};
    const result = await authorizeIntegratedConsentRequest(new Request('http://127.0.0.1:8081/reports/v2', {headers: ${JSON.stringify(receiptHeaders(current))}}), ['automatic_reporting'], ${JSON.stringify(binding)});
    if (result.error) throw new Error(await result.error.text());
    process.stdout.write(JSON.stringify(result.confirmation));
  `], { env: process.env, encoding: "utf8" });
  assert.deepEqual(JSON.parse(output), current);
});

test("S19-29 archive tampering, omission, cross-installation copy, mode and links fail closed", async () => {
  const installation = "archive_safety";
  await seed(installation);
  assert.equal((await save(installation, "archive_safety_rotation", 4097)).status, 201);
  const segmentPath = integratedConsentAuditSegmentPaths().find(entry => entry.statePath === statePath(installation))!.segmentPath;
  const original = await readFile(segmentPath, "utf8");
  const query = () => save(installation, "archive_safety_untrusted_request", 4098, allFalse);
  const before = backendCalls;
  try {
    const envelope = JSON.parse(original);
    envelope.tag_base64url = "a".repeat(22);
    await writeFile(segmentPath, JSON.stringify(envelope));
    assert.equal((await query()).status, 503);
    await rm(segmentPath);
    assert.equal((await query()).status, 503);
    await writeFile(segmentPath, original, { mode: 0o600 });
    await chmod(segmentPath, 0o644);
    assert.equal((await query()).status, 503);
    await chmod(segmentPath, 0o600);
    const extraLink = `${segmentPath}.link`;
    await link(segmentPath, extraLink);
    try { assert.equal((await query()).status, 503); } finally { await rm(extraLink); }
    await rm(segmentPath);
    const source = `${segmentPath}.source`;
    await writeFile(source, original, { mode: 0o600 });
    await symlink(source, segmentPath);
    try { assert.equal((await query()).status, 503); } finally { await rm(segmentPath); await rm(source); }
    await writeFile(segmentPath, original, { mode: 0o600 });
    const decoded = decryptTestStateFile<any>({ kind: "integrated-consent", recordId: path.basename(segmentPath) }, original, INTEGRATED_CONSENT_MAX_STATE_BYTES);
    decoded.installation_id = "different_installation";
    await writeFile(segmentPath, encryptTestStateFile({ kind: "integrated-consent", recordId: path.basename(segmentPath) }, decoded, INTEGRATED_CONSENT_MAX_STATE_BYTES));
    assert.equal((await query()).status, 503);
    assert.equal(backendCalls, before);
  } finally { await rm(segmentPath, { force: true }); await writeFile(segmentPath, original, { mode: 0o600 }); }
  const main = await readFile(statePath(installation), "utf8");
  try { await rm(statePath(installation)); assert.equal((await query()).status, 503); }
  finally { await writeFile(statePath(installation), main, { mode: 0o600 }); }
});

test("S19-29 killed-writer archive temp is preserved while startup/inspect accept valid state", async () => {
  const installation = "killed_archive_writer";
  await seed(installation);
  assert.equal((await save(installation, "killed_archive_writer_rotation", 4097)).status, 201);
  const temporary = `${statePath(installation)}.audit-00000002.1234.${"b".repeat(16)}.tmp`;
  await writeFile(temporary, "interrupted encryption", { mode: 0o600 });
  try {
    assert.doesNotThrow(() => assertGatewayManagedStateReady());
    const results = await runGatewayStateMaintenance("inspect");
    assert.ok(results.some(result => result.file === statePath(installation)));
    assert.equal(results.some(result => result.file === temporary), false);
    assert.equal(await readFile(temporary, "utf8"), "interrupted encryption");
  } finally { await rm(temporary); }
});

test("S19-29 inspect/rotation include referenced and orphan archives; old key can be retired after rotation", async () => {
  const installation = "rotation_archive_orphan";
  await seed(installation);
  setIntegratedConsentStorageFaultForTests("current_rename");
  try { assert.equal((await save(installation, "rotation_archive_orphan_request", 4097)).status, 503); }
  finally { setIntegratedConsentStorageFaultForTests(null); }
  const orphan = integratedConsentAuditSegmentPaths().find(entry => entry.statePath === statePath(installation))!.segmentPath;
  await configureTestStateEncryption(keyringPath, [{ keyId: "old-key", status: "decrypt-only", fillByte: 0x41 },
    { keyId: "new-key", status: "active", fillByte: 0x42 }]);
  const inspected = await runGatewayStateMaintenance("inspect");
  assert.ok(inspected.some(result => result.file === orphan && result.keyId === "old-key" && result.statusBefore === "encrypted-decrypt-only"));
  const rotated = await runGatewayStateMaintenance("rotate");
  assert.ok(rotated.some(result => result.file === orphan && result.action === "rotated"));
  await configureTestStateEncryption(keyringPath, [{ keyId: "new-key", status: "active", fillByte: 0x42 }]);
  resetGatewayStateEncryptionForTests();
  assert.ok((await runGatewayStateMaintenance("inspect")).every(result => result.statusBefore === "encrypted-active"));
  assert.equal((await save(installation, "rotation_archive_orphan_request", 4097)).status, 201);
});
