import { createHash } from "node:crypto";
import path from "node:path";

export const INSTALLATION_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/;

export const REQUEST_ID = /^[A-Za-z0-9_-]{16,128}$/;

export const SHA256 = /^[0-9a-f]{64}$/;

const POLICY_VERSION = /^FP-013-[1-9][0-9]*\.[0-9]+\.[0-9]+$/;

const WHOLE_SECOND_INSTANT =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;

export const CONSENT_KEYS = [
  "raw_source_collection",
  "automatic_reporting",
  "mobile_network_transfer",
  "training_reuse"
] as const;

export const MAX_EVENTS = 4096;

export const NORMAL_MAX_STATE_BYTES = 4 * 1024 * 1024;

export const WITHDRAWAL_RESERVE_EVENTS = CONSENT_KEYS.length;

// Existing full ledgers retain their audit chain; only monotone withdrawals
// may consume the bounded reserve, never a new grant or an actor change.
export const INTEGRATED_CONSENT_MAX_STATE_BYTES = NORMAL_MAX_STATE_BYTES + 16 * 1024;

export type IntegratedConsentKey = typeof CONSENT_KEYS[number];

export type IntegratedConsentItemVersions = {
  raw_source_collection: string;
  automatic_reporting: string;
  mobile_network_transfer: string;
  training_reuse: string;
};

export const INTEGRATED_CONSENT_ITEM_VERSIONS: IntegratedConsentItemVersions =
  Object.freeze({
    raw_source_collection: "FP-013-RAW-1.2.0",
    automatic_reporting: "FP-013-AUTO-1.2.0",
    mobile_network_transfer: "FP-013-MOBILE-1.0.0",
    training_reuse: "FP-013-TRAINING-1.1.0"
  });

const ITEM_VERSION_PATTERNS: Record<IntegratedConsentKey, RegExp> = {
  raw_source_collection: /^FP-013-RAW-[1-9][0-9]*\.[0-9]+\.[0-9]+$/,
  automatic_reporting: /^FP-013-AUTO-[1-9][0-9]*\.[0-9]+\.[0-9]+$/,
  mobile_network_transfer: /^FP-013-MOBILE-[1-9][0-9]*\.[0-9]+\.[0-9]+$/,
  training_reuse: /^FP-013-TRAINING-[1-9][0-9]*\.[0-9]+\.[0-9]+$/
};

export type IntegratedConsentSelections = {
  raw_source_collection: boolean;
  automatic_reporting: boolean;
  mobile_network_transfer: boolean;
  training_reuse: boolean;
};

export type IntegratedConsentConfirmation = {
  schema_version: "walksafe.integrated-consent-confirmation.v2";
  current: true;
  installation_id: string;
  request_id: string;
  policy_version: string;
  item_versions: IntegratedConsentItemVersions;
  client_revision: number;
  revision: number;
  selections: IntegratedConsentSelections;
  confirmed_at: string;
  gateway_audit_record_sha256: string;
  backend_consent_receipt_sha256: string;
};

type LegacyIntegratedConsentEvent = {
  request_id: string;
  policy_version: string;
  item_versions: IntegratedConsentItemVersions;
  client_revision: number;
  revision: number;
  selections: IntegratedConsentSelections;
  confirmed_at: string;
  previous_receipt_sha256: string | null;
  receipt_sha256: string;
};

type IntegratedConsentEventV4 = {
  request_id: string;
  policy_version: string;
  item_versions: IntegratedConsentItemVersions;
  client_revision: number;
  revision: number;
  selections: IntegratedConsentSelections;
  confirmed_at: string;
  previous_gateway_audit_record_sha256: string | null;
  gateway_audit_record_sha256: string;
  backend_consent_receipt_sha256: string;
  backend_recorded_at: string;
  field_actor_binding_sha256: string;
};

export type IntegratedConsentEventV5 = IntegratedConsentEventV4 & {
  expected_previous_backend_receipt_sha256: string | null;
};

export type AuthoritativeIntegratedConsentEvent =
  | IntegratedConsentEventV4
  | IntegratedConsentEventV5;

