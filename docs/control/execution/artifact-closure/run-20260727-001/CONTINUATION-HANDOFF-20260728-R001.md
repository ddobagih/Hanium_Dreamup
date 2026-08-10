# WalkSafe 산출물 종결 작업 인계서

## 0. 문서 정보

| 항목 | 값 |
|---|---|
| 문서 ID | `WS-ARTIFACT-CLOSURE-CONTINUATION-HANDOFF-20260728-R001` |
| 작성일 | `2026-07-28` |
| 상태 | `CURRENT_HANDOFF` |
| 프로젝트 루트 | `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715` |
| 실행 루트 | `docs/control/execution/artifact-closure/run-20260727-001/` |
| 현재 출시 상태 | `NOT_ELIGIBLE` |
| 즉시 재개 작업 | `artifact-register.json content_sha256 재계산 및 검증기 보완` |

이 문서는 컴퓨터 재부팅 뒤 다른 Codex가 기존 대화 없이 작업을 이어가기 위한 현재 상태와 실행 순서를 기록한다.

이 문서가 제품 출시, 산출물 전체 완료, 실제 시험 완료 또는 승인 완료를 주장하지 않는다.

---

## 1. 작업 목적

최종 목적은 한이음 드림업 WalkSafe 프로젝트의 exact257 산출물을 정확하고 통일된 기준으로 완성하고, 산출물에 대응하는 구현·시험·승인·실제 사건 증거까지 acceptance contract에 맞게 결속하는 것이다.

자동 연속 실행 체계나 Goal 엔진 자체가 목적이 아니다.

반드시 지킬 원칙:

1. 문서가 작성됐다는 사실과 산출물이 완료됐다는 사실을 구분한다.
2. 계획·schema·packet·review PASS를 실제 실행·승인·배포·출시 완료로 승격하지 않는다.
3. 기존 정본과 역사 파일은 덮어쓰지 않고 add-only successor를 만든다.
4. exact257 ID, canonical source tuple, acceptance contract와 기존 분류를 보존한다.
5. 사용자 질문은 내부에서 진행 가능한 작업을 모두 수행한 뒤 한 번에 통합한다.
6. 독립 QA, 실제 기기·현장 사건, 외부 권리 사실은 합성하지 않는다.

---

## 2. 사용자 결정과 작업 방식

### 2.1 사용자 의도

- 공수가 많이 들어가더라도 산출물의 정확성·통일성을 우선한다.
- 내부에서 가능한 범위는 사용자 확인 없이 연속 진행한다.
- 병렬 에이전트와 컴퓨터 자원을 충분히 사용해도 된다.
- 다만 과거 작업 중 갑작스러운 로그아웃이 있었으므로 무거운 작업은 동시에 여러 개 실행하지 않는다.
- 가벼운 문서 조사·독립 검수는 병렬화한다.
- 질문은 마지막에 통합한다.

### 2.2 사용자 역할

| 역할 | 현재 지정 |
|---|---|
| 사용자 identity | `김민호` |
| `PROJECT_SCOPE_OWNER` | 김민호, self-asserted |
| `SERVICE_OWNER` | 김민호, self-asserted |
| `PRODUCT_OWNER` | 김민호, self-asserted |
| `INDEPENDENT_QA_REVIEWER` | `UNASSIGNED` |

김민호가 owner 역할을 맡는다는 진술은 저장됐지만, OWNER14에 대한 ID별 `APPROVE_CONTENT` 결정은 아직 수집하지 않았다.

ATTEST4는 independent QA와 owner의 별도 판단이 필요하므로 QA 미지정 상태에서는 닫을 수 없다.

### 2.3 제품 범위 결정

- 범위는 한이음 제출·시연에 한정하지 않고 실제 배포·출시까지 포함한다.
- scope decision exact45는 모두 결정됐다.
- `IN_SCOPE`: 43건
- `OUT_OF_SCOPE_N_A`: `DLV-DSC-04`, `DLV-WS-16`
- `DLV-DSC-04`: 공식 사용자 조사 결과는 현재 범위 N/A다.
- `DLV-WS-16`: end-user PWA 시험만 현재 범위 N/A다. Android 제품과 admin web을 제외하지 않는다.
- 두 N/A 항목은 관련 기능을 다시 승인하면 재활성화한다.

### 2.4 사용자 사실 응답

