# W1 요구사항 현재상태 후속 보완

- 문서 ID: `WS-ARTIFACT-REMEDIATION-W1-REQ-CURRENT-STATE-20260726-001`
- 상태: `DRAFT_SUCCESSOR_SUPPLEMENT`
- 범위: `DLV-REQ-03`, `DLV-REQ-06`, `DLV-REQ-16`, `DLV-REQ-17`, `DLV-REQ-18`, `DLV-REQ-19`
- JSON 내용 SHA-256: `94d3cf6d5567d49fe8a7909dbdca12bd1676e471ed65440512f28620f99b6d63`
- compact decision successor: `4a448f65280c2cd8cd850a749434f4e124769e47b7b84e31e2e14c58328d2faf`

## 비파괴·주장 경계

이 문서는 기존 요구사항 정본, 관리대장, 변경이력, checkpoint, daylog, git 상태를 수정하지 않는 add-only successor supplement다. REQ-17 산출물 유형 wrapper의 등록 상태와 underlying 68개 요구 집합의 승인 상태를 구분한다. 이번 dirty-worktree 요구 집합 successor는 `DRAFT / NOT_APPROVED / NOT_BASELINED`이며 predecessor를 supersede하지 않는다.

내부 타깃 단위시험은 `17 + 5 = 22 PASS`지만 `pass_claimed=false`다. 정식시험 279건, 실기기 센서, 실제 망 전환·소켓 취소, 5초 현장 보정은 `NOT_RUN`; 5개 release gate는 미실행·미면제이고 release는 `NOT_ELIGIBLE`이다.

## REQ-16 exact-68 RTM successor overlay

- canonical source: `docs/deliverables/03-requirements/rtm.json`
- source SHA-256: `1d73d1e6e0a47ea3833df779c945397d799b14e9b26fdac4bb577197a660a7bd`
- requirement count/version: `68 / 0.2.0`
- requirement ID-set SHA-256: `05c859b87e32eaa8beb0441d2ce902234f9d2110330c4da3e6f52ba3b75fdd85`
- embedded row projection SHA-256: `f054aea4cbd51b1ed39b4f58283c45058e55f02f78b86fc63a29845b8a42cef5`
- row 상태: `DRAFT / NOT_APPROVED / NOT_BASELINED / formal NOT_RUN`

| 차원 | LINKED | UNLINKED | 실제 edge | 판정 근거 |
|---|---:|---:|---:|---|
| design | 68 | 0 | 716 | canonical field only |
| module | 0 | 68 | 0 | canonical field only |
| config | 0 | 68 | 0 | canonical field only |
| test | 68 | 0 | 279 | canonical field only |
| evidence | 13 | 55 | 24 | canonical field only |

모든 차원은 `LINKED + UNLINKED = 68`이다. design/test는 68행 모두 명시 참조가 있고, module/config는 canonical RTM에 해당 필드가 없으므로 68행 모두 `UNLINKED`다. `code_trace`는 별도 observed-code 차원으로 보존하며 module/config로 재명명하거나 추정하지 않는다.

### 차원별 미연결 요구 ID

#### design

없음


#### module

