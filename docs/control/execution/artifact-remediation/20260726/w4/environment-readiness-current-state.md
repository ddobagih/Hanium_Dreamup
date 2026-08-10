# W4 시험 환경 준비도 current state

- 문서 ID: `WS-ARTIFACT-REMEDIATION-W4-ENVIRONMENT-READINESS-20260727-002`
- Wave: `W4`
- 상태: `INTERNAL_GAP`
- Markdown content fingerprint: `cddef2cc06e35951fa278b06898af6ffff1ff7f2689fcd2de43ffda097750f7b`
- 승인·formal 실행·외부 action 완료·N/A·release 상태 변경 주장: 없음

## 현재 판정

canonical logical environment는 정확히 6개다. environment instance는 0개이고 formal-ready environment도 0개다. formal test environment ready와 device/field authorization은 모두 `false`, support matrix는 `DRAFT_UNAPPROVED`다.

## Scope boundary

- covered exact13 (13): `DLV-TST-01`, `DLV-TST-02`, `DLV-TST-03`, `DLV-TST-04`, `DLV-TST-05`, `DLV-TST-07`, `DLV-TST-09`, `DLV-TST-11`, `DLV-TST-14`, `DLV-TST-18`, `DLV-TST-19`, `DLV-TST-20`, `DLV-TST-21`
- auxiliary dependencies (8): `DLV-TST-12`, `DLV-TST-13`, `DLV-TST-16`, `DLV-TST-17`, `DLV-TST-22`, `DLV-TST-23`, `DLV-SEC-14`, `DLV-WS-21`
- sets disjoint: `true`
- auxiliary IDs do not expand W4 exact coverage.

## Canonical environment instance contract

모든 logical environment는 독립 `instances` 배열을 가지며 현재 여섯 배열 모두 비어 있다. formal-ready instance는 `0`이다.

| Ordinal | Field group | Type | Required leaf fields |
|---:|---|---|---|
| 1 | `environment_id` | `string` | - |
| 2 | `instance_id` | `string` | - |
| 3 | `owner_role` | `string` | - |
| 4 | `provisioned_at` | `timestamp_with_timezone` | - |
| 5 | `retire_at` | `timestamp_with_timezone` | - |
| 6 | `source_binding` | `object` | `source_commit`, `source_dirty` |
| 7 | `build_binding` | `object` | `build_id`, `artifact_sha256` |
| 8 | `model_config_binding` | `object` | `model_sha256`, `config_sha256` |
| 9 | `interface_data_binding` | `object` | `openapi_sha256`, `db_migration` |
| 10 | `device_network_binding` | `object` | `device_pseudonym`, `network_profile` |
| 11 | `verification_evidence_id` | `string` | - |
| 12 | `approval_status` | `enum` | - |

12개 field-group는 source, build, model, config, OpenAPI, DB, device, network binding을 모두 포함한다. required leaf와 smoke evidence가 있고 `approval_status=APPROVED`일 때만 formal-ready다.

## Logical environments and instance arrays

| Environment | Registry status | Boundary | Current status | Instances | Formal-ready |
|---|---|---|---|---:|---:|
| `ENV-UNIT-ANDROID` | `NOT_PROVISIONED_FOR_FORMAL_RUN` | `INTERNAL` | `INTERNAL_GAP` | 0 | 0 |
| `ENV-UNIT-SERVER` | `NOT_PROVISIONED_FOR_FORMAL_RUN` | `INTERNAL` | `INTERNAL_GAP` | 0 | 0 |
| `ENV-INTEGRATION-POSTGIS` | `NOT_PROVISIONED_FOR_FORMAL_RUN` | `INTERNAL` | `INTERNAL_GAP` | 0 | 0 |
| `ENV-DEVICE-SUPPORTED` | `PENDING_SUPPORTED_DEVICE_MATRIX` | `EXTERNAL` | `EXTERNAL_ACTION_PENDING_AFTER_INTERNAL_READINESS` | 0 | 0 |
| `ENV-FIELD-CONTROLLED` | `BLOCKED_BY_WS_21_AND_RECOVERY_GATE` | `EXTERNAL` | `EXTERNAL_ACTION_PENDING_AFTER_INTERNAL_READINESS` | 0 | 0 |
| `ENV-LEGACY-WEB` | `REFERENCE_ONLY` | `REFERENCE_ONLY` | `REFERENCE_ONLY` | 0 | 0 |

