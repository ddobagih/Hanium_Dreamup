# W7 AIML 독립 재검토

- 재검토일: `2026-07-27`
- 선행 리뷰: `independent-review.md` 보존
- 판정: `GO`
- finding: `blocking 0 / major 0 / minor 0`
- subject-set count: `11`
- subject-set digest: `7bc296d82ce24ec68f59cc0d185fce5693030784effcbc6b28c0bbad44088240`
- digest 규칙: 기존 리뷰와 동일한 11개 대상의 `{path, sha256, byte_length}`를 path 오름차순으로 배열하고 UTF-8 JSON, `ensure_ascii=false`, key 정렬, compact separator, trailing LF로 canonicalize한 뒤 SHA-256

## 기존 minor 종결

### Fallback 역할 토큰

최신 JSON과 Markdown의 역할 토큰이 정본 `model-register.json`과 정확히 일치한다.

- `custom_tactile`: `LEGACY_TWO_MODEL_TACTILE_FALLBACK_COMPONENT`
- `coco_general`: `LEGACY_TWO_MODEL_GENERAL_FALLBACK_COMPONENT`

`unified_walksafe`는 `PRIMARY`이며 fallback alias `legacy_two_model`이 위 두 모델을 정확한 순서로 가리킨다. 두 fallback 모델도 `unified_walksafe`를 fallback 대상으로 가리킨다.

### Semantic projection fingerprint

최신 문서가 exact projection 계약을 제공하며 다음 규칙으로 독립 재현했다.

- root: `artifact_dispositions`, `runtime_models`, `formal_boundary`
- 각 root는 계약에 열거된 필드만 선택
- artifact와 model 배열 및 모든 중첩 배열은 원본 순서 보존
- 누락 필드는 오류, `null`·boolean·number·string은 JSON type과 값을 그대로 보존
- UTF-8, `ensure_ascii=false`, `sort_keys=true`, separator `,`와 `:`, trailing LF
- projection 본문에는 별도 schema field를 추가하지 않음
- SHA-256 lowercase hexadecimal

재현값은 저장값과 동일하다.

`557ce0fcf0a45cf06a9d9964ecfecb11aeb4e1e291971ba551eb378228f1916d`

추가 무결성 회귀도 없다.

- JSON content: `55f5112644c0f8fe26cb1719c31f9242d70c629d602a9aa8531d256dfa177517`
- input set: `3d7769079742311200b547e5c9bc65529c64c9080b8f477498d8d1b22afc0141`
- Markdown projection: `ab1807c56d722118c865c442619c20c70821ca68f53fac649ebc7e93095293f1`

## 회귀 판정

- exact8 JSON/Markdown 순서·집합 일치: `PASS`
- `DLV-AIML-14` 내부 `OK` 후보 1건과 나머지 `INTERNAL_GAP` 7건: `PASS`
- `source_commit=null`, `build_id=null`: `PASS`
- runtime model exact3 및 `UNKNOWN_PROVENANCE` exact2: `PASS`
- model register와 역할·fallback 일치: `PASS`

| Runtime model | Android asset SHA-256 | Bytes | 상태 |
|---|---|---:|---|
| `unified_walksafe` | `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19` | `9984493` | asset 일치 |
| `custom_tactile` | `3336a4411eda461a3159cca80c046d52a5dadfd6a91f3831490bb5574a160780` | `38460640` | asset 일치 |
| `coco_general` | `776cafdaf1e0bc585a076d4ee3dd71d62f653e0bc2f8504f287e7b5a76c689e1` | `10343256` | asset 일치 |

세 모델 모두 evaluation, conversion equivalence, device validation은 `NOT_RUN`, deployment eligible은 `false`, release eligibility는 `NOT_ELIGIBLE`이다. 전체 formal boundary도 evaluation, equivalence, on-device validation, threshold sweep, independent reevaluation을 `NOT_RUN`, release approval을 `NOT_APPROVED`, release eligibility를 `NOT_ELIGIBLE`로 유지한다. 모든 exact8 disposition도 `NOT_APPROVED/NOT_ELIGIBLE` 경계를 보존한다.

테스트는 재실행하지 않았다. 이번 재검토는 최신 current-state pair, 정본 model register, 현재 runtime asset의 정적 계약·해시·크기 및 fingerprint 재현만 수행했다.

