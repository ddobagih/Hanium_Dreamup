# W5 보안·개인정보 산출물 독립 검토

- 검토 ID: `WS-ARTIFACT-REMEDIATION-W5-INDEPENDENT-REVIEW-20260727-001`
- 검토일: `2026-07-27`
- 검토 역할: W5 작성자와 분리된 독립 reviewer
- 검토 범위: W5 exact 9, current-state JSON/Markdown 3쌍, scan run 001~003, dependency remediation receipt, TLS 수정과 대상 시험
- 최종 판정: `NO_GO_REQUIRES_CORRECTION_AND_REREVIEW`
- 현재 finding: `blocking 2 / major 1 / minor 0`
- status delta 생성 허용: `false`

현재 수치, 실행 경계와 보수적 `NOT_RUN` 표시는 대체로 정확하지만, source
binding과 `DLV-SEC-11`·`DLV-SEC-15` 필수 내용이 완료되지 않았다. 따라서 의도한
`OK SEC02/03/09/11/15`, `INTERNAL_GAP SEC01/10/12`, `EXTERNAL SEC06`
projection을 아직 중앙 delta에 반영하면 안 된다.

이 판정은 제품 보안 취약점이 확인됐다는 뜻이 아니다. 현재 문서가 스스로 선언한
artifact completeness와 evidence custody를 충족하는지를 판정한 것이다.

## 1. 동결된 검토 subject

Subject-set SHA-256은 경로를 bytewise 정렬하고 각 항목을
`<repository-relative-path><TAB><file-sha256><LF>`로 직렬화해 계산했다.

- subject file count: `19`
- subject-set SHA-256: `70892c0d8f7d8eb536ba005625cdc4b1f77bc86459623b50173484b1fb16610c`
- source commit: `null`
- build ID: `null`
- worktree boundary: current dirty-worktree exact file bytes
- whole repository frozen: `false`

| Subject path | File SHA-256 |
|---|---|
| `docs/control/artifact-types.json` | `73a17c237a0e2fb6b25fb5a34203d187f25cd0571d879ffa3a6a2d63ed5c5fbb` |
| `docs/control/execution/artifact-audits/20260726/security-ai-audit.json` | `4eb543de60cb07d267f52af17f725036aa79520da883ba02814456dbfe4dbd14` |
| `docs/control/execution/artifact-remediation/20260726/w3/evidence-20260726-003/lock-inventory.json` | `baddd42d96d21a751df4494a48fa0b1e81f7df233c60d63981dd4e754ee5df04` |
| `docs/control/execution/artifact-remediation/20260726/w5/privacy-consent-secrets-current-state.json` | `ba18e837461ce59106cb75752f30c13eff478c7cd11fba0d8c370f1e60821d7d` |
| `docs/control/execution/artifact-remediation/20260726/w5/privacy-consent-secrets-current-state.md` | `344e5d720eacd415cd6adb44551f8286fac4a3a52a139563c934c38b720f1869` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-plan-threat-risk-current-state.json` | `2e5119f2253519f4fd329f54363c31738ef2a46d9991f71a2407ba631e3e56a7` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-plan-threat-risk-current-state.md` | `31b3ff3c4775e81cf9bae1945246a7507c6260196a44404c230dd587c70a87cf` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-current-state.json` | `0c3e550517de60c08646657d16582769a8a536412560837bcf94812620aa1dc0` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-current-state.md` | `289e5b97d9cd573b9a4260ea53c838464e7ecc75cca926b59a3fb3441ad6742f` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/dependency-remediation-20260727-001/changes.json` | `052c7d08de49efd119fcf205d7f8aa698c8c40bb8559ea012bca7329d31b40b6` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/dependency-remediation-20260727-001/execution-lineage.json` | `5d159ce8a4483cdce319a58dd49ebb53128f4f04fd591a19df5a63175d22a767` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/dependency-remediation-20260727-001/manifest.json` | `fa1bbe0bf3072ef72c7d529b962f42bb47947fdc4f98991fa040456dba3f5153` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/dependency-remediation-20260727-001/package-delta.json` | `e3fdc3940bead48cdce49cc914b9397d0e0a3c967fc5d60f9e3bea92e3304c5c` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/dependency-remediation-20260727-001/validation-summary.json` | `87994ede6f09a1bd3fd43ea0fc6c26233ca56df171adcf436bab7908d9c3b1d7` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-001/execution-summary.json` | `88c16018d88c5d5983d531c62b4908eea9ee1d1cbccd58841767668132f12455` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-002/execution-summary.json` | `47be8e6516f423381a51a616b6bbebf8b984bc57cca87b2eef74df20996beb0a` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-003/execution-summary.json` | `ce9e230afeabe591728de39f1e15a050023581751a6f03a660a57970c943e372` |
| `scripts/check_walksafe_trusted_proxy_20260716.py` | `2a9568b6d207a76f78a0aefde1e77d2a639c042b1a4df4ef5501b3fad4d975b7` |
| `tests/test_walksafe_full_rc_tooling.py` | `dd4e1c2fd0d298e670e662b27496f7b986333be29f26fbd29ba31b702dd62874` |

