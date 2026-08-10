# W6 AIML data/schema current state

- 문서 ID: `WALKSAFE-W6-AIML-DATA-SCHEMA-CURRENT-STATE-20260727`
- 범위: exact8 (`DLV-AIML-04`, `DLV-AIML-05`, `DLV-AIML-08`, `DLV-AIML-09`, `DLV-AIML-11`, `DLV-AIML-12`, `DLV-AIML-13`, `DLV-AIML-21`)
- 권한 경계: 내부 current-state 및 후보 판정이며 승인, formal pass, 배포 또는 릴리스 완료 증거가 아니다.
- 현재 결론: `DLV-AIML-04`만 독립 검토 전 `OK` 후보이고 나머지 7개는 `INTERNAL_GAP`이다.
- `source_commit=null`, `build_id=null`, release=`NOT_ELIGIBLE`.

## Hash-bound source generation evidence

| Role | Path | Bytes | SHA-256 |
|---|---|---:|---|
| `BUILDER_IMPLEMENTATION` | `scripts/build_walksafe_formal_aiml_20260721.py` | `84116` | `5babd4df6d56db99c1222611b4659c3cfa82d717beb773b7d854b8a87d376f15` |
| `TARGETED_TEST_CONTRACT` | `tests/test_walksafe_formal_aiml.py` | `23351` | `b5fedb010da952c9dbc5a0b82d505b425cbd804305224355637219516f5f8551` |
| `AIML_DATA_MANAGEMENT_GENERATED` | `docs/deliverables/08-ai-ml-data/data-management.md` | `39378` | `34426ab6b8d4ec8410291f750a5049e8f16109e84e3d07eb78e9c60be0f0e559` |
| `AIML_MODEL_DEVELOPMENT_GENERATED` | `docs/deliverables/08-ai-ml-data/model-development.md` | `23552` | `19239979cdcba6e52a6dea084c7f1fe8c5dfc5aae80d050a9c4c742604e8b939` |
| `CURRENT_DATASET_REGISTER` | `docs/deliverables/08-ai-ml-data/registers/dataset-register.json` | `26684` | `c47ae48a0d52aa2fce119f6a8432cacf3aaf21f7ef063b76f19cfad0ad1983bd` |
| `CURRENT_MODEL_REGISTER` | `docs/deliverables/08-ai-ml-data/registers/model-register.json` | `12663` | `3161e34f86ccef324a1864ad1c7310b2e31d7bcf5ed09e55945b5840635e8f88` |
| `AIML_GENERATION_MANIFEST` | `docs/deliverables/manifests/aiml-draft-20260721-r001.json` | `38456` | `5b912ac448732ee9860cfc723bcd3d677f8d119dbd30bc2ff5c58f3e5fd47770` |

- evidence file count: `7`; input-set fingerprint: `2d145f9693807c661d153a8870ba7811b6cb7430f8498f7250d9d4d1d8baf860`
- 별도 command log 파일은 생성하지 않았다. 아래 결과는 위 7개 해시 집합과 결속된 동일 세션 실행 결과다.

| Execution | Result | Bound detail |
|---|---|---|
| builder generation | `PASS` | generated `11`; materialized `14`; planned `12`; formal executions `0`; gates `5 NOT_RUN`; release `NOT_ELIGIBLE` |
| builder `--check` | `PASS` | verified `11` files |
| targeted pytest | `PASS` | `19 passed`, `0 failed` |

## Exact8 disposition

| Artifact | Current disposition | Preserved reason / next evidence |
|---|---|---|
| `DLV-AIML-04` | `OK_CANDIDATE_PENDING_INDEPENDENT_REVIEW` | The current dataset/model registers bind the exact three-model 13/3/80 class order, model-specific namespaces, canonical-name conversion and breaking-major migration rules. Independent review has not run; this is an OK candidate, not an approved or formal closure. |
| `DLV-AIML-05` | `INTERNAL_GAP` | The generated register is a schema/current-state candidate and does not contain an actual immutable dataset manifest with content-level image/label hashes. Produce and bind the actual dataset manifest and content hashes. |
| `DLV-AIML-08` | `INTERNAL_GAP` | No gold-set receipt, independent double-label result or adjudication evidence is bound. Create an immutable gold set, double-label it and bind adjudication evidence. |
| `DLV-AIML-09` | `INTERNAL_GAP` | No immutable train/validation/test split manifest or capture-group leakage checker execution is bound. Freeze split membership by capture group and execute a zero-leakage checker. |
| `DLV-AIML-11` | `INTERNAL_GAP` | The current unified candidate source is hash-bound, but dataset snapshot, split, config, environment, run and checkpoint are not connected as one reproducible chain; fallback source provenance remains unknown. Bind one immutable end-to-end training lineage and resolve fallback provenance. |
| `DLV-AIML-12` | `INTERNAL_GAP` | Candidate metrics are not formal evaluation evidence and the formal experiment count is zero. Execute the approved protocol and bind run-level inputs, outputs and metrics. |
| `DLV-AIML-13` | `INTERNAL_GAP` | No two model candidates have been evaluated on the same immutable split and metric protocol. Run and bind at least one same-protocol comparison without mixing candidate metrics. |
| `DLV-AIML-21` | `INTERNAL_GAP` | No source-versus-export prediction equivalence run is bound for the runtime artifacts. Run equivalence on the immutable evaluation set after every affected model re-export. |