## Formal subject and W3 PostGIS boundary

- `source_commit`, build, artifact, model, config, OpenAPI, DB migration and environment instance formal bindings remain null/`NOT_BOUND`.
- W3 PostGIS fresh migration, schema parity, sequential repeat and cleanup are `PASS_INTERNAL_CANDIDATE_ONLY`.
- formal PostGIS, Android→Gateway→Backend→PostGIS and production are `NOT_RUN`; W3 PASS relabel is prohibited.

## Local internal work items

| ID | Status | Owner input | Evidence | Acceptance |
|---|---|---|---|---|
| `W4-INT-ENV-001` | `INTERNAL_GAP` | immutable subject candidate; approved tool/runtime versions; test-only secret references | three environment instance records; smoke receipts; tool/runtime/network/data boundary bindings | ENV-UNIT-ANDROID, ENV-UNIT-SERVER and ENV-INTEGRATION-POSTGIS each have one unique approved instance with every required field and successful smoke evidence; this does not authorize device or field execution. |
| `W4-INT-BIND-002` | `INTERNAL_GAP` | source commit; build outputs; model; configuration; OpenAPI; migration head | subject manifest; component SHA-256 values; dirty-state declaration; build receipt | Every formal run input resolves to one immutable subject manifest and no required binding is null. |
| `W4-INT-MAP-003` | `INTERNAL_GAP` | canonical 279-case register; current fixture inventory; external dependency classification | exact-279 mapping; duplicate and unassigned checks; fixture or external-action reference per case | The exact 279 case IDs are unique and each has an explicit fixture mapping or an explicit external action; mapping alone does not change NOT_RUN. |
| `W4-INT-POSTGIS-004` | `INTERNAL_GAP` | formal subject candidate; test-only PostGIS instance; local Gateway and Backend process contract | process identities; request and response trace; database assertions; migration and cleanup receipts; defect and retest linkage | Actual cross-process traffic reaches the isolated PostGIS database and assertions pass on the same bound candidate. W3 database-only PASS is not sufficient and production is not claimed. |
| `W4-INT-EVIDENCE-005` | `INTERNAL_GAP` | approved evidence schema; encrypted raw-evidence store; retention and access rules | new-run path allocation; raw-evidence control reference; identity pseudonym rules; result and defect linkage checks | A new run can be recorded without overwriting a predecessor and without storing secrets, exact location, raw audio/video or real device identifiers in Git. |
| `W4-INT-A11Y-006` | `INTERNAL_GAP` | one immutable current-source binding for both Android apps; explicit accessibility assertion selectors for the user app and administrator app; fixed JDK, Gradle and Android plugin versions | user-app accessibility suite command, raw log, exit code and SHA-256; administrator-app accessibility suite command, raw log, exit code and SHA-256; source/content-set binding for both suites; assertion-to-TST-14 candidate mapping with formal contribution fixed at zero | Both accessibility-specific suites pass against the same current-source binding. Generic JVM suite totals alone are insufficient, and the automated PASS remains an internal candidate until physical TalkBack and human QA also close. |

## TST-14 dual closure

- operator: `AND`
- automated current-source axis: required `PASS_INTERNAL_CURRENT_SOURCE`, current `NOT_RUN_AS_ACCESSIBILITY_SPECIFIC_SUITE`
- generic candidates remain Android user 728 and Android admin 38 `PASS_INTERNAL`; accessibility-specific PASS is not inferred and formal contribution is 0.
- physical/human axis: required `PASS_PHYSICAL_TALKBACK_AND_HUMAN_QA`, current `NOT_RUN`.
- overall: `OPEN_BOTH_AXES_REQUIRED`.

## WAITING_EXTERNAL 전 내부 선행조건