| 질문 | 사용자 응답과 현재 경계 |
|---|---|
| 후보 모델 학습자 | 김민호가 직접 학습, self-attested |
| 학습 데이터 출처 | 프로젝트 문서 기준, 목록 완전성은 기억하지 못해 `UNKNOWN` |
| 유료 계약·구독·외부업체 | 없음 |
| 승인 staging endpoint·시험 계정 | 없음 |
| 지원 기기 | 현재 연결 `SM-G981N / Android 13` 및 Galaxy S25 |
| Galaxy S25 exact model/OS | `UNKNOWN`, 빈 값 유지 |
| 공식 실기기 시험 | 미실행, 정지 상태 앱 실행은 informal smoke일 뿐 |
| 현장시험 | 미실행 |
| 실제 모델 검토 | 미실행 |
| PoC | 미실행 |

---

## 3. 현재 exact257 정본

### 3.1 현재 subject chain

현재 exact257 진행 상태의 subject는 다음 조합이다.

1. 원장: `phase1-exact257-successor-ledger-r007.json`
2. 완료 boolean coverage wrapper: `packets/phase1-exact257-successor-r008/evidence.json`
3. release status wrapper: `packets/phase1-exact257-successor-r009/evidence.json`
4. LF-inclusive non-self integrity wrapper: `packets/phase1-exact257-successor-r010/evidence.json`
5. 현재 독립 검수: `phase1-exact257-successor-independent-review-r010.md`

R010 독립 검수 판정:

- `BLOCKING 0`
- `MAJOR 0`
- `MINOR 0`
- `PASS_FOR_CONTROLLED_R007_SUBJECT_CHAIN_THROUGH_R010_ONLY`

핵심 물리 SHA-256:

| 대상 | SHA-256 |
|---|---|
| R007 ledger | `4cf29456590aaa6df99a4306e50e9d39bf152b69208b3b78c1d1f6087a0a33e9` |
| R010 evidence | `e878aa9915e549cfd06acaef7f150b75e0d6a0221d6c9978d6597d15c81c8056` |
| R010 receipt | `5e9300013d252932cc696585c8e2d99ddcd434d972647c32a57620e64f62d51a` |

R005~R009는 현재 정본에 이르는 historical correction chain이다. R005~R009의 PASS_WITH_MINOR 결과를 현재 최종 PASS로 인용하지 않는다.

### 3.2 canonical 분류

canonical source snapshot 분류는 변경하지 않았다.

| canonical status | 수 |
|---|---:|
| `OK` | 124 |
| `INTERNAL_GAP` | 48 |
| `EXTERNAL` | 49 |
| `N_A_CANDIDATE` | 36 |
| 합계 | 257 |

### 3.3 현재 progress queue

| queue | 수 |
|---|---:|
| `OK_BASELINE` | 124 |
| `INTERNAL_READY` | 62 |
| `INTERNAL_RUN_REQUIRED` | 24 |
| `OWNER_APPROVAL_PENDING` | 14 |
| `ATTESTATION_REVIEW_PENDING` | 4 |
| `EVIDENCE_FACT_PENDING` | 6 |
| `SCOPE_DECISION_PENDING` | 0 |
| `REAL_EVENT_PENDING` | 21 |
| `SCOPE_N_A_APPROVED` | 2 |
| 합계 | 257 |

현재 완료 인정:

- 기존 baseline OK: 124
- 현재 범위 N/A 종료: 2
- 합계: `126/257`
- open: `131/257`

현재 범위 N/A 종료 2건:

- `DLV-DSC-04`
- `DLV-WS-16`

전역·무조건 artifact completion claim은 0이다. 두 건은 `CLOSED_N_A_FOR_CURRENT_SCOPE`만 주장한다.

### 3.4 계속 0으로 유지해야 하는 값

- formal279 PASS: 0
- verified data rights/privacy fact: 0
- 공식 actual-device receipt: 0
- 실제 field/model/PoC/deployment event: 0
- OWNER14 actual approval: 0
- ATTEST4 independent-QA acceptance: 0
- release candidate: 없음
- release approval: 0
- release eligibility: `NOT_ELIGIBLE`
- policy reopen: `false`

---

## 4. 정책과 release gate

현재 정책:

- `PB-WALKSAFE-FEATURE-POLICY-1.0.1`
- effective decision register:
  `docs/control/decision-interview/walksafe-effective-decision-register-current-20260726-r001.json`

