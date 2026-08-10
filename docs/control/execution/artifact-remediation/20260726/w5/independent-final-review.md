# W5 보안·개인정보 산출물 최종 독립 재검토

- 검토 ID: `WS-ARTIFACT-REMEDIATION-W5-INDEPENDENT-FINAL-REVIEW-20260727-001`
- 검토일: `2026-07-27`
- 검토 역할: W5 보완 작성자와 분리된 독립 reviewer
- 최종 판정: `GO_STATUS_DELTA_ALLOWED`
- finding: `blocking 0 / major 0 / minor 0`
- status delta 생성 허용: `true`
- formal, signing, deployment 또는 release 허용: `false`

기존 두 검토의 `NO_GO_REQUIRES_CORRECTION_AND_REREVIEW` 이력은 변경하거나
소급 삭제하지 않는다.

- 최초 review: `blocking 2 / major 1 / minor 0`
- 첫 rereview: `blocking 1 / major 0 / minor 0`
- 이번 final review: `blocking 0 / major 0 / minor 0`

이번 판정은 보완된 최신 bytes에서 `W5-B01`, `W5-B02`, `W5-M01`이 모두
닫혔으므로 exact9 내부 status delta를 작성해도 된다는 뜻이다. 제품 보안 무결점,
법률 승인, formal pass 또는 release eligibility를 뜻하지 않는다.

## 1. 동결된 최종 subject

Subject-set SHA-256은 경로를 bytewise 정렬하고 각 항목을
`<repository-relative-path><TAB><file-sha256><LF>`로 직렬화해 계산했다.
이 final review 파일 자체는 subject digest에 포함하지 않는다.

- subject file count: `22`
- subject-set SHA-256:
  `649a8eeceae8863b0f75a19a40672fbc2e36484f496366daaea95026d7cccee8`
- source commit: `null`
- build ID: `null`
- worktree boundary: current exact file bytes
- whole repository frozen: `false`

| Subject path | Bytes | File SHA-256 |
|---|---:|---|
| `docs/control/artifact-types.json` | `639009` | `73a17c237a0e2fb6b25fb5a34203d187f25cd0571d879ffa3a6a2d63ed5c5fbb` |
| `docs/control/execution/artifact-audits/20260726/security-ai-audit.json` | `26822` | `4eb543de60cb07d267f52af17f725036aa79520da883ba02814456dbfe4dbd14` |
| `docs/control/execution/artifact-remediation/20260726/w3/evidence-20260726-003/lock-inventory.json` | `576621` | `baddd42d96d21a751df4494a48fa0b1e81f7df233c60d63981dd4e754ee5df04` |
| `docs/control/execution/artifact-remediation/20260726/w5/independent-rereview.md` | `15725` | `6243efe959bb3cbaa154f662c9889ad873eee02bc06893f4204dd4c103f4be6a` |
| `docs/control/execution/artifact-remediation/20260726/w5/independent-review.md` | `15654` | `7dbf30fa7e9520bf802be2b23883b83f8dfa5f09ba8ecc2000324b03ece29b8a` |
| `docs/control/execution/artifact-remediation/20260726/w5/privacy-consent-secrets-current-state.json` | `97289` | `46ac5d716b6a04be7fb275c57755a2d8ffbdf1e4171ac473b7814c766249e675` |
| `docs/control/execution/artifact-remediation/20260726/w5/privacy-consent-secrets-current-state.md` | `46405` | `15e6f467f2d340dec781a29404641cf0a8cc88368bab681429b020fa243a5aec` |
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

## 2. `W5-B01` 최종 검토

판정: `CLOSED`.

### Corrected source bindings

| Source ID | Actual path | Actual / declared bytes | SHA-256 | Result |
|---|---|---|---|---|
| `SRC-ADM-API` | `apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityApi.java` | `4817 / 4817` | `22b126d131d4b9229bdb7d71c03c408bfa323424520e6480fee0aba30b89f9ab` | `PASS` |
| `SRC-ADM-HTTP` | `apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClient.java` | `21927 / 21927` | `d77c737cf2a606db682fc8e9f40111742fb2c12f7096fb6bf5424356514e1ee8` | `PASS` |
| `SRC-W3-TRACE` | `docs/control/execution/artifact-remediation/20260726/w3/engineering-trace-current-state.json` | `37403 / 37403` | `0c816c830a4371fc4abbef0e3f4848940daa130837e3cead06a0e7a9eb800ce0` | `PASS` |
| `SRC-FP013-VERIFY` | `docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-013-R001/verification-result.json` | `5522 / 5522` | `cbe4c2c0bfce2956e76c7e798337f7e2168f47bf6d39b59adbc206317d44fe6b` | `PASS` |

