/**
 * Defines the bounded, opt-in policy for retaining report payloads while offline.
 * This pure module neither persists nor replays items; callers must provide those lifecycle steps.
 */
export const OFFLINE_REPORT_QUEUE_SCHEMA_VERSION = "walksafe.offline_report_queue.v1";
export const DEFAULT_OFFLINE_REPORT_QUEUE_TTL_MS = 24 * 60 * 60 * 1000;
export const DEFAULT_OFFLINE_REPORT_QUEUE_MAX_ITEMS = 20;
export const DEFAULT_OFFLINE_REPORT_QUEUE_MAX_PAYLOAD_BYTES = 64 * 1024;

export type OfflineReportQueueItem = {
  schema_version: typeof OFFLINE_REPORT_QUEUE_SCHEMA_VERSION;
  id: string;
  created_at_ms: number;
  expires_at_ms: number;
  retry_count: number;
  payload: Record<string, unknown>;
};

export type OfflineReportQueuePruneResult = {
  kept: OfflineReportQueueItem[];
  expired: OfflineReportQueueItem[];
  dropped_over_limit: OfflineReportQueueItem[];
};

export type EnqueueOfflineReportOptions = {
  nowMs: number;
  ttlMs?: number;
  maxItems?: number;
  maxPayloadBytes?: number;
  optIn: boolean;
  idFactory?: () => string;
};

export function payloadSizeBytes(payload: Record<string, unknown>): number {
  return new TextEncoder().encode(JSON.stringify(payload)).length;
}

export function pruneOfflineReportQueue(
  items: OfflineReportQueueItem[],
  options: { nowMs: number; maxItems?: number }
): OfflineReportQueuePruneResult {
  const { nowMs, maxItems = DEFAULT_OFFLINE_REPORT_QUEUE_MAX_ITEMS } = options;
  const active = items.filter((item) => item.expires_at_ms > nowMs);
  const expired = items.filter((item) => item.expires_at_ms <= nowMs);
  const newestFirst = [...active].sort((a, b) => b.created_at_ms - a.created_at_ms);
  const kept = newestFirst.slice(0, maxItems);
  const droppedOverLimit = newestFirst.slice(maxItems);
  return {
    kept: kept.sort((a, b) => a.created_at_ms - b.created_at_ms),
    expired,
    dropped_over_limit: droppedOverLimit
  };
}

export function enqueueOfflineReport(
  existingItems: OfflineReportQueueItem[],
  payload: Record<string, unknown>,
  options: EnqueueOfflineReportOptions
): { item: OfflineReportQueueItem; queue: OfflineReportQueueItem[]; prune: OfflineReportQueuePruneResult } {
  if (!options.optIn) {
    throw new Error("offline report queue requires explicit opt-in");
  }
  const maxPayloadBytes = options.maxPayloadBytes ?? DEFAULT_OFFLINE_REPORT_QUEUE_MAX_PAYLOAD_BYTES;
  const sizeBytes = payloadSizeBytes(payload);
  if (sizeBytes > maxPayloadBytes) {
    throw new Error(`offline report payload too large: ${sizeBytes} > ${maxPayloadBytes}`);
  }

  const ttlMs = options.ttlMs ?? DEFAULT_OFFLINE_REPORT_QUEUE_TTL_MS;
  const item: OfflineReportQueueItem = {
    schema_version: OFFLINE_REPORT_QUEUE_SCHEMA_VERSION,
    id: options.idFactory?.() ?? cryptoRandomId(),
    created_at_ms: options.nowMs,
    expires_at_ms: options.nowMs + ttlMs,
    retry_count: 0,
    payload
  };
  const prune = pruneOfflineReportQueue([...existingItems, item], {
    nowMs: options.nowMs,
    maxItems: options.maxItems
  });
  return { item, queue: prune.kept, prune };
}

export function markOfflineReportRetry(
  items: OfflineReportQueueItem[],
  id: string,
  options: { nowMs: number }
): OfflineReportQueueItem[] {
  const { nowMs } = options;
  return items.map((item) =>
    item.id === id && item.expires_at_ms > nowMs
      ? { ...item, retry_count: item.retry_count + 1 }
      : item
  );
}

export function removeOfflineReport(items: OfflineReportQueueItem[], id: string): OfflineReportQueueItem[] {
  return items.filter((item) => item.id !== id);
}

function cryptoRandomId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return `offline-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