RQ-FP-001-001, RQ-FP-002-001, RQ-FP-003-001, RQ-FP-004-001, RQ-FP-005-001, RQ-FP-006-001, RQ-FP-007-001, RQ-FP-008-001, RQ-FP-009-001, RQ-FP-010-001, RQ-FP-011-001, RQ-FP-012-001, RQ-FP-013-001, RQ-FP-014-001, RQ-FP-015-001, RQ-FP-016-001, RQ-FP-017-001, RQ-FP-018-001, RQ-FP-019-001, RQ-FP-020-001, RQ-FP-021-001, RQ-FP-022-001, RQ-FP-023-001, RQ-FP-024-001, RQ-FP-025-001, RQ-FP-026-001, RQ-FP-027-001, RQ-FP-028-001, RQ-FP-029-001, RQ-FP-030-001, RQ-FP-031-001, RQ-FP-032-001, RQ-FP-033-001, RQ-FP-034-001, RQ-FP-035-001, RQ-FP-036-001, RQ-FP-037-001, RQ-FP-038-001, RQ-FP-039-001, RQ-FP-040-001, RQ-FP-041-001, RQ-FP-042-001, RQ-FP-043-001, RQ-FP-044-001, RQ-FP-045-001, RQ-FP-046-001, RQ-FP-047-001, RQ-FP-048-001, RQ-FP-049-001, RQ-FP-050-001, RQ-FP-051-001, RQ-FP-052-001, RQ-FP-053-001, RQ-FP-054-001, RQ-GATE-CLOUD-COST-MEASUREMENT-001, RQ-GATE-PHONE-QUEUE-BYTE-LIMIT-001, RQ-GATE-RAW-COLLECTION-RELEASE-REVIEW-001, RQ-GATE-SERVER-CAPACITY-STATE-CONTRACT-001, RQ-GATE-SINGLE-ADMIN-RECOVERY-DRILL-001, RQ-NPC-AUTO-REPORT-001, RQ-NPC-DATA-LIFECYCLE-001, RQ-NPC-NAVIGATION-ROUTE-DIRECTION-001, RQ-NPC-PERMISSION-SESSION-LIFECYCLE-001, RQ-NPC-PHONE-QUEUE-CAPACITY-001, RQ-NPC-RAW-ORIGINAL-COLLECTION-001, RQ-NPC-SERVER-CAPACITY-STATE-SYNC-001, RQ-NPC-SERVER-STORAGE-CAPACITY-001, RQ-NPC-SINGLE-ADMIN-RECOVERY-001


#### config

RQ-FP-001-001, RQ-FP-002-001, RQ-FP-003-001, RQ-FP-004-001, RQ-FP-005-001, RQ-FP-006-001, RQ-FP-007-001, RQ-FP-008-001, RQ-FP-009-001, RQ-FP-010-001, RQ-FP-011-001, RQ-FP-012-001, RQ-FP-013-001, RQ-FP-014-001, RQ-FP-015-001, RQ-FP-016-001, RQ-FP-017-001, RQ-FP-018-001, RQ-FP-019-001, RQ-FP-020-001, RQ-FP-021-001, RQ-FP-022-001, RQ-FP-023-001, RQ-FP-024-001, RQ-FP-025-001, RQ-FP-026-001, RQ-FP-027-001, RQ-FP-028-001, RQ-FP-029-001, RQ-FP-030-001, RQ-FP-031-001, RQ-FP-032-001, RQ-FP-033-001, RQ-FP-034-001, RQ-FP-035-001, RQ-FP-036-001, RQ-FP-037-001, RQ-FP-038-001, RQ-FP-039-001, RQ-FP-040-001, RQ-FP-041-001, RQ-FP-042-001, RQ-FP-043-001, RQ-FP-044-001, RQ-FP-045-001, RQ-FP-046-001, RQ-FP-047-001, RQ-FP-048-001, RQ-FP-049-001, RQ-FP-050-001, RQ-FP-051-001, RQ-FP-052-001, RQ-FP-053-001, RQ-FP-054-001, RQ-GATE-CLOUD-COST-MEASUREMENT-001, RQ-GATE-PHONE-QUEUE-BYTE-LIMIT-001, RQ-GATE-RAW-COLLECTION-RELEASE-REVIEW-001, RQ-GATE-SERVER-CAPACITY-STATE-CONTRACT-001, RQ-GATE-SINGLE-ADMIN-RECOVERY-DRILL-001, RQ-NPC-AUTO-REPORT-001, RQ-NPC-DATA-LIFECYCLE-001, RQ-NPC-NAVIGATION-ROUTE-DIRECTION-001, RQ-NPC-PERMISSION-SESSION-LIFECYCLE-001, RQ-NPC-PHONE-QUEUE-CAPACITY-001, RQ-NPC-RAW-ORIGINAL-COLLECTION-001, RQ-NPC-SERVER-CAPACITY-STATE-SYNC-001, RQ-NPC-SERVER-STORAGE-CAPACITY-001, RQ-NPC-SINGLE-ADMIN-RECOVERY-001


