# W4 외부 action packet

- 문서 ID: `WS-ARTIFACT-REMEDIATION-W4-EXTERNAL-ACTION-PACKET-20260727-002`
- Wave: `W4`
- 상태: `EXTERNAL_ACTION_PENDING_AFTER_INTERNAL_READINESS`
- Markdown content fingerprint: `fb17874ba8ff9242c972b5a3be88926b8fbf1cd6c6e7c41cd187b1cdbf12c1e6`

이 문서는 actionable handoff 계약이다. 내부 준비, owner 입력, 실행 evidence, 권한 있는 reviewer 결정 전까지 모든 action은 pending이며 artifact/test/gate/release 상태를 변경하지 않는다.

## Scope boundary

- covered exact13 (13): `DLV-TST-01`, `DLV-TST-02`, `DLV-TST-03`, `DLV-TST-04`, `DLV-TST-05`, `DLV-TST-07`, `DLV-TST-09`, `DLV-TST-11`, `DLV-TST-14`, `DLV-TST-18`, `DLV-TST-19`, `DLV-TST-20`, `DLV-TST-21`
- auxiliary dependencies (8): `DLV-TST-12`, `DLV-TST-13`, `DLV-TST-16`, `DLV-TST-17`, `DLV-TST-22`, `DLV-TST-23`, `DLV-SEC-14`, `DLV-WS-21`
- sets disjoint: `true`
- auxiliary IDs do not expand W4 exact coverage.

## Internal readiness gate

- status: `NOT_READY`
- satisfied: `0/7`
- required IDs: `W4-INT-PRE-01`, `W4-INT-PRE-02`, `W4-INT-PRE-03`, `W4-INT-PRE-04`, `W4-INT-PRE-05`, `W4-INT-PRE-06`, `W4-INT-PRE-07`
- handoff rule: Do not transition an action to WAITING_EXTERNAL until all seven internal preconditions pass and a bounded request is issued to the owner.
- local PostGIS W3 PASS remains an internal candidate and is not formal/cross-process/production completion.

## TST-14 dual closure

- operator: `AND`
- automated axis: both Android apps require accessibility-specific current-source PASS; current overall automated status is not yet satisfied.
- physical/human axis: physical TalkBack matrix and authorized human accessibility QA are required and `NOT_RUN`.
- generic user 728/admin 38 internal suite PASS cannot be inferred as accessibility-specific or formal PASS.

## External actions (11)

### `W4-EXT-TST02-APPROVAL-000` Authorized test schedule, entry/exit and suspend/resume approval

- sequence/resource: `5` / `LIGHT_HUMAN_APPROVAL`
- status: `EXTERNAL_ACTION_PENDING_AFTER_INTERNAL_READINESS`
- owner_role: `QA test authority`
- supporting roles: `Product owner`, `Technical owner`, `Safety owner when a field scenario is included`
- related IDs: `DLV-TST-02`, `DLV-TST-20`, `DLV-TST-21`
- owner input: versioned candidate test schedule and execution windows; entry and exit criteria with evidence references; suspend, abort, resume and restart criteria; named decision authority and escalation route; immutable subject and environment-readiness candidate

**Ordered procedure**

1. Review the immutable subject, case scope, environment readiness and unresolved dependencies.
2. Approve or reject the versioned schedule and bounded execution windows.
3. Approve entry and exit criteria for each execution tier.
4. Approve suspend, abort, resume and restart triggers and identify the decision authority.
5. Issue a signed, timestamped approval receipt bound to the schedule and criteria hashes.

**Stop conditions**

- Source, build, model, config, OpenAPI, DB or case scope changes after review.
- A required environment or controlled test-data assignment is not ready.
- Suspend/resume authority or escalation ownership is ambiguous.
- A safety, privacy, credential or evidence-retention prerequisite is missing.