현재 충족은 `0/7`, 상태는 `NOT_READY_TO_TRANSITION_TO_WAITING_EXTERNAL`다.

| ID | Status | Rule |
|---|---|---|
| `W4-INT-PRE-01` | `NOT_SATISFIED` | Exact 279 case-to-fixture or external-action mapping has zero duplicates and zero unassigned cases. |
| `W4-INT-PRE-02` | `NOT_SATISFIED` | The immutable subject has non-null source, build, artifact, model, config, OpenAPI and DB bindings as applicable. |
| `W4-INT-PRE-03` | `NOT_SATISFIED` | All local formal environment instances have required fields and successful smoke evidence. |
| `W4-INT-PRE-04` | `NOT_SATISFIED` | Controlled test-data IDs and sensitive-evidence handling are assigned to the cases selected for execution. |
| `W4-INT-PRE-05` | `NOT_SATISFIED` | Append-only run paths, raw-evidence control references, executor/reviewer pseudonyms and defect linkage are ready. |
| `W4-INT-PRE-06` | `NOT_SATISFIED` | Each requested external action has a bounded owner request, procedure, stop rule and evidence checklist; the approval candidate is prepared without claiming approval. |
| `W4-INT-PRE-07` | `NOT_SATISFIED` | Accessibility-specific automated suites for both Android apps PASS on the same current-source binding with raw receipts and explicit TST-14 candidate mapping. |

외부 action은 모든 내부 선행조건과 bounded owner request 전까지 `EXTERNAL_ACTION_PENDING_AFTER_INTERNAL_READINESS`이며 evidence와 권한 있는 결정 전 PASS/CLOSED/OK/N/A 전환을 금지한다.

## Heavy lane and logout guard

1. freeze subject and complete light readiness checks
2. Android user/admin Gradle build
3. Gateway/Node build after Gradle exits
4. local unit, contract and regression execution
5. isolated PostGIS cross-process execution and cleanup
6. physical Android baseline and compatibility
7. TalkBack and accessibility on the bound physical-device build
8. live TMAP and controlled network transitions
9. device latency, battery, thermal and queue measurement
10. staging load, capacity-state and cloud-cost measurement
11. security verification
12. formal aggregation and QA review

- heavy lane limit: `1`
- Do not overlap Android Gradle, Node build, PostGIS, device measurement or load testing.
- During a full gate, pause source writes and every other build or test.
- Do not start a new heavy step in Red memory state; in Amber allow one heavy process and at most three active light tools.
- Stop assigning new packets before logout or shutdown.
- Finish the active heavy step or preserve it as an incomplete attempt without a PASS receipt.
- Stop local processes, clean the single-use PostGIS environment and remove temporary secret files when safe.
- Record the exact active step, subject digest, output paths and next action in the handoff owned by the coordinator.
- After restart, treat receipt-less RUNNING output as ABANDONED_BY_REBOOT and use a new attempt ID.
- Never reuse a receipt-less PASS or an old environment instance as current formal evidence.

## Immutable claim boundary

- `formal_test_planned_count`: `279`
- `formal_test_execution_status`: `NOT_RUN`
- `formal_test_pass_count`: `0`
- `formal_test_pass_claimed`: `false`
- `release_gate_count`: `5`
- `release_gate_status`: `NOT_RUN`
- `release_gates_waived`: `false`
- `actual_device_status`: `NOT_RUN`
- `talkback_accessibility_status`: `NOT_RUN`
- `tmap_live_network_status`: `NOT_RUN`
- `device_performance_status`: `NOT_RUN`
- `staging_load_status`: `NOT_RUN`
- `security_external_verification_status`: `NOT_RUN_OR_NOT_ASSESSED`
- `qa_approval_status`: `NOT_APPROVED`
- `release_status`: `NOT_ELIGIBLE`

## Exact predecessor bindings

