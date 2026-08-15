# NPC single-admin recovery Goal start-control reanchor authorization

- Authorization ID: `WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-NPC-AUTHORIZATION-20260812-001`
- Authorization date: `2026-08-12`
- Recorded at: `2026-08-12T22:30:24+09:00`
- Status: `AUTHORIZED_FOR_EXACT_ZERO_CREDIT_PREPARATION_AND_CONDITIONAL_SEQ58_PUBLICATION`
- Planned event type: `GOAL_START_CONTROL_REANCHORED`
- Planned event ID: `WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-NPC-20260812-001`
- Planned sequence: `58`
- Target Goal: `WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001`
- Independent review: `independent-review.md`

## 1. Decision

Sequence 58 is authorized only as an add-only, zero-credit control event that repairs the repository snapshot context and makes the R002 start-gate contract the active successor binding for the target Goal. The event must leave the target Goal `READY -> READY`; it must not be represented as `GOAL_STARTED`, implementation work, test execution, completion, approval, deployment, release, or an external event.

`CANONICAL_BINDINGS_UPDATED` is not the correct event type. That event type governs canonical deliverable-role and artifact-queue binding changes and may participate in producer completion ordering. This authorization does not change a canonical deliverable, artifact state, queue membership, or producer completion state. The dedicated type is therefore `GOAL_START_CONTROL_REANCHORED`.

The actual start-gate execution and `GOAL_STARTED` transition are separate work. They may occur no earlier than sequence 59, after sequence 58 has been published and its exact successor checkpoint has passed strict continuation and Goal-graph validation.

## 2. User authorization basis

사용자는 중단 지점을 확인한 뒤 중단 전과 같은 방식으로 기능 구현·산출물 작성을 계속하고, 목표·단계별 계획과 검토·독립 검수를 거쳐 끝날 때까지 진행하며, 추가 확인을 기다리지 말고 바로 진행하라고 반복 지시했다.

This authorizes the repository-internal preparation, validation, and exact conditional publication needed to resume the already selected Goal. It does not authorize contacting an external service or person, operating an actual device, using production credentials, deploying, signing, releasing, fabricating external evidence, waiving a gate, or claiming final project completion.

## 3. Bound source state

| Binding | Exact value |
|---|---|
| Source checkpoint | `docs/control/walksafe-project-continuation-checkpoint.json` |
| Source checkpoint physical SHA-256 | `de3ffafa2d8ff151beedc28e7b5f45296382dcdf93e41280f43136532e54358e` |
| Source checkpoint byte count | `1796959` |
| Source event sequence | `57` |
| Source event ID | `WS-GOAL-GRAPH-V2-4-GOAL-READY-NPC-SINGLE-ADMIN-RECOVERY-20260810-001` |
| Source event SHA-256 | `08e25cd9808e2301a4af7a3b463d5a1cda795caf41eed57a35de29676f2210ef` |
| Source event predecessor SHA-256 | `66d41874a3b1a57a2137f084d38be828c776889b6813667d940f81a6ba421f90` |
| Goal document | `docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/epic-03-npc-single-admin-recovery-r001.md` |
| Goal document SHA-256 | `234a224883779208ba7878a9865076083bb9cfd205dfdb7a760b043f7af6b16d` |
| Static plan manifest SHA-256 | `7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07` |

At authorization time, the target Goal is the focus Goal and is `READY`; its predecessor `WS-GOAL-EPIC-03-FP-046-R001` is `COMPLETE_AT_TARGET`. There are no `IN_PROGRESS` Goals, Goal blockers, pending questions, or pending producer completion. The ready frontier is exactly the target Goal, `WS-GOAL-EPIC-03`, and `WS-GOAL-EPIC-12`.

## 4. Start-gate contract successor binding

The sequence 57 R001 binding is immutable history and must not be edited or retroactively replaced.