- evidence required: signed schedule approval receipt; schedule file SHA-256 and execution-window version; entry/exit criteria matrix; suspend/abort/resume authority matrix and decision-log template; approver identity, role and timestamp
- acceptance rule: The authorized QA test authority signs the exact schedule, entry/exit criteria and suspend/resume contract for the bound candidate. No formal execution may start under this action before that receipt exists.
- forbidden substitutes: AI approval; unsigned meeting note; calendar entry without criteria; execution before approval
- gate contribution: -

### `W4-EXT-TST03-APPROVAL-000` Authorized logical-environment and supported-device-matrix approval

- sequence/resource: `8` / `LIGHT_HUMAN_APPROVAL`
- status: `EXTERNAL_ACTION_PENDING_AFTER_INTERNAL_READINESS`
- owner_role: `QA environment authority`
- supporting roles: `Android device owner`, `Backend owner`, `DB owner`, `Security and privacy owner`
- related IDs: `DLV-TST-03`, `DLV-TST-20`, `DLV-TST-21`
- owner input: canonical six logical environments and their instance records; all 12 instance field groups and smoke evidence; supported manufacturer/model/Android/API/ARCore/depth matrix; network, test-data, secret-reference and retirement boundaries; reference-only exclusion for ENV-LEGACY-WEB

**Ordered procedure**

1. Verify that the canonical logical-environment set is exactly six and uniquely identified.
2. Review each local instance for all 12 field groups, smoke evidence and lifecycle dates.
3. Review each supported-device matrix cell and its Android/API/capability/network requirements.
4. Confirm ENV-LEGACY-WEB is reference-only and cannot substitute for Android evidence.
5. Approve, reject or return each instance and matrix revision with a signed decision receipt.

**Stop conditions**

- Any logical environment is missing, duplicated or silently added.
- An instance has a null required binding, missing smoke evidence or expired lifecycle.
- A supported-device cell lacks an owner, device source or capability criterion.
- The matrix or environment facts drift after review.

- evidence required: signed logical-environment approval receipt; per-instance approval decisions and smoke evidence references; supported-device matrix revision and SHA-256; rejected or deferred cell list with owner and next evidence; approver identity, role and timestamp
- acceptance rule: The authorized environment authority approves the exact six-environment model, every formal-ready local instance and every required supported-device matrix cell. Unapproved or unavailable cells remain pending and are never automatic N/A.
- forbidden substitutes: environment document presence; device inventory without approval; emulator-only matrix; automatic N/A
- gate contribution: -

### `W4-EXT-DEVICE-001` Physical Android baseline and compatibility execution

- sequence/resource: `10` / `HEAVY_DEVICE`
- status: `EXTERNAL_ACTION_PENDING_AFTER_INTERNAL_READINESS`
- owner_role: `Android device owner`
- supporting roles: `QA owner`
- related IDs: `DLV-TST-03`, `DLV-TST-07`, `DLV-TST-09`, `DLV-TST-11`, `DLV-TST-14`, `DLV-TST-20`, `DLV-TST-21`
- owner input: physical user and administrator Android devices; authorized supported-device matrix cells; bound APK, model and configuration hashes; manufacturer/model, Android/API, ARCore/depth and network profile; device pseudonyms rather than serials

**Ordered procedure**

1. Verify the approved supported-device matrix and bound APK/model/config hashes.
2. Install and launch user and administrator apps on each required physical matrix cell.
3. Capture manufacturer/model, Android/API, capability, network and pseudonym bindings.
4. Execute assigned functional and compatibility steps without changing the subject.
5. Record controlled evidence, defects and same-cell retests.

**Stop conditions**

- A matrix cell, device capability or subject hash is unapproved or mismatched.
- Install/launch fails before evidence correlation is established.
- Device identity would expose a real serial or uncontrolled personal identifier.
- A safety-critical or data-integrity failure requires suspension.

- evidence required: install and launch receipt per matrix cell; device capability and environment record; functional and compatibility step results; controlled screen/log/video evidence IDs and SHA-256; defect and retest linkage
- acceptance rule: Every required support-matrix cell executes the assigned cases on the bound physical-device build with no unexplained mismatch; missing cells remain NOT_RUN or BLOCKED and do not become N/A automatically.
- forbidden substitutes: emulator-only result; legacy Web/PWA result; unbound debug result; device inventory without execution
- gate contribution: -

