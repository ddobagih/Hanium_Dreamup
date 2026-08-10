# W5 보안·개인정보 산출물 독립 재검토

- 재검토 ID: `WS-ARTIFACT-REMEDIATION-W5-INDEPENDENT-REREVIEW-20260727-001`
- 재검토일: `2026-07-27`
- 검토 역할: W5 보완 작성자와 분리된 독립 reviewer
- 이전 검토: `independent-review.md`의
  `NO_GO_REQUIRES_CORRECTION_AND_REREVIEW`, `blocking 2 / major 1 / minor 0`
- 최종 판정: `NO_GO_REQUIRES_CORRECTION_AND_REREVIEW`
- 현재 finding: `blocking 1 / major 0 / minor 0`
- status delta 생성 허용: `false`

이 재검토는 이전 `NO_GO` 이력을 변경하지 않는다. 수정된 current-state와 신규
finding register를 새 subject set으로 다시 판정했다. `W5-B02`와 `W5-M01`은
재현 가능하게 닫혔지만 `W5-B01` 성공 기준이 완전히 충족되지 않았다.

## 1. Finding

### `W5-B01-R1` privacy binding의 byte custody와 projection fingerprint가 불완전함

- severity: `BLOCKING`
- 영향 ID: `DLV-SEC-06`, `DLV-SEC-09`
- status: `OPEN`
- status delta 영향: `DLV-SEC-09`의 `OK` 전환과 W5 전체 delta 생성을 차단함

정정된 네 경로는 모두 실제 파일이며 선언 SHA-256과 일치한다. 전체 privacy
source binding의 stale path도 `0`이고 source/content fingerprint도 재현된다.
그러나 다음 두 필수 항목이 남았다.

1. `SRC-W3-TRACE` binding에 `byte_length`가 없다. 실제 크기는 `37403` bytes다.
   나머지 세 정정 binding은 선언 byte length와 실제 크기가 일치한다.
2. `markdown_projection`에는 parity key와 동일 in-memory model 선언만 있고
   재계산 가능한 64자리 semantic projection fingerprint가 없다.
   raw Markdown SHA를 JSON에 넣어 순환 참조를 만들 필요는 없지만, exact parity
   key로 만든 별도 canonical projection fingerprint는 필요하다.

필수 수정:

- `SRC-W3-TRACE.byte_length=37403`을 JSON source binding에 추가한다.
- Markdown source-binding 표에도 네 binding의 byte length를 lossless하게
  투영한다.
- parity key의 canonical JSON에 대한 SHA-256을
  `markdown_projection.semantic_projection_sha256` 같은 비순환 필드로
  추가하고 Markdown에도 같은 값을 투영한다.
- 동일 모델에서 `source_bindings_sha256`, 관련 section fingerprint,
  `content_sha256`과 projection fingerprint를 다시 계산한다.
- 수정 후 stale `0`, hash mismatch `0`, byte metadata gap `0`,
  JSON/Markdown projection mismatch `0`을 독립 재검토한다.

현재 확인값:

| Source ID | Actual path | Actual / declared bytes | SHA-256 match | Markdown path/hash |
|---|---|---|---|---|
| `SRC-ADM-API` | `apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityApi.java` | `4817 / 4817` | `PASS` | `PASS` |
| `SRC-ADM-HTTP` | `apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClient.java` | `21927 / 21927` | `PASS` | `PASS` |
| `SRC-W3-TRACE` | `docs/control/execution/artifact-remediation/20260726/w3/engineering-trace-current-state.json` | `37403 / MISSING` | `PASS` | `PASS` |
| `SRC-FP013-VERIFY` | `docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-013-R001/verification-result.json` | `5522 / 5522` | `PASS` | `PASS` |

| B01 check | Result |
|---|---|
| corrected path exists | `4/4 PASS` |
| declared SHA-256 matches actual bytes | `4/4 PASS` |
| declared byte length matches when present | `3/3 PASS` |
| required byte length present | `3/4 FAIL` |
| all privacy source binding stale paths | `0 PASS` |
| all privacy target hash mismatches | `0 PASS` |
| `source_bindings_sha256` reproduction | `PASS` |
| `content_sha256` reproduction | `PASS` |
| semantic Markdown projection fingerprint | `MISSING FAIL` |

Fingerprint 확인:

- privacy JSON file SHA-256:
  `0e164e54c1f9888a628cbc75ce655f1fe75eb58d228e60c54b395f550e1f9ca7`
- privacy Markdown file SHA-256:
  `a678fd3e66ae7786b02bc9d41efcb1704f4080770aa6db48b1e809b0d8c46766`
