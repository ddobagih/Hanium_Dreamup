# W3 Engineering Evidence Successor Work Contract R001

## 1. 문서 통제

| 항목 | 값 |
|---|---|
| document_id | `W3-ENGINEERING-EVIDENCE-SUCCESSOR-WORK-CONTRACT-R001` |
| status | `NONCANONICAL_DRAFT` |
| execution_mode | `PLAN_ONLY` |
| execution_started | `false` |
| implementation_authorized | `false` |
| implementation_authorization_status | `ABSENT_DENY_ALL` |
| runner_registration_authorized | `false` |
| canonical_change_authorized | `false` |
| artifact_credit_delta | `0` |
| formal_test_credit_delta | `0` |
| release_gate_delta | `0` |
| prepared_on | `2026-07-31` |
| repository | `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715` |

이 문서는 W3 engineering evidence의 add-only successor 작업을 실행하기 전
범위·입력·출력·검증·중단 조건을 고정하는 비정본 계획 초안이다. 이 문서의
존재는 구현, 실행, 검토 완료, canonical 전환, artifact 완료, 정식 시험 또는
출시 승인이 아니다.

현재 사실 경계는 다음과 같다.

- formal test register: `279/279 NOT_RUN`
- formal test PASS/FAIL credit: `0/0`
- test plan lifecycle/approval: `DRAFT / NOT_APPROVED`
- release gates: `5/5 NOT_RUN`, waiver `0`
- release: `NOT_ELIGIBLE`

## 2. 정확한 결함 원인

역사 W3 builder
`scripts/build_walksafe_w3_engineering_evidence_20260726.py`는
`docs/deliverables/06-testing/registers/test-cases.json`의 물리 SHA-256을
`19a6b425bf3a0ee880f1c9229a42b96845ab4c3a1f99037783fe872426e0deee`
로 고정한다. 역사 test의 공통 fixture는 이 역사 preimage가 아니라 같은 경로의
live 파일을 복사한다.

live canonical `PLANNED_TEST_CASES` binding은 seq39에서
`fc51836c1dddd8852a74805e7fc5b1f6e647f461168139ae2565318745f87f2e`
로 전진했다. 따라서 fixture가 builder의 full-file SHA 검사를 통과하지 못하고
후속 검증 전에 `formal test register differs from canonical SHA-256`로
중단된다.

현재 targeted 결과는 `22 passed, 26 failed`다. 26개 고유 test method의
실패는 같은 선행 SHA 불일치에서 발생하며, 원장 내 279개 case의 ID 집합·순서나
실행 상태가 변해서 발생한 실패가 아니다.

## 3. Live formal register exact facts

| 항목 | 재계산 값 |
|---|---|
| path | `docs/deliverables/06-testing/registers/test-cases.json` |
| physical SHA-256 | `fc51836c1dddd8852a74805e7fc5b1f6e647f461168139ae2565318745f87f2e` |
| bytes | `2592818` |
| version | `0.2.0` |
| lifecycle | `DRAFT` |
| verification | `NOT_RUN` |
| approval | `NOT_APPROVED` |
| release | `NOT_ELIGIBLE` |
| case count | `279` |
| unique ID count | `279` |
| ID-set SHA-256 | `8ab0a039d0a6f9fcaad753bac08428da0f74ef27d40426ad4da49a28d5a0f167` |
| ordered-ID SHA-256 | `681b6ad6c222aa73dfcf1bedb021279eca72342a108017688817e3afbfdfe395` |
| `execution_status=NOT_RUN` count | `279` |
| `result=null` count | `279` |

seq39은 위 물리 SHA의 canonical binding만 승인했다. content suitability,
test-plan approval, formal PASS, artifact state 또는 release 상태를 승인하지
않았다. successor도 이 경계를 그대로 보존해야 한다.

## 4. Immutable predecessor bindings

아래 값은 이 계획 작성 직전에 live filesystem에서 다시 계산했다. 모든
predecessor는 read-only다.

