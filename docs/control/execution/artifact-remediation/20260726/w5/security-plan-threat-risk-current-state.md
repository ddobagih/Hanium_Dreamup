# W5 보안 계획·위협·위험 current state

- 문서 ID: `WS-W5-SECURITY-PLAN-THREAT-RISK-CURRENT-STATE-20260727-001`
- 상태: `CURRENT_GOVERNANCE_BASELINE_PENDING_INDEPENDENT_REVIEW_AND_SEC01_RECEIPTS`
- exact artifact: `DLV-SEC-01, DLV-SEC-02, DLV-SEC-03`
- JSON content fingerprint: `3f0b7222ea617bc76944cfd139e619f6bd94bd98a95651045529fcb4e267855f`
- semantic parity fingerprint: `381faa9d084a184f97d9d5d587692935eaed8bf87911334f1bfd6637bb666fdf`

> 이 문서는 동일 in-memory JSON 객체에서 생성된 사람이 읽는 투영입니다. source commit, release build, formal/device/deploy/signing/release 완료를 주장하지 않습니다.

## 판정

| Artifact | Audit | Current disposition | OK candidate | Independent review | 근거 |
|---|---|---|---:|---|---|
| `DLV-SEC-01` | `INTERNAL_GAP` | `INTERNAL_GAP_RETAINED` | `false` | `NOT_REQUESTED_FOR_OK_TRANSITION` | current generation, scan scope, SLA and gates are now explicit, but actual role acknowledgement and training receipts are both zero; source_commit/build_id and independent approval also remain absent. |
| `DLV-SEC-02` | `INTERNAL_GAP` | `READY_FOR_INDEPENDENT_REVIEW_AS_OK_CANDIDATE` | `true` | `PENDING` | current DFD nodes, flows, data classes, trust boundaries, entry points, owners and bidirectional threat/control/evidence/risk links are complete; no status transition is applied before independent review. |
| `DLV-SEC-03` | `INTERNAL_GAP` | `READY_FOR_INDEPENDENT_REVIEW_AS_OK_CANDIDATE` | `true` | `PENDING` | all six source risks are preserved verbatim and extended add-only with cause, path, exposure, due/expiry, residual status and proposed treatment; no status transition is applied before independent review. |

SEC01은 actual role assignment, acknowledgement, training receipt가 모두 0이므로 gap을 유지합니다. SEC02/03은 독립 검토 전 후보일 뿐 현재 OK 전환이 아닙니다.

## Current source subject

- generation: `W5-CURRENT-SOURCE-RUN-20260727-002` / run: `W5-SECURITY-SCAN-20260727-002`
- selected source: `528` files / `45bade9a3e2b1bea39627e3031a3c9302751748195d1458e6c8b1eea1275eea4`
- source_commit: `null`; build_id: `null`
- W4 predecessor receipt SHA: `e924b5216da5ba174fb765c8c5d8d18e91a9b2a8aeec8dd8b6876b0a644210ba`

| Binding | Kind | Path | SHA-256 | Claim |
|---|---|---|---|---|
| `SRC-AUDIT-BASELINE` | `EXACT_FILE_SHA256` | `docs/control/execution/artifact-audits/20260726/artifact-audit-baseline.json` | `713835e71b9fd8a64c9f3744e00f2b52e06273705c725451de795ba4b1cb55dc` | 2026-07-26 SEC01~03 current audit classification and required actions |
| `SRC-SECURITY-PLAN` | `EXACT_FILE_SHA256` | `docs/deliverables/07-security/security-and-privacy-plan.md` | `4badcccfd1b4a991faed9e9bd0199b9fbe8c4c823d091afe208da91d013ca286` | canonical Draft policy, SLA, roles and pre-existing threat inputs |
| `SRC-RISK-REGISTER` | `EXACT_FILE_SHA256` | `docs/deliverables/07-security/registers/security-risks.json` | `2a428d6aaee5eee214f36e6842ecc929df73379cd3398b7af3f4d24bc6a321cc` | add-only preserved opening snapshot of six OPEN risks |
| `SRC-RUN002-MANIFEST` | `EXACT_FILE_SHA256` | `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-002/selected-source-manifest.json` | `45bade9a3e2b1bea39627e3031a3c9302751748195d1458e6c8b1eea1275eea4` | current selected source set, 528 files |
| `SRC-RUN002-SUMMARY` | `EXACT_FILE_SHA256` | `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-002/execution-summary.json` | `47be8e6516f423381a51a616b6bbebf8b984bc57cca87b2eef74df20996beb0a` | current source scan execution aggregates and trusted-proxy validation |
| `SRC-DEP-MANIFEST` | `EXACT_FILE_SHA256` | `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/dependency-remediation-20260727-001/manifest.json` | `fa1bbe0bf3072ef72c7d529b962f42bb47947fdc4f98991fa040456dba3f5153` | dependency remediation receipt manifest |
| `SRC-DEP-CHANGES` | `EXACT_FILE_SHA256` | `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/dependency-remediation-20260727-001/changes.json` | `052c7d08de49efd119fcf205d7f8aa698c8c40bb8559ea012bca7329d31b40b6` | three modified dependency declaration/lock files |
| `SRC-DEP-VALIDATION` | `EXACT_FILE_SHA256` | `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/dependency-remediation-20260727-001/validation-summary.json` | `87994ede6f09a1bd3fd43ea0fc6c26233ca56df171adcf436bab7908d9c3b1d7` | determinism, four-package smoke and 28 targeted backend tests |
| `SRC-DEP-DELTA` | `EXACT_FILE_SHA256` | `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/dependency-remediation-20260727-001/package-delta.json` | `e3fdc3940bead48cdce49cc914b9397d0e0a3c967fc5d60f9e3bea92e3304c5c` | approved four-package version projection |
| `SRC-W4-RECEIPT` | `EXACT_FILE_SHA256` | `docs/control/execution/artifact-remediation/20260726/w4/implementation-receipt.json` | `e924b5216da5ba174fb765c8c5d8d18e91a9b2a8aeec8dd8b6876b0a644210ba` | terminal W4 predecessor receipt |
| `SRC-W4-VALIDATION` | `EXACT_FILE_SHA256` | `docs/control/execution/artifact-remediation/20260726/w4/validation-summary.json` | `f41fac3a2c497a6109f0c2213800663d1b24094750070ce98449063ed40e2590` | terminal W4 validation boundary |
| `SRC-W4-DELTA` | `EXACT_FILE_SHA256` | `docs/control/execution/artifact-remediation/20260726/w4/artifact-status-delta.json` | `9e8f3547b68874ad6b546dd0bccbfb612647d5923b2026912c1efc05243f4a21` | terminal W4 status delta and release boundary |
| `SRC-SCAN-SUCCESSOR` | `PATH_ONLY_FORWARD_REFERENCE` | `docs/control/execution/artifact-remediation/20260726/w5/security-scan-current-state.json` | `null` | future authoritative normalized actual scan findings; existence, hash and outcome are not claimed |

### 의존성 remediation과 TLS

- dependency: `PASS_WITH_RECOVERED_RECEIPT_EXDEV`; full backend lock install: `NOT_RUN_RESOURCE_POLICY`
- versions: `Pillow 12.3.0, python-multipart 0.0.31, python-dotenv 1.2.2, idna 3.15`
- TLS: `TARGETED_INTERNAL_PASS_NOT_DEPLOYMENT_EVIDENCE`; CA verification, hostname checking, invalid-context pre-network rejection만 targeted internal evidence로 결속

