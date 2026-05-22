# Backend Two-Model API 계약 초안

- 작성일: 2026-05-22
- 담당: Worker A / Backend API 계약
- 범위: 문서 초안만 작성. backend 실제 코드 변경, GPU 추론/평가, 배포 결정은 하지 않는다.
- 로컬 확인 파일:
  - `backend/app/detector.py`
  - `backend/app/schemas.py`
  - `backend/app/main.py`
  - `backend/tests/test_detect.py`
  - `docs/inference_contract.md`

## 1. 현재 backend 계약 요약

### 1.1 기존 클래스 계약

`backend/app/schemas.py` 기준 현재 API의 클래스 공간은 아래 4개로 고정되어 있다.

| class_id | class_name |
| ---: | --- |
| 0 | `damaged_tactile_block` |
| 1 | `parked_kickboard_bicycle` |
| 2 | `construction_obstacle` |
| 3 | `pothole` |

- `CLASS_ORDER`는 `CLASS_NAMES`를 `class_id` 오름차순으로 정렬한 tuple이다.
- `ClassId`는 `Literal[0, 1, 2, 3]`이다.
- `ClassName`도 위 4개 문자열만 허용한다.
- `ReportMetadata.class_name_matches_id` validator가 `class_id`와 `class_name`의 기존 매핑 일치를 강제한다.
- `backend/app/config.py`의 `MODEL_CLASS_ORDER` 파서도 현재 `CLASS_ORDER`와 다른 값이면 오류를 낸다.

### 1.2 기존 `/detect` 요청/응답 계약

`backend/app/main.py` 기준 현재 `/detect`는 다음 형태다.

```http
POST /detect
Content-Type: multipart/form-data

context=<DetectContext JSON string>
image=<image file>
```

`context`는 `DetectContext`로 검증되며 필드는 다음과 같다.

| field | type | note |
| --- | --- | --- |
| `captured_at` | datetime or null | 없으면 서버가 현재 시각 사용 |
| `gps` | object or null | `latitude`, `longitude`, `accuracy_m` |
| `heading` | number or null | `0 <= heading < 360` |

성공 응답은 `DetectResponse`이다.

```json
{
  "model_status": "ready",
  "model_version": "walksafe-test",
  "detections": [
    {
      "class_id": 0,
      "class_name": "damaged_tactile_block",
      "confidence": 0.88,
      "bbox": {
        "x": 0.1,
        "y": 0.2,
        "width": 0.4,
        "height": 0.4
      },
      "captured_at": "2026-05-12T12:00:00Z",
      "source": "server",
      "gps": {
        "latitude": 37.5665,
        "longitude": 126.978,
        "accuracy_m": 9.5
      },
      "heading": 180.0
    }
  ]
}
```

- `detections[]` 항목은 `ReportMetadata`와 동일하다.
- `bbox`는 현재 이미지 기준 정규화 좌표(`x`, `y`, `width`, `height`)이다.
- `source`는 `fake`, `onnx`, `server` 중 하나이며 서버 추론 결과는 `server`를 사용한다.
- 모델 미설정/로드 실패 시 `/detect`는 `503`과 `detail.code = "model_unavailable"`을 반환한다.
- `/detect/health`는 `model_status`, `model_version`, `reason`, `model_artifact_path`, `model_class_order`, threshold 설정을 반환한다.

### 1.3 현재 detector adapter 동작

`backend/app/detector.py` 기준 현재 YOLO `.pt` adapter는 다음 전제를 가진다.

- 모델 task가 `detect`가 아니면 사용하지 않는다.
- 모델 class order가 backend `CLASS_ORDER`와 같아야 한다.
- 예외적으로 1-class 모델이 `damaged_tactile_block` 하나만 제공하면 기존 `class_id=0`으로 매핑한다.
- box의 model class id는 backend class id로 변환된 뒤 `ReportMetadata`로 반환된다.
- 따라서 새 3-class custom 모델이나 COCO 모델의 class order를 현재 `/detect`에 그대로 연결하면 class order mismatch 또는 `ReportMetadata` 검증 문제에 걸릴 가능성이 높다.

## 2. 새 two-model 체계

새 결과 체계는 두 모델의 class 공간을 섞지 않고, 결과마다 모델 출처를 유지하는 방향으로 검토한다.

### 2.1 custom tactile model

- 모델 역할: 점자블럭/지면 위험 관련 custom 탐지
- 후보 `model_key`: `custom_tactile`
- 후보 `source_model`: `custom_tactile_yolo26s`
- class 목록:
  - `normal_tactile_block`
  - `damaged_tactile_block`
  - `tactile_damage_area`

### 2.2 COCO model

- 모델 역할: 보행 안전 판단에 필요한 일반 객체 탐지
- 후보 `model_key`: `coco`
- 후보 `source_model`: `coco_yolo26n_pretrained`
- 1차 allowlist class:
  - `person`
  - `car`
  - `bus`
  - `truck`
  - `bicycle`
  - `motorcycle`
  - `traffic light`
  - `bench`