### `W4-EXT-A11Y-002` TalkBack and technical accessibility execution

- sequence/resource: `20` / `HEAVY_DEVICE`
- status: `EXTERNAL_ACTION_PENDING_AFTER_INTERNAL_READINESS`
- owner_role: `Accessibility QA authority`
- supporting roles: `Android user-app owner`, `Android admin-app owner`, `Physical-device owner`
- related IDs: `DLV-TST-14`, `DLV-TST-20`, `DLV-TST-21`
- owner input: PASS receipts for accessibility-specific automated suites of both Android apps on the same current-source binding; bound physical device and APKs; TalkBack version and settings; locale, font scale, display size and orientation matrix; approved accessibility procedure and human reviewer

**Ordered procedure**

1. Verify both Android apps have accessibility-specific automated-suite PASS receipts on the same current-source binding.
2. Verify the exact accessibility assertions selected for TST-14 and keep their formal contribution at zero.
3. Install the bound app build on the approved physical device and record TalkBack/display settings.
4. Execute node-tree, focus order, focus recovery, announcement, reflow, touch-target and alternative-feedback steps.
5. Have the authorized human accessibility QA reviewer compare observations with acceptance criteria.
6. Record defects and retest both the affected automated axis and physical TalkBack axis when implementation changes.

**Stop conditions**

- Either Android app lacks an accessibility-specific automated-suite PASS on the current source.
- The physical APK or device binding differs from the approved subject or matrix.
- TalkBack is disabled, changes version/settings mid-run, or focus/announcement capture is unavailable.
- A critical focus trap, missing safety announcement or inaccessible recovery path is observed.
- Human reviewer identity or controlled raw-evidence handling is missing.

- evidence required: user-app accessibility automated-suite PASS receipt and source binding; administrator-app accessibility automated-suite PASS receipt and source binding; accessibility assertion-to-TST-14 candidate mapping with formal contribution zero; physical-device TalkBack node tree, focus and announcement trace; large-text reflow, touch-target and non-color cue observations; authorized human accessibility QA receipt and defect/retest links
- acceptance rule: Closure uses AND: both current-source Android accessibility-specific automated suites PASS, and the approved physical-device TalkBack matrix passes with an authorized human accessibility QA decision. Neither axis can substitute for the other.
- forbidden substitutes: static semantics review only; emulator-only TalkBack; screenshot without focus or announcement evidence; document approval without execution
- gate contribution: -

### `W4-EXT-TMAP-003` Single-session physical Android user to Gateway to Backend to PostGIS E2E with live TMAP

- sequence/resource: `30` / `HEAVY_DEVICE_NETWORK`
- status: `EXTERNAL_ACTION_PENDING_AFTER_INTERNAL_READINESS`
- owner_role: `QA integration authority`
- supporting roles: `Android device owner`, `Gateway owner`, `Backend owner`, `DB owner`, `TMAP credential owner`
- related IDs: `DLV-TST-07`, `DLV-TST-09`, `DLV-TST-11`, `DLV-TST-20`, `DLV-TST-21`
- owner input: one immutable manifest containing source commit, build/artifact, model, config, OpenAPI and DB migration bindings; one approved supported physical Android user device and environment instance; running Gateway, Backend and isolated PostGIS process identities; approved TMAP account, TMAP_APP_KEY secret-store reference, quota and live endpoints; controlled route/POI inputs and Wi-Fi/mobile/offline recovery profile; one allocated formal run_id and one correlated application session_id

**Ordered procedure**

