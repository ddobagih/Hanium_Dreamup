# 탐지 이벤트 계약

> **문서 상태(2026-06-02): legacy v1.** 이 4-class `DetectionEvent` 계약은 PWA/v1 호환용이다. 현재 탐지 기준은 `docs/walksafe-v2/backend_api_contract.md`와 `docs/walksafe-v2/two_model_runtime_plan.md`다.


작성 기준일: 2026-05-12

## 목적

모델 개발과 PWA/백엔드 개발을 병렬로 진행하기 위해 탐지 결과의 최소 공통 형식을 고정한다. 1차 PWA는 fake detector를 사용하지만, 나중에 ONNX 또는 서버 추론 결과도 같은 형식으로 교체한다.

## 클래스

`datasets/walksafe_kr_v1/data.yaml`과 동일하게 유지한다.

| class_id | class_name |
| ---: | --- |
| 0 | `damaged_tactile_block` |
| 1 | `parked_kickboard_bicycle` |
| 2 | `construction_obstacle` |
| 3 | `pothole` |

## DetectionEvent

```json
{
  "class_id": 0,
  "class_name": "damaged_tactile_block",
  "confidence": 0.86,
  "bbox": {
    "x": 0.18,
    "y": 0.42,
    "width": 0.36,
    "height": 0.24
  },
  "captured_at": "2026-05-12T12:00:00.000Z",
  "source": "fake",
  "gps": {
    "latitude": 37.5665,
    "longitude": 126.978,
    "accuracy_m": 12.5
  },
  "heading": 180.0
}
```

## 필드 규칙

| 필드 | 타입 | 규칙 |
| --- | --- | --- |
| `class_id` | integer | `0`, `1`, `2`, `3` 중 하나 |
| `class_name` | string | `class_id`에 대응하는 클래스명과 일치 |
| `confidence` | number | `0` 이상 `1` 이하 |
| `bbox.x` | number | 카메라 프레임 기준 정규화 좌표, `0` 이상 `1` 이하 |
| `bbox.y` | number | 카메라 프레임 기준 정규화 좌표, `0` 이상 `1` 이하 |
| `bbox.width` | number | `0` 초과 `1` 이하 |
| `bbox.height` | number | `0` 초과 `1` 이하 |
| `captured_at` | ISO datetime | 탐지 시각 |
| `source` | string | `fake`, `onnx`, `server` 중 하나 |
| `gps` | object or null | 위치 권한이 없거나 실패하면 `null` |
| `gps.latitude` | number | WGS84 위도 |
| `gps.longitude` | number | WGS84 경도 |
| `gps.accuracy_m` | number or null | 브라우저 Geolocation 정확도 |
| `heading` | number or null | 기기 또는 이동 방향, `0` 이상 `360` 미만 |

## 신고 업로드

PWA는 신고 시 `multipart/form-data`로 보낸다.

| part | 내용 |
| --- | --- |
| `metadata` | `DetectionEvent` JSON 문자열 |
| `image` | 현재 카메라 프레임 캡처 이미지 |

백엔드는 `metadata`를 검증한 뒤 이미지 파일을 로컬 업로드 폴더에 저장하고, PostGIS 좌표 컬럼에는 `gps`가 있는 경우에만 위치를 저장한다.

업로드 이미지 정책:

| 항목 | 기본값 |
| --- | --- |
| 허용 MIME | `image/jpeg`, `image/png`, `image/webp` |
| 최대 크기 | `8388608` bytes |
| 설정 변수 | `ALLOWED_IMAGE_CONTENT_TYPES`, `MAX_UPLOAD_BYTES` |

서버는 원본 파일명을 저장 파일명으로 사용하지 않는다. 원본 파일명은 확장자와 MIME 불일치 여부 확인에만 사용하며, 저장 파일명은 서버가 생성한 UUID를 사용한다.

현재 백엔드는 multipart MIME, 파일명 확장자, 업로드 크기, 이미지 바이트의 최소 헤더를 확인한다. 전체 이미지 디코딩이나 악성 파일 정밀 검사는 아직 포함하지 않는다.

## 신고 응답 추가 필드

백엔드는 저장된 신고를 반환할 때 프론트에서 바로 표시할 수 있는 계산 필드를 추가한다. 이 값들은 DB에 별도 저장하지 않고 신고 데이터에서 계산한다.

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| `location_quality` | string | `missing`, `low`, `medium`, `high` 중 하나 |
| `review_flags` | string[] | 운영자 검토가 필요한 사유 목록 |
| `duplicate_report_ids` | string[] | `POST /reports` 시점에 발견된 중복 후보 신고 ID 목록 |

`location_quality` 기준:

| 값 | 조건 |
| --- | --- |
| `missing` | `gps`가 없음 |
| `low` | `accuracy_m`이 없거나 50m 초과 |
| `medium` | `accuracy_m`이 15m 초과 50m 이하 |
| `high` | `accuracy_m`이 15m 이하 |

`review_flags` 값:

| 값 | 조건 |
| --- | --- |
| `fake_source` | `source`가 `fake` |
| `low_confidence` | `confidence`가 0.7 미만 |
| `missing_location` | 위치 없음 |
| `low_location_accuracy` | 위치 품질이 `low` |
| `missing_heading` | `heading`이 없음 |

## 중복 신고 확인

프론트는 신고 버튼을 누르기 전 중복 가능성을 확인할 수 있다.

```http
GET /reports/duplicate-check?class_name=pothole&captured_at=2026-05-12T15:03:00Z&lat=37.50002&lng=127.00002&radius_m=25&minutes=10
```

기본 기준:

| 파라미터 | 기본값 | 의미 |
| --- | ---: | --- |
| `radius_m` | 25 | 같은 위치로 볼 반경 |
| `minutes` | 10 | `captured_at` 전후 시간 범위 |

응답:

```json
{
  "duplicate_report_ids": ["..."],
  "reports": []
}
```

`POST /reports` 응답에도 같은 기준으로 저장 직전 발견한 중복 후보 ID가 `duplicate_report_ids`에 포함된다.

## 서버 추론 API 자리

모델 파일과 추론 어댑터가 준비되기 전까지 서버 추론 API는 명시적으로 unavailable 상태를 반환한다. fake 탐지 결과를 서버 모델 결과처럼 반환하지 않는다.

### 상태 확인

```http
GET /detect/health
```

현재 응답:

```json
{
  "model_status": "unavailable",
  "model_version": null,
  "reason": "model_not_configured"
}
```

### 추론 요청

```http
POST /detect
Content-Type: multipart/form-data

context={"captured_at":"2026-05-12T12:00:00Z","gps":{"latitude":37.5665,"longitude":126.978,"accuracy_m":9.5},"heading":180}
image=<camera frame image>
```

`image`는 신고 업로드와 같은 MIME/크기 제한을 적용한다.

모델 연결 전 응답:

```json
{
  "detail": {
    "code": "model_unavailable",
    "reason": "model_not_configured",
    "message": "Server inference is not available until a model adapter is implemented."
  }
}
```

향후 모델 연결 후 `POST /detect`는 `DetectionEvent` 목록을 `detections` 배열로 반환한다. 이때 각 항목의 `source`는 `server`로 둔다.