## 3. 충돌 지점

### 3.1 기존 `class_id` 0~3과 새 class 의미 차이

현재 backend의 `class_id`는 전역 4-class 의미로 사용된다.

| 기존 class_id | 기존 의미 | 새 체계에서의 충돌 가능성 |
| ---: | --- | --- |
| 0 | `damaged_tactile_block` | custom 모델 학습 순서가 `normal_tactile_block`부터라면 model class id 0은 정상 점자블럭일 수 있음 |
| 1 | `parked_kickboard_bicycle` | custom 모델에서는 `damaged_tactile_block`일 수 있고, COCO id와도 의미가 다름 |
| 2 | `construction_obstacle` | custom 모델에서는 `tactile_damage_area`일 수 있고, COCO id와도 의미가 다름 |
| 3 | `pothole` | 새 custom 3-class에는 직접 대응 class가 없음 |

결론: 새 two-model 결과의 raw class id를 기존 `class_id`에 그대로 넣으면 의미가 깨진다. `model_key` 또는 `source_model`으로 class id namespace를 분리해야 한다.

### 3.2 `ReportMetadata` validator 문제

현재 `ReportMetadata`는 다음을 강제한다.

- `class_id`는 `0|1|2|3`만 허용
- `class_name`은 기존 4개만 허용
- `CLASS_NAMES[class_id] == class_name` 필수

따라서 아래 새 class는 현재 `ReportMetadata`로 표현할 수 없다.

- `normal_tactile_block`
- `tactile_damage_area`
- COCO allowlist 전체(`person`, `car`, `bus`, `truck`, `bicycle`, `motorcycle`, `traffic light`, `bench`)

`damaged_tactile_block`도 이름은 기존에 존재하지만, 새 custom 모델의 raw class id가 기존 `0`과 다르면 현재 validator와 충돌한다.

### 3.3 DB `reports.class_id` 저장 문제

`backend/app/models.py` 기준 `reports.class_id`는 단순 `Integer`이고, `reports.class_name`은 `String(64)`이다. DB 컬럼 자체는 정수/문자열을 저장할 수 있지만, 현재 API 검증과 조회 필터는 기존 class 의미를 전제로 한다.

주의점:

- 기존 DB의 `class_id=0`은 `damaged_tactile_block` 의미로 저장되어 있다.
- 새 custom/COCO raw id를 같은 컬럼에 저장하면 과거 데이터와 새 데이터의 의미가 섞인다.
- `/reports`, `/reports/duplicate-check`의 `class_name` 필터 타입도 현재 `ClassName`이라 기존 4개만 허용한다.
- DB 구조 변경 여부는 아직 확정하지 않는다. 문서 단계에서는 `class_id` namespace 분리, 별도 v2 metadata 저장, legacy report adapter 중 하나를 선택지로 남긴다.

## 4. API 선택지

### 4.1 Option A: 기존 `/detect` 유지 + 내부 adapter 교체하지 않음

보수적 선택지이다.

- 기존 `/detect`와 `/detect/health` 계약을 그대로 둔다.
- 현재 adapter의 class order 검증과 `ReportMetadata` 응답을 유지한다.
- two-model 결과는 backend API에 바로 연결하지 않는다.
- 장점:
  - 기존 프론트/테스트/신고 저장 계약을 깨지 않는다.
  - `class_id` 의미 충돌을 피한다.
- 단점:
  - 새 custom/COCO 결과를 backend API로 제공하지 못한다.
  - two-model runtime과 backend 계약 사이의 통합 작업이 뒤로 밀린다.

### 4.2 Option B: 새 `/detect/v2` 추가

추천 초안이다. 단, 최종 결정은 필요하다.

- 기존 `/detect`는 그대로 둔다.
- 새 two-model 응답만 `/detect/v2`에서 제공한다.
- v2 detection은 `class_id`를 기존 전역 id로 해석하지 않고, `model_key`/`source_model`을 포함한다.
- 필요하면 raw model id는 `model_class_id`처럼 이름을 바꿔서 넣고, 기존 `class_id`와 구분한다.
- 장점:
  - 기존 `/detect`와 `/reports` 호환성을 보존한다.
  - 새 class 공간과 COCO allowlist를 명확히 표현할 수 있다.
  - v2 테스트를 기존 테스트와 분리할 수 있다.
- 단점:
  - 프론트가 v1/v2 중 어떤 endpoint를 사용할지 선택해야 한다.
  - v2 결과를 신고 저장으로 연결할 때 별도 mapping 정책이 필요하다.

### 4.3 Option C: 기존 `/detect` 응답 확장

호환성 리스크가 큰 선택지이다.