AIML04의 `OK`는 독립 검토 전 후보일 뿐이다. 승인, formal closure 및 release eligibility는 주장하지 않는다.

## Runtime class contract

| Runtime model | Role | Count | ID namespace | Order SHA-256 |
|---|---|---:|---|---|
| `unified_walksafe` | `PRIMARY` | `13` | `walksafe.unified_walksafe.class_id` | `5a01ce89ef0123c136fc17910c326fc64e70a3eaed77f3d4e80548518722d6d3` |
| `custom_tactile` | `LEGACY_TWO_MODEL_TACTILE_FALLBACK_COMPONENT` | `3` | `walksafe.custom_tactile.class_id` | `85210b06510e5f0967d1c3ae325aa82e0731a11fe53e0b6a83f67e3d1ad84c00` |
| `coco_general` | `LEGACY_TWO_MODEL_GENERAL_FALLBACK_COMPONENT` | `80` | `coco.coco_general.class_id` | `a2bb9c8218affdef450cc85951e6a5abc5e4d956cd37b1be3d04840ae0120c8e` |

숫자 class ID는 각 모델 namespace 안에서만 의미가 있다. 서로 다른 모델의 같은 숫자는 의미 동등성을 뜻하지 않는다. qualified ID는 `<model-specific namespace>:<zero-based class_id>` 형식이다.

Canonical-name 변환은 `trim -> lowercase ASCII -> 각 non-alphanumeric run을 underscore로 치환 -> 양끝 underscore 제거`다. 이 변환은 이름 비교용이며 모델별 숫자 ID namespace를 합치지 않는다.

### `unified_walksafe` exact order (13)

- runtime labels: `0:person, 1:bicycle, 2:car, 3:motorcycle, 4:bus, 5:truck, 6:traffic light, 7:normal_tactile_block, 8:damaged_tactile_block, 9:crosswalk, 10:curb_step, 11:uneven_sidewalk, 12:e_scooter_obstruction`
- canonical names: `0:person, 1:bicycle, 2:car, 3:motorcycle, 4:bus, 5:truck, 6:traffic_light, 7:normal_tactile_block, 8:damaged_tactile_block, 9:crosswalk, 10:curb_step, 11:uneven_sidewalk, 12:e_scooter_obstruction`

### `custom_tactile` exact order (3)

- runtime labels: `0:normal_tactile_block, 1:damaged_tactile_block, 2:tactile_damage_area`
- canonical names: `0:normal_tactile_block, 1:damaged_tactile_block, 2:tactile_damage_area`

### `coco_general` exact order (80)

- runtime labels: `0:person, 1:bicycle, 2:car, 3:motorcycle, 4:airplane, 5:bus, 6:train, 7:truck, 8:boat, 9:traffic light, 10:fire hydrant, 11:stop sign, 12:parking meter, 13:bench, 14:bird, 15:cat, 16:dog, 17:horse, 18:sheep, 19:cow, 20:elephant, 21:bear, 22:zebra, 23:giraffe, 24:backpack, 25:umbrella, 26:handbag, 27:tie, 28:suitcase, 29:frisbee, 30:skis, 31:snowboard, 32:sports ball, 33:kite, 34:baseball bat, 35:baseball glove, 36:skateboard, 37:surfboard, 38:tennis racket, 39:bottle, 40:wine glass, 41:cup, 42:fork, 43:knife, 44:spoon, 45:bowl, 46:banana, 47:apple, 48:sandwich, 49:orange, 50:broccoli, 51:carrot, 52:hot dog, 53:pizza, 54:donut, 55:cake, 56:chair, 57:couch, 58:potted plant, 59:bed, 60:dining table, 61:toilet, 62:tv, 63:laptop, 64:mouse, 65:remote, 66:keyboard, 67:cell phone, 68:microwave, 69:oven, 70:toaster, 71:sink, 72:refrigerator, 73:book, 74:clock, 75:vase, 76:scissors, 77:teddy bear, 78:hair drier, 79:toothbrush`
- canonical names: `0:person, 1:bicycle, 2:car, 3:motorcycle, 4:airplane, 5:bus, 6:train, 7:truck, 8:boat, 9:traffic_light, 10:fire_hydrant, 11:stop_sign, 12:parking_meter, 13:bench, 14:bird, 15:cat, 16:dog, 17:horse, 18:sheep, 19:cow, 20:elephant, 21:bear, 22:zebra, 23:giraffe, 24:backpack, 25:umbrella, 26:handbag, 27:tie, 28:suitcase, 29:frisbee, 30:skis, 31:snowboard, 32:sports_ball, 33:kite, 34:baseball_bat, 35:baseball_glove, 36:skateboard, 37:surfboard, 38:tennis_racket, 39:bottle, 40:wine_glass, 41:cup, 42:fork, 43:knife, 44:spoon, 45:bowl, 46:banana, 47:apple, 48:sandwich, 49:orange, 50:broccoli, 51:carrot, 52:hot_dog, 53:pizza, 54:donut, 55:cake, 56:chair, 57:couch, 58:potted_plant, 59:bed, 60:dining_table, 61:toilet, 62:tv, 63:laptop, 64:mouse, 65:remote, 66:keyboard, 67:cell_phone, 68:microwave, 69:oven, 70:toaster, 71:sink, 72:refrigerator, 73:book, 74:clock, 75:vase, 76:scissors, 77:teddy_bear, 78:hair_drier, 79:toothbrush`