| 역할 | path | bytes | SHA-256 |
|---|---|---:|---|
| historical builder | `scripts/build_walksafe_w3_engineering_evidence_20260726.py` | 108715 | `404b55e3476478990732dacad10da291704f88e64629c2adecf6cb467b2ae941` |
| historical test | `tests/test_walksafe_w3_engineering_evidence_20260726.py` | 59320 | `b1075d37870fc575da050a6fec701015651de4476b517c85d51c898d11dccb06` |
| run003 receipt | `docs/control/execution/artifact-remediation/20260726/w3/run-20260726-003/command-receipt.json` | 6114 | `899d88cfd6337e80992c1ccaba620d23fdca4a611f51aeac9cc19923a7b3cdf4` |
| evidence003 manifest | `docs/control/execution/artifact-remediation/20260726/w3/evidence-20260726-003/engineering-evidence-manifest.json` | 5215 | `13674e1132727cd7d065bf67658505d8ba2859bf1c2680e316495dc6738825d5` |
| evidence003 source snapshot | `docs/control/execution/artifact-remediation/20260726/w3/evidence-20260726-003/source-snapshot.json` | 637704 | `9db28f43297b961a0e7290c1f85f706d2ca34b84c95879e71293f32a4266e103` |
| evidence003 module inventory | `docs/control/execution/artifact-remediation/20260726/w3/evidence-20260726-003/module-inventory.json` | 86869 | `5473ba952634f7c9596ce970d377c62bed78beba9be1a86b846d29ac78830fb1` |
| evidence003 lock inventory | `docs/control/execution/artifact-remediation/20260726/w3/evidence-20260726-003/lock-inventory.json` | 576621 | `baddd42d96d21a751df4494a48fa0b1e81f7df233c60d63981dd4e754ee5df04` |
| evidence003 fixture inventory | `docs/control/execution/artifact-remediation/20260726/w3/evidence-20260726-003/fixture-inventory.json` | 68485 | `be584ced83fad77cf859dbd5f3ec2e3c2ba8ba56c848d7a16afccdddbc6a1aa6` |
| evidence003 build provenance | `docs/control/execution/artifact-remediation/20260726/w3/evidence-20260726-003/internal-build-provenance.json` | 126562 | `56b7ec0a352bdf590846bcda6ec69d7594a68989d1326917c43874ea016e0492` |
| evidence003 SPDX | `docs/control/execution/artifact-remediation/20260726/w3/evidence-20260726-003/sbom.spdx.json` | 907864 | `7599f3129872f20b44ca9b8c6363b29880018f536a54100279cd4a90a66ddb9e` |
| evidence003 CycloneDX | `docs/control/execution/artifact-remediation/20260726/w3/evidence-20260726-003/sbom.cyclonedx.json` | 815731 | `cdad7be21efe592c5d3ae429b3718a2561f475c3bccc7a98e3430beaf876cfce` |
| W3 independent review | `docs/control/execution/artifact-remediation/20260726/w3/independent-review.md` | 11495 | `006f676b0eb06df3843c1838c7adc247747f886369242cb4c087b26b2f837107` |
| W3 implementation receipt | `docs/control/execution/artifact-remediation/20260726/w3/implementation-receipt.json` | 10298 | `ebd8e565a3c9111b2ef1f5cd66f1285d51b1d2ac6d2169e482f869c309f00dcb` |
| formal279 immutable record | `docs/control/execution/artifact-closure/run-20260727-001/formal279-not-run-control-record.json` | 41119 | `41a862efe4171ebcaecb4198dce9ddf3a1086d458e3afe0725901cb82f61dd81` |
| formal279 manifest | `docs/control/execution/artifact-closure/run-20260727-001/formal279-not-run-control-manifest.json` | 1415 | `ec697cdf5730e2cc6d9e640fd6c566673ac9f45780918c85bf5159b1afe24706` |
| formal279 receipt | `docs/control/execution/artifact-closure/run-20260727-001/formal279-not-run-control-receipt.json` | 2048 | `f95f4b381382cc92ef71a0eacd965668ba51c5a91af5d8c460ecd5b1b133ea3d` |
| seq38 test-register preimage | `docs/control/history/canonical-preimages/sha256/19a6b425bf3a0ee880f1c9229a42b96845ab4c3a1f99037783fe872426e0deee.json` | 2590342 | `19a6b425bf3a0ee880f1c9229a42b96845ab4c3a1f99037783fe872426e0deee` |