#### test

없음


#### evidence

RQ-FP-001-001, RQ-FP-003-001, RQ-FP-004-001, RQ-FP-007-001, RQ-FP-008-001, RQ-FP-010-001, RQ-FP-011-001, RQ-FP-012-001, RQ-FP-013-001, RQ-FP-014-001, RQ-FP-015-001, RQ-FP-016-001, RQ-FP-017-001, RQ-FP-018-001, RQ-FP-022-001, RQ-FP-023-001, RQ-FP-024-001, RQ-FP-025-001, RQ-FP-026-001, RQ-FP-028-001, RQ-FP-029-001, RQ-FP-030-001, RQ-FP-031-001, RQ-FP-032-001, RQ-FP-033-001, RQ-FP-034-001, RQ-FP-035-001, RQ-FP-036-001, RQ-FP-038-001, RQ-FP-039-001, RQ-FP-040-001, RQ-FP-041-001, RQ-FP-042-001, RQ-FP-043-001, RQ-FP-044-001, RQ-FP-046-001, RQ-FP-047-001, RQ-FP-048-001, RQ-FP-051-001, RQ-FP-053-001, RQ-FP-054-001, RQ-GATE-CLOUD-COST-MEASUREMENT-001, RQ-GATE-PHONE-QUEUE-BYTE-LIMIT-001, RQ-GATE-RAW-COLLECTION-RELEASE-REVIEW-001, RQ-GATE-SERVER-CAPACITY-STATE-CONTRACT-001, RQ-GATE-SINGLE-ADMIN-RECOVERY-DRILL-001, RQ-NPC-AUTO-REPORT-001, RQ-NPC-DATA-LIFECYCLE-001, RQ-NPC-NAVIGATION-ROUTE-DIRECTION-001, RQ-NPC-PERMISSION-SESSION-LIFECYCLE-001, RQ-NPC-PHONE-QUEUE-CAPACITY-001, RQ-NPC-RAW-ORIGINAL-COLLECTION-001, RQ-NPC-SERVER-CAPACITY-STATE-SYNC-001, RQ-NPC-SERVER-STORAGE-CAPACITY-001, RQ-NPC-SINGLE-ADMIN-RECOVERY-001


68개 모든 행의 `design/module/config/test/evidence` 실제 참조, LINKED/UNLINKED, formal 상태와 waiver 참조는 JSON의 `rtm_successor_overlay.rows`에 기록했다. FP-035 행은 policy 1.0.1, compact decision, approval, COMMITTED receipt, 최종 7개 code binding, 22 internal PASS를 추가 결속한다. FP-047 행은 기존 internal PASS / GAP-056 PARTIAL trace를 유지한다. formal 279는 모두 `NOT_RUN`이다.

## REQ-17 non-approved dirty-worktree baseline successor

