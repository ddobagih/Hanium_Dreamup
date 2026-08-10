# Data and model source current state

- Snapshot: `WS-DATA-MODEL-SOURCE-CURRENT-STATE-20260727-001`
- Scope: `HANIUM_SUBMISSION_AND_DEMO`
- 대상: `DLV-AIML-01`, `DLV-AIML-02`, `DLV-AIML-03`
- 방식: 저장소의 선별된 metadata와 단일 candidate PT를 읽어 현재 상태만 기록
- 수행하지 않음: 학습, build, test, dataset materialization, 전체 dataset hash 검사

## 판정

| ID | 현재 판정 | 남은 경계 |
|---|---|---|
| `DLV-AIML-01` | 내부에서 데이터 관리 현재 상태를 작성할 수 있음 | 실제 취득·권리·직접수집·동의·privacy 사실은 evidence fact request 또는 `NOT_VERIFIED` |
| `DLV-AIML-02` | dataset 이름·선언 구성·class·후보 모델 연결은 확인됨 | 현재 materialized manifest와 `data.yaml`이 없어 content hash·split·독립 test는 검증 불가 |
| `DLV-AIML-03` | provider와 일부 공식 URL을 포함한 source inventory 작성 가능 | 정확한 약관·license·취득일·계정·학습/재배포 권한·동의 원본은 `NOT_VERIFIED` |

## 현재 13-class dataset

저장소 문서는 `datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627`을 현재 후보로 선언한다. `data_sources/README.md`에는 13 classes, train 167,759장, validation 28,747장, 독립 test 없음, 전체 196,506장, bbox 607,814개가 적혀 있다. 이번 작업에서는 원본 dataset을 materialize하거나 수량을 다시 계산하지 않았으므로 모두 `DECLARED_NOT_RECOMPUTED`다.

다음 현재 기준선 파일은 저장소에 없다.

| 경로 | 상태 |
|---|---|
| `datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/data.yaml` | `MISSING` |
| `datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/materialized_manifest.csv` | `MISSING`; 선언 hash만 존재하고 검증 불가 |
| `reports/walksafe_best_eval_20260708/training_reference_index_20260708.md` | `MISSING` |
| `data_sources/manifests/walksafe_aihub_source_usage_20260628.md` | `MISSING` |
| `docs/model-data/walksafe_13class_dataset_source_contract_20260619.md` | `MISSING` |

5월의 curation/index manifest와 AI task manifest는 존재하지만 현재 13-class 전체 materialized manifest를 대체하지 않는다.

<!-- data-model-missing-path-inventory:start -->
## Missing-path deterministic inventory binding

- Inventory: `docs/control/execution/artifact-closure/run-20260727-001/data-model-missing-path-inventory.json`
- Checked at: `2026-07-27T19:08:16+09:00`
- Inventory bytes: `15880`
- Inventory file SHA-256: `5c6a2a1554d106336996e9b0d50c7744f79aac65ea76ca93a27b2c6d68a0356c`
- Filesystem-state SHA-256: `f828481a8950ace025b1fb8d54ae9e1f3f2594a7fd8095190426582cf1f968ea`
- Inventory content fingerprint: `1d3e37be9b53e276fca12e7768fd272532fe3cacdb1f32a82776c3a1e89eb22d`
- 검사 경계: 선언된 예상 경로의 존재 여부와 가장 가까운 기존 parent의 직계 entry 이름·형식·크기만 확인했다. 파일 원문과 하위 디렉터리는 읽지 않았다.