| Binding | R001 before | R002 after |
|---|---|---|
| Schema version | `1.0` | `1.1` |
| Document ID | `WS-NPC-SINGLE-ADMIN-RECOVERY-INITIAL-START-GATE-CONTRACT-20260810-001` | `WS-NPC-SINGLE-ADMIN-RECOVERY-INITIAL-START-GATE-CONTRACT-20260812-002` |
| Contract ID | `WS-NPC-SINGLE-ADMIN-RECOVERY-INTERNAL-START-GATE-R001` | `WS-NPC-SINGLE-ADMIN-RECOVERY-INTERNAL-START-GATE-R002` |
| Contract version | `2026-08-10.1` | `2026-08-12.1` |
| Path | `docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001/initial-start-gate-contract-r001.json` | `docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001/initial-start-gate-contract-r002.json` |
| Physical SHA-256 | `0e9b80005e3ad206af6d43d724dfc70688e6d7ef8907ad6ee2c72da2e60679d1` | `37b843953a5c8089ae2b23224804fbef0c21150c2f2bbf876a57cb4cd3083227` |
| Canonical SHA-256 | `b6a8ada662716ee963f1248ffdf5fdd730c545640a35a7fc11ed134016321186` | `1ca0369ac5be15375514d800b1c5a66f9a3ff3af4c487df6e379f57a54ca67de` |
| Byte count | `3764` | `5294` |

The only supersession reason is `CURRENT_TEST_LAYER_REGISTRY_RUNNER_REQUIRED`: R001 preserves a historical test-layer runner that cannot validate the current registry, while R002 uses `scripts/run_walksafe_test_layers_current.sh`. R002 remains add-only and cannot become effective merely because the file exists. Sequence 58 must bind it explicitly, and sequence 57 must retain the R001 bytes and binding above.

The R002 `ROOT_NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REGRESSION` command also names `tests/test_run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812.py` explicitly. This closes the reviewed start-gate runner regression gap before sequence 58 publication; it does not execute the gate or grant test credit.

## 5. Repository context reanchor

The checkpoint's recorded repository context is historical and cannot serve as the live standalone repository anchor: it records branch `codex/walksafe-rc2-hardening-20260715` and commit `a3ad7eead6b5d834d3e0675422475a9aad351e3d`, while that commit is not a live object in the standalone repository. The repository-internal historical witness may preserve byte-level history, but it does not turn that object into the current live Git anchor.

Sequence 58 may reanchor the repository context only as follows:

| Context | Before | Authorized after |
|---|---|---|
| Branch | `codex/walksafe-rc2-hardening-20260715` | `current` |
| Base commit | `a3ad7eead6b5d834d3e0675422475a9aad351e3d` | `f0093863e82bfc80d9f11915cef33a51d44b8730` |
| Current head | `a3ad7eead6b5d834d3e0675422475a9aad351e3d` | `ca0898d56eaa45b947b9f513a2bcdbfcb5bc5a0c` |

The after-base must exist as a commit and be an ancestor of the after-head. Immediately before publication, the publisher must independently recapture the live branch and HEAD and require the exact authorized values above. If either differs, this authorization is stale and publication must stop. Managed path membership, count, path-set SHA-256, and content-set SHA-256 must be recomputed after all authorized control files are stable; the sequence 57 values `818`, `a92ca456869315d43939ffd3a46295ca8f405dc7acc3613384d08d70e4868f45`, and `7419a29ef7d1b4e1347c9111dde2b39e7abcdf3e7efb05ad28ee1f39df30242e` are source values, not authorized sequence 58 after-values.

## 6. Exact sequence 58 event contract

The event must use exactly these common fields:

```text
sequence
event_id
event_type
occurred_on
occurred_at
previous_focus_goal_id
previous_focus_content_sha256
focus_goal_id
focus_goal_content_sha256
subject_goal_id
from_status
to_status
static_plan_manifest_sha256
status_changes
runtime_after
blockers_after
blocker_resolution_ids_after
source_checkpoint_version
evidence_refs
previous_event_sha256
event_sha256
```

It must additionally use exactly these control fields:

```text
source_ready_event_binding
contract_supersession
repository_context_reanchor
authorization_binding
independent_review_binding
claim_boundary
unchanged_control_projection
```