- artifact-content predecessor baseline: `CB-WALKSAFE-REQ-17-1.0.0` / `requirements-traceability.md` / `ab250dd0b139822aa0d8acbfe0b599b394e48b59556b44048d13403e4c0a9822`
- underlying requirement-set predecessor: `WS-FORMAL-REQ-DRAFT-20260721-001`, proposed `RB-WALKSAFE-REQUIREMENTS-1.0.0`, `NOT_APPROVED / NOT_BASELINED`
- successor: `NOT_APPROVED / NOT_BASELINED / SUPPLEMENTS_DOES_NOT_SUPERSEDE`
- 포함: 68, 제외: 0, 합집합: 68
- 포함 집합 SHA-256: `05c859b87e32eaa8beb0441d2ce902234f9d2110330c4da3e6f52ba3b75fdd85`
- 제외 집합 SHA-256: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` (빈 byte 집합)
- 합집합 SHA-256: `05c859b87e32eaa8beb0441d2ce902234f9d2110330c4da3e6f52ba3b75fdd85`
- open waiver: 0 / `NONE_RECORDED_IN_BOUND_EXACT_68_SCOPE`
- `source_commit=null`, `dirty_worktree_expected=true`, `whole_repository_frozen=false`
- current snapshot: 7 implementation + 2 unit-test source + 2 JUnit XML = 11 paths

제외 0과 waiver 0은 bound exact-68 source scope 안에서만 유효하다. 전역적 부재를 주장하지 않는다. 다섯 gate의 `NOT_RUN / waived=false`는 waiver가 아니다.

## REQ-18 add-only 변경이력

### REQ-CHG-20260726-003 / CR-0002

CR-0002의 원래 `before` 필드는 요구문이 아니라 충돌 설명이므로 아래 세 문장을 분리해 결속한다. 텍스트 SHA는 exact UTF-8 bytes, Unicode normalization 없음, trailing LF 없음 계약이다.

| 구분 | SHA-256 |
|---|---|
| CR 충돌 설명 | `5d574d5db74d4670fdc2004e56dbe9b66a8374f74aed26507127c6a5a3ac57e3` |
| policy 1.0.0 실제 before clause | `b6c4627bc22dbaf326b4fee83e7f4b29cef54ed59ca8d64d41d8968b1cb81c94` |
| 승인된 after normative rule | `bb7220f01d94909e96639923fbe14f89ee8999f03e5ce34c8f4d1cb6bdec40b3` |
| 현재 REQ-03 전체 요구문 | `d5a93d43fbd93ce97d9641e5224ee2511a51cd832f3821ced7881473081dbd8d` |

- 영향 design: `DES-04, DES-13, DES-20` 직접 영향; `DES-09` CR downstream; `DES-06` correction trace 갱신 대상.
- 구현: 7개 code path/SHA 직접 결속.
- 내부시험: 22개 JUnit method ID, 2개 source, 2개 result XML, `PASS_INTERNAL_TARGETED_UNIT_ONLY`.
- 정식: `AC-FP-035-01..04 / TC-FP-035-01..04`, result path 없음, `NOT_RUN`.
- policy: `PB-WALKSAFE-FEATURE-POLICY-1.0.1` / `b6f5b850...` / `BASELINED`.
- approval: `APPROVAL_RECORDED / phase 0 APPROVED` / `6ad3e602...`.
- application: `COMMITTED / IMMUTABLE` / `002355b9...`.
- legacy CR/history mutation: `false`.

#### 최종 code 결속

| 경로 | SHA-256 |
|---|---|
| `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/AndroidNetworkTransferPolicy.kt` | `7befaf2a3f939c8bd7c382467d9b1bb2c5015f8324e103fcdfc84c0d17ef13e6` |
| `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt` | `a1b6b2a1853f41c538821b2a05345c94cd4b8f12a8e24d2f375b5044e0cd7b43` |
| `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidStepTracker.kt` | `489b16b47928ddc4a67d29c4526ecd6afae179ba7a9884e748e25481f0b905f9` |
| `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/debuglog/MetadataLogUploader.kt` | `00e724c9a66f1e7b5b9d741b70831b38e430ceb80a65f7c29cc0631dfc45cc39` |
| `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/debuglog/FrameCaptureUploader.kt` | `84023098b0d5401f97ae634c712124a912b8cedec7672e89c26fb89cc863575f` |
| `apps/android/app/src/debug/java/kr/co/hanium/dreamup/walksafe/debuglog/HttpFrameCaptureUploader.kt` | `acab6de2a6f30be1ed35783eec811b3bf0adf02063f238738b2b30a9aa4caddb` |
| `apps/android/app/src/debug/java/kr/co/hanium/dreamup/walksafe/debuglog/HttpMetadataLogUploader.kt` | `7bf041afd2a6b19686c948f467c7d04dcd1aa828559b089462d87421094fb84f` |

#### 내부 test ID

AndroidNetworkTransferPolicyTest.unknownMotionFailsClosedEvenOnWifi, AndroidNetworkTransferPolicyTest.trackingRestartRequiresANewFirstSensorSampleBeforeStationary, AndroidNetworkTransferPolicyTest.walkingBlocksEveryActivityOriginalUploadAndCancelsWhenWalkingResumes, AndroidNetworkTransferPolicyTest.stepEvidenceRequiresObservationWindowAndReturnsToWalkingOnNewStep, AndroidNetworkTransferPolicyTest.onlyActiveSessionWithConfirmedStationaryEvidenceMapsToStationary, AndroidNetworkTransferPolicyTest.stationaryWifiAllowsActivityOriginalUploadForBothPreferences, AndroidNetworkTransferPolicyTest.wifiIsAllowedForBothPreferences, AndroidNetworkTransferPolicyTest.firstSensorSampleClosesAdmissionAndStartsStationaryWindow, AndroidNetworkTransferPolicyTest.admissionCheckAndEnqueueCompleteBeforeConcurrentCloseAndCancel, AndroidNetworkTransferPolicyTest.stationaryOtherAndOfflineActivityOriginalTransfersFailClosed, AndroidNetworkTransferPolicyTest.trackingStopClosesAdmissionResetsMotionAndCancelsBothUploaders, AndroidNetworkTransferPolicyTest.closeAndCancelBeforeAdmissionPreventsCheckAndEnqueue, AndroidNetworkTransferPolicyTest.inactiveTransitionClosesAndCancelsEvenFromConfirmedStationary, AndroidNetworkTransferPolicyTest.stationaryCellularRequiresExplicitOptInForActivityOriginalUpload, AndroidNetworkTransferPolicyTest.cellularRequiresTheIndependentCellularPreference, AndroidNetworkTransferPolicyTest.unspecifiedPreferenceFailsBackToWifiOnlyQueueing, AndroidNetworkTransferPolicyTest.offlineIsAlwaysBlockedAndNonCellularTransportRemainsAvailable, HttpMetadataLogUploaderTest.cancellationAfterWorkerCheckBeforeHttpStartUsesEnqueueGeneration, HttpMetadataLogUploaderTest.dropsFrameCaptureConnectionFailureWithoutCrashingTheCaller, HttpMetadataLogUploaderTest.dropsConnectionFailureWithoutCrashingTheCaller, HttpMetadataLogUploaderTest.cancellationImmediatelyAfterConnectionPublicationStopsBeforeRequestOutput, HttpMetadataLogUploaderTest.walkingResumeCancellationPreventsQueuedMetadataUploadFromStarting

### REQ-CHG-20260726-004 / FP-047

기존 FP-047 bounded internal reassessment trace를 별도 항목으로 유지한다. internal policy-conformance는 `PASS`, GAP-056은 `PARTIAL`, formal/device/user/external/production은 `NOT_RUN`, release는 `NOT_ELIGIBLE`이다.

## REQ-19 용어 addendum

`GLO-031..035`는 FP-035 upload-only 규칙, 수정 후보 원문, 내부 구현 검증, 정식 시험, dirty-worktree 소스 스냅샷의 해석 경계를 유지한다. 기존 `glossary.json`은 수정하지 않았다.

## release boundary

- formal test: `279 NOT_RUN`
- release gate: `5 NOT_RUN`, 모두 `waived=false`
- release: `NOT_ELIGIBLE`

전체 31개 source binding의 exact path/SHA와 allowed/prohibited claim은 JSON에 기록했다.

JSON 자기 내용 SHA-256: `94d3cf6d5567d49fe8a7909dbdca12bd1676e471ed65440512f28620f99b6d63`.
