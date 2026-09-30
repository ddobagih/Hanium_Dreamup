/**
 * S19-52: independent verifier for the request-bound v4 Backend actor assertion.
 * It recomputes the HMAC from what a fake Backend actually received (method,
 * origin-form target, body bytes) instead of trusting Gateway helpers.
 */
import assert from "node:assert/strict";
import { createHash, createHmac } from "node:crypto";

export type ExpectedBackendActor = {
  actorId: string;
  accountGeneration: number;
  authEpoch: number | null;
  secret?: string;
  nowSeconds?: number;
};

export type VerifiedBackendActorV4 = {
  issuedAt: number;
  expiresAt: number;
  jti: string;
  bodySha256: string;
  target: string;
  method: string;
};

export function upstreamBodyBytes(init?: RequestInit): Buffer {
  const body = init?.body;
  if (body === undefined || body === null) return Buffer.alloc(0);
  if (typeof body === "string") return Buffer.from(body, "utf8");
  if (body instanceof ArrayBuffer) return Buffer.from(body);
  if (ArrayBuffer.isView(body)) {
    return Buffer.from(body.buffer, body.byteOffset, body.byteLength);
  }
  assert.fail(`upstream body is not a hashable byte sequence: ${Object.prototype.toString.call(body)}`);
}

export function upstreamTarget(input: string | URL | Request): string {
  const url = new URL(input instanceof Request ? input.url : String(input));
  return `${url.pathname}${url.search}`;
}

export async function upstreamFormData(init?: RequestInit): Promise<FormData> {
  const contentType = new Headers(init?.headers).get("content-type") ?? "";
  assert.match(contentType, /^multipart\/form-data; boundary=/);
  return await new Response(new Uint8Array(upstreamBodyBytes(init)), {
    headers: { "content-type": contentType }
  }).formData();
}

export function assertBackendActorAssertionV4(
  input: string | URL | Request,
  init: RequestInit | undefined,
  expected: ExpectedBackendActor
): VerifiedBackendActorV4 {
  const secret = expected.secret ?? process.env.WALKSAFE_GATEWAY_SESSION_SECRET?.trim() ?? "";
  assert.ok(secret.length >= 32, "test secret must be configured");
  const headers = new Headers(init?.headers);
  assert.equal(headers.get("x-walksafe-actor-id"), expected.actorId);
  assert.equal(headers.get("x-walksafe-account-generation"), String(expected.accountGeneration));
  const token = headers.get("x-walksafe-actor-assertion") ?? "";
  const parts = token.split(".");
  assert.equal(parts.length, 7, `v4 assertion has seven parts: ${token}`);
  const [version, iatText, expText, epochText, kid, jti, signature] = parts as [
    string, string, string, string, string, string, string
  ];
  assert.equal(version, "v4");
  assert.match(iatText, /^[1-9][0-9]{0,11}$/);
  assert.match(expText, /^[1-9][0-9]{0,11}$/);
  const issuedAt = Number(iatText);
  const expiresAt = Number(expText);
  assert.equal(expiresAt - issuedAt, 30);
  const now = expected.nowSeconds ?? Math.floor(Date.now() / 1000);
  assert.ok(Math.abs(now - issuedAt) <= 60, "assertion must be issued at request time");
  assert.equal(epochText, String(expected.authEpoch ?? 0));
  const expectedKid = createHmac("sha256", secret)
    .update("walksafe-backend-actor-kid-v1")
    .digest("base64url")
    .slice(0, 16);
  assert.equal(kid, expectedKid);
  assert.match(jti, /^[A-Za-z0-9_-]{22}$/);
  const method = (init?.method ?? "GET").toUpperCase();
  const target = upstreamTarget(input);
  const bodySha256 = createHash("sha256").update(upstreamBodyBytes(init)).digest("hex");
  const message = [
    "walksafe-backend-actor-v4",
    "aud:walksafe-backend",
    `kid:${expectedKid}`,
    "access:field",
    `actor:${expected.actorId}`,
    `generation:${expected.accountGeneration}`,
    `epoch:${expected.authEpoch ?? 0}`,
    `iat:${issuedAt}`,
    `exp:${expiresAt}`,
    `jti:${jti}`,
    `method:${method}`,
    `target:${target}`,
    `body-sha256:${bodySha256}`
  ].join("\n");
  assert.equal(
    signature,
    createHmac("sha256", secret).update(message, "utf8").digest("base64url"),
    "v4 signature must bind the request the Backend received"
  );
  return { issuedAt, expiresAt, jti, bodySha256, target, method };
}