정책을 다시 질문하거나 재개하지 않는다.

열린 release gate 5개:

1. `GATE-PHONE-QUEUE-BYTE-LIMIT`
2. `GATE-SERVER-CAPACITY-STATE-CONTRACT`
3. `GATE-RAW-COLLECTION-RELEASE-REVIEW`
4. `GATE-CLOUD-COST-MEASUREMENT`
5. `GATE-SINGLE-ADMIN-RECOVERY-DRILL`

현재 모두 `NOT_RUN`, 미면제다.

FP-035 경계:

- 보행 중 raw upload 금지
- 보행 종료 뒤 Wi-Fi 전송
- cellular 전송은 명시적 사용자 선택이 있을 때만 허용

---

## 5. 완료된 주요 작업

### 5.1 Android·Web·Gateway

현재 검증 결과:

| 검증 | 결과 |
|---|---|
| Android targeted | `82/82 PASS` |
| Android former-failure clusters | `45/45 PASS` |
| Android full JVM | `759/759 PASS` |
| Android lint | `PASS` |
| Web lint | `PASS` |
| Web typecheck | `PASS` |
| Android gateway typecheck | `PASS` |
| OpenAPI checker | `PASS`, exit 0 |

OpenAPI:

- venv: `.venv`
- Python: `3.14.6`
- requirements: `backend/requirements.txt`
- log:
  `phase1-validation/openapi-check-r002.log`
- receipt:
  `phase1-validation/openapi-check-receipt-r002.json`

현재 구현 baseline:

- `current-implementation-baseline-phase1-r002.json`
- `current-implementation-baseline-phase1-independent-review-r002.md`
- verdict: `PASS_FOR_INTERNAL_PHASE1_FACT_BASELINE_ONLY`

기기 facts:

- ADB observed: `SM-G981N`, Samsung, SDK 33, Android 13
- user-declared scope candidate: Galaxy S25
- Galaxy S25 model/OS: `UNKNOWN`
- 공식 실기기 시험 receipt: 0

### 5.2 데이터·모델 자료 복구

RC2에 없던 자료를 sibling 프로젝트 `/home/ddobagi/Code/hanium-dreamup`에서 찾았다.

| 자료 | SHA-256 |
|---|---|
| dataset manifest | `5ea9796589e02fcb64b82af564400df081731d7b2136e9a654422df56fdb94f6` |
| training results | `1dc4b32335db04910ed51746f4bb3e7955d3f545c25dbd5dd4c44253b9d62dcc` |
| 후보 PT | `a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669` |
| Android TFLite | `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19` |

RC2 선언 hash와 모두 일치했다.

현재 successor:

- `data-model-source-current-state-r002.json`
- `data-model-source-current-state-independent-review-r002.md`
- findings: 0

제한:

- sibling 경로는 RC2 외부이며 immutable copy가 아니다.
- source-list completeness: `UNKNOWN`
- 직접촬영·사용자제공 포함 여부: `UNKNOWN`
- 권리·privacy: `NOT_VERIFIED`
- formal training reproduction: 0
- split/leakage verification: 0
- model approval/release credit: 0

### 5.3 scope45

현재 파일:

- `phase1-user-scope-decision-capture-r001.json`
- `phase1-user-scope-decision-capture-independent-review-r001.md`
- `phase1-scope45-transition-application-r001.json`
- `phase1-scope45-transition-independent-review-r001.md`

검수:

- exact45 unique
- `43 IN_SCOPE`
- `2 OUT_OF_SCOPE_N_A`
- findings 0

### 5.4 신규 INTERNAL_READY 25건

#### AI·개발·보안 9건

Exact IDs:

- `DLV-AIML-19`
- `DLV-AIML-20`
- `DLV-AIML-25`
- `DLV-AIML-26`
- `DLV-DEV-10`
- `DLV-DEV-11`
- `DLV-DEV-13`
- `DLV-DEV-15`
- `DLV-SEC-18`

상태:

- canonical section 보완 완료
- add-only DEV manifest/register 생성 완료
- `content_authored=true` 9건
- acceptance/approval/execution/formal/release credit 0
- independent review findings 0

현재 파일:

- `packets/phase1-ready25-ai-dev-sec/evidence.json`
- `phase1-ready25-ai-dev-sec-check-receipt-r001.json`
- `phase1-ready25-ai-dev-sec-independent-review-r001.md`

