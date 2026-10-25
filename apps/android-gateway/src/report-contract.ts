import { objectPayload } from "./route-responses.js";

export const CANONICAL_REPORT_UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export const LOWER_SHA256 = /^[0-9a-f]{64}$/;

export const USER_REPORT_BODY_LIMIT_BYTES = 4 * 1024;

export const USER_REPORT_RESPONSE_LIMIT_BYTES = 128 * 1024;

export const USER_REPORT_REQUEST_HISTORY_RESPONSE_LIMIT_BYTES = 48 * 1024;

const USER_REPORT_CURSOR = /^[A-Za-z0-9_-]{1,1024}$/;

const USER_REPORT_STATUSES = new Set([
  "RECEIVED", "INSTITUTION_SUBMITTED", "REJECTED", "RESOLVED"
]);

const USER_REQUEST_STATUSES = new Set([
  "RECEIVED", "ACKNOWLEDGED", "RESOLVED", "REJECTED"
]);

const USER_REPORT_CONTENT_CATEGORIES = new Set([
  "SIDEWALK_OBSTRUCTION", "ROAD_DAMAGE", "ACCESSIBILITY_BARRIER", "OTHER"
]);

const USER_REPORT_DELETION_STATES = new Set([
  "PENDING", "LEGAL_HOLD", "REJECTED", "DELETED"
]);

const USER_REPORT_EXTERNAL_COPY_DELETION_STATES = new Set([
  "NOT_REQUESTED",
  "REQUEST_SENT",
  "REPLY_ACKNOWLEDGED",
  "REPLY_DELETION_CONFIRMED",
  "REPLY_DECLINED"
]);

export function exactObjectKeys(
  value: Record<string, unknown>,
  expected: readonly string[]
): boolean {
  const actual = Object.keys(value).sort();
  return actual.length === expected.length &&
    actual.every((key, index) => key === [...expected].sort()[index]);
}

export type UserReportRoute =
  | Readonly<{ kind: "list" }>
  | Readonly<{ kind: "request-history" }>
  | Readonly<{ kind: "detail" | "request" | "content" | "correction"; reportId: string }>
  | Readonly<{ kind: "request-detail"; reportId: string; requestId: string }>
  | Readonly<{ kind: "deletion"; requestId: string }>;

function validDateTime(value: unknown): value is string {
  return typeof value === "string" && value.length >= 20 && value.length <= 40 &&
    Number.isFinite(Date.parse(value));
}

const AWARE_DATE_TIME =
  /^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})$/;

function validAwareDateTime(value: unknown): value is string {
  return typeof value === "string" && value.length >= 20 && value.length <= 40 &&
    AWARE_DATE_TIME.test(value) && Number.isFinite(Date.parse(value));
}

function optionalBoundedText(value: unknown): value is string | null {
  return value === null || (
    typeof value === "string" &&
    Array.from(value).length >= 1 && Array.from(value).length <= 500
  );
}

function sanitizeRequestSummary(value: unknown): Record<string, unknown> | null {
  const item = objectPayload(value);
  if (!item || !exactObjectKeys(item, [
    "request_id", "request_type", "status", "status_version",
    "public_response", "created_at", "updated_at"
  ])) return null;
  if (
    typeof item.request_id !== "string" || !CANONICAL_REPORT_UUID.test(item.request_id) ||
    (item.request_type !== "CORRECTION" && item.request_type !== "DELETE") ||
    typeof item.status !== "string" || !USER_REQUEST_STATUSES.has(item.status) ||
    typeof item.status_version !== "number" || !Number.isSafeInteger(item.status_version) ||
    item.status_version < 1 || !optionalBoundedText(item.public_response) ||
    !validDateTime(item.created_at) || !validDateTime(item.updated_at)
  ) return null;
  return {
    request_id: item.request_id,
    request_type: item.request_type,
    status: item.status,
    status_version: item.status_version,
    public_response: item.public_response,
    created_at: item.created_at,
    updated_at: item.updated_at
  };
}

