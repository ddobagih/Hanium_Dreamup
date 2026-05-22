# Two-model runtime plan

- 기준일: 2026-05-22 KST
- 구현 상태: CPU-only filtering/merge helper 구현 완료
- 미구현: 실제 YOLO26s custom / YOLO26n COCO inference adapter 연결

## 1. 모델 구성

| model_key | source_model | 역할 | class/allowlist |
|---|---|---|---|
| `custom_tactile` | `YOLO26s custom` | 점자블록/타일 상태와 손상 영역 | `normal_tactile_block`, `damaged_tactile_block`, `tactile_damage_area` |
| `coco_general` | `YOLO26n COCO pretrained` | 일반 객체 후보 | `person`, `car`, `bus`, `truck`, `bicycle`, `motorcycle`, `traffic light`, `bench` |

## 2. 현재 구현

- Helper: `model/two_model_runtime.py`
- Test: `model/test_two_model_runtime.py`
- Config: `configs/walksafe_two_model_runtime_20260522.yaml`

Helper 기능:

- runtime config validation
- custom tactile class filtering
- COCO allowlist filtering
- class별 threshold filtering
- detection payload normalization
- custom + COCO 결과 concatenate
- cross-model NMS 미적용

이 helper는 CPU-only이며 모델 파일을 로드하지 않는다.

## 3. 현재 threshold

현재 config의 모든 class threshold는 0.25다. 이 값은 서비스 최종 threshold가 아니라 v2 contract/demo 연결용 기본값이다.

| model_key | default threshold | 비고 |
|---|---:|---|
| `custom_tactile` | 0.25 | Stage1/외부 검수 후 조정 필요 |
| `coco_general` | 0.25 | smoke test와 경고 품질 확인 후 조정 필요 |

## 4. 결과 병합 원칙

- 두 모델은 class 의미가 다르므로 class id를 전역으로 합치지 않는다.
- 각 detection은 `model_key`, `source_model`, `model_class_id`, `class_name`, `category`, `threshold_used`를 유지한다.
- bbox가 겹쳐도 cross-model NMS를 하지 않는다.
- downstream은 `class_name`만 보지 말고 `model_key`와 함께 판단한다.

## 5. Backend 연결 상태

- `/detect/v2`는 현재 `backend/app/services/detect_v2.py`의 fake contract를 반환한다.
- fake contract도 helper를 통과하므로 allowlist/threshold/cross-model NMS 정책은 테스트된다.
- 실제 YOLO26s/COCO inference adapter는 아직 연결되지 않았다.

## 6. 다음 구현 후보

1. YOLO26s Stage1 candidate weight 경로와 COCO pretrained weight 경로를 runtime 설정으로 받는다.
2. 실제 inference adapter를 만들어 raw detection을 `Detection` payload로 정규화한다.
3. `/detect/v2`에서 fake contract와 real adapter를 설정으로 전환한다.
4. latency, threshold, allowlist, false positive/false negative를 별도 검증한다.
5. 일반 객체는 위험 평가 계층과 연결되기 전까지 경고로 바로 말하지 않는다.