- source bindings fingerprint:
  `b15f82c4309a1aaa38bafc3a919193cfb45f411d6591ffeb01e7091ed95ba5c4`
- content fingerprint:
  `e21eb5df3ffd88faf86015326413994817449c458e3c40ba01494b14c1908823`
- projection fingerprint: `MISSING`

## 2. 동결된 재검토 subject

Subject-set SHA-256은 경로를 bytewise 정렬하고 각 항목을
`<repository-relative-path><TAB><file-sha256><LF>`로 직렬화해 계산했다.
이 재검토 파일 자체는 subject digest에 포함하지 않는다.

- subject file count: `21`
- subject-set SHA-256:
  `a6fa8a79b7556f33086690d9ae3c82f1d8837d25b5823c3a7ecd1e2afac1e8b3`
- source commit: `null`
- build ID: `null`
- worktree boundary: current exact file bytes
- whole repository frozen: `false`

| Subject path | Bytes | File SHA-256 |
|---|---:|---|
| `docs/control/artifact-types.json` | `639009` | `73a17c237a0e2fb6b25fb5a34203d187f25cd0571d879ffa3a6a2d63ed5c5fbb` |
| `docs/control/execution/artifact-audits/20260726/security-ai-audit.json` | `26822` | `4eb543de60cb07d267f52af17f725036aa79520da883ba02814456dbfe4dbd14` |
| `docs/control/execution/artifact-remediation/20260726/w3/evidence-20260726-003/lock-inventory.json` | `576621` | `baddd42d96d21a751df4494a48fa0b1e81f7df233c60d63981dd4e754ee5df04` |
| `docs/control/execution/artifact-remediation/20260726/w5/independent-review.md` | `15654` | `7dbf30fa7e9520bf802be2b23883b83f8dfa5f09ba8ecc2000324b03ece29b8a` |
| `docs/control/execution/artifact-remediation/20260726/w5/privacy-consent-secrets-current-state.json` | `73894` | `0e164e54c1f9888a628cbc75ce655f1fe75eb58d228e60c54b395f550e1f9ca7` |
| `docs/control/execution/artifact-remediation/20260726/w5/privacy-consent-secrets-current-state.md` | `45025` | `a678fd3e66ae7786b02bc9d41efcb1704f4080770aa6db48b1e809b0d8c46766` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-finding-register.json` | `123698` | `fdc0cb5d5d5aef301816967bb0b5e6757f3d9edeed925ee1d07846ed1548c285` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-plan-threat-risk-current-state.json` | `109714` | `2e5119f2253519f4fd329f54363c31738ef2a46d9991f71a2407ba631e3e56a7` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-plan-threat-risk-current-state.md` | `45055` | `31b3ff3c4775e81cf9bae1945246a7507c6260196a44404c230dd587c70a87cf` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-current-state.json` | `150923` | `be717aa01b8b9c9f1e218f1bf8dfd90a5b07cfe6aed427255e5fcd0b133b0e32` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-current-state.md` | `14129` | `1c755571c493272722d2b930fa0e455e672247d77a557f770df949a9b7578066` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/dependency-remediation-20260727-001/changes.json` | `774` | `052c7d08de49efd119fcf205d7f8aa698c8c40bb8559ea012bca7329d31b40b6` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/dependency-remediation-20260727-001/execution-lineage.json` | `1241` | `5d159ce8a4483cdce319a58dd49ebb53128f4f04fd591a19df5a63175d22a767` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/dependency-remediation-20260727-001/manifest.json` | `4225` | `fa1bbe0bf3072ef72c7d529b962f42bb47947fdc4f98991fa040456dba3f5153` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/dependency-remediation-20260727-001/package-delta.json` | `4942` | `e3fdc3940bead48cdce49cc914b9397d0e0a3c967fc5d60f9e3bea92e3304c5c` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/dependency-remediation-20260727-001/validation-summary.json` | `2303` | `87994ede6f09a1bd3fd43ea0fc6c26233ca56df171adcf436bab7908d9c3b1d7` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-001/execution-summary.json` | `15975` | `88c16018d88c5d5983d531c62b4908eea9ee1d1cbccd58841767668132f12455` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-002/execution-summary.json` | `22246` | `47be8e6516f423381a51a616b6bbebf8b984bc57cca87b2eef74df20996beb0a` |
| `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-003/execution-summary.json` | `17078` | `ce9e230afeabe591728de39f1e15a050023581751a6f03a660a57970c943e372` |
| `scripts/check_walksafe_trusted_proxy_20260716.py` | `15539` | `2a9568b6d207a76f78a0aefde1e77d2a639c042b1a4df4ef5501b3fad4d975b7` |
| `tests/test_walksafe_full_rc_tooling.py` | `132303` | `dd4e1c2fd0d298e670e662b27496f7b986333be29f26fbd29ba31b702dd62874` |