function sanitizeUserReportItem(value: unknown): Record<string, unknown> | null {
  const item = objectPayload(value);
  if (!item || !exactObjectKeys(item, [
    "report_id", "created_at", "user_status",
    "public_rejection_reason", "latest_request"
  ])) return null;
  const latest = item.latest_request === null
    ? null : sanitizeRequestSummary(item.latest_request);
  if (
    typeof item.report_id !== "string" || !CANONICAL_REPORT_UUID.test(item.report_id) ||
    !validDateTime(item.created_at) || typeof item.user_status !== "string" ||
    !USER_REPORT_STATUSES.has(item.user_status) ||
    !optionalBoundedText(item.public_rejection_reason) ||
    (item.latest_request !== null && latest === null)
  ) return null;
  return {
    report_id: item.report_id,
    created_at: item.created_at,
    user_status: item.user_status,
    public_rejection_reason: item.public_rejection_reason,
    latest_request: latest
  };
}

export type UserReportCorrectionRequest = Readonly<{
  expectedRevision: number;
  idempotencyKey: string;
  body: Record<string, unknown>;
}>;

function nullableContentCategory(value: unknown): boolean {
  return value === null || (
    typeof value === "string" && USER_REPORT_CONTENT_CATEGORIES.has(value)
  );
}

function canonicalCorrectionDescription(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const nfc = value.normalize("NFC");
  if (/\p{C}/u.test(nfc)) return null;
  const normalized = nfc
    .trim()
    .split(/\s+/u)
    .filter(Boolean)
    .join(" ");
  const length = Array.from(normalized).length;
  return length >= 1 && length <= 500
    ? normalized
    : null;
}

function nullableCanonicalCorrectionDescription(value: unknown): boolean {
  return value === null || canonicalCorrectionDescription(value) === value;
}

function sanitizeReportContentCurrent(
  value: Record<string, unknown>,
  reportId: string
): Record<string, unknown> | null {
  if (!exactObjectKeys(value, [
    "schema_version", "report_id", "revision", "content_sha256",
    "user_description", "category_hint", "corrected_at"
  ]) ||
    value.schema_version !== "walksafe.report-content-current.v1" ||
    value.report_id !== reportId ||
    typeof value.revision !== "number" || !Number.isSafeInteger(value.revision) ||
    value.revision < 0 || typeof value.content_sha256 !== "string" ||
    !LOWER_SHA256.test(value.content_sha256) ||
    !nullableCanonicalCorrectionDescription(value.user_description) ||
    !nullableContentCategory(value.category_hint) ||
    !(
      value.corrected_at === null || validAwareDateTime(value.corrected_at)
    ) ||
    (value.revision === 0 && (
      value.user_description !== null ||
      value.category_hint !== null ||
      value.corrected_at !== null
    )) ||
    (value.revision > 0 && value.corrected_at === null)
  ) return null;
  return {
    schema_version: value.schema_version,
    report_id: value.report_id,
    revision: value.revision,
    content_sha256: value.content_sha256,
    user_description: value.user_description,
    category_hint: value.category_hint,
    corrected_at: value.corrected_at
  };
}

function sanitizeReportContentRevision(
  value: Record<string, unknown>,
  reportId: string,
  expected: UserReportCorrectionRequest | null
): Record<string, unknown> | null {
  if (!expected || !exactObjectKeys(value, [
    "schema_version", "report_id", "revision", "expected_revision",
    "idempotency_key", "content_sha256", "user_description",
    "category_hint", "corrected_at"
  ]) ||
    value.schema_version !== "walksafe.report-content-revision.v1" ||
    value.report_id !== reportId ||
    typeof value.revision !== "number" || !Number.isSafeInteger(value.revision) ||
    value.revision < 1 || value.revision !== expected.expectedRevision + 1 ||
    value.expected_revision !== expected.expectedRevision ||
    value.idempotency_key !== expected.idempotencyKey ||
    typeof value.content_sha256 !== "string" || !LOWER_SHA256.test(value.content_sha256) ||
    !nullableCanonicalCorrectionDescription(value.user_description) ||
    !nullableContentCategory(value.category_hint) ||
    !validAwareDateTime(value.corrected_at)
  ) return null;
  return {
    schema_version: value.schema_version,
    report_id: value.report_id,
    revision: value.revision,
    expected_revision: value.expected_revision,
    idempotency_key: value.idempotency_key,
    content_sha256: value.content_sha256,
    user_description: value.user_description,
    category_hint: value.category_hint,
    corrected_at: value.corrected_at
  };
}

