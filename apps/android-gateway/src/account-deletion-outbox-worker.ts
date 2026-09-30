/** Own the timer, cancellation, and completion barrier for the deletion drain. */
export class AccountDeletionOutboxWorker {
  private readonly abort = new AbortController();
  private timer: ReturnType<typeof setInterval> | null = null;
  private inflight: Promise<void> | null = null;
  private stopped = false;
  private lastHealthyDrainAt: number | null = null;
  private drainFailed = false;

  constructor(
    private readonly drain: (signal: AbortSignal) => Promise<unknown>,
    private readonly onError: () => void,
    private readonly hasPausedOperations: () => boolean = () => false
  ) {}

  isReady(nowEpochMs = Date.now()): boolean {
    try {
      return !this.stopped &&
        this.timer !== null &&
        !this.drainFailed &&
        this.lastHealthyDrainAt !== null &&
        nowEpochMs >= this.lastHealthyDrainAt &&
        nowEpochMs - this.lastHealthyDrainAt <= 15_000 &&
        !this.hasPausedOperations();
    } catch {
      return false;
    }
  }

  start(): void {
    if (this.stopped || this.timer !== null) return;
    this.timer = setInterval(() => this.run(), 5_000);
    this.timer.unref();
    this.run();
  }

  private run(): void {
    if (this.stopped || this.inflight !== null) return;
    this.inflight = Promise.resolve()
      .then(async () => {
        if (this.abort.signal.aborted) return;
        const result = await this.drain(this.abort.signal);
        if (typeof result !== "object" || result === null) {
          this.drainFailed = true;
          return;
        }
        const outcome = result as { attempted?: unknown; succeeded?: unknown };
        if (
          !Number.isSafeInteger(outcome.attempted) ||
          !Number.isSafeInteger(outcome.succeeded) ||
          (outcome.attempted as number) < 0 ||
          (outcome.succeeded as number) < 0 ||
          (outcome.succeeded as number) > (outcome.attempted as number) ||
          outcome.attempted !== outcome.succeeded
        ) {
          this.drainFailed = true;
          return;
        }
        if (outcome.attempted === 0 && this.drainFailed) return;
        this.drainFailed = false;
        this.lastHealthyDrainAt = Date.now();
      })
      .catch(() => {
        this.drainFailed = true;
        this.onError();
      })
      .finally(() => { this.inflight = null; });
  }

  async stop(): Promise<void> {
    this.stopped = true;
    if (this.timer !== null) clearInterval(this.timer);
    this.timer = null;
    this.abort.abort();
    // Never release the service lock while a drain can still write durable state.
    await this.inflight;
  }
}