1. Verify every source/build/model/config/OpenAPI/DB hash and the approved environment instance before launch.
2. Install the bound Android user APK on the supported physical device and allocate one run_id and one session_id.
3. Start Gateway, Backend and isolated PostGIS, capture process identities and confirm migration head.
4. From the Android user app, perform the assigned login/consent/navigation preconditions within that session.
5. Execute live TMAP destination search and pedestrian routing through actual network transport.
6. Send the correlated Android request through Gateway and Backend and assert the expected PostGIS state in the same run.
7. Execute assigned network transition, timeout and recovery branches without changing the bound subject.
8. Stop processes safely, collect defects, and hash every device/network/process/DB evidence object under the same run/session.

**Stop conditions**

- Any source/build/model/config/OpenAPI/DB hash differs from the frozen manifest.
- The physical device or environment is outside the approved matrix.
- TMAP credential, quota, live endpoint or outbound network is unavailable.
- Gateway, Backend, migration head or PostGIS health cannot be established.
- run_id/session_id correlation is lost or evidence would require stitching another run.
- A safety, privacy, raw-data or secret-handling boundary is violated.

- evidence required: single run_id and session_id correlation manifest; source/build/artifact/model/config/OpenAPI/DB binding manifest; physical device and environment-instance approval references; sanitized live TMAP request/response and timing receipts; Android to Gateway to Backend request/response trace; PostGIS migration-head and database assertion receipts; network transition, timeout, recovery and stop records; secret non-disclosure check and defect/retest linkage
- acceptance rule: One uninterrupted or formally resumed run/session on one supported physical Android user device proves live TMAP and the Android user to Gateway to Backend to PostGIS path under the same source/build/model/config/OpenAPI/DB bindings. Results from different runs, subjects or devices may not be stitched into PASS.
- forbidden substitutes: mock or stub TMAP; loopback-only client; database-only W3 PASS; multiple runs stitched together; legacy Web/PWA
- gate contribution: -

### `W4-EXT-DEVICE-PERF-004` Supported-device performance, battery, thermal and queue measurement

- sequence/resource: `40` / `HEAVY_DEVICE_MEASUREMENT`
- status: `EXTERNAL_ACTION_PENDING_AFTER_INTERNAL_READINESS`
- owner_role: `Android performance owner`
- supporting roles: `QA owner`, `Product owner`
- related IDs: `DLV-TST-12`, `DLV-TST-13`, `DLV-TST-20`, `DLV-TST-21`
- owner input: supported physical-device matrix; approved workload duration and measurement method; battery baseline and charging rule; ambient and starting thermal conditions; controlled queue payload and storage-space profiles

**Ordered procedure**

1. Approve the physical-device cells, workload, duration and measurement instruments.
2. Record battery, charging, ambient, thermal, storage and queue baselines.
3. Run the bound workload without another heavy process.
4. Collect latency, battery, thermal, CPU, memory, storage and queue measurements.
5. Repeat required samples and submit each provisional budget for approval or revision.

**Stop conditions**

- Device, workload or instrument differs from the approved method.
- Charging, thermal or ambient state invalidates comparison.
- Thermal or storage safety threshold is exceeded.
- Another heavy workload starts or measurement evidence is incomplete.

- evidence required: latency distribution and sample count; battery delta and duration; thermal state and throttling trace; CPU, memory and transient-storage measurements; queue byte count, item count and full-storage behavior; separate budget approval or revision receipt
- acceptance rule: Every supported-device measurement is reproducible on the bound candidate and the authorized owners separately approve or revise each provisional budget; measurements feed but do not automatically close the phone-queue gate.
- forbidden substitutes: desktop benchmark; emulator timing; single undocumented observation; provisional budget treated as approved
- gate contribution: `GATE-PHONE-QUEUE-BYTE-LIMIT`

### `W4-EXT-STAGING-005` Staging load, capacity-state and cloud-cost measurement

- sequence/resource: `50` / `HEAVY_STAGING_LOAD`
- status: `EXTERNAL_ACTION_PENDING_AFTER_INTERNAL_READINESS`
- owner_role: `Operations owner`
- supporting roles: `Backend owner`, `QA owner`, `Cloud billing owner`
- related IDs: `DLV-TST-12`, `DLV-TST-20`, `DLV-TST-21`
- owner input: approved staging account and topology; bounded load profile and stop thresholds; test-only identities and data; capacity-state configuration and observation window; cloud storage/request/restore billing access

