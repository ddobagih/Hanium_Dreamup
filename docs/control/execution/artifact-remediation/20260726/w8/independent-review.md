# W8 release-readiness 독립 검토

> 검토일: `2026-07-27`  
> 검토 대상: W8 exact-five current-state와 현재 REL 공용 소스/생성물  
> 판정: `NO_GO`  
> finding: `BLOCKING 0 / MAJOR 2 / MINOR 0`

## 결론

`DLV-REL-17`의 현재 내용은 독립 검토 기준을 충족한다. 사용자 설명서와 생성된 REL-17 투영에는 정확히 네 개의 독립·기본 OFF 선택, 정확히 세 개의 network state, 철회·삭제·재동의의 기술 동작과 법률 승인 경계, TMAP 외부 처리 경계, W7 exact-three 모델의 register-only 경계가 보수적으로 유지된다.

그러나 W8 review subject는 W9가 후속 수정한 공용 builder/test/manifest를 W8 변경 5건에 포함하면서 successor 계보와 REL-17 pre/post byte 보존 근거를 제공하지 않는다. 또한 `semantic_projection_fingerprint`의 projection 정의가 없어 독립 재현할 수 없다. 따라서 REL-17의 내용 적합성은 인정하되 W8 독립 봉인과 중앙 전이는 승인하지 않는다.

## 검토 subject

- 포함 범위: W8 JSON/Markdown 2개, W8 source binding 10개, current manifest가 선언한 generated file 18개를 중복 제거한 현재 파일 29개
- digest 규칙: 경로순으로 정렬한 `{path, sha256, byte_length}` 배열을 UTF-8 JSON, `ensure_ascii=false`, key sort, compact separators, trailing LF로 canonicalize한 뒤 SHA-256
- subject count: `29`
- subject digest: `71b48180e74d198ab90e6e6bb59e84c95f0f7f4cdef15ea083f2867c1ed5ec42`
- 현재 REL-17 section 추출 규칙: `delivery-and-handover.md`의 `<a id="rel-17"></a>`부터 다음 artifact anchor 직전까지
- 현재 REL-17 section byte length: `5321`
- 현재 REL-17 section SHA-256: `f6fe0a8e7cc6788cec9d2a0e56b7b3ccdc06c296b27ab68487a93807d7c2e61a`

## Finding

### MAJOR-W8-001: W9 successor 소스를 W8 변경으로 귀속했고 REL-17 byte 보존을 증명하지 못한다

근거:

- W8 JSON의 `current_subject.change_binding_ids`와 `change_count=5`는 USER_GUIDE, builder, test, handover, REL manifest를 모두 W8 변경으로 표시한다.
- 현재 builder에는 `W9_AS_OF`와 W9 lifecycle 계약이 있고, 현재 test에는 W9 검사가 있으며, 현재 manifest에는 `w9_content_assessment`가 있다.
- W8 JSON/Markdown에는 `W9`, successor, 후속 계보 또는 byte-preserved 경계가 없다.
- 현재 handover와 REL-17 section의 digest는 고정할 수 있지만 pre-W9 REL-17 section digest 또는 동일 바이트 snapshot이 없어 W9 전후 byte preservation을 독립 증명할 수 없다.
- 관련 위치: `release-readiness-current-state.json:122`, `release-readiness-current-state.json:129`, `release-readiness-current-state.json:132`, `build_walksafe_formal_rel_ops_cls_20260721.py:77`, `build_walksafe_formal_rel_ops_cls_20260721.py:422`, `build_walksafe_formal_rel_ops_cls_20260721.py:1834`, `tests/test_walksafe_formal_rel_ops_cls.py:253`

영향:

- REL-17 내용 자체가 틀렸다는 finding은 아니다.
- 다만 W8-exclusive 변경, W9 current successor source, W9 이후에도 보존된 REL-17 projection을 구분할 수 없어 W8 독립 승인 subject가 모호하다.

수정 지시:

1. W8이 실제 작성한 파일과 W9가 후속 수정한 공용 builder/test/manifest를 별도 binding 집합으로 분리한다.
2. 공용 파일에는 `origin_wave=W9`, `relationship=CURRENT_SUCCESSOR_SOURCE`와 predecessor/current hash를 기록하고 W8 `change_binding_ids`에서 제외한다.
3. pre-W9 REL-17 section 바이트가 남아 있으면 동일 추출 규칙의 pre/post length와 SHA-256을 기록해 byte preservation을 증명한다.
4. pre-W9 바이트가 없으면 `byte_preserved`를 주장하지 말고 현재 W9 successor를 새 baseline으로 명시한 뒤 REL-17 내용을 다시 독립 검토 대상으로 결속한다.
5. subject 변경 후 input, semantic, content fingerprint와 Markdown projection을 다시 생성한다.

### MAJOR-W8-002: semantic projection fingerprint가 독립 재현 가능한 계약을 갖지 않는다

근거:

- JSON은 `semantic_projection_fingerprint=5daa8dbf95625ab028e218b36112d25579a9b624f16958f243119fea46385e2d`를 기록하고 `projection_fingerprint_reproducible=true`라고 주장한다.
- canonical JSON 규칙은 기록되어 있지만 semantic projection에 포함되는 field/path, 배열 순서, 제외 규칙 또는 projection payload가 없다.
- 저장된 주요 semantic field의 canonical top-level subset과 직접 field/wrapper 후보로 해당 digest를 재현하지 못했다.
- 같은 digest가 Markdown에 표시되는 것은 값의 복사 일치이며 독립 재현이 아니다.
- 관련 위치: `release-readiness-current-state.json:141`, `release-readiness-current-state.json:169`, `release-readiness-current-state.json:413`, `release-readiness-current-state.md:159`

수정 지시:

1. canonical semantic projection payload 자체를 JSON에 저장하거나, 포함 field/path와 정렬·제외 규칙을 결정론적으로 명시한다.
2. 명시한 규칙으로 fingerprint를 다시 계산하고 독립 checker가 같은 값을 산출하도록 한다.
3. Markdown은 같은 저장 projection에서 렌더링하고 parity 검사 결과를 남긴다.

## exact-five 판정

| artifact | 독립 판정 | 근거 |
|---|---|---|
| `DLV-REL-15` | `INTERNAL_GAP_RETAINED` | rollback template만 존재하며 실행 `NOT_RUN`, result `null` |
| `DLV-REL-16` | `INTERNAL_GAP_RETAINED` | signed artifact `0`, signing/install `NOT_RUN`, signing assessment `NOT_ASSESSED` |
| `DLV-REL-17` | 내용 기준 `OK_CANDIDATE`, 전이 보류 | 필요한 사용자·privacy·TMAP·model boundary는 완전하나 W8 subject 계보와 semantic seal 수정 필요 |
| `DLV-REL-19` | `INTERNAL_GAP_RETAINED` | demo/training `NOT_RUN`, participant/session receipt `0` |
| `DLV-REL-22` | `INTERNAL_GAP_RETAINED` | known `346`, unknown `185`, total `531`; final notice `0`, reconciliation `NOT_RUN` |

exact-five 구성과 disposition은 JSON/Markdown 사이에서 일치하며 REL-17만 `ok_candidate=true`이다.

## REL-17 내용 검증

- consent choice: `RAW_SOURCE_COLLECTION`, `AUTOMATIC_REPORTING`, `MOBILE_NETWORK_TRANSFER`, `TRAINING_REUSE`의 exact 4, 모두 기본 `OFF`, 상호 독립
- network: `WALKING -> DENIED/NONE`, `STOPPED_MOBILE_OPTED_IN -> CONDITIONALLY_ALLOWED/CELLULAR_OR_WIFI`, `STOPPED_MOBILE_NOT_OPTED_IN -> CONDITIONALLY_ALLOWED/WIFI_ONLY`
- privacy operation: `WITHDRAWAL`, `DELETION`, `RECONSENT`의 기술 계약은 구분되어 있고 실제 실행은 모두 `NOT_RUN`
- legal split: 180일은 기술·운영 draft일 뿐 승인된 법적 보유기간이 아니며 최종 한국어 문안과 법률 상태는 `NOT_APPROVED`
- TMAP: 외부 제3자 처리 경계이며 live `NOT_RUN`, 계약·법률 `NOT_APPROVED`; 제공/위탁, 국외이전, 계약 역할, 최종 고지·동의가 미확정
- model3: `unified_walksafe` 1개만 candidate source-bound, `custom_tactile`과 `coco_general`은 `UNKNOWN_PROVENANCE`; 세 모델 모두 평가·동등성·실기기 검증 `NOT_RUN`, 승인 `NOT_APPROVED`, 배포 불가, release `NOT_ELIGIBLE`
- USER_GUIDE와 current generated REL-17 section에서 요구 표식 누락 `0`

## 전역 보수 경계

- formal 279: `NOT_RUN`, pass `0`, evidence `0`
- actual device: `NOT_RUN`
- live TMAP: `NOT_RUN`
- signing: execution `NOT_RUN`, assessment `NOT_ASSESSED`
- deployment/install/rollback/demo: 모두 `NOT_RUN`
- legal/model/release approval: 모두 `NOT_APPROVED`
- release gate: `5`, 모두 `NOT_RUN`, waiver 없음
- source commit/build ID: 모두 `null`
- release: `NOT_ELIGIBLE`

W4/W5/W7 predecessor 파일은 W8에 기록된 SHA-256과 현재 바이트가 일치했다. 이는 predecessor binding 무결성만 확인한 것이며 외부 실행·승인 또는 W8 중앙 전이를 새로 승인하지 않는다.

## 무결성 및 실행 결과

| 검사 | 결과 |
|---|---|
| W8 source binding 10개 현재 바이트 대조 | `PASS`, mismatch `0` |
| content fingerprint 독립 재현 | `PASS`, `c25b4d6b69d1176e8d838cfb995b4f691eb272cce215d3eab8874ffebc866af8` |
| input-set fingerprint 독립 재현 | `PASS`, 경로순 `{path,sha256,byte_length}` canonical array, `a0efff4baf7cea575e046f204506b0a7fd30c4fc7d40948adf009fbe50de70ff` |
| semantic projection fingerprint 독립 재현 | `FAIL`, projection 계약 부재 |
| JSON/Markdown primary fact parity | `PASS`, `41/41`, missing `0` |
| manifest generated file 현재 바이트 대조 | `PASS`, `18/18`, mismatch `0` |
| REL-17 USER_GUIDE/current projection 필수 표식 | `PASS`, missing `0/0` |
| `python3 scripts/build_walksafe_formal_rel_ops_cls_20260721.py --check` | `PASS`, 19 files verified |
| REL-17 targeted `unittest` | `PASS`, `1/1` |

시스템 Python에 `pytest` 모듈이 없어 pytest frontend는 실행되지 않았다. 동일한 대상 메서드 `WalkSafeFormalRelOpsClsTests.test_rel_17_user_guide_content_is_complete_without_closing_neighbors`를 표준 `unittest`로 실행해 통과시켰다.

## 승인 경계

- W8 독립 검토: `NO_GO`
- central transition applied: `false` 유지
- REL-17 내용 판정: `OK_CANDIDATE_CONTENT_ONLY`
- release/deployment/legal/model approval: 부여하지 않음
- 재검토 조건: `MAJOR-W8-001`과 `MAJOR-W8-002` 수정, 새 subject digest와 세 fingerprint 독립 재현