function sanitizeReportDeletionStatus(
  value: Record<string, unknown>,
  requestId: string
): Record<string, unknown> | null {
  if (!exactObjectKeys(value, [
    "schema_version", "request_id", "report_id", "state",
    "request_status_version", "external_copy_count", "external_copies", "updated_at"
  ]) ||
    value.schema_version !== "walksafe.report-deletion-status.v2" ||
    value.request_id !== requestId ||
    typeof value.report_id !== "string" || !CANONICAL_REPORT_UUID.test(value.report_id) ||
    typeof value.state !== "string" || !USER_REPORT_DELETION_STATES.has(value.state) ||
    typeof value.request_status_version !== "number" ||
    !Number.isSafeInteger(value.request_status_version) ||
    value.request_status_version < 1 ||
    typeof value.external_copy_count !== "number" ||
    !Number.isSafeInteger(value.external_copy_count) ||
    value.external_copy_count < 0 || !Array.isArray(value.external_copies) ||
    value.external_copy_count !== value.external_copies.length ||
    (value.state !== "DELETED" && value.external_copies.length > 0) ||
    !validAwareDateTime(value.updated_at)
  ) return null;
  const externalCopies: Record<string, unknown>[] = [];
  for (const candidate of value.external_copies) {
    const item = objectPayload(candidate);
    if (!item || !exactObjectKeys(item, [
      "institution", "state", "status_recorded_at"
    ]) ||
      typeof item.institution !== "string" ||
      [...item.institution].length < 1 || [...item.institution].length > 160 ||
      item.institution.trim() !== item.institution ||
      typeof item.state !== "string" ||
      !USER_REPORT_EXTERNAL_COPY_DELETION_STATES.has(item.state) ||
      (item.status_recorded_at !== null && !validAwareDateTime(item.status_recorded_at))
    ) return null;
    externalCopies.push({
      institution: item.institution,
      state: item.state,
      status_recorded_at: item.status_recorded_at
    });
  }
  return {
    schema_version: value.schema_version,
    request_id: value.request_id,
    report_id: value.report_id,
    state: value.state,
    request_status_version: value.request_status_version,
    external_copy_count: value.external_copy_count,
    external_copies: externalCopies,
    updated_at: value.updated_at
  };
}

function safeHistoryInteger(value: unknown, minimum: number): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= minimum;
}

function sanitizeUserReportRequestHistoryItem(
  value: unknown,
  expectedReportId: string | null
): Record<string, unknown> | null {
  const item = objectPayload(value);
  if (!item || !exactObjectKeys(item, [
    "revision", "source", "report_id", "request_id", "request", "deletion_status"
  ]) ||
    !safeHistoryInteger(item.revision, 1) ||
    (item.source !== "ACTIVE_REQUEST" && item.source !== "DELETION_TOMBSTONE") ||
    typeof item.report_id !== "string" || !CANONICAL_REPORT_UUID.test(item.report_id) ||
    (expectedReportId !== null && item.report_id !== expectedReportId) ||
    typeof item.request_id !== "string" || !CANONICAL_REPORT_UUID.test(item.request_id)
  ) return null;

  const requestPayload = item.request === null ? null : objectPayload(item.request);
  if (item.request !== null && requestPayload === null) return null;
  const request = requestPayload === null ? null : sanitizeRequestSummary(requestPayload);
  if (requestPayload !== null && (
    request === null ||
    request.request_id !== item.request_id ||
    !validAwareDateTime(requestPayload.created_at) ||
    !validAwareDateTime(requestPayload.updated_at)
  )) return null;

  const deletionPayload = item.deletion_status === null
    ? null : objectPayload(item.deletion_status);
  if (item.deletion_status !== null && deletionPayload === null) return null;
  const deletion = deletionPayload === null
    ? null : sanitizeReportDeletionStatus(deletionPayload, item.request_id);
  if (deletionPayload !== null && (
    deletion === null || deletion.report_id !== item.report_id
  )) return null;

  if (item.source === "ACTIVE_REQUEST") {
    if (request === null) return null;
    if (request.request_type === "CORRECTION" && deletion !== null) return null;
    if (request.request_type === "DELETE" && (
      deletion === null ||
      deletion.state === "DELETED" ||
      deletion.request_status_version !== request.status_version
    )) return null;
  } else if (
    request !== null || deletion === null || deletion.state !== "DELETED"
  ) {
    return null;
  }

  return {
    revision: item.revision,
    source: item.source,
    report_id: item.report_id,
    request_id: item.request_id,
    request,
    deletion_status: deletion
  };
}