**Ordered procedure**

1. Approve staging topology, identities, data, load profile and stop thresholds.
2. Record baseline capacity-state, resource and billing conditions.
3. Run the bounded load profile with one heavy lane.
4. Observe saturation, recovery, client capacity-state propagation and data integrity.
5. Export billing evidence and obtain separate budget and gate decisions.

**Stop conditions**

- Target is production or topology is outside approved staging.
- Error, saturation, data-integrity or cost stop threshold is exceeded.
- Client capacity-state correlation or billing attribution is lost.
- Another heavy load or security execution starts.

- evidence required: throughput, latency and error distribution; CPU, memory, connection and storage utilization; capacity-state version, observation time, propagation latency and offline TTL behavior; saturation, recovery and data-integrity trace; billing export for storage, requests and restore; authorized budget and gate decisions
- acceptance rule: The declared staging shape sustains the approved profile within separately approved limits, capacity state reaches the client under the approved contract, and actual cost evidence supports an authorized decision for both affected gates.
- forbidden substitutes: localhost-only benchmark; estimated cost without billing evidence; production load without approval; load run concurrent with another heavy lane
- gate contribution: `GATE-SERVER-CAPACITY-STATE-CONTRACT`, `GATE-CLOUD-COST-MEASUREMENT`

### `W4-EXT-RAW-REVIEW-006` Independent raw-collection release review

- sequence/resource: `60` / `LIGHT_HUMAN_REVIEW`
- status: `EXTERNAL_ACTION_PENDING_AFTER_INTERNAL_READINESS`
- owner_role: `Independent privacy reviewer`
- supporting roles: `Privacy owner`, `Product owner`
- related IDs: `DLV-TST-21`, `DLV-TST-22`
- owner input: current raw-collection scope; notice, consent and rights procedures; retention, access and deletion controls; release candidate behavior and evidence references

**Ordered procedure**

1. Freeze the actual raw-collection behavior and notice/consent/rights evidence.
2. Provide the controlled packet to an independent privacy reviewer.
3. Record findings and required product or procedure changes.
4. Re-review changed scope and obtain product reapproval when required.
5. Issue an authorized gate decision linked to the review record.

**Stop conditions**

- Reviewer independence or scope is not established.
- Actual release behavior differs from the review subject.
- Required notice, consent, rights, retention or deletion evidence is missing.
- A required change has not been implemented and re-reviewed.

- evidence required: independent review record; finding and required-change list; product reapproval when scope or procedure changes; authorized gate decision
- acceptance rule: An independent reviewer evaluates the actual release behavior and the authorized owners record closure or required changes; a template or self-review does not close the gate.
- forbidden substitutes: AI approval; document presence; developer self-review
- gate contribution: `GATE-RAW-COLLECTION-RELEASE-REVIEW`

### `W4-EXT-ADMIN-RECOVERY-007` Single-administrator lost-device recovery drill

- sequence/resource: `70` / `MODERATE_DEVICE_DRILL`
- status: `EXTERNAL_ACTION_PENDING_AFTER_INTERNAL_READINESS`
- owner_role: `Administrator owner`
- supporting roles: `Security owner`, `Operations owner`, `QA owner`
- related IDs: `DLV-TST-16`, `DLV-TST-17`, `DLV-TST-21`, `DLV-TST-22`
- owner input: authorized administrator test identity; administrator device and independent recovery means; lost-device scenario and stop rules; session revocation and high-risk freeze procedure

**Ordered procedure**

1. Approve the administrator test identity, device, recovery means and stop rules.
2. Establish the pre-loss session and high-risk-operation baseline.
3. Declare the device lost and execute recovery without using the lost device.
4. Verify old-session revocation, high-risk freeze and restored access.
5. Review audit evidence, defects and the authorized gate decision.

**Stop conditions**

