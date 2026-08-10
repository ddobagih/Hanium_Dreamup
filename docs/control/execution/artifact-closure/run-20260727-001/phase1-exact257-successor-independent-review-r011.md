# Phase 1 Exact257 Successor R011 Independent Document and Control Review

## Review control

| Field | Value |
|---|---|
| Review ID | `WS-PHASE1-EXACT257-SUCCESSOR-INDEPENDENT-REVIEW-20260729-R011` |
| Review date | `2026-07-29` |
| Reviewer identity | `CODEX-INDEPENDENT-REVIEWER-R011-20260729-001` |
| Reviewer task | `/root/audit_r011_semantics` |
| Review scope | `INDEPENDENT_READ_ONLY_DOCUMENT_AND_CONTROL_REVIEW` |
| Generator implementation | `NOT_PERFORMED_BY_REVIEWER` |
| Materialization | `NOT_PERFORMED_BY_REVIEWER` |
| Product independent QA | `NOT_PERFORMED` |
| Subject packet | `docs/control/execution/artifact-closure/run-20260727-001/packets/phase1-exact257-successor-r011` |
| Verdict | `PASS_FOR_READY25_PROGRESS_APPLICATION_WITH_ZERO_CREDIT_BOUNDARY_ONLY` |

This review was performed after the three R011 JSON subjects had been
materialized. The reviewer did not implement the generator and did not perform
the materialization. Here, independent means that the actual files and their
document/control claims were checked by a reviewer separate from those
activities. It does not mean product-independent QA, product acceptance,
approval, testing, execution, event performance, formal evidence, closure, or
release review.

## Findings

| Severity | Count |
|---|---:|
| BLOCKING | 0 |
| MAJOR | 0 |
| MINOR | 0 |
| **Total** | **0** |

The zero-finding result is limited to the stated read-only document and control
scope.

## Physical subjects

The following files were read from their materialized paths. Their current
physical byte lengths and SHA-256 values are:

| Subject | Bytes | Physical SHA-256 |
|---|---:|---|
| `docs/control/execution/artifact-closure/run-20260727-001/packets/phase1-exact257-successor-r011/phase1-exact257-successor-ledger-r011.json` | 2,637,012 | `7fc4bd6f2242b17de75faa040e42b4744c972a492f87ff4365caaeae53f93368` |
| `docs/control/execution/artifact-closure/run-20260727-001/packets/phase1-exact257-successor-r011/evidence.json` | 12,671 | `f44642a1c635fb0d7122a3e4d8dff1d952fe2e0ee641bef5b7b5b469ce60aa3f` |
| `docs/control/execution/artifact-closure/run-20260727-001/packets/phase1-exact257-successor-r011/phase1-exact257-successor-check-receipt-r011.json` | 16,749 | `3cf69c9c0f382ca2d2622e552d57d82b5df02468ae85f7d67e8be8477bc9714d` |

The packet directory contains exactly those three direct-child regular files.
The directory mode is `0700`; each JSON file mode is `0644`; no subject is a
symbolic link and no R011 staging directory remained after publication.

The reviewed generator and regression-test files were also physically hashed:

| Control file | Bytes | Physical SHA-256 |
|---|---:|---|
| `scripts/build_walksafe_phase1_exact257_successor_r011_20260729.py` | 73,574 | `58ae751014972662d0aea4a9f94cf1b13a451ce32add98facbbfc354a1d7a26a` |
| `tests/test_walksafe_phase1_exact257_successor_r011_20260729.py` | 29,798 | `8b750365d08a4a3d1104ee9566d47a1807903cf4bd0f9f298276bc9b6c971869` |

## Exact-set and row-delta contract

The R007 predecessor has physical SHA-256
`4cf29456590aaa6df99a4306e50e9d39bf152b69208b3b78c1d1f6087a0a33e9`.
Independent comparison of its ordered `records` array with the physical R011
ledger produced:

| Check | Result |
|---|---:|
| Ordered predecessor rows | 257 |
| Ordered R011 rows | 257 |
| Record-order mismatches | 0 |
| Completely unchanged non-Ready25 rows | 232 |
| Ready25 progress-only rows | 25 |
| Unexpected or non-allowlisted row deltas | 0 |