이 검토 파일 자체는 후속 재검토 또는 중앙 패키지가 exact SHA-256으로 결속해야
한다. 현재 `NO_GO` 검토를 status transition의 승인 근거로 사용할 수 없다.

## 2. Findings

### `W5-B01` privacy source binding 4개가 존재하지 않는 경로를 참조함

- severity: `BLOCKING`
- 영향 ID: `DLV-SEC-06`, `DLV-SEC-09`
- 영향: JSON이 선언한 `INTERNAL_TRACE_COMPLETE`와 source hash custody를 현재
  경로로 재현할 수 없다. `SEC09`의 OK 전환을 막는다. `SEC06`의 외부 법률
  경계 자체는 유지되지만 내부 trace complete 주장은 수정 전 사용할 수 없다.

| Source ID | 잘못된 현재 경로 | 확인된 동일-byte 경로 |
|---|---|---|
| `SRC-ADM-API` | `apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminSecurityApi.java` | `apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityApi.java` |
| `SRC-ADM-HTTP` | `apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminSecurityHttpClient.java` | `apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClient.java` |
| `SRC-W3-TRACE` | `docs/control/execution/artifact-remediation/20260726/w3/engineering-evidence-trace.json` | `docs/control/execution/artifact-remediation/20260726/w3/engineering-trace-current-state.json` |
| `SRC-FP013-VERIFY` | `docs/control/execution/walksafe-epic-02-fp013-integrated-consent-verification-result-20260725-r001.json` | `docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-013-R001/verification-result.json` |

네 대체 파일은 각각 문서가 선언한 SHA-256과 byte length가 정확히 일치한다.
내용을 재작성하지 말고 source path만 실제 경로로 정정한다.

필수 수정:

- privacy JSON과 Markdown의 네 경로를 함께 정정한다.
- `source_bindings_sha256`, `content_sha256`, 관련 section fingerprint를 정정된
  동일 in-memory model에서 다시 계산한다.
- JSON/Markdown projection을 다시 생성하고 재검토 subject를 새 digest로 동결한다.

### `W5-B02` SEC11·SEC15 원장이 artifact catalog 필수 내용을 충족하지 않음

- severity: `BLOCKING`
- 영향 ID: `DLV-SEC-11`, `DLV-SEC-15`
- 영향: 수입 개수는 맞지만 register completeness가 artifact completeness와
  같지 않다. 두 ID를 현재 상태에서 OK로 전환하면 필수 필드가 없는 row를
  완료로 오판한다.

`DLV-SEC-11` 누락:

- OSV database 조회/갱신 기준 시각 또는 동결된 DB snapshot 식별자
- backend logical cluster와 Android coordinate별 advisory/CVE 식별자 결속
- coordinate별 reachability 판정 또는 명시적 `NOT_ASSESSED`와 owner·due date
- 완화·업데이트, 예외 여부·만료, 재검사 상태와 판정
- Android 16 coordinate별 source evidence ID와 release 영향

`DLV-SEC-15` 누락:

- SAST/SCA finding row별 자산·버전·환경
- severity와 exploitability
- owner·due date·상태
- 수정·완화 evidence와 재검사 연결
- CVE/결함/release 영향 연결

현재 SAST family 집계와 Android 공통 action은 source result 누락을 막는
reconciliation에는 유효하지만, 각 취약점 row의 필수 lifecycle 필드를 대신하지
못한다. accepted context도 waiver가 아니다.

필수 수정:

- 단위 계약을 유지하면서 각 row 또는 hash-bound coordinate subregister에 위
  필드를 추가한다.
- 아직 판단하지 않은 값은 비워 두지 말고 `NOT_ASSESSED`와 owner·due date를
  기록한다.