export type IntegratedConsentEvent =
  | LegacyIntegratedConsentEvent
  | AuthoritativeIntegratedConsentEvent;

export type IntegratedConsentState = {
  schema_version: 2 | 3 | 4 | 5 | 6 | 7;
  installation_id: string;
  control_secret_sha256: string;
  field_actor_binding?: {
    actor_sha256: string;
    consent_revision: number;
    bound_at: string;
  } | null;
  events: IntegratedConsentEvent[];
  revision?: number;
  audit_archive?: ConsentAuditArchive;
};

export type ConsentAuditArchive = {
  segment_count: number;
  archived_events: number;
  last_segment_sha256: string;
};

export type ConsentAuditSegment = {
  schema_version: "walksafe.integrated-consent-audit.v1";
  installation_id: string;
  sequence: number;
  previous_segment_sha256: string | null;
  events: IntegratedConsentEvent[];
};

export type ConsentHistoryCursor = {
  revision: number;
  audit: string | null;
  previousClientRevision: number;
  actorRevisions: Map<string, number>;
  authoritative: boolean;
};

export type ConsentHistoryQuery = { requestId: string; actorSha256: string };

export class IntegratedConsentRequestError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string
  ) {
    super(message);
    this.name = "IntegratedConsentRequestError";
  }
}

export function exactKeys(value: Record<string, unknown>, expected: readonly string[]): boolean {
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length && actual.every((key, index) => key === wanted[index]);
}

export function objectValue(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

export function selectionsOrNull(value: unknown): IntegratedConsentSelections | null {
  const payload = objectValue(value);
  if (!payload || !exactKeys(payload, CONSENT_KEYS)) return null;
  if (CONSENT_KEYS.some((key) => typeof payload[key] !== "boolean")) return null;
  return {
    raw_source_collection: payload.raw_source_collection as boolean,
    automatic_reporting: payload.automatic_reporting as boolean,
    mobile_network_transfer: payload.mobile_network_transfer as boolean,
    training_reuse: payload.training_reuse as boolean
  };
}

export function itemVersionsOrNull(value: unknown): IntegratedConsentItemVersions | null {
  const payload = objectValue(value);
  if (!payload || !exactKeys(payload, CONSENT_KEYS)) return null;
  if (CONSENT_KEYS.some((key) => (
    typeof payload[key] !== "string" ||
    !ITEM_VERSION_PATTERNS[key].test(payload[key] as string)
  ))) {
    return null;
  }
  return {
    raw_source_collection: payload.raw_source_collection as string,
    automatic_reporting: payload.automatic_reporting as string,
    mobile_network_transfer: payload.mobile_network_transfer as string,
    training_reuse: payload.training_reuse as string
  };
}

export function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== "object") {
    const encoded = JSON.stringify(value);
    if (encoded === undefined) throw new TypeError("unsupported canonical JSON value");
    return encoded;
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  const payload = value as Record<string, unknown>;
  return `{${Object.keys(payload).sort().map(
    (key) => `${JSON.stringify(key)}:${canonicalJson(payload[key])}`
  ).join(",")}}`;
}

export function sha256(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}

function eventReceipt(
  installationId: string,
  event: Omit<LegacyIntegratedConsentEvent, "receipt_sha256">
): string {
  return sha256(canonicalJson({
    installation_id: installationId,
    ...event
  }));
}

export function eventAuditRecord(
  installationId: string,
  event:
    | Omit<IntegratedConsentEventV4, "gateway_audit_record_sha256">
    | Omit<IntegratedConsentEventV5, "gateway_audit_record_sha256">
): string {
  return sha256(canonicalJson({
    installation_id: installationId,
    ...event
  }));
}

export function authoritativeEvent(
  event: IntegratedConsentEvent
): event is AuthoritativeIntegratedConsentEvent {
  return "gateway_audit_record_sha256" in event;
}

export function eventAuditSha256(event: IntegratedConsentEvent): string {
  return authoritativeEvent(event)
    ? event.gateway_audit_record_sha256
    : event.receipt_sha256;
}

