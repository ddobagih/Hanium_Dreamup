# W4 시험 데이터 프로파일 배정 현재상태

- 문서 ID: `WS-ARTIFACT-REMEDIATION-W4-TEST-DATA-ASSIGNMENT-CURRENT-STATE-20260727-001`
- 상태: `DRAFT_CONTROLLED_PROFILE_ASSIGNMENT_NOT_FORMAL_EXECUTION`
- 형식: 원본 `test-cases.json`을 수정하지 않는 add-only overlay
- JSON 동등 사실 정본: `docs/control/execution/artifact-remediation/20260726/w4/test-data-assignment-current-state.json`
- JSON content fingerprint: `fa931c2f87e5e120008f6a29a8e6134147ca76b207fa5095762d5360766f225c`

## 경계

이 문서는 정확한 정식 시험 279건에 승인 전 합성 또는 가명 프로파일 ID를 최소 하나씩 배정한다. 프로파일 instance 생성, 환경 provisioning, fixture 연결, 정식 실행 또는 PASS를 의미하지 않는다.

실제 개인정보, 실제 계정·credential, signing key, 실제 위치, 실제 기기 식별자와 TMAP key를 생성하거나 저장하지 않는다. 원본 `test_data_ids`는 변경하지 않았고 정식 상태는 `NOT_RUN 279 / PASS 0`이다.

## 원본 결속과 집계

| 항목 | 값 |
| --- | ---: |
| 정본 test case SHA-256 | `19a6b425bf3a0ee880f1c9229a42b96845ab4c3a1f99037783fe872426e0deee` |
| case ID set SHA-256 | `8ab0a039d0a6f9fcaad753bac08428da0f74ef27d40426ad4da49a28d5a0f167` |
| source-order ID SHA-256 | `681b6ad6c222aa73dfcf1bedb021279eca72342a108017688817e3afbfdfe395` |
| case / unique / duplicate | 279 / 279 / 0 |
| 배정 row / 빈 배정 | 279 / 0 |
| 프로파일 정의 / 배정 edge | 9 / 317 |
| 자동 fixture explicit link | 0 |
| profile instance | 0 |
| canonical result=null | 279 |
| canonical evidence_ids=[] | 279 |
| 정식 NOT_RUN / PASS | 279 / 0 |

ID set hash는 W3 `engineering-evidence-manifest.json`의 formal test ID set hash와 일치한다. 순서는 canonical test-case array의 FP 265건, NPC 9건, Gate 5건을 보존한다.

## 프로파일

모든 프로파일은 `NOT_APPROVED_FOR_FORMAL_EXECUTION` 및 `NOT_GENERATED`이다.

| 프로파일 ID | 분류 | 배정 case 수 | 용도 |
| --- | --- | ---: | --- |
| `TDP-SYNTH-BASE-001` | SYNTHETIC | 279 | 전 정식 시험 공통 합성 실행 문맥 |
| `TDP-SYNTH-CAPACITY-FAILURE-001` | SYNTHETIC | 8 | 합성 용량·실패·재시도·복구 문맥 |
| `TDP-SYNTH-GATEWAY-BACKEND-ADMIN-001` | SYNTHETIC_PSEUDONYMOUS | 8 | 합성 Gateway·Backend·관리자 상관관계 문맥 |
| `TDP-SYNTH-IDENTITY-CONSENT-001` | SYNTHETIC_PSEUDONYMOUS | 3 | 합성 가명 계정·동의·권한 문맥 |
| `TDP-SYNTH-LIFECYCLE-001` | SYNTHETIC_PSEUDONYMOUS | 6 | 합성 보존·삭제·철회 생명주기 문맥 |
| `TDP-SYNTH-MEDIA-MODEL-001` | SYNTHETIC | 2 | 생성 미디어·모델·원본수집 통제 대체물 |
| `TDP-SYNTH-RELEASE-GOVERNANCE-001` | SYNTHETIC | 6 | 합성 release gate·검토·훈련 문맥 |
| `TDP-SYNTH-ROUTE-LOCATION-001` | SYNTHETIC | 1 | 합성 위치·경로·방향 문맥 |
| `TDP-SYNTH-WALK-DEVICE-001` | SYNTHETIC | 4 | 합성 보행·기기·센서·네트워크 상태 |

## 결정적 배정 규칙