어떤 실행도 위 파일이나 `run-20260726-003`,
`evidence-20260726-003`을 output target으로 사용해서는 안 된다. 역사 builder의
`--write`는 최종 경로를 `os.replace`할 수 있으므로 역사 output 경로에 대한
재실행은 명시적으로 금지한다.

## 5. 계획검수 전제와 예약 successor 경로

경로 집합을 서로 반대 조건으로 분리한다.

### 5.1 `PLAN_REVIEW_PREREQUISITE`

아래 파일은 구현 전에 `MUST_EXIST`인 regular file이다.

| 역할 | 경로 | 구현 전 조건 |
|---|---|
| 이 계획서의 독립검수 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/W3-ENGINEERING-EVIDENCE-SUCCESSOR-WORK-CONTRACT-INDEPENDENT-REVIEW-R001.md` | 수정된 이 계약의 exact SHA-256/bytes를 결속하고 findings `0/0/0` |

symlink는 허용하지 않는다. 검수 파일은 별도 검수자가 이 계획의 최종 물리
SHA-256과 bytes를 받은 뒤 add-only로 생성한다.

### 5.2 `SUCCESSOR_CREATE_TARGETS`

아래 여섯 target은 구현 직전까지 모두 `MUST_BE_ABSENT`다. 이름은 예약일 뿐
이 계획으로 생성 권한이 생기지 않는다.

| 역할 | 예약 경로 |
|---|---|
| successor builder | `scripts/build_walksafe_w3_engineering_evidence_20260731_r001.py` |
| successor test | `tests/test_walksafe_w3_engineering_evidence_20260731_r001.py` |
| successor raw run | `docs/control/execution/artifact-remediation/20260726/w3/run-20260731-004/` |
| successor evidence | `docs/control/execution/artifact-remediation/20260726/w3/evidence-20260731-004/` |
| successor implementation receipt | `docs/control/execution/artifact-remediation/20260726/w3/implementation-receipt-20260731-r002.json` |
| successor independent technical review | `docs/control/execution/artifact-remediation/20260726/w3/independent-review-20260731-r002.md` |

## 6. Exclusive-create와 fail-closed 계약

실행 승인을 받더라도 `SUCCESSOR_CREATE_TARGETS` 여섯 개는 생성 전에
regular-file/directory 부재를 `lstat` 기준으로 확인한다.

1. successor create target이 file, directory 또는 symlink로 이미 존재하면
   즉시 중단한다.
2. 파일은 exclusive create(`O_CREAT|O_EXCL` 또는 Python `xb`)로만 생성한다.
3. directory는 단일 exclusive create를 사용하고 기존 directory 재사용을
   금지한다.
4. successor builder는 임시파일 exclusive create 뒤에도 기존 final path를
   replace하지 않는다. final target 부재를 재확인하고 no-clobber publish한다.
5. 여섯 successor create target 중 하나라도 존재하거나 predecessor
   hash/bytes가 위 표와 다르면 아무 파일도 생성하지 않는다.
6. 부분 생성이 발생하면 그것을 성공으로 재사용하지 않는다. 생성된 add-only
   candidate를 `INCOMPLETE_DO_NOT_USE`로 식별하고 새 revision 계획 없이는 삭제,
   overwrite 또는 retry하지 않는다.

## 7. 승인 후에만 가능한 구현 범위

현재 `implementation_authorization_status`는 `ABSENT_DENY_ALL`이다. 유효한
승인 subject는 exact 문자열 `W3_SUCCESSOR_INTERNAL_EVIDENCE_ONLY`이며, 승인은
다음을 모두 물리 결속해야 한다.

- 수정 완료된 이 계약의 path, SHA-256, bytes
- §5.1 findings-zero 계획검수의 path, SHA-256, bytes
- §5.2의 exact 여섯 successor create target
- runner, canonical, checkpoint, formal 실행, artifact/Gate/release credit이
  승인 범위 밖이라는 명시적 제외

위 결속이 없는 일반적·포괄적 “진행” 지시는 W3 successor 구현승인으로
해석하지 않는다.

별도 구현 승인은 다음 최소 범위에만 적용한다.

1. 역사 builder와 test를 복제한 새 versioned successor를 작성한다.
2. full-file formal register pin을 live `fc518...`로 바꾼다.
3. 279 unique ID, ID-set hash, ordered-ID hash, `279 NOT_RUN`,
   `279 result=null` 검증은 유지한다.
4. 새 builder/test/document ID가 predecessor path와 SHA-256을 명시하도록 한다.
5. evidence output의 모든 document/generation ID를 새 revision으로 바꾼다.
6. internal evidence와 formal 279를 분리하고 approval/release claim을 계속
   `NOT_CLAIMED / NOT_RUN / NOT_ELIGIBLE`로 둔다.
7. historical rejection test가 mutation 전 stale mismatch로 통과하지 않도록
   canonical fixture와 forged fixture의 출발 SHA를 각각 명시적으로 확인한다.
8. §8의 exact 다섯 raw command와 closed output set을 생성하는 `--run-fresh`,
   `--stage-android`, `--package-gateway`, `--capture-subjects` mode를
   successor builder에 한정해 구현한다.

다음은 구현 승인 범위 밖이다.

- 기존 builder/test/run/evidence/review/receipt 수정
- `scripts/run_walksafe_test_layers_20260711.sh` 수정 또는 test layer 등록
- live test register, test plan 또는 다른 canonical 파일 수정
- artifact ledger, Goal, checkpoint 또는 transition history 수정
- formal execution, 실제 기기 시험, release gate 실행
- artifact/formal/release credit 부여

## 8. 실행·검증·검수 순서

아래 단계는 서로 합치거나 순서를 바꾸지 않는다.

### A. Preflight

1. 별도 구현 권한 문구와 범위를 확인한다.
   승인에는 `W3_SUCCESSOR_INTERNAL_EVIDENCE_ONLY`, 최종 계약과 findings-zero
   계획검수의 exact path/hash/bytes, 여섯 create target이 모두 있어야 한다.
2. predecessor 표의 모든 path/hash/bytes를 다시 계산한다.
3. live formal register의 SHA/bytes, 279 ID/order/status/result-null을 다시
   계산한다.
4. §5.1 계획검수 regular file이 존재하고 최종 계약의 hash/bytes와 findings
   `0/0/0`을 결속하는지 확인한다.
5. §5.2 successor create target 여섯 개가 전부 존재하지 않음을 확인한다.
6. v2.4 continuation과 Goal graph quick check가 PASS인지 확인한다.

### B. Successor source

1. successor builder와 test만 exclusive-create한다.
2. targeted negative test로 old pin 또는 forged register를 거부하는지 확인한다.
3. fresh fixture가 live pin을 사용하고 279 계약을 모두 확인하는지 검증한다.
4. targeted successor suite가 모두 PASS하기 전에는 raw run을 만들지 않는다.

### C. Fresh run/evidence

1. successor builder의 `--run-fresh`로 fresh `run-20260731-004/`를
   exclusive-create한다. receipt의 command sequence는 아래 exact 다섯 개다.

| 순서 | command ID | cwd | argv | log | output closed set |
|---:|---|---|---|---|---|
| 1 | `android-internal-build` | `apps/android` | `["./gradlew",":app:assembleDebug",":adminapp:assembleDebug"]` | `logs/android-internal-build.log` | `[]` |
| 2 | `android-artifact-stage` | `.` | `["python3","-B","scripts/build_walksafe_w3_engineering_evidence_20260731_r001.py","--stage-android","--repo-root",".","--run-dir","docs/control/execution/artifact-remediation/20260726/w3/run-20260731-004"]` | `logs/android-artifact-stage.log` | `["artifacts/user-app-internal.apk","artifacts/admin-app-internal.apk"]` |
| 3 | `gateway-internal-build` | `apps/android-gateway` | `["npm","run","build"]` | `logs/gateway-internal-build.log` | `[]` |
| 4 | `gateway-artifact-package` | `.` | `["python3","-B","scripts/build_walksafe_w3_engineering_evidence_20260731_r001.py","--package-gateway","--repo-root",".","--run-dir","docs/control/execution/artifact-remediation/20260726/w3/run-20260731-004"]` | `logs/gateway-artifact-package.log` | `["artifacts/android-gateway-dist.tar.gz"]` |
| 5 | `source-subject-capture` | `.` | `["python3","-B","scripts/build_walksafe_w3_engineering_evidence_20260731_r001.py","--capture-subjects","--repo-root",".","--run-dir","docs/control/execution/artifact-remediation/20260726/w3/run-20260731-004"]` | `logs/source-subject-capture.log` | `["subjects/backend.json","subjects/model.json","subjects/config.json"]` |

2. raw run의 exact file set은 위 log 5개, output 6개, 새
   `command-receipt.json` 1개, 합계 12개다. receipt에는 각 command의 실제
   exit code, 시작·종료시각, tool version, argv/cwd와 새 log/output
   SHA-256/bytes만 기록한다.
3. 과거 run/log/artifact의 복사·재라벨·`PASS_REUSED`는 금지한다. 필요한
   tool 또는 build 환경이 없으면 해당 branch를 보류하고 과거 run으로
   대체하지 않는다.
4. fresh `evidence-20260731-004/`에만 `--write`한다.
5. 같은 builder·run·output으로 `--check`한다.
6. source snapshot, fixture inventory, SPDX, CycloneDX, provenance와 manifest의
   path/SHA/bytes DAG를 재계산한다.
7. formal status는 계속 `279/279 NOT_RUN`, approval은 `NOT_CLAIMED`,
   release는 `NOT_ELIGIBLE`이어야 한다.

### D. Receipt와 독립검수

1. 새 implementation receipt를 predecessor와 새 evidence의 exact hash/bytes에
   결속해 exclusive-create한다.
2. 실행자와 다른 검토자가 source diff, no-clobber 계약, targeted suite,
   write/check 결과, output DAG와 claim boundary를 읽기 전용으로 재검증한다.
3. independent review는 `BLOCKING=0`, `MAJOR=0`, `MINOR=0`일 때만 내부
   successor 후보를 수용할 수 있다.
4. 이 수용은 formal test-plan approval, artifact completion 또는 release
   eligibility가 아니다.
5. evidence와 receipt 생성 뒤 v2.4 continuation과 Goal graph quick check를
   다시 실행한다. 이 PASS는 control snapshot 비회귀만 뜻한다.

### E. 별도 runner 결정

runner 등록은 위 successor 생성·검수와 분리한다. 새 사용자 승인과 Coordinator의
managed snapshot transaction 없이 runner를 수정하지 않는다. 승인 시에도
`scripts/run_walksafe_test_layers_20260711.sh`의 before/after SHA-256, bytes,
mode, 603-path snapshot 영향과 나머지 미등록 test를 함께 검토해야 한다.

## 9. 검증 명령 계약

실제 successor 이름이 위 예약값과 일치한다는 전제에서 다음을 사용한다.

```bash
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. .venv/bin/python -B -m pytest \
  -q -p no:cacheprovider \
  tests/test_walksafe_w3_engineering_evidence_20260731_r001.py

PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. .venv/bin/python -B \
  scripts/build_walksafe_w3_engineering_evidence_20260731_r001.py \
  --run-fresh --repo-root . \
  --run-dir docs/control/execution/artifact-remediation/20260726/w3/run-20260731-004

PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. .venv/bin/python -B \
  scripts/build_walksafe_w3_engineering_evidence_20260731_r001.py \
  --write --repo-root . \
  --run-dir docs/control/execution/artifact-remediation/20260726/w3/run-20260731-004 \
  --output-dir docs/control/execution/artifact-remediation/20260726/w3/evidence-20260731-004

PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. .venv/bin/python -B \
  scripts/build_walksafe_w3_engineering_evidence_20260731_r001.py \
  --check --repo-root . \
  --run-dir docs/control/execution/artifact-remediation/20260726/w3/run-20260731-004 \
  --output-dir docs/control/execution/artifact-remediation/20260726/w3/evidence-20260731-004

PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. .venv/bin/python -B \
  scripts/check_walksafe_project_continuation_v2_4.py \
  --root . \
  --checkpoint docs/control/walksafe-project-continuation-checkpoint.json

PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. .venv/bin/python -B \
  scripts/check_walksafe_goal_graph_v2_4.py \
  --root . \
  --checkpoint docs/control/walksafe-project-continuation-checkpoint.json
