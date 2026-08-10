# W1 최종 독립 검토

- 검토 ID: `WS-ARTIFACT-REMEDIATION-W1-INDEPENDENT-REVIEW-20260726-002`
- 선행 판정: `WS-ARTIFACT-REMEDIATION-W1-INDEPENDENT-REVIEW-20260726-001 / REJECTED`
- 검토 범위: W1 산출물 유형 18개와 FP-035 Android 내부 구현·단위시험 근거
- 재검토 입력: 변경된 DSC·REQ current-state JSON/Markdown와 선행 검토에서 통과한 불변 입력
- 검토 방식: 기준선 `required_action` 대조, exact-set·coverage·JSON 지문·source binding 검사, 코드·JUnit 선행 판정 재사용
- 최종 판정: `APPROVED`
- 최종 발견 수: `blocking 0 / major 0 / minor 0`
- FP-035 기술 코드 발견 수: `blocking 0 / major 0 / minor 0`

이 문서는 선행 `REJECTED` 판정을 대체하는 최종 재검토 기록이다. `APPROVED`는 W1 내부 문서 gap의 현재 상태 정합화가 완료됐다는 뜻이며, 정식시험·실기기·외부 검토·배포 또는 release 승인을 뜻하지 않는다.

## 최종 발견 사항

새 blocking, major 또는 minor 발견 사항이 없다. 선행 major 네 건은 아래와 같이 해소됐다.

| 선행 발견 | 최종 판정 | 해소 근거 |
|---|---|---|
| `MAJOR-01 / DLV-DSC-03` | `RESOLVED` | 적용성 `ACTIVE`, 계획 ID, 책임 role/owner, current-state pointer를 결속하고 실행 `NOT_RUN`, 실제 참여자·인터뷰·세션 0을 유지했다. |
| `MAJOR-02 / DLV-REQ-16` | `RESOLVED` | exact 68개 RTM 행에 design/module/config/test/evidence 필드와 실제 참조 또는 명시적 `UNLINKED`를 기록하고 coverage를 재산출했다. |
| `MAJOR-03 / DLV-REQ-17` | `RESOLVED` | 포함 68·제외 0·open waiver 0, wrapper baseline과 underlying Draft 집합을 분리하고 `SUPPLEMENTS_DOES_NOT_SUPERSEDE`, `source_commit=null`, `NOT_APPROVED/NOT_BASELINED`를 명시했다. |
| `MAJOR-04 / DLV-REQ-18` | `RESOLVED` | CR-0002 before/after text·hash, design 5개, code 7개, 내부시험 22 PASS·XML 2개, formal 4 `NOT_RUN`, policy·approval·COMMITTED receipt를 entry 003에 직접 결속했다. |

## 네 보완 항목 재검토

### DLV-DSC-03

- 적용성 결정: `ACTIVE`
- 결정 ID: `WS-DSC03-APPLICABILITY-DECISION-20260726-001`
- 계획 ID: `WS-DSC03-RESEARCH-PLAN-20260726-001`
- 책임 role/owner: `사용자연구책임자 / 김민호`
- current pointer: add-only `dsc-current-state.json`을 legacy predecessor보다 먼저 읽음
- 계획 상태: `DRAFT_PLAN_READY_FOR_INTERNAL_REVIEW`
- 실행 상태: `NOT_RUN`
- 실제 참여자·인터뷰·사용성 세션: `0 / 0 / 0`
- 승인·정식 검증·결과 완료 주장: 없음

적용성과 계획 lifecycle을 구분했으므로 `ACTIVE`가 연구 실행 완료로 오해되지 않는다.

### DLV-REQ-16

| 차원 | LINKED | UNLINKED | 실제 edge | 판정 |
|---|---:|---:|---:|---|
| design | 68 | 0 | 716 | 실제 design ID·path·anchor를 유지하며 모두 `DRAFT_NOT_APPROVED` |
| module | 0 | 68 | 0 | canonical source에 필드가 없어 추정하지 않음 |
| config | 0 | 68 | 0 | canonical source에 필드가 없어 추정하지 않음 |
| test | 68 | 0 | 279 | 전부 계획 시험이며 `NOT_RUN`, `pass_claimed=false` |
| evidence | 13 | 55 | 24 | 실제 evidence trace만 연결하고 나머지는 `UNLINKED` |

- 행 수·고유 requirement ID: `68 / 68`
- requirement ID-set SHA-256: `05c859b87e32eaa8beb0441d2ce902234f9d2110330c4da3e6f52ba3b75fdd85`
- row projection SHA-256: `f054aea4cbd51b1ed39b4f58283c45058e55f02f78b86fc63a29845b8a42cef5`
- 각 차원 불변식: `LINKED + UNLINKED = 68`
- design 참조 path: 716개 모두 존재
- formal test 참조: 279개 모두 `NOT_RUN`, PASS 주장 0
- FP-035 overlay: 정책 1.0.1, current decision, 승인, COMMITTED receipt, code 7개, 내부시험 22 PASS 결속
- FP-047 overlay: internal PASS, GAP-056 `PARTIAL`, formal `NOT_RUN`, release `NOT_ELIGIBLE` 유지

