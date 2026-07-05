# WalkSafe 모델 카드 초안

- 작성일: 2026-05-22
- 문서 상태: 발표/보고서/PR용 초안
- 현재 상태: YOLO26s custom 학습 진행 중. 최종 평가/배포 판정 전.
- 주의: 이 문서는 GPU 추론/평가를 새로 실행하지 않고 로컬 문서와 `results.csv`를 CPU로 읽어 작성한 초안이다.

## 1. 모델 개요

| 항목 | 내용 |
| --- | --- |
| 주 모델 | YOLO26s custom tactile model |
| 보조 모델 | YOLO26n COCO pretrained model |
| 주 모델 상태 | 3-class custom 데이터로 학습 진행 중 |
| 보조 모델 상태 | 학습 없이 inference-only 후보 |
| 핵심 목적 | 사용자 발에 거슬릴 수 있는 지면/점자블럭 위험을 custom 모델로 인식하고, 사람/차량 등 큰 일반 객체는 COCO 모델로 보조 인식 |
| 현재 배포 상태 | 배포 전. Stage 1/2 평가와 threshold 확정 필요 |

## 2. 의도한 사용 목적

### 2.1 Custom YOLO26s

Custom YOLO26s는 보행 보조 시나리오에서 발밑 또는 전방 지면의 점자블럭 상태를 탐지하는 모델이다.

- 정상 점자블럭 존재 확인
- 파손된 점자블럭 객체 후보 탐지
- 점자블럭 내 파손 영역 후보 탐지
- 사용자 발에 걸리거나 보행을 방해할 수 있는 지면 위험 신호 제공

이 모델은 `정상 점자블럭`도 함께 탐지한다. 따라서 "깨진 점자블럭만 찾는 모델"로 해석하면 안 된다.

### 2.2 COCO YOLO26n

COCO YOLO26n pretrained 모델은 custom tactile 모델이 다루지 않는 큰 일반 객체를 보조로 탐지하는 inference-only 후보이다.

- 사람, 차량, 버스, 트럭, 자전거, 오토바이, 신호등, 벤치 등 보행 안전에 참고할 수 있는 COCO allowlist 객체 후보
- custom tactile 결과와 별도 source로 유지
- tactile damage 판정과 섞지 않음

## 3. Custom YOLO26s 학습 데이터 요약

참고한 로컬 데이터셋:

- `datasets/walksafe_kr_tactile_3class_20260521/BUILD_SUMMARY.md`
- `datasets/walksafe_kr_tactile_3class_20260521/data.yaml`

### 3.1 데이터셋 메타데이터

| 항목 | 확인된 내용 |
| --- | --- |
| 데이터셋 경로 | `datasets/walksafe_kr_tactile_3class_20260521` |
| 생성일 | 2026-05-21 (Asia/Seoul) |
| source image split | `datasets/walksafe_kr_v2` |
| split 정책 | 기존 source dataset의 train/val/test assignment 유지 |
| 이미지 materialization | relative symlink |
| class 수 | 3 |
| `data.yaml` split | `images/train`, `images/val`, `images/test` |

### 3.2 Split/box 수

아래 수치는 `BUILD_SUMMARY.md` 기준으로 확인된 값만 기재한다.

| split | images | class 0 boxes | class 1 boxes | class 2 boxes | total boxes |
| --- | ---: | ---: | ---: | ---: | ---: |
| train | 16,433 | 8,092 | 8,341 | 19,755 | 36,188 |
| val | 4,695 | 2,312 | 2,383 | 5,703 | 10,398 |
| test | 2,347 | 1,156 | 1,191 | 2,788 | 5,135 |
| total | 23,475 | 11,560 | 11,915 | 28,246 | 51,721 |

### 3.3 아직 모르는 것/확인 필요

| 항목 | 상태 |
| --- | --- |
| 최종 학습 완료 epoch | 학습 진행 중이라 확정 전 |
| 최종 val/test 성능 | 학습 완료 후 Stage 1/2 평가 필요 |
| 배포용 threshold | 평가 후 class별 조정 필요 |
| 외부 공개/배포 관점의 최종 개인정보·라이선스 판단 | 프로젝트 내부 기록과 별도 최종 확인 필요 |

## 4. Class 정의

