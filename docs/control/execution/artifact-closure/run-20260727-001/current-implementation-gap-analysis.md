# WalkSafe Current Implementation Gap Analysis

- 문서 ID: `WALKSAFE-CURRENT-IMPLEMENTATION-GAP-ANALYSIS-20260727-001`
- 정책: `PB-WALKSAFE-FEATURE-POLICY-1.0.1`
- 소스: `2026-07-26` dirty-worktree exact content snapshot
- 판정: `NOT_APPROVED`, `NOT_ELIGIBLE`
- content review: 최종 resource receipt run-state 반영

## 재평가 원칙

과거 68행의 ID와 추적 관계만 승계했다. `RESOLVED_BY_CURRENT_CODE`는 exact source가 historical assertion을 반증한다는 뜻이다. resource-pilot의 관측 출력 PASS는 execution-time subject identity가 확립되지 않아 machine PASS, formal 시험, 실기기 또는 출시 근거로 사용하지 않는다.

## Coverage

- historical rows: `68`
- unique IDs: `68`
- duplicates: `0`
- missing: `0`
- historical planned tests: `279`

## 현재 분류 집계

| 분류 | 건수 |
|---|---:|
| `EVIDENCE_GAP` | 14 |
| `NOT_APPLICABLE_TO_SUBMISSION_SCOPE` | 0 |
| `PARTIAL` | 39 |
| `RESOLVED_BY_BOUND_EVIDENCE` | 3 |
| `RESOLVED_BY_CURRENT_CODE` | 3 |
| `UNVERIFIED` | 9 |

## Resolved assertion bindings