## SEC01 실행 계획

### Scan scope

| Lane | Tool/scope | Current result | Boundary |
|---|---|---|---|
| SAST | `semgrep 1.164.0` / backend/app; backend/alembic; Android user/admin main; Gateway src; model; scripts | 34 findings, 1 error, pending triage | detailed findings path-only |
| SCA | `osv-scanner 2.4.0` / backend recursive source, 64 packages | 6 affected packages, 52 unique primary IDs, pending reachability/triage | reachability/triage pending |
| Secret | `gitleaks 8.30.1` / current selected tree only | 0 findings | history `NOT_RUN` |

Actual normalized findings authority: `docs/control/execution/artifact-remediation/20260726/w5/security-scan-current-state.json` (`PATH_ONLY_FORWARD_REFERENCE`, SHA `null`).

### Remediation SLA

| Severity | Initial triage | Rule |
|---|---|---|
| `CRITICAL` | 4 business hours | immediately block feature/release; no unblock before containment, fix and retest |
| `HIGH` | 1 business day | fix and retest before beta/release; otherwise only a time-bounded SEC-16 exception with compensating controls |
| `MEDIUM` | 5 business days | set plan, owner and due date before the next deployment |
| `LOW` | 10 business days | register backlog item and next review date |

### Security readiness checks

| ID | Check | Owner | Status | Release blocking | Reason |
|---|---|---|---|---:|---|
| `CHK-01` | current generation freeze | 빌드·증거관리자 | `PARTIAL` | `true` | 528-file manifest is hash-bound; source_commit and build_id are null |
| `CHK-02` | SAST triage and retest | 보안·개인정보책임자 | `PENDING_TRIAGE` | `true` | run002 completed with 34 findings and 1 error; detailed authority is path-only successor |
| `CHK-03` | SCA reachability and retest | 기술책임자 | `PENDING_TRIAGE` | `true` | run002 reports 6 affected packages and 52 unique primary IDs; four-package remediation does not close all findings |
| `CHK-04` | secret scan current tree and history | 보안·개인정보책임자 | `PARTIAL` | `true` | current selected tree reports zero findings; history scan is NOT_RUN |
| `CHK-05` | dependency and TLS corrective validation | 기술책임자 | `INTERNAL_EVIDENCE_PRESENT_NOT_FORMAL_RELEASE` | `true` | dependency receipt passes with stated resource boundary; TLS CA/hostname positive and pre-network negative tests exited zero |
| `CHK-06` | independent security review | 보안·개인정보책임자 | `NOT_RUN` | `true` | SEC02/03 are only candidates pending review |
| `CHK-07` | role acknowledgement and training | 프로젝트책임자 | `NOT_RUN` | `true` | actual assignment, acknowledgement and training receipt counts are zero |
| `CHK-08` | predecessor five release gates | 프로젝트책임자 | `NOT_RUN` | `true` | five gates remain unwaived and NOT_RUN |

### Role/training matrix

| Role | Separation | Training | Acknowledgement | Training receipts | Ack receipts | Due / expiry |
|---|---|---|---|---:|---:|---|
| 보안·개인정보책임자 | independent reviewer must not be the sole content author | secure development, privacy handling, incident triage | security plan, raw data rules, incident escalation | `0` | `0` | `2026-08-03` / `2026-08-31` |
| 기술책임자 | cannot self-approve independent security review | secure architecture, supply-chain security, TLS validation | current source subject, remediation SLA | `0` | `0` | `2026-08-03` / `2026-08-31` |
| QA책임자 | review evidence independently from implementer where required | security test evidence, data-safe testing | formal/device boundary, no-PASS-without-evidence rule | `0` | `0` | `2026-08-03` / `2026-08-31` |
| 제품책임자 | may not substitute product decision for security finding closure | security risk acceptance, privacy product decisions | open-risk register, release block conditions | `0` | `0` | `2026-08-03` / `2026-08-31` |
| 법무·라이선스검토자 | independent legal conclusion where required | applicable privacy obligations, open-source licensing | legal-review boundary, no inferred approval | `0` | `0` | `2026-08-10` / `2026-08-31` |
| 프로젝트책임자 | approval cannot fabricate missing independent review or execution | release governance, security exception expiry | five-gate no-waiver state, six OPEN risks | `0` | `0` | `2026-08-03` / `2026-08-31` |
| 접근성·안전책임자 | safety acceptance requires actual-device evidence | fail-safe navigation, accessible incident response | live-TMAP boundary, actual-device boundary | `0` | `0` | `2026-08-10` / `2026-08-31` |
| 빌드·증거관리자 | cannot alter execution evidence after capture | artifact integrity, secret-safe evidence handling | current subject manifest, path-only successor rule | `0` | `0` | `2026-08-03` / `2026-08-31` |

## SEC02 current DFD

> Logical current-source DFD입니다. production deployment 또는 actual-device execution을 뜻하지 않습니다.

### Nodes

| Node | Name | Type / zone | Data classes | Owner | Exposure note |
|---|---|---|---|---|---|
| `N-ANDROID-USER` | Android user | `CLIENT_PROCESS` / `DEVICE_USER` | `DC-AUTH, DC-IDENTITY, DC-RAW, DC-LOCATION, DC-REPORT, DC-CONSENT, DC-MODEL-CONFIG` | 기술책임자 | threats `THR-001, THR-003, THR-004, THR-005, THR-006, THR-007, THR-008, THR-009` |
| `N-ANDROID-ADMIN` | Android admin | `PRIVILEGED_CLIENT_PROCESS` / `DEVICE_ADMIN` | `DC-AUTH, DC-IDENTITY, DC-RAW, DC-REPORT, DC-ADMIN-RECOVERY, DC-TELEMETRY` | 프로젝트책임자 | threats `THR-002, THR-003, THR-007, THR-008` |
| `N-GATEWAY` | Gateway | `EDGE_SERVICE` / `EDGE` | `DC-AUTH, DC-IDENTITY, DC-RAW, DC-LOCATION, DC-REPORT, DC-CONSENT, DC-TELEMETRY` | 기술책임자 | threats `THR-003, THR-006, THR-007, THR-008, THR-009` |
| `N-BACKEND` | Backend | `APPLICATION_SERVICE` / `SERVICE` | `DC-AUTH, DC-IDENTITY, DC-RAW, DC-LOCATION, DC-REPORT, DC-CONSENT, DC-ADMIN-RECOVERY, DC-TELEMETRY` | 기술책임자 | threats `THR-001, THR-002, THR-003, THR-004, THR-005, THR-006, THR-007, THR-008, THR-009, THR-010` |
| `N-POSTGIS` | PostGIS | `DATA_STORE` / `DATA` | `DC-IDENTITY, DC-LOCATION, DC-REPORT, DC-CONSENT, DC-TELEMETRY` | 기술책임자 | threats `THR-001, THR-003` |
| `N-RAW-STORE` | raw encrypted queue/store | `DATA_STORE_AGGREGATE` / `RAW_CONTROLLED_DATA` | `DC-RAW, DC-LOCATION, DC-REPORT, DC-CONSENT` | 보안·개인정보책임자 | threats `THR-001, THR-004, THR-006` |
| `N-MODEL-CONFIG` | model/config | `ARTIFACT_STORE_AND_LOCAL_RUNTIME` / `MODEL_RUNTIME` | `DC-MODEL-CONFIG, DC-RAW, DC-LOCATION` | 기술책임자 | threats `THR-005, THR-008` |
| `N-TMAP` | TMAP | `EXTERNAL_SERVICE` / `EXTERNAL_PROVIDER` | `DC-LOCATION` | 접근성·안전책임자 | threats `THR-005` |
| `N-CONSENT-LEDGER` | consent ledger | `PRIVACY_LEDGER` / `EDGE_CONTROLLED_DATA` | `DC-IDENTITY, DC-CONSENT, DC-TELEMETRY` | 보안·개인정보책임자 | threats `THR-006, THR-009` |
| `N-ADMIN-RECOVERY` | admin recovery | `PRIVILEGED_CONTROL_PLANE` / `PRIVILEGED_RECOVERY` | `DC-AUTH, DC-ADMIN-RECOVERY, DC-TELEMETRY` | 프로젝트책임자 | threats `THR-002` |
| `N-BUILD-EVIDENCE` | build/evidence pipeline | `SUPPLY_CHAIN_AND_EVIDENCE_PLANE` / `BUILD_EVIDENCE` | `DC-MODEL-CONFIG, DC-BUILD-EVIDENCE, DC-TELEMETRY` | 빌드·증거관리자 | threats `THR-008, THR-010` |