| id | class name | 의미 | 앱/보고서 해석 |
| ---: | --- | --- | --- |
| 0 | `normal_tactile_block` | 정상 점자블럭 | 점자블럭 존재와 보행 유도 환경을 확인하는 신호. 단독으로 위험 경고로 취급하지 않음 |
| 1 | `damaged_tactile_block` | 파손된 점자블럭 객체 | 점자블럭 단위의 파손 후보. 사용자의 발밑/전방 위험 신호로 우선 검토 |
| 2 | `tactile_damage_area` | 점자블럭 내 파손 영역 | 파손 위치를 더 국소적으로 표시하는 후보. 작은 bbox 특성상 오탐/미탐과 IoU 변동을 별도 리뷰 필요 |

## 5. 개인정보/블러 정책 초안

이 섹션은 모델 카드용 운영 원칙 초안이며, 법적 확정 판단이 아니다.

| 항목 | 정책 초안 |
| --- | --- |
| 원본 보존 | 원본 이미지는 임의 수정하지 않고, 필요 시 sanitized derivative를 별도 생성 |
| EXIF/파일명 | 외부 공유 또는 배포 전 EXIF 제거와 파일명/location signal 제거 여부 확인 |
| 얼굴/차량번호 | 얼굴, 차량번호 등 민감정보가 확인되면 blur/redaction 후 사용 또는 제외 |
| no-blur 결정 | "민감정보 없음/블러 불필요" 판단은 근거 manifest와 리뷰 기록이 있을 때만 프로젝트 내부 결정으로 기록 |
| 외부 배포 | 법률/라이선스/개인정보 관점의 최종 배포 가능 여부는 별도 확인 필요 |

참고 맥락:

- 이전 privacy sanitize 기록에는 사용자가 `privacy_hold` 이미지에 민감정보가 없고 블러가 불필요하다고 수동 검수한 내용이 있다.
- 다만 이 문서에서는 그 내용을 **프로젝트 내부 결정/검수 기록**으로만 다룬다.
- 이 기록은 외부 배포 가능성, 법적 적합성, 전체 데이터셋 privacy pass를 자동으로 확정하지 않는다.

## 6. 현재 학습/성능 상태

### 6.1 학습 run 정보

아래 정보는 로컬 run의 `args.yaml`과 `results.csv`를 CPU로 읽어 확인했다. 새 GPU 추론/평가를 실행하지 않았다.

| 항목 | 값 |
| --- | --- |
| run 경로 | `runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521` |
| data | `datasets/walksafe_kr_tactile_3class_20260521/data.yaml` |
| epochs 설정 | 200 |
| image size | 960 |
| batch | 8 |
| optimizer | MuSGD |
| 현재 해석 | 학습 진행 중. 최종 지표 아님 |

### 6.2 `results.csv` 기준 중간 지표

읽은 파일:

- `runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/results.csv`
- 파일 수정 시각: 2026-05-22 01:56:13 +0900 확인
- 읽은 시점의 마지막 기록 epoch: 31

`results.csv`에서 `metrics/mAP50-95(B)`가 가장 높았던 행은 epoch 27이다. 이 값은 **현재까지 CSV에 기록된 중간 지표**이며, 최종 best epoch 또는 최종 test 성능으로 확정하면 안 된다.

| 기준 | epoch | precision(B) | recall(B) | mAP50(B) | mAP50-95(B) |
| --- | ---: | ---: | ---: | ---: | ---: |
| 현재 CSV 내 mAP50-95 최고 행 | 27 | 0.80684 | 0.78507 | 0.82397 | 0.72968 |
| 마지막 기록 행 | 31 | 0.78728 | 0.78359 | 0.81883 | 0.72735 |

주의:

- 위 지표는 학습 중간 결과이며 최종 성능이 아니다.
- test set 최종 평가는 아직 문서화되지 않았다.
- class별 AP/precision/recall은 이 문서 작성 시점에 확인하지 않았다.
- acceptance 판정은 학습 완료 후 Stage 1 정량 평가와 Stage 2 앱 관점 샘플 리뷰를 거쳐야 한다.

## 7. 런타임/통합 정책 초안

| 항목 | 정책 |
| --- | --- |
| 모델 분리 | custom YOLO26s와 COCO YOLO26n은 학습 목적과 class 의미가 다르므로 하나의 모델처럼 합치지 않음 |
| 결과 필드 | 각 detection에 `source_model`, `class_name`, `bbox`, `confidence`, `threshold_used` 유지 |
| NMS | 모델별 NMS를 먼저 수행하고, custom/COCO 간 cross-model NMS는 수행하지 않음 |
| COCO class | allowlist class만 사용자 알림 후보로 사용 |
| threshold | 현재 값은 초안/추정. YOLO26s 평가 완료 후 class별로 확정 |
| 기본 실행 방식 | 병렬/순차 여부는 latency, 서버 GPU 여유, 모바일/서버 구조 측정 후 확정 |