Each of the exact25 rows has exactly one new append at each of these paths, with
the previous list retained as an exact prefix:

- `/records/*/progress_axes/content_authored/observations/-`
- `/records/*/progress_axes/packet_materialization/-`
- `/records/*/progress_axes/independent_review/-`

Every other field in those rows is unchanged. All 232 rows outside exact25 are
identical JSON objects to their R007 predecessors.

The exact-set fingerprints were independently reproduced using UTF-8,
lexicographically sorted IDs, one ID per line, and a final LF:

| Set | Items | Serialized bytes | SHA-256 |
|---|---:|---:|---|
| exact9 | 9 | 103 | `f9ad54b016fd88acd9902bedb5b72d069c40976f0c5bf406bfd72c9d7f563703` |
| exact13 | 13 | 143 | `20a267bf37e7626a33ffe2751a136c8d562ca61d606065ed3fe6ab97dade31a5` |
| exact3 | 3 | 33 | `cbf82d6ed916977579682552299668ca2e552a22a0c55284c2a7ffe564fb631e` |
| exact25 | 25 | 279 | `c952cc7fc5d6c440444ab27e121b463a4db23fc1990b9bdbba38f6421baaf948` |

The three native progress-axis totals changed only by the exact25 appends:

| Progress axis | R007 | R011 | Delta |
|---|---:|---:|---:|
| Content-authored observations | 146 | 171 | 25 |
| Packet materializations | 146 | 171 | 25 |
| Independent-review-axis entries | 80 | 105 | 25 |

The independent-review-axis name is inherited ledger structure. The new entries
are explicitly bounded to source document-review observations; they do not
constitute product-independent QA.

## Preserved exact257 invariants

The physical rows reproduce these canonical counts:

| Canonical status | Count |
|---|---:|
| `OK` | 124 |
| `INTERNAL_GAP` | 48 |
| `EXTERNAL` | 49 |
| `N_A_CANDIDATE` | 36 |

The physical rows reproduce these current queue-route counts:

| Queue route | Count |
|---|---:|
| `OK_BASELINE` | 124 |
| `INTERNAL_READY` | 62 |
| `INTERNAL_RUN_REQUIRED` | 24 |
| `OWNER_APPROVAL_PENDING` | 14 |
| `ATTESTATION_REVIEW_PENDING` | 4 |
| `EVIDENCE_FACT_PENDING` | 6 |
| `SCOPE_DECISION_PENDING` | 0 |
| `REAL_EVENT_PENDING` | 21 |
| `SCOPE_N_A_APPROVED` | 2 |

The ledger summaries and authorization boundary are identical to R007.
`BASELINE_OK_ONLY_NO_PHASE1_CLOSURE=124` plus
`CLOSED_N_A_FOR_CURRENT_SCOPE=2` yields 126 closed-equivalent rows, while 131
rows remain `OPEN`. Release status remains `NOT_ELIGIBLE`.

## Zero-credit and responsibility boundary

The ledger application, evidence preserved invariants, and receipt summary each
contain the same strict-integer zero-credit map:

| Credit field | Value |
|---|---:|
| `acceptance_count` | 0 |
| `actual_device_event_count` | 0 |
| `attestation_approval_count` | 0 |
| `execution_count` | 0 |
| `formal279_pass_count` | 0 |
| `formal_evidence_count` | 0 |
| `in_scope_substantive_credit_count` | 0 |
| `owner_approval_count` | 0 |
| `real_event_count` | 0 |
| `release_eligible_count` | 0 |
| `verified_rights_or_external_fact_count` | 0 |

For every exact25 append, content acceptance, owner approval, completion, and
state promotion remain boolean `false`. Product-QA acceptance, closure,
execution, owner-approval, and release credit also remain boolean `false`.
Execution-completion, formal-pass, real-event, owner-approval, and
release-eligibility claims remain boolean `false` in the inherited row
boundaries.

The product-independent QA reviewer remains `UNASSIGNED`, and product-QA
acceptance count remains 0. No product build or test and no approval event were
performed by R011.

## Source-review and independent-review boundary

All 16 declared source bindings have unique IDs and paths. Each binding's path,
byte length, and SHA-256 matches the current physical file:

| Source group | Matching bindings |
|---|---:|
| R007/R010 controlled chain | 4/4 |
| Current artifact register, change log, and manifest | 3/3 |
| Ready25 exact9, exact13, and exact3 packet/receipt/review files | 9/9 |
| **Total** | **16/16** |

The three source document-review files report zero findings within their own
document/control scopes. R011 correctly preserves these separate facts:

- source reviewer identity recorded count is 0;
- source review independence verified by R011 is `false`;
- source document reviews do not substitute for product QA;
- the product-independent QA reviewer is `UNASSIGNED`; and
- product-QA acceptance count is 0.

The builder did not generate this R011 review. The evidence accurately records
that a separate post-materialization review was pending at generation time.
This file is that separate review, created after the packet was present. It
does not alter the packet, retroactively verify the source reviewers'
independence, assign product QA, or grant acceptance or approval credit.

## JSON integrity and binding graph

All three physical JSON files parse as UTF-8 objects under duplicate-key and
non-finite-number rejection. Each has exactly one terminal LF and no terminal
CRLF. The declared non-self projections were independently reproduced with
recursive lexicographic key ordering, compact `,` and `:` separators,
`ensure_ascii=false`, all four digest fields replaced by JSON null, and the
final LF included:

| Target | Canonical bytes | Non-self SHA-256 | Final LF |
|---|---:|---|---:|
| R011 ledger | 1,839,190 | `3219242896c297e2e86b0c9ccd03f1a362c1226bbd78e202c8c621fef6a0fd5e` | PASS |
| R011 evidence | 10,538 | `605ff63bd34057291b6469476c43ef1e5724932af1ee4d47daa6bd6f6487f0c5` | PASS |
| R011 receipt | 13,028 | `09e16ea6c46b12bea377081cc7027fbf1679c36bee9721cbaf685f5146c25160` | PASS |

Both integrity sections in every JSON reproduce the independently calculated
canonical byte count and SHA-256. The evidence's ledger output binding matches
the physical ledger, and the receipt's ledger and evidence output bindings
match both physical files. The receipt contains 9 checks, all `PASS`, with
`9 PASS / 0 FAIL`.

## Add-only atomic publication control

The evidence and receipt both declare add-only publication, overwrite
prohibition, the single R011 packet directory, and
`STAGE_DIRECTORY_THEN_RENAMEAT2_NOREPLACE`. Under the physically hashed
generator implementation, all three outputs must be direct children of one
staging directory; files and staging directory are synchronized before a
single `renameat2(RENAME_NOREPLACE)` publication, followed by parent-directory
synchronization. Existing targets are rejected, and unpublished staging
content is cleaned after handled failures.

The actual packet is one directory containing exactly the three expected JSON
files, and the regression suite covers interrupted writes, abrupt child exit,
no-replace behavior, path confinement, and committed-output verification. The
reviewer did not perform or runtime-trace the materialization transaction; this
finding validates the materialized state and the pinned atomic-publication
control implementation, not an independently witnessed syscall event.

## Validation

The generator's read-only committed-output check returned:

```text
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. python3 -B scripts/build_walksafe_phase1_exact257_successor_r011_20260729.py --check
verified 3 add-only R011 outputs; exact257=257, closed-equivalent=126, open=131, release=NOT_ELIGIBLE
```

The full R011 regression file returned:

```text
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. .venv/bin/python -B -m pytest -q -p no:cacheprovider tests/test_walksafe_phase1_exact257_successor_r011_20260729.py
23 passed, 1 skipped in 2.41s
```

The skipped case is the expected unmaterialized-state check because the R011
packet now exists. The committed-output check executed.

An independent read-only recomputation, separate from the generator CLI,
returned:

```text
rows=257
record_order_mismatch=0
unchanged=232
progress_only=25
unexpected_delta=0
progress_totals=171/171/105
closed_equivalent=126
open=131
source_bindings=16/16
receipt_checks=9/9 PASS
```

## Verdict

`PASS_FOR_READY25_PROGRESS_APPLICATION_WITH_ZERO_CREDIT_BOUNDARY_ONLY`

This verdict accepts only the deterministic Ready25 progress-observation
application and its physical control bindings. This review is not product
independent QA and grants no product acceptance, approval, test, execution,
device or real-event, formal-evidence, release, deployment, completion, or
closure credit.