- actual path: `4/4 PASS`
- actual SHA-256: `4/4 PASS`
- actual/declared byte length: `4/4 PASS`
- Markdown path/hash/byte projection: `4/4 PASS`
- all privacy source-binding stale paths: `0`
- hash mismatch: `0`
- byte metadata gap: `0`

### Source, content and lossless semantic projection

- source-binding fingerprint:
  `d23e65cbfe0cbe61f2757c4ab3854b075fca2ecba079871ec403c7b9a4b5fd4a`
- independently reproduced source-binding fingerprint: `PASS`
- JSON content fingerprint:
  `6bbe8f2e669c532365ae3ed382511c780bdedfaf918ccb633381a913cc7dddc1`
- independently reproduced content fingerprint: `PASS`
- semantic projection schema:
  `walksafe.w5.privacy-consent-secrets.markdown-semantic-projection.v1`
- semantic payload:
  `/markdown_projection/semantic_projection/payload`
- projection SHA-256:
  `1aa79774c9e541ee8a1786030f6a333c28e0e6eaaa2824d42a6e4ca6f3352aab`
- independently reproduced projection SHA-256: `PASS`
- JSON/Markdown fingerprint parity: `PASS`

Projection preimage는 payload만 사용한다. 직렬화 규약은 UTF-8 JSON,
`ensure_ascii=false`, `sort_keys=true`, separators `(comma,colon)`, trailing
newline 없음이다. payload는 exact controls, dispositions, authority boundary,
section count, source-order path/SHA/byte quad와 RFC6901 external/NOT_RUN marker를
lossless하게 포함한다.

JSON/Markdown에 같은 schema, payload pointer, construction rule, canonicalization,
fingerprint와 다음 수치가 투영됐다.

| Parity item | Count |
|---|---:|
| exact controls | `2` |
| dispositions | `2` |
| source bindings | `39` |
| processing activities | `8` |
| notice/receipt rows | `6` |
| third-party transfers | `1` |
| secret inventory | `17` |
| secret scan trace top-level | `6` |
| external markers | `29` |
| NOT_RUN markers | `7` |

Raw Markdown SHA를 JSON에 넣지 않는 비순환 규칙도 명시되어 있다.

## 3. `W5-B02` 회귀 검토

판정: `CLOSED_WITHOUT_REGRESSION`.

| Stable lifecycle unit | Rows | Required-field gap | NOT_ASSESSED owner/due/retest/release gap | Source/advisory gap |
|---|---:|---:|---:|---:|
| SAST coordinate | `34` | `0` | `0` | `0` |
| Backend SCA logical cluster | `8` | `0` | `0` | `0` |
| Android SCA coordinate | `16` | `0` | `0` | `0` |
| Secret false positive | `2` | `0` | `0` | `0` |

필수 lifecycle field 13개는 모든 60행에 존재한다. 판단 근거가 없는 값은
null/empty 대신 보수적인 `NOT_ASSESSED` 또는 이유가 있는 `NOT_APPLICABLE`을
사용하며 owner, due, retest, release impact가 함께 존재한다.

- OSV baseline: `LIVE_SERVICE_NOT_SNAPSHOTTED`, time-bound observations `2`
- backend 8행 advisory/OSV/reachability/source evidence: gap `0`
- Android 16행 advisory/OSV/reachability/source evidence: gap `0`
- accepted context waiver: `false`
- raw secret value recorded: `0`
- register content fingerprint:
  `367cdce978098dae63fed8ece87e987a33ab4d244adb9b1aa0e7a36ab81e8c29`
- fingerprint reproduction: `PASS`

이 판정은 open finding의 해결, zero vulnerability, waiver 또는 release 승인을
의미하지 않는다.

## 4. `W5-M01` 회귀 검토

판정: `CLOSED_WITHOUT_REGRESSION`.

### Summary and raw-report custody

| Run | Bytes | SHA-256 | Actual match |
|---|---:|---|---|
| `W5-SECURITY-SCAN-20260727-001` | `15975` | `88c16018d88c5d5983d531c62b4908eea9ee1d1cbccd58841767668132f12455` | `PASS` |
| `W5-SECURITY-SCAN-20260727-002` | `22246` | `47be8e6516f423381a51a616b6bbebf8b984bc57cca87b2eef74df20996beb0a` | `PASS` |
| `W5-SECURITY-SCAN-20260727-003` | `17078` | `ce9e230afeabe591728de39f1e15a050023581751a6f03a660a57970c943e372` | `PASS` |