```

두 quick check는 preflight와 evidence/receipt 생성 후 각각 실행한다. PASS는
v2.4 control snapshot을 훼손하지 않았다는 비회귀 의미뿐이며 W3 승인, formal
279 실행 또는 새 evidence의 내용 적합성 검증이 아니다.

runner 등록이 별도 승인된 뒤에만 다음을 추가한다.

```bash
scripts/run_walksafe_test_layers_20260711.sh validate
```

## 10. `48/48 PASS`의 제한된 의미

읽기 전용 조사에서 runtime pin만 live SHA로 대체한 simulation과 역사 preimage를
역사 fixture에 공급한 simulation은 각각 기존 targeted suite `48/48 PASS`를
재현했다. 이는 stale full-file pin 하나가 현재 26개 실패의 공통 원인이라는
진단 근거다.

향후 successor suite의 `48/48 PASS`는 오직 새 builder의 내부 단위·보안·무결성
회귀가 통과했다는 뜻이다. 다음을 뜻하지 않는다.

- formal 279를 실행했다는 뜻
- test plan이 승인됐다는 뜻
- 실제 기기·현장·접근성·보안 검증 완료
- W3 artifact 또는 전체 프로젝트 완료
- release gate 통과나 waiver
- release eligibility

따라서 `48/48 PASS`가 나와도 credit delta는 artifact `0`, formal test `0`,
release gate `0`이다.

## 11. Stop conditions

다음 중 하나라도 발생하면 즉시 중단하고 successor를 current로 주장하지 않는다.

- 구현·runner·canonical 변경에 필요한 별도 권한이 없음
- predecessor path/hash/bytes 불일치
- live register SHA/bytes 또는 279 ID/order/status/result-null 불일치
- §5.1 계획검수가 없거나 regular file이 아니거나 최종 계약 hash/bytes와
  findings `0/0/0`을 결속하지 않음
- §5.2 successor create target 중 하나라도 이미 존재하거나 symlink임
- 기존 W3 또는 formal279 history를 수정·덮어쓸 필요가 생김
- successor document/generation ID가 predecessor ID를 재사용함
- targeted suite가 `48/48 PASS`가 아님
- `--write`와 `--check`가 byte-exact하게 일치하지 않음
- output manifest의 path/SHA/bytes 또는 DAG 불일치
- formal status가 `NOT_RUN`에서 상승함
- approval, artifact completion, gate 또는 release credit을 암시함
- independent review finding이 `0/0/0`이 아님
- runner 등록이 같은 작업에 묶이거나 managed snapshot 권한이 없음
- v2.4 continuation 또는 Goal graph quick check 실패
- exact raw command 5개를 fresh 실행할 도구·build 환경이 없거나 raw run
  closed set이 12개와 다름

## 12. 완료 조건

이 계획서 자체의 완료 조건은 지정된 단일 noncanonical file의 존재와 내용
자체검증뿐이다. successor 구현·실행·검수는 아직 시작하지 않았다.

- `EXECUTION_STARTED=false`
- `IMPLEMENTATION_AUTHORIZED=false`
- `IMPLEMENTATION_AUTHORIZATION_STATUS=ABSENT_DENY_ALL`
- `RUNNER_REGISTRATION_AUTHORIZED=false`
- `CANONICAL_CHANGE_AUTHORIZED=false`
- `ARTIFACT_CREDIT_DELTA=0`
- `FORMAL_TEST_CREDIT_DELTA=0`
- `RELEASE_GATE_DELTA=0`