## 3. `W5-B02` 재검토

판정: `CLOSED`.

`security-finding-register.json`의 required lifecycle field는 `asset`, `version`,
`environment`, `severity`, `exploitability`, `owner`, `due`, `status`,
`mitigation_or_update`, `exception_status`, `exception_expiry`, `retest`,
`release_impact`다. null/empty 대신 근거가 없으면 이유가 있는
`NOT_ASSESSED` 또는 `NOT_APPLICABLE`을 쓰도록 계약되어 있다.

| Stable row unit | Rows | Required-field gap | NOT_ASSESSED owner/due/retest/release gap | Source binding gap |
|---|---:|---:|---:|---:|
| SAST coordinate | `34` | `0` | `0` | `0` |
| Backend SCA logical cluster | `8` | `0` | `0` | `0` |
| Android SCA coordinate | `16` | `0` | `0` | `0` |
| Secret false positive | `2` | `0` | `0` | `0` |

추가 확인:

- OSV lookup baseline은 `LIVE_SERVICE_NOT_SNAPSHOTTED`와 evidence window가 있는
  관측 `2`건으로 결속된다. offline DB snapshot 또는 미래 무결성을 주장하지 않는다.
- backend 8행과 Android 16행 모두 OSV record, advisory alias, reachability,
  source evidence와 release impact를 가진다.
- Android source evidence ID 누락은 `0`이다.
- mitigation/update, exception, expiry, retest 상태 누락은 `0`이다.
- `accepted_context_is_waiver=false`가 모든 60 lifecycle row와 authority
  boundary에서 유지된다.
- secret value recorded count는 `0`이다.
- register content fingerprint는 재현됐다:
  `367cdce978098dae63fed8ece87e987a33ab4d244adb9b1aa0e7a36ab81e8c29`.

이 판정은 finding이 해결됐거나 vulnerability가 0이라는 뜻이 아니다.
`NOT_ASSESSED` reachability/exploitability와 open remediation/retest/release
impact를 보수적으로 보존한 원장 completeness만 확인한다.

## 4. `W5-M01` 재검토

판정: `CLOSED`.

### Execution-summary backward binding

| Run | Summary path | Bytes | SHA-256 | Actual match |
|---|---|---:|---|---|
| `W5-SECURITY-SCAN-20260727-001` | `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-001/execution-summary.json` | `15975` | `88c16018d88c5d5983d531c62b4908eea9ee1d1cbccd58841767668132f12455` | `PASS` |
| `W5-SECURITY-SCAN-20260727-002` | `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-002/execution-summary.json` | `22246` | `47be8e6516f423381a51a616b6bbebf8b984bc57cca87b2eef74df20996beb0a` | `PASS` |
| `W5-SECURITY-SCAN-20260727-003` | `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-003/execution-summary.json` | `17078` | `ce9e230afeabe591728de39f1e15a050023581751a6f03a660a57970c943e372` | `PASS` |

- verified summary path/SHA/byte: `3/3`
- raw report actual path/SHA/byte: `8/8`
- raw binding triple found in its verified execution summary: `8/8`
- binding method:
  `TRANSITIVE_THROUGH_VERIFIED_EXECUTION_SUMMARY`
- mutation rule: path, SHA-256, byte length 또는 source ID 변경 시 전이 결속 무효
- raw secret value와 advisory 원문은 재검토 문서에 복제하지 않았다.

### Stable-ID parity and fingerprints

- JSON register stable IDs: `66 / unique 66`
- projection manifest stable IDs: `66 / unique 66`
- Markdown stable IDs: `66 / unique 66`
- exact set mismatch: `0`
- SAST family `6` + SAST coordinate `34` + backend cluster `8` +
  Android coordinate `16` + secret row `2` = `66`
- finding-register content fingerprint:
  `367cdce978098dae63fed8ece87e987a33ab4d244adb9b1aa0e7a36ab81e8c29`
- scan-state content fingerprint:
  `3c743e240a249c742450f9f0cac5ecd3835802b31d9836e070b2520ada14096e`
- scan Markdown projection fingerprint:
  `b468497a1422e02a020a7a48339594f1385023bd60c9c936478a0ec047dbb102`
- 세 fingerprint canonical reproduction: `3/3 PASS`
- 세 fingerprint Markdown projection presence: `3/3 PASS`

