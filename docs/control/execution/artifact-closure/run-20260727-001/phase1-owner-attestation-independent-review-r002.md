# Phase 1 Owner/Attestation 독립 검토 R002

## 1. 판정

| 항목 | 결과 |
|---|---|
| 검토 판정 | `DECISION_READY_PREPARATION_ONLY` |
| limited GO | **GO, exact18 human-decision preparation에만 한정** |
| findings | `0` |
| blocking findings | `0` |
| R001 `F-001` | `R001 BLOCKING CLOSED` |
| 실제 owner/QA 승인 | `0` |
| 상태 승격 | `0` |
| 실행·시험·배포·출시 승인 | 없음 |

새 `phase1-current-state-attestation/evidence.json`은 R001에서 불일치했던 `test-cases.json`과 `test-plan.md`를 포함한 13개 transitive subject의 현재 물리 bytes/SHA를 다시 결속했다. 갱신된 owner-attestation packet은 이 successor만 `CURRENT_STATE_ATTESTATION_SOURCE`로 결속하고 Phase 0 packet은 `HISTORICAL_PREDECESSOR_ONLY`로 한정한다.

따라서 OWNER14와 ATTEST4의 exact18 human-decision 입력을 준비하는 limited GO를 부여한다. 이 판정은 승인, formal test credit, 실제 실행, release eligibility, production 배포 또는 최종 approval receipt가 아니다.

## 2. R001 blocker closure

R001의 `F-001 HIGH/BLOCKING`은 Phase 0 attestation이 current source로 사용되면서 내부의 권위 source가 현재 물리 bytes와 달랐던 문제였다. 새 successor는 이전 packet을 수정하지 않고 add-only 계보를 만들었다.

| packet | 역할 | bytes | file SHA-256 |
|---|---|---:|---|
| `packets/phase0-current-state-attestations/evidence.json` | `HISTORICAL_PREDECESSOR_ONLY` | `26753` | `8f162aa636e9d49457c34d1c5253f15b10f1fc9bbb7d29667b5de086bb8b71b8` |
| `packets/phase1-current-state-attestation/evidence.json` | `CURRENT_ATTESTATION_DECISION_INPUT` | `38400` | `86c91f1b46f435323ec00d4a9263f747fc4d90461ead5bdd2c2bff033cee51d4` |

새 packet은 `predecessor_modified=false`, `historical_files_modified=false`, `add_only_successor=true`를 선언한다. Phase 0 packet과 그 formal279 record/manifest/receipt는 각각 historical predecessor 또는 historical transitive control로만 남는다.

R001에서 불일치했던 두 파일은 다음 현재 값으로 재결속됐다.

| subject | 현재 bytes | 현재 SHA-256 | successor binding |
|---|---:|---|---|
| `docs/deliverables/06-testing/registers/test-cases.json` | `2592818` | `fc51836c1dddd8852a74805e7fc5b1f6e647f461168139ae2565318745f87f2e` | MATCH |
| `docs/deliverables/06-testing/test-plan.md` | `10905` | `a01a410b77479e351f3e73ee04ecbfdfab64a820fef63714e5fbec87540189e2` | MATCH |

`test-cases.json`은 `SRC-FORMAL279-PLAN`, `CURRENT_FORMAL279_PLAN_SOURCE`로, `test-plan.md`는 `INV-TEST-PLAN`, `CURRENT_DIRECTORY_INVENTORY_SOURCE`로 현재 transitive manifest에 포함된다.

결론: `R001 BLOCKING CLOSED`.

## 3. 13-subject transitive source closure

`transitive_source_manifest`의 선언과 물리 재계산 결과는 다음과 같다.

| 항목 | 값 |
|---|---:|
| subject count | `13` |
| current semantic subjects | `10` |
| historical transitive controls | `3` |
| 물리 bytes/SHA MATCH | `13/13` |
| mismatch | `0` |

13개 모두 현재 물리 bytes가 다시 캡처됐다. 의미상 권위는 10개 current subject와 3개 historical control로 분리된다.