### Data classes

| ID | Name | Sensitivity | Threats |
|---|---|---|---|
| `DC-AUTH` | authentication/session secrets | `SECRET` | `THR-001, THR-002, THR-007` |
| `DC-IDENTITY` | pseudonymous account/device identity | `CONFIDENTIAL` | `THR-001, THR-003, THR-006, THR-007, THR-009` |
| `DC-RAW` | raw image/audio/sensor/report payload | `RESTRICTED` | `THR-001, THR-004, THR-006, THR-007, THR-009` |
| `DC-LOCATION` | precise location, route and movement state | `RESTRICTED` | `THR-001, THR-004, THR-005, THR-006, THR-007` |
| `DC-REPORT` | report, adjudication and agency-delivery state | `CONFIDENTIAL` | `THR-001, THR-002, THR-003, THR-004, THR-007` |
| `DC-CONSENT` | consent, withdrawal, deletion and privacy-rights ledger | `CONFIDENTIAL` | `THR-001, THR-004, THR-006, THR-009` |
| `DC-ADMIN-RECOVERY` | admin recovery factors and high-risk action state | `SECRET` | `THR-002, THR-007` |
| `DC-MODEL-CONFIG` | model binaries, runtime config, inference input/output | `INTEGRITY_CRITICAL` | `THR-005, THR-008` |
| `DC-TELEMETRY` | security telemetry and append-only audit events | `CONFIDENTIAL` | `THR-002, THR-003, THR-004, THR-009, THR-010` |
| `DC-BUILD-EVIDENCE` | source, dependency, build, scan and evidence manifests | `INTEGRITY_CRITICAL` | `THR-008, THR-010` |

### Trust boundaries

| ID | Boundary | Zones | Owner | Threats |
|---|---|---|---|---|
| `TB-DEVICE-USER` | human/OS to user-app sandbox | `UNTRUSTED_HUMAN_OS_INPUT` -> `DEVICE_USER` | 기술책임자 | `THR-001, THR-003, THR-004, THR-005, THR-006, THR-008, THR-009` |
| `TB-DEVICE-ADMIN` | operator/OS to privileged admin-app sandbox | `UNTRUSTED_OR_PRIVILEGED_OPERATOR_INPUT` -> `DEVICE_ADMIN` | 프로젝트책임자 | `THR-002, THR-003` |
| `TB-NETWORK-EDGE` | device to Gateway/Backend network edge | `DEVICE_USER_OR_ADMIN` -> `EDGE_OR_SERVICE` | 기술책임자 | `THR-001, THR-002, THR-003, THR-006, THR-007, THR-009` |
| `TB-GATEWAY-SERVICE` | Gateway to Backend service | `EDGE` -> `SERVICE` | 기술책임자 | `THR-003, THR-006, THR-007, THR-009` |
| `TB-SERVICE-DATA` | Backend to PostGIS/raw controlled data | `SERVICE` -> `DATA_OR_RAW_CONTROLLED_DATA` | 보안·개인정보책임자 | `THR-001, THR-003, THR-004` |
| `TB-EXTERNAL-TMAP` | Backend to external TMAP | `SERVICE` -> `EXTERNAL_PROVIDER` | 접근성·안전책임자 | `THR-005` |
| `TB-PRIVILEGE-RECOVERY` | admin privilege and recovery plane | `DEVICE_ADMIN` -> `PRIVILEGED_RECOVERY_OR_SERVICE` | 프로젝트책임자 | `THR-002, THR-007` |
| `TB-SUPPLYCHAIN-EVIDENCE` | build/evidence plane to runtime artifacts | `BUILD_EVIDENCE` -> `MODEL_RUNTIME_OR_APPLICATION` | 빌드·증거관리자 | `THR-008, THR-010` |
| `TB-RAW-CONTROL` | raw capture to encrypted controlled storage | `DEVICE_OR_SERVICE_PROCESSING` -> `RAW_CONTROLLED_DATA` | 보안·개인정보책임자 | `THR-001, THR-004, THR-006` |

### Entry points

| ID | Entry point | Nodes | Owner | Threats |
|---|---|---|---|---|
| `EP-USER-SENSOR` | user-app sensor/camera/location input | `N-ANDROID-USER` | 기술책임자 | `THR-001, THR-004, THR-005, THR-006` |
| `EP-USER-GATEWAY` | user Gateway API routes | `N-ANDROID-USER, N-GATEWAY` | 기술책임자 | `THR-003, THR-006, THR-007, THR-009` |
| `EP-GATEWAY-PROXY` | Gateway proxy boundary | `N-GATEWAY, N-BACKEND` | 기술책임자 | `THR-003, THR-006, THR-007` |
| `EP-BACKEND-API` | Backend public/internal API | `N-BACKEND` | 기술책임자 | `THR-001, THR-002, THR-003, THR-004, THR-005, THR-006, THR-007, THR-009` |
| `EP-POSTGIS` | PostGIS query/migration interface | `N-POSTGIS` | 기술책임자 | `THR-001, THR-003` |
| `EP-RAW-STORE` | raw encrypted queue/store interface | `N-RAW-STORE` | 보안·개인정보책임자 | `THR-001, THR-004, THR-006` |
| `EP-MODEL-CONFIG` | model/config load and inference interface | `N-MODEL-CONFIG` | 기술책임자 | `THR-005, THR-008` |
| `EP-TMAP` | TMAP outbound request/inbound response | `N-TMAP` | 접근성·안전책임자 | `THR-005` |
| `EP-CONSENT` | consent/privacy-rights operations | `N-CONSENT-LEDGER` | 보안·개인정보책임자 | `THR-006, THR-009` |
| `EP-ADMIN-API` | administrator review/high-risk API | `N-ANDROID-ADMIN, N-BACKEND` | 프로젝트책임자 | `THR-002, THR-003, THR-007` |
| `EP-ADMIN-RECOVERY` | administrator recovery operations | `N-ANDROID-ADMIN, N-ADMIN-RECOVERY, N-BACKEND` | 프로젝트책임자 | `THR-002` |
| `EP-BUILD-EVIDENCE` | source/dependency/artifact/evidence ingestion | `N-BUILD-EVIDENCE` | 빌드·증거관리자 | `THR-008, THR-010` |

### Flows

