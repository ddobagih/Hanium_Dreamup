# 257종 잔여 작업 실행 준비 행렬

- Matrix ID: `WS-257-READINESS-MATRIX-20260727-R001`
- 상태: `PROVISIONAL_PHASE0_INPUT`
- 기준 snapshot: `artifact-audit-successor-snapshot.json`
- 기준 raw SHA-256: `481339c99f26055f6bbb008a3f4831c029013d2275e0e4a61cab4f7d30444947`
- 범위: `INTERNAL_GAP 48 / EXTERNAL 49 / N_A_CANDIDATE 36 = OPEN 133`
- 경계: 이 행렬은 계획 입력이며 상태 승인, 실제 실행, N/A 결정, 외부 사건 receipt가 아니다.

## 1. 공통 판정 규칙

- 각 행의 값은 Phase 0에서 source-of-truth, completion mode, authority와 함께 확정한다.
- artifact ledger는 ID당 한 행이다.
- actual action은 `{artifact_id, action_id, attempt_id}`로 별도 관리한다.
- `PLAN_OR_CONTROL` 또는 `NO_NEW_RUN`은 빈 템플릿을 뜻하지 않는다. 현재 scope, 실제 정본, authority review가 필요하다.
- `REAL`은 실제 실행이 필요하다.
- `DECISION`은 실제 권한자 판단이 필요하지만 물리 사건은 필요하지 않다.
- `NO_EVENT`는 관찰기간·검색범위·ledger hash가 있는 승인된 current-state attestation을 허용한다.
- `CONDITIONAL`은 정당한 사건이 발생할 때만 실행하며 증거용 사건 생성은 금지한다.
- N/A 36건은 모두 개별 receipt가 필요하며 일괄 승인을 금지한다.

## 2. 내부 48건

요약: 실제 실행 `YES 29 / NO_NEW_RUN 16 / CONDITIONAL 3`.