function sanitizeUserReportRequestHistory(
  value: unknown,
  expectedReportId: string | null,
  requestCursor: string | null,
  expectedLimit: number
): Record<string, unknown> | null {
  const requestHadCursor = requestCursor !== null;
  const payload = objectPayload(value);
  if (!payload || !exactObjectKeys(payload, [
    "schema_version", "report_id", "snapshot_revision", "total_count", "items", "next_cursor"
  ]) ||
    payload.schema_version !== "walksafe.user-report-request-history-page.v1" ||
    !(
      payload.report_id === null ||
      (typeof payload.report_id === "string" && CANONICAL_REPORT_UUID.test(payload.report_id))
    ) ||
    payload.report_id !== expectedReportId ||
    !safeHistoryInteger(payload.snapshot_revision, 0) ||
    !safeHistoryInteger(payload.total_count, 0) ||
    !Array.isArray(payload.items) || payload.items.length > expectedLimit ||
    !(
      payload.next_cursor === null ||
      (typeof payload.next_cursor === "string" && USER_REPORT_CURSOR.test(payload.next_cursor))
    )
  ) return null;

  const items: Record<string, unknown>[] = [];
  const requestIds = new Set<string>();
  let previousRevision: number | null = null;
  for (const candidate of payload.items) {
    const item = sanitizeUserReportRequestHistoryItem(candidate, expectedReportId);
    if (!item) return null;
    const revision = item.revision as number;
    const requestId = item.request_id as string;
    if (
      revision > payload.snapshot_revision ||
      (previousRevision !== null && revision >= previousRevision) ||
      requestIds.has(requestId)
    ) return null;
    previousRevision = revision;
    requestIds.add(requestId);
    items.push(item);
  }
  if (
    payload.total_count < items.length ||
    ((payload.total_count === 0) !== (payload.snapshot_revision === 0)) ||
    (items.length === 0 && (payload.total_count !== 0 || payload.next_cursor !== null)) ||
    (requestHadCursor && items.length >= payload.total_count) ||
    (requestHadCursor && items.length > 0 &&
      (items[0]!.revision as number) >= payload.snapshot_revision) ||
    (requestHadCursor && payload.next_cursor !== null &&
      payload.total_count < items.length + 2) ||
    (requestCursor !== null && payload.next_cursor === requestCursor) ||
    (!requestHadCursor && items.length > 0 && items[0]!.revision !== payload.snapshot_revision) ||
    (!requestHadCursor && (
      (payload.total_count === items.length) !== (payload.next_cursor === null)
    ))
  ) return null;
  return {
    schema_version: payload.schema_version,
    report_id: payload.report_id,
    snapshot_revision: payload.snapshot_revision,
    total_count: payload.total_count,
    items,
    next_cursor: payload.next_cursor
  };
}

export function sanitizeUserReportResponse(
  value: unknown,
  route: UserReportRoute,
  correctionRequest: UserReportCorrectionRequest | null,
  expectedHistoryReportId: string | null,
  historyRequestCursor: string | null,
  expectedHistoryLimit: number
): Record<string, unknown> | null {
  if (route.kind === "request-history") {
    return sanitizeUserReportRequestHistory(
      value, expectedHistoryReportId, historyRequestCursor, expectedHistoryLimit
    );
  }
  const payload = objectPayload(value);
  if (!payload) return null;
  if (route.kind === "content") {
    return sanitizeReportContentCurrent(payload, route.reportId);
  }
  if (route.kind === "correction") {
    return sanitizeReportContentRevision(payload, route.reportId, correctionRequest);
  }
  if (route.kind === "deletion") {
    return sanitizeReportDeletionStatus(payload, route.requestId);
  }
  if (route.kind === "request-detail") {
    const sanitized = sanitizeRequestSummary(payload);
    return sanitized?.request_id === route.requestId ? sanitized : null;
  }
  if (route.kind === "request") return sanitizeRequestSummary(payload);
  if (route.kind === "detail") {
    if (!exactObjectKeys(payload, [
      "schema_version", "report_id", "created_at", "user_status",
      "public_rejection_reason", "latest_request"
    ]) || payload.schema_version !== "walksafe.user-report-detail.v1") return null;
    const item = { ...payload };
    delete item.schema_version;
    const sanitized = sanitizeUserReportItem(item);
    return sanitized && sanitized.report_id === route.reportId ? {
      schema_version: "walksafe.user-report-detail.v1",
      ...sanitized
    } : null;
  }
  if (!exactObjectKeys(payload, ["schema_version", "items", "next_cursor"]) ||
    payload.schema_version !== "walksafe.user-report-list.v1" ||
    !Array.isArray(payload.items) || payload.items.length > 100 ||
    !(
      payload.next_cursor === null ||
      (typeof payload.next_cursor === "string" && USER_REPORT_CURSOR.test(payload.next_cursor))
    )) return null;
  const items = payload.items.map(sanitizeUserReportItem);
  if (items.some(item => item === null)) return null;
  return {
    schema_version: "walksafe.user-report-list.v1",
    items,
    next_cursor: payload.next_cursor
  };
}