| ID | From -> To | Data / protocol | Boundaries / entry points | Owner | Current exposure |
|---|---|---|---|---|---|
| `F-01` | `N-ANDROID-USER` -> `N-MODEL-CONFIG` | `DC-MODEL-CONFIG, DC-RAW, DC-LOCATION` / local file/runtime API | `TB-DEVICE-USER` / `EP-USER-SENSOR, EP-MODEL-CONFIG` | 기술책임자 | source-bound local runtime; signing and actual-device validation are not assessed/run |
| `F-02` | `N-ANDROID-USER` -> `N-RAW-STORE` | `DC-RAW, DC-LOCATION, DC-REPORT, DC-CONSENT` / local encrypted storage API | `TB-DEVICE-USER, TB-RAW-CONTROL` / `EP-USER-SENSOR, EP-RAW-STORE` | 보안·개인정보책임자 | capacity, device key custody and deletion receipts are not formally validated |
| `F-03` | `N-ANDROID-USER` -> `N-GATEWAY` | `DC-AUTH, DC-IDENTITY, DC-RAW, DC-LOCATION, DC-REPORT, DC-CONSENT` / HTTPS intended | `TB-NETWORK-EDGE` / `EP-USER-GATEWAY, EP-GATEWAY-PROXY` | 기술책임자 | source selected; deployed certificate, endpoint and actual-device evidence are NOT_RUN |
| `F-04` | `N-GATEWAY` -> `N-BACKEND` | `DC-AUTH, DC-IDENTITY, DC-RAW, DC-LOCATION, DC-REPORT, DC-CONSENT, DC-TELEMETRY` / HTTP(S) deployment-configured | `TB-GATEWAY-SERVICE` / `EP-GATEWAY-PROXY, EP-BACKEND-API` | 기술책임자 | trusted-proxy TLS validator has targeted evidence; deployed topology is NOT_RUN |
| `F-05` | `N-BACKEND` -> `N-POSTGIS` | `DC-IDENTITY, DC-LOCATION, DC-REPORT, DC-CONSENT, DC-TELEMETRY` / database driver; deployment TLS/credentials not evidenced | `TB-SERVICE-DATA` / `EP-BACKEND-API, EP-POSTGIS` | 기술책임자 | schema/source present; production least privilege, encryption and backup receipts absent |
| `F-06` | `N-BACKEND` -> `N-RAW-STORE` | `DC-RAW, DC-LOCATION, DC-REPORT` / object/file storage interface | `TB-SERVICE-DATA, TB-RAW-CONTROL` / `EP-BACKEND-API, EP-RAW-STORE` | 보안·개인정보책임자 | logical boundary only; current deployment inventory, KMS and deletion receipts absent |
| `F-07` | `N-BACKEND` -> `N-TMAP` | `DC-LOCATION` / HTTPS intended | `TB-EXTERNAL-TMAP` / `EP-BACKEND-API, EP-TMAP` | 접근성·안전책임자 | live provider, quota and same-generation endpoint validation are NOT_RUN |
| `F-08` | `N-TMAP` -> `N-BACKEND` | `DC-LOCATION` / HTTPS intended | `TB-EXTERNAL-TMAP` / `EP-TMAP, EP-BACKEND-API` | 접근성·안전책임자 | provider response freshness and failover are not live-tested |
| `F-09` | `N-GATEWAY` -> `N-CONSENT-LEDGER` | `DC-IDENTITY, DC-CONSENT, DC-TELEMETRY` / local/service ledger API | `TB-GATEWAY-SERVICE` / `EP-USER-GATEWAY, EP-CONSENT` | 보안·개인정보책임자 | source ledger exists; production immutability and external-store deletion receipts absent |
| `F-10` | `N-CONSENT-LEDGER` -> `N-GATEWAY` | `DC-IDENTITY, DC-CONSENT` / local/service ledger API | `TB-GATEWAY-SERVICE` / `EP-CONSENT, EP-USER-GATEWAY` | 보안·개인정보책임자 | current UI/legal wording and actual-device state reconciliation remain outside this evidence |
| `F-11` | `N-ANDROID-ADMIN` -> `N-BACKEND` | `DC-AUTH, DC-IDENTITY, DC-RAW, DC-REPORT, DC-TELEMETRY` / HTTPS required by endpoint policy | `TB-DEVICE-ADMIN, TB-NETWORK-EDGE, TB-PRIVILEGE-RECOVERY` / `EP-ADMIN-API, EP-BACKEND-API` | 프로젝트책임자 | source policy/control present; actual-device and deployed endpoint evidence NOT_RUN |
| `F-12` | `N-ANDROID-ADMIN` -> `N-ADMIN-RECOVERY` | `DC-AUTH, DC-ADMIN-RECOVERY, DC-TELEMETRY` / privileged app operation | `TB-DEVICE-ADMIN, TB-PRIVILEGE-RECOVERY` / `EP-ADMIN-RECOVERY` | 프로젝트책임자 | single-admin recovery drill is NOT_RUN |
| `F-13` | `N-ADMIN-RECOVERY` -> `N-BACKEND` | `DC-AUTH, DC-ADMIN-RECOVERY, DC-TELEMETRY` / HTTPS privileged API | `TB-NETWORK-EDGE, TB-PRIVILEGE-RECOVERY` / `EP-ADMIN-RECOVERY, EP-BACKEND-API` | 프로젝트책임자 | recovery source exists; deployed revocation and break-glass evidence absent |
| `F-14` | `N-BUILD-EVIDENCE` -> `N-MODEL-CONFIG` | `DC-MODEL-CONFIG, DC-BUILD-EVIDENCE` / manifested build input | `TB-SUPPLYCHAIN-EVIDENCE` / `EP-BUILD-EVIDENCE, EP-MODEL-CONFIG` | 빌드·증거관리자 | selected source hash exists; signed delivery is NOT_ASSESSED |
| `F-15` | `N-BUILD-EVIDENCE` -> `N-ANDROID-USER` | `DC-BUILD-EVIDENCE, DC-MODEL-CONFIG` / build pipeline | `TB-SUPPLYCHAIN-EVIDENCE` / `EP-BUILD-EVIDENCE` | 빌드·증거관리자 | source selected; release build, signing and deployment NOT_RUN/NOT_ASSESSED |
| `F-16` | `N-BUILD-EVIDENCE` -> `N-ANDROID-ADMIN` | `DC-BUILD-EVIDENCE` / build pipeline | `TB-SUPPLYCHAIN-EVIDENCE` / `EP-BUILD-EVIDENCE` | 빌드·증거관리자 | source selected; release build, signing and deployment NOT_RUN/NOT_ASSESSED |
| `F-17` | `N-BUILD-EVIDENCE` -> `N-GATEWAY` | `DC-BUILD-EVIDENCE` / build pipeline | `TB-SUPPLYCHAIN-EVIDENCE` / `EP-BUILD-EVIDENCE` | 빌드·증거관리자 | source selected; deployment NOT_RUN |
| `F-18` | `N-BUILD-EVIDENCE` -> `N-BACKEND` | `DC-BUILD-EVIDENCE` / build pipeline | `TB-SUPPLYCHAIN-EVIDENCE` / `EP-BUILD-EVIDENCE` | 빌드·증거관리자 | dependency remediation is bound; full lock install was NOT_RUN by resource policy and deployment NOT_RUN |
| `F-19` | `N-BACKEND` -> `N-BUILD-EVIDENCE` | `DC-BUILD-EVIDENCE, DC-TELEMETRY` / local controlled evidence write | `TB-SUPPLYCHAIN-EVIDENCE` / `EP-BUILD-EVIDENCE` | 빌드·증거관리자 | run002 aggregate evidence exists; normalized actual findings remain a path-only forward reference |