| ID | Packet | 완료 유형 | 실행 | 최소 proof | 선행 | 외부 sub-action |
|---|---|---|---|---|---|---|
| DLV-AIML-05 | DATA-QUALITY | MODEL_DATA_EXECUTION | YES | immutable dataset와 image/label hash | data scope/privacy | data access·owner |
| DLV-AIML-06 | DATA-QUALITY | MODEL_DATA_EXECUTION | YES | keep/fix/hold/drop decision ledger | AIML-05 | data owner 승인 |
| DLV-AIML-07 | DATA-QUALITY | HUMAN_EXTERNAL_SUBACTION | NO_NEW_RUN | class별 예시 version과 승인 | AIML-05·taxonomy | label owner |
| DLV-AIML-08 | DATA-QUALITY | MIXED | YES | gold set, double-label, adjudication raw evidence | AIML-05/06/07 | labeler·adjudicator |
| DLV-AIML-09 | DATA-QUALITY | MODEL_DATA_EXECUTION | YES | train/val/test manifest와 leakage receipt | AIML-05..08 | 없음 |
| DLV-AIML-10 | DATA-QUALITY | MODEL_DATA_EXECUTION | YES | capture/sequence/metadata leakage pairs와 verdict | AIML-09 | 없음 |
| DLV-AIML-11 | TRAINING | MODEL_DATA_EXECUTION | CONDITIONAL | dataset→checkpoint provenance 또는 재학습 receipt | DATA-QUALITY | source·compute |
| DLV-AIML-12 | TRAINING | MODEL_DATA_EXECUTION | YES | formal experiment input/env/metric/checkpoint hash | AIML-11 | 없음 |
| DLV-AIML-13 | TRAINING | MODEL_DATA_EXECUTION | YES | 같은 split 후보-vs-fallback raw metric | AIML-11/12 | 없음 |
| DLV-AIML-15 | TRAINING | MIXED | NO_NEW_RUN | runtime model card 3개와 평가 binding | AIML-13/16/23 | model owner |
| DLV-AIML-16 | TRAINING | MIXED | CONDITIONAL | 3-model source/hash/conversion/fallback chain | AIML-11 | fallback source |
| DLV-AIML-17 | EVALUATION | HUMAN_EXTERNAL_SUBACTION | NO_NEW_RUN | 사전 동결 protocol/tolerance 승인 | data·training | evaluation approver |
| DLV-AIML-21 | EVALUATION | MODEL_DATA_EXECUTION | YES | fixed-input PT/TFLite raw comparison | AIML-16/17 | 없음 |
| DLV-AIML-23 | EVALUATION | MODEL_DATA_EXECUTION | YES | frozen threshold sweep와 독립 재평가 | AIML-17/21 | independent reviewer |
| DLV-DES-15 | DESIGN-SECURITY | CONTENT_TRACE | NO_NEW_RUN | 두 앱 current flow와 code-design trace | named source·DES-19 | 없음 |
| DLV-DES-19 | DESIGN-SECURITY | CONTENT_TRACE | NO_NEW_RUN | admin auth/recovery/session trust boundary | named source | 없음 |
| DLV-DES-20 | DESIGN-SECURITY | MIXED | YES | threat model, 보안 검증 trace, review | DES-15/19 | security reviewer |
| DLV-DEV-09 | BUILD-SUPPLY | BUILD_EXECUTION | YES | clean build input/command/toolchain/output hash | named release generation | 없음 |
| DLV-DEV-12 | BUILD-SUPPLY | BUILD_EXECUTION | YES | migration up/down와 backup/restore receipt | DEV-20 | DB snapshot |
| DLV-DEV-14 | BUILD-SUPPLY | CONTENT_TRACE | NO_NEW_RUN | fixture source/privacy/expected/hash와 test link | source·case inventory | privacy review |
| DLV-DEV-16 | STATIC-SECRET | SECURITY_EXECUTION | YES | formal lint/static raw report | DEV-20 | 없음 |
| DLV-DEV-17 | BUILD-SUPPLY | HUMAN_EXTERNAL_SUBACTION | NO_NEW_RUN | license obligation 전수 판정 | DEV-19 | license/legal |
| DLV-DEV-19 | BUILD-SUPPLY | BUILD_EXECUTION | YES | release CycloneDX/SPDX SBOM | DEV-20 | 없음 |
| DLV-DEV-20 | BUILD-SUPPLY | BUILD_EXECUTION | YES | reproducible build 2회와 provenance | source/toolchain freeze | 없음 |
| DLV-DEV-21 | INTEGRATION-RELEASE | TEST_EXECUTION | YES | same-generation integration raw result | build/static/model/design | service credential |
| DLV-REL-15 | INTEGRATION-RELEASE | TEST_EXECUTION | YES | rollback trigger/result/post-check receipt | release candidate | deploy environment |
| DLV-REL-16 | INTEGRATION-RELEASE | MIXED | YES | signed artifact와 install/remove evidence | DEV-20 | signing authority/key/device |
| DLV-REL-18 | INTEGRATION-RELEASE | CONTENT_TRACE | NO_NEW_RUN | current admin 운영 guide | DES-19·SEC-19 | admin review |
| DLV-REL-19 | INTEGRATION-RELEASE | HUMAN_EXTERNAL_SUBACTION | YES | demo/training participant·session receipt | RC·REL-18 | participant·trainer |
| DLV-REL-22 | INTEGRATION-RELEASE | HUMAN_EXTERNAL_SUBACTION | YES | 531 dependency reconciliation와 final notice | DEV-19/17 | license/legal |
| DLV-SEC-01 | DESIGN-SECURITY | MIXED | YES | security plan, role training, independent approval | source/build·DES-20 | role holder·approver |
| DLV-SEC-10 | STATIC-SECRET | SECURITY_EXECUTION | YES | SAST parse error 0와 triage/retest | DEV-20 | 없음 |
| DLV-SEC-12 | STATIC-SECRET | SECURITY_EXECUTION | YES | current tree와 full-history secret scan | DEV-20 | history access |
| DLV-SEC-19 | DESIGN-SECURITY | MIXED | YES | audit schema, threshold, alert procedure와 verification | DES-20·build | ops reviewer |
| DLV-TST-02 | TEST-GOV | HUMAN_EXTERNAL_SUBACTION | NO_NEW_RUN | entry/exit/stop/resume 승인 | RC·env | test authority |
| DLV-TST-03 | TEST-ENV | MIXED | YES | formal env와 supported-device matrix | RC·support decision | device owner |
| DLV-TST-05 | TEST-GOV | CONTENT_TRACE | NO_NEW_RUN | direct req-design-module-case-fixture trace | design·DEV-14 | independent QA |
| DLV-TST-06 | TEST-GOV | TEST_EXECUTION | YES | formal unit raw result와 build/env hash | DEV-20·TST-02/03 | 없음 |
| DLV-TST-07 | TEST-ENV | TEST_EXECUTION | YES | isolated cross-process integration evidence | build·TST-02/03 | 없음 |
| DLV-TST-08 | TEST-GOV | TEST_EXECUTION | YES | OpenAPI 전체 contract result | DEV-20·TST-02 | 없음 |
| DLV-TST-09 | TEST-ENV | TEST_EXECUTION | YES | supported-device와 live TMAP E2E | TST-03/07 | device·credential |
| DLV-TST-11 | TEST-GOV | TEST_EXECUTION | YES | release regression raw result | DEV-21·formal tests | external component |
| DLV-TST-14 | TEST-ENV | MIXED | YES | automated accessibility와 physical TalkBack | TST-03 | user·device |
| DLV-TST-18 | TEST-GOV | CONTENT_TRACE | CONDITIONAL | append-only defect 또는 zero-defect receipt | formal runs | 없음 |
| DLV-TST-19 | TEST-GOV | CONTENT_TRACE | NO_NEW_RUN | bound evidence 기반 coverage/quality metric | TST-06..18 | 없음 |
| DLV-TST-20 | TEST-GOV | HUMAN_EXTERNAL_SUBACTION | NO_NEW_RUN | STR·defect·residual-risk 승인 | TST-18/19 | risk approver |
| DLV-TST-21 | TEST-GOV | HUMAN_EXTERNAL_SUBACTION | NO_NEW_RUN | risk acceptance·expiry·release impact 결정 | TST-20 | release approver |
| DLV-WS-10 | EVALUATION | MIXED | YES | same-input PT/TFLite와 approved tolerance | AIML-16/17/21 | tolerance approver |