## Breaking-major migration boundary

| Change | Classification | Required consequence |
|---|---|---|
| class addition | `BREAKING_MAJOR` | 새 major schema, runtime config/Kotlin map 동시 갱신, 영향 모델 재-export, prediction equivalence 재검증 |
| class deletion | `BREAKING_MAJOR` | 새 major schema, runtime config/Kotlin map 동시 갱신, 영향 모델 재-export, prediction equivalence 재검증 |
| class reorder | `BREAKING_MAJOR` | 새 major schema, runtime config/Kotlin map 동시 갱신, 영향 모델 재-export, prediction equivalence 재검증 |

- 현재 migration status: `NOT_RUN`
- 현재 prediction conversion equivalence: `NOT_RUN`

## Current register bindings

- dataset register: `docs/deliverables/08-ai-ml-data/registers/dataset-register.json` / `c47ae48a0d52aa2fce119f6a8432cacf3aaf21f7ef063b76f19cfad0ad1983bd` / `26684` bytes
- model register: `docs/deliverables/08-ai-ml-data/registers/model-register.json` / `3161e34f86ccef324a1864ad1c7310b2e31d7bcf5ed09e55945b5840635e8f88` / `12663` bytes
- `unified_walksafe`: primary; source candidate bound but `NOT_APPROVED`; evaluation/equivalence/device `NOT_RUN`; release `NOT_ELIGIBLE`.
- `custom_tactile`: fallback; source `UNKNOWN_PROVENANCE`; evaluation/equivalence/device `NOT_RUN`; release `NOT_ELIGIBLE`.
- `coco_general`: fallback; source `UNKNOWN_PROVENANCE`; evaluation/equivalence/device `NOT_RUN`; release `NOT_ELIGIBLE`.

## Preserved INTERNAL_GAP boundary

- AIML05: 실제 immutable dataset manifest와 image/label content hash가 없다.
- AIML08: gold set, double-label 및 adjudication 근거가 없다.
- AIML09: immutable split과 capture-group leakage checker 실행 근거가 없다.
- AIML11: dataset/split/config/environment/run/checkpoint의 재현 가능한 training provenance chain이 단절되어 있다.
- AIML12: formal experiment count는 `0`이다.
- AIML13: 동일 immutable split과 protocol의 비교 count는 `0`이다.
- AIML21: source/export prediction equivalence는 `NOT_RUN`이다.

## Verification boundary

| Boundary | Current state |
|---|---|
| formal test count | `279` |
| formal execution / executed / passed / evidence | `NOT_RUN / 0 / 0 / 0` |
| formal QA approval | `NOT_APPROVED_PENDING` |
| actual device | `NOT_RUN` |
| prediction equivalence | `NOT_RUN` |
| deployment | `NOT_RUN` |
| release gates | `5 / NOT_RUN / not waived` |
| release | `NOT_ELIGIBLE` |

## Deterministic self-validation

- exact JSON/Markdown ID parity: `PASS (8 / unique 8)`
- disposition boundary: `PASS (OK candidate 1 / INTERNAL_GAP 7 / final closure 0)`
- runtime counts/order/namespaces: `PASS (13 / 3 / 80; three unique namespaces)`
- current register hash bindings: `PASS (2)`
- source evidence hash bindings: `PASS (7)`
- formal/device/release boundary: `PASS`
- JSON content fingerprint: `5d26dba8ca105121ccc29ee4cb56e710615aec08aa8dcde13caec800bedf665a`
- semantic projection fingerprint: `151b9092646686e86b313db67fc298be35686363efad65b03b73f28a75d34926`
- input-set fingerprint: `2d145f9693807c661d153a8870ba7811b6cb7430f8498f7250d9d4d1d8baf860`
- product tests by this authoring step: `NOT_RUN`; Git: `NOT_USED`; daylog: `NOT_WRITTEN_BY_INSTRUCTION`
<!-- walksafe-json-content-fingerprint: 5d26dba8ca105121ccc29ee4cb56e710615aec08aa8dcde13caec800bedf665a -->
<!-- walksafe-semantic-parity-fingerprint: 151b9092646686e86b313db67fc298be35686363efad65b03b73f28a75d34926 -->
<!-- walksafe-input-set-fingerprint: 2d145f9693807c661d153a8870ba7811b6cb7430f8498f7250d9d4d1d8baf860 -->
