import { spawnSync } from "node:child_process";
import path from "node:path";
import {
  isProtectedWebUpstreamRuntime,
  resolveUpstreamBaseUrl
} from "../app/api/_backend";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const BACKEND_BASE = "http://127.0.0.1:8000";
const VOICE_BASE = "http://127.0.0.1:9001";

function loadBackendModule(environment: NodeJS.ProcessEnv) {
  const modulePath = path.resolve(__dirname, "../app/api/_backend.js");
  return spawnSync(process.execPath, ["-e", "require(process.argv[1])", modulePath], {
    env: environment,
    encoding: "utf8"
  });
}

function protectedEnvironment(overrides: Record<string, string> = {}): NodeJS.ProcessEnv {
  const environment = { ...process.env } as NodeJS.ProcessEnv;
  Object.assign(
    environment,
    {
      NODE_ENV: "production",
      WALKSAFE_ENVIRONMENT: "production",
      WALKSAFE_WEB_START_MODE: "production",
      BACKEND_API_BASE_URL: BACKEND_BASE,
      VOICE_API_BASE_URL: VOICE_BASE
    },
    overrides
  );
  return environment;
}

function main() {
  assert(isProtectedWebUpstreamRuntime("field", "development", "development"), "field must be protected");
  assert(isProtectedWebUpstreamRuntime("staging", "development", "development"), "staging must be protected");
  assert(isProtectedWebUpstreamRuntime("production", "development", "development"), "production must be protected");
  assert(isProtectedWebUpstreamRuntime("", "production", "development"), "NODE_ENV production must be protected");
  assert(isProtectedWebUpstreamRuntime("", "test", "production"), "production start mode must be protected");
  assert(isProtectedWebUpstreamRuntime("unknown", "production", "development"), "unknown production env must fail closed");
  assert(
    isProtectedWebUpstreamRuntime("development", "production", "production"),
    "a development label must not override a production runtime"
  );
  assert(
    isProtectedWebUpstreamRuntime("test", "production", "production"),
    "a test label must not override a production runtime"
  );
  assert(!isProtectedWebUpstreamRuntime("test", "test", "test"), "test runtime must retain development behavior");

  assert(
    resolveUpstreamBaseUrl("BACKEND_API_BASE_URL", BACKEND_BASE, BACKEND_BASE, true) === BACKEND_BASE,
    "the exact backend loopback origin must be accepted"
  );
  assert(
    resolveUpstreamBaseUrl("VOICE_API_BASE_URL", VOICE_BASE, VOICE_BASE, true) === VOICE_BASE,
    "the exact voice loopback origin must be accepted"
  );

  const unsafeBackendValues = [
    "http://user@127.0.0.1:8000",
    "http://user:password@127.0.0.1:8000",
    "http://127.0.0.1:8000@attacker.invalid",
    "http://127.0.0.1:8000/path",
    "http://127.0.0.1:8000?query=1",
    "http://127.0.0.1:8000#fragment",
    "http://localhost:8000",
    "http://backend.internal:8000",
    "http://127.0.0.1:8001",
    "https://127.0.0.1:8000",
    "http://127.1:8000",
    "http://[::1]:8000",
    "http://127.0.0.1:8000/",
    " http://127.0.0.1:8000"
  ];
  for (const value of unsafeBackendValues) {
    let rejected = false;
    try {
      resolveUpstreamBaseUrl("BACKEND_API_BASE_URL", value, BACKEND_BASE, true);
    } catch {
      rejected = true;
    }
    assert(rejected, `protected runtime accepted an unsafe backend base URL: ${value}`);
  }

  assert(
    resolveUpstreamBaseUrl(
      "BACKEND_API_BASE_URL",
      "http://backend.test:7000/base///",
      BACKEND_BASE,
      false
    ) === "http://backend.test:7000/base",
    "development/test runtime must preserve the previous configurable upstream behavior"
  );

  const productionUserinfo = loadBackendModule(
    protectedEnvironment({ BACKEND_API_BASE_URL: "http://127.0.0.1:8000@attacker.invalid" })
  );
  assert(productionUserinfo.status !== 0, "production module load accepted a credential-exfil backend URL");
  assert(
    productionUserinfo.stderr.includes("BACKEND_API_BASE_URL must be exactly"),
    "production module rejection must identify the invalid backend setting"
  );

  const stagingPath = loadBackendModule(
    protectedEnvironment({
      NODE_ENV: "development",
      WALKSAFE_ENVIRONMENT: "staging",
      WALKSAFE_WEB_START_MODE: "development",
      VOICE_API_BASE_URL: "http://127.0.0.1:9001/path"
    })
  );
  assert(stagingPath.status !== 0, "staging module load accepted a voice URL with a path");
  assert(
    stagingPath.stderr.includes("VOICE_API_BASE_URL must be exactly"),
    "staging module rejection must identify the invalid voice setting"
  );

  const mislabeledProduction = loadBackendModule(
    protectedEnvironment({
      NODE_ENV: "production",
      WALKSAFE_ENVIRONMENT: "test",
      WALKSAFE_WEB_START_MODE: "production",
      BACKEND_API_BASE_URL: "http://backend.test:7000/base/",
      VOICE_API_BASE_URL: VOICE_BASE
    })
  );
  assert(mislabeledProduction.status !== 0, "a production runtime accepted an external backend under a test label");
  assert(
    mislabeledProduction.stderr.includes("BACKEND_API_BASE_URL must be exactly"),
    "production test-label rejection must identify the invalid backend setting"
  );

  const testOverride = loadBackendModule(
    protectedEnvironment({
      NODE_ENV: "test",
      WALKSAFE_ENVIRONMENT: "test",
      WALKSAFE_WEB_START_MODE: "test",
      BACKEND_API_BASE_URL: "http://backend.test:7000/base/",
      VOICE_API_BASE_URL: "http://voice.test:7001/"
    })
  );
  assert(testOverride.status === 0, testOverride.stderr || "test module load failed");

  console.log("upstream base URL policy checks passed");
}

main();
