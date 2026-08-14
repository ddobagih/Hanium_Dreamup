# NPC single-admin recovery Goal start-control reanchor independent review

- Review ID: `WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-NPC-INDEPENDENT-REVIEW-20260812-001`
- Review date: `2026-08-12`
- Reviewed at: `2026-08-12T22:32:19+09:00`
- Reviewed authorization: `authorization.md`
- Authorization physical SHA-256: `1fe494dc36160fae2c6cd4ed344bd1515a23faad3f4f3c2004baf9375ec3e819`
- Authorization byte count: `12634`
- Planned event type: `GOAL_START_CONTROL_REANCHORED`
- Planned event ID: `WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-NPC-20260812-001`
- Planned sequence: `58`
- Finding count: `BLOCKING 0 / MAJOR 0 / MINOR 0`
- Verdict: `PASS_FOR_EXACT_ZERO_CREDIT_CONTROL_DESIGN_AND_CONDITIONAL_PUBLICATION`

## 1. Review scope and method

This review independently checked the authorization's user-scope interpretation, source checkpoint and transition tail, target Goal readiness, R001 and R002 contract bytes and canonical hashes, repository-object state and ancestry, proposed event semantics and field set, checkpoint mutation boundary, sequence 59 lineage, and zero-credit boundary.

The review recomputed hashes from the physical files and inspected live Git directly. It did not infer that a planned event, gate, test, product implementation, deployment, or release had executed. This verdict is valid only for the exact authorization bytes identified above.

## 2. Findings

No blocking, major, or minor defect was found in the exact zero-credit control design.

The verdict permits publication only if all conditions in this review and the authorization are mechanically satisfied at publication time. Those conditions are execution preconditions, not evidence that sequence 58 already exists or that the Goal has started.

## 3. Source checkpoint and Goal-state verification

The physical source checkpoint was independently measured as follows:

| Property | Verified value |
|---|---|
| Path | `docs/control/walksafe-project-continuation-checkpoint.json` |
| Physical SHA-256 | `de3ffafa2d8ff151beedc28e7b5f45296382dcdf93e41280f43136532e54358e` |
| Byte count | `1796959` |
| Mode | `0600` |
| Link count | `1` |
| File type | regular file |

The sealed tail is sequence `57`, event `WS-GOAL-GRAPH-V2-4-GOAL-READY-NPC-SINGLE-ADMIN-RECOVERY-20260810-001`, event SHA-256 `08e25cd9808e2301a4af7a3b463d5a1cda795caf41eed57a35de29676f2210ef`, with predecessor SHA-256 `66d41874a3b1a57a2137f084d38be828c776889b6813667d940f81a6ba421f90`. Canonical JSON hashing of the sequence 57 event after excluding `event_sha256` reproduced the stored event SHA-256 exactly.

The target Goal document SHA-256 independently matched `234a224883779208ba7878a9865076083bb9cfd205dfdb7a760b043f7af6b16d`. The runtime state is internally consistent:

- target and focus Goal: `WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001`
- target status: `READY`
- predecessor `WS-GOAL-EPIC-03-FP-046-R001`: `COMPLETE_AT_TARGET`
- `IN_PROGRESS` Goal count: `0`
- blocked Goal count: `0`
- open question count: `0`
- pending producer completion: empty
- ready frontier: target Goal, `WS-GOAL-EPIC-03`, `WS-GOAL-EPIC-12`

This state supports a control-only `READY -> READY` reanchor. It does not support treating sequence 58 itself as a start event.

## 4. Contract supersession verification

Both contract bindings were independently reproduced from physical bytes and canonical JSON:

| Contract | Physical SHA-256 | Canonical SHA-256 | Bytes |
|---|---|---|---:|
| R001 | `0e9b80005e3ad206af6d43d724dfc70688e6d7ef8907ad6ee2c72da2e60679d1` | `b6a8ada662716ee963f1248ffdf5fdd730c545640a35a7fc11ed134016321186` | `3764` |
| R002 | `37b843953a5c8089ae2b23224804fbef0c21150c2f2bbf876a57cb4cd3083227` | `1ca0369ac5be15375514d800b1c5a66f9a3ff3af4c487df6e379f57a54ca67de` | `5294` |

R002 is an add-only successor with document ID `WS-NPC-SINGLE-ADMIN-RECOVERY-INITIAL-START-GATE-CONTRACT-20260812-002`, contract ID `WS-NPC-SINGLE-ADMIN-RECOVERY-INTERNAL-START-GATE-R002`, and version `2026-08-12.1`. Its supersedes block exactly binds R001 and the sequence 57 readiness event.