## Attacker taxonomy

| ID | Attacker | Capability | Access | Scenarios |
|---|---|---|---|---|
| `ATK-01` | unauthenticated remote attacker | internet/API probing, malformed input and credential attacks | remote edge | `THR-001, THR-002, THR-003, THR-005, THR-007` |
| `ATK-02` | authenticated malicious user | valid user session, report/privacy API abuse and replay | user-authorized surface | `THR-003, THR-004, THR-006, THR-009` |
| `ATK-03` | stolen-device thief or local malware | device filesystem, tokens, sensors and local queue observation | user/admin device | `THR-001, THR-002, THR-004, THR-005, THR-006` |
| `ATK-04` | compromised administrator | privileged review, recovery and high-risk operations | admin plane | `THR-002, THR-003, THR-007, THR-009` |
| `ATK-05` | malicious or careless insider/operator | service, data store, backup or evidence access | internal/operational | `THR-001, THR-006, THR-008, THR-009, THR-010` |
| `ATK-06` | dependency/build supply-chain attacker | package, build input, model/config or evidence substitution | supply chain | `THR-008, THR-010` |
| `ATK-07` | network or external-provider adversary | MITM, DNS/proxy manipulation, stale/malicious provider response | network/provider boundary | `THR-005, THR-007` |
| `ATK-08` | resource-abuse actor | high-volume capture/upload/report requests and storage exhaustion | device/API resource boundary | `THR-003, THR-004` |
| `ATK-09` | evidence or gate manipulator | report omission, hash substitution or false release assertion | build/evidence governance plane | `THR-008, THR-010` |

## Threat scenarios

| ID | Scenario | Attacker / DFD | P / D / R controls | Evidence / risks | Exposure | L / I / residual | Treatment / due / expiry |
|---|---|---|---|---|---|---|---|
| `THR-001` | raw data exfiltration or purpose-divergent access | `ATK-01, ATK-03, ATK-05` / `N-ANDROID-USER, N-BACKEND, N-POSTGIS, N-RAW-STORE, F-02, F-05, F-06, TB-DEVICE-USER, TB-NETWORK-EDGE, TB-SERVICE-DATA, TB-RAW-CONTROL, EP-USER-SENSOR, EP-BACKEND-API, EP-POSTGIS, EP-RAW-STORE` | `CTL-P01, CTL-P02, CTL-P08 / CTL-D01, CTL-D02, CTL-D03 / CTL-R02, CTL-R05` | `EV-01, EV-02, EV-04, EV-07, EV-09` / `SR-001` | production device/storage/KMS/backup evidence absent; current SAST findings await triage and history secret scan is NOT_RUN | `MEDIUM` / `CRITICAL` / `NOT_ASSESSED` | `MITIGATE` `PROPOSED_NOT_APPROVED` / 보안·개인정보책임자 `2026-07-31` / `2026-08-31` |
| `THR-002` | admin takeover, factor loss or recovery lockout | `ATK-01, ATK-03, ATK-04` / `N-ANDROID-ADMIN, N-BACKEND, N-ADMIN-RECOVERY, F-11, F-12, F-13, TB-DEVICE-ADMIN, TB-NETWORK-EDGE, TB-PRIVILEGE-RECOVERY, EP-ADMIN-API, EP-ADMIN-RECOVERY, EP-BACKEND-API` | `CTL-P03, CTL-P08 / CTL-D01, CTL-D02 / CTL-R01, CTL-R05` | `EV-01, EV-02, EV-04, EV-07, EV-09` / `SR-002` | admin source controls exist, but actual-device behavior, remote revocation and recovery drill are NOT_RUN | `MEDIUM` / `HIGH` / `NOT_ASSESSED` | `MITIGATE` `PROPOSED_NOT_APPROVED` / 프로젝트책임자 `2026-08-03` / `2026-08-31` |
| `THR-003` | duplicate/spam reports and privileged adjudication error | `ATK-01, ATK-02, ATK-04, ATK-08` / `N-ANDROID-USER, N-ANDROID-ADMIN, N-GATEWAY, N-BACKEND, N-POSTGIS, F-03, F-04, F-05, F-11, TB-DEVICE-USER, TB-DEVICE-ADMIN, TB-NETWORK-EDGE, TB-GATEWAY-SERVICE, TB-SERVICE-DATA, EP-USER-GATEWAY, EP-GATEWAY-PROXY, EP-BACKEND-API, EP-POSTGIS, EP-ADMIN-API` | `CTL-P03, CTL-P04 / CTL-D01, CTL-D02 / CTL-R05` | `EV-01, EV-02, EV-07, EV-09` / `SR-003` | source idempotency/rate controls are visible; production abuse thresholds, alerts and adjudication review are not evidenced | `MEDIUM` / `HIGH` / `NOT_ASSESSED` | `MITIGATE` `PROPOSED_NOT_APPROVED` / 보안·개인정보책임자 `2026-08-05` / `2026-08-31` |
| `THR-004` | encrypted queue exhaustion and protected-record loss | `ATK-02, ATK-03, ATK-08` / `N-ANDROID-USER, N-BACKEND, N-RAW-STORE, F-02, F-06, TB-DEVICE-USER, TB-SERVICE-DATA, TB-RAW-CONTROL, EP-USER-SENSOR, EP-RAW-STORE, EP-BACKEND-API` | `CTL-P04, CTL-P05 / CTL-D01, CTL-D02 / CTL-R02` | `EV-01, EV-02, EV-07, EV-09` / `SR-004` | byte ceiling, supported-device capacity and ordered recovery gate are NOT_RUN | `HIGH` / `HIGH` / `NOT_ASSESSED` | `MITIGATE` `PROPOSED_NOT_APPROVED` / 기술책임자 `2026-07-31` / `2026-08-31` |
| `THR-005` | TMAP outage, tampered response or stale-route continuation | `ATK-01, ATK-03, ATK-07` / `N-ANDROID-USER, N-BACKEND, N-MODEL-CONFIG, N-TMAP, F-01, F-07, F-08, TB-DEVICE-USER, TB-EXTERNAL-TMAP, EP-USER-SENSOR, EP-MODEL-CONFIG, EP-BACKEND-API, EP-TMAP` | `CTL-P06, CTL-P08 / CTL-D01, CTL-D02 / CTL-R03` | `EV-01, EV-02, EV-06, EV-07, EV-09` / `SR-005` | trusted-proxy TLS validation is targeted only; live TMAP, provider quota/fallback and actual-device route behavior are NOT_RUN | `MEDIUM` / `CRITICAL` / `NOT_ASSESSED` | `MITIGATE` `PROPOSED_NOT_APPROVED` / 접근성·안전책임자 `2026-07-31` / `2026-08-31` |
| `THR-006` | FP-035 mobile-network upload policy bypass | `ATK-02, ATK-03, ATK-05` / `N-ANDROID-USER, N-GATEWAY, N-BACKEND, N-RAW-STORE, N-CONSENT-LEDGER, F-02, F-03, F-04, F-09, F-10, TB-DEVICE-USER, TB-NETWORK-EDGE, TB-GATEWAY-SERVICE, TB-RAW-CONTROL, EP-USER-SENSOR, EP-USER-GATEWAY, EP-GATEWAY-PROXY, EP-BACKEND-API, EP-RAW-STORE, EP-CONSENT` | `CTL-P01, CTL-P04 / CTL-D01, CTL-D03 / CTL-R02, CTL-R05` | `EV-01, EV-02, EV-07, EV-09` / `SR-006` | normalized policy is preserved but bundled approval, formal tests and actual-device network behavior are NOT_RUN | `MEDIUM` / `HIGH` / `NOT_ASSESSED` | `MITIGATE` `PROPOSED_NOT_APPROVED` / 제품책임자 `2026-08-03` / `2026-08-31` |
| `THR-007` | Gateway/proxy TLS or trust-boundary bypass | `ATK-01, ATK-04, ATK-07` / `N-ANDROID-USER, N-ANDROID-ADMIN, N-GATEWAY, N-BACKEND, F-03, F-04, F-11, TB-NETWORK-EDGE, TB-GATEWAY-SERVICE, TB-PRIVILEGE-RECOVERY, EP-USER-GATEWAY, EP-GATEWAY-PROXY, EP-ADMIN-API, EP-BACKEND-API` | `CTL-P03, CTL-P08 / CTL-D01, CTL-D02 / CTL-R04, CTL-R05` | `EV-01, EV-02, EV-04, EV-06, EV-07, EV-09` / `SR-001, SR-002` | CA/hostname positive and pre-network negative validation passes internally; deployed endpoints and certificates are not evidenced | `MEDIUM` / `CRITICAL` / `NOT_ASSESSED` | `MITIGATE` `PROPOSED_NOT_APPROVED` / 기술책임자 `2026-07-31` / `2026-08-31` |
| `THR-008` | dependency, model/config or runtime artifact substitution | `ATK-05, ATK-06, ATK-09` / `N-BUILD-EVIDENCE, N-MODEL-CONFIG, N-ANDROID-USER, N-ANDROID-ADMIN, N-GATEWAY, N-BACKEND, F-01, F-14, F-15, F-16, F-17, F-18, TB-DEVICE-USER, TB-SUPPLYCHAIN-EVIDENCE, EP-MODEL-CONFIG, EP-BUILD-EVIDENCE` | `CTL-P07, CTL-P09 / CTL-D02, CTL-D04 / CTL-R04, CTL-R05` | `EV-01, EV-02, EV-03, EV-05, EV-07, EV-09` / `SR-001, SR-005` | manifest and four-package remediation are bound; scan findings await triage, full lock install is resource-bounded, signing/deployment are not assessed/run | `MEDIUM` / `CRITICAL` / `NOT_ASSESSED` | `MITIGATE` `PROPOSED_NOT_APPROVED` / 기술책임자 `2026-07-31` / `2026-08-31` |
| `THR-009` | consent/privacy ledger tamper, replay or state divergence | `ATK-02, ATK-04, ATK-05` / `N-ANDROID-USER, N-GATEWAY, N-BACKEND, N-CONSENT-LEDGER, F-03, F-09, F-10, TB-DEVICE-USER, TB-NETWORK-EDGE, TB-GATEWAY-SERVICE, EP-USER-GATEWAY, EP-CONSENT, EP-BACKEND-API` | `CTL-P01, CTL-P03 / CTL-D01, CTL-D03 / CTL-R02, CTL-R05` | `EV-01, EV-02, EV-04, EV-07, EV-09` / `SR-001, SR-006` | ledger source exists; current legal/UI text, production immutability, cross-store reconciliation and deletion receipts are absent | `MEDIUM` / `HIGH` / `NOT_ASSESSED` | `MITIGATE` `PROPOSED_NOT_APPROVED` / 보안·개인정보책임자 `2026-08-03` / `2026-08-31` |
| `THR-010` | scan evidence omission, hash substitution or false gate assertion | `ATK-05, ATK-06, ATK-09` / `N-BACKEND, N-BUILD-EVIDENCE, F-19, TB-SUPPLYCHAIN-EVIDENCE, EP-BUILD-EVIDENCE` | `CTL-P07, CTL-P09 / CTL-D02, CTL-D04 / CTL-R04, CTL-R05` | `EV-01, EV-02, EV-03, EV-05, EV-07, EV-08, EV-09` / `SR-001` | run002 and W4 hashes are fixed, but normalized findings are path-only, independent review is pending and five release gates remain NOT_RUN | `MEDIUM` / `HIGH` / `NOT_ASSESSED` | `MITIGATE` `PROPOSED_NOT_APPROVED` / 빌드·증거관리자 `2026-07-31` / `2026-08-31` |