주의:

- 이 wave는 `artifact-register.json`을 수정하지 않았다.
- generator 충돌을 피하려고 일부러 제외했다.
- 재개 시 generator source에서 exact9 applicability와 QA 미지정 상태를 반영해야 한다.

#### 종료·운영 13건

Exact IDs:

- `DLV-CLS-04`
- `DLV-CLS-08`
- `DLV-CLS-10`
- `DLV-CLS-11`
- `DLV-CLS-14`
- `DLV-CLS-15`
- `DLV-CLS-16`
- `DLV-OPS-06`
- `DLV-OPS-07`
- `DLV-OPS-11`
- `DLV-OPS-13`
- `DLV-OPS-18`
- `DLV-OPS-22`

구현 경계:

- exact13 전부 `IN_SCOPE`
- `content_authored=13`
- actual result와 approval/event credit 0
- `OPS-18`: incident 0, `NOT_TRIGGERED`
- `CLS-16`: `OPERATIONS_CONTINUE`, shutdown 미발생
- `CLS-10`: recipient/operator 미지정
- 독립 QA 미지정

검증:

- generator `--check`: PASS
- pinned pytest: `19 passed`

역사 packet:

- `packets/phase1-ready25-cls-ops/evidence.json`
- `phase1-ready25-cls-ops-independent-review-r001.md`

현재 successor 후보:

- `packets/phase1-ready25-cls-ops-r002/evidence.json`
- `packets/phase1-ready25-cls-ops-r002/phase1-ready25-cls-ops-check-receipt-r002.json`

현재 successor 후보는 아래 self-digest finding 때문에 아직 최종 PASS가 아니다.

#### Release 3건

Exact IDs:

- `DLV-REL-03`
- `DLV-REL-05`
- `DLV-REL-09`

구현 경계:

- 전부 `IN_SCOPE`
- `content_authored=3`
- release candidate 없음
- approval/build/deploy/event/formal/release credit 0
- 5 gates `NOT_RUN`
- release `NOT_ELIGIBLE`

현재 packet:

- `packets/phase1-ready25-rel/evidence.json`
- `packets/phase1-ready25-rel/phase1-ready25-rel-check-receipt-r001.json`

아래 self-digest finding 때문에 독립 review는 HOLD다.

### 5.5 신규 INTERNAL_RUN_REQUIRED 8건

Exact IDs:

- `DLV-AIML-18`
- `DLV-REL-04`
- `DLV-REL-06`
- `DLV-REL-07`
- `DLV-REL-08`
- `DLV-TST-12`
- `DLV-TST-16`
- `DLV-TST-17`

현재 파일:

- `packets/phase1-run8-readiness/evidence.json`
- `phase1-run8-readiness-check-receipt-r001.json`
- `phase1-run8-readiness-independent-review-r001.md`

판정:

- findings 0
- `PASS_FOR_READINESS_AND_ZERO_CREDIT_BOUNDARY_ONLY`
- actual credit `0/8`
- actual run 0

주요 blocker:

| ID | blocker |
|---|---|
| AIML-18 | 승인된 independent test split·threshold·reviewer 없음 |
| REL-04 | named candidate·clean commit/tag 없음 |
| REL-06 | 승인된 release 계약·signing 없음 |
| REL-07 | signing key/cert/policy/operator attestation 없음 |
| REL-08 | release-specific SBOM/provenance generator 없음 |
| TST-12 | workload·duration·threshold·load driver 없음 |
| TST-16 | fault injector·blast radius·QA 없음 |
| TST-17 | 계획 test case 0, REL-15 미승인, signed old/new build 없음 |

기존 Android/OpenAPI/Web/SBOM/과거 YOLO 결과를 이 exact8의 실제 실행 증거로 재사용하면 안 된다.

---

## 6. 현재 HOLD 원인

### 6.1 발견된 결함

파일:

`docs/deliverables/00-control/artifact-register.json`

현재 값:

| 항목 | 값 |
|---|---|
| 선언 `content_sha256` | `cbbd0225432e559ba09cef1c9c3fec1b7777268e7ad00dbde1e94a8111f52ceb` |
| 현재 object 재계산값 | `3bc5cee452ba58130145d24ca207076c24a4ee19df2d20d2674465c3ce606989` |
| 물리 파일 SHA-256 | `875a21af9277a8caf536520a7c530df5d4504830cc323fbcc5e2620e827f44f1` |

