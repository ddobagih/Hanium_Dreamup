# Phase 1 Owner/Attestation 독립 검토 R001

## 1. 판정

| 항목 | 결과 |
|---|---|
| 검토 판정 | `BLOCKED_STALE_CURRENT_ATTESTATION_BINDING` |
| `DECISION_READY_PREPARATION_ONLY` limited GO | **부여하지 않음** |
| finding | `1` |
| blocking finding | `1` |
| 최고 심각도 | `HIGH` |
| 실제 owner/QA 승인 | `0` |
| 실행·시험·배포·출시 승인 | 없음 |

R007의 OWNER14 content review, 두 Phase 1 successor packet, OWNER14와 ATTEST4의 선택 집합 및 승인 경계는 일치한다. 그러나 통합 owner-attestation packet이 `CURRENT_STATE_ATTESTATION_SOURCE`로 분류한 Phase 0 attestation의 권위 source 중 하나와 captured directory inventory 중 하나가 현재 물리 바이트와 다르다. 따라서 이 packet 전체를 현재 ATTEST4 human-decision 입력으로 사용하는 것은 차단한다.

## 2. Findings

### `F-001` `HIGH` `BLOCKING`: ATTEST4의 current-state source closure가 stale하다

`phase1-owner-attestation-decision-ready/evidence.json`의 `$.source_bindings[5]`는 Phase 0 attestation packet을 다음과 같이 분류한다.

| 필드 | 선언 |
|---|---|
| role | `CURRENT_STATE_ATTESTATION_SOURCE` |
| historical_only | `false` |
| file bytes | `26753` |
| file SHA-256 | `8f162aa636e9d49457c34d1c5253f15b10f1fc9bbb7d29667b5de086bb8b71b8` |

Phase 0 packet 파일 자체의 bytes/SHA는 선언과 일치한다. 하지만 그 파일이 `as_of=2026-07-27T19:10:32+09:00`, `as_of_semantics=EXACT_TIMESTAMP_LATEST_BOUND_SOURCE_CAPTURE`로 current state를 판단할 때 사용한 물리 source closure는 현재 작업 트리와 일치하지 않는다.

| Phase 0 binding | 역할 | captured bytes/SHA-256 | 현재 bytes/SHA-256 | 결과 |
|---|---|---|---|---|
| `docs/deliverables/06-testing/registers/test-cases.json` | `AUTHORITATIVE_FORMAL_TEST_PLAN_REGISTER` | `2590342` / `19a6b425bf3a0ee880f1c9229a42b96845ab4c3a1f99037783fe872426e0deee` | `2592818` / `fc51836c1dddd8852a74805e7fc5b1f6e647f461168139ae2565318745f87f2e` | **MISMATCH** |
| `docs/deliverables/06-testing/test-plan.md` | captured directory inventory | `8743` / `2b84c874501a4e6b3b2b3a3d1ba18c405203d40432b60c7474a028a36fda9e23` | `10905` / `a01a410b77479e351f3e73ee04ecbfdfab64a820fef63714e5fbec87540189e2` | **MISMATCH** |

Phase 0 `source_set_fingerprint`는 저장된 captured binding projection에 대해서는 정확하다.

| 계산 | canonical bytes | SHA-256 |
|---|---:|---|
| 선언된 captured source projection 재계산 | `1655` | `2209ca31eb63715fe55c9f6fda1b6f8912ec8e40981f3a8a71c05260d19e38a8` |
| 현재 물리 bytes/SHA로 치환한 동일 projection | `1655` | `6ddc74ff442476bd962d0535ea607b9329c1705ff40a069b2911f857a4f48e5b` |

이 차이는 Phase 0 packet의 내부 무결성 결함이 아니라, 후속 packet이 과거 시점 캡처를 현재 ATTEST4 decision source로 승격한 전이 의존성 결함이다. 특히 `test-cases.json`은 DLV-TST-22 attestation이 명시적으로 참조하는 `SRC-FORMAL279-PLAN`이다.

Owner packet의 `any_bound_subject_byte_change_invalidates_packet=true`는 직접 결속된 Phase 0 packet 파일이 바뀌지 않는 한 그 내부 source의 후속 변경을 검출하지 못한다. `physical_binding_manifest`도 DLV-TST-22에 대해 `test-quality-report.md`만 직접 결속하며, 변경된 `test-cases.json` 또는 captured inventory의 `test-plan.md`를 current transitive decision input으로 결속하지 않는다.