전 279건에 `TDP-SYNTH-BASE-001`를 먼저 배정한다. FP 265건에는 근거 없는 추가 분류를 추론하지 않는다. 아래 NPC/Gate에만 ID 자체로 명시된 통제 범위의 추가 프로파일을 사전 배정하며, 추가 ID는 사전순으로 정렬한다.

| 정식 시험 ID | 추가 프로파일 |
| --- | --- |
| `TC-NPC-RAW-ORIGINAL-COLLECTION-01` | `TDP-SYNTH-LIFECYCLE-001`, `TDP-SYNTH-MEDIA-MODEL-001`, `TDP-SYNTH-RELEASE-GOVERNANCE-001` |
| `TC-NPC-DATA-LIFECYCLE-01` | `TDP-SYNTH-GATEWAY-BACKEND-ADMIN-001`, `TDP-SYNTH-LIFECYCLE-001` |
| `TC-NPC-SERVER-STORAGE-CAPACITY-01` | `TDP-SYNTH-CAPACITY-FAILURE-001`, `TDP-SYNTH-GATEWAY-BACKEND-ADMIN-001`, `TDP-SYNTH-LIFECYCLE-001` |
| `TC-NPC-PHONE-QUEUE-CAPACITY-01` | `TDP-SYNTH-CAPACITY-FAILURE-001`, `TDP-SYNTH-WALK-DEVICE-001` |
| `TC-NPC-AUTO-REPORT-01` | `TDP-SYNTH-GATEWAY-BACKEND-ADMIN-001` |
| `TC-NPC-PERMISSION-SESSION-LIFECYCLE-01` | `TDP-SYNTH-IDENTITY-CONSENT-001`, `TDP-SYNTH-LIFECYCLE-001`, `TDP-SYNTH-WALK-DEVICE-001` |
| `TC-NPC-NAVIGATION-ROUTE-DIRECTION-01` | `TDP-SYNTH-ROUTE-LOCATION-001`, `TDP-SYNTH-WALK-DEVICE-001` |
| `TC-NPC-SINGLE-ADMIN-RECOVERY-01` | `TDP-SYNTH-CAPACITY-FAILURE-001`, `TDP-SYNTH-GATEWAY-BACKEND-ADMIN-001`, `TDP-SYNTH-IDENTITY-CONSENT-001` |
| `TC-NPC-SERVER-CAPACITY-STATE-SYNC-01` | `TDP-SYNTH-CAPACITY-FAILURE-001`, `TDP-SYNTH-GATEWAY-BACKEND-ADMIN-001` |
| `TC-GATE-PHONE-QUEUE-BYTE-LIMIT-01` | `TDP-SYNTH-CAPACITY-FAILURE-001`, `TDP-SYNTH-RELEASE-GOVERNANCE-001`, `TDP-SYNTH-WALK-DEVICE-001` |
| `TC-GATE-SERVER-CAPACITY-STATE-CONTRACT-01` | `TDP-SYNTH-CAPACITY-FAILURE-001`, `TDP-SYNTH-GATEWAY-BACKEND-ADMIN-001`, `TDP-SYNTH-RELEASE-GOVERNANCE-001` |
| `TC-GATE-RAW-COLLECTION-RELEASE-REVIEW-01` | `TDP-SYNTH-LIFECYCLE-001`, `TDP-SYNTH-MEDIA-MODEL-001`, `TDP-SYNTH-RELEASE-GOVERNANCE-001` |
| `TC-GATE-CLOUD-COST-MEASUREMENT-01` | `TDP-SYNTH-CAPACITY-FAILURE-001`, `TDP-SYNTH-GATEWAY-BACKEND-ADMIN-001`, `TDP-SYNTH-LIFECYCLE-001`, `TDP-SYNTH-RELEASE-GOVERNANCE-001` |
| `TC-GATE-SINGLE-ADMIN-RECOVERY-DRILL-01` | `TDP-SYNTH-CAPACITY-FAILURE-001`, `TDP-SYNTH-GATEWAY-BACKEND-ADMIN-001`, `TDP-SYNTH-IDENTITY-CONSENT-001`, `TDP-SYNTH-RELEASE-GOVERNANCE-001` |

seed 계약은 `SHA-256(test_run_id + LF + test_case_id + LF + profile_id + LF + source_binding_sha256)`이다. 이 overlay는 run ID, seed 또는 데이터 instance를 실제로 생성하지 않는다.