원인:

`scripts/build_walksafe_formal_rel_ops_cls_20260721.py`의 `_ready25_artifact_register()`가 기존 register를 수정한 뒤 내부 `content_sha256`을 다시 계산하지 않는다.

현재 `--check`도 이 내부 self-digest를 검사하지 않는다.

영향:

- 제품 Android/backend 코드 영향 없음
- generated physical file binding은 정상
- exact13/REL3 내용 자체는 정상
- artifact register 내부 무결성 claim만 stale
- exact13 r002와 REL3 r001 독립 review는 `HOLD_FOR_MINOR_INTEGRITY_CORRECTION`

### 6.2 이미 해결된 별도 오류

이전 generator 수정 중 다음 괄호 오류 2곳이 있었으나 사용자 승인 후 수정됐다.

```python
(outputs | side_outputs).items()
```

현재 이 오류는 남아 있지 않다.

### 6.3 현재 generator 상태

- generator source:
  `scripts/build_walksafe_formal_rel_ops_cls_20260721.py`
- current physical SHA-256:
  `9578da58c3563d8439d964a20913f9d7fc2b0b804c426d3d20730193104bd9fe`
- 기본 output: 19
- side output: 5
- physical output byte match: 24/24
- `--check`: PASS
- pinned pytest: 19 passed
- manifest self-digest: PASS
- artifact-register self-digest: FAIL

---

## 7. 재부팅 뒤 첫 실행 순서

### 단계 A. add-only preflight와 현재 결함 수정

1. `scripts/build_walksafe_formal_rel_ops_cls_20260721.py`를 한 번만 읽는다.
2. 첫 write 실행 전에 generator의 packet 상수·출력 경로를 새 add-only successor로 변경한다.
3. 권장 새 경로는 AI·DEV·SEC `r002`, CLS·OPS `r003`, REL `r002`다. 현재 exact9 r001, exact13 r001/r002, REL r001은 historical로 보존한다.
4. 새 successor 대상 경로가 이미 존재하면 write를 중단하는 existence guard를 추가한다.
5. `_ready25_artifact_register()`가 모든 mutation을 마친 뒤 `content_sha256`을 기존 canonical serialization contract로 다시 계산하도록 수정한다.
6. `--check` 경로에 artifact-register self-digest expected/observed equality 검사를 추가한다.
7. 같은 generator pass에서 exact9의 applicability를 `IN_SCOPE`로 반영한다.
8. exact9의 독립 QA는 `UNASSIGNED`, 김민호 owner 역할은 self-asserted, actual approval은 0으로 유지한다.
9. 아래 순서로 write 생성, check, targeted test를 실행한다.

```bash
cd /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715
python3 -B scripts/build_walksafe_formal_rel_ops_cls_20260721.py
python3 -B scripts/build_walksafe_formal_rel_ops_cls_20260721.py --check
.venv/bin/python -m pytest tests/test_walksafe_formal_rel_ops_cls.py -q
```

성공 기준:

- generator check PASS
- pytest `19 passed`
- artifact-register self-digest expected=observed
- manifest self-digest expected=observed
- exact9+13+3 모두 `IN_SCOPE`
- independent QA `UNASSIGNED`
- content authored 25
- actual approval/event/formal/release credit 0

### 단계 B. 새 successor packet 검수

artifact-register와 manifest hash가 바뀌므로 단계 A에서 새 경로로 생성한 packet만 검수한다. 기존 packet을 수정하거나 덮어쓰지 않는다.

검수 대상 add-only successor:

1. AI·DEV·SEC exact9 packet successor
2. CLS·OPS exact13 packet successor
3. REL exact3 packet successor
4. 각 companion check receipt
5. 각 독립 content review

독립 review success criteria:

- exact set 누락·중복 0
- canonical section과 source hash 일치
- content required boundary 충족
- unknown은 blocker+owner+due condition으로 유지
- QA 미지정
- 승인·실행·event·formal·release credit 0
- 5 gates `NOT_RUN`

### 단계 C. exact257 후속 checkpoint

내용 작성만으로 artifact closure를 올리지 않는다.

다음 중 실제 schema에 맞는 최소 add-only 방식을 선택한다.

