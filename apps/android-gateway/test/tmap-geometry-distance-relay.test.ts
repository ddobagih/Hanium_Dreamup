import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { createServer, type IncomingHttpHeaders, type Server } from "node:http";
import { type AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

const ACTOR_ID = "tmap-distance-operator";
const ACCOUNT_TOKEN = "tmap-distance-account-token-12345678901234567890";
const SERVICE_TOKEN = "tmap-distance-service-token-12345678901234567890";

type NormalizedRoute = {
  distance_source: string;
  priority: string;
  summary: { distance_m: number };
  steps: Array<{ distance_m: number }>;
  guide_points: Array<{ distance_from_start_m: number; remaining_distance_m: number }>;
};
type ApiFixture = {
  request: Record<string, unknown>;
  status: number;
  response: NormalizedRoute | { detail: { code: string; message: string } };
  provider_input: {
    features: Array<{ geometry: { type: string }; properties: Record<string, unknown> }>;
  };
};
type UpstreamRequest = {
  method: string | undefined;
  url: string | undefined;
  headers: IncomingHttpHeaders;
  body: string;
};

async function listen(server: Server): Promise<string> {
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      server.off("error", reject);
      resolve();
    });
  });
  return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
}

async function close(server: Server): Promise<void> {
  server.closeAllConnections();
  await new Promise<void>((resolve) => server.close(() => resolve()));
}

// These snapshots were captured on 2026-09-26 from fetch_tmap_pedestrian_route ->
// spawned TmapRuntime -> FastAPI /navigation/walking, with httpx.MockTransport
// only at the external provider boundary. provider_input is the unnormalized
// input to that pipeline; the response is its real JSON output, not a second
// implementation of the distance calculation in this test.
// The normal npm test runner executes with apps/android-gateway as its cwd.
async function fixture(name: string): Promise<ApiFixture> {
  return JSON.parse(await readFile(
    path.join("test", "fixtures", "tmap-geometry-distance-relay", `${name}.json`),
    "utf8"
  )) as ApiFixture;
}