export function validUserReportQuery(url: URL): boolean {
  const seen = new Set<string>();
  for (const [key, value] of url.searchParams) {
    if (seen.has(key) || !["limit", "cursor", "user_status"].includes(key)) return false;
    seen.add(key);
    if (key === "limit" && (!/^[1-9][0-9]{0,2}$/.test(value) || Number(value) > 100)) return false;
    if (key === "cursor" && !USER_REPORT_CURSOR.test(value)) return false;
    if (key === "user_status" && !USER_REPORT_STATUSES.has(value)) return false;
  }
  return true;
}

export function validUserReportRequestHistoryQuery(url: URL): boolean {
  const seen = new Set<string>();
  for (const [key, value] of url.searchParams) {
    if (seen.has(key) || !["limit", "cursor", "report_id"].includes(key)) return false;
    seen.add(key);
    if (key === "limit" && (!/^[1-9][0-9]?$/.test(value) || Number(value) > 25)) {
      return false;
    }
    if (key === "cursor" && !USER_REPORT_CURSOR.test(value)) return false;
    if (key === "report_id" && !CANONICAL_REPORT_UUID.test(value)) return false;
  }
  return true;
}

export function validUserRequestBody(value: unknown): Record<string, unknown> | null {
  const payload = objectPayload(value);
  if (!payload || !exactObjectKeys(
    payload, ["client_request_id", "request_type", "request_text"]
  )) return null;
  if (
    typeof payload.client_request_id !== "string" ||
    !CANONICAL_REPORT_UUID.test(payload.client_request_id) ||
    (payload.request_type !== "CORRECTION" && payload.request_type !== "DELETE") ||
    typeof payload.request_text !== "string" ||
    payload.request_text !== payload.request_text.trim() ||
    Array.from(payload.request_text).length < 1 ||
    Array.from(payload.request_text).length > 500
  ) return null;
  return payload;
}

export function validUserCorrectionBody(value: unknown): UserReportCorrectionRequest | null {
  const payload = objectPayload(value);
  if (!payload) return null;
  const keys = Object.keys(payload);
  const allowed = new Set([
    "expected_revision", "idempotency_key", "user_description", "category_hint"
  ]);
  if (
    keys.some(key => !allowed.has(key)) ||
    !Object.hasOwn(payload, "expected_revision") ||
    !Object.hasOwn(payload, "idempotency_key") ||
    (!Object.hasOwn(payload, "user_description") && !Object.hasOwn(payload, "category_hint")) ||
    typeof payload.expected_revision !== "number" ||
    !Number.isSafeInteger(payload.expected_revision) || payload.expected_revision < 0 ||
    typeof payload.idempotency_key !== "string" ||
    !CANONICAL_REPORT_UUID.test(payload.idempotency_key)
  ) return null;

  const body: Record<string, unknown> = {
    expected_revision: payload.expected_revision,
    idempotency_key: payload.idempotency_key
  };
  if (Object.hasOwn(payload, "user_description")) {
    if (payload.user_description === null) {
      body.user_description = null;
    } else if (typeof payload.user_description === "string") {
      const normalized = canonicalCorrectionDescription(payload.user_description);
      if (normalized === null) return null;
      body.user_description = normalized;
    } else {
      return null;
    }
  }
  if (Object.hasOwn(payload, "category_hint")) {
    if (!nullableContentCategory(payload.category_hint)) return null;
    body.category_hint = payload.category_hint;
  }
  return {
    expectedRevision: payload.expected_revision,
    idempotencyKey: payload.idempotency_key,
    body
  };
}