The reason code `CURRENT_TEST_LAYER_REGISTRY_RUNNER_REQUIRED` is supported by the repository. R001 calls `scripts/run_walksafe_test_layers_20260711.sh validate`; that historical runner references absent `tests/test_walksafe_v2_5_control_candidate_20260730.py`. R002 instead calls `scripts/run_walksafe_test_layers_current.sh validate`. This justifies an event-sourced successor binding, not mutation of R001 or sequence 57.

The reviewed R002 bytes explicitly include `tests/test_run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812.py` in `ROOT_NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REGRESSION`. The added command member closes the start-gate runner regression gap without changing the eight-check order, execution scope, zero-credit meaning, or recorded review time.

The review therefore accepts `contract_supersession.previous_contract_binding = R001`, `replacement_contract_binding = R002`, and `reason_code = CURRENT_TEST_LAYER_REGISTRY_RUNNER_REQUIRED` as the only valid sequence 58 contract transition.

## 5. Repository reanchor verification

Live Git inspection produced this result:

| Object or relation | Result |
|---|---|
| Historical checkpoint commit `a3ad7eead6b5d834d3e0675422475a9aad351e3d` | absent as a live standalone-repository commit object |
| Authorized base `f0093863e82bfc80d9f11915cef33a51d44b8730` | present commit |
| Authorized head `ca0898d56eaa45b947b9f513a2bcdbfcb5bc5a0c` | present commit |
| `f009386...` ancestor of `ca0898...` | pass |
| Observed branch | `current` |

The add-only historical witness can preserve the old commit/path/blob evidence but does not replace a live repository anchor. Reanchoring the operational snapshot to the present root commit and head is therefore semantically distinct from rewriting history.

The authorization correctly refuses to freeze the sequence 57 managed snapshot as the sequence 58 after-state. The managed membership and its count/path/content hashes must be regenerated only after the R002 contract, authorization, review, checker, tests, and other authorized control files are stable. The publisher must also recapture branch and HEAD immediately before compare-and-swap; a different live value makes this authorization stale.

## 6. Event-type and event-schema review

`CANONICAL_BINDINGS_UPDATED` was rejected for this operation because no canonical deliverable role, artifact status, artifact-work-queue membership, producer-completion state, or canonical content binding is being changed. Using it here would conflate repository/start-gate control maintenance with canonical artifact lifecycle semantics.

`GOAL_START_CONTROL_REANCHORED` is accepted because it communicates all three required facts without overclaiming:

1. the target remains `READY`;
2. repository context and active start-gate control are reanchored;
3. actual Goal start remains a separately gated next transition.

The approved control field set is exactly:

```text
source_ready_event_binding
contract_supersession
repository_context_reanchor
authorization_binding
independent_review_binding
claim_boundary
unchanged_control_projection
```

The nested structure is sufficient and non-duplicative:

- `source_ready_event_binding` identifies the immutable sequence 57 READY source.
- `contract_supersession` contains exact previous/replacement bindings and reason code.
- `repository_context_reanchor.before` binds the source checkpoint and recorded context; `after` binds the live replacement context and recomputed snapshot.
- `authorization_binding` and `independent_review_binding` make the authority chain byte-exact.
- `claim_boundary` makes `implementation_start_authorized=false` and every credit delta zero.
- `unchanged_control_projection.before/after` proves that values outside the allowlist did not drift.

No separate top-level `source_checkpoint_binding`, before/after contract fields, before/after repository fields, supersession-reason field, or allowed-mutation field should be admitted. Their information belongs in the nested fields above or in the external authorization allowlist. Exact field-set enforcement should reject both missing and surplus fields.

## 7. Checkpoint mutation-boundary review

The six authorized mutation classes in `authorization.md` are sufficient and narrowly scoped: one event append and anchor/cutoff advance; repository and handoff reanchor; deterministic managed-snapshot refresh; active R002 projection; authority/review evidence binding; and strictly mechanical schema projection if needed.

The following state must compare equal before and after, except where the authorization explicitly names a mechanical projection:

- all Goal statuses and Goal inventory
- focus Goal, ready frontier, blocked Goals, pending questions, and pending producer completion
- canonical bindings and artifact-work-queue meaning
- artifact lifecycle counts and completion boundary
- current-work meaning and next implementation action
- formal-test inventory, release gates, waiver state, and release status
- implementation, approval, actual-event, external, device, deployment, signing, release, and completion credits