## 생성·비식별·secret 통제

- 모든 app, Gateway, Backend, Admin, DB, bucket, queue 자원은 하나의 승인된 test-run namespace를 사용한다.
- 가명 ID는 실제 인물·기관·기기와 연결되지 않으며 reversible mapping을 만들지 않는다.
- 위치는 실제 주소와 연결되지 않은 합성 격자만 사용한다.
- 미디어는 생성물 또는 별도 사용권과 비식별 처리가 확인된 fixture만 허용한다. 얼굴, 번호판, 음성 식별자와 정확 위치 metadata는 금지한다.
- Gateway·Backend·Admin에는 합성 actor와 correlation ID만 사용하고 production endpoint, storage, queue와 공유 관리자 계정을 금지한다.
- secret 값은 fixture·manifest·로그·스크린샷·evidence에 넣지 않는다. 승인된 test `secret_ref`와 redacted fingerprint만 허용한다.
- TMAP key는 생성·저장하지 않는다. 향후 sandbox 호출도 별도 승인된 `secret_ref` 없이는 금지한다.

## 보존·폐기

생성 자원은 run 종료 후 24시간 이내 삭제하는 Draft 통제를 적용한다. app test state, Admin actor/session, Gateway namespace, Backend test schema, object prefix, queue prefix, route cache와 생성 media가 cleanup 대상이다.

cleanup receipt에는 run ID, namespace, 자원 종류, 완료시각, 결과와 redacted receipt SHA-256을 남긴다. cleanup이 pending 또는 실패하면 정식 PASS로 확정하지 않는다.

## 누수 중단 기준

실제 식별자·reversible map, credential·secret·TMAP key, 승인되지 않은 실제 얼굴·번호판·음성·정확 위치, production endpoint/DB/bucket/queue, 공유 Admin 계정 접속 또는 cleanup 증명 실패가 발견되면 run을 `STOPPED_DATA_LEAK_SUSPECTED`로 즉시 중단한다.

중단 후 격리, 접근 차단, credential 폐기·회전, 노출 자료 제거, 영향 조사, TST-18 defect와 redacted incident receipt가 필요하다. 재검토와 재실행 전에는 formal PASS 기여가 0이다.

## 원본수집 경계

`TC-NPC-RAW-ORIGINAL-COLLECTION-01`과 `TC-GATE-RAW-COLLECTION-RELEASE-REVIEW-01`에는 생성 media 프로파일을 배정했지만 이는 실제 원본수집 검토를 대체하지 않는다. 두 건은 `BLOCKED_BY_RAW_COLLECTION_RELEASE_REVIEW / NOT_RUN`이다.

향후 원본 사용이 별도 승인되더라도 Git에는 통제 저장소 reference ID, hash, 동의·승인 reference, 접근통제와 보존·폐기 receipt만 기록한다.

## Current trace 재결속

- Upstream trace raw SHA-256: `d46a9ea3ecc5334c15531947733a520469b5cf712a0eed216d53413a18187893`
- Upstream trace content fingerprint: `a1c1aa13fc63e63bd6ed9470f0080aaca6ec94dda7db6aadb6c46bad84188f86`
- Canonical order digest 계약: compact JSON array + trailing LF
- Declared source drift: rebind 전 2 / rebind 후 0
- Current generated subject: run002 결속 7 / 현재 byte 일치 7
- Validation lineage: run001 `FAIL_PRESERVED` → run002 `PASS_STRUCTURE_ONLY`
- W3 final predecessor: `0c816c830a4371fc4abbef0e3f4848940daa130837e3cead06a0e7a9eb800ce0`
- W3 final common fingerprint: `aba4fe81c7fdc2c91c86d66df17e7d67b9fd584fef7fbdd075fc5ab48db8d4a4`
- Central sealing decision: `GO`
- Formal 영향: `NOT_RUN 279 / PASS 0`, release `NOT_ELIGIBLE`

run001 실패 기록과 pre-final W3 predecessor는 삭제하거나 PASS로 덮지 않았다. run002와 W3 sealing은 구조·내부 evidence 경계이며 formal execution evidence가 아니다.

## 자동시험 fixture 경계

W3 내부 자동시험 PASS는 별도 축이다. 검토된 explicit edge가 없으므로 자동 fixture→formal case 매핑은 `0/279`이며 이 문서도 이를 추론하지 않는다.
