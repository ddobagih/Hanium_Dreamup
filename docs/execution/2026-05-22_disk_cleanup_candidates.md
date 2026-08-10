# 디스크 정리 후보 조사 (2026-05-22)

- 조사 시각: 2026-05-22 01:39 KST
- 조사 범위: 프로젝트 내부의 `runs/`, `datasets/`, `logs/`, `data_sources/`
- 수행 원칙: 삭제 없음, GPU 작업/조회 없음, `du`/`df`/`find`/`ps` 기반 조회만 수행
- 주의: 아래는 **정리 후보 문서화**이며, 실제 삭제는 사용자 승인 후 별도 수행해야 한다. 구체 삭제 명령은 의도적으로 적지 않았다.

## 1. 현재 디스크 상태

`df -h .` 결과:

| Filesystem | Size | Used | Avail | Use% | Mounted |
|---|---:|---:|---:|---:|---|
| `/dev/nvme0n1p2` | 915G | 779G | 90G | 90% | `/` |

프로젝트 내 조사 대상 합계는 약 270G 수준이다.

| 경로 | 크기 |
|---|---:|
| `datasets/` | 249G |
| `runs/` | 21G |
| `logs/` | 40M |
| `data_sources/` | 36M |

## 2. 대용량 상위 디렉터리

### `datasets/` 주요 항목

| 경로 | 크기 | 메모 |
|---|---:|---|
| `datasets/walksafe_kr_v2` | 199G | 대부분 이미지. `images/train` 139G, `images/val` 40G, `images/test` 21G |
| `datasets/walksafe_kr_v1` | 49G | 대부분 이미지. `images/train` 36G, `images/val` 11G, `images/test` 2.8G |
| `datasets/walksafe_kr_v3_privacy_sanitized_20260520` | 964M | privacy-sanitized materialized 이미지로 보임 |
| `datasets/walksafe_kr_v3_manual_review_20260520` | 367M | review overlay/crop 포함. `images/overlays` 279M, `images/crops` 75M |
| `datasets/walksafe_kr_tactile_3class_20260521` | 202M | 현재 3-class dataset. 보존 권장 |
| `datasets/walksafe_replay_v2train_v3posheavy_4to1_20260521` | 130M | materialized replay dataset |
| `datasets/walksafe_replay_v2train_v3pos_x2_20260521` | 129M | materialized replay dataset |
| `datasets/walksafe_replay_v2train_v3balanced_2to1_20260521` | 129M | materialized replay dataset |
| `datasets/walksafe_kr_v3_holdout_candidate` | 19M | holdout 후보 |

### `runs/` 주요 항목

| 경로 | 크기 | 메모 |
|---|---:|---|
| `runs/validation` | 20G | validation/materialized subset 중심 |
| `runs/validation/aihub513_vl2_vs2_tactile_subset` | 18G | `images/val` 18G. 대형 validation 결과/데이터 |
| `runs/validation/aihub513_vl1_vs1_tactile_hard_negative_200_20260517` | 1.7G | hard-negative validation 데이터/결과 |
| `runs/detect` | 503M | 학습/검증 결과 |
| `runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521` | 232M | **현재 YOLO26s 진행 중 run. 삭제 금지** |
| `runs/detect/runs` | 149M | 중첩된 완료 run들로 보임 |
| `runs/validation/aihub513_vl2_vs2_tactile_smoke` | 55M | smoke validation |
| `runs/review` | 9.1M | review 산출물 |
| `runs/failure_sampling` | 6.7M | failure sampling 산출물 |

### `logs/`, `data_sources/` 요약