## Controls

| ID | Class | Control | Owner | Current status | Evidence | Scenarios |
|---|---|---|---|---|---|---|
| `CTL-P01` | `PREVENTIVE` | consent, minimization and FP-035 network policy | 보안·개인정보책임자 | `SOURCE_POLICY_PRESENT_NOT_FORMALLY_VALIDATED` | `EV-01, EV-07` | `THR-001, THR-006, THR-009` |
| `CTL-P02` | `PREVENTIVE` | encryption, key separation and least privilege | 보안·개인정보책임자 | `DOCUMENTED_AND_PARTIAL_SOURCE_NOT_DEPLOYMENT_EVIDENCED` | `EV-01, EV-07` | `THR-001` |
| `CTL-P03` | `PREVENTIVE` | role separation, MFA and high-risk action gate | 프로젝트책임자 | `SOURCE_BOUND_ACTUAL_DEVICE_AND_RECOVERY_NOT_RUN` | `EV-01, EV-07` | `THR-002, THR-003, THR-007, THR-009` |
| `CTL-P04` | `PREVENTIVE` | rate limit, idempotency and bounded request handling | 기술책임자 | `SOURCE_BOUND_NOT_FORMAL_RELEASE` | `EV-01, EV-02` | `THR-003, THR-004, THR-006` |
| `CTL-P05` | `PREVENTIVE` | queue byte cap and protected-record deletion priority | 기술책임자 | `POLICY_PRESENT_CAPACITY_GATE_NOT_RUN` | `EV-07` | `THR-004` |
| `CTL-P06` | `PREVENTIVE` | TMAP timeout/quota/stale-route fail-safe | 접근성·안전책임자 | `SOURCE_POLICY_PRESENT_LIVE_TMAP_NOT_RUN` | `EV-01, EV-07` | `THR-005` |
| `CTL-P07` | `PREVENTIVE` | locked dependency update and deterministic projection | 기술책임자 | `INTERNAL_REMEDIATION_PASS_WITH_RESOURCE_BOUNDARY` | `EV-03, EV-05` | `THR-008, THR-010` |
| `CTL-P08` | `PREVENTIVE` | TLS CA verification and hostname checking | 기술책임자 | `TARGETED_INTERNAL_VALIDATION_PASS_NOT_DEPLOYMENT_EVIDENCE` | `EV-02, EV-06` | `THR-001, THR-002, THR-005, THR-007` |
| `CTL-P09` | `PREVENTIVE` | selected-source manifest and hash-bound artifact/evidence custody | 빌드·증거관리자 | `CURRENT_SOURCE_HASH_BOUND_SIGNING_NOT_ASSESSED` | `EV-01, EV-08` | `THR-008, THR-010` |
| `CTL-D01` | `DETECTIVE` | security telemetry and append-only audit review | 보안·개인정보책임자 | `PARTIAL_SOURCE_NO_PRODUCTION_ALERT_RECEIPT` | `EV-01, EV-07` | `THR-001, THR-002, THR-003, THR-004, THR-005, THR-006, THR-007, THR-009` |
| `CTL-D02` | `DETECTIVE` | SAST, SCA and secret scan | 보안·개인정보책임자 | `EXECUTED_CURRENT_SOURCE_WITH_FINDINGS_PENDING_TRIAGE` | `EV-02, EV-03, EV-04, EV-09` | `THR-001, THR-002, THR-003, THR-004, THR-005, THR-007, THR-008, THR-010` |
| `CTL-D03` | `DETECTIVE` | consent/privacy/deletion ledger reconciliation | 보안·개인정보책임자 | `SOURCE_LEDGER_PRESENT_PRODUCTION_RECONCILIATION_NOT_RUN` | `EV-01, EV-07` | `THR-001, THR-006, THR-009` |
| `CTL-D04` | `DETECTIVE` | release gate and fingerprint verification | 빌드·증거관리자 | `SELF_CHECK_DEFINED_FIVE_RELEASE_GATES_NOT_RUN` | `EV-01, EV-08` | `THR-008, THR-010` |
| `CTL-R01` | `RECOVERY` | session revocation, recovery verification and high-risk freeze | 프로젝트책임자 | `SOURCE_BOUND_RECOVERY_DRILL_NOT_RUN` | `EV-01, EV-07` | `THR-002` |
| `CTL-R02` | `RECOVERY` | raw quarantine, ordered deletion and queue recovery | 보안·개인정보책임자 | `DOCUMENTED_CAPACITY_AND_DELETION_RECEIPTS_ABSENT` | `EV-07` | `THR-001, THR-004, THR-006, THR-009` |
| `CTL-R03` | `RECOVERY` | stop unsafe guidance and require user route reselection | 접근성·안전책임자 | `SOURCE_POLICY_PRESENT_LIVE_RECOVERY_NOT_RUN` | `EV-01, EV-07` | `THR-005` |
| `CTL-R04` | `RECOVERY` | dependency/TLS corrective patch and targeted retest | 기술책임자 | `INTERNAL_EVIDENCE_PRESENT_FORMAL_RELEASE_RETEST_PENDING` | `EV-03, EV-05, EV-06, EV-09` | `THR-007, THR-008, THR-010` |
| `CTL-R05` | `RECOVERY` | incident containment, credential rotation and evidence preservation | 보안·개인정보책임자 | `PROCEDURE_EXPECTED_NO_DRILL_OR_INCIDENT_RECEIPT` | `EV-04, EV-07, EV-09` | `THR-001, THR-002, THR-003, THR-006, THR-007, THR-008, THR-009, THR-010` |

