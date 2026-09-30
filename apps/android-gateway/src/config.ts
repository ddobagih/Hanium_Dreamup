import path from "node:path";
import { tmpdir } from "node:os";

const EXPECTED_BACKEND_API_BASE_URL = "http://127.0.0.1:8000";
const EXPECTED_VOICE_API_BASE_URL = "http://127.0.0.1:9001";
const EXPECTED_GATEWAY_HOST = "127.0.0.1";
const DEFAULT_GATEWAY_PORT = 8081;
const MIN_FIELD_ACCESS_TTL_SECONDS = 60;
const MAX_FIELD_ACCESS_TTL_SECONDS = 60 * 60;
const MIN_FIELD_REFRESH_IDLE_TTL_SECONDS = 5 * 60;
const MAX_FIELD_REFRESH_IDLE_TTL_SECONDS = 90 * 24 * 60 * 60;
const MIN_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS = 60 * 60;
const MAX_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS = 365 * 24 * 60 * 60;

export function assertGatewayStateStorageConfiguration(
  environment: NodeJS.ProcessEnv = process.env
): void {
  if (environment.NODE_ENV !== "production") return;
  const keyringPath = environment.WALKSAFE_GATEWAY_STATE_KEYRING_FILE?.trim() ?? "";
  const ledgerPath = environment.WALKSAFE_FIELD_WALK_LEDGER_PATH?.trim() ?? "";
  if (!keyringPath) {
    throw new Error("WALKSAFE_GATEWAY_STATE_KEYRING_FILE is required in production");
  }
  if (!ledgerPath) {
    throw new Error("WALKSAFE_FIELD_WALK_LEDGER_PATH is required in production");
  }
  if (
    !path.isAbsolute(keyringPath) ||
    path.resolve(keyringPath) !== keyringPath ||
    !path.isAbsolute(ledgerPath) ||
    path.resolve(ledgerPath) !== ledgerPath
  ) {
    throw new Error("gateway state keyring and field-walk ledger paths must be canonical absolute paths");
  }
}

export function resolveGatewayStateDirectory(
  environment: NodeJS.ProcessEnv = process.env
): string {
  const configured = environment.WALKSAFE_GATEWAY_RATE_LIMIT_DIR?.trim() ?? "";
  if (!configured && environment.NODE_ENV === "production") {
    throw new Error("WALKSAFE_GATEWAY_RATE_LIMIT_DIR is required in production");
  }
  const value = configured || path.join(tmpdir(), "walksafe-gateway-rate-limit-v1");
  if (!path.isAbsolute(value) || path.resolve(value) !== value) {
    throw new Error("gateway state directory must be one canonical absolute path");
  }
  return value;
}

export function resolveGatewayServiceRuntimeLockPath(
  environment: NodeJS.ProcessEnv = process.env
): string {
  return path.join(resolveGatewayStateDirectory(environment), "gateway-service-runtime.lock");
}

export function resolveBackendBaseUrl(rawValue = process.env.BACKEND_API_BASE_URL): string {
  const value = rawValue ?? EXPECTED_BACKEND_API_BASE_URL;
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error(`BACKEND_API_BASE_URL must be exactly ${EXPECTED_BACKEND_API_BASE_URL}`);
  }
  if (
    value !== EXPECTED_BACKEND_API_BASE_URL ||
    parsed.protocol !== "http:" ||
    parsed.username !== "" ||
    parsed.password !== "" ||
    parsed.hostname !== "127.0.0.1" ||
    parsed.port !== "8000" ||
    parsed.pathname !== "/" ||
    parsed.search !== "" ||
    parsed.hash !== ""
  ) {
    throw new Error(`BACKEND_API_BASE_URL must be exactly ${EXPECTED_BACKEND_API_BASE_URL}`);
  }
  return EXPECTED_BACKEND_API_BASE_URL;
}

export type VoiceServiceConfig = Readonly<{
  baseUrl: typeof EXPECTED_VOICE_API_BASE_URL;
  serviceToken: string;
}>;