The `unchanged_control_projection` field must carry enough deterministic before/after data for the checker to prove those equalities. A textual assertion alone is insufficient.

Compare-and-swap against the exact source checkpoint, candidate-file validation, final input/Git recapture, atomic replacement, and preservation of regular-file mode `0600` with link count `1` are necessary. They prevent a valid review from being applied to a changed live checkpoint.

## 8. Negative validation matrix

| Mutation or condition | Required result |
|---|---|
| Event sequence is not `58` | reject |
| Event ID or type differs | reject |
| Previous event is not exact sequence 57 SHA-256 | reject |
| Source READY binding differs | reject |
| Target is not `READY` or predecessor is not complete-at-target | reject |
| Any Goal is unexpectedly `IN_PROGRESS` | reject |
| Blocker, question, or pending producer appears | reject |
| Sequence 57 or R001 bytes are modified | reject |
| R002 physical or canonical binding differs | reject |
| Supersession reason differs | reject |
| Live branch or HEAD differs from the authorized after-context | reject as stale authority |
| Base is absent or not an ancestor of head | reject |
| Managed paths are unsafe, duplicate, unsorted, stale, or not reproducible | reject |
| Checkpoint/gate output is incorrectly included in its own snapshot | reject |
| Authorization or review binding differs | reject |
| Required event field is missing or surplus field appears | reject |
| `from_status` or `to_status` is not `READY` | reject |
| `status_changes` is nonempty | reject |
| Canonical, queue, lifecycle, current-work, or completion state drifts | reject |
| `implementation_start_authorized` is not `false` | reject |
| Any credit delta is nonzero | reject |
| Event self-seal does not reproduce | reject |
| Strict validator fails | reject |
| Compare-and-swap source changed | reject with no checkpoint replacement |
| Final checkpoint is not regular `0600`, link count `1` | reject |

These rejection cases must be represented by automated positive/negative checker tests before publication. Passing only the happy path is insufficient for this control transition.

## 9. Sequence 59 lineage review

The split between sequence 58 and sequence 59 is correct. A later start receipt and `GOAL_STARTED` event must preserve two distinct anchors:

- readiness lineage remains anchored to sequence 57 through `source_ready_event_sha256`;
- execution lineage is anchored to the exact published sequence 58 checkpoint through `source_checkpoint_sha256`, while the sequence 59 event's `previous_event_sha256` equals the sequence 58 event SHA-256.

The start gate must execute R002 exactly. R001 and generic-runner fallback are forbidden. Product implementation may begin only after the R002 checks pass, the gate evidence is sealed against the sequence 58 checkpoint, strict continuation/artifact-queue/Goal-graph validation passes for the current work session, and sequence 59 is atomically published.

## 10. Credit and operational-boundary verification

The reviewed authorization grants no implementation-start authority at sequence 58 and preserves the following exact boundary:

```text
implementation_start_authorized=false
goal_status_change_count=0
product_implementation_credit_delta=0
artifact_completion_credit_delta=0
test_credit_delta=0
formal_test_credit_delta=0
approval_credit_delta=0
actual_event_credit_delta=0
external_action_credit_delta=0
actual_device_credit_delta=0
deployment_credit_delta=0
signing_credit_delta=0
release_credit_delta=0
final_completion_credit_delta=0
```

The approved checkpoint state remains `279/279 NOT_RUN` for formal tests, five remaining gates with no waiver, and release `NOT_ELIGIBLE`. No external service, actual-device run, deployment, signing, release, or final completion is authorized or evidenced.

## 11. Final verdict and publication preconditions

Verdict: `PASS_FOR_EXACT_ZERO_CREDIT_CONTROL_DESIGN_AND_CONDITIONAL_PUBLICATION`.

Before sequence 58 may be published, all of these conditions must pass together:

1. The authorization remains exactly SHA-256 `1fe494dc36160fae2c6cd4ed344bd1515a23faad3f4f3c2004baf9375ec3e819`, `12634` bytes.
2. The publisher binds this independent review's final physical SHA-256 and byte count without editing this file afterward.
3. The source checkpoint, source event, Goal document, R001, R002, static plan, live branch, live HEAD, and base ancestry remain exact.
4. The final stable managed snapshot is regenerated and independently reproducible.
5. Positive and negative seq58 checker tests pass against the exact event schema and mutation boundary.
6. All existing applicable continuation and Goal-graph checks pass against a candidate checkpoint.
7. The compare-and-swap source is revalidated immediately before atomic replacement.

If any condition fails, this verdict authorizes no partial publication, fallback, Goal start, product modification, or credit.