| class | subject |
|---|---|
| current | artifact register |
| current | acceptance receipt |
| current | `test-cases.json` |
| current | release-readiness decision |
| current | software-test report |
| current | test evidence |
| current | `test-plan.md` |
| current | test-quality report |
| current | operations canonical document |
| current | operations register JSON |
| historical only | formal279 manifest |
| historical only | formal279 receipt |
| historical only | formal279 record |

역사적 3개 subject도 현재 파일 bytes에는 exact-hash 결속되지만 `current_semantic_authority=false`, `HISTORICAL_TRANSITIVE_CONTROL_ONLY`이므로 새 QA/owner 승인 권위로 승격되지 않는다.

## 4. Source-set, manifest 및 non-self fingerprint

모든 digest는 선언된 UTF-8 recursive lexicographic key order, compact JSON, no trailing LF 및 excluded-member 규칙으로 독립 재계산했다.

| 대상 | canonical bytes | 선언 SHA-256 | 결과 |
|---|---:|---|---|
| Phase 1 direct source-set fingerprint | `1646` | `65980d546a1d941e16b29e2dfa688faa3455db0c59e1d790ba8660a22a20132f` | MATCH |
| Phase 1 transitive source manifest | `5364` | `e2995ab050429af8def30570c4c3692f0cc5e319e1cc8b13860fe471b80b66e3` | MATCH |
| Phase 1 attestation packet fingerprint | `30878` | `ce3e4d7e435ac3bf4ada767d950070f84c835e39caa528b2d4b2c24425318d5b` | MATCH |
| Owner decision-item manifest | `6247` | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` | MATCH |
| Owner physical-binding manifest | `4550` | `cb7294c18aaab61df1c18dd26881db2015436d85a5ebda7f3c18780e68e6e495` | MATCH |
| Owner packet fingerprint | `40467` | `59678e30dda89a6462e41a44e8db44009a13ce5cf1a93f7846c699a818db6eab` | MATCH |
| R007 receipt fingerprint | `24125` | `15e6ed49b205076c24f474ec6955dfed113a3f1bae9e00421439bb85efa80e0e` | MATCH |

Direct source-set은 6개 `source_bindings`의 `byte_length`, `captured_at`, `path`, `sha256`, `subject_role` projection을 path 순서로 계산한다. 13-subject manifest는 direct source, directory inventory, historical transitive control을 합친 closure다.

## 5. Exact4 attestation 검증

정확한 ATTEST4는 다음과 같다.

`DLV-OPS-17`, `DLV-OPS-19`, `DLV-OPS-23`, `DLV-TST-22`

| artifact | current attestation ID | predecessor ID |
|---|---|---|
| `DLV-OPS-17` | `ATT-PHASE1-DLV-OPS-17-20260727-001` | `ATT-PHASE0-DLV-OPS-17-20260727-001` |
| `DLV-OPS-19` | `ATT-PHASE1-DLV-OPS-19-20260727-001` | `ATT-PHASE0-DLV-OPS-19-20260727-001` |
| `DLV-OPS-23` | `ATT-PHASE1-DLV-OPS-23-20260727-001` | `ATT-PHASE0-DLV-OPS-23-20260727-001` |
| `DLV-TST-22` | `ATT-PHASE1-DLV-TST-22-20260727-001` | `ATT-PHASE0-DLV-TST-22-20260727-001` |

네 행 모두 다음 상태를 유지한다.

| 필드 | 값 |
|---|---|
| `as_of` | `2026-07-27T19:10:32+09:00` |
| `as_of_precision` | `SECOND` |
| `as_of_semantics` | `EXACT_TIMESTAMP_LATEST_BOUND_SOURCE_CAPTURE` |
| execution | `NOT_RUN` |
| independent review | `PENDING_INDEPENDENT_REVIEW` |
| approval | `NOT_APPROVED` |
| release decision | `NO_GO` |
| successor recapture | `MEANING_AND_AS_OF_PRESERVED_CURRENT_PHYSICAL_SOURCES_REBOUND` |

물리 source 재캡처 시각은 `2026-07-27T22:58:18+09:00`이지만 이를 새 실행·사건·승인 시각으로 사용하지 않았다. 기존 attestation의 exact `as_of`, 의미, disposition을 유지하고 현재 물리 source가 같은 repository-state 판단을 계속 지지하는지만 재검산했다.

현재 formal279 관측도 `279`개, result null `279`, PASS `0`, FAIL `0`, aggregate `NOT_RUN`, completion receipt 미결속, release `NOT_ELIGIBLE`로 일치한다. OPS-17/19/23 tuple count도 모두 `0`이고 no-event disclaimer를 보존한다.

## 6. 갱신 owner decision packet

### 6.1 Current attestation binding

갱신 packet은 Phase 0를 current source 목록에서 제거하고 새 successor를 다음과 같이 직접 결속한다.

| 필드 | 값 |
|---|---|
| binding ID | `SRC-PHASE1-CURRENT-STATE-ATTESTATION` |
| role | `CURRENT_STATE_ATTESTATION_SOURCE` |
| historical only | `false` |
| packet ID | `WS-PHASE1-CURRENT-STATE-ATTESTATION-20260727-001` |
| bytes | `38400` |
| file SHA-256 | `86c91f1b46f435323ec00d4a9263f747fc4d90461ead5bdd2c2bff033cee51d4` |
| content fingerprint | `ce3e4d7e435ac3bf4ada767d950070f84c835e39caa528b2d4b2c24425318d5b` |

`attestation_binding_control`은 Phase 0를 `HISTORICAL_PREDECESSOR_ONLY`, Phase 1을 `CURRENT_STATE_ATTESTATION_SOURCE`로 분리하며 binding reason을 `R001_BLOCKING_TRANSITIVE_STALE_SOURCE_RESOLVED_BY_PHASE1_CURRENT_PHYSICAL_RECAPTURE`로 고정한다.

### 6.2 Exact18

OWNER14는 다음 14개다.

`DLV-DES-21`, `DLV-DSC-05`, `DLV-DSC-06`, `DLV-DSC-07`, `DLV-DSC-11`, `DLV-MGT-03`, `DLV-MGT-08`, `DLV-REQ-12`, `DLV-REQ-13`, `DLV-REQ-15`, `DLV-SEC-04`, `DLV-SEC-05`, `DLV-SEC-06`, `DLV-SEC-17`

ATTEST4는 다음 4개다.

`DLV-OPS-17`, `DLV-OPS-19`, `DLV-OPS-23`, `DLV-TST-22`

재계산 결과는 다음과 같다.

| 항목 | 결과 |
|---|---:|
| owner items | `14` |
| attestation items | `4` |
| union | `18` |
| intersection | `0` |
| sets disjoint | `true` |
| decision-item manifest count | `18` |
| physical-binding manifest subjects | `13` |

ATTEST4의 네 `exact_attestation_subject`는 모두 새 packet의 `38400/86c91…` bytes와 `$.attestations[0..3]` selector에 결속된다. 과거 Phase 0 attestation ID는 lineage 필드로만 남는다.

### 6.3 승인 및 상태 경계

| 상태 | 재계산 |
|---|---:|
| actual content-owner approval | `0` |
| actual independent-QA acceptance | `0` |
| actual attestation scope-owner approval | `0` |
| actual approval signature | `0` |
| actual execution | `0` |
| state promotion | `false` |
| final approval receipt | `false` |
| release approval | `false` |
| formal test | `NOT_RUN` |
| attestation release decision | `NO_GO` |
| release status | `NOT_ELIGIBLE` |

OWNER14에는 `PROJECT_SCOPE_OWNER`, ATTEST4에는 `INDEPENDENT_QA_REVIEWER`와 artifact별 `SERVICE_OWNER` 또는 `PRODUCT_OWNER`가 필요하다. 모든 `actual_decision`, identity, decided-on, signature는 아직 `null` 또는 `PENDING`이다.

허위 승인, 허위 QA acceptance, 실행 credit, state promotion 또는 release promotion은 발견하지 않았다.

## 7. OWNER14 및 R007 불변 검증

R007은 이전 독립 검토와 동일한 물리 바이트를 유지한다.

| 항목 | 값 |
|---|---|
| receipt ID | `WS-CONTENT-ACCEPTANCE-REVIEW-20260727-007` |
| bytes | `28685` |
| file SHA-256 | `b016e6d3cccd9a57eb9bdab69e7e5dd058b6471baa86fcef8f6f2eaf5963834f` |
| content fingerprint | `15e6ed49b205076c24f474ec6955dfed113a3f1bae9e00421439bb85efa80e0e` |
| receipt status | `INDEPENDENT_REVIEW_COMPLETED_PENDING_SCOPE_OWNER_APPROVAL` |

OWNER14 집계도 변경되지 않았다.

| 결과 | 수 |
|---|---:|
| PASS | `14` |
| PARTIAL | `0` |
| FAIL | `0` |
| blocking | `0` |
| INFO | `5` |
| LOW | `4` |
| MEDIUM | `1` |
| HIGH | `4` |
| 실제 scope-owner approval | `0` |

두 authorable successor도 기존 exact binding을 유지한다.

| packet | bytes | SHA-256 |
|---|---:|---|
| Phase 1 internal core | `17664` | `d0dfaa251b284f84582149581f661c66c4cfc67509df43564e041476c7570e62` |
| Phase 1 security | `16598` | `a79ed29970b27bc208a0c96bb42de68da7fb95728ce239174ed9a214b410d638` |

## 8. 물리 binding 및 fixed snapshot

세 현재 검토 JSON의 path/bytes/SHA declaration을 따라 111개 physical binding declaration, 32개 고유 bound path를 재계산했다.

| 항목 | 결과 |
|---|---:|
| declaration count | `111` |
| unique bound paths | `32` |
| physical mismatch | `0` |

고정 스냅샷은 새 attestation packet, 갱신 owner packet, R007 및 모든 직접 물리 binding path의 합집합 33개를 사용한다.

```json
{
  "schema_version": "walksafe.phase1-owner-attestation-independent-review-fixed-snapshot.v2",
  "subjects": [
    {
      "path": "<repository-relative path>",
      "byte_length": 0,
      "sha256": "<physical SHA-256>"
    }
  ]
}
```

| 항목 | 값 |
|---|---|
| canonicalization | UTF-8, recursive lexicographic key order, compact JSON, no trailing LF |
| subject order | repository-relative path lexicographic |
| subject count | `33` |
| canonical byte length | `6143` |
| fixed snapshot SHA-256 | `efb2e0559d82f38bb7a945e2f1b8014e7a93548049db65a47beb306873e2eea2` |

## 9. Limited GO 경계

`DECISION_READY_PREPARATION_ONLY` limited GO가 허용하는 범위는 다음과 같다.

- Exact18 manifest와 packet fingerprint를 제시해 요구된 human decision을 요청한다.
- OWNER14의 project scope-owner decision을 기록한다.
- ATTEST4의 independent-QA 및 artifact별 scope-owner decision을 기록한다.
- 승인자가 decision-item manifest와 packet fingerprint에 정확히 결속했는지 검증한다.

이 limited GO가 허용하지 않는 범위는 다음과 같다.

- 승인·서명·QA acceptance를 미리 채우거나 추정한다.
- queue 또는 canonical content state를 승격한다.
- NOT_RUN을 PASS, 실행 완료 또는 test credit으로 바꾼다.
- `NO_GO` 또는 `NOT_ELIGIBLE`을 release GO로 바꾼다.
- production 실행, 법률 의견 또는 최종 approval receipt를 주장한다.

현재 고정 스냅샷에 대한 최종 판정은 `DECISION_READY_PREPARATION_ONLY`, findings `0`, `R001 BLOCKING CLOSED`다.