| Gap / assertion | current assertion | exact source SHA-256 | locator | historical assertion disproved | evidence identity | receipt/check and supporting evidence |
|---|---|---|---|---|---|---|
| `GAP-010` / `GAP-010-A01` | Current product purpose is an Android walking-assistance candidate with an explicit non-substitution safety boundary. | `README.md`<br>`4872261e42e02819ca8f5f7aaea6397b3eefc4538eddec65492134660f890852` | lines 3 and 11-17 | The historical root description treated Web/PWA as the main product and Android as a secondary research path, leaving the approved Android purpose inconsistent. | ID `WALKSAFE-IMPLEMENTATION-REGISTER-REFRESH-RECEIPT-20260727-001`<br>type `walksafe.implementation-register-refresh-receipt.v1.1`<br>subject `IMPLEMENTATION_REGISTER_REFRESH_POLICY_AND_MODULE_BOUNDARY`<br>subject hash kind `ASSERTION_EXACT_FILE_SHA256`<br>`4872261e42e02819ca8f5f7aaea6397b3eefc4538eddec65492134660f890852` | `docs/control/execution/artifact-closure/run-20260727-001/implementation-register-refresh-receipt.json`<br>`c2eac794d4324b32043ef687244c53c07f5419f66799c448b0d7ab1bd710590a`<br>validation_results[check_id=CHK-POLICY-BINDING] and validation_results[check_id=CHK-MODULE-BOUNDARY]: `PASS`<br>REGISTER_REFRESH_PACKET: `docs/control/execution/artifact-closure/run-20260727-001/packets/implementation-register-refresh/evidence.json` / `95026afb0e2d8a6f0ef6758d803c5bf2471d7befce06c26cc1143644f807ec2a`<br>QUALITY_REGISTER_CURRENT_R002: `docs/deliverables/05-implementation/quality-evidence-register-20260727-r002.json` / `d116b59dcb05bf8e6ba93de5c2cbcd3d7d8648433739212b89af502bee3a069d` |
| `GAP-011` / `GAP-011-A01` | The current completion verdict is explicitly formal execution NOT_RUN and release NOT_ELIGIBLE. | `docs/control/execution/artifact-remediation/20260726/w8/release-readiness-current-state.json`<br>`49a5885953c32031eb977181c3d76b359b88eeb2b1b13d66ab320b52974c2879` | formal_execution_status=NOT_RUN; release_status=NOT_ELIGIBLE at the current release-readiness boundary | The historical assessment lacked a bound current release-stage verdict and therefore could not state completion eligibility from evidence. | ID `WS-ARTIFACT-REMEDIATION-W8-IMPLEMENTATION-RECEIPT-20260727-001`<br>type `walksafe.implementation-receipt.v8`<br>subject `W8_RELEASE_READINESS_CURRENT_STATE`<br>subject hash kind `BOUND_RELEASE_CURRENT_STATE_FILE`<br>`49a5885953c32031eb977181c3d76b359b88eeb2b1b13d66ab320b52974c2879` | `docs/control/execution/artifact-remediation/20260726/w8/implementation-receipt.json`<br>`d05e7ebcc2be943f9d9d35d3eb9f163327ba387e694fc6366e439c4dc7aabd05`<br>release_current_state.formal_execution_status=NOT_RUN; release_current_state.release_status=NOT_ELIGIBLE: `BOUND_STATE_RECORDED`<br>RELEASE_READINESS_CURRENT_STATE: `docs/control/execution/artifact-remediation/20260726/w8/release-readiness-current-state.json` / `49a5885953c32031eb977181c3d76b359b88eeb2b1b13d66ab320b52974c2879` |
| `GAP-016` / `GAP-016-A01` | The Android Gradle project includes the user application module. | `apps/android/settings.gradle.kts`<br>`49c08cc67c7d619da7cad48ed033fa8c426ac7a967d697efc1817cb4d5ad46b0` | include(":app") at line 18 | The historical product boundary described Web/PWA as the user product and Android as a non-product research path. | ID `WALKSAFE-IMPLEMENTATION-REGISTER-REFRESH-RECEIPT-20260727-001`<br>type `walksafe.implementation-register-refresh-receipt.v1.1`<br>subject `IMPLEMENTATION_REGISTER_REFRESH_POLICY_AND_MODULE_BOUNDARY`<br>subject hash kind `ASSERTION_EXACT_FILE_SHA256`<br>`49c08cc67c7d619da7cad48ed033fa8c426ac7a967d697efc1817cb4d5ad46b0` | `docs/control/execution/artifact-closure/run-20260727-001/implementation-register-refresh-receipt.json`<br>`c2eac794d4324b32043ef687244c53c07f5419f66799c448b0d7ab1bd710590a`<br>validation_results[check_id=CHK-MODULE-BOUNDARY]: `PASS`<br>REGISTER_REFRESH_PACKET: `docs/control/execution/artifact-closure/run-20260727-001/packets/implementation-register-refresh/evidence.json` / `95026afb0e2d8a6f0ef6758d803c5bf2471d7befce06c26cc1143644f807ec2a`<br>QUALITY_REGISTER_CURRENT_R002: `docs/deliverables/05-implementation/quality-evidence-register-20260727-r002.json` / `d116b59dcb05bf8e6ba93de5c2cbcd3d7d8648433739212b89af502bee3a069d` |
| `GAP-016` / `GAP-016-A02` | The user Android application has its own product application identifier. | `apps/android/app/build.gradle.kts`<br>`cbc884f8c17f21748c028afee38ffb7e8151ca9404655906d76b7cb9de3de160` | defaultConfig.applicationId=kr.co.hanium.dreamup.walksafe at line 56 | The historical assertion did not have a bound Android product application identity. | ID `WALKSAFE-IMPLEMENTATION-REGISTER-REFRESH-RECEIPT-20260727-001`<br>type `walksafe.implementation-register-refresh-receipt.v1.1`<br>subject `IMPLEMENTATION_REGISTER_REFRESH_POLICY_AND_MODULE_BOUNDARY`<br>subject hash kind `ASSERTION_EXACT_FILE_SHA256`<br>`cbc884f8c17f21748c028afee38ffb7e8151ca9404655906d76b7cb9de3de160` | `docs/control/execution/artifact-closure/run-20260727-001/implementation-register-refresh-receipt.json`<br>`c2eac794d4324b32043ef687244c53c07f5419f66799c448b0d7ab1bd710590a`<br>outputs[output_id=MODULE_REGISTER_SUCCESSOR] plus validation_results[check_id=CHK-MODULE-BOUNDARY]: `PASS`<br>REGISTER_REFRESH_PACKET: `docs/control/execution/artifact-closure/run-20260727-001/packets/implementation-register-refresh/evidence.json` / `95026afb0e2d8a6f0ef6758d803c5bf2471d7befce06c26cc1143644f807ec2a`<br>QUALITY_REGISTER_CURRENT_R002: `docs/deliverables/05-implementation/quality-evidence-register-20260727-r002.json` / `d116b59dcb05bf8e6ba93de5c2cbcd3d7d8648433739212b89af502bee3a069d` |
| `GAP-018` / `GAP-018-A01` | Web/PWA is explicitly classified as LEGACY_REFERENCE_ONLY and is not a current WalkSafe product. | `apps/web/README.md`<br>`939c366d0b4171c76a35975f9ad5a0c2e7e5f1f3bedbaa1c02db1fce2814a2b3` | lines 1-7 and 44-50 | The historical Web README called Web/PWA the current main user application. | ID `WALKSAFE-IMPLEMENTATION-REGISTER-REFRESH-RECEIPT-20260727-001`<br>type `walksafe.implementation-register-refresh-receipt.v1.1`<br>subject `IMPLEMENTATION_REGISTER_REFRESH_POLICY_AND_MODULE_BOUNDARY`<br>subject hash kind `ASSERTION_EXACT_FILE_SHA256`<br>`939c366d0b4171c76a35975f9ad5a0c2e7e5f1f3bedbaa1c02db1fce2814a2b3` | `docs/control/execution/artifact-closure/run-20260727-001/implementation-register-refresh-receipt.json`<br>`c2eac794d4324b32043ef687244c53c07f5419f66799c448b0d7ab1bd710590a`<br>validation_results[check_id=CHK-MODULE-BOUNDARY]: `PASS`<br>REGISTER_REFRESH_PACKET: `docs/control/execution/artifact-closure/run-20260727-001/packets/implementation-register-refresh/evidence.json` / `95026afb0e2d8a6f0ef6758d803c5bf2471d7befce06c26cc1143644f807ec2a`<br>QUALITY_REGISTER_CURRENT_R002: `docs/deliverables/05-implementation/quality-evidence-register-20260727-r002.json` / `d116b59dcb05bf8e6ba93de5c2cbcd3d7d8648433739212b89af502bee3a069d` |
| `GAP-018` / `GAP-018-A02` | The controlled product-boundary configuration prohibits legacy Next fallback and fixes legacy Web to a non-product role. | `configs/walksafe_product_boundary_20260722.json`<br>`976547476d61d789c7a453b16c361a40c8fc25719d57215d9d4c10f0e99537a0` | legacy_next_fallback=PROHIBITED at line 154; legacy_web.product_role=LEGACY_REFERENCE_ONLY at lines 158-174 | The historical boundary allowed Web implementation evidence to conflict with the Android-only approved product boundary. | ID `WALKSAFE-IMPLEMENTATION-REGISTER-REFRESH-RECEIPT-20260727-001`<br>type `walksafe.implementation-register-refresh-receipt.v1.1`<br>subject `IMPLEMENTATION_REGISTER_REFRESH_POLICY_AND_MODULE_BOUNDARY`<br>subject hash kind `ASSERTION_EXACT_FILE_SHA256`<br>`976547476d61d789c7a453b16c361a40c8fc25719d57215d9d4c10f0e99537a0` | `docs/control/execution/artifact-closure/run-20260727-001/implementation-register-refresh-receipt.json`<br>`c2eac794d4324b32043ef687244c53c07f5419f66799c448b0d7ab1bd710590a`<br>validation_results[check_id=CHK-POLICY-BINDING] and validation_results[check_id=CHK-MODULE-BOUNDARY]: `PASS`<br>REGISTER_REFRESH_PACKET: `docs/control/execution/artifact-closure/run-20260727-001/packets/implementation-register-refresh/evidence.json` / `95026afb0e2d8a6f0ef6758d803c5bf2471d7befce06c26cc1143644f807ec2a`<br>QUALITY_REGISTER_CURRENT_R002: `docs/deliverables/05-implementation/quality-evidence-register-20260727-r002.json` / `d116b59dcb05bf8e6ba93de5c2cbcd3d7d8648433739212b89af502bee3a069d` |
| `GAP-022` / `GAP-022-A01` | Android first-run code maintains four independent integrated-consent selections and requires server confirmation before protected use. | `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt`<br>`a1b6b2a1853f41c538821b2a05345c94cd4b8f12a8e24d2f375b5044e0cd7b43` | firstRunLocalRequest("integrated_consent"); applyIntegratedConsentConfirmation; consentConfirmation checks at lines 2979-3030 and 12421-12540 | The historical implementation had only an in-memory report consent state and no integrated first-run consent confirmation path. | ID `PATH:docs/control/execution/artifact-closure/run-20260727-001/resource-pilot/android-user-unit-fresh-receipt.json`<br>type `walksafe.resource-pilot-diagnostic-observation.v2`<br>subject `apps/android/app`<br>subject hash kind `POST_HOC_CURRENT_SOURCE_MANIFEST_NOT_EXECUTION_TIME_PROOF`<br>`c99fe6e6f009d084e688995ca9e19f083065d25af6a830785ee7f55d75bb5913` | `docs/control/execution/artifact-closure/run-20260727-001/resource-pilot/android-user-unit-fresh-receipt.json`<br>`22335b7c941fa83ffcba6489459b76880c8eda5ae134b1897a4274a1aa44923c`<br>result: tests=728, passed=728, failures=0, errors=0, skipped=0; decision.observed_internal_test_output=PASS; decision.admissible_current_subject_test_result=NOT_ESTABLISHED; decision.admission_status=CONDITIONAL_OBSERVATION_ONLY: `OBSERVED_OUTPUT_PASS_SUBJECT_IDENTITY_NOT_ESTABLISHED`<br>RAW_LOG: `docs/control/execution/artifact-closure/run-20260727-001/resource-pilot/android-user-unit-fresh.log` / `22a6759067a3d2a34a43cd9e10b3e098eff85f2386c468ebe1a8923ff7576ba0`<br>SUBJECT_MANIFEST_DIGEST: `android-user-unit-fresh-receipt.json subject_manifest_binding.sha256` / `c99fe6e6f009d084e688995ca9e19f083065d25af6a830785ee7f55d75bb5913`<br>XML_SUITE_MANIFEST_DIGEST: `android-user-unit-fresh-receipt.json result.xml_manifest_sha256` / `becc5d0427c46aaccade8ab46ff01bd32dc02842b90c9930c420564e9aec58a0`<br>ASSERTION_XML_SUITE: `apps/android/app/build/test-results/testDebugUnitTest/TEST-kr.co.hanium.dreamup.walksafe.MainActivityIntegratedConsentStaticTest.xml` / `8aac72712fe1fed335f8de5fe3bd097ada4c70d0aeaed2fb264e66dd13c1851e` |
| `GAP-022` / `GAP-022-A02` | The independent Android gateway implements versioned integrated-consent storage, confirmation and authorization. | `apps/android-gateway/src/integrated-consent.ts`<br>`4a47c9fc59c054251f1aafe0a31b9d76d08687eab40b79955d8059feba1b367d` | INTEGRATED_CONSENT_CONTROL; IntegratedConsentConfirmation; authorizeIntegratedConsentRequest | The historical implementation had no protected server-side integrated-consent ledger or authorization boundary. | ID `W3-REUSE-ANDROID-GATEWAY-NODE`<br>type `walksafe.w3-integration-command-receipt.v1`<br>subject `MOD-ANDROID-GATEWAY`<br>subject hash kind `ANDROID_GATEWAY_CONTENT_SET`<br>`9956340a4d695d21c8449e1e8fd2ebf1924573977bf3346f7c449fd6b5bbea15` | `docs/control/execution/artifact-remediation/20260726/w3/integration-run-20260726-001/receipts/gateway-npm-test.json`<br>`79cae4f38d8ccd990aa99b1f15a0a80ca40838e868d9e41cc5e2e02b2901ab6d`<br>quality evidence_rows[evidence_id=W3-REUSE-ANDROID-GATEWAY-NODE]; command receipt npm test; result passed_count=62 failed_count=0: `PASS_REUSED_EXACT_SOURCE`<br>RAW_LOG: `docs/control/execution/artifact-remediation/20260726/w3/integration-run-20260726-001/logs/gateway-npm-test.log` / `00bb601dc150b5004e1792d160237cbdd1f2c9dafdbf9558e15d9faea8bd0ab2`<br>QUALITY_REGISTER_CURRENT_R002: `docs/deliverables/05-implementation/quality-evidence-register-20260727-r002.json` / `d116b59dcb05bf8e6ba93de5c2cbcd3d7d8648433739212b89af502bee3a069d`<br>REGISTER_REFRESH_PACKET: `docs/control/execution/artifact-closure/run-20260727-001/packets/implementation-register-refresh/evidence.json` / `95026afb0e2d8a6f0ef6758d803c5bf2471d7befce06c26cc1143644f807ec2a` |
| `GAP-026` / `GAP-026-A01` | A resumed walking session remains paused until explicit recognized START confirmation passes current recheck state. | `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt`<br>`a1b6b2a1853f41c538821b2a05345c94cd4b8f12a8e24d2f375b5044e0cd7b43` | onResume at line 3687; requestWalkSessionResumeConfirmation at line 8686; handleWalkSessionResumeRecognition at line 8773 | The historical implementation resumed walking sensors immediately after returning to the activity without a complete recheck and user confirmation. | ID `PATH:docs/control/execution/artifact-closure/run-20260727-001/resource-pilot/android-user-unit-fresh-receipt.json`<br>type `walksafe.resource-pilot-diagnostic-observation.v2`<br>subject `apps/android/app`<br>subject hash kind `POST_HOC_CURRENT_SOURCE_MANIFEST_NOT_EXECUTION_TIME_PROOF`<br>`c99fe6e6f009d084e688995ca9e19f083065d25af6a830785ee7f55d75bb5913` | `docs/control/execution/artifact-closure/run-20260727-001/resource-pilot/android-user-unit-fresh-receipt.json`<br>`22335b7c941fa83ffcba6489459b76880c8eda5ae134b1897a4274a1aa44923c`<br>result: tests=728, passed=728, failures=0, errors=0, skipped=0; decision.observed_internal_test_output=PASS; decision.admissible_current_subject_test_result=NOT_ESTABLISHED; decision.admission_status=CONDITIONAL_OBSERVATION_ONLY: `OBSERVED_OUTPUT_PASS_SUBJECT_IDENTITY_NOT_ESTABLISHED`<br>RAW_LOG: `docs/control/execution/artifact-closure/run-20260727-001/resource-pilot/android-user-unit-fresh.log` / `22a6759067a3d2a34a43cd9e10b3e098eff85f2386c468ebe1a8923ff7576ba0`<br>SUBJECT_MANIFEST_DIGEST: `android-user-unit-fresh-receipt.json subject_manifest_binding.sha256` / `c99fe6e6f009d084e688995ca9e19f083065d25af6a830785ee7f55d75bb5913`<br>XML_SUITE_MANIFEST_DIGEST: `android-user-unit-fresh-receipt.json result.xml_manifest_sha256` / `becc5d0427c46aaccade8ab46ff01bd32dc02842b90c9930c420564e9aec58a0`<br>ASSERTION_XML_SUITE: `apps/android/app/build/test-results/testDebugUnitTest/TEST-kr.co.hanium.dreamup.walksafe.MainActivityWalkSessionLifecycleStaticTest.xml` / `a2851b2080a5c86d31574c0cae090c83aa7ad4a134375ebdbeb68704b8ffa5d0`<br>ASSERTION_XML_SUITE: `apps/android/app/build/test-results/testDebugUnitTest/TEST-kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycleFp018Test.xml` / `d1ac889663a9b27a5773d2cf36b350f33e25eb5fd317ca0335f439c1ceed6bae` |