| Expected missing path | Nearest existing parent | First missing component | Parent direct-entry inventory SHA-256 |
|---|---|---|---|
| `reports/walksafe_best_eval_20260708/training_reference_index_20260708.md` | `.` | `reports` | `f86dd3734aff8fdfbc973532c0e14f0632c0b54a1653001fce465271fdab6a4f` |
| `data_sources/manifests/walksafe_aihub_source_usage_20260628.md` | `data_sources/manifests` | `walksafe_aihub_source_usage_20260628.md` | `e894057b67cd593ce088f4302f7b788fc33bf73b9eb27b35e7187e8b54a465c0` |
| `docs/model-data/walksafe_13class_dataset_source_contract_20260619.md` | `docs` | `model-data` | `60f656e6d9f7d8289b70d287ab0cbe6b0ad548c5f8656353f3aa6b55061f0d0e` |
| `datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/data.yaml` | `.` | `datasets` | `f86dd3734aff8fdfbc973532c0e14f0632c0b54a1653001fce465271fdab6a4f` |
| `datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/materialized_manifest.csv` | `.` | `datasets` | `f86dd3734aff8fdfbc973532c0e14f0632c0b54a1653001fce465271fdab6a4f` |
<!-- data-model-missing-path-inventory:end -->

## 선언된 현재 source

선언 source entry는 6개다. 정규화한 `(provider, dataset identity)` 기준 unique dataset/provider pair는 5개이며, AIHub 189 Surface와 AIHub 189 수동 e-scooter entry는 같은 pair로 한 번만 계산한다.

| Source | Provider·URL | 포함 상태 | License·학습·재배포 | 동의·privacy |
|---|---|---|---|---|
| COCO 2017 | provider·정확한 URL `UNKNOWN_IN_REPOSITORY` | 현재 dataset 포함 선언, 재현 불가 | `NOT_VERIFIED` | consent applicability `UNDETERMINED`; verification `NOT_VERIFIED` |
| AIHub 186 | AIHub, URL `UNKNOWN_IN_REPOSITORY` | 현재 dataset 포함 선언, 재현 불가 | `NOT_VERIFIED` | `UNKNOWN/NOT_VERIFIED` |
| AIHub 513 보행 안전 도로 시설물 | AIHub, `https://www.aihub.or.kr/aihubdata/data/view.do?aihubDataSe=data&currMenu=115&dataSetSn=513&topMenu=100` | 포함 선언과 과거 derived manifest 존재 | 다운로드 승인/API 필요 선언, license 원문·권한 `NOT_VERIFIED` | contact sheet에서 명백한 얼굴·번호판이 없었다는 제한된 기록만 있음; source audit `NOT_RUN` |
| AIHub 189 Surface | AIHub, `https://aihub.or.kr/aihubdata/data/view.do?dataSetSn=189` | 포함 선언, 재현 불가 | 다운로드 승인/API 필요 선언, license 원문·권한 `NOT_VERIFIED` | `UNKNOWN/NOT_VERIFIED` |
| AIHub 572 e-scooter | AIHub, URL `UNKNOWN_IN_REPOSITORY` | 사람이 label 승인한 16,005장 포함 선언, 재현 불가 | `NOT_VERIFIED` | `UNKNOWN/NOT_VERIFIED` |
| AIHub 189 manual e-scooter | AIHub, 위 AIHub 189 URL | 사람이 relabel 승인한 7장 포함 선언, 재현 불가 | `NOT_VERIFIED` | `UNKNOWN/NOT_VERIFIED` |

사람이 label을 승인했다는 기록은 source 권리, 재배포 권리 또는 참여자 동의 증거가 아니다.

## 과거·비현재 후보

- `walksafe_v1`의 `LibreYOLO/road-traffic`, `Libre-YOLO/street-work`, `jaygala24/pothole-detection`은 과거 smoke/pretrain 후보다. 현재 13-class 입력으로 선언되지 않았고 정확한 URL과 license는 `NOT_VERIFIED`다.
- AIHub 159 1인칭 보행영상은 2026-05-19 기록에서 파일 미확인 후보다.
- 직접 촬영 한국 보행 데이터는 계획으로만 적혀 있다. 현재 학습 포함 여부, 실제 수집, consent record와 privacy 검토는 `UNKNOWN`이다.
- v3 relabel package는 AIHub 513 계열 경로의 sanitized image 20개와 review row 32개를 가진다. source-level rights·consent·privacy 완료를 뜻하지 않는다.
- tactile damage area package의 AI suggestion은 120행이지만 non-final이며 apply summary는 120행 모두 pending, dataset build `false`, materialized manifest 없음이다.