- advisory alias를 대화나 Markdown에 대량 출력할 필요는 없다. 별도 결정론적
  JSON register와 SHA-256을 두고 Markdown에는 stable ID와 판정만 투영할 수 있다.
- 수정 전까지 `SEC11`, `SEC15`는 `INTERNAL_GAP`을 유지한다.

### `W5-M01` scan run의 backward hash binding과 JSON/Markdown parity가 불완전함

- severity: `MAJOR`
- 영향 ID: `DLV-SEC-11`, `DLV-SEC-15`
- 영향: `scan_runs[]`는 summary 경로와 집계만 기록하고 summary file SHA-256과
  byte length를 기록하지 않는다. 특히 current authoritative Run003을 현재-state
  JSON이 backward binding하지 않는다.

현재 Run summary SHA-256:

| Run | Summary SHA-256 |
|---|---|
| `W5-SECURITY-SCAN-20260727-001` | `88c16018d88c5d5983d531c62b4908eea9ee1d1cbccd58841767668132f12455` |
| `W5-SECURITY-SCAN-20260727-002` | `47be8e6516f423381a51a616b6bbebf8b984bc57cca87b2eef74df20996beb0a` |
| `W5-SECURITY-SCAN-20260727-003` | `ce9e230afeabe591728de39f1e15a050023581751a6f03a660a57970c943e372` |

각 summary 내부의 stdout, stderr와 report path·SHA-256·byte length는 실제
파일과 모두 일치했다. 문제는 current-state에서 summary로 향하는 마지막
backward edge가 path-only인 점이다.

Markdown parity 누락:

- backend SCA stable ID `W5-SCA-PYPI-*` 4개가 Markdown에 투영되지 않음
- Android stable ID `W5-SCA-MAVEN-001`~`016`과 개별 coordinate가 Markdown에
  투영되지 않음
- JSON의 16-row 원장 대신 Markdown에는 총계만 있음

필수 수정:

- `scan_runs[]` 또는 별도 `source_bindings[]`에 summary SHA-256과 byte length를
  기록한다.
- Run summary가 이미 결속한 raw report는 중복 복사하지 않고 transitive binding
  규약을 명시한다.
- Markdown에 stable ID와 package-version coordinate를 투영하거나, 명시적으로
  정의한 lossless projection manifest와 fingerprint를 결속한다.
- scan JSON content fingerprint와 Markdown projection fingerprint를 추가해
  재검토가 같은 모델을 판정하도록 한다.

## 3. 확인된 정합성

### Exact 9

정규화된 ID 집합은 정확히 다음 9개이며 중복이 없다.

`DLV-SEC-01`, `DLV-SEC-02`, `DLV-SEC-03`, `DLV-SEC-06`,
`DLV-SEC-09`, `DLV-SEC-10`, `DLV-SEC-11`, `DLV-SEC-12`,
`DLV-SEC-15`

### SAST

- Run001: finding `35`, parse error `1`
- Run002: finding `34`, parse error `1`
- Run003: finding `34`, parse error `1`
- Run003 register: `34/34`
- likely-review: `15`
- accepted-context: `19`
- unregistered: `0`
- arithmetic: `13 + 2 + 5 + 10 + 3 + 1 = 34`
- Kotlin limitation: `MainActivity.kt`의 유효한 trailing-comma multiline-lambda
  위치에 대한 partial parse `1`
- `SEC10` gap 유지: 적절함

### SCA

- current backend lock package instance: `85`
- unchanged non-backend instance: `441`
- current dependency instance: `526/526`, `100%`
- ecosystem: Maven `438`, npm `3`, PyPI `85`
- backend affected package coordinate: `4`
- backend logical cluster: `8/8`
- backend arithmetic: `5 + 1 + 1 + 1 = 8`
- Android affected package-version coordinate: `16/16`
- npm affected package: `0`
- PURL 없는 application/module root `5`를 dependency 분모에서 제외한 규칙:
  적절함

수치와 단위 분리는 정확하다. 서로 다른 단위를 하나의 취약점 총계로 합산하지
않은 것도 적절하다. 다만 `W5-B02` 때문에 `SEC11` 완료 판정은 아직 허용하지
않는다.

### Secret scan과 개인정보 경계