export function resolveVoiceServiceConfig(
  environment: NodeJS.ProcessEnv = process.env
): VoiceServiceConfig | null {
  if (environment.WALKSAFE_VOICE_ENABLED?.trim() !== "true") return null;
  const value = environment.WALKSAFE_VOICE_API_BASE_URL?.trim() ?? "";
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error(`WALKSAFE_VOICE_API_BASE_URL must be exactly ${EXPECTED_VOICE_API_BASE_URL}`);
  }
  if (
    value !== EXPECTED_VOICE_API_BASE_URL ||
    parsed.protocol !== "http:" ||
    parsed.username !== "" ||
    parsed.password !== "" ||
    parsed.hostname !== "127.0.0.1" ||
    parsed.port !== "9001" ||
    parsed.pathname !== "/" ||
    parsed.search !== "" ||
    parsed.hash !== ""
  ) {
    throw new Error(`WALKSAFE_VOICE_API_BASE_URL must be exactly ${EXPECTED_VOICE_API_BASE_URL}`);
  }
  const serviceToken = environment.WALKSAFE_VOICE_SERVICE_TOKEN?.trim() ?? "";
  const separatedCredentials = [
    environment.WALKSAFE_FIELD_TEST_TOKEN?.trim() ?? "",
    environment.WALKSAFE_GATEWAY_SESSION_SECRET?.trim() ?? ""
  ].filter(Boolean);
  try {
    const accounts = JSON.parse(environment.WALKSAFE_FIELD_ACCOUNTS_JSON ?? "[]") as unknown;
    if (Array.isArray(accounts)) {
      for (const account of accounts) {
        if (typeof account === "object" && account !== null &&
          typeof (account as { token?: unknown }).token === "string") {
          separatedCredentials.push((account as { token: string }).token.trim());
        }
      }
    }
  } catch {
    // Account configuration has its own fail-closed validation path.
  }
  if (serviceToken.length < 24 || separatedCredentials.includes(serviceToken)) {
    throw new Error("WALKSAFE_VOICE_SERVICE_TOKEN must be a dedicated secret of at least 24 characters");
  }
  return { baseUrl: EXPECTED_VOICE_API_BASE_URL, serviceToken };
}

export type BindAddress = { host: string; port: number };

/**
 * `refreshIdleTtlSeconds` / `refreshAbsoluteTtlSeconds` are null when the operator set the
 * explicit value `unlimited` (owner decision 2, 2026-09-29: login stays until logout, app
 * removal, remote revoke, password change, lock, reuse, incident, admin action or account
 * deletion). The access TTL is always finite and refresh tokens keep rotating.
 */
export type FieldLongSessionConfig = {
  accessTtlSeconds: number;
  refreshIdleTtlSeconds: number | null;
  refreshAbsoluteTtlSeconds: number | null;
};

const UNLIMITED_TTL = "unlimited";

function explicitTtlSeconds(
  environment: NodeJS.ProcessEnv,
  name: string,
  minimum: number,
  maximum: number,
  allowUnlimited = false
): number | typeof UNLIMITED_TTL | null {
  const value = environment[name]?.trim() ?? "";
  if (allowUnlimited && value === UNLIMITED_TTL) return UNLIMITED_TTL;
  if (!/^\d+$/.test(value)) return null;
  const seconds = Number(value);
  return Number.isSafeInteger(seconds) && seconds >= minimum && seconds <= maximum
    ? seconds
    : null;
}

export function resolveFieldLongSessionConfig(
  environment: NodeJS.ProcessEnv = process.env
): FieldLongSessionConfig | null {
  // login-F12: only an exact `true` enables and only empty/`false` disables; any other value
  // (`True`, `1`, `yes`, ...) is a configuration error at startup instead of a silent off.
  const enabled = environment.WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED?.trim() ?? "";
  if (enabled === "" || enabled === "false") return null;
  if (enabled !== "true") {
    throw new Error("WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED must be true or false");
  }
  const accessTtlSeconds = explicitTtlSeconds(
    environment,
    "WALKSAFE_FIELD_ACCESS_TTL_SECONDS",
    MIN_FIELD_ACCESS_TTL_SECONDS,
    MAX_FIELD_ACCESS_TTL_SECONDS
  );
  const refreshIdleTtlSeconds = explicitTtlSeconds(
    environment,
    "WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS",
    MIN_FIELD_REFRESH_IDLE_TTL_SECONDS,
    MAX_FIELD_REFRESH_IDLE_TTL_SECONDS,
    true
  );
  const refreshAbsoluteTtlSeconds = explicitTtlSeconds(
    environment,
    "WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS",
    MIN_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS,
    MAX_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS,
    true
  );
  // Ordering with `unlimited` as infinity: access <= idle <= absolute. An unlimited idle
  // window under a finite absolute cap is rejected like any other inverted pair.
  if (
    typeof accessTtlSeconds !== "number" ||
    refreshIdleTtlSeconds === null ||
    refreshAbsoluteTtlSeconds === null ||
    (typeof refreshIdleTtlSeconds === "number" && refreshIdleTtlSeconds < accessTtlSeconds) ||
    (typeof refreshAbsoluteTtlSeconds === "number" && (
      refreshIdleTtlSeconds === UNLIMITED_TTL || refreshAbsoluteTtlSeconds < refreshIdleTtlSeconds
    ))
  ) {
    throw new Error("field long-lived session TTL configuration is invalid");
  }
  return {
    accessTtlSeconds,
    refreshIdleTtlSeconds: refreshIdleTtlSeconds === UNLIMITED_TTL ? null : refreshIdleTtlSeconds,
    refreshAbsoluteTtlSeconds: refreshAbsoluteTtlSeconds === UNLIMITED_TTL
      ? null
      : refreshAbsoluteTtlSeconds
  };
}

