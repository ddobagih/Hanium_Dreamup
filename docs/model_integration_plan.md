# Model Integration Plan

> **문서 상태(2026-06-02): legacy v1.** `/detect` tactile v2 adapter 계획 기록이다. 현재 `/detect/v2`/unified 기준은 `docs/walksafe-v2/backend_model_integration_notes.md`다.


작성 기준일: 2026-05-12

## 2026-05-17~2026-05-18 구현 반영

- backend `/detect` `.pt` adapter와 PWA server detector mode는 구현되어 headless fixture smoke에서 `source: "server"` 신고 저장까지 확인됐다.
- 현재 backend ready 기준 산출물은 `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt`다. `best.onnx`는 test split 2,347장 기준 full metric equivalence를 통과했지만, PT 대비 CPU p95 latency가 느렸고 browser/ONNX Runtime Web latency 근거는 아직 없다.
- v2 모델은 class `0: damaged_tactile_block` baseline이다. 4-class 서비스 성능 근거로 쓰지 않는다.
- 이 문서는 남은 통합/운영 확인 항목을 추적하는 문서로 유지한다.

## 목적

현재 PWA와 백엔드는 fake detector와 서버 추론 adapter로 병렬 개발 중이다. 실제 운영/field 검증으로 확장할 때 이 문서 순서대로 기존 계약을 유지하면서 확인한다.

## 전제

- 데이터셋은 `datasets/walksafe_kr_v1/data.yaml`의 4개 클래스 순서를 유지한다.
- 탐지 이벤트 계약은 `docs/inference_contract.md`를 따른다.
- 서버 추론 결과의 `source`는 `server`로 둔다.
- fake detector는 모델 성능 검증에 사용하지 않는다.

## 연결 대상 파일

| 영역 | 파일 | 역할 |
| --- | --- | --- |
| 모델 학습 | `model/train_yolo.py` | YOLO baseline 학습 |
| 데이터 검증 | `model/validate_yolo_dataset.py` | YOLO 데이터셋 구조 확인 |
| 모델 상태 | `backend/app/detector.py` | `/detect/health` 응답 |
| 서버 추론 | `backend/app/detector.py` | `/detect` 이미지 추론 |
| API 스키마 | `backend/app/schemas.py` | `DetectResponse`, `ReportMetadata` |
| 임시 시스템 추적 | `docs/model_placeholder_systems.md` | 제거 또는 교체 대상 관리 |

## 1단계: 모델 산출물 위치 고정

학습 완료 후 모델 파일 경로를 `.env`에 지정한다.

```env
MODEL_ARTIFACT_PATH=/absolute/path/to/best.pt
MODEL_VERSION=walksafe-kr-tactile-v2-full-20260514-best-02a6be87
```

현재 `backend/app/detector.py`는 Ultralytics `.pt` 산출물을 로드해 `/detect` 결과를 반환할 수 있다. 모델 env가 없거나 파일이 없으면 `/detect/health`는 unavailable, `POST /detect`는 `503 model_unavailable`을 반환한다.

## 2단계: Detector Adapter 구현

`backend/app/detector.py`에서 다음 동작을 구현한다.

1. 앱 시작 후 모델 파일을 로드한다.
2. `detect_health()`가 로드 성공 시 `model_status: "ready"`를 반환한다.
3. `run_detection()`이 이미지 바이트와 `DetectContext`를 받아 `DetectResponse`를 반환한다.
4. 모든 탐지 결과는 `ReportMetadata` 검증을 통과해야 한다.

반환 예:

```json
{
  "model_status": "ready",
  "model_version": "walksafe-yolo-v1",
  "detections": [
    {
      "class_id": 0,
      "class_name": "damaged_tactile_block",
      "confidence": 0.88,
      "bbox": {
        "x": 0.2,
        "y": 0.35,
        "width": 0.4,
        "height": 0.22
      },
      "captured_at": "2026-05-12T12:00:00Z",
      "source": "server",
      "gps": null,
      "heading": null
    }
  ]
}
```

## 3단계: 입력 이미지 전처리 정리

서버 추론은 업로드 검증을 통과한 JPEG, PNG, WebP만 받는다. 어댑터 내부에서 필요한 크기 변환, RGB 변환, 정규화, NMS를 처리한다.

전처리 결과가 바뀌어도 API 응답의 `bbox`는 원본 카메라 프레임 기준 정규화 좌표로 유지한다.

## 4단계: fake 의존 제거

모델 연결 후 확인할 항목:

1. `/detect/health`가 `ready`를 반환한다.
2. `/detect`가 `source: "server"` 탐지 결과를 반환한다.
3. PWA 또는 외부 프론트엔드가 fake 없이 `/detect` 결과로 신고를 생성할 수 있다.
4. `docs/model_placeholder_systems.md`의 서버 추론 placeholder 항목을 완료 처리한다.
5. fake 데이터는 모델 성능 집계와 시연 근거에서 제외한다.

## 보류 항목

다음은 추가 field/runtime 근거가 생긴 뒤 결정한다.

- ONNX Runtime을 백엔드에 둘지, 브라우저 PWA에 둘지. 현재 ONNX metric equivalence는 통과했지만 로컬 CPU latency는 PT보다 느렸고 browser/ONNX Runtime Web latency는 미실행이다.
- confidence threshold 기본값
- 클래스별 TTS 우선순위
- 중복 신고 기준을 클래스별로 다르게 둘지 여부