- per-ID `content_authored/reviewed` progress field가 바뀌면 새 ledger successor
- queue와 ledger row가 변하지 않으면 R007 ledger를 bind하는 progress wrapper successor

보존 값:

- canonical `124/48/49/36`
- queue `124/62/24/14/4/6/0/21/2`
- closure 2
- open 131
- formal279 0
- actual event 0
- approval 0
- release `NOT_ELIGIBLE`

새 successor도 fresh independent review findings 0이 필요하다.

---

## 8. 이후 남은 전체 작업

### 8.1 OWNER14

Exact IDs:

- `DLV-DES-21`
- `DLV-DSC-05`
- `DLV-DSC-06`
- `DLV-DSC-07`
- `DLV-DSC-11`
- `DLV-MGT-03`
- `DLV-MGT-08`
- `DLV-REQ-12`
- `DLV-REQ-13`
- `DLV-REQ-15`
- `DLV-SEC-04`
- `DLV-SEC-05`
- `DLV-SEC-06`
- `DLV-SEC-17`

김민호가 `PROJECT_SCOPE_OWNER`임은 확인됐지만, exact14에 대한 `APPROVE_CONTENT`, `REJECT_CONTENT`, `RETURN_FOR_CHANGE` 결정은 아직 없다.

결정은 exact subject fingerprint와 decision-item manifest에 결속해야 한다.

### 8.2 ATTEST4

Exact IDs:

- `DLV-OPS-17`
- `DLV-OPS-19`
- `DLV-OPS-23`
- `DLV-TST-22`

필요:

- `INDEPENDENT_QA_REVIEWER` accept/reject/return
- OPS 3건 `SERVICE_OWNER` approve/reject/return
- TST 1건 `PRODUCT_OWNER` approve/reject/return

현재 QA가 미지정이므로 blocker다.

### 8.3 EVIDENCE_FACT6

Exact IDs:

- `DLV-AIML-01`
- `DLV-AIML-02`
- `DLV-AIML-03`
- `DLV-CLS-13`
- `DLV-SEC-13`
- `DLV-TST-13`

현재 확보한 것:

- 김민호 직접 학습 self-attestation
- 문서에 선언된 dataset source
- manifest/results/model physical hash match
- 유료 계약 없음
- staging 없음
- `SM-G981N / Android 13` ADB observation
- Galaxy S25 user declaration

아직 부족한 것:

- dataset source-list completeness
- 사용 권리·license 검토
- privacy/consent 검토
- 직접촬영·사용자제공 포함 여부
- Galaxy S25 exact model/OS
- 승인된 target/staging environment

사실이 없다는 응답도 attributable as-of receipt로 정규화해야 한다.

### 8.4 INTERNAL_RUN_REQUIRED 24

새 run8 외에 기존 run16이 있다.

실제 실행 전 공통 요구:

- exact procedure ID/version
- frozen input/environment
- command argv/cwd
- tool/runtime version
- start/end timestamp
- raw output locator와 SHA-256
- exit code/result
- 사전 PASS 기준
- defect/residual risk
- executor와 independent reviewer

명령이 unbound인 항목은 임의로 실행하지 않는다.

### 8.5 REAL_EVENT_PENDING 21

포함되는 사건 유형:

- 지원 대상 실기기 시험
- 실제 보행·현장시험
- 실제 모델 검토
- PoC
- 배포 후 smoke
- canary
- penetration test
- 인수·인계·검수
- 실제 field consent/safety

현재 공식 receipt는 0이다.

### 8.6 formal279와 release

- formal279: `NOT_RUN`, PASS 0
- release gates: 5/5 `NOT_RUN`
- named release candidate: 없음
- signing credential/certificate: 미결속
- approved target environment: 없음
- release approval: 0

실제 배포·출시 범위이므로 이 항목들을 N/A로 제거하면 안 된다.

---

## 9. 리소스 운용

과거 작업 중 갑작스러운 사용자 로그아웃이 있었다.

권장:

- 문서 audit와 독립 review는 2~4개 병렬
- Gradle, pip install, model evaluation, 대형 hash 작업은 한 번에 1개 heavy lane
- Android 전체 test와 모델 평가를 동시에 실행하지 않는다.
- 대형 디렉터리 전체 `ls -R`, 무제한 `rg --files`, 불필요한 전체 재해시는 금지한다.
- 각 wave마다 add-only packet, review, daylog checkpoint를 먼저 남긴다.
- 사용자가 자원 사용을 허용했다고 단일 에이전트만 고집하지 않는다.
- 사용자가 로그아웃 위험을 언급했다고 병렬화 자체를 중단하지 않는다.