- summary path/SHA/byte: `3/3 PASS`
- raw report actual path/SHA/byte: `8/8 PASS`
- raw binding triple in verified summary: `8/8 PASS`
- raw secret/advisory prose copied into this review: `0`

### Stable-ID and fingerprint parity

- JSON register stable IDs: `66 / unique 66`
- projection manifest stable IDs: `66 / unique 66`
- Markdown stable IDs: `66 / unique 66`
- exact set mismatch: `0`
- SAST family `6` + SAST coordinate `34` + backend `8` + Android `16` +
  secret `2` = `66`
- finding-register content fingerprint:
  `367cdce978098dae63fed8ece87e987a33ab4d244adb9b1aa0e7a36ab81e8c29`
- scan-state content fingerprint:
  `3c743e240a249c742450f9f0cac5ecd3835802b31d9836e070b2520ada14096e`
- scan Markdown projection fingerprint:
  `b468497a1422e02a020a7a48339594f1385023bd60c9c936478a0ec047dbb102`
- canonical fingerprint reproduction: `3/3 PASS`
- Markdown fingerprint presence: `3/3 PASS`

## 5. Exact9 최종 판정

세 current-state JSON을 합친 exact ID 집합은 `9 / unique 9`이며 중복과 누락이
없다. 최종 candidate projection은 다음과 같다.

| Artifact | Final candidate disposition |
|---|---|
| `DLV-SEC-01` | `INTERNAL_GAP` |
| `DLV-SEC-02` | `OK` |
| `DLV-SEC-03` | `OK` |
| `DLV-SEC-06` | `EXTERNAL` |
| `DLV-SEC-09` | `OK` |
| `DLV-SEC-10` | `INTERNAL_GAP` |
| `DLV-SEC-11` | `OK` |
| `DLV-SEC-12` | `INTERNAL_GAP` |
| `DLV-SEC-15` | `OK` |

- OK: `SEC02`, `SEC03`, `SEC09`, `SEC11`, `SEC15`
- INTERNAL_GAP: `SEC01`, `SEC10`, `SEC12`
- EXTERNAL: `SEC06`
- projection parity: `PASS`
- status delta safety: `GO_STATUS_DELTA_ALLOWED`

`OK`는 내부 content/trace 후보 판정이다. formal security pass, 취약점 0,
법률 승인 또는 release 적격 판정으로 확대하지 않는다.

## 6. 보수 경계

| Boundary | Verified state |
|---|---|
| source commit / build ID | `null / null` |
| accepted context | `not a waiver` |
| current selected-tree secret findings | `0` |
| full repository-history secret scan | `NOT_RUN` |
| Kotlin parse limitation | `partial 1` |
| formal tests | `279 / NOT_RUN / executed 0 / pass 0 / evidence 0` |
| formal QA approval | `NOT_APPROVED_PENDING` |
| signing | `NOT_ASSESSED` |
| actual device | `NOT_RUN` |
| deployment | `NOT_RUN_CURRENT_GENERATION` |
| release gates | `5 / NOT_RUN / not waived` |
| release | `NOT_ELIGIBLE` |

Security-plan pair, scan pair/register, three execution summaries, dependency receipt,
TLS script/test evidence는 이전 rereview subject와 byte-identical하다. B02/M01과
기존 receipt/TLS 검토 결과에 state mutation은 없다. 이번 검토는 제품 시험을
재실행하지 않았으며 기존 hash-bound receipt만 검토했다.

## 7. 최종 결론

최종 판정은 `GO_STATUS_DELTA_ALLOWED`다.

- blocking: `0`
- major: `0`
- minor: `0`
- `W5-B01`: `CLOSED`
- `W5-B02`: `CLOSED_WITHOUT_REGRESSION`
- `W5-M01`: `CLOSED_WITHOUT_REGRESSION`
- exact9 candidate projection: `PASS`
- status delta: `ALLOWED`
- formal/release transition: `NOT_ALLOWED`
- product tests run by this review: `false`
- Git used: `false`
- daylog written: `false`

이 문서는 앞선 두 `NO_GO`를 삭제하지 않고, 해당 finding이 최신 subject bytes에서
닫혔음을 기록하는 후속 독립 판정이다.