function validIsoInstant(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) && new Date(parsed).toISOString() === value;
}

export function validWholeSecondInstant(value: unknown): value is string {
  if (typeof value !== "string" || !WHOLE_SECOND_INSTANT.test(value)) return false;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) &&
    new Date(parsed).toISOString() === `${value.slice(0, -1)}.000Z`;
}

export function equalSelections(
  left: IntegratedConsentSelections,
  right: IntegratedConsentSelections
): boolean {
  return CONSENT_KEYS.every((key) => left[key] === right[key]);
}

export function equalItemVersions(
  left: IntegratedConsentItemVersions,
  right: IntegratedConsentItemVersions
): boolean {
  return CONSENT_KEYS.every((key) => left[key] === right[key]);
}

export function hasCurrentIntegratedConsentItemVersions(
  confirmationValue: Pick<IntegratedConsentConfirmation, "item_versions">
): boolean {
  return equalItemVersions(
    confirmationValue.item_versions,
    INTEGRATED_CONSENT_ITEM_VERSIONS
  );
}

export function validState(
  value: unknown,
  installationId: string,
  fullHistory = false,
  eventOffset = 0,
  initialAudit: string | null = null,
  cursor?: ConsentHistoryCursor
): value is IntegratedConsentState {
  const state = objectValue(value);
  const legacyKeys = [
    "schema_version",
    "installation_id",
    "control_secret_sha256",
    "events"
  ];
  const currentKeys = [...legacyKeys, "field_actor_binding"];
  if (
    !state ||
    !(
      (state.schema_version === 2 && exactKeys(state, legacyKeys)) ||
      ((state.schema_version === 3 ||
        state.schema_version === 4 ||
        state.schema_version === 5 ||
        state.schema_version === 6) &&
        exactKeys(state, currentKeys))
    ) ||
    state.installation_id !== installationId ||
    typeof state.control_secret_sha256 !== "string" ||
    !SHA256.test(state.control_secret_sha256) ||
    !Array.isArray(state.events) ||
    (!fullHistory && state.events.length > MAX_EVENTS + WITHDRAWAL_RESERVE_EVENTS)
  ) {
    return false;
  }
  if (state.schema_version !== 2 && state.field_actor_binding !== null) {
    const binding = objectValue(state.field_actor_binding);
    if (
      !binding ||
      !exactKeys(binding, ["actor_sha256", "consent_revision", "bound_at"]) ||
      typeof binding.actor_sha256 !== "string" ||
      !SHA256.test(binding.actor_sha256) ||
      !Number.isSafeInteger(binding.consent_revision) ||
      binding.consent_revision !== eventOffset + state.events.length ||
      !validIsoInstant(binding.bound_at)
    ) {
      return false;
    }
  }
  let previousAuditRecord: string | null = cursor?.audit ?? initialAudit;
  let previousClientRevision = cursor?.previousClientRevision ?? 0;
  const previousClientRevisionByActor = cursor?.actorRevisions ?? new Map<string, number>();
  let foundAuthoritativeEvent = cursor?.authoritative ?? false;
  for (let index = 0; index < state.events.length; index += 1) {
    const rawEvent = objectValue(state.events[index]);
    if (
      !rawEvent ||
      typeof rawEvent.request_id !== "string" ||
      !REQUEST_ID.test(rawEvent.request_id) ||
      typeof rawEvent.policy_version !== "string" ||
      !POLICY_VERSION.test(rawEvent.policy_version) ||
      !Number.isSafeInteger(rawEvent.client_revision) ||
      (rawEvent.client_revision as number) <= 0 ||
      rawEvent.revision !== eventOffset + index + 1 ||
      !validIsoInstant(rawEvent.confirmed_at)
    ) {
      return false;
    }
    const selections = selectionsOrNull(rawEvent.selections);
    const itemVersions = itemVersionsOrNull(rawEvent.item_versions);
    if (!selections || !itemVersions) return false;
    if ("receipt_sha256" in rawEvent) {
      if (
        foundAuthoritativeEvent ||
        (rawEvent.client_revision as number) <= previousClientRevision ||
        !exactKeys(rawEvent, [
          "request_id",
          "policy_version",
          "item_versions",
          "client_revision",
          "revision",
          "selections",
          "confirmed_at",
          "previous_receipt_sha256",
          "receipt_sha256"
        ]) ||
        rawEvent.previous_receipt_sha256 !== previousAuditRecord ||
        typeof rawEvent.receipt_sha256 !== "string" ||
        !SHA256.test(rawEvent.receipt_sha256)
      ) return false;
      const eventWithoutReceipt: Omit<LegacyIntegratedConsentEvent, "receipt_sha256"> = {
        request_id: rawEvent.request_id,
        policy_version: rawEvent.policy_version,
        item_versions: itemVersions,
        client_revision: rawEvent.client_revision as number,
        revision: rawEvent.revision,
        selections,
        confirmed_at: rawEvent.confirmed_at,
        previous_receipt_sha256:
          rawEvent.previous_receipt_sha256 as string | null
      };
      if (eventReceipt(installationId, eventWithoutReceipt) !== rawEvent.receipt_sha256) {
        return false;
      }
      previousAuditRecord = rawEvent.receipt_sha256;
    } else {
      const hasExpectedPrevious =
        "expected_previous_backend_receipt_sha256" in rawEvent;
      const eventKeys = [
        "request_id",
        "policy_version",
        "item_versions",
        "client_revision",
        "revision",
        "selections",
        "confirmed_at",
        "previous_gateway_audit_record_sha256",
        "gateway_audit_record_sha256",
        "backend_consent_receipt_sha256",
        "backend_recorded_at",
        "field_actor_binding_sha256",
        ...(hasExpectedPrevious
          ? ["expected_previous_backend_receipt_sha256"]
          : [])
      ];
      if (
        (state.schema_version !== 4 &&
          state.schema_version !== 5 &&
          state.schema_version !== 6) ||
        (hasExpectedPrevious &&
          state.schema_version !== 5 &&
          state.schema_version !== 6) ||
        !exactKeys(rawEvent, eventKeys) ||
        rawEvent.previous_gateway_audit_record_sha256 !== previousAuditRecord ||
        typeof rawEvent.gateway_audit_record_sha256 !== "string" ||
        !SHA256.test(rawEvent.gateway_audit_record_sha256) ||
        typeof rawEvent.backend_consent_receipt_sha256 !== "string" ||
        !SHA256.test(rawEvent.backend_consent_receipt_sha256) ||
        !validWholeSecondInstant(rawEvent.backend_recorded_at) ||
        typeof rawEvent.field_actor_binding_sha256 !== "string" ||
        !SHA256.test(rawEvent.field_actor_binding_sha256) ||
        (hasExpectedPrevious &&
          rawEvent.expected_previous_backend_receipt_sha256 !== null &&
          (typeof rawEvent.expected_previous_backend_receipt_sha256 !== "string" ||
            !SHA256.test(rawEvent.expected_previous_backend_receipt_sha256)))
      ) return false;
      const actorClientRevision = previousClientRevisionByActor.get(
        rawEvent.field_actor_binding_sha256
      ) ?? 0;
      if (
        (state.schema_version === 6 &&
          (rawEvent.client_revision as number) <= actorClientRevision) ||
        (state.schema_version !== 6 &&
          (rawEvent.client_revision as number) <= previousClientRevision)
      ) return false;
      const eventWithoutAudit = {
        request_id: rawEvent.request_id,
        policy_version: rawEvent.policy_version,
        item_versions: itemVersions,
        client_revision: rawEvent.client_revision as number,
        revision: rawEvent.revision,
        selections,
        confirmed_at: rawEvent.confirmed_at,
        previous_gateway_audit_record_sha256:
          rawEvent.previous_gateway_audit_record_sha256 as string | null,
        backend_consent_receipt_sha256: rawEvent.backend_consent_receipt_sha256,
        backend_recorded_at: rawEvent.backend_recorded_at,
        field_actor_binding_sha256: rawEvent.field_actor_binding_sha256,
        ...(hasExpectedPrevious
          ? {
              expected_previous_backend_receipt_sha256:
                rawEvent.expected_previous_backend_receipt_sha256 as string | null
            }
          : {})
      };
      if (eventAuditRecord(installationId, eventWithoutAudit) !== rawEvent.gateway_audit_record_sha256) {
        return false;
      }
      previousAuditRecord = rawEvent.gateway_audit_record_sha256;
      previousClientRevisionByActor.set(
        rawEvent.field_actor_binding_sha256,
        rawEvent.client_revision as number
      );
      foundAuthoritativeEvent = true;
    }
    previousClientRevision = rawEvent.client_revision as number;
  }
  if (
    state.schema_version !== 4 &&
    state.schema_version !== 5 &&
    state.schema_version !== 6 &&
    foundAuthoritativeEvent
  ) return false;
  const currentEvent = state.events.at(-1);
  if (
    (state.schema_version === 4 ||
      state.schema_version === 5 ||
      state.schema_version === 6) &&
    currentEvent &&
    authoritativeEvent(currentEvent)
  ) {
    const binding = objectValue(state.field_actor_binding);
    if (
      !binding ||
      binding.actor_sha256 !== currentEvent.field_actor_binding_sha256 ||
      binding.consent_revision !== currentEvent.revision
    ) return false;
  }
  if (cursor) {
    cursor.revision = eventOffset + state.events.length;
    cursor.audit = previousAuditRecord;
    cursor.previousClientRevision = previousClientRevision;
    cursor.authoritative = foundAuthoritativeEvent;
  }
  return true;
}