## Resource observation boundary

- current receipt physical SHA: `22335b7c941fa83ffcba6489459b76880c8eda5ae134b1897a4274a1aa44923c`
- receipt integrity SHA: `e2f296fec46f4f8db84605078c64a81964f963eafb642ee4149624f15b8537a0`
- post-hoc subject manifest SHA: `c99fe6e6f009d084e688995ca9e19f083065d25af6a830785ee7f55d75bb5913` (`NOT_EXECUTION_TIME_PROOF`)
- observed output: `PASS`
- admissible current-subject result: `NOT_ESTABLISHED`
- normalized gap-analysis result: `OBSERVED_OUTPUT_PASS_SUBJECT_IDENTITY_NOT_ESTABLISHED`
- admission: `CONDITIONAL_OBSERVATION_ONLY`; scope: `RESOURCE_ADMISSION_ONLY`; evidence grade: `false`
- code resolution과 formal279·actual-device·production·release 미완료를 분리한다.

## GAP-044 policy approval correction

- FP-035는 `PB-WALKSAFE-FEATURE-POLICY-1.0.1`에서 이미 승인됐다.
- `walksafe-artifact-baseline-application-receipt-20260722-r001.json`의 transaction은 `COMMITTED`다.
- 재승인은 요구하지 않는다.
- 구현 적합성, phone queue, selected Network/mobile branch, Android→Gateway→Backend integration tests는 계속 `OPEN`이다.