- A real production identity or uncontrolled secret would be used.
- Independent recovery means or audit capture is unavailable.
- Old sessions remain active or high-risk operations bypass the freeze.
- The scenario affects non-test users or data.

- evidence required: recovery timeline; old-device session revocation proof; high-risk operation freeze and recovery assertions; audit record; defect/retest and authorized gate decision
- acceptance rule: The drill demonstrates recovery without the lost device, revokes its sessions, preserves the high-risk freeze contract and produces an authorized gate decision.
- forbidden substitutes: tabletop document only; unit test only; recovery code existence without drill
- gate contribution: `GATE-SINGLE-ADMIN-RECOVERY-DRILL`

### `W4-EXT-SECURITY-008` Authorized security verification and active-scope penetration test

- sequence/resource: `80` / `HEAVY_SECURITY`
- status: `EXTERNAL_ACTION_PENDING_AFTER_INTERNAL_READINESS`
- owner_role: `Security owner`
- supporting roles: `Independent security tester`, `Operations owner`, `QA owner`
- related IDs: `DLV-SEC-14`, `DLV-TST-20`, `DLV-TST-21`, `DLV-TST-22`
- owner input: authorized SEC-14 applicability decision; approved scope and rules of engagement; isolated or staging target; test credentials and secret-store references; stop, notification and cleanup contacts

**Ordered procedure**

1. Obtain the authorized SEC-14 applicability decision and signed rules of engagement.
2. Verify isolated/staging scope, credentials, contacts and cleanup plan.
3. Execute approved automated and manual assessment activities.
4. Triage findings against the bound candidate and remediate release blockers.
5. Retest and record residual-risk acceptance or signed N/A by authorized owners.

**Stop conditions**

- Scope, target, tester authorization or rules of engagement is missing.
- A production-impact, data-loss or uncontrolled-secret risk appears.
- A critical finding requires immediate containment.
- The candidate changes before findings and retest are bound.

- evidence required: signed scope and tester identity; tool and manual-test manifest; finding severity and affected subject; remediation and retest evidence; residual-risk acceptance or signed N/A basis by authorized owners
- acceptance rule: If SEC-14 is active, the approved independent assessment is bound to the candidate and every release-blocking finding is remediated or formally accepted; if not applicable, an authorized signed N/A decision is required.
- forbidden substitutes: unit/auth regression alone; unapproved scan; developer self-approval; automatic N/A
- gate contribution: -

### `W4-EXT-QA-009` Formal execution aggregation and QA approval

- sequence/resource: `90` / `LIGHT_HUMAN_APPROVAL`
- status: `EXTERNAL_ACTION_PENDING_AFTER_INTERNAL_READINESS`
- owner_role: `QA owner`
- supporting roles: `Technical owner`, `Security and privacy owners`, `Product owner`
- related IDs: `DLV-TST-18`, `DLV-TST-19`, `DLV-TST-20`, `DLV-TST-21`, `DLV-TST-22`, `DLV-TST-23`
- owner input: approved test plan and environment instances; immutable release-generation manifest; append-only formal execution records for the exact 279-case register; defect, metric, residual-risk and five-gate snapshots; external action results and applicability decisions

**Ordered procedure**

1. Freeze the approved plan, environment instances and immutable release-generation manifest.
2. Reconcile the exact 279 IDs with append-only execution and applicability records.
3. Reconcile defects, metrics, residual risks, external actions and five gates.
4. Generate TST-20 and TST-21 revisions from the same evidence cutoff.
5. Have authorized QA and required reviewers record an explicit decision and timestamp.

**Stop conditions**

- Any exact case is missing, duplicated or bound to another subject.
- A mandatory case has unexplained NOT_RUN/BLOCKED or an unlinked failure.
- Evidence cutoff, defect snapshot, risk snapshot or gate state is inconsistent.
- An external approval or reviewer identity is missing.

