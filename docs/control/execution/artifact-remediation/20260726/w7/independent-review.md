# W7 AIML 독립 검토

- 검토일: `2026-07-27`
- 판정: `GO`
- finding: `blocking 0 / major 0 / minor 2`
- 검토 범위: 공통 AIML 변경 7개와 W6/W7 current-state JSON/Markdown, 총 11개 파일
- subject-set digest: `6912e7e3a87394d33ac1d17755c39064e6457d7bc44cfa1ea635ee4d04dc134f`
- digest 규칙: 검토 대상 11개 파일의 `{path, sha256, byte_length}`를 path 오름차순으로 배열하고 UTF-8 JSON, `ensure_ascii=false`, key 정렬, compact separator, trailing LF로 canonicalize한 뒤 SHA-256

## Findings

### `minor` W7 요약의 fallback 역할 토큰이 정본 토큰과 다름

W7 `model_registry_projection`과 Markdown은 `custom_tactile`을 `LEGACY_TACTILE_FALLBACK_COMPONENT`, `coco_general`을 `LEGACY_GENERAL_FALLBACK_COMPONENT`로 표시한다. 정본 `model-register.json`과 W7에 내장된 `source_record`는 각각 `LEGACY_TWO_MODEL_TACTILE_FALLBACK_COMPONENT`, `LEGACY_TWO_MODEL_GENERAL_FALLBACK_COMPONENT`를 사용한다.

역할과 fallback 대상의 의미는 일치하고 정본 record도 보존되어 있어 현재 후보 판정을 뒤집지는 않는다. 다만 기계 소비자와 문서 통일성을 위해 W7 요약 토큰을 정본과 동일하게 맞춰야 한다.

### `minor` W7 semantic projection fingerprint의 독립 재현 규칙이 불완전함

W7은 semantic projection schema 이름만 기록하고 포함 필드와 변환 규칙을 기록하지 않는다. 저장된 모든 top-level field 조합으로도 `fa62a3cdaf39bf4bcd0b6e0812d3aefd9cc2beb9a1a38ec273b3cfab03bac42f`를 재현할 수 없었다.

전체 JSON content fingerprint, source-binding input-set fingerprint, Markdown projection fingerprint는 모두 독립 재현되므로 내용 결속 자체는 유지된다. 향후에는 semantic projection의 정확한 key 집합과 변환 규칙 또는 결정론적 checker를 함께 제공해야 한다.

## Exact 판정

| ID | 독립 판정 | 근거 |
|---|---|---|
| `DLV-AIML-06` | `INTERNAL_GAP` | keep/fix/hold/drop 수치 기준과 적용·승인 증거 없음 |
| `DLV-AIML-07` | `INTERNAL_GAP` | class별 승인 positive/negative 예시 세트 없음 |
| `DLV-AIML-10` | `INTERNAL_GAP` | capture/perceptual/sequence/metadata leakage 실행 결과 없음 |
| `DLV-AIML-14` | `OK` 내부 후보 | exact3 runtime 모델의 자산, 계약, 역할, fallback 및 보수적 상태가 정본 register와 현재 파일 hash에 결속됨 |
| `DLV-AIML-15` | `INTERNAL_GAP` | 모델별 card 3개와 승인 결과 없음 |
| `DLV-AIML-16` | `INTERNAL_GAP` | fallback 2개의 원천 provenance와 conversion equivalence 없음 |
| `DLV-AIML-17` | `INTERNAL_GAP` | 사전 동결 protocol/split hash/grid/수용 기준/tolerance 없음 |
| `DLV-AIML-23` | `INTERNAL_GAP` | threshold sweep와 독립 재평가 `NOT_RUN` |

JSON과 Markdown의 ID 순서 및 집합은 위 exact8과 동일하며 중복이나 누락이 없다. `DLV-AIML-14`의 `OK`는 내부 current-state 후보 판정이며 모델 승인이나 릴리스 적격 판정이 아니다.

## Runtime exact3 검증