## 68행 재평가

| Gap | 요구사항 | 제목 | 현재 분류 | 자원 | 다음 조치 |
|---|---|---|---|---|---|
| `GAP-001` | `RQ-NPC-RAW-ORIGINAL-COLLECTION-001` | 활성 보행 중 원본 수집 | `PARTIAL` | `INTERNAL_ENGINEERING` | Release 원본 저장 경로와 동의 철회 시 삭제 경계를 완성하고 TC-NPC-RAW-ORIGINAL-COLLECTION-01을 실행한다. |
| `GAP-002` | `RQ-NPC-DATA-LIFECYCLE-001` | 보존기간과 삭제기한 | `PARTIAL` | `INTERNAL_ENGINEERING` | 휴대전화·gateway·backend·backup별 보존 및 삭제 receipt를 하나의 lifecycle evidence로 결속한다. |
| `GAP-003` | `RQ-NPC-SERVER-STORAGE-CAPACITY-001` | 서버 원본·백업 용량과 저장비 | `UNVERIFIED` | `EXTERNAL_SERVICE_MEASUREMENT` | 실제 object storage 용량·backup 성장·월 비용을 측정해 GATE-CLOUD-COST-MEASUREMENT를 판정한다. |
| `GAP-004` | `RQ-NPC-PHONE-QUEUE-CAPACITY-001` | 휴대전화 대기자료 용량 | `UNVERIFIED` | `EXTERNAL_SERVICE_MEASUREMENT` | 휴대전화 대기함의 구현 여부와 최대 byte 수를 확정하고 실제 한도 시험을 실행한다. |
| `GAP-005` | `RQ-NPC-AUTO-REPORT-001` | 손상 점자블록 자동신고 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-NPC-AUTO-REPORT-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-006` | `RQ-NPC-PERMISSION-SESSION-LIFECYCLE-001` | 권한·로그인·동의 상태 분리 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-NPC-PERMISSION-SESSION-LIFECYCLE-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-007` | `RQ-NPC-NAVIGATION-ROUTE-DIRECTION-001` | 경로·진행방향·보폭 책임 분리 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-NPC-NAVIGATION-ROUTE-DIRECTION-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-008` | `RQ-NPC-SINGLE-ADMIN-RECOVERY-001` | 한 명의 관리자와 계정 복구 | `PARTIAL` | `INTERNAL_ENGINEERING` | 관리자 운영 workflow를 보안 경계에 연결하고 별도 기기 분실 복구훈련을 실행한다. |
| `GAP-009` | `RQ-NPC-SERVER-CAPACITY-STATE-SYNC-001` | 서버 용량상태를 휴대전화에 전달 | `UNVERIFIED` | `EXTERNAL_SERVICE_MEASUREMENT` | 서버 용량 상태 계약과 Android fail-closed 소비 경로를 구현·측정한다. |
| `GAP-010` | `RQ-FP-001-001` | 제품 목적과 우선순위 | `RESOLVED_BY_CURRENT_CODE` | `INTERNAL_ENGINEERING` | RQ-FP-001-001의 현재 source binding을 successor RTM에 유지하고 계획 시험을 실행한다. |
| `GAP-011` | `RQ-FP-002-001` | 현재 출시 단계와 완료 판정 | `RESOLVED_BY_BOUND_EVIDENCE` | `INTERNAL_ENGINEERING` | 현재 NOT_ELIGIBLE 판정을 유지하고 formal 279, signing, device, deployment evidence 전에는 상태를 올리지 않는다. |
| `GAP-012` | `RQ-FP-003-001` | 단일 책임자 의사결정 | `PARTIAL` | `OWNER_APPROVAL` | 단일 책임자와 대리·복구 권한을 승인 가능한 owner record에 결속한다. |
| `GAP-013` | `RQ-FP-004-001` | 우선 사용자 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-004-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-014` | `RQ-FP-005-001` | 공식 사용환경과 횡단보도 | `EVIDENCE_GAP` | `ACTUAL_DEVICE_OR_USER` | 승인 사용환경과 횡단보도 시나리오를 실제 지원 기기에서 실행한다. |
| `GAP-015` | `RQ-FP-006-001` | 단독 보행 목표와 휴대전화 장착 | `EVIDENCE_GAP` | `ACTUAL_DEVICE_OR_USER` | 장착 방식과 단독 보행 제한을 실제 사용자·기기 시험으로 검증한다. |
| `GAP-016` | `RQ-FP-007-001` | 사용자용 안드로이드 전용 앱 | `RESOLVED_BY_BOUND_EVIDENCE` | `INTERNAL_ENGINEERING` | RQ-FP-007-001의 bound evidence를 successor RTM에 유지하되 formal 또는 release PASS로 확대하지 않는다. |
| `GAP-017` | `RQ-FP-008-001` | 관리자용 안드로이드 앱 | `PARTIAL` | `INTERNAL_ENGINEERING` | 관리자 앱의 신고 검수·기관 전달·피드백 workflow를 구현하고 formal test에 연결한다. |
| `GAP-018` | `RQ-FP-009-001` | 지원 기기와 과거 웹 버전 | `RESOLVED_BY_BOUND_EVIDENCE` | `INTERNAL_ENGINEERING` | RQ-FP-009-001의 bound evidence를 successor RTM에 유지하되 formal 또는 release PASS로 확대하지 않는다. |
| `GAP-019` | `RQ-FP-010-001` | 첫 실행과 회원가입 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-010-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-020` | `RQ-FP-011-001` | 장기 로그인 유지 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-011-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-021` | `RQ-FP-012-001` | 여러 기기 동시 로그인 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-012-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-022` | `RQ-FP-013-001` | 첫 실행 통합 동의 | `RESOLVED_BY_CURRENT_CODE` | `INTERNAL_ENGINEERING` | 현행 통합 동의 코드 binding을 successor RTM과 formal case에 유지한다. |
| `GAP-023` | `RQ-FP-014-001` | 권한 거부·철회 시 기능별 처리 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-014-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-024` | `RQ-FP-015-001` | 사용 중 철회·계정 삭제 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-015-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-025` | `RQ-FP-016-001` | 로그인 뒤 카메라 중심 무버튼 화면 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-016-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-026` | `RQ-FP-017-001` | 보행 자동 시작·다른 화면 전환 시 일시중지 | `RESOLVED_BY_CURRENT_CODE` | `INTERNAL_ENGINEERING` | 사용자 확인 재개 경로를 successor RTM에 결속하고 actual-device lifecycle case를 실행한다. |
| `GAP-027` | `RQ-FP-018-001` | 보행 상태·종료·복구 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-018-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-028` | `RQ-FP-019-001` | 휴대전화 내부 물체 후보 탐지 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-019-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-029` | `RQ-FP-020-001` | 위험 등급·우선순위·행동 안내 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-020-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-030` | `RQ-FP-021-001` | 탐지 대상 종류·카메라 품질·거리 근거 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-021-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-031` | `RQ-FP-022-001` | 티맵 목적지 검색과 큰 경로 | `EVIDENCE_GAP` | `ACTUAL_DEVICE_OR_USER` | live TMAP 목적지 검색·경로 검증을 실제 네트워크와 지원 기기에서 실행한다. |
| `GAP-032` | `RQ-FP-023-001` | 경로 이탈·재탐색·티맵 장애 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-023-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-033` | `RQ-FP-024-001` | 점자블록 휴대전화 내부 경로 보조와 손상 처리 | `EVIDENCE_GAP` | `ACTUAL_DEVICE_OR_USER` | 점자블록 local guidance를 실제 카메라·depth·경로 조건에서 검증한다. |
| `GAP-034` | `RQ-FP-025-001` | 길라잡이 호출어와 휴대전화 내부 음성인식 | `PARTIAL` | `ACTUAL_DEVICE_OR_USER` | 길라잡이 호출어와 on-device STT 가용성을 지원 기기별로 검증한다. |
| `GAP-035` | `RQ-FP-026-001` | 음성 명령·확인·중재 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-026-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-036` | `RQ-FP-027-001` | 휴대전화 내부 음성안내·진동 대체 안내 | `EVIDENCE_GAP` | `ACTUAL_DEVICE_OR_USER` | TTS·TalkBack·진동 전달과 실패 안전정지를 실제 기기에서 검증한다. |
| `GAP-037` | `RQ-FP-028-001` | 안드로이드 화면읽기 지원 범위 | `EVIDENCE_GAP` | `ACTUAL_DEVICE_OR_USER` | TalkBack과 화면읽기 흐름을 전맹·저시력 사용자 조건에서 검증한다. |
| `GAP-038` | `RQ-FP-029-001` | 거부·장애 확인창의 접근 가능한 조작 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-029-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-039` | `RQ-FP-030-001` | 화면 미확인 사용과 시각 접근성 | `EVIDENCE_GAP` | `ACTUAL_DEVICE_OR_USER` | 화면 미확인 전체 task flow를 실제 접근성 사용자 시험으로 검증한다. |
| `GAP-040` | `RQ-FP-031-001` | 손상 점자블록 자동신고 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-031-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-041` | `RQ-FP-032-001` | 신고 중복·재시도·처리 단계 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-032-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-042` | `RQ-FP-033-001` | 관리자 검수·기관 전달·사용자 피드백 | `PARTIAL` | `INTERNAL_ENGINEERING` | 관리자 검수·기관 전달·사용자 피드백 상태기계와 audit receipt를 완성한다. |
| `GAP-043` | `RQ-FP-034-001` | 수집할 사용자 활동자료 목록 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-034-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-044` | `RQ-FP-035-001` | 보행 중 휴대전화 저장·정지 시 서버 전송 | `PARTIAL` | `INTERNAL_AND_EXTERNAL_EVIDENCE` | 정책 1.0.1 승인과 COMMITTED application receipt 결속을 유지하고 재승인을 요구하지 않는다. 구현 적합성, phone queue, selected Network/mobile branch, Android→Gateway→Backend integration tests를 완료한다. |
| `GAP-045` | `RQ-FP-036-001` | 서버 확인 뒤 휴대전화 삭제·서버 보존 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-036-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-046` | `RQ-FP-037-001` | 현재 모델의 사용 위치 | `PARTIAL` | `INTERNAL_AND_EXTERNAL_EVIDENCE` | 개발 runtime 위치는 유지하되 승인된 모델 promotion, 독립 평가, actual-device equivalence와 이전 정상 모델 rollback evidence를 확보한다. |
| `GAP-047` | `RQ-FP-038-001` | 서버 재학습·학습자료 안전검사·독립평가 | `PARTIAL` | `INTERNAL_ENGINEERING` | 데이터 안전검사 뒤 독립 정량 평가와 actual-device conversion equivalence를 실행한다. |
| `GAP-048` | `RQ-FP-039-001` | 모델 등록·교체·이전 정상 모델 복구 | `PARTIAL` | `INTERNAL_ENGINEERING` | 승인 모델 promotion과 이전 정상 모델 rollback을 실제 artifact hash로 검증한다. |
| `GAP-049` | `RQ-FP-040-001` | 휴대전화·서버 역할과 하나의 서버 출입구 | `EVIDENCE_GAP` | `INTERNAL_AND_EXTERNAL_EVIDENCE` | 동일 generation에서 Android→Gateway→Backend→PostGIS cross-process 시험을 실행한다. |
| `GAP-050` | `RQ-FP-041-001` | 계정·공간 데이터베이스와 대용량 원본 저장소 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-041-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-051` | `RQ-FP-042-001` | 외부 서비스 비밀키·대기자료·중복방지 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-042-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-052` | `RQ-FP-043-001` | 장애가 계속될 때 멈출 기능 범위 | `EVIDENCE_GAP` | `ACTUAL_DEVICE_OR_USER` | RQ-FP-043-001의 formal·actual-device·cross-process evidence를 해당 경계에서 실행한다. |
| `GAP-053` | `RQ-FP-044-001` | 인터넷이 없거나 일부 기능만 쓸 때의 제한 | `EVIDENCE_GAP` | `ACTUAL_DEVICE_OR_USER` | RQ-FP-044-001의 formal·actual-device·cross-process evidence를 해당 경계에서 실행한다. |
| `GAP-054` | `RQ-FP-045-001` | 저장공간·배터리·발열·복구 | `UNVERIFIED` | `ACTUAL_DEVICE_OR_USER` | 저장공간·배터리·발열 gate의 현재 구현을 확인하고 actual-device 임계값 시험을 실행한다. |
| `GAP-055` | `RQ-FP-046-001` | 개인정보 수집·보존·사용자 권리 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-046-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-056` | `RQ-FP-047-001` | 사용자·관리자 로그인과 권한 분리 | `PARTIAL` | `INTERNAL_ENGINEERING` | RQ-FP-047-001의 남은 workflow를 완성하고 연결된 계획 시험을 실행한다. |
| `GAP-057` | `RQ-FP-048-001` | 암호화·접속정보·보안사고 | `PARTIAL` | `SECURITY_REVIEW` | release secrets, TLS, incident response, signing 경계를 정식 보안 검토에 결속한다. |
| `GAP-058` | `RQ-FP-049-001` | 출시 전에 반드시 통과할 기능·안전·접근성 시험 | `EVIDENCE_GAP` | `ACTUAL_DEVICE_OR_USER` | 계획된 formal 279 cases를 승인 baseline에서 실행한다. |
| `GAP-059` | `RQ-FP-050-001` | 휴대전화 전체 성능·현장 사용자 시험 | `EVIDENCE_GAP` | `ACTUAL_DEVICE_OR_USER` | 지원 기기의 성능·카메라·음성·접근성과 현장 사용자 시험을 실행한다. |
| `GAP-060` | `RQ-FP-051-001` | 앱스토어 배포·버전 일치·업데이트·이전 정상판 복구 | `EVIDENCE_GAP` | `RELEASE_OR_CLOUD_OPERATIONS` | signed installable build, store version, update와 rollback을 같은 release generation에서 검증한다. |
| `GAP-061` | `RQ-FP-052-001` | 운영 상태 확인·알림·단일 관리자 지원 | `PARTIAL` | `INTERNAL_ENGINEERING` | 실제 monitoring·alert 경로와 지정 관리자 지원 절차를 배포 환경에서 검증한다. |
| `GAP-062` | `RQ-FP-053-001` | 백업·복원·비용·용량 운영 | `EVIDENCE_GAP` | `RELEASE_OR_CLOUD_OPERATIONS` | production backup·restore·rollback·capacity·cost evidence를 실행한다. |
| `GAP-063` | `RQ-FP-054-001` | 유지보수·안전한 배포·서비스 종료 | `PARTIAL` | `RELEASE_OR_CLOUD_OPERATIONS` | 유지보수, 안전 배포, 종료·자료처분 절차의 승인과 실행 receipt를 확보한다. |
| `GAP-064` | `RQ-GATE-PHONE-QUEUE-BYTE-LIMIT-001` | 휴대전화 대기자료의 실제 용량 한도 | `UNVERIFIED` | `EXTERNAL_SERVICE_MEASUREMENT` | 휴대전화 대기자료의 실제 byte 한도를 정하고 측정 receipt를 만든다. |
| `GAP-065` | `RQ-GATE-SERVER-CAPACITY-STATE-CONTRACT-001` | 서버 용량상태를 휴대전화에 전달하는 규칙 | `UNVERIFIED` | `EXTERNAL_SERVICE_MEASUREMENT` | 서버 용량상태 계약과 Android 동기화 실패 동작을 승인·시험한다. |
| `GAP-066` | `RQ-GATE-RAW-COLLECTION-RELEASE-REVIEW-001` | 무가림 원본 수집의 출시 전 독립 검토 | `UNVERIFIED` | `INDEPENDENT_REVIEW` | 무가림 원본 수집을 독립 검토하고 승인 또는 거부 결정을 기록한다. |
| `GAP-067` | `RQ-GATE-CLOUD-COST-MEASUREMENT-001` | 실제 클라우드 저장비 측정 | `UNVERIFIED` | `EXTERNAL_SERVICE_MEASUREMENT` | 실제 cloud 저장·backup 부하로 월 비용과 한도를 측정한다. |
| `GAP-068` | `RQ-GATE-SINGLE-ADMIN-RECOVERY-DRILL-001` | 관리자 휴대전화 분실 복구훈련 | `UNVERIFIED` | `OWNER_OPERATIONAL_DRILL` | 별도 관리자 기기 분실·세션 폐기·복구훈련을 실행하고 승인 receipt를 만든다. |

## 미해소 공통 경계

- formal 279: `NOT_RUN`, formal PASS `0`
- actual-device interactive flow: `NOT_RUN`
- Android→Gateway→Backend→PostGIS cross-process: `NOT_RUN`
- reproducible build·attestation·signing·deployment: 미완료
- model approval: `NOT_APPROVED`, release: `NOT_ELIGIBLE`
- 5개 release gate: 모두 `UNVERIFIED`; 제출 범위 N/A로 임의 전환하지 않음

상세 source/evidence refs와 affected deliverable IDs는 JSON 정본에 기록한다.