`source_ready_event_binding` must bind sequence 57 by sequence, event ID, event SHA-256, Goal ID, and `READY` status. `contract_supersession` must contain `previous_contract_binding`, `replacement_contract_binding`, and `reason_code`, using the exact R001/R002 bindings and reason above. `repository_context_reanchor` must contain `before` and `after`; its `before` member must bind the exact source checkpoint path, physical SHA-256, byte count, and recorded repository context, and its `after` member must bind the exact authorized live branch/base/head plus the publication-time managed snapshot. `unchanged_control_projection` must contain `before` and `after` projections and prove that every non-authorized control value is identical.

The event must bind this authorization and its independent review by project-relative path, exact physical SHA-256, and byte count. It must use `previous_event_sha256 = 08e25cd9808e2301a4af7a3b463d5a1cda795caf41eed57a35de29676f2210ef`. Its event SHA-256 must be mechanically recomputed using the existing transition-event canonicalization rule.

## 7. Allowed checkpoint mutations

Only the following mutations are authorized:

1. Append the one exact sequence 58 event and advance the transition-history anchor and validation cutoff to that event.
2. Replace the live repository branch/base/head projection with the exact authorized after-context and refresh the corresponding session-handoff source snapshot.
3. Recompute the managed working-tree path list, count, path-set SHA-256, and content-set SHA-256 from the stable authorized tree, preserving deterministic sorting, uniqueness, path safety, checkpoint self-exclusion, and handoff parity.
4. Establish the target Goal's active start-gate contract projection as the exact R002 binding above, without changing the embedded R001 binding in sequence 57.
5. Add the exact authorization and independent-review evidence bindings required by sequence 58.
6. Perform only mechanical schema/version projection strictly required to represent those fields.

Any canonical binding, artifact queue, artifact lifecycle, Goal inventory, Goal status, ready-frontier membership, current-work meaning, blocker, pending question, completion boundary, release gate, approval, formal-test state, or product file change is outside this event's allowed checkpoint mutations.

Publication must use compare-and-swap semantics against the exact source checkpoint SHA-256, write a separate candidate, run all applicable strict validators against the candidate, revalidate the live Git and input bindings, and atomically replace the checkpoint only if every check passes. The final checkpoint must remain a regular file with mode `0600` and link count `1`.

## 8. Sequence 59 handoff

Sequence 58 does not start the Goal. A later sequence 59 start attempt must satisfy all of the following:

- `previous_event_sha256` equals the exact sequence 58 event SHA-256.
- The start-gate receipt binds `source_ready_event_sha256` to the sequence 57 SHA-256 above.
- The start-gate receipt binds `source_checkpoint_sha256` to the exact published sequence 58 checkpoint SHA-256.
- The gate executes the exact ordered checks from the R002 contract, with no fallback to R001 or an unbound generic runner.
- Strict continuation, artifact-work-queue, and Goal-graph validation pass against the sequence 58 checkpoint, including the current work-session binding required by the start procedure.
- Product files remain unchanged until the gate has passed and `GOAL_STARTED` has been published.

## 9. Claim boundary

For sequence 58, all of the following deltas are exactly zero:

| Credit or state | Authorized value |
|---|---:|
| Goal status changes | `0` |
| `implementation_start_authorized` | `false` |
| Product implementation credit | `0` |
| Artifact completion credit | `0` |
| Test credit | `0` |
| Formal-test credit | `0` |
| Approval credit | `0` |
| Actual-event credit | `0` |
| External-action credit | `0` |
| Actual-device credit | `0` |
| Deployment credit | `0` |
| Signing credit | `0` |
| Release credit | `0` |
| Final-completion credit | `0` |

Release remains `NOT_ELIGIBLE`. Formal tests remain `279/279 NOT_RUN`. All release gates remain unwaived. This authorization is not evidence that sequence 58 or sequence 59 has executed.

## 10. Fail-closed conditions

Publication is forbidden if any bound source hash, R001 or R002 identity, authorization or review hash, live branch, live HEAD, base ancestry, event field set, previous-event hash, source-ready status, blocker/question count, managed snapshot, allowed-mutation diff, self-seal, strict validator, file mode, or link-count check differs. A failure creates no partial checkpoint update, no Goal transition, and no credit.
