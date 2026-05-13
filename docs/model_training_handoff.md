# Model Training Handoff

작성 기준일: 2026-05-12

## 목적

모델 학습이 끝난 뒤 백엔드 `/detect` adapter 작업으로 바로 넘어가기 위해 필요한 결과물을 정리한다.

## 학습 완료 후 필요한 것

| 항목 | 예시 | 이유 |
| --- | --- | --- |
| 모델 파일 경로 | `runs/detect/train/weights/best.pt` | `MODEL_ARTIFACT_PATH` 후보 |
| export 파일 경로 | `best.onnx` | 서버 또는 브라우저 추론 연결 후보 |
| 사용한 data yaml | `datasets/walksafe_kr_v1/data.yaml` | 클래스 순서 확인 |
| 입력 이미지 크기 | `640` | 전처리 크기 고정 |
| 학습 명령 | `python model/train_yolo.py ...` | 재현성 확인 |
| 검증 metric | mAP, precision, recall | 모델 연결 우선순위 판단 |
| 클래스별 성능 | 4개 클래스별 metric | 낮은 성능 클래스 확인 |
| confidence 기준 | 예: `0.5` | 초기 알림 threshold 후보 |

## 반드시 확인할 클래스 순서

`datasets/walksafe_kr_v1/data.yaml`과 모델 출력 순서가 같아야 한다.

| class_id | class_name |
| ---: | --- |
| 0 | `damaged_tactile_block` |
| 1 | `parked_kickboard_bicycle` |
| 2 | `construction_obstacle` |
| 3 | `pothole` |

순서가 다르면 백엔드와 프론트의 `class_id`, `class_name` 매핑이 틀어진다.

## 백엔드에 넘길 최소 정보

학습 완료 후 이 정보만 있으면 다음 작업을 시작할 수 있다.

```text
model_path:
export_path:
data_yaml:
image_size:
confidence_threshold:
training_command:
metrics_summary:
known_limitations:
```

## 다음 구현 위치

| 파일 | 작업 |
| --- | --- |
| `backend/.env` | `MODEL_ARTIFACT_PATH` 설정 |
| `backend/app/detector.py` | 모델 로딩과 추론 adapter 구현 |
| `backend/app/schemas.py` | 필요 시 모델 버전 필드만 조정 |
| `docs/model_placeholder_systems.md` | 완료된 placeholder 정리 |

## 연결 전 주의

- fake 신고 데이터는 성능 평가에 섞지 않는다.
- 모델 출력 bbox는 원본 카메라 프레임 기준 정규화 좌표로 변환해야 한다.
- 서버 추론 결과의 `source`는 `server`로 둔다.
- 처음에는 threshold를 낮게 잡아 API 연결을 확인하고, 이후 성능 기준에 맞춰 조정한다.