## 모델

| 모델 | 현재 사실 | provenance·license 경계 |
|---|---|---|
| `walksafe-13cls-yolo26n-img768-epoch270-20260708` | candidate PT 존재, 5,411,845 bytes, SHA-256 `a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669` | dataset manifest 누락, YOLO26n COCO pretrained base의 정확한 origin·URL·license `NOT_VERIFIED`, release 불가 |
| Android 13-class TFLite | model register가 9,984,493 bytes와 SHA-256 `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19`를 선언 | 이번 review에서 asset을 다시 hash하지 않음; 실기기·변환 동등성 `NOT_RUN` |
| `custom_tactile` | legacy fallback runtime model | source artifact provenance `UNKNOWN_PROVENANCE` |
| `coco_general` | legacy COCO fallback runtime model | source artifact provenance `UNKNOWN_PROVENANCE` |

## 정확한 evidence input

전체 28개 입력의 exact path, byte length, SHA-256은 동반 JSON의 `evidence_inputs`에 기록했다. 핵심 입력은 다음과 같다.

| Path | Bytes | SHA-256 |
|---|---:|---|
| `data_sources/README.md` | 3,046 | `6daa5aa0d76e6565917128d490242a468195f171f70326513f244d42c0b1aabf` |
| `data_sources/manifests/korean_dataset_candidates.md` | 6,020 | `29ee8575deee9c4b9c09fd46cafe6a1c06d99a77a214873e6464d9aca6e71cd3` |
| `data_sources/manifests/walksafe_v1_sources.md` | 1,482 | `fcaac487301c80171aec6b83dd0d4c2444edb2ca8162297041d06c58d117684b` |
| `data_sources/manifests/walksafe_kr_v3_candidate_index_2026-05-19.csv` | 47,179 | `6ac7cb425577d977afaaf67a0cc97ad97bb32479012620fdcb3ea289b6eaa690` |
| `ai_tasks/walksafe_v3_relabel_20260521/image_manifest.csv` | 11,679 | `94c46820ae33e9c5662e316301973d5ba7f1c63ef6cfe2fb246c1a1cda3903dc` |
| `model/registry/walksafe-model-registry.json` | 2,293 | `3768cb39c44c13dc03c827288eabdbf9ec9fb244b7750d5252c0685db5a70b3a` |
| `model/artifacts/candidates/walksafe_13cls_yolo26n_img768_20260708/walksafe_13cls_yolo26n_img768_best_epoch270.pt` | 5,411,845 | `a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669` |
| `docs/deliverables/08-ai-ml-data/registers/data-source-register.json` | 5,834 | `f0a18fa7758ebbeeb572225dbd67053909f5551c394970c7827515820f3f5f74` |
| `docs/deliverables/08-ai-ml-data/registers/dataset-register.json` | 26,684 | `c47ae48a0d52aa2fce119f6a8432cacf3aaf21f7ef063b76f19cfad0ad1983bd` |
| `docs/deliverables/08-ai-ml-data/registers/model-register.json` | 12,663 | `3161e34f86ccef324a1864ad1c7310b2e31d7bcf5ed09e55945b5840635e8f88` |

## 남은 최소 evidence fact request

1. 현재 13-class 학습 source가 COCO 2017과 AIHub 186·513·189·572뿐인지, 직접 촬영·사용자 제공 이미지가 실제 train/validation에 들어갔는지 확인이 필요하다.
2. 실제 사용 source별 취득일·계정 또는 evidence ID, 정확한 공식 URL·약관/license, 학습·변형·재배포 권한 기록이 있는지 확인이 필요하다. 없으면 `없음`으로 확정하면 된다.
3. Evidence fact request: 13-class 학습을 시작한 YOLO26n pretrained/base weight의 origin·version·download URL·license 근거를 요청한다. 확인할 수 없으면 `UNKNOWN_PROVENANCE`로 확정한다.