`code_trace`를 module/config로 재명명하지 않았으며 없는 연결을 생성하지 않았다.

### DLV-REQ-17

- artifact-content predecessor: `CB-WALKSAFE-REQ-17-1.0.0`
- underlying requirement-set predecessor: `WS-FORMAL-REQ-DRAFT-20260721-001`
- predecessor underlying 상태: `DRAFT / NOT_APPROVED / NOT_BASELINED`
- successor underlying 상태: `DRAFT / NOT_APPROVED / NOT_BASELINED`
- 포함·제외·합집합: `68 / 0 / 68`
- open waiver: `0`, 범위는 bound exact-68에 한정
- supersession: `SUPPLEMENTS_DOES_NOT_SUPERSEDE`
- predecessor 보존: `true`
- source commit: `null`
- dirty worktree expected: `true`
- whole repository frozen: `false`
- 현재 source snapshot: 정확한 11개 path/SHA

등록된 REQ-17 artifact wrapper의 승인 상태와 underlying 68개 요구 집합의 Draft 상태를 분리했다. 다섯 `NOT_RUN / waived=false` gate를 waiver로 잘못 계산하지 않았다.

### DLV-REQ-18

`REQ-CHG-20260726-003`은 다음을 직접 결속한다.

- CR-0002 원래 conflict 설명과 SHA-256
- 정책 1.0.0 실제 before clause와 SHA-256
- 승인된 after normative rule과 SHA-256
- 현재 REQ-03 전체 요구문과 SHA-256
- 영향 design: `DES-04`, `DES-06`, `DES-09`, `DES-13`, `DES-20`
- 구현 source: 7개 path/SHA
- 내부시험: 22개 method ID, source 2개, JUnit XML 2개, `PASS_INTERNAL_TARGETED_UNIT_ONLY`
- 정식시험: `AC/TC-FP-035-01..04`, result path 0, `NOT_RUN`, `pass_claimed=false`
- 정책 기준선: `PB-WALKSAFE-FEATURE-POLICY-1.0.1 / BASELINED`
- 승인 기록: `APPROVAL_RECORDED / APPROVED`
- 적용 receipt: `COMMITTED / IMMUTABLE`
- actual device/network/socket: 모두 `NOT_RUN`
- release: `NOT_ELIGIBLE`

`REQ-CHG-20260726-004`는 FP-047 후속 trace로 별도 유지되며 CR-0002 상세 기록의 대체물로 사용하지 않는다. legacy change log와 역사 후보를 수정하지 않았다.

## 18개 항목 최종 판정

| 산출물 유형 | `required_action` 최종 판정 | 권고 전환 | 핵심 근거 |
|---|---|---|---|
| `DLV-DOC-01` | 충족 | `OK` | 정책 1.0.1, current decision, current-state 보완물과 감사·파동 근거가 add-only로 연결됨 |
| `DLV-DOC-05` | 충족 | `OK` | before target·replacement hash, 승인·COMMITTED receipt, 영향 산출물과 불변 이력 결속 |
| `DLV-DSC-03` | 충족 | `OK` | 적용성 `ACTIVE`, 계획·owner·pointer 결속, 실행과 실제 결과는 `NOT_RUN`/0 |
| `DLV-DSC-07` | 내부 선행작업 충족 | `EXTERNAL` | 비교 protocol은 준비됐으나 공식 source·URL·조사일·설치 관찰 결과는 0 |
| `DLV-DSC-09` | 내부 재평가 기록 충족 | `EXTERNAL` | 내부 component evidence·제약은 결속됐으나 고정 build 실기기 E2E와 외부 통합은 `NOT_RUN` |
| `DLV-DSC-14` | 충족 | `OK` | r021 feature 54·전체 68, 내부 receipt 12, 완료 0과 다음 작업을 분리 |
| `DLV-MGT-06` | 충족 | `OK` | WBS projection, WBS-8/8.1과 FP-047 bounded slice 결속 |
| `DLV-MGT-07` | 충족 | `OK` | 실제 근거가 있는 진척 사건만 기록하고 demo 완료일을 생성하지 않음 |
| `DLV-MGT-14` | 충족 | `OK` | RAID-011 resolved, RAID-012 잔여 위험 분리, 집계 12/11/1 |
| `DLV-MGT-15` | 충족 | `OK` | base 135 + replacement 1, effective 135, edge 428 current pointer |
| `DLV-MGT-16` | 충족 | `OK` | CR-0002 승인·적용과 내부 검증, downstream/formal/device closure 분리 |
| `DLV-MGT-17` | 충족 | `OK` | 동일 관측시점의 정책·승인·audit·r021·RAID·release 경계 재투영 |
| `DLV-REQ-03` | 충족 | `OK` | 승인 FP-035 규칙, caller/module, fail-closed admission과 cancellation trace |
| `DLV-REQ-06` | 충족 | `OK` | 네 전송 분기와 fail-closed/cancellation 내부시험 연결 |
| `DLV-REQ-16` | 충족 | `OK` | exact-68 행, 다섯 차원 실제 coverage와 FP-035/FP-047 overlay |
| `DLV-REQ-17` | 충족 | `OK` | include/exclude/waiver, predecessor/supersession, dirty source 경계 결속 |
| `DLV-REQ-18` | 충족 | `OK` | CR-0002 before/after·design·code·internal/formal test·승인 적용 결속 |
| `DLV-REQ-19` | 충족 | `OK` | GLO-031~035가 현행 효력과 역사·검증·snapshot 용어를 분리 |