## 3. 외부 49건

Authority: `DL` data/legal, `FQ` field QA, `RO` release/operations, `PA` sponsor/acceptance, `IR` research observer.

분포: `PLAN_OR_CONTROL 6 / ACTUAL_DECISION 9 / ACTUAL_OBSERVATION 5 / ACTUAL_DEVICE_RUN 12 / ACTUAL_OPERATION 7 / ACTUAL_ACCEPTANCE 10`.

| ID | Track | 의미 | Event mode | 최소 proof | 선행 |
|---|---|---|---|---|---|
| DLV-AIML-01 | DL | ACTUAL_DECISION | DECISION | 권리·동의 원본과 허용범위 결정 | data inventory |
| DLV-AIML-02 | DL | ACTUAL_DECISION | DECISION | dataset manifest와 권리상태 | 원데이터·권리 |
| DLV-AIML-03 | DL | ACTUAL_DECISION | DECISION | 약관·license·동의·철회 영향 결정 | provider 원문 |
| DLV-AIML-22 | DL | ACTUAL_DEVICE_RUN | REAL | APK/model/config 반복 성능과 지원판정 | 후보·기기 |
| DLV-SEC-04 | DL | ACTUAL_DECISION | DECISION | 독립 보안검토와 finding 재검증 | code/design hash |
| DLV-SEC-05 | DL | ACTUAL_DECISION | DECISION | 필요성·비례성·잔여위험 법률판정 | processing inventory |
| DLV-SEC-06 | DL | ACTUAL_DECISION | DECISION | UI notice와 법적근거·철회 결정 | 문구·처리흐름 |
| DLV-SEC-17 | DL | PLAN_OR_CONTROL | NO_EVENT | 연락·통지 통제와 no-incident snapshot | 연락망·의무 |
| DLV-WS-06 | FQ | ACTUAL_DEVICE_RUN | REAL | GPS scenario raw result | build·device·장소 |
| DLV-WS-07 | FQ | ACTUAL_DEVICE_RUN | REAL | participant field route 결과 | 동의·안전계획 |
| DLV-WS-09 | FQ | ACTUAL_DEVICE_RUN | REAL | 다기기 비교 성능 | 동일 candidate |
| DLV-WS-12 | FQ | ACTUAL_DEVICE_RUN | REAL | 소음별 STT raw result | device·corpus |
| DLV-WS-13 | FQ | ACTUAL_DEVICE_RUN | REAL | 안내·진동 관찰 | device·observer |
| DLV-WS-14 | FQ | ACTUAL_DEVICE_RUN | REAL | TalkBack focus/label/role 결과 | TalkBack device |
| DLV-WS-15 | FQ | ACTUAL_DEVICE_RUN | REAL | 대비·확대·reflow·touch 결과 | Android candidate |
| DLV-WS-17 | FQ | ACTUAL_DEVICE_RUN | REAL | OS/device matrix 핵심 흐름 | support matrix |
| DLV-WS-20 | FQ | ACTUAL_DEVICE_RUN | REAL | phone-gateway-server E2E | phone·server |
| DLV-WS-21 | FQ | ACTUAL_ACCEPTANCE | ACCEPTANCE | 참여자 설명·철회·안전·동의 | protocol |
| DLV-OPS-06 | RO | ACTUAL_OPERATION | REAL | live metric dashboard·alert 검증 | metric source |
| DLV-OPS-11 | RO | ACTUAL_OPERATION | REAL | isolated restore receipt와 RTO | backup·operator |
| DLV-OPS-13 | RO | ACTUAL_OPERATION | REAL | DR drill·rollback | infra·runbook |
| DLV-OPS-17 | RO | PLAN_OR_CONTROL | NO_EVENT | no-incident current-state receipt | incident schema |
| DLV-OPS-19 | RO | PLAN_OR_CONTROL | NO_EVENT | no-change current-state receipt | change schema |
| DLV-OPS-22 | RO | ACTUAL_OPERATION | REAL | capacity/cost 측정 | paid resource |
| DLV-OPS-23 | RO | ACTUAL_OPERATION | CONDITIONAL | 정당한 삭제 event 또는 readiness | due target |
| DLV-REL-13 | RO | ACTUAL_OPERATION | CONDITIONAL | 승인 배포·smoke·rollback | candidate·window |
| DLV-REL-20 | RO | ACTUAL_ACCEPTANCE | ACCEPTANCE | 인계 package와 수령 서명 | recipient |
| DLV-REL-21 | RO | ACTUAL_ACCEPTANCE | ACCEPTANCE | 지정 검수자 후보 검수 | scope·candidate |
| DLV-CLS-14 | RO | PLAN_OR_CONTROL | NO_EVENT | 보존·이관·삭제 절차 current-state 승인 | inventory·legal |
| DLV-CLS-15 | RO | PLAN_OR_CONTROL | NO_EVENT | asset disposition current-state 승인 | asset inventory |
| DLV-CLS-16 | RO | PLAN_OR_CONTROL | NO_EVENT | 종료 통제 current-state 승인 | shutdown plan |
| DLV-CLS-02 | PA | ACTUAL_ACCEPTANCE | ACCEPTANCE | 최종 인도 scope와 서명 | package·recipient |
| DLV-CLS-04 | PA | ACTUAL_OBSERVATION | REAL | KPI 실제 최종 측정 | protocol |
| DLV-CLS-08 | PA | ACTUAL_ACCEPTANCE | ACCEPTANCE | residual-risk 수용 | mitigation evidence |
| DLV-CLS-10 | PA | ACTUAL_ACCEPTANCE | ACCEPTANCE | 운영 인수 확인 | ops package |
| DLV-CLS-11 | PA | ACTUAL_ACCEPTANCE | CONDITIONAL | 실제 종료 후 회고 확인 | legitimate closure |
| DLV-DES-21 | PA | ACTUAL_DECISION | DECISION | TMAP/GCS 계약·이전 법률결정 | contract·flow |
| DLV-DSC-05 | PA | ACTUAL_OBSERVATION | REAL | 비식별 raw→persona trace | privacy·protocol |
| DLV-DSC-06 | PA | ACTUAL_OBSERVATION | REAL | user journey raw trace | research result |
| DLV-DSC-11 | PA | ACTUAL_OBSERVATION | REAL | baseline/target 실제 측정 | sample·protocol |
| DLV-MGT-03 | PA | ACTUAL_ACCEPTANCE | ACCEPTANCE | KPI 완료판정 승인 | formal result |
| DLV-MGT-08 | PA | ACTUAL_DECISION | DECISION | 가격·quota·예산 결정 | quote·budget |
| DLV-REQ-12 | PA | ACTUAL_DEVICE_RUN | REAL | device/network/server 성능 | environment |
| DLV-REQ-13 | PA | ACTUAL_OPERATION | REAL | recovery drill과 SLO/RTO/RPO | backup |
| DLV-REQ-15 | PA | ACTUAL_DECISION | DECISION | 법률/license 의무 승인 | legal evidence |
| DLV-TST-22 | PA | ACTUAL_ACCEPTANCE | ACCEPTANCE | same-generation Go/No-Go | all gates |
| DLV-TST-23 | PA | ACTUAL_ACCEPTANCE | ACCEPTANCE | recipient·independent QA 서명 | TST-22 |
| DLV-DSC-07 | IR | ACTUAL_OBSERVATION | REAL | 공식 source 비교 raw trace | method |
| DLV-DSC-09 | IR | ACTUAL_DEVICE_RUN | REAL | named PoC raw result | candidate·env |