| Model | Android asset / SHA-256 / bytes | Input / output | Class / role / fallback |
|---|---|---|---|
| `unified_walksafe` | `apps/android/app/src/main/assets/models/walksafe_unified_yolo26n_768_float32.tflite` / `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19` / `9984493` | input `[1,768,768,3] float32`; output `[1,300,6] float32` | `13`, `walksafe.unified_walksafe.class_id`, `PRIMARY`; fallback alias `legacy_two_model` -> `custom_tactile`, `coco_general` |
| `custom_tactile` | `apps/android/app/src/main/assets/models/custom_tactile_yolo26s_float32.tflite` / `3336a4411eda461a3159cca80c046d52a5dadfd6a91f3831490bb5574a160780` / `38460640` | input side `960`, shape/dtype 미선언; output class-order만 선언, tensor shape/dtype 미선언 | `3`, `walksafe.custom_tactile.class_id`, 정본 역할 `LEGACY_TWO_MODEL_TACTILE_FALLBACK_COMPONENT`; `legacy_two_model`, fallback for `unified_walksafe` |
| `coco_general` | `apps/android/app/src/main/assets/models/coco_yolo26n_float32.tflite` / `776cafdaf1e0bc585a076d4ee3dd71d62f653e0bc2f8504f287e7b5a76c689e1` / `10343256` | input side `640`, shape/dtype 미선언; output class-order만 선언, tensor shape/dtype 미선언 | `80`, `coco.coco_general.class_id`, 정본 역할 `LEGACY_TWO_MODEL_GENERAL_FALLBACK_COMPONENT`; `legacy_two_model`, fallback for `unified_walksafe` |

- 세 Android asset의 현재 path/hash/size는 모두 일치한다.
- `unified_walksafe` 원천 후보는 path/hash/size가 결속됐지만 `NOT_APPROVED`다.
- `custom_tactile`, `coco_general` 원천 path/hash/size/format은 `null`이며 `UNKNOWN_PROVENANCE`다.
- model별 class order는 13/3/80 exact order와 각 order SHA-256에 일치한다.
- 모든 모델은 evaluation, conversion equivalence, device validation이 `NOT_RUN`, approval이 `NOT_APPROVED`, deployment eligible이 `false`, release가 `NOT_ELIGIBLE`이다.

## 경계 및 과대주장 검사

- `source_commit=null`, `build_id=null`이다.
- model card, data quality, leakage, protocol, threshold sweep 완료 주장 수는 `0`이다.
- 후보 metric은 `candidate_metrics_not_formal_evaluation`로 격리되어 formal 평가로 사용되지 않는다.
- W6에 결속된 formal test catalog `279`건은 `NOT_RUN / passed 0`이고 W7도 formal execution evidence `0` 경계를 유지한다.
- evaluation, equivalence, on-device validation, threshold sweep, independent reevaluation은 모두 `NOT_RUN`이다.
- release approval은 `NOT_APPROVED`, release eligibility는 `NOT_ELIGIBLE`이다.

## 독립 검증

- `PYTHONDONTWRITEBYTECODE=1 python3 scripts/build_walksafe_formal_aiml_20260721.py --check`
  - `PASS`: generated file `11`, materialized `14`, planned `12`, formal execution `0`, gate `5 NOT_RUN`, release `NOT_ELIGIBLE`
- `PYTHONDONTWRITEBYTECODE=1 /home/ddobagi/.venv/bin/python -m pytest -q -p no:cacheprovider tests/test_walksafe_formal_aiml.py`
  - `PASS`: `19 passed`, `0 failed`
- W7 JSON content fingerprint, source-binding input-set fingerprint, Markdown projection fingerprint를 독립 재현했다.
- 선언된 W7 source binding 18개의 현재 path/hash/size가 모두 일치한다.
- JSON/Markdown exact8, 후보 1건/갭 7건, runtime exact3, `UNKNOWN_PROVENANCE` exact2를 확인했다.
- Android/Gradle, actual-device, formal279 실행은 수행하지 않았다.