test("HTTP Gateway preserves actual Backend STAIR_AVOID distance results", { timeout: 15_000 }, async (t) => {
  const stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-tmap-distance-relay-"));
  const upstreamRequests: UpstreamRequest[] = [];
  let activeFixture: ApiFixture | undefined;
  const backend = createServer((request, response) => {
    void (async () => {
      const chunks: Buffer[] = [];
      for await (const chunk of request) chunks.push(Buffer.from(chunk));
      upstreamRequests.push({
        method: request.method,
        url: request.url,
        headers: request.headers,
        body: Buffer.concat(chunks).toString("utf8")
      });
      if (!activeFixture || request.url !== "/navigation/walking") {
        response.writeHead(404).end();
        return;
      }
      response.writeHead(activeFixture.status, { "content-type": "application/json" });
      response.end(JSON.stringify(activeFixture.response));
    })().catch(() => response.writeHead(500).end());
  });
  let gateway: Server | undefined;
  const environment: Record<string, string | undefined> = {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: SERVICE_TOKEN,
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([{ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN }]),
    WALKSAFE_GATEWAY_SESSION_SECRET: "tmap-distance-session-secret-123456789012345678901234567890",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "walk-ledger.json"),
    WALKSAFE_GATEWAY_STATE_KEYRING_FILE: path.join(stateDirectory, "state-keyring.json"),
    WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "false",
    WALKSAFE_ADMIN_TOKEN: undefined,
    WALKSAFE_ADMIN_ACCOUNTS_JSON: undefined
  };
  const previous = new Map(Object.keys(environment).map((key) => [key, process.env[key]]));

  try {
    for (const [key, value] of Object.entries(environment)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
    const backendUrl = await listen(backend);
    // Keep the fixed production URL contract. Only the injected HTTP transport
    // maps that exact endpoint to our private ephemeral server; it cannot
    // contact an existing Backend process on port 8000.
    const { createGatewayServer } = await import("../src/node-adapter.js");
    const { ACTOR_ID_HEADER, FIELD_TEST_TOKEN_HEADER } = await import("../src/backend.js");
    const { configureTestStateEncryption } = await import("./state-encryption-fixture.js");
    await configureTestStateEncryption(environment.WALKSAFE_GATEWAY_STATE_KEYRING_FILE!);
    gateway = createGatewayServer({
      fetchImpl: async (input, init) => {
        assert.equal(String(input), "http://127.0.0.1:8000/navigation/walking");
        return fetch(`${backendUrl}/navigation/walking`, init);
      }
    });
    const gatewayUrl = await listen(gateway);
    const login = await fetch(`${gatewayUrl}/api/field-session`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN })
    });
    assert.equal(login.status, 200, await login.text());
    const cookie = login.headers.get("set-cookie")?.split(";")[0];
    assert.ok(cookie);
    assert.ok(cookie.startsWith("walksafe_field_session="));
    assert.equal(upstreamRequests.length, 0);

    for (const [name, expectedSource] of [
      ["nonuniform-total", "provider_total_geometry_scaled"],
      ["nonuniform-step-sum", "provider_step_sum_geometry_scaled"],
      ["ambiguous-guide", null]
    ] as const) {
      await t.test(name, async () => {
        const captured = await fixture(name);
        activeFixture = captured;
        upstreamRequests.length = 0;
        assert.equal(captured.request.priority, "STAIR_AVOID");
        const requestBody = JSON.stringify(captured.request);
        const response = await fetch(`${gatewayUrl}/api/navigation/walking`, {
          method: "POST",
          headers: { "content-type": "application/json", cookie },
          body: requestBody
        });
        assert.equal(response.status, captured.status);
        assert.equal(response.headers.get("cache-control"), "no-store");
        const raw = await response.text();
        assert.equal(raw, JSON.stringify(captured.response), "the entire Backend JSON must survive HTTP relay");
        assert.deepEqual(JSON.parse(raw), captured.response);
        assert.equal(upstreamRequests.length, 1);
        const forwarded = upstreamRequests[0]!;
        assert.equal(forwarded.method, "POST");
        assert.equal(forwarded.url, "/navigation/walking");
        assert.equal(forwarded.body, requestBody);
        assert.equal(forwarded.headers[ACTOR_ID_HEADER.toLowerCase()], ACTOR_ID);
        assert.equal(forwarded.headers[FIELD_TEST_TOKEN_HEADER.toLowerCase()], SERVICE_TOKEN);

        if (expectedSource === null) {
          assert.equal(response.status, 502);
          assert.ok("detail" in captured.response);
          assert.equal(captured.response.detail.code, "invalid_tmap_response");
          assert.equal(captured.response.detail.message,
            "TMAP pedestrian route guide position is ambiguous or out of order.");
          return;
        }

        assert.equal(response.status, 200);
        assert.ok("distance_source" in captured.response);
        const route = JSON.parse(raw) as NormalizedRoute;
        assert.equal(route.distance_source, expectedSource);
        assert.equal(route.priority, "STAIR_AVOID");
        assert.equal(route.summary.distance_m, 200);
        assert.deepEqual(captured.provider_input.features
          .filter((feature) => feature.geometry.type === "LineString")
          .map((feature) => feature.properties.distance), [160, 40]);
        assert.deepEqual(route.steps.map((step) => step.distance_m), [100, 100]);
        assert.equal(route.steps.reduce((sum, step) => sum + step.distance_m, 0), route.summary.distance_m);
        assert.deepEqual(route.guide_points.map((guide) => [
          guide.distance_from_start_m, guide.remaining_distance_m
        ]), [[0, 200], [100, 100], [200, 0]]);
        for (const guide of route.guide_points) {
          assert.equal(guide.distance_from_start_m + guide.remaining_distance_m, route.summary.distance_m);
        }
      });
    }
  } finally {
    await Promise.all([gateway ? close(gateway) : Promise.resolve(), close(backend)]);
    await rm(stateDirectory, { recursive: true, force: true });
    for (const [key, value] of previous) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
});