## 5. Exact 9 후보 판정

세 current-state JSON을 합친 exact ID 집합은 `9 / unique 9`이고 요청된
candidate projection과 구조적으로 일치한다. 다만 `W5-B01-R1` 때문에
`DLV-SEC-09`의 전환은 아직 안전하지 않다.

| Artifact | 문서상 최종 후보 | 재검토 허용 판정 |
|---|---|---|
| `DLV-SEC-01` | `INTERNAL_GAP` | `INTERNAL_GAP` |
| `DLV-SEC-02` | `OK` | `OK_CANDIDATE_SUPPORTED` |
| `DLV-SEC-03` | `OK` | `OK_CANDIDATE_SUPPORTED` |
| `DLV-SEC-06` | `EXTERNAL` | `EXTERNAL_CANDIDATE_SUPPORTED`, B01 custody 보완 필요 |
| `DLV-SEC-09` | `OK` | `HOLD_AS_INTERNAL_GAP_PENDING_W5-B01-R1` |
| `DLV-SEC-10` | `INTERNAL_GAP` | `INTERNAL_GAP` |
| `DLV-SEC-11` | `OK` | `OK_CANDIDATE_SUPPORTED` |
| `DLV-SEC-12` | `INTERNAL_GAP` | `INTERNAL_GAP` |
| `DLV-SEC-15` | `OK` | `OK_CANDIDATE_SUPPORTED` |

따라서 요청된 `OK SEC02/03/09/11/15`, `INTERNAL_GAP SEC01/10/12`,
`EXTERNAL SEC06` 전체 projection은 아직 status delta로 적용하기에 안전하지
않다. `SEC09`을 제외한 네 OK 후보와 세 gap 및 SEC06 외부 경계는 안전하다.

## 6. 보수 경계와 변경되지 않은 근거

| Boundary | Verified state |
|---|---|
| source commit / build ID | `null / null` |
| accepted context | `not a waiver` |
| current selected-tree secret findings | `0` |
| full repository-history secret scan | `NOT_RUN` |
| Kotlin parse limitation | `partial 1` |
| formal tests | `279 / NOT_RUN / executed 0 / pass 0 / evidence 0` |
| signing | `NOT_ASSESSED` |
| actual device | `NOT_RUN` |
| deployment | `NOT_RUN_CURRENT_GENERATION` |
| release gates | `5 / NOT_RUN / not waived` |
| release | `NOT_ELIGIBLE` |

Security-plan pair는 이전 subject와 byte-identical하다. JSON SHA-256은
`2e5119f2253519f4fd329f54363c31738ef2a46d9991f71a2407ba631e3e56a7`,
Markdown SHA-256은
`31b3ff3c4775e81cf9bae1945246a7507c6260196a44404c230dd587c70a87cf`다.
그 pair의 source risk 6개 add-only, formal/device/signing/deploy/release 경계에
새 mutation은 없다.

Dependency remediation receipt 5개도 이전 subject와 byte-identical하다.
따라서 이전 검토의 변경 product file `3`, 승인 package version `4`,
non-target payload change `0`, isolated import/version `4/4 PASS`,
targeted backend test `28 PASS`,
full backend lock install `NOT_RUN_RESOURCE_POLICY`,
`PASS_WITH_RECOVERED_RECEIPT_EXDEV` 경계를 그대로 보존한다. 이 재검토가
해당 시험을 다시 실행했다는 주장은 하지 않는다.

TLS script, test source와 Run002 summary도 이전 subject와 byte-identical하다.
Run002는 trusted-proxy `py_compile` exit `0`과 CA/hostname positive 및
pre-network negative 대상 시험 2개의 exit `0` receipt를 보존한다.
이는 제품 배포 또는 live TMAP evidence가 아니다.

## 7. 최종 결론

현재 판정은 `NO_GO_REQUIRES_CORRECTION_AND_REREVIEW`다.

- blocking: `1`
- major: `0`
- minor: `0`
- `W5-B02`: `CLOSED`
- `W5-M01`: `CLOSED`
- `W5-B01`: `OPEN`
- status delta: `NOT_ALLOWED`
- product tests run by this rereview: `false`
- Git used: `false`
- daylog written: `false`

`SRC-W3-TRACE` byte length과 비순환 semantic Markdown projection fingerprint를
추가하고 privacy JSON/Markdown fingerprint를 다시 계산한 뒤 B01만 재검토하면
된다. 그 전에는 원 `NO_GO`를 대체하거나 SEC09을 OK로 전환하면 안 된다.