- Run001 current tree finding: `2`
- 두 finding: 좁은 rule/path false-positive 판정
- Run002 current tree finding: `0`
- repository history: `NOT_RUN`
- raw secret value가 current-state JSON/Markdown에 기록된 수: `0`
- secret inventory: `17`, identifier·owner·lifecycle만 기록
- `SEC12` gap 유지: 적절함
- `SEC06`: `PROPOSED_PENDING_EXTERNAL_LEGAL_REVIEW`
- 법적 근거, 최종 한국어 고지, TMAP 수령자·계약·국외 이전, 보존·삭제
  schedule을 외부 법률·개인정보 결정으로 유지: 적절함

### TLS 수정

- success context는 `ssl.CERT_REQUIRED`와 `check_hostname=true`를 강제함
- default client context는 명시한 CA file과 `SERVER_AUTH` 목적으로 생성됨
- negative `CERT_NONE` context는 network 연결 전 거부됨
- Run002 `py_compile`: `PASS`
- targeted test: `2 PASS`
- product deployment 또는 live TMAP evidence로 확대하지 않음: 적절함

### Dependency remediation

- 변경 product file: `3`
- 승인 package version 변경: `4`
- non-target payload change: `0`
- backend/quality lock 반복 생성: byte-identical
- isolated import/version: `4/4 PASS`
- targeted backend test: `28 PASS`
- full backend lock install: `NOT_RUN_RESOURCE_POLICY`
- EXDEV와 rollback-handler 실패 계보 보존 후 repository-local receipt 복구:
  hash 일치
- 최종 status `PASS_WITH_RECOVERED_RECEIPT_EXDEV`: 근거와 일치

### Security plan·threat·risk

- exact scope `SEC01/02/03`
- source risk `6/6` add-only 보존
- risk state mutation `0`
- dangling reference `0`
- bidirectional link asymmetry `0`
- preventive/detective/recovery control class gap `0`
- formal 279, actual device, deployment, signing, five release gate와 release
  경계 보존
- `SEC01` gap 유지: 적절함
- `SEC02`, `SEC03` content-level OK 후보: 근거상 안전함

## 4. 현재 허용 가능한 disposition

수정 전 임시 권고:

| Artifact | 현재 독립 판정 |
|---|---|
| `DLV-SEC-01` | `INTERNAL_GAP` |
| `DLV-SEC-02` | `OK_CANDIDATE_SUPPORTED` |
| `DLV-SEC-03` | `OK_CANDIDATE_SUPPORTED` |
| `DLV-SEC-06` | `EXTERNAL_CANDIDATE_SUPPORTED`, 내부 binding 수정 필요 |
| `DLV-SEC-09` | `HOLD_AS_INTERNAL_GAP_PENDING_REBIND` |
| `DLV-SEC-10` | `INTERNAL_GAP` |
| `DLV-SEC-11` | `INTERNAL_GAP` |
| `DLV-SEC-12` | `INTERNAL_GAP` |
| `DLV-SEC-15` | `INTERNAL_GAP` |

`W5-B01`만 정확히 닫히면 `SEC09`은 다시 OK 후보로 검토할 수 있다.
`SEC11`과 `SEC15`는 `W5-B02`, `W5-M01`의 필수 필드·hash·projection을 모두
보완한 뒤에만 OK 후보로 재검토한다.

## 5. 승인 범위와 재검토 조건

현재 허용:

- 세 finding을 반영한 current-state JSON/Markdown 수정
- 동일 실행 원본을 유지한 source path·hash 재결속
- SCA/vulnerability register 필수 lifecycle 필드 보강
- 새 subject digest를 사용하는 독립 재검토

현재 금지:

- W5 `artifact-status-delta.json` 생성
- `SEC09`, `SEC11`, `SEC15` OK 전환
- scan finding 0 또는 vulnerability 0 주장
- accepted context를 waiver로 해석
- history secret scan 완료 주장
- formal 279, legal approval, signing, deployment 또는 release 적격 주장

재검토 성공 기준:

- stale source path `0`
- source hash/byte mismatch `0`
- SEC11·SEC15 catalog required-content gap `0`
- scan run path-only edge `0`
- JSON/Markdown stable-ID projection mismatch `0`
- exact 9 중복·누락 `0`
- raw secret value 노출 `0`
- 기존 수치·`NOT_RUN`·release 경계 mutation `0`

최종 결론:

`NO_GO_REQUIRES_CORRECTION_AND_REREVIEW`. 현재 수치와 보수적 실행 경계는
신뢰할 수 있지만 evidence custody와 두 원장의 필수 내용이 완성되지 않았다.
세 finding이 닫히기 전에는 W5 중앙 status delta를 생성하면 안 된다.