| 경로/파일 | 크기 | 메모 |
|---|---:|---|
| `logs/` 전체 | 40M | 전체적으로 작음 |
| `logs/walksafe_kr_tactile_v2_full.log` | 11M | 가장 큰 로그 |
| `logs/walksafe_tactile3_yolo26s_pipeline_20260521.log` | 8.5M | YOLO26s 관련 로그. 보존 권장 |
| `logs/walksafe_replay_remaining_driver_20260521.log` | 7.4M | replay driver 로그 |
| `logs/walksafe_tactile3_yolo26s_resume_pipeline_20260522.log` | 약 413K+ | **현재 YOLO26s resume 로그. 삭제 금지** |
| `data_sources/manifests` | 36M | manifest/source 성격. 보존 권장 |
| `data_sources/labels` | 88K | 원본/소스 라벨 성격. 보존 권장 |
| `data_sources/scripts/__pycache__` | 88K | 재생성 가능한 Python cache |

## 3. 현재 진행 중인 YOLO26s run/log 확인

`ps` 조회에서 현재 다음 프로세스가 확인됐다.

- `bash scripts/resume_walksafe_tactile3_yolo26s_20260522.sh` (PID file: `logs/walksafe_tactile3_yolo26s_resume_pipeline_20260522.pid` → `2773678`)
- `.venv/bin/yolo detect train model=/home/ddobagi/Code/hanium-dreamup/runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/weights/last.pt resume=True workers=4`

최근 갱신 파일도 같은 run을 가리킨다.

| 파일 | 최근 수정/크기 |
|---|---:|
| `runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/weights/last.pt` | 2026-05-22 01:34 KST, 약 58M |
| `runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/results.csv` | 2026-05-22 01:34 KST |
| `logs/walksafe_tactile3_yolo26s_resume_pipeline_20260522.log` | 2026-05-22 01:38 KST 기준 갱신 중 |

따라서 아래는 **절대 삭제 금지**다.

- `runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/`
- `logs/walksafe_tactile3_yolo26s_resume_pipeline_20260522.log`
- `logs/walksafe_tactile3_yolo26s_resume_pipeline_20260522.pid`
- `logs/walksafe_tactile3_yolo26s_pipeline_20260521.log`
- `logs/walksafe_tactile3_yolo26s_pipeline_20260521.pid`

## 4. 삭제 후보 등급

### 1) 상대적으로 안전 후보

승인 후에도 현재 학습이 끝났는지 먼저 확인해야 한다. 용량 효과는 대부분 작다.

| 후보 | 크기 | 판단 |
|---|---:|---|
| `data_sources/scripts/__pycache__` | 88K | Python bytecode cache. 재생성 가능 |
| `runs/validation/aihub513_vl2_vs2_tactile_smoke` | 55M | smoke validation 산출물로 보임. 결과가 문서화/불필요하면 후보 |
| `runs/detect/runs/detect/walksafe_tactile3_yolo26s_smoke_20260521` | 39M | YOLO26s smoke run. 현재 full/resume run과 다르지만, 승인 전 확인 필요 |
| `runs/detect/walksafe_kr_tactile_smoke` | 16M | 과거 smoke run으로 보임 |
| `runs/failure_sampling/walksafe_kr_v2_test_stream_smoke_20260519` | 52K | smoke sampling 산출물 |
| `runs/validation/aihub513_vl2_vs2_tactile_subset/labels/val.cache` | 966K | cache 파일. 재생성 가능하나 해당 validation 재사용 여부 확인 필요 |
| `datasets/walksafe_kr_v2/labels/test.cache` | 590K | cache 파일. 재생성 가능하나 dataset 재사용 여부 확인 필요 |

참고: `datasets/walksafe_kr_tactile_3class_20260521/labels/train.cache`(4.6M), `labels/val.cache`(1.3M)는 cache 성격이지만 현재 3-class dataset 하위에 있으므로, 현재 학습 중에는 건드리지 않는 편이 안전하다.

### 2) 주의 후보

삭제 시 재현성, 비교 실험, validation 기록, materialized dataset 재생성 가능성 확인이 필요하다. 사용자 승인 전 삭제 금지.