- 기존 `/detect`의 `detections[]`에 `source_model`, `model_key`, `category` 등을 추가한다.
- 기존 `ReportMetadata` 타입과 validator를 바꿔야 할 가능성이 높다.
- `class_id`/`class_name` 허용 범위를 넓히면 `/reports` 저장, duplicate check, 기존 프론트 표시 로직까지 영향을 받을 수 있다.
- Pydantic response model 변경에 따라 기존 클라이언트가 예상하지 못한 필드를 받거나, 기존 필드 의미가 달라질 수 있다.

결론: Option C는 한 endpoint로 단순해 보이지만 기존 `/detect`를 깨는 변경이 될 가능성이 있어 현재 단계에서는 신중해야 한다.

## 5. 권장 `/detect/v2` 응답 JSON 초안

아래는 계약 초안이며 최종 확정이 아니다. 핵심 원칙은 `source_model`, `model_key`, `category`, `class_name`, `confidence`, `bbox`를 detection마다 포함하는 것이다.

```json
{
  "schema_version": "detect.v2.draft",
  "model_status": "ready",
  "models": [
    {
      "model_key": "custom_tactile",
      "source_model": "custom_tactile_yolo26s",
      "model_version": "custom-yolo26s-training-result"
    },
    {
      "model_key": "coco",
      "source_model": "coco_yolo26n_pretrained",
      "model_version": "coco-pretrained"
    }
  ],
  "detections": [
    {
      "source_model": "custom_tactile_yolo26s",
      "model_key": "custom_tactile",
      "category": "tactile_block_state",
      "class_name": "normal_tactile_block",
      "confidence": 0.82,
      "bbox": {
        "x": 0.18,
        "y": 0.42,
        "width": 0.36,
        "height": 0.24
      }
    },
    {
      "source_model": "custom_tactile_yolo26s",
      "model_key": "custom_tactile",
      "category": "tactile_damage",
      "class_name": "tactile_damage_area",
      "confidence": 0.64,
      "bbox": {
        "x": 0.32,
        "y": 0.51,
        "width": 0.12,
        "height": 0.09
      }
    },
    {
      "source_model": "coco_yolo26n_pretrained",
      "model_key": "coco",
      "category": "general_object",
      "class_name": "person",
      "confidence": 0.77,
      "bbox": {
        "x": 0.61,
        "y": 0.18,
        "width": 0.2,
        "height": 0.67
      }
    }
  ]
}
```

필드 초안:

| field | 위치 | 의미 |
| --- | --- | --- |
| `source_model` | detection | 실제 결과를 낸 모델 출처. 예: `custom_tactile_yolo26s`, `coco_yolo26n_pretrained` |
| `model_key` | detection | 클라이언트 분기용 안정 키. 예: `custom_tactile`, `coco` |
| `category` | detection | UI/알림 정책용 상위 분류. 예: `tactile_block_state`, `tactile_damage`, `general_object` |
| `class_name` | detection | 모델별 class 이름. 기존 `ClassName` Literal에 묶지 않음 |
| `confidence` | detection | `0` 이상 `1` 이하 |
| `bbox` | detection | 기존 `/detect`와 같은 정규화 좌표 형식 |

검토 필요 항목:

- v2에 `captured_at`, `gps`, `heading`을 top-level로 둘지 detection마다 복사할지 결정 필요.
- raw model class id가 필요하면 `class_id` 대신 `model_class_id`를 사용해 기존 class id와 구분하는 편이 안전하다.
- v2 결과를 `POST /reports`로 저장할지, 별도 `/reports/v2` 또는 legacy mapping을 둘지는 아직 결정하지 않는다.

## 6. 마이그레이션 순서

1. 문서
   - 본 계약 초안으로 기존 계약, 충돌 지점, 선택지를 공유한다.
2. 테스트
   - 기존 `/detect` 테스트가 깨지지 않는지 먼저 고정한다.
   - `/detect/v2`를 선택한다면 v2 schema test를 새로 만든다.
   - GPU 추론 없이 fake two-model adapter 또는 fixture로 contract test부터 작성한다.
3. v2 endpoint
   - 최종 선택이 Option B라면 `/detect/v2`를 추가한다.
   - 기존 `/detect` 내부 adapter는 임의로 교체하지 않는다.
4. 프론트 연동
   - 프론트가 v1/v2 endpoint와 detection field를 명시적으로 선택하도록 한다.
   - UI/알림 정책은 `model_key`, `category`, `class_name` 기준으로 분기한다.
5. 기존 `/detect` deprecation 여부 결정
   - 기존 프론트/신고 저장/운영 데이터 영향 확인 후 별도로 결정한다.
   - 현 단계에서는 기존 `/detect`를 깨는 변경이나 deprecation을 확정하지 않는다.

## 7. 현재 단계에서 확정하지 않는 것

- 기존 `/detect`를 깨는 변경 여부
- DB `reports.class_id` 구조 변경 여부
- v2 결과를 기존 `/reports`에 바로 저장할지 여부
- 서버/모바일/GPU 배포 방식
- YOLO26s custom 평가 결과와 threshold
- COCO allowlist threshold 최종값
