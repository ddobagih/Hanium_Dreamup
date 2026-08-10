# W6 AIML 독립 검토

- 검토일: `2026-07-27`
- 판정: `GO`
- finding: `blocking 0 / major 0 / minor 0`
- 검토 범위: 공통 AIML 변경 7개와 W6/W7 current-state JSON/Markdown, 총 11개 파일
- subject-set digest: `6912e7e3a87394d33ac1d17755c39064e6457d7bc44cfa1ea635ee4d04dc134f`
- digest 규칙: 검토 대상 11개 파일의 `{path, sha256, byte_length}`를 path 오름차순으로 배열하고 UTF-8 JSON, `ensure_ascii=false`, key 정렬, compact separator, trailing LF로 canonicalize한 뒤 SHA-256

## Exact 판정

| ID | 독립 판정 | 근거 |
|---|---|---|
| `DLV-AIML-04` | `OK` 내부 후보 | 런타임 exact3 모델의 class 순서, 모델별 namespace, canonical-name 변환, breaking-major migration 규칙이 현재 runtime/Kotlin map과 일치하며 해시 결속됨 |
| `DLV-AIML-05` | `INTERNAL_GAP` | 실제 immutable dataset manifest와 image/label content hash 없음 |
| `DLV-AIML-08` | `INTERNAL_GAP` | gold set, double-label, adjudication 실행 증거 없음 |
| `DLV-AIML-09` | `INTERNAL_GAP` | immutable split과 capture-group leakage checker 실행 증거 없음 |
| `DLV-AIML-11` | `INTERNAL_GAP` | dataset/split/config/environment/run/checkpoint의 단일 training provenance chain 없음 |
| `DLV-AIML-12` | `INTERNAL_GAP` | formal experiment count `0` |
| `DLV-AIML-13` | `INTERNAL_GAP` | 같은 immutable split/protocol을 사용한 model comparison count `0` |
| `DLV-AIML-21` | `INTERNAL_GAP` | source/export prediction equivalence `NOT_RUN` |

JSON과 Markdown의 ID 순서 및 집합은 위 exact8과 동일하며 중복이나 누락이 없다. `DLV-AIML-04`의 `OK`는 내부 current-state 후보 판정이며 승인, formal PASS, 배포 또는 릴리스 완료를 의미하지 않는다.

## Class 계약

| Runtime model | Class count | Namespace | Order SHA-256 |
|---|---:|---|---|
| `unified_walksafe` | `13` | `walksafe.unified_walksafe.class_id` | `5a01ce89ef0123c136fc17910c326fc64e70a3eaed77f3d4e80548518722d6d3` |
| `custom_tactile` | `3` | `walksafe.custom_tactile.class_id` | `85210b06510e5f0967d1c3ae325aa82e0731a11fe53e0b6a83f67e3d1ad84c00` |
| `coco_general` | `80` | `coco.coco_general.class_id` | `a2bb9c8218affdef450cc85951e6a5abc5e4d956cd37b1be3d04840ae0120c8e` |

- runtime config와 `TwoModelClassMap.kt`의 모델 exact set과 class order가 일치한다.
- 숫자 class ID는 모델별 namespace에서만 의미가 있으며 모델 사이의 같은 숫자를 의미 동등으로 취급하지 않는다.
- canonical name은 `trim -> lowercase ASCII -> non-alphanumeric run을 underscore로 치환 -> 양끝 underscore 제거` 규칙을 따른다.
- class 추가, 삭제, 순서 변경은 모두 `BREAKING_MAJOR`다.
- migration, 재-export 및 prediction conversion equivalence는 `NOT_RUN`이다.

## 경계 및 과대주장 검사

- `source_commit=null`, `build_id=null`이며 path/SHA-256/byte-length 결속만 주장한다.
- `custom_tactile`, `coco_general` 원천은 `UNKNOWN_PROVENANCE`로 유지된다.
- dataset, gold set, split, training, experiment, comparison, equivalence 완료 주장 수는 `0`이다.
- formal test catalog `279`건은 `NOT_RUN / executed 0 / passed 0 / evidence 0`이다.
- 실제 기기, prediction equivalence, deployment는 `NOT_RUN`이다.
- 5개 release gate는 `NOT_RUN`, 미면제이며 release는 `NOT_ELIGIBLE`이다.
- formal 승인이나 테스트 완료를 주장한 곳은 없다.

## 독립 검증

- `PYTHONDONTWRITEBYTECODE=1 python3 scripts/build_walksafe_formal_aiml_20260721.py --check`
  - `PASS`: generated file `11`, materialized `14`, planned `12`, formal execution `0`, gate `5 NOT_RUN`, release `NOT_ELIGIBLE`
- `PYTHONDONTWRITEBYTECODE=1 /home/ddobagi/.venv/bin/python -m pytest -q -p no:cacheprovider tests/test_walksafe_formal_aiml.py`
  - `PASS`: `19 passed`, `0 failed`
- W6 JSON content, semantic projection, input-set fingerprint를 각각 독립 재현했다.
- W6 JSON/Markdown exact8 및 세 fingerprint 표기가 일치한다.
- 선언된 W6 source evidence 7개의 현재 path/hash/size가 모두 일치한다.
- Android/Gradle, actual-device, formal279 실행은 수행하지 않았다.

