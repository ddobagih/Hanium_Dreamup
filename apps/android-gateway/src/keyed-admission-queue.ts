export class QueueAdmissionBusyError extends Error {
  constructor() {
    super("Keyed admission queue is busy.");
    this.name = "QueueAdmissionBusyError";
  }
}

export class QueueAdmissionAbortedError extends Error {
  constructor() {
    super("Keyed admission was aborted.");
    this.name = "QueueAdmissionAbortedError";
  }
}

type Waiter = {
  deadline: number;
  signal: AbortSignal | undefined;
  timer: ReturnType<typeof setTimeout> | null;
  onAbort: (() => void) | null;
  resolve: () => void;
  reject: (error: Error) => void;
};

type KeyState = {
  waiters: Waiter[];
  priority: Waiter | null;
  priorityActive: boolean;
};

export class KeyedAdmissionQueue {
  private readonly keys = new Map<string, KeyState>();

  constructor(
    private readonly maxWaitersPerKey: number,
    private readonly waitTimeoutMs: number
  ) {
    if (!Number.isSafeInteger(maxWaitersPerKey) || maxWaitersPerKey < 0 ||
      !Number.isFinite(waitTimeoutMs) || waitTimeoutMs <= 0) {
      throw new RangeError("Invalid keyed admission queue limits.");
    }
  }

  async run<T>(
    key: string,
    action: () => Promise<T>,
    signal?: AbortSignal
  ): Promise<T> {
    if (signal?.aborted) throw new QueueAdmissionAbortedError();
    let state = this.keys.get(key);
    if (state === undefined) {
      state = { waiters: [], priority: null, priorityActive: false };
      this.keys.set(key, state);
    } else {
      const waitingState = state;
      if (waitingState.priority !== null || waitingState.priorityActive ||
        waitingState.waiters.length >= this.maxWaitersPerKey) {
        throw new QueueAdmissionBusyError();
      }
      const admission = new Promise<void>((resolve, reject) => {
        const waiter: Waiter = {
          deadline: performance.now() + this.waitTimeoutMs,
          signal,
          timer: null,
          onAbort: null,
          resolve,
          reject
        };
        waitingState.waiters.push(waiter);
        if (signal) {
          waiter.onAbort = () => this.rejectWaiter(
            waitingState, waiter, new QueueAdmissionAbortedError()
          );
          signal.addEventListener("abort", waiter.onAbort, { once: true });
          if (signal.aborted) {
            waiter.onAbort();
            return;
          }
        }
        this.scheduleDeadline(waitingState, waiter);
      });
      await admission;
    }

    try {
      if (signal?.aborted) throw new QueueAdmissionAbortedError();
      return await action();
    } finally {
      this.release(key, state);
    }
  }

  // Reserved for full security revocation; ordinary work never bypasses this barrier.
  async runPriority<T>(key: string, action: () => Promise<T>): Promise<T> {
    let state = this.keys.get(key);
    if (state === undefined) {
      state = { waiters: [], priority: null, priorityActive: true };
      this.keys.set(key, state);
    } else {
      const waitingState = state;
      if (waitingState.priority !== null || waitingState.priorityActive) {
        throw new QueueAdmissionBusyError();
      }
      for (const waiter of [...waitingState.waiters]) {
        this.rejectWaiter(waitingState, waiter, new QueueAdmissionBusyError());
      }
      const admission = new Promise<void>((resolve, reject) => {
        const waiter: Waiter = {
          deadline: performance.now() + this.waitTimeoutMs,
          signal: undefined,
          timer: null,
          onAbort: null,
          resolve,
          reject
        };
        waitingState.priority = waiter;
        this.scheduleDeadline(waitingState, waiter);
      });
      await admission;
    }

    try {
      return await action();
    } finally {
      this.release(key, state);
    }
  }

  private scheduleDeadline(state: KeyState, waiter: Waiter): void {
    const remaining = waiter.deadline - performance.now();
    if (remaining <= 0) {
      this.rejectWaiter(state, waiter, new QueueAdmissionBusyError());
      return;
    }
    waiter.timer = setTimeout(() => {
      waiter.timer = null;
      this.scheduleDeadline(state, waiter);
    }, Math.min(remaining, 2_147_483_647));
  }

  private clearWaiter(waiter: Waiter): void {
    if (waiter.timer !== null) clearTimeout(waiter.timer);
    waiter.timer = null;
    if (waiter.signal && waiter.onAbort) {
      waiter.signal.removeEventListener("abort", waiter.onAbort);
    }
    waiter.onAbort = null;
  }

  private rejectWaiter(state: KeyState, waiter: Waiter, error: Error): void {
    if (state.priority === waiter) {
      state.priority = null;
    } else {
      const index = state.waiters.indexOf(waiter);
      if (index < 0) return;
      state.waiters.splice(index, 1);
    }
    this.clearWaiter(waiter);
    waiter.reject(error);
  }

  private release(key: string, state: KeyState): void {
    for (;;) {
      const priority = state.priority !== null;
      const waiter = priority ? state.priority : state.waiters.shift();
      if (priority) state.priority = null;
      if (!waiter) {
        state.priorityActive = false;
        this.keys.delete(key);
        return;
      }
      this.clearWaiter(waiter);
      if (waiter.signal?.aborted) {
        waiter.reject(new QueueAdmissionAbortedError());
      } else if (performance.now() >= waiter.deadline) {
        waiter.reject(new QueueAdmissionBusyError());
      } else {
        if (priority) state.priorityActive = true;
        waiter.resolve();
        return;
      }
    }
  }
}