| ID | Path | file SHA-256 | content fingerprint |
|---|---|---|---|
| `PRED-W4-TESTING-CURRENT` | `docs/control/execution/artifact-remediation/20260726/w4/testing-current-state.json` | `2853458b3d6ee14ae34a2d1973068101ab2c3eae0dd3502a25f2dcf6e06e00d2` | `bb3eeb5f914a3a69685b8a2dfd003595d81b1f1a55807c486ecdf9edc36a6151` |
| `PRED-W4-TEST-TRACE` | `docs/control/execution/artifact-remediation/20260726/w4/test-trace-current-state.json` | `d46a9ea3ecc5334c15531947733a520469b5cf712a0eed216d53413a18187893` | `a1c1aa13fc63e63bd6ed9470f0080aaca6ec94dda7db6aadb6c46bad84188f86` |
| `PRED-W3-INTEGRATION` | `docs/control/execution/artifact-remediation/20260726/w3/integration-run-20260726-004/integration-current-state.json` | `dca362040f241734201d00d94b912ec0b901ec444e506cf7a0b731e71f3d6a2c` | `101e565547945a56c938b9f27b71beff4dac450e0352db978bf0a147a428bbe1` |
| `PRED-CANONICAL-ENVIRONMENTS` | `docs/deliverables/06-testing/registers/environments.json` | `8d01ad85534e5f740ce5d89ecfadbc93a8bb58c13e3a06f15a4ed5e9deb51dc7` | `6a8c36648c01745fb0a29562fd7941ec5de5b6453f301edf587ba262aa893d3f` |

Binding direction is predecessor or Markdown projection to JSON. No file embeds its own raw SHA-256, so the graph is acyclic.

## Independent-review finding closure matrix

| Finding | Requirement | Closure | Status | Execution/approval claimed |
|---|---|---|---|---|
| `W4-REVIEW-FINDING-01` | TST-02 governance approval and TST-03 environment/device-matrix approval actions | W4-EXT-TST02-APPROVAL-000 and W4-EXT-TST03-APPROVAL-000 define owner input, ordered procedure, stop conditions, evidence and acceptance. | `CLOSED_BY_THIS_REVISION` | `false` |
| `W4-REVIEW-FINDING-02` | TST-09 one-session full physical Android/live TMAP/PostGIS E2E | W4-EXT-TMAP-003 now requires one run/session and one source/build/model/config/OpenAPI/DB binding through Android user to Gateway to Backend to PostGIS. | `CLOSED_BY_THIS_REVISION` | `false` |
| `W4-REVIEW-FINDING-03` | TST-14 dual automated and physical/human closure | W4-INT-A11Y-006 and W4-EXT-A11Y-002 are joined by an AND closure; generic 728/38 suites are not inferred as accessibility-specific PASS. | `CLOSED_BY_THIS_REVISION` | `false` |
| `W4-REVIEW-FINDING-04` | Canonical environment instance 12-field-group model with per-environment arrays | All six logical environments carry empty instances arrays and the canonical contract covers source/build/model/config/OpenAPI/DB/device/network bindings. | `CLOSED_BY_THIS_REVISION` | `false` |
| `W4-REVIEW-FINDING-05` | Separate covered exact13 from auxiliary dependencies | scope_boundary records exact13 and a disjoint auxiliary ID set that cannot expand W4 coverage. | `CLOSED_BY_THIS_REVISION` | `false` |
| `W4-REVIEW-FINDING-06` | Every external action has owner_role, ordered procedure, stop conditions, evidence and acceptance | The external packet validator requires all five fields on every action and rejects empty lists or rules. | `CLOSED_BY_THIS_REVISION` | `false` |
| `W4-REVIEW-FINDING-07` | Exact predecessor and JSON/Markdown pair binding with an acyclic DAG | Each JSON binds exact predecessor and Markdown file SHA-256/content fingerprints in one direction; downstream receipts bind JSON raw SHA-256, avoiding self-hash cycles. | `CLOSED_BY_THIS_REVISION` | `false` |

## Pair-binding DAG

- graph: `W4-ENV-BINDING-DAG-20260727-002`
- direction: external predecessors and this Markdown projection to environment JSON
- cycle count: `0`
- downstream external packet or central receipt binds the environment JSON raw SHA-256.