## 8. 평가/Acceptance 기준 연결

최종 모델 채택 여부는 `docs/execution/2026-05-22_model_acceptance_criteria.md`의 초안 기준을 따른다.

| 단계 | 확인할 것 | 현재 상태 |
| --- | --- | --- |
| Stage 1 정량 평가 | val/test mAP50, mAP50-95, precision, recall, class별 성능 | 학습 완료 후 필요 |
| Stage 2 앱 샘플 리뷰 | 오탐/미탐, 정상/파손 혼동, 파손 영역 bbox 위치 품질 | 학습 완료 후 필요 |
| Threshold tuning | val 기준 후보 비교 후 test에서 최종 1회 확인 | 미완료 |
| COCO smoke/integration | allowlist, source 분리, latency, custom 결과와 혼동 없음 | 미완료 |
| 최종 판정 | 합격/보류/재학습 및 근거 기록 | 미완료 |

## 9. 한계와 예상 실패 모드

| 유형 | 실패 가능성 |
| --- | --- |
| 원거리 객체 | 멀리 있는 점자블럭 또는 작은 파손은 bbox가 작아 미탐될 수 있음 |
| 작은 파손 | `tactile_damage_area`는 작은 bbox 특성상 IoU 변화와 label 일관성에 민감함 |
| 오염/얼룩 | 흙, 물자국, 페인트, 마모 흔적을 파손으로 오탐할 수 있음 |
| 그림자/조명 | 강한 그림자, 야간/역광, 젖은 바닥 반사가 파손 또는 블럭 패턴처럼 보일 수 있음 |
| 이물/바닥 패턴 | 낙엽, 쓰레기, 타일 패턴, 보도블럭 경계가 tactile class로 오탐될 수 있음 |
| 정상/파손 혼동 | 정상 점자블럭과 파손 점자블럭의 경계 사례에서 class가 바뀔 수 있음 |
| COCO 보조 모델 | COCO 일반 객체 결과를 tactile 위험으로 해석하면 잘못된 경고가 될 수 있음 |
| 배포 환경 | 모바일/서버 latency, 카메라 흔들림, 프레임 품질에 따라 실제 사용자 경험이 달라질 수 있음 |

## 10. 배포 전 체크리스트

| 영역 | 체크 항목 | 상태 |
| --- | --- | --- |
| 학습 완료 | YOLO26s 200 epoch 학습 완료 여부 확인 | 미완료 |
| 산출물 고정 | 최종 `best.pt`/run ID/학습 설정 기록 | 미완료 |
| 정량 평가 | val/test 전체 및 class별 mAP50, mAP50-95, precision, recall 기록 | 미완료 |
| 샘플 리뷰 | false positive/false negative 대표 사례와 원인 기록 | 미완료 |
| threshold | custom class별 confidence/IoU/NMS 확정 | 미완료 |
| COCO 정책 | allowlist, threshold, source 분리, cross-model NMS 금지 확인 | 미완료 |
| 개인정보 | EXIF, 얼굴, 차량번호, location signal, blur/no-blur 근거 재확인 | 확인 필요 |
| 라이선스/배포 | 데이터/가중치/보고서 외부 공유 가능 범위 확인 | 확인 필요 |
| 런타임 | 서버/모바일 latency와 실패 시 fallback 확인 | 미완료 |
| 사용자 안내 | 오탐/미탐 가능성과 보조 안내 성격을 UI/문서에 반영 | 미완료 |
| 롤백 | 이전 모델 또는 안전한 no-warning/fallback 모드 준비 | 미완료 |

## 11. 참고 문서

- `datasets/walksafe_kr_tactile_3class_20260521/BUILD_SUMMARY.md`
- `datasets/walksafe_kr_tactile_3class_20260521/data.yaml`
- `docs/execution/2026-05-22_model_two_model_runtime_plan.md`
- `docs/execution/2026-05-22_model_acceptance_criteria.md`
- `docs/execution/2026-05-22_coco_yolo26n_inference_policy.md`
- `docs/execution/2026-05-20_model_v3_privacy_sanitize_execution.md` 참고 맥락: privacy hold 수동 검수 기록. 외부 배포/법적 판단 확정 근거로 사용하지 않음.