---

## 10. 작업 규칙

1. Git reset, checkout, revert 금지.
2. 기존 변경이나 사용자 변경을 되돌리지 않는다.
3. historical receipt·ledger·review를 덮어쓰지 않는다.
4. canonical source file을 generator가 소유하면 generator를 수정하고 재생성한다.
5. 생성기 source와 generated output을 동시에 수동 수정하지 않는다.
6. packet physical hash와 내부 self-digest를 둘 다 검증한다.
7. `content_authored`, `content_reviewed`, `owner_approved`, `executed`, `closed`, `release_eligible`를 별도 축으로 둔다.
8. 독립 review findings가 있으면 add-only correction successor를 만든다.
9. 실제 사용자 답변을 raw transcript로 저장하지 않고 normalized decision/fact만 저장한다.
10. 질문은 더 이상 내부 진행이 불가능할 때 통합한다.

---

## 11. 예상 일정

전제: QA·기기·환경·서명·참여자 등 필요한 입력이 적시에 제공되고 큰 실패가 없을 때.

| 범위 | 예상 |
|---|---:|
| 현재 self-digest 수정·exact25 successor 재검수·checkpoint | 1~2시간 |
| 나머지 내부 문서·증거·검사기 정리 | 4~8시간 |
| 내부 실행 24건과 실패 보완 | 1~3일 |
| 실기기·현장·PoC·배포 사건 | 1~2일 |
| formal279·release gate·최종 감사 | 1~2일 |
| 전체 best-case | 4~7일 |

독립 QA, target environment, signing credential, 현장 조건이 준비되지 않으면 해당 기간은 확정할 수 없다.

---

## 12. 중요 파일 빠른 목록

```text
docs/control/execution/artifact-closure/run-20260727-001/
├── CONTINUATION-HANDOFF-20260728-R001.md
├── phase1-exact257-successor-ledger-r007.json
├── phase1-exact257-successor-independent-review-r010.md
├── phase1-user-scope-decision-capture-r001.json
├── phase1-user-scope-decision-capture-independent-review-r001.md
├── phase1-scope45-transition-application-r001.json
├── phase1-scope45-transition-independent-review-r001.md
├── current-implementation-baseline-phase1-r002.json
├── current-implementation-baseline-phase1-independent-review-r002.md
├── data-model-source-current-state-r002.json
├── data-model-source-current-state-independent-review-r002.md
├── phase1-ready25-ai-dev-sec-independent-review-r001.md
├── phase1-run8-readiness-independent-review-r001.md
├── packets/phase1-ready25-ai-dev-sec/evidence.json
├── packets/phase1-ready25-cls-ops-r002/evidence.json
├── packets/phase1-ready25-rel/evidence.json
├── packets/phase1-run8-readiness/evidence.json
└── phase1-validation/openapi-check-receipt-r002.json
```

Generator:

```text
scripts/build_walksafe_formal_rel_ops_cls_20260721.py
```

현재 defect subject:

```text
docs/deliverables/00-control/artifact-register.json
```

작업 로그:

```text
daylog/2026-07-28.md
```

---

## 13. local-memory 상태

- `memory.start_session`은 정상 동작한 적이 있다.
- `memory.log_work`는 `/home/ddobagi/.codex/memory/memory.sqlite.write.lock` writer lock으로 반복 timeout이 발생했다.
- 재부팅 후 start-session을 한 번 시도하고, 종료 시 log-work를 한 번만 시도한다.
- lock이 반복되면 무한 재시도하지 말고 daylog를 정본 작업 기록으로 사용한다.

---

## 14. 재개 시 첫 보고 형식

다른 Codex는 이 문서를 읽은 뒤 사용자에게 다음 네 줄을 우선 보고한다.

1. current exact257: `126 closed-equivalent / 131 open`
2. scope pending: `0`
3. current blocker: `artifact-register content_sha256 stale`
4. next action: `generator digest fix → regenerate → check/test → independent review`

그 뒤 사용자에게 이미 답한 질문을 반복하지 말고 바로 단계 A부터 이어서 진행한다.
