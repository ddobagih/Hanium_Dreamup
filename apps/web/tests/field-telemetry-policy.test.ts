import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readFile, rm, stat, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import {
  FIELD_TELEMETRY_CONSENT_MAX_AGE_MS,
  FIELD_TELEMETRY_CONSENT_VERSION,
  isFieldTelemetryConsentRecordValid
} from "../app/_walksafe/hooks/useFieldTestTelemetry";
import { verifiedRuntimeSourceCommit } from "../app/api/_runtime-source-identity";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function envelope(payload: Record<string, unknown> = { online: true }) {
  return {
    schema_version: "walksafe.field-telemetry.v1",
    session_id: "web-field-telemetry-test",
    captured_at: new Date().toISOString(),
    event_type: "heartbeat",
    payload
  };
}

async function main() {
  const consentNow = Date.parse("2026-07-11T03:00:00.000Z");
  const consentRecord = {
    version: FIELD_TELEMETRY_CONSENT_VERSION,
    actor_id: "tester.kim",
    accepted_at: new Date(consentNow).toISOString(),
    expires_at: new Date(consentNow + FIELD_TELEMETRY_CONSENT_MAX_AGE_MS).toISOString()
  };
  assert(isFieldTelemetryConsentRecordValid(consentRecord, "tester.kim", consentNow), "current actor consent should pass");
  assert(!isFieldTelemetryConsentRecordValid(consentRecord, "tester.lee", consentNow), "one actor must not inherit another actor's consent");
  assert(
    !isFieldTelemetryConsentRecordValid(consentRecord, "tester.kim", consentNow + FIELD_TELEMETRY_CONSENT_MAX_AGE_MS),
    "telemetry consent must expire after its bounded lifetime"
  );

  const root = await mkdtemp(path.join(os.tmpdir(), "walksafe-field-telemetry-"));
  const gatewayRoot = await mkdtemp(path.join(os.tmpdir(), "walksafe-field-telemetry-gateway-"));
  const buildRoot = await mkdtemp(path.join(os.tmpdir(), "walksafe-field-telemetry-build-"));
  const standaloneBuildRoot = await mkdtemp(path.join(os.tmpdir(), "walksafe-field-telemetry-standalone-"));
  const conflictingBuildRoot = await mkdtemp(path.join(os.tmpdir(), "walksafe-field-telemetry-conflict-"));
  const originalCwd = process.cwd();
  const sourceCommit = "a".repeat(40);
  await mkdir(path.join(buildRoot, ".next"), { recursive: true, mode: 0o700 });
  await writeFile(path.join(buildRoot, ".next", "BUILD_ID"), `${sourceCommit}\n`, { encoding: "utf8", mode: 0o600 });
  await writeFile(path.join(standaloneBuildRoot, "BUILD_ID"), `${sourceCommit}\n`, { encoding: "utf8", mode: 0o600 });
  await writeFile(path.join(conflictingBuildRoot, "BUILD_ID"), `${sourceCommit}\n`, { encoding: "utf8", mode: 0o600 });
  await mkdir(path.join(conflictingBuildRoot, ".next"), { recursive: true, mode: 0o700 });
  await writeFile(path.join(conflictingBuildRoot, ".next", "BUILD_ID"), `${"b".repeat(40)}\n`, {
    encoding: "utf8",
    mode: 0o600
  });
  assert(
    await verifiedRuntimeSourceCommit(buildRoot, sourceCommit) === sourceCommit,
    "next start must bind .next/BUILD_ID to the configured source commit"
  );
  assert(
    await verifiedRuntimeSourceCommit(standaloneBuildRoot, sourceCommit) === sourceCommit,
    "standalone runtime must bind root BUILD_ID to the configured source commit"
  );
  assert(
    await verifiedRuntimeSourceCommit(conflictingBuildRoot, sourceCommit) === null,
    "conflicting root and .next build ids must fail closed"
  );
  process.chdir(buildRoot);
  const loadAccounts = Array.from({ length: 12 }, (_value, index) => ({
    actor_id: `tester.load.${index + 1}`,
    token: `tester-load-${index + 1}-telemetry-token-123456789`
  }));
  process.env.WALKSAFE_FIELD_LOG_DIR = root;
  process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = gatewayRoot;
  process.env.WALKSAFE_FIELD_TEST_TOKEN = "field-token-for-telemetry-tests-123456";
  process.env.WALKSAFE_ADMIN_TOKEN = "admin-token-for-telemetry-tests-123456";
  process.env.WALKSAFE_GATEWAY_SESSION_SECRET = "telemetry-session-secret-for-tests-123456789";
  process.env.WALKSAFE_ENVIRONMENT = "field";
  process.env.WALKSAFE_SOURCE_COMMIT = sourceCommit;
  process.env.WALKSAFE_FIELD_ACCOUNTS_JSON = JSON.stringify([
    { actor_id: "tester.kim", token: "tester-kim-telemetry-token-123456" },
    { actor_id: "tester.lee", token: "tester-lee-telemetry-token-123456" },
    ...loadAccounts
  ]);

  const gateway = await import("../app/api/_gateway-auth");
  const route = await import("../app/api/walksafe-field-log/route");
  const fieldSession = gateway.establishGatewaySession(
    "field",
    new Request("http://localhost/api/field-session"),
    "tester.kim"
  );
  const fieldCookie = (fieldSession.headers.get("set-cookie") ?? "").split(";", 1)[0];
  const otherFieldSession = gateway.establishGatewaySession(
    "field",
    new Request("http://localhost/api/field-session"),
    "tester.lee"
  );
  const otherFieldCookie = (otherFieldSession.headers.get("set-cookie") ?? "").split(";", 1)[0];
  const adminSession = gateway.establishGatewaySession("admin", new Request("http://localhost/api/admin-session"));
  const adminCookie = (adminSession.headers.get("set-cookie") ?? "").split(";", 1)[0];

  const fieldCookieFor = (actorId: string) => {
    const session = gateway.establishGatewaySession(
      "field",
      new Request("http://localhost/api/field-session"),
      actorId
    );
    return (session.headers.get("set-cookie") ?? "").split(";", 1)[0];
  };

  const send = (body: unknown, cookie = fieldCookie, consent = true) =>
    route.POST(
      new Request("http://localhost/api/walksafe-field-log", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          cookie,
          ...(consent ? { "x-walksafe-telemetry-consent": "walksafe.telemetry-consent.v1" } : {})
        },
        body: JSON.stringify(body)
      })
    );

  const deleteSession = (sessionId: string, cookie = fieldCookie) =>
    route.DELETE(
      new Request(`http://localhost/api/walksafe-field-log?session_id=${encodeURIComponent(sessionId)}`, {
        method: "DELETE",
        headers: { cookie }
      })
    );

  const responseCode = async (response: Response): Promise<string | null> => {
    const payload = await response.clone().json() as { code?: unknown };
    return typeof payload.code === "string" ? payload.code : null;
  };

  const missingConsent = await send(envelope(), fieldCookie, false);
  assert(missingConsent.status === 403, "telemetry must require explicit versioned consent");

  const adminDenied = await send(envelope(), adminCookie);
  assert(adminDenied.status === 401, "admin session must not collect pedestrian field telemetry");

  const unknownPayload = await send(envelope({ online: true, arbitrary: "value" }));
  assert(unknownPayload.status === 422, "unknown root payload fields must be rejected");

  const mediaBypassSession = "web-field-top-level-media-test";
  const topLevelMedia = await send({
    ...envelope(),
    session_id: mediaBypassSession,
    raw_image_base64: "A".repeat(1024)
  });
  assert(topLevelMedia.status === 422, "unknown top-level media fields must be rejected");
  const mediaBypassPath = path.join(root, new Date().toISOString().slice(0, 10), `${mediaBypassSession}.jsonl`);
  const mediaBypassPersisted = await stat(mediaBypassPath).then(
    () => true,
    (error: NodeJS.ErrnoException) => {
      if (error.code === "ENOENT") return false;
      throw error;
    }
  );
  assert(!mediaBypassPersisted, "rejected top-level media must not be persisted");

  const rawMedia = await send(
    envelope({
      detections: [{ class_name: "person", raw_image: "A".repeat(256) }]
    })
  );
  assert(rawMedia.status === 422, "raw media and base64-shaped nested fields must be rejected");

  const staleClock = await send({ ...envelope(), captured_at: "2000-01-01T00:00:00.000Z" });
  assert(staleClock.status === 422, "client timestamps with excessive skew must be rejected");

  const created = await send(
    envelope({
      online: true,
      gps: { latitude: 37.566512345, longitude: 126.978012345, accuracy_m: 8 },
      viewport: { width: 390, height: 844, device_pixel_ratio: 3 },
      non_metric_advisory_active: true,
      non_metric_advisory_tier: "CAMERA_NON_METRIC_ADVISORY",
      non_metric_advisory_direction: "front",
      non_metric_advisory_message: "카메라 기준 정면에 장애물 감지. TMAP 길 안내를 기준으로 주변을 확인하세요.",
      non_metric_advisory_consecutive_frames: 3,
      non_metric_advisory_stable_ms: 700,
      non_metric_advisory_max_gps_accuracy_m: 15,
      non_metric_advisory_metric: false,
      non_metric_advisory_tmap_authoritative: true,
      non_metric_advisory_reports_allowed: false
    })
  );
  const createdFailure = created.status === 201 ? "" : `: ${created.status} ${await created.clone().text()}`;
  assert(created.status === 201, `valid consented telemetry must be stored${createdFailure}`);
  const serverDate = new Date().toISOString().slice(0, 10);
  const recordPath = path.join(root, serverDate, "web-field-telemetry-test.jsonl");
  const record = JSON.parse((await readFile(recordPath, "utf8")).trim()) as {
    actor_id?: string;
    received_at?: string;
    consent_version?: string;
    server_source_commit?: string;
    payload?: {
      gps?: { latitude?: number; longitude?: number };
      non_metric_advisory_active?: boolean;
      non_metric_advisory_tier?: string;
      non_metric_advisory_direction?: string;
      non_metric_advisory_consecutive_frames?: number;
      non_metric_advisory_stable_ms?: number;
      non_metric_advisory_max_gps_accuracy_m?: number;
      non_metric_advisory_metric?: boolean;
      non_metric_advisory_tmap_authoritative?: boolean;
      non_metric_advisory_reports_allowed?: boolean;
    };
  };
  assert(Boolean(record.received_at), "server receipt time must be persisted");
  assert(record.actor_id === "tester.kim", "the authenticated telemetry actor must be persisted");
  assert(record.consent_version === "walksafe.telemetry-consent.v1", "consent version must be auditable");
  assert(record.server_source_commit === sourceCommit, "telemetry must carry the exact server build source commit");
  assert(record.payload?.gps?.latitude === 37.56651, "telemetry latitude must be minimized server-side");
  assert(record.payload?.gps?.longitude === 126.97801, "telemetry longitude must be minimized server-side");
  assert(record.payload?.non_metric_advisory_active === true, "non-metric advisory activity must remain separate evidence");
  assert(record.payload?.non_metric_advisory_tier === "CAMERA_NON_METRIC_ADVISORY", "the advisory tier must be persisted");
  assert(record.payload?.non_metric_advisory_direction === "front", "the screen-relative advisory direction must be persisted");
  assert(record.payload?.non_metric_advisory_consecutive_frames === 3, "three distinct inference frames must be auditable");
  assert(record.payload?.non_metric_advisory_stable_ms === 700, "the 700ms continuity gate must be auditable");
  assert(record.payload?.non_metric_advisory_max_gps_accuracy_m === 15, "the runtime GPS threshold must be auditable");
  assert(record.payload?.non_metric_advisory_metric === false, "the Web advisory must explicitly remain non-metric");
  assert(record.payload?.non_metric_advisory_tmap_authoritative === true, "TMAP must remain route-authoritative");
  assert(record.payload?.non_metric_advisory_reports_allowed === false, "the advisory must not gain report authority");
  const ownerSidecar = await readFile(path.join(root, ".owners", "web-field-telemetry-test.owner"), "utf8");
  assert(!ownerSidecar.includes("tester.kim"), "owner sidecars must not retain the raw actor id");

  process.env.WALKSAFE_FIELD_LOG_MAX_SESSIONS = "1";
  const existingAtSessionBoundary = await send(envelope({ online: false }));
  assert(existingAtSessionBoundary.status === 201, "the session hard cap must still allow an existing session");
  const sessionQuota = await send({ ...envelope(), session_id: "web-field-session-quota-test" });
  assert(sessionQuota.status === 507, "the session hard cap must reject a new session at N+1");
  assert(await responseCode(sessionQuota) === "field_log_storage_session_quota", "session quota failures must be explicit");
  delete process.env.WALKSAFE_FIELD_LOG_MAX_SESSIONS;

  process.env.WALKSAFE_FIELD_LOG_MAX_FILES = "2";
  const existingAtFileBoundary = await send(envelope({ online: true }));
  assert(existingAtFileBoundary.status === 201, "the file hard cap must still allow append-only writes");
  const fileQuota = await send({ ...envelope(), session_id: "web-field-file-quota-test" });
  assert(fileQuota.status === 507, "the file hard cap must reject new owner and log files at N+1");
  assert(await responseCode(fileQuota) === "field_log_storage_file_quota", "file quota failures must be explicit");
  delete process.env.WALKSAFE_FIELD_LOG_MAX_FILES;

  const storedBytes = (await stat(recordPath)).size + (await stat(path.join(root, ".owners", "web-field-telemetry-test.owner"))).size;
  process.env.WALKSAFE_FIELD_LOG_MAX_TOTAL_BYTES = String(storedBytes);
  const totalQuota = await send(envelope({ online: true }));
  assert(totalQuota.status === 507, "the byte hard cap must reject an append at N+1 bytes");
  assert(await responseCode(totalQuota) === "field_log_storage_total_quota", "byte quota failures must be explicit");
  delete process.env.WALKSAFE_FIELD_LOG_MAX_TOTAL_BYTES;

  const crossActorAppend = await send(envelope(), otherFieldCookie);
  assert(crossActorAppend.status === 403, "another field actor must not append to an owned telemetry session");
  const crossActorDelete = await route.DELETE(
    new Request("http://localhost/api/walksafe-field-log?session_id=web-field-telemetry-test", {
      method: "DELETE",
      headers: { cookie: otherFieldCookie }
    })
  );
  assert(crossActorDelete.status === 403, "another field actor must not delete an owned telemetry session");
  assert(await stat(recordPath).then(() => true, () => false), "cross-actor deletion must preserve the owner log");

  const deleted = await route.DELETE(
    new Request("http://localhost/api/walksafe-field-log?session_id=web-field-telemetry-test", {
      method: "DELETE",
      headers: { cookie: fieldCookie }
    })
  );
  assert(deleted.status === 200, "consent withdrawal must be able to delete the current session log");
  let stillExists = true;
  try {
    await stat(recordPath);
  } catch {
    stillExists = false;
  }
  assert(!stillExists, "session deletion must remove its JSONL records from every date directory");

  const lateHeartbeat = await send(envelope());
  assert(lateHeartbeat.status === 409, "a late in-flight heartbeat must not recreate a withdrawn session log");
  assert(!(await stat(recordPath).then(() => true, () => false)), "withdrawn session tombstone must keep the log deleted");

  const raceSessionId = "web-field-first-write-race";
  let raceBodyController!: ReadableStreamDefaultController<Uint8Array>;
  const raceBody = new ReadableStream<Uint8Array>({
    start(controller) {
      raceBodyController = controller;
    }
  });
  const racedPost = route.POST(
    new Request("http://localhost/api/walksafe-field-log", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        cookie: fieldCookie,
        "x-walksafe-telemetry-consent": "walksafe.telemetry-consent.v1"
      },
      body: raceBody,
      duplex: "half"
    } as RequestInit & { duplex: "half" })
  );
  await new Promise<void>((resolve) => setImmediate(resolve));
  const racedDelete = await deleteSession(raceSessionId);
  assert(racedDelete.status === 200, "withdrawal must claim and revoke a session before its first POST finishes");
  raceBodyController.enqueue(new TextEncoder().encode(JSON.stringify({ ...envelope(), session_id: raceSessionId })));
  raceBodyController.close();
  const racedPostResponse = await racedPost;
  assert(racedPostResponse.status === 409, "a first POST released after withdrawal must observe the tombstone");
  const raceRecordPath = path.join(root, serverDate, `${raceSessionId}.jsonl`);
  assert(!(await stat(raceRecordPath).then(() => true, () => false)), "withdrawal must prevent a delayed first JSONL write");
  const repeatedDelete = await deleteSession(raceSessionId);
  assert(repeatedDelete.status === 200, "session withdrawal must be idempotent for the owning actor");
  const crossActorRaceDelete = await deleteSession(raceSessionId, otherFieldCookie);
  assert(crossActorRaceDelete.status === 403, "another actor must not reuse an owner-bound tombstone");
  const crossActorRacePost = await send({ ...envelope(), session_id: raceSessionId }, otherFieldCookie);
  assert(crossActorRacePost.status === 403, "another actor must not append through an owner-bound tombstone");

  const legacySessionId = "web-field-legacy-tombstone";
  const legacyOwnerBinding = createHash("sha256")
    .update("walksafe-field-owner-v1\0tester.kim")
    .digest("hex");
  await mkdir(path.join(root, ".owners"), { recursive: true, mode: 0o700 });
  await mkdir(path.join(root, ".revoked"), { recursive: true, mode: 0o700 });
  await writeFile(path.join(root, ".owners", `${legacySessionId}.owner`), `${legacyOwnerBinding}\n`, { mode: 0o600 });
  await writeFile(path.join(root, ".revoked", `${legacySessionId}.revoked`), "2026-07-11T00:00:00.000Z\n", { mode: 0o600 });
  const legacyDelete = await deleteSession(legacySessionId);
  assert(legacyDelete.status === 200, "legacy timestamp tombstones with a valid owner must remain withdrawable");
  assert(
    (await readFile(path.join(root, ".revoked", `${legacySessionId}.revoked`), "utf8")).trim() === legacyOwnerBinding,
    "legacy tombstones must be upgraded to the privacy-safe owner binding"
  );
  assert(
    !(await stat(path.join(root, ".owners", `${legacySessionId}.owner`)).then(() => true, () => false)),
    "an upgraded tombstone must replace its redundant owner sidecar"
  );

  const stalledPosts: Array<{ pending: Promise<Response>; abort: AbortController }> = [];
  for (let index = 0; index < 8; index += 1) {
    const abort = new AbortController();
    const body = new ReadableStream<Uint8Array>({ start() {} });
    const pending = route.POST(
      new Request("http://localhost/api/walksafe-field-log", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          cookie: fieldCookie,
          "x-walksafe-telemetry-consent": "walksafe.telemetry-consent.v1"
        },
        body,
        signal: abort.signal,
        duplex: "half"
      } as RequestInit & { duplex: "half" })
    );
    stalledPosts.push({ pending, abort });
  }
  const postBusy = await send({ ...envelope(), session_id: "web-field-post-admission-overflow" });
  assert(postBusy.status === 503, "the POST admission gate must reject N+1 before reading its body");
  assert(await responseCode(postBusy) === "field_log_busy", "POST admission failures must be explicit");
  for (const stalled of stalledPosts) stalled.abort.abort();
  const cancelledPosts = await Promise.all(stalledPosts.map(({ pending }) => pending));
  assert(cancelledPosts.every((response) => response.status === 499), "aborted body readers must release every POST slot");
  const postAfterRelease = await send({ ...envelope(), session_id: "web-field-post-admission-released" });
  assert(postAfterRelease.status === 201, "POST admission capacity must recover after cancellation");

  const deleteOne = deleteSession("web-field-delete-admission-1");
  const deleteTwo = deleteSession("web-field-delete-admission-2");
  const deleteThree = deleteSession("web-field-delete-admission-3");
  const deleteBusy = await deleteThree;
  assert(deleteBusy.status === 503, "the DELETE admission gate must reject N+1 concurrent operations");
  assert(await responseCode(deleteBusy) === "field_log_busy", "DELETE admission failures must be explicit");
  assert((await deleteOne).status === 200 && (await deleteTwo).status === 200, "admitted DELETE operations must finish");
  assert((await deleteSession("web-field-delete-admission-3")).status === 200, "DELETE capacity must recover after release");

  process.env.WALKSAFE_FIELD_LOG_MAX_FILES = "invalid";
  const invalidStorageConfig = await send({ ...envelope(), session_id: "web-field-invalid-storage-config" });
  assert(invalidStorageConfig.status === 503, "invalid explicit storage limits must fail closed");
  assert(
    await responseCode(invalidStorageConfig) === "field_log_storage_config_invalid",
    "invalid storage configuration must not silently use a fallback"
  );
  delete process.env.WALKSAFE_FIELD_LOG_MAX_FILES;

  process.env.WALKSAFE_FIELD_LOG_MIN_FREE_BYTES = String(1024 * 1024 * 1024 * 1024);
  const lowDisk = await send({ ...envelope(), session_id: "web-field-low-disk-test" });
  assert(lowDisk.status === 507, "the configured free-byte floor must fail closed");
  assert(await responseCode(lowDisk) === "field_log_storage_low_disk", "free-byte failures must be explicit");
  delete process.env.WALKSAFE_FIELD_LOG_MIN_FREE_BYTES;

  process.env.WALKSAFE_FIELD_LOG_MIN_FREE_INODES = "1000000000";
  const lowInodes = await send({ ...envelope(), session_id: "web-field-low-inode-test" });
  assert(lowInodes.status === 507, "the configured free-inode floor must fail closed");
  assert(await responseCode(lowInodes) === "field_log_storage_low_inodes", "free-inode failures must be explicit");
  delete process.env.WALKSAFE_FIELD_LOG_MIN_FREE_INODES;

  const unsafeEntry = path.join(root, "unsafe-entry");
  await symlink(root, unsafeEntry);
  const unsafeStorage = await send({ ...envelope(), session_id: "web-field-unsafe-storage-test" });
  assert(unsafeStorage.status === 503, "symlink storage entries must fail closed");
  assert(await responseCode(unsafeStorage) === "field_log_storage_unsafe", "unsafe storage failures must be explicit");
  await rm(unsafeEntry);

  const oversizedSidecarSession = "web-field-oversized-sidecar";
  const oversizedSidecarPath = path.join(root, ".owners", `${oversizedSidecarSession}.owner`);
  await writeFile(oversizedSidecarPath, `${legacyOwnerBinding}${" ".repeat(193)}`, { mode: 0o600 });
  const oversizedSidecar = await send({ ...envelope(), session_id: oversizedSidecarSession });
  assert(oversizedSidecar.status === 503, "owner sidecars must be bounded before their contents are read");
  assert(await responseCode(oversizedSidecar) === "field_log_storage_unsafe", "oversized sidecars must fail closed");
  await rm(oversizedSidecarPath);

  let actorRateLimited: Response | null = null;
  const rotatedCookies = new Set<string>();
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const rotatedCookie = fieldCookieFor("tester.kim");
    rotatedCookies.add(rotatedCookie);
    const response = await send({ ...envelope(), session_id: raceSessionId }, rotatedCookie);
    if (response.status === 429) {
      actorRateLimited = response;
      break;
    }
    assert(response.status === 409, "the owning actor's revoked session must remain closed while counting rate");
  }
  assert(rotatedCookies.size > 1, "the rate test must exercise multiple replacement session cookies");
  assert(actorRateLimited?.status === 429, "cookie rotation must not reset the authenticated actor rate");
  assert(
    await responseCode(actorRateLimited) === "field_log_actor_rate_limited",
    "actor and global rate failures must remain distinguishable"
  );
  assert(Number(actorRateLimited.headers.get("retry-after")) > 0, "actor rate limits must return Retry-After");

  let globalPostRateLimited: Response | null = null;
  for (const account of loadAccounts) {
    const cookie = fieldCookieFor(account.actor_id);
    for (let attempt = 0; attempt < 95; attempt += 1) {
      const response = await send({ ...envelope(), session_id: raceSessionId }, cookie);
      const code = response.status === 429 ? await responseCode(response) : null;
      if (code === "field_log_global_rate_limited") {
        globalPostRateLimited = response;
        break;
      }
      if (code === "field_log_actor_rate_limited") break;
      assert(response.status === 403, "cross-actor tombstone probes must remain forbidden before rate exhaustion");
    }
    if (globalPostRateLimited) break;
  }
  assert(globalPostRateLimited?.status === 429, "POST admission must enforce a service-global rate limit");
  assert(Number(globalPostRateLimited.headers.get("retry-after")) > 0, "global POST rate limits must return Retry-After");

  let deleteActorRateObserved = false;
  let globalDeleteRateLimited: Response | null = null;
  for (const [accountIndex, account] of loadAccounts.entries()) {
    const cookie = fieldCookieFor(account.actor_id);
    for (let attempt = 0; attempt < 35; attempt += 1) {
      const response = await deleteSession(`web-field-delete-rate-${accountIndex}-${attempt}`, cookie);
      const code = response.status === 429 ? await responseCode(response) : null;
      if (code === "field_log_global_rate_limited") {
        globalDeleteRateLimited = response;
        break;
      }
      if (code === "field_log_actor_rate_limited") {
        deleteActorRateObserved = true;
        break;
      }
      assert(response.status === 200, "admitted DELETE requests must create idempotent owner-bound tombstones");
    }
    if (globalDeleteRateLimited) break;
  }
  assert(deleteActorRateObserved, "DELETE admission must enforce its actor rate limit");
  assert(globalDeleteRateLimited?.status === 429, "DELETE admission must enforce its service-global rate limit");

  process.chdir(originalCwd);
  await rm(root, { recursive: true, force: true });
  await rm(gatewayRoot, { recursive: true, force: true });
  await rm(buildRoot, { recursive: true, force: true });
  await rm(standaloneBuildRoot, { recursive: true, force: true });
  await rm(conflictingBuildRoot, { recursive: true, force: true });
  delete process.env.WALKSAFE_ENVIRONMENT;
  delete process.env.WALKSAFE_SOURCE_COMMIT;

  console.log("field telemetry policy checks passed");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