영향은 다음과 같다.

- R007의 OWNER14 content review 결과를 부정하지 않는다.
- 실제 승인, 실행, 시험, 배포 또는 release state를 허위로 승격하지는 않았다.
- ATTEST4를 현재 상태에 대한 QA 및 scope-owner 승인 입력으로 제시하는 것은 안전하지 않다.
- OWNER14와 ATTEST4를 합친 owner-attestation packet 전체에는 limited GO를 부여할 수 없다.

필수 조치는 다음과 같다.

1. 현재 물리 source로 Phase 0 attestation successor를 새로 materialize하고 기존 packet은 역사적 as-of 캡처로 보존한다.
2. 새 successor의 source bindings, source-set fingerprint, document integrity를 재계산한다.
3. 새 owner-attestation decision packet이 successor bytes/SHA와 ATTEST4 전이 source closure를 명시적으로 결속하게 한다.
4. 변경된 decision-item manifest와 packet fingerprint에 대해 독립 재검토를 수행한다.

## 3. R006 계보와 현재 권위

| subject | ID/역할 | bytes | SHA-256 | 판정 |
|---|---|---:|---|---|
| `content-acceptance-review-receipt.json` | `WS-CONTENT-ACCEPTANCE-REVIEW-20260727-006`, historical | `28305` | `bca93cff7c58e3f5cd26646a38c66e2c5518561bd68c58d3fbde13be90dacb3f` | 역사적 lineage만 허용 |
| `content-acceptance-review-receipt-r007.json` | `WS-CONTENT-ACCEPTANCE-REVIEW-20260727-007`, current | `28685` | `b016e6d3cccd9a57eb9bdab69e7e5dd058b6471baa86fcef8f6f2eaf5963834f` | 현재 content-review authority |

R007은 `successor_of_receipt_id=WS-CONTENT-ACCEPTANCE-REVIEW-20260727-006`과 `historical_receipt_role=R006_HISTORICAL_ONLY_AFTER_CURRENT_BYTE_CHANGE`를 선언한다. Owner packet도 R006을 `HISTORICAL_CONTENT_REVIEW_RECEIPT`, `historical_only=true`로, R007을 `CURRENT_INDEPENDENT_CONTENT_REVIEW_RECEIPT`, `historical_only=false`로 분리한다.

R006의 옛 canonical 바인딩 중 현재 파일과 다른 8개 declaration은 3개 고유 파일의 역사적 bytes를 가리킨다. 이는 R007이 successor로 재검토하고 R006을 역사적으로 강등한 사유와 일치하므로 별도 current-binding finding으로 세지 않았다.

## 4. R007 및 두 successor packet 검산

### 4.1 현재 canonical bytes

R007의 8개 `canonical_subjects`는 모두 현재 물리 bytes/SHA-256과 일치했다.

| canonical subject | bytes | SHA-256 |
|---|---:|---|
| `docs/deliverables/04-design/security-and-operations-design.md` | `83400` | `e99b96971d411c7ce07c26679adc4eb229ab78d5a3f62ee50af7cc366b3970ac` |
| `docs/deliverables/02-discovery/discovery-evidence-and-analysis.md` | `43076` | `c4ad2df0a0108591ceeea029e10eac8835b9993773c30539994cb6b501471129` |
| `docs/deliverables/02-discovery/product-definition.md` | `23179` | `f0c835ae6ea3f952e4650b978412e06f150384fcbb01268db710767542589e60` |
| `docs/deliverables/01-management/project-charter.md` | `22749` | `aa71b094be01928fbf830bec5ffda70635a9afdae4876a3afee181eee3522979` |
| `docs/deliverables/01-management/project-management-plan.md` | `35549` | `5f292543a0749d9734bf64c9a7b2d362091da6dfd7364b513f65a02d47acd1fa` |
| `docs/deliverables/03-requirements/system-requirements.md` | `689364` | `ad96200ac62d3c1c46ae6e6a092d3dd2d2bd85d954d8af688ea4fe8fef9e5e56` |
| `docs/deliverables/07-security/security-and-privacy-plan.md` | `69161` | `48ca9fdc3e992ce7a9fc8cb089beb704e24a8a45791dac84693a9a6434a3ab7f` |
| `docs/deliverables/07-security/security-response-and-monitoring.md` | `33912` | `e12637797b00007cff56ba727bbd3c66fa176c177ed60f0299ba8d1e41e306fe` |