export function assertFieldLongSessionConfiguration(
  environment: NodeJS.ProcessEnv = process.env
): void {
  resolveFieldLongSessionConfig(environment);
  // S19-17: validated with the other field settings before listen.
  resolveFieldWalkV1CommandMode(environment);
  // S19-53: field login, refresh, and upload limits are keyed by this header.
  assertTrustedClientIpConfiguration(environment);
}
/**
 * S19-53: production (the field deployment template also runs with NODE_ENV=production)
 * must name the header its reverse proxy overwrites on every request. Without it every
 * client address would be unknown, so startup fails instead of serving with merged limits.
 */
export function assertTrustedClientIpConfiguration(
  environment: NodeJS.ProcessEnv = process.env
): void {
  if (environment.NODE_ENV !== "production") return;
  const value = environment.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER?.trim().toLowerCase() ?? "";
  if (value !== "cf-connecting-ip" && value !== "x-real-ip") {
    throw new Error(
      "WALKSAFE_GATEWAY_TRUSTED_IP_HEADER must be cf-connecting-ip or x-real-ip in production"
    );
  }
}
export type FieldWalkV1CommandMode = "accept" | "reject";
/**
 * S19-17: `walksafe.field-walk-command.v1` carries no issue time, so an evicted v1 request
 * cannot be bounded by the idempotency window. Android sends only v2, so v1 is refused by
 * default (409 walk_command_version_retired, nothing executed); `accept` is an explicit
 * opt-in for a legacy client. Any other value is a startup error.
 */
export function resolveFieldWalkV1CommandMode(
  environment: NodeJS.ProcessEnv = process.env
): FieldWalkV1CommandMode {
  const value = environment.WALKSAFE_FIELD_WALK_V1_COMMANDS?.trim() ?? "";
  if (value === "" || value === "reject") return "reject";
  if (value === "accept") return "accept";
  throw new Error("WALKSAFE_FIELD_WALK_V1_COMMANDS must be accept or reject");
}

export function resolveBindAddress(
  rawHost = process.env.WALKSAFE_ANDROID_GATEWAY_HOST,
  rawPort = process.env.WALKSAFE_ANDROID_GATEWAY_PORT
): BindAddress {
  const host = rawHost?.trim() || EXPECTED_GATEWAY_HOST;
  if (host !== EXPECTED_GATEWAY_HOST) {
    throw new Error(`WALKSAFE_ANDROID_GATEWAY_HOST must be exactly ${EXPECTED_GATEWAY_HOST}`);
  }
  const portText = rawPort?.trim() || String(DEFAULT_GATEWAY_PORT);
  if (!/^\d+$/.test(portText)) throw new Error("WALKSAFE_ANDROID_GATEWAY_PORT must be an integer");
  const port = Number(portText);
  if (!Number.isSafeInteger(port) || port < 1 || port > 65_535) {
    throw new Error("WALKSAFE_ANDROID_GATEWAY_PORT must be between 1 and 65535");
  }
  return { host, port };
}

export function resolvePrivacyRightsRequestUrl(
  rawValue = process.env.WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL
): string {
  const value = rawValue?.trim() ?? "";
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error("WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL must be one exact HTTPS URL");
  }
  if (
    parsed.protocol !== "https:" ||
    parsed.hostname === "" ||
    parsed.username !== "" ||
    parsed.password !== "" ||
    parsed.search !== "" ||
    parsed.hash !== "" ||
    parsed.pathname === "/"
  ) {
    throw new Error("WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL must be one exact HTTPS URL");
  }
  return parsed.toString();
}

export const BACKEND_API_BASE_URL = resolveBackendBaseUrl();
