# Backend v2 API 계약

- 기준일: 2026-05-22 KST
- 구현 위치: `backend/app/api/`, `backend/app/services/`, `backend/app/schemas.py`
- 상태: v2 API contract 구현 완료, 실제 YOLO26s/COCO 추론 adapter는 아직 미연결

## 1. v1과 v2 분리 원칙

v1 API는 기존 4-class 계약을 유지한다.

| endpoint | 상태 | 용도 |
|---|---|---|
| `GET /detect/health` | 유지 | legacy server detector 상태 |
| `POST /detect` | 유지 | legacy v1 탐지 |
| `POST /reports` | 유지 | legacy v1 신고 저장 |
| `GET /reports`, `GET /reports/{id}`, `PATCH /reports/{id}/status` | 유지 | admin/report 운영 |

v2는 모델별 class-id 공간을 분리하기 위해 별도 schema를 쓴다. v2에서 `model_class_id`는 `model_key` 안에서만 의미가 있다.

## 2. `POST /detect/v2`

### 요청

```http
POST /detect/v2
Content-Type: multipart/form-data

context=<DetectContext JSON string>
image=<image file>
```

`context`는 v1 `DetectContext`를 재사용한다.

| field | type | note |
|---|---|---|
| `captured_at` | datetime or null | 없으면 서버 시각 사용 |
| `gps` | object or null | `latitude`, `longitude`, optional `accuracy_m` |
| `heading` | number or null | `0 <= heading < 360` |

현재 구현은 이미지 업로드 검증을 수행한 뒤 fake v2 detection을 반환한다. 실제 모델 추론은 아직 실행하지 않는다.

### 응답

```json
{
  "schema_version": "detect.v2",
  "detections": [
    {
      "schema_version": "detect.v2",
      "model_key": "custom_tactile",
      "source_model": "fake/custom-tactile-contract",
      "model_class_id": 2,
      "class_name": "tactile_damage_area",
      "category": "tactile",
      "confidence": 0.91,
      "bbox": { "x": 0.12, "y": 0.18, "width": 0.42, "height": 0.36 },
      "threshold_used": 0.25,
      "captured_at": "2026-05-22T00:00:00Z",
      "gps": null,
      "heading": null
    }
  ]
}
```

### model keys

| `model_key` | source model | class space |
|---|---|---|
| `custom_tactile` | YOLO26s custom 또는 fake contract | `normal_tactile_block`, `damaged_tactile_block`, `tactile_damage_area` |
| `coco_general` | YOLO26n COCO pretrained 또는 fake contract | allowlist 일반 객체 |

`/detect/v2` fake contract는 COCO allowlist 밖 class를 필터링한다. 모델 간 cross-model NMS는 하지 않는다.

## 3. `POST /reports/v2`

### 요청

```http
POST /reports/v2
Content-Type: multipart/form-data

metadata=<ReportV2Metadata JSON string>
image=<image file>
```

`metadata`는 `DetectV2Detection`에 아래 필드를 추가한 형태다.

| field | type | note |
|---|---|---|
| `trigger` | `auto` 또는 `voice` | 자동 신고인지 음성 요청 신고인지 |
| `auto_reported` | boolean | `trigger == "auto"`일 때 true |

### 허용 대상

| model_key | class_name | 저장 |
|---|---|---|
| `custom_tactile` | `tactile_damage_area` | 허용 |
| `custom_tactile` | `damaged_tactile_block` | 허용 |
| `custom_tactile` | `normal_tactile_block` | 거부 |
| `coco_general` | any | 거부 |

거부 시 422을 반환한다.

### 저장 정책

- 기존 `reports` 테이블을 재사용한다.
- `class_id`에는 v2 `model_class_id`를 저장한다.
- `class_name`에는 v2 `class_name`을 저장한다.
- `source`는 `source_model`이 `fake`로 시작하면 `fake`, 아니면 `server`로 저장한다.
- 원본 v2 payload는 report JSON metadata에 보존한다.
- 중복 후보는 class/location/time 기준으로 기존 정책을 재사용한다. GPS가 없으면 중복 후보는 빈 배열이다.

## 4. 안전 주의

- v1 `ClassId`/`ClassName` validator에 v2 class를 억지로 추가하지 않는다.
- v2 `model_class_id`를 전역 class id로 해석하지 않는다.
- 일반 객체를 `/reports/v2` 저장 대상으로 열지 않는다.
- 실제 YOLO adapter를 붙일 때도 `model_key`, `source_model`, `threshold_used`를 유지한다.
- 두 모델 결과를 class name 또는 bbox만 보고 단일 NMS로 제거하지 않는다.

## 5. 검증

현재 관련 테스트:

```bash
PATH="$PWD/.venv/bin:$PATH" PYTHONPATH=. python3 -m pytest \
  backend/tests/test_detect_v2.py \
  backend/tests/test_reports_v2.py \
  backend/tests/test_detect.py \
  backend/tests/test_reports.py -q
```