### 4.2 successor packet physical binding

| packet | bytes | file SHA-256 | non-self packet fingerprint | 결과 |
|---|---:|---|---|---|
| `phase1-internal-authorable-core/evidence.json` | `17664` | `d0dfaa251b284f84582149581f661c66c4cfc67509df43564e041476c7570e62` | `5b88584dd4fc277070281c7f71555e05302fd2f8d8c75ce840ee2a6c91698817` | MATCH |
| `phase1-security-authorable/evidence.json` | `16598` | `a79ed29970b27bc208a0c96bb42de68da7fb95728ce239174ed9a214b410d638` | `f6546b2c4e925d280fb7968dd69f443b3d301e3ac511314afe0136b679518e87` | MATCH |

두 packet의 내부 status는 materialization 당시 `PENDING_R007_CONTENT_REVIEW_AND_OWNER_APPROVAL`이고, R007이 그 exact packet bytes를 후속 독립 검토했다. 두 packet 모두 `state_promotion_performed=false`, `owner_approval_status=NOT_APPROVED`, `release_status=NOT_ELIGIBLE`를 유지한다. 과거 predecessor packet은 각각 `HISTORICAL_PREDECESSOR_ONLY`로 한정돼 있다.

## 5. OWNER14 및 ATTEST4 집합 검산

### 5.1 OWNER14

| 항목 | 재계산 |
|---|---:|
| PASS | `14` |
| PARTIAL | `0` |
| FAIL | `0` |
| `blocks_content_review_pass=true` | `0` |
| nonblocking INFO | `5` |
| nonblocking LOW | `4` |
| nonblocking MEDIUM | `1` |
| nonblocking HIGH | `4` |
| 실제 owner decision | `0` |
| 실제 owner signature | `0` |

R007의 14개 artifact review는 모두 `PASS_CONTENT_REVIEW_ONLY`, `PENDING_SCOPE_OWNER_APPROVAL`, `blocks_content_review_pass=false`다. HIGH 네 건도 법률, 보안시험, 잔여위험, provider/UI/E2E 및 release gate를 열린 상태로 유지하는 content-review finding이며 승인이나 release credit이 아니다.

### 5.2 정확한 집합

`OWNER14`는 다음 14개다.

`DLV-DES-21`, `DLV-DSC-05`, `DLV-DSC-06`, `DLV-DSC-07`, `DLV-DSC-11`, `DLV-MGT-03`, `DLV-MGT-08`, `DLV-REQ-12`, `DLV-REQ-13`, `DLV-REQ-15`, `DLV-SEC-04`, `DLV-SEC-05`, `DLV-SEC-06`, `DLV-SEC-17`

`ATTEST4`는 다음 4개다.

`DLV-OPS-17`, `DLV-OPS-19`, `DLV-OPS-23`, `DLV-TST-22`

재계산 결과는 `OWNER14 ∩ ATTEST4 = ∅`, union count `18`이다. `decision_item_manifest`도 owner `14`, attestation `4`, total `18`로 동일하다.

### 5.3 결정 권한과 실제 상태

| 결정 class | 필수 권한 | actual decision | 서명 |
|---|---|---|---|
| OWNER14 content | `PROJECT_SCOPE_OWNER` | 모두 `null` | 모두 `null` |
| ATTEST4 independent QA | `INDEPENDENT_QA_REVIEWER` | 모두 `null` | 모두 `null` |
| DLV-OPS-17/19/23 scope | `SERVICE_OWNER` | 모두 `null` | 모두 `null` |
| DLV-TST-22 scope | `PRODUCT_OWNER` | `null` | `null` |

Owner packet의 `state_control`은 실제 content-owner approval, independent-QA acceptance, attestation scope-owner approval, signature를 모두 `0`으로 유지한다. `state_promotion_performed=false`, `final_approval_receipt_issued=false`, `release_approval_issued=false`, `release_status=NOT_ELIGIBLE`도 일치한다. 허위 승인 또는 허위 state promotion은 발견하지 않았다.

## 6. Manifest 및 non-self fingerprint 재계산

