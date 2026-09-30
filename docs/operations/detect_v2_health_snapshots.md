# Detect v2 health snapshots

## Scope and policy

The detect.v2 health endpoint reads an already computed process-local snapshot.
It does not stat files, load configuration, hash artifacts, start work, or wait
for a refresh. The HTTP contract remains 200 with the existing flat health
fields. An unavailable health state is not an inference result.

The dedicated settings are:

| Environment variable | Default | Meaning |
| --- | --- | --- |
| WALKSAFE_DETECT_V2_HEALTH_REFRESH_INTERVAL_SECONDS | 5 | Delay between completed refresh attempts |
| WALKSAFE_DETECT_V2_HEALTH_MAX_AGE_SECONDS | 10 | Maximum successful snapshot age, measured from calculation start |
| WALKSAFE_DETECT_V2_HEALTH_REFRESH_TIMEOUT_SECONDS | 5 | One refresh deadline |

All values must be finite and positive. Maximum age must cover both the
interval and the refresh timeout. These are new conservative policy defaults,
not measured operational optima and not reused inference or storage-inventory
budgets. Slow refreshes can cause a fail-closed age gap; uninterrupted ready
responses are not promised.

File changes are detected by periodic refresh, not by the query. The default
detection delay includes the five-second interval and calculation time.
A changed configuration identity invalidates the matching memory generation.
Read requests never extend the success lifetime or create additional workers.

## Initialization and response states

The application lifecycle starts the manager and waits for its first outcome
up to the refresh deadline. A failure becomes unavailable, not a new startup
admission gate. The standalone reader before initialization returns
detect_v2_health_not_checked; it never falls back to synchronous validation.

Fixed health-only reasons are:

- detect_v2_health_not_checked
- detect_v2_health_expired
- detect_v2_health_refresh_timeout
- detect_v2_health_refresh_failed
- detect_v2_health_artifacts_changed
- detect_v2_health_stopped

Ordinary candidate configuration errors retain their existing reason.
Synthetic unavailable responses preserve all response field names and
configured path basenames, with unknown runtime fields set to null. They do
not mislabel an unchecked state as a missing model. No exception message,
configuration dump, secret, or full filesystem path is sent over the public
health response.

The immutable child projection contains only mode, environment, the four
role-specific model/configuration paths, and image size. It is not a Settings
object or a model object. The manager is not attached to Settings, preserving
the separate inference serialization boundary.

## Refresh and publication

One coordinator runs one refresh at a time. A dedicated child calculates the
candidate without reading or publishing the parent's provider cache. It does
not require model loading, GPU initialization, or inference warmup.

The child fingerprints the four configured paths before and after candidate
calculation, including symlink targets where present. A detected change rejects
the candidate. This is metadata-change detection, not proof against every
possible metadata-preserving alteration. The parent accepts only the current
settings generation and a finite, timely calculation age.

A failed attempt replaces previous ready state with unavailable. A late result
cannot renew an expired successful snapshot. Repeated queries do not create a
refresh backlog. Readiness warmup and inference availability remain separate
contracts.

## Linux subprocess and IPC ownership

The production runner uses its own child, nonblocking stdin/stdout pipes, and
a selector. It does not reuse the inference runner. Input and output are
eight-byte unsigned big-endian length-prefixed JSON. Both payloads are limited
to MAX_HEALTH_FRAME_BYTES = 65536 bytes, a new internal protocol bound.

The parent requires a complete frame, valid unique-key finite JSON, the exact
flat response shape, EOF without trailing bytes, zero child exit status, and
a remaining deadline after parsing and exit observation. Partial frames,
oversize frames, malformed responses, nonzero exits, and cancellation fail
closed. Child diagnostics are not protocol output.

Cleanup has a separate one-second budget. It attempts termination, then kill
if needed, and reaps only the owned child. Selector and both channel closures
are attempted independently. Unconfirmed exit or close retains the affected
reference and quarantines that runner; another child is not created over it.

Manager close returns true only after its coordinator has exited and its
owned production resources are confirmed closed. The stop wrapper raises the
fixed error "detect v2 health refresh cleanup is unconfirmed" on false without
discarding ownership. A later close may retry cleanup.

Process creation, interpreter imports, native cleanup, and OS uninterruptible
states do not have an unconditional end-to-end wall-clock guarantee. A timeout
response is not proof of child exit. A quarantined child is not reported as
successfully recovered.

## Test seams and evidence boundaries

The runner callable interface is (settings_projection, timeout_seconds,
stop_event) returning the existing health dictionary. Injected cooperative
runners are useful for clock and generation observations only; they do not
prove cancellation of blocked file I/O.

The worker command seam permits an owned bootstrap to install Engine and
socket guards before worker/candidate execution. Parent monkeypatches are not
assumed to propagate to a child. Guard coverage begins at the documented
bootstrap point, not before all interpreter imports.

The direct regression file includes real Linux child/selector cases for
blocked FIFO I/O, partial pipe responses, oversized/malformed JSON, nonzero
exit, file change during computation, stop ownership, and a successful next
refresh after timeout cleanup. Fixture fallback cleanup is reported separately
from runner cleanup. Controlled fake cleanup failures are not real process
termination evidence.

The existing four HTTP health tests explicitly prepare a real candidate
refresh before retaining their original exact response assertions. The
provider identity and lazy model-loading contracts remain separate regressions.
No test result is claimed by this document; execution receipts are maintained
by the integrating owner.