## Validation evidence

| ID | Evidence | Result | Binding | Scope | Limitations | Controls / scenarios |
|---|---|---|---|---|---|---|
| `EV-01` | run002 selected-source manifest | `SOURCE_SET_BOUND` | `SRC-RUN002-MANIFEST` | 528 current selected files | source_commit null, build_id null, not a release build or deployment receipt | `CTL-P01, CTL-P02, CTL-P03, CTL-P04, CTL-P06, CTL-P09, CTL-D01, CTL-D03, CTL-D04, CTL-R01, CTL-R03` / `THR-001, THR-002, THR-003, THR-004, THR-005, THR-006, THR-007, THR-008, THR-009, THR-010` |
| `EV-02` | run002 Semgrep SAST aggregate | `COMPLETED_WITH_FINDINGS_PENDING_TRIAGE` | `SRC-RUN002-SUMMARY` | backend/app, backend/alembic, Android user/admin main, Gateway src, model and scripts; 654 scanned paths | no detailed finding disposition asserted here, actual findings use EV-09 path-only successor | `CTL-P04, CTL-P08, CTL-D02` / `THR-001, THR-002, THR-003, THR-004, THR-005, THR-006, THR-007, THR-008, THR-009, THR-010` |
| `EV-03` | run002 OSV backend SCA aggregate | `COMPLETED_WITH_FINDINGS_PENDING_TRIAGE` | `SRC-RUN002-SUMMARY` | backend recursive source; 64 packages | reachability and per-finding treatment pending, four-package remediation does not imply all SCA findings closed | `CTL-P07, CTL-D02, CTL-R04` / `THR-008, THR-010` |
| `EV-04` | run002 Gitleaks current selected tree aggregate | `ZERO_CURRENT_TREE_FINDINGS_WITH_SCOPE_GAP` | `SRC-RUN002-SUMMARY` | current selected tree only; exact-two-path allowlist plus generic API-key rule | history scan NOT_RUN, zero current-tree findings does not prove full secret absence | `CTL-D02, CTL-R05` / `THR-001, THR-002, THR-007, THR-009` |
| `EV-05` | dependency remediation receipt | `PASS_WITH_RECOVERED_RECEIPT_EXDEV` | `SRC-DEP-MANIFEST` | Pillow 12.3.0, python-multipart 0.0.31, python-dotenv 1.2.2, idna 3.15 and deterministic lock projection | full backend lock install NOT_RUN by explicit resource policy | `CTL-P07, CTL-R04` / `THR-008, THR-010` |
| `EV-06` | trusted-proxy TLS fix targeted validation | `TARGETED_INTERNAL_PASS` | `SRC-RUN002-SUMMARY` | CA-verified and hostname-checked success context plus invalid context rejected before network | not a deployed endpoint or formal release receipt | `CTL-P08, CTL-R04` / `THR-005, THR-007` |
| `EV-07` | canonical six-risk opening snapshot | `DRAFT_NOT_APPROVED_OPENING_SNAPSHOT` | `SRC-RISK-REGISTER` | SR-001 through SR-006 | all residual ratings NOT_ASSESSED, all acceptance references null, release NOT_ELIGIBLE | `CTL-P01, CTL-P02, CTL-P03, CTL-P05, CTL-P06, CTL-D01, CTL-D03, CTL-R01, CTL-R02, CTL-R03, CTL-R05` / `THR-001, THR-002, THR-003, THR-004, THR-005, THR-006, THR-007, THR-008, THR-009, THR-010` |
| `EV-08` | W4 terminal predecessor boundary | `PREDECESSOR_BOUND` | `SRC-W4-RECEIPT` | terminal W4 receipt SHA plus W4 validation/delta bindings | formal 279 NOT_RUN, five release gates NOT_RUN and unwaived, release NOT_ELIGIBLE | `CTL-P09, CTL-D04` / `THR-010` |
| `EV-09` | normalized actual security scan findings | `PATH_ONLY_FORWARD_REFERENCE` | `SRC-SCAN-SUCCESSOR` | future security-scan-current-state.json | existence not claimed, sha256 not claimed, finding status, disposition and closure not claimed | `CTL-D02, CTL-R04, CTL-R05` / `THR-001, THR-002, THR-003, THR-004, THR-005, THR-006, THR-007, THR-008, THR-009, THR-010` |

## SEC03 six-risk add-only register

Source register SHA: `2a428d6aaee5eee214f36e6842ecc929df73379cd3398b7af3f4d24bc6a321cc`. Source state remains `DRAFT / NOT_APPROVED / NOT_ELIGIBLE`; all six remain `OPEN`, residual `NOT_ASSESSED`, acceptance `null`.