| 대상 | 선언 SHA-256 | 재계산 | 결과 |
|---|---|---|---|
| R006 receipt content fingerprint | `141402d98b984d74fdde2fe84209d8ad6cce93b830a4df62b81088f9fbbee47c` | 동일 | MATCH |
| R007 receipt content fingerprint | `15e6ed49b205076c24f474ec6955dfed113a3f1bae9e00421439bb85efa80e0e` | 동일 | MATCH |
| Core source manifest | `2caa91a5f59b77c944ca401cd37efd6f705f3c35a8baebd972873d4b5bd83f6b` | 동일 | MATCH |
| Core output manifest | `ef7058cef55ded4d60caf08d64c1e0cef613656715e26e9295aa57ad794b94d2` | 동일 | MATCH |
| Core packet fingerprint | `5b88584dd4fc277070281c7f71555e05302fd2f8d8c75ce840ee2a6c91698817` | 동일 | MATCH |
| Security source manifest | `fcebdea37b81b41477dbb97274cf47469676d5f1825dfd5231bb18852cfb3f44` | 동일 | MATCH |
| Security output manifest | `f356cc064eda6377cf1254556dca733c7e19571b3c9a708ab146b3b10b5c89fb` | 동일 | MATCH |
| Security packet fingerprint | `f6546b2c4e925d280fb7968dd69f443b3d301e3ac511314afe0136b679518e87` | 동일 | MATCH |
| Owner decision-item manifest | `851a64611a4e1434d3d54001c3645bdb96853687122081ae21142058c06c8f59` | 동일 | MATCH |
| Owner physical-binding manifest | `cb7294c18aaab61df1c18dd26881db2015436d85a5ebda7f3c18780e68e6e495` | 동일 | MATCH |
| Owner packet fingerprint | `0ba9c58f8b418107d8349dfea67b426e68854a60e98acbf6c6f3f78c7c924ecc` | 동일 | MATCH |
| Phase 0 captured source-set fingerprint | `2209ca31eb63715fe55c9f6fda1b6f8912ec8e40981f3a8a71c05260d19e38a8` | 동일 | MATCH, captured projection만 해당 |
| Phase 0 document integrity | `efead7b32b3e36837d0bbdfa08d03b217a314d6e4a6131cf9ceb08744ff55b8f` | 동일 | MATCH |

Fingerprint와 manifest 자체의 canonicalization 및 excluded-member 규칙은 모두 재현됐다. `F-001`은 self-fingerprint 오류가 아니라, internally consistent한 과거 source projection을 current decision source로 계속 사용하는 authority/staleness 오류다.

## 7. 물리 binding 및 고정 스냅샷

R007, owner packet, 두 successor packet, R006 및 Phase 0 attestation에서 path와 bytes/SHA를 직접 선언한 142개 declaration, 40개 고유 path를 물리 재계산했다.

| 분류 | mismatch declaration |
|---|---:|
| 현재 R007, 두 successor, owner direct/physical bindings | `0` |
| R006 historical binding | `8`, 3개 고유 과거 canonical file |
| Phase 0 current-state source closure | `2` |

검토 고정 스냅샷은 위 여섯 JSON의 모든 직접 path binding과 네 주요 검토 JSON을 합집합한 실제 물리 subject 41개를 path로 정렬한 다음 아래 projection으로 계산했다.

```json
{
  "schema_version": "walksafe.phase1-owner-attestation-independent-review-fixed-snapshot.v1",
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
| subject count | `41` |
| canonical byte length | `7576` |
| fixed snapshot SHA-256 | `6cb56b3c81da808f6df12b57566ed9f3f094386f78665b9985623ae79f77aa6c` |

이 고정 SHA는 finding을 통과 판정으로 바꾸지 않는다. 해당 스냅샷에서 통합 packet의 판정은 `BLOCKED_STALE_CURRENT_ATTESTATION_BINDING`이다.

## 8. 최종 gate

`DECISION_READY_PREPARATION_ONLY` limited GO의 전제는 OWNER14뿐 아니라 ATTEST4도 현재 권위 source closure에 결속되는 것이다. 그 전제가 충족되지 않았으므로 limited GO를 부여하지 않는다.

R007 content-review receipt와 OWNER14의 pending human-decision 경계는 유지할 수 있다. 그러나 ATTEST4 승인 요청, owner-attestation 통합 decision manifest, 이후 queue update 또는 approval receipt는 `F-001`이 해소되고 새 exact hashes에 대한 독립 재검토가 끝날 때까지 진행하면 안 된다.