최종 권고 집계는 `OK 16 / EXTERNAL 2 / INTERNAL_GAP 0`이다. W1 exact set은 18개이며 중복·누락이 없다. 이 delta는 불변 257개 audit baseline 자체를 소급 수정하지 않는다.

## 지문·source binding 검증

변경 파일 검증 결과는 다음과 같다.

| 입력 | 파일 SHA-256 | record content SHA-256 | 판정 |
|---|---|---|---|
| `dsc-current-state.json` | `776cceea0d5505c83b915ea7848f252a314ebc03ba9798f7191bf60012a5c39a` | `9c47698e4ad1ade9c0faf33cc6cddad5db5d36e70dcaca9a1bf7418a2925648b` | 재계산 일치 |
| `dsc-current-state.md` | `b844e22407b078517c5a3dc9eed0f2caecf40492743c38bd2a4df2e73f52f134` | JSON 지문 표기 일치 | PASS |
| `req-current-state.json` | `615f35910cb1fffab6284aee456e562e6d21e75671f225bd9f06868ad0785dce` | `94d3cf6d5567d49fe8a7909dbdca12bd1676e471ed65440512f28620f99b6d63` | 재계산 일치 |
| `req-current-state.md` | `a5af580c5e2761b68eb892a255c0be156d2e0da8e01ef78430d84a00b27079a7` | JSON 지문 표기 일치 | PASS |

- DSC 명시 path/SHA binding: 18개, mismatch 0
- REQ 명시 path/SHA binding: 64개, mismatch 0
- DSC scope ID: 정확히 4개
- REQ scope ID: 정확히 6개
- 보유 DOC 2 + DSC 4 + MGT 6 + REQ 6: 정확히 18개, unique 18

## 보유 PASS 결과 재사용

변경되지 않은 입력은 선행 독립 검토의 PASS 결과를 재사용했다.

| 검증 | 보유 결과 |
|---|---|
| audit baseline | 총 257, `OK 59 / INTERNAL_GAP 123 / EXTERNAL 39 / N/A_CANDIDATE 36` |
| 승인 집계 | `102 approved baselined / 27 active / 53 draft / 75 planned`, 승인 적용 129 |
| r021 | assessment 68, `5/16/4/11/32/0`, 정식 완료 0 |
| decision successor | SHA `4a448f65280c2cd8cd850a749434f4e124769e47b7b84e31e2e14c58328d2faf`, 정책 1.0.1, effective 135, edge 428 |
| MGT record fingerprint | `62e65cbda8a575284acda6ae69fe65459cf3fbcefa3a0db64e41d80cc22bafa4` |
| FP-035 JUnit XML | policy 17 + uploader 5 = 22, skipped/failure/error 0 |
| formal·release | formal 279 전부 `NOT_RUN`, gate 5개 미면제, `NOT_ELIGIBLE` |
| FP-035 기술 코드 | `blocking 0 / major 0 / minor 0` |

이번 재검토에서는 Gradle, 빌드, git, 정식시험을 실행하지 않았다.

## 남은 EXTERNAL 및 NOT_RUN 경계

- `DLV-DSC-07`: 공식 vendor/app-store 자료, 조사일·URL·버전과 통제 설치 관찰 수집이 남았다.
- `DLV-DSC-09`: 하나의 고정 후보 build를 이용한 카메라→추론→안내→신고 실기기 E2E와 외부 연동이 남았다.
- 사용자 연구 실제 참여자·인터뷰·사용성 세션은 모두 0/`NOT_RUN`이다.
- 실제 기기 motion sensor, tracking start/stop, 실제 network transition, real socket cancellation과 5초 stationary 현장 보정은 `NOT_RUN`이다.
- 정식시험 279개는 전부 `NOT_RUN`이고 release gate 5개는 모두 미면제다.
- 실제 사용자·TalkBack·관리자·field·PostGIS·reports·PostgreSQL concurrency·외부 보안/법률/개인정보/접근성 검토·production deployment는 완료로 승격하지 않는다.
- 최종 release 상태는 `NOT_ELIGIBLE`이다.

## 승인 결론

W1의 18개 `required_action`은 내부 작성 또는 명시적 외부 전환 경계까지 양방향 추적된다. 근거 없는 artifact 승인, formal PASS, gate 면제 또는 release 승격은 발견되지 않았다. 따라서 후속 `implementation-receipt`, `validation-summary`, `artifact-status-delta`에서 `OK 16 / EXTERNAL 2`를 materialize하는 것을 승인한다.