| Risk | Preserved title | L / I / inherent | Owner | Current exposure | Scenarios / controls / evidence | Treatment | Due / expiry |
|---|---|---|---|---|---|---|---|---|
| `SR-001` | 무가림 원본의 유출·목적 외 접근 | `MEDIUM` / `CRITICAL` / `CRITICAL` | 보안·개인정보책임자 | OPEN: source controls exist, but production deployment, key separation, backup deletion, history secret scan and independent review are not evidenced | `THR-001, THR-007, THR-008, THR-009, THR-010` / `CTL-P01, CTL-P02, CTL-P08, CTL-P09, CTL-D01, CTL-D02, CTL-D03, CTL-D04, CTL-R02, CTL-R04, CTL-R05` / `EV-01, EV-02, EV-04, EV-06, EV-07, EV-08, EV-09` | `MITIGATE` `PROPOSED_NOT_APPROVED`; residual `NOT_ASSESSED`; acceptance `null` | 보안·개인정보책임자 `2026-07-31` / `2026-08-31` |
| `SR-002` | 단일 관리자 휴대전화 분실·복구 실패 | `MEDIUM` / `HIGH` / `HIGH` | 프로젝트책임자 | OPEN: MFA/high-risk action source controls exist; actual device, remote revocation and recovery drill evidence are NOT_RUN | `THR-002, THR-007` / `CTL-P03, CTL-P08, CTL-D01, CTL-D02, CTL-R01, CTL-R05` / `EV-01, EV-02, EV-04, EV-06, EV-07, EV-09` | `MITIGATE` `PROPOSED_NOT_APPROVED`; residual `NOT_ASSESSED`; acceptance `null` | 프로젝트책임자 `2026-08-03` / `2026-08-31` |
| `SR-003` | 신고 중복·스팸·관리자 오판 | `MEDIUM` / `HIGH` / `HIGH` | 보안·개인정보책임자 | OPEN: idempotency, rate limiting and audit-oriented controls are source-bound; formal abuse tests, production alerting and adjudication review are not evidenced | `THR-003` / `CTL-P03, CTL-P04, CTL-D01, CTL-D02, CTL-R05` / `EV-01, EV-02, EV-07, EV-09` | `MITIGATE` `PROPOSED_NOT_APPROVED`; residual `NOT_ASSESSED`; acceptance `null` | 보안·개인정보책임자 `2026-08-05` / `2026-08-31` |
| `SR-004` | 휴대전화 대기자료 포화·유실 | `HIGH` / `HIGH` / `HIGH` | 기술책임자 | OPEN: prioritization policy exists, but supported-device capacity, byte-limit gate and recovery behavior are NOT_RUN | `THR-004` / `CTL-P04, CTL-P05, CTL-D01, CTL-D02, CTL-R02` / `EV-01, EV-02, EV-07, EV-09` | `MITIGATE` `PROPOSED_NOT_APPROVED`; residual `NOT_ASSESSED`; acceptance `null` | 기술책임자 `2026-07-31` / `2026-08-31` |
| `SR-005` | TMAP 장애·오래된 경로 안내 | `MEDIUM` / `CRITICAL` / `CRITICAL` | 접근성·안전책임자 | OPEN: fail-safe policy and TLS source validation exist; live TMAP, actual-device E2E and signed model/config delivery are NOT_RUN or NOT_ASSESSED | `THR-005, THR-008` / `CTL-P06, CTL-P07, CTL-P08, CTL-P09, CTL-D01, CTL-D02, CTL-D04, CTL-R03, CTL-R04, CTL-R05` / `EV-01, EV-02, EV-03, EV-05, EV-06, EV-07, EV-09` | `MITIGATE` `PROPOSED_NOT_APPROVED`; residual `NOT_ASSESSED`; acceptance `null` | 접근성·안전책임자 `2026-07-31` / `2026-08-31` |
| `SR-006` | FP-035 이동통신망 정규화 지시의 묶음 승인 대기 | `MEDIUM` / `HIGH` / `HIGH` | 제품책임자 | OPEN: source policy is explicit and preserved, but bundled approval, current formal tests and actual-device network behavior are NOT_RUN | `THR-006, THR-009` / `CTL-P01, CTL-P04, CTL-D01, CTL-D03, CTL-R02, CTL-R05` / `EV-01, EV-02, EV-04, EV-07, EV-09` | `MITIGATE` `PROPOSED_NOT_APPROVED`; residual `NOT_ASSESSED`; acceptance `null` | 제품책임자 `2026-08-03` / `2026-08-31` |

SR-006 normalized policy is preserved unchanged:

- walking: 일반 활동원본은 보행 중 휴대전화에 암호화해 저장하고 서버로 전송하지 않는다.
- stopped_mobile_opted_in: 사용자가 이동통신망 전송을 명시적으로 선택한 경우에만 정지 판정 뒤 이동통신망 전송을 허용한다.
- stopped_mobile_not_opted_in: 이동통신망 전송을 선택하지 않은 사용자는 정지 뒤에도 Wi-Fi에서만 전송한다.

## Verification boundary

| Boundary | Current state |
|---|---|
| `formal_test_count` | `279` |
| `formal_execution_status` | `NOT_RUN` |
| `formal_executed_count` | `0` |
| `formal_pass_count` | `0` |
| `formal_evidence_count` | `0` |
| `formal_empty_evidence_ids_count` | `279` |
| `formal_result_null_count` | `279` |
| `formal_qa_approval_status` | `NOT_APPROVED_PENDING` |
| `actual_device_status` | `NOT_RUN` |
| `full_e2e_status` | `NOT_RUN` |
| `live_tmap_status` | `NOT_RUN` |
| `physical_talkback_status` | `NOT_RUN` |
| `performance_status` | `NOT_RUN` |
| `deployment_status` | `NOT_RUN_CURRENT_GENERATION` |
| `signing_status` | `NOT_ASSESSED` |
| `release_gate_count` | `5` |
| `release_gate_status` | `NOT_RUN` |
| `release_gates_waived` | `false` |
| `security_scan_execution_status` | `COMPLETED_CURRENT_SOURCE_WITH_FINDINGS_PENDING_TRIAGE` |
| `normalized_scan_finding_authority` | `PATH_ONLY_FORWARD_REFERENCE` |
| `release_status` | `NOT_ELIGIBLE` |
| `release_eligible_claimed` | `false` |

## Self-validation and parity

| Check | Status | Count/details |
|---|---|---|
| `exact_artifact_scope` | `PASS` | `3` |
| `duplicate_ids` | `PASS` | `0` |
| `dangling_references` | `PASS` | `0` |
| `orphan_records` | `PASS` | `0` |
| `bidirectional_links` | `PASS` | `0` |
| `control_class_coverage` | `PASS` | `0` |
| `six_risk_add_only_preservation` | `PASS` | `6` |
| `scan_findings_forward_binding` | `PASS` | `boundary predicates` |
| `boundary_preservation` | `PASS` | `boundary predicates` |

- overall: `PASS`
- exact: `3 / unique 3`
- JSON content fingerprint: `3f0b7222ea617bc76944cfd139e619f6bd94bd98a95651045529fcb4e267855f`
- semantic parity fingerprint: `381faa9d084a184f97d9d5d587692935eaed8bf87911334f1bfd6637bb666fdf`
- input-set fingerprint: `3209cb5601b5e190e2d2ffd3a52a057909c43101f5d0a6a0aee6c6c2a1287004`
- product tests: `NOT_RUN_BY_THIS_AUTHORING_TASK`; git: `NOT_USED`; daylog: `NOT_WRITTEN_BY_INSTRUCTION`
<!-- walksafe-json-content-fingerprint: 3f0b7222ea617bc76944cfd139e619f6bd94bd98a95651045529fcb4e267855f -->
<!-- walksafe-semantic-parity-fingerprint: 381faa9d084a184f97d9d5d587692935eaed8bf87911334f1bfd6637bb666fdf -->