| 후보 | 크기 | 주의 이유 |
|---|---:|---|
| `datasets/walksafe_kr_v2` | 199G | 가장 큰 항목. materialized dataset일 가능성이 크지만 현재/향후 실험 참조 여부 확인 필요 |
| `datasets/walksafe_kr_v1` | 49G | 대형 dataset. v2와 중복/대체 가능 여부 확인 필요 |
| `runs/validation/aihub513_vl2_vs2_tactile_subset` | 18G | validation subset/results. 삭제 시 검증 재현성 영향 큼 |
| `runs/validation/aihub513_vl1_vs1_tactile_hard_negative_200_20260517` | 1.7G | hard-negative validation 데이터/결과 |
| `datasets/walksafe_kr_v3_privacy_sanitized_20260520` | 964M | privacy-sanitized 산출물. 원본 대비 재생성 가능성 확인 필요 |
| `datasets/walksafe_kr_v3_manual_review_20260520` | 367M | manual review overlay/crop. 감사/검토 근거일 수 있음 |
| `datasets/walksafe_replay_v2train_v3posheavy_4to1_20260521` | 130M | replay materialized dataset |
| `datasets/walksafe_replay_v2train_v3pos_x2_20260521` | 129M | replay materialized dataset |
| `datasets/walksafe_replay_v2train_v3balanced_2to1_20260521` | 129M | replay materialized dataset |
| `runs/detect/runs` | 149M | 중첩된 완료 run들. 실험 결과/weights 포함 가능 |
| `logs/*.log` 중 완료된 과거 로그 | 최대 11M | 용량 효과 작음. 실험 증거로 쓰일 수 있어 일괄 삭제 비추천 |

### 3) 삭제 비추천/보존

아래는 이번 정리 대상에서 제외하는 편이 안전하다.

| 보존 대상 | 크기/메모 | 이유 |
|---|---:|---|
| `runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521` | 232M | 현재 진행 중인 YOLO26s run/checkpoints |
| `runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/weights/*.pt` | 각 약 58M | `last.pt`, `best.pt`, `epoch*.pt` 등 현재/재개용 checkpoint |
| `logs/walksafe_tactile3_yolo26s_resume_pipeline_20260522.*` | 진행 중 | 현재 resume 로그/PID |
| `logs/walksafe_tactile3_yolo26s_pipeline_20260521.*` | 8.5M+ | 현재 YOLO26s 학습 계열 로그 |
| `datasets/walksafe_kr_tactile_3class_20260521` | 202M | 현재 3-class dataset |
| `data_sources/labels` | 88K | 원본/소스 라벨 성격 |
| `data_sources/manifests` | 36M | dataset 재현/감사 근거 manifest |

## 5. 정리 판단 요약

- 즉시 안전 후보만으로 확보 가능한 공간은 매우 작다. cache/smoke/log 일부를 정리해도 대략 수십 MB 수준이다.
- 실제로 큰 공간을 확보하려면 `datasets/walksafe_kr_v2`(199G), `datasets/walksafe_kr_v1`(49G), `runs/validation/aihub513_vl2_vs2_tactile_subset`(18G) 검토가 필요하지만, 모두 주의 후보라서 실험/데이터 소유자 확인 없이는 삭제 비추천이다.
- 현재 진행 중인 YOLO26s run, checkpoint, resume 로그, 현재 3-class dataset, source label/manifest는 삭제 비추천/보존으로 분류했다.
- 실제 삭제가 필요하면, 먼저 현재 YOLO26s 학습 종료 여부와 각 dataset/run의 재생성 가능성을 사용자에게 승인받은 뒤 별도 작업으로 수행하는 것이 안전하다.

## 6. 근거로 사용한 조회 명령

삭제 명령은 실행하지 않았다.

- `df -h .`
- `du -sh runs datasets logs data_sources`
- `du -h --max-depth=1/2/3 runs datasets logs data_sources`
- `find runs datasets logs data_sources -type f ...`로 대용량/최근 파일 확인
- `find runs datasets logs data_sources -type d \( -name '*cache*' -o -name '__pycache__' \) ...`로 cache 후보 확인
- `ps -eo pid,lstart,etime,stat,pcpu,pmem,args --sort=-pcpu`로 현재 YOLO26s 학습 프로세스 확인
- `stat`로 현재 run/checkpoint/log 갱신 시각 확인
