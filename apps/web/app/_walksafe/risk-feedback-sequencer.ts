/** Bounded risk-action arbitration: higher severity preempts, peers stay FIFO. */
export const RISK_FEEDBACK_SEQUENCE_CAPACITY = 3;
export const RISK_FEEDBACK_SEQUENCE_TTL_MS = 1_800;
export const RISK_FEEDBACK_MAX_FAILURES = 3;
export const RISK_FEEDBACK_RETRY_BASE_MS = 600;

export type RiskFeedbackItem<T> = {
  key: string;
  severityRank: number;
  lastSeenAtMs: number;
  payload: T;
};

export type RiskFeedbackSequence<T> = {
  active: RiskFeedbackItem<T> | null;
  pending: RiskFeedbackItem<T>[];
};

export type RiskFeedbackEnqueueOutcome =
  | "activated"
  | "preempted"
  | "queued"
  | "queued_replacing_lower"
  | "refreshed"
  | "dropped_capacity"
  | "dropped_invalid";

export function createRiskFeedbackSequence<T>(): RiskFeedbackSequence<T> {
  return { active: null, pending: [] };
}

function isStructurallyValid<T>(item: RiskFeedbackItem<T>): boolean {
  return item.key.length > 0 && Number.isFinite(item.severityRank) && item.severityRank > 0 &&
    Number.isFinite(item.lastSeenAtMs);
}

function isFresh<T>(item: RiskFeedbackItem<T>, nowMs: number): boolean {
  const ageMs = nowMs - item.lastSeenAtMs;
  return ageMs >= 0 && ageMs <= RISK_FEEDBACK_SEQUENCE_TTL_MS;
}

function prunePending<T>(pending: readonly RiskFeedbackItem<T>[], nowMs: number): RiskFeedbackItem<T>[] {
  return pending.filter((item) => isFresh(item, nowMs));
}

export function enqueueRiskFeedback<T>(
  current: RiskFeedbackSequence<T>,
  item: RiskFeedbackItem<T>,
  nowMs: number
): { state: RiskFeedbackSequence<T>; outcome: RiskFeedbackEnqueueOutcome; preempted: RiskFeedbackItem<T> | null } {
  if (!isStructurallyValid(item) || !Number.isFinite(nowMs) || !isFresh(item, nowMs)) {
    return { state: current, outcome: "dropped_invalid", preempted: null };
  }

  const state: RiskFeedbackSequence<T> = {
    active: current.active,
    pending: prunePending(current.pending, nowMs)
  };
  const active = state.active;
  if (!active) {
    return { state: { ...state, active: item }, outcome: "activated", preempted: null };
  }

  if (active.key === item.key) {
    if (item.severityRank > active.severityRank) {
      return { state: { ...state, active: item }, outcome: "preempted", preempted: active };
    }
    return {
      state: {
        ...state,
        active: { ...item, severityRank: Math.max(active.severityRank, item.severityRank) }
      },
      outcome: "refreshed",
      preempted: null
    };
  }

  const pendingIndex = state.pending.findIndex((queued) => queued.key === item.key);
  if (pendingIndex >= 0) {
    if (item.severityRank > active.severityRank) {
      const pending = [...state.pending];
      pending.splice(pendingIndex, 1);
      return { state: { active: item, pending }, outcome: "preempted", preempted: active };
    }
    const pending = [...state.pending];
    pending[pendingIndex] = item;
    return { state: { ...state, pending }, outcome: "refreshed", preempted: null };
  }

  if (item.severityRank > active.severityRank) {
    return { state: { ...state, active: item }, outcome: "preempted", preempted: active };
  }

  if (state.pending.length < RISK_FEEDBACK_SEQUENCE_CAPACITY - 1) {
    return { state: { ...state, pending: [...state.pending, item] }, outcome: "queued", preempted: null };
  }

  const lowestRank = Math.min(...state.pending.map((queued) => queued.severityRank));
  if (item.severityRank <= lowestRank) {
    return { state, outcome: "dropped_capacity", preempted: null };
  }
  const replaceIndex = state.pending.map((queued) => queued.severityRank).lastIndexOf(lowestRank);
  const pending = state.pending.filter((_, index) => index !== replaceIndex);
  pending.push(item);
  return { state: { ...state, pending }, outcome: "queued_replacing_lower", preempted: null };
}

export function completeRiskFeedback<T>(
  current: RiskFeedbackSequence<T>,
  activeKey: string
): RiskFeedbackSequence<T> {
  if (current.active?.key !== activeKey) return current;
  const [active = null, ...pending] = current.pending;
  return { active, pending };
}

export function claimRiskFeedbackForDelivery<T>(
  current: RiskFeedbackSequence<T>,
  options: { nowMs: number; isCurrent: (item: RiskFeedbackItem<T>) => boolean }
): { state: RiskFeedbackSequence<T>; item: RiskFeedbackItem<T> | null; skippedKeys: string[] } {
  let active = current.active;
  const pending = prunePending(current.pending, options.nowMs);
  const skippedKeys: string[] = [];
  while (active && (!isFresh(active, options.nowMs) || !options.isCurrent(active))) {
    skippedKeys.push(active.key);
    active = pending.shift() ?? null;
  }
  return { state: { active, pending }, item: active, skippedKeys };
}

export function advanceRiskFeedbackFailure(retryCount: number): {
  retryCount: number;
  delayMs: number | null;
  terminal: boolean;
} {
  const nextRetryCount = Math.max(0, Math.floor(retryCount)) + 1;
  if (nextRetryCount >= RISK_FEEDBACK_MAX_FAILURES) {
    return { retryCount: nextRetryCount, delayMs: null, terminal: true };
  }
  return {
    retryCount: nextRetryCount,
    delayMs: RISK_FEEDBACK_RETRY_BASE_MS * 2 ** (nextRetryCount - 1),
    terminal: false
  };
}