- evidence required: QA review receipt; exact-279 execution and applicability reconciliation; TST-20 result report; TST-21 open defect and residual-risk snapshot; explicit reviewer identities, decision and timestamp
- acceptance rule: Every exact case has an authorized applicability and truthful append-only disposition, mandatory release cases have no unexplained NOT_RUN or BLOCKED result, failures link defects and risks, and QA records an explicit decision. This action supplies inputs but does not itself sign TST-22 or TST-23.
- forbidden substitutes: AI approval; generated report without run evidence; sum of historical internal suites; multiple unbound builds merged into one PASS
- gate contribution: -

## Conditional field extension

- status: `EXTERNAL_ACTION_PENDING_AFTER_INTERNAL_READINESS`
- activation: Any controlled field or target-user scenario additionally requires WS-21 safety approval, participant consent, safety staff, stop/emergency rules and controlled raw-evidence handling before execution.
- automatic activation/N/A: `false`

## Heavy lane and logout guard

- heavy lane limit: `1`
- heavy order: `W4-EXT-DEVICE-001` -> `W4-EXT-A11Y-002` -> `W4-EXT-TMAP-003` -> `W4-EXT-DEVICE-PERF-004` -> `W4-EXT-STAGING-005` -> `W4-EXT-ADMIN-RECOVERY-007` -> `W4-EXT-SECURITY-008`
- light human actions: `W4-EXT-TST02-APPROVAL-000`, `W4-EXT-TST03-APPROVAL-000`, `W4-EXT-RAW-REVIEW-006`, `W4-EXT-QA-009`
- Run only one heavy action or local heavy step at a time.
- Do not overlap Android Gradle, Node build, PostGIS, physical-device measurement, staging load or security execution.
- Complete TST-02 schedule approval and TST-03 environment approval before any formal heavy execution.
- Complete physical functional baseline before the TST-14 physical TalkBack axis, TST-09 live full E2E and device performance.
- Run QA aggregation only after all available action evidence and unresolved blockers are frozen.
- Stop new action assignment before logout or shutdown.
- Finish the current heavy action or retain it as an incomplete attempt without PASS.
- Revoke temporary credentials, stop test traffic and clean controlled local resources when safe.
- Record subject digest, active action, external owner, output controls and next step in the coordinator handoff.
- After restart, mark receipt-less RUNNING work ABANDONED_BY_REBOOT and allocate a new attempt ID.
- Do not convert partial device, network, load or security output into PASS.

## Immutable claim boundary

- `formal_test_planned_count`: `279`
- `formal_test_execution_status`: `NOT_RUN`
- `formal_test_pass_count`: `0`
- `formal_test_pass_claimed`: `false`
- `release_gate_count`: `5`
- `release_gate_status`: `NOT_RUN`
- `release_gates_waived`: `false`
- `external_action_completed_count`: `0`
- `qa_approval_status`: `NOT_APPROVED`
- `tst22_status`: `NOT_ELIGIBLE`
- `tst23_status`: `NOT_READY_FOR_ACCEPTANCE`
- `release_status`: `NOT_ELIGIBLE`

## Exact predecessor bindings

| ID | Path | file SHA-256 | content fingerprint |
|---|---|---|---|
| `PRED-W4-ENV-JSON` | `docs/control/execution/artifact-remediation/20260726/w4/environment-readiness-current-state.json` | `f5a3dbe9b9d38e4ec49009d4ac123ab905ea857fd37f6c250d030f8bdcfae6fc` | `f64618bd4900e7224bec36604828a9f4c176730f759ef9eab355cc17f4c8f945` |
| `PRED-W4-ENV-MD` | `docs/control/execution/artifact-remediation/20260726/w4/environment-readiness-current-state.md` | `ed6279cdd4adc73af38904b20bb869e33e73af712717dc800e9e2180d1f45e01` | `cddef2cc06e35951fa278b06898af6ffff1ff7f2689fcd2de43ffda097750f7b` |

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

- direction: environment JSON/Markdown and this Markdown projection to external-action JSON
- cycle count: `0`
- the central W4 implementation receipt binds the external-action JSON raw SHA-256.