## 4. N/A 후보 36건

Authority flag: `[L]` legal 추가, `[E]` 전문 authority 추가, `[L+E]` 둘 다 추가.

허용 결과는 모든 행에 `N_A_APPROVED / INTERNAL_GAP / EXTERNAL / OK`이며 실제 trigger와 evidence에 따라 제한한다.

| ID | Packet | 적용성 영역 | Authority | Activation trigger | 선행 정보 |
|---|---|---|---|---|---|
| DLV-AIML-18 | SEC-AIML | 모델 승격 평가 | MODEL_VALIDATION [E] | 명명 모델 승격 평가 시작 | 후보·dataset·metric threshold |
| DLV-AIML-19 | SEC-AIML | FP/FN 안전 분석 | MODEL_SAFETY [E] | AIML-18 완료 또는 중대 field defect | FP/FN 표본·defect register |
| DLV-AIML-20 | SEC-AIML | 강건성·편향 | MODEL_GOVERNANCE [E] | 모델 승격 또는 scope 확대 | device/environment matrix |
| DLV-AIML-25 | SEC-AIML | model monitoring | MODEL_OPS·PRIVACY [E] | 시험·운영 traffic 시작 | telemetry·alert 기준 |
| DLV-AIML-26 | SEC-AIML | 재학습·재검토 | MODEL_GOVERNANCE [E] | trigger·정기일·formal request | trigger ledger |
| DLV-CLS-01 | CLOSURE | 종료 계획 | SPONSOR·CLOSURE | 종료 제안과 release scope 확정 | closure proposal |
| DLV-CLS-03 | CLOSURE | 성과·편차 | SPONSOR·ACCEPTANCE | 종료 승인과 인수 결과 | actual performance |
| DLV-CLS-05 | CLOSURE | 최종 인도 index | CONFIG·RECIPIENT | 인도 artifact set 동결 | handover register |
| DLV-CLS-06 | CLOSURE | 불변 archive | RETENTION [L+E] | release와 index 확정 | retention rule |
| DLV-CLS-12 | CLOSURE | 종료 후속 | SPONSOR·RISK | defect/risk/debt 입력 동결 | final ledgers |
| DLV-CLS-13 | CLOSURE | 계약·구독 종료 | PROCUREMENT [L] | 유료 관계 존재 | vendor inventory |
| DLV-DEV-10 | TECH | CI/CD | BUILD·SECURITY [E] | workflow가 release module 처리 | workflow inventory |
| DLV-DEV-11 | TECH | IaC | PLATFORM·SECURITY [E] | IaC/deploy candidate 사용 | infrastructure inventory |
| DLV-DEV-13 | TECH | sample data | DATA·PRIVACY [L+E] | sample data 포함 | data inventory |
| DLV-DEV-15 | TECH | change review | ENGINEERING·QA [E] | 실제 change/review workflow 존재 | review record |
| DLV-DSC-04 | MANAGEMENT | user research | RESEARCH·PRIVACY [L+E] | 연구 승인 또는 실행 | protocol·consent |
| DLV-OPS-07 | CLOSURE | 자동 alert | SRE·SERVICE [E] | alert channel 도입·필수화 | on-call·SLA |
| DLV-OPS-18 | CLOSURE | postmortem | INCIDENT [E] | incident severity/repeat 기준 충족 | incident ledger |
| DLV-REL-03 | RELEASE | version 관리 | RELEASE·SUPPORT | 외부 인도 version 발행 | version policy |
| DLV-REL-04 | RELEASE | release manifest | CONFIG·RELEASE [E] | named RC 생성 | RC identity |
| DLV-REL-05 | RELEASE | source freeze | CONFIG·RELEASE | RC 승인 | immutable source |
| DLV-REL-06 | RELEASE | Android artifact | BUILD·SIGNING [E] | release 후보 존재 | build/signing contract |
| DLV-REL-07 | RELEASE | hash·서명 | SIGNING·SECURITY [E] | 외부 인도 artifact 생성 | artifact bytes |
| DLV-REL-08 | RELEASE | SBOM·provenance | SUPPLY_CHAIN [E] | named artifact scope 확정 | dependency inventory |
| DLV-REL-09 | RELEASE | release notes | PRODUCT·RECIPIENT | 새 version 확정 | previous delta |
| DLV-REL-14 | RELEASE | canary | RELEASE_OPS·RISK [E] | 단계 배포 선택·traffic 사용 | traffic/rollback |
| DLV-SEC-13 | SEC-AIML | remote DAST | APPSEC·STAGING [E] | staging endpoint 승인 | endpoint/account |
| DLV-SEC-14 | SEC-AIML | penetration test | SECURITY·PENTEST [L+E] | RC와 scope·tester 승인 | RC SHA·contract |
| DLV-SEC-18 | SEC-AIML | disclosure | SECURITY_RESPONSE [L+E] | public service·beta 활성화 | channel·safe harbor |
| DLV-TST-10 | TECH | UAT | QA·USER_REP [E] | UAT 계획 승인 | criteria·participant |
| DLV-TST-12 | TECH | performance | PERF_QA [E] | beta milestone·SLO 확정 | workload·threshold |
| DLV-TST-13 | TECH | device compatibility | DEVICE_QA [E] | support matrix 확정 | environment matrix |
| DLV-TST-15 | TECH | usability | UX·PRIVACY [L+E] | 사용성 평가 승인 | participant·consent |
| DLV-TST-16 | TECH | fault recovery | SRE·SAFETY [E] | fault-injection scope 승인 | fault model |
| DLV-TST-17 | TECH | install/update/rollback | RELEASE·SIGNING [E] | signed build 생성 | REL-15·install matrix |
| DLV-WS-16 | CLOSURE | Web/PWA scope | PRODUCT·SPONSOR | Web/PWA 지원 범위 재승인 | acceptance matrix |

## 5. Phase 0 선결 모호성

1. named release generation의 source/build/toolchain/lock/config/model 범위
2. AIML-11 provenance 복구와 재학습 중 어떤 증거가 필요한지
3. AIML-16 fallback 원천 회수 불가 시 재변환·교체 기준
4. dataset 권리·개인정보와 label/data/evaluation/tolerance authority
5. DEV-21과 TST-07의 동일 integration evidence 공동 사용 가능 여부
6. TST-18 zero-defect receipt 허용 여부
7. 지원기기, live TMAP, TalkBack participant의 실제 범위
8. signing identity, key custody, rollback/deploy environment
9. license/legal 최종 authority
10. full repository history 접근 가능 여부
11. INTERNAL_GAP→OK 독립 reviewer와 최소 증거 계약

## 6. Phase 0 수용 기준

- 133/133 행의 provisional 값 검토 완료
- 257/257 `artifact_kind`, `subject_of_truth`, `completion_mode` 확정
- actual event를 억지로 요구하는 predicate 0
- current-state/no-event와 conditional-event authority 규칙 확정
- legal/N/A decision dependency 누락 0
- `PHASE0_STARTED=false`, `EXECUTION_STARTED=false`는 실제 착수 전까지 유지