export function auditSegmentPath(filePath: string, sequence: number): string {
  if (!Number.isSafeInteger(sequence) || sequence < 1 || sequence > 99_999_999) {
    throw new Error("consent audit segment sequence is exhausted");
  }
  return `${filePath}.audit-${String(sequence).padStart(8, "0")}`;
}

export function validIntegratedConsentAuditSegmentForMaintenance(value: unknown, recordId: string): boolean {
  const segment = objectValue(value);
  if (!segment || !exactKeys(segment, ["schema_version", "installation_id", "sequence",
    "previous_segment_sha256", "events"]) ||
    segment.schema_version !== "walksafe.integrated-consent-audit.v1" ||
    typeof segment.installation_id !== "string" || !INSTALLATION_ID.test(segment.installation_id) ||
    !Number.isSafeInteger(segment.sequence) || Number(segment.sequence) < 1 || Number(segment.sequence) > 99_999_999 ||
    (segment.previous_segment_sha256 !== null &&
      (typeof segment.previous_segment_sha256 !== "string" || !SHA256.test(segment.previous_segment_sha256))) ||
    (Number(segment.sequence) === 1) !== (segment.previous_segment_sha256 === null) ||
    !Array.isArray(segment.events) || segment.events.length === 0 || segment.events.length > MAX_EVENTS + WITHDRAWAL_RESERVE_EVENTS) {
    return false;
  }
  const expectedFile = `${sha256(`integrated-consent\0${segment.installation_id}`)}.json`;
  if (recordId !== path.basename(auditSegmentPath(expectedFile, Number(segment.sequence)))) return false;
  const events = segment.events as IntegratedConsentEvent[];
  const first = events[0];
  const last = events.at(-1);
  if (!first || !last || !Number.isSafeInteger(first.revision) || first.revision < 1 ||
    (Number(segment.sequence) === 1 && first.revision !== 1)) return false;
  const initialAudit = authoritativeEvent(first)
    ? first.previous_gateway_audit_record_sha256 : first.previous_receipt_sha256;
  return validState({
    schema_version: 6,
    installation_id: segment.installation_id,
    control_secret_sha256: "0".repeat(64),
    field_actor_binding: authoritativeEvent(last) ? {
      actor_sha256: last.field_actor_binding_sha256,
      consent_revision: last.revision,
      bound_at: last.confirmed_at
    } : null,
    events
  }, segment.installation_id, true, first.revision - 1, initialAudit);
}

