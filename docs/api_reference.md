# Backend API Reference

작성 기준일: 2026-06-02 KST

## 범위

이 문서는 프론트엔드와 백엔드가 공유해야 하는 FastAPI 계약만 정리한다. v1 legacy 탐지 이벤트 필드의 상세 규칙은 `docs/inference_contract.md`를 참고하되, 현재 v2/unified 기준은 이 문서와 `docs/walksafe-v2/backend_api_contract.md`를 우선한다.

기본 서버 주소:

```text
http://127.0.0.1:8000
```

## 공통 규칙

- 응답은 JSON이다.
- 신고 생성과 서버 추론 요청은 `multipart/form-data`를 사용한다.
- 날짜/시간은 ISO 8601 문자열을 사용한다.
- 위도/경도는 WGS84 좌표를 사용한다.
- 이미지 원본 파일명은 저장 파일명으로 사용하지 않는다. 서버는 UUID 기반 파일명으로 저장한다.

## 이미지 업로드 정책

| 항목 | 현재 값 |
| --- | --- |
| 허용 MIME | `image/jpeg`, `image/png`, `image/webp` |
| 기본 최대 크기 | `8388608` bytes |
| 저장 위치 | `UPLOAD_DIR` |
| 저장 파일명 | `{report_id}.{jpg|png|webp}` |

검증 순서:

1. multipart `Content-Type`이 허용 목록에 있는지 확인한다.
2. 파일명 확장자가 있으면 MIME과 충돌하지 않는지 확인한다.
3. 최대 크기를 넘지 않는지 확인한다.
4. 파일 바이트의 최소 헤더가 MIME과 맞는지 확인한다.

오류 코드:

| HTTP | `detail.code` | 의미 |
| ---: | --- | --- |
| 400 | `unsupported_image_type` | 허용되지 않은 MIME |
| 400 | `image_extension_mismatch` | 파일명 확장자와 MIME 불일치 |
| 400 | `empty_image` | 빈 파일 |
| 400 | `image_content_mismatch` | 이미지 헤더와 MIME 불일치 |
| 413 | `upload_too_large` | 최대 업로드 크기 초과 |

## GET /health

백엔드 프로세스 상태 확인.

응답:

```json
{
  "status": "ok"
}
```

## GET /detect/health

v1 서버 추론 모델 상태 확인. `MODEL_ARTIFACT_PATH`에 호환되는 `.pt`가 설정되어 있으면 `ready`, 없거나 로드할 수 없으면 `unavailable`을 반환한다.

응답 예:

```json
{
  "model_status": "unavailable",
  "model_version": null,
  "reason": "model_not_configured"
}
```

## POST /detect

v1 서버 추론 API. `MODEL_ARTIFACT_PATH`가 준비된 개발 환경에서는 YOLO `.pt` adapter 결과를 v1 `DetectionEvent`로 반환한다. 모델이 설정되지 않았거나 로드할 수 없으면 이미지를 검증한 뒤 `503 model_unavailable`을 반환한다.

요청:

| part | 내용 |
| --- | --- |
| `context` | `DetectContext` JSON 문자열 |
| `image` | 카메라 프레임 이미지 |

`context` 예:

```json
{
  "captured_at": "2026-05-12T12:00:00Z",
  "gps": {
    "latitude": 37.5665,
    "longitude": 126.978,
    "accuracy_m": 9.5
  },
  "heading": 180
}
```

모델 미설정 오류 응답 예:

```json
{
  "detail": {
    "code": "model_unavailable",
    "reason": "model_not_configured"
  }
}
```


## GET /detect/v2/health

v2 탐지 provider 설정 상태를 확인한다. `DETECT_V2_MODE` 기본값은 `fake`이며, `yolo` 또는 `real`이면 unified model path를 우선 확인하고 없으면 legacy custom tactile/COCO model path pair를 확인한다. 이 health API는 Ultralytics/Pillow/model weight를 실제로 로드하지 않는다.

응답 예:

```json
{
  "schema_version": "detect.v2",
  "mode": "fake",
  "status": "ready",
  "reason": null,
  "runtime_primary_model": "unified_walksafe",
  "runtime_fallback_model": "legacy_two_model",
  "configured_runtime": null,
  "custom_tactile_model_path": null,
  "coco_model_path": null,
  "unified_model_path": null,
  "runtime_config_path": null
}
```

`yolo`/`real` mode에서 model path가 없으면 `status: "unavailable"`, `reason: "detect_v2_model_not_configured:..."`가 될 수 있다.

## POST /detect/v2

v2 서버 탐지 API. 기본은 fake provider이며, `DETECT_V2_MODE=yolo` 또는 `real`에서 model path가 설정되어 있으면 `LazyYoloDetectV2Runtime`/`YoloDetectV2Provider`를 사용한다. `DETECT_V2_UNIFIED_MODEL_PATH`가 있으면 `unified_walksafe` 단일 모델을 우선 사용하고, 없으면 legacy custom tactile + COCO pair로 fallback한다. 실제 Ultralytics/Pillow/model load는 첫 실제 `/detect/v2` 요청 시점까지 지연된다.

요청:

| part | 내용 |
| --- | --- |
| `context` | `DetectContext` JSON 문자열 |
| `image` | 카메라 프레임 이미지 |

응답 예:

```json
{
  "schema_version": "detect.v2",
  "detections": [
    {
      "schema_version": "detect.v2",
      "model_key": "unified_walksafe",
      "source_model": "walksafe_unified_yolo26n",
      "model_class_id": 8,
      "class_name": "damaged_tactile_block",
      "category": "tactile",
      "confidence": 0.91,
      "bbox": {"x": 0.2, "y": 0.35, "width": 0.4, "height": 0.22},
      "threshold_used": 0.6,
      "captured_at": "2026-05-12T12:00:00Z",
      "gps": null,
      "heading": null
    }
  ]
}
```

주요 필드:

| 필드 | 의미 |
| --- | --- |
| `model_key` | primary `unified_walksafe`; fallback/legacy에서는 `custom_tactile` 또는 `coco_general` |
| `model_class_id` | 해당 `model_key` 안에서만 의미 있는 class id. `unified_walksafe`는 13-class order 기준 |
| `class_name` | v2 class name 문자열 |
| `category` | `tactile` 또는 `general_obstacle` 계열 |
| `threshold_used` | runtime filter에 사용된 threshold |
| `distance_m` | 선택 거리값. `0 <= distance_m <= 50`일 때만 유효 |
| `distance_source` | `sensor_depth`, `manual_fixture`, `model_estimate`, `unknown` |
| `distance_confidence` | 거리값 신뢰도 `0..1`. 프론트는 source/confidence가 없으면 보폭 문구를 제외 |

`yolo`/`real` mode에서 provider를 만들 수 없으면 `503 detect_v2_unavailable`을 반환한다.

## GET /navigation/destinations/search/health

목적지 검색 provider 상태를 확인한다. 실제 TMAP 호출 없이 key 설정 또는 mock mode만 확인한다.

```json
{
  "provider": "tmap_poi",
  "mode": "mock",
  "status": "ready",
  "reason": null,
  "poi_search_url": null
}
```

## GET /navigation/destinations/search

장소 이름을 목적지 후보로 정규화한다. `TMAP_POI_PROVIDER=mock`에서는 로컬 fixture/alias만 사용해 외부 호출을 하지 않는다.

Query:

| query | 의미 |
| --- | --- |
| `query` | 장소명. “서울역으로 안내해줘” 같은 조사/명령 suffix는 서버에서 보수적으로 정규화 |
| `limit` | 1~10, 기본 5 |
| `origin_lat`, `origin_lng` | 선택. mock/local provider는 후보 거리 정렬에 사용 |

응답 후보:

```json
{
  "schema_version": "walksafe.destination_search.v1",
  "provider": "tmap_poi",
  "query": "서울역",
  "results": [
    {
      "id": "mock-seoul-station",
      "name": "서울역",
      "point": {"latitude": 37.5546788, "longitude": 126.9706069, "name": "서울역"},
      "address": "서울 중구 봉래동2가",
      "road_address": "서울 중구 한강대로 405",
      "category": "교통",
      "result_type": "alias",
      "distance_m": 1250
    }
  ]
}
```

## GET /navigation/walking/health

현재 보행 route provider 설정 상태를 확인한다. 실제 provider API를 호출하지 않고 key 설정 여부만 확인한다. 기본 provider는 `tmap_pedestrian`이다.

응답 예:

```json
{
  "provider": "tmap_pedestrian",
  "status": "unavailable",
  "reason": "tmap_app_key_missing",
  "walking_directions_url": "https://apis.openapi.sk.com/tmap/routes/pedestrian"
}
```

## POST /navigation/walking

TMAP 보행 경로를 WalkSafe 앱 전용 schema로 정규화한다. provider key는 백엔드 env에만 두고, 프론트는 이 endpoint만 호출한다. `WALKING_ROUTE_PROVIDER=kakao_mobility`로 설정하면 Kakao fallback provider를 사용할 수 있다.

요청:

```json
{
  "origin": {"latitude": 37.39472714688412, "longitude": 127.11015314141542},
  "destination": {"latitude": 37.401937080111644, "longitude": 127.10824367964793, "name": "테스트 목적지"},
  "waypoints": [],
  "priority": "STAIR_AVOID",
  "radius_m": 5000
}
```

`priority`는 `RECOMMEND`, `MAIN_STREET`, `DISTANCE`, `STAIR_AVOID`를 지원한다. TMAP 기본 요청은 시각장애인 보행 안전을 우선해 `STAIR_AVOID`를 사용한다. 응답의 `distance_m`/`duration_s`는 앱 내부 안내 계산 입력이며, 사용자 TTS는 거리(m) 단독 표현보다 시간/보폭 기반 표현을 우선한다. 예: “10초 뒤 좌회전 준비”, “약 15보 앞”, “지금 좌회전하세요”. 보폭 기본값은 개인화 전 임시값이고, GPS/센서 기반 속도 추정에는 오차가 있으므로 보폭 안내는 근사 표현으로 다룬다.

응답:

```json
{
  "schema_version": "walksafe.walking_route.v1",
  "provider": "tmap_pedestrian",
  "provider_route_id": null,
  "priority": "STAIR_AVOID",
  "summary": {"distance_m": 1281, "duration_s": 1220},
  "polyline": [{"latitude": 37.39472714688412, "longitude": 127.11015314141542}],
  "steps": [
    {
      "index": 0,
      "distance_m": 20,
      "duration_s": 18,
      "instruction": "테스트길, 20m",
      "road_name": "테스트길",
      "turn_type": null,
      "facility_type": 0,
      "points": [{"latitude": 37.39472714688412, "longitude": 127.11015314141542}]
    }
  ],
  "guide_points": [
    {
      "index": 0,
      "point": {"latitude": 37.398, "longitude": 127.109},
      "instruction": "좌회전",
      "turn_type": 13,
      "point_type": "GP",
      "distance_from_start_m": 20,
      "remaining_distance_m": 1261,
      "bearing_deg": 12.4
    }
  ],
  "provider_result_code": 0,
  "provider_result_message": "OK"
}
```

`guide_points`는 TMAP Point feature의 안내 지점이다. `bearing_deg`는 route polyline의 다음 segment 방향에서 계산한 0 이상 360 미만의 보조 heading이다. 프론트는 `distance_from_start_m`/`remaining_distance_m`와 현재 진행 거리, 보행 속도/보폭 추정값을 비교해 “10초 뒤 좌회전 준비”, “약 15보 앞”, “지금 좌회전하세요” 같은 안내를 만들 수 있다. Kakao fallback provider는 현재 `guide_points: []`를 반환한다.

오류:

| HTTP | `detail.code` | 의미 |
| ---: | --- | --- |
| 503 | `tmap_app_key_missing` | 백엔드에 TMAP appKey 없음 |
| 502 | `tmap_invalid_api_key` | TMAP appKey가 잘못됐거나 보행자 경로안내 상품 권한 없음 |
| 504 | `tmap_timeout` | TMAP provider timeout |
| 502 | `tmap_provider_error` | TMAP provider HTTP 오류 |
| 503 | `kakao_api_key_missing` | Kakao fallback 사용 시 REST API key 없음 |
| 504 | `kakao_timeout` | Kakao fallback provider timeout |
| 502 | `kakao_provider_error` | Kakao fallback provider HTTP 오류 |
| 422 | `route_unavailable` | provider가 경로 없음/실패 결과 반환 |
| 422 | `route_priority_unsupported` | fallback provider가 요청 priority를 지원하지 않음 |

## POST /reports

탐지 이벤트와 이미지를 신고로 저장한다.

요청:

| part | 내용 |
| --- | --- |
| `metadata` | `DetectionEvent` JSON 문자열 |
| `image` | 신고 이미지 |

`metadata` 예:

```json
{
  "class_id": 0,
  "class_name": "damaged_tactile_block",
  "confidence": 0.91,
  "bbox": {
    "x": 0.2,
    "y": 0.35,
    "width": 0.4,
    "height": 0.22
  },
  "captured_at": "2026-05-12T12:00:00Z",
  "source": "fake",
  "gps": {
    "latitude": 37.5665,
    "longitude": 126.978,
    "accuracy_m": 9.5
  },
  "heading": 181
}
```

응답은 `ReportResponse`이며 주요 필드는 다음과 같다.

| 필드 | 의미 |
| --- | --- |
| `id` | 신고 UUID |
| `status` | `new`, `reviewed`, `resolved` |
| `image_path` | 정적 이미지 경로 |
| `location_quality` | `missing`, `low`, `medium`, `high` |
| `review_flags` | 검토 필요 사유 목록 |
| `duplicate_report_ids` | 저장 직전 발견한 중복 후보 ID |


## POST /reports/v2

v2 탐지 metadata와 이미지를 신고로 저장한다. 저장 대상은 `unified_walksafe` 또는 legacy `custom_tactile`의 손상 점자블록(`damaged_tactile_block`)이다. 손상 점자블록은 자동 신고만 수행할 때 사용자 TTS 기본값이 없다. 손상 영역 bbox(`tactile_damage_area`)와 일반 객체(`coco_general`, unified 일반 객체 등)는 시설물 신고 대상이 아니라 보조 정보/사용자 위험 경고 입력이므로 저장을 거부한다. 사용자 위험 경고는 장애물, 보행자에게 접근하는 객체, 경로 차단 중심이며, `낙상 위험`은 현재 MVP에서 별도 경고 카테고리로 쓰지 않는다.

요청 metadata는 `/detect/v2` detection 필드에 다음 필드를 추가한다.

| 필드 | 의미 |
| --- | --- |
| `trigger` | `auto` 또는 `voice` |
| `auto_reported` | 자동 신고 여부 boolean |
| `source` | optional `android`. Android native upload source를 backend/admin/export에서 분리 |
| `coordinate_gate_status` | optional string. Android bbox/depth gate 상태 |

현재 허용 저장 대상:

| model_key | class_name |
| --- | --- |
| `unified_walksafe` | `damaged_tactile_block` |
| `custom_tactile` | `damaged_tactile_block` |

그 외 v2 metadata는 `422`로 거부된다. v2 손상 점자블록 신고는 GPS가 있어야 저장된다.

## GET /reports

신고 목록을 최신순으로 조회한다.

쿼리:

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `limit` | 아니오 | `1..100`, 기본 `25` |
| `status` | 아니오 | `new`, `reviewed`, `resolved` |
| `class_name` | 아니오 | v1 4개 탐지 클래스 또는 v2 class name 문자열 |
| `source` | 아니오 | `fake`, `onnx`, `server`, `android` |
| `model_key` | 아니오 | v2 metadata `model_key` |
| `trigger` | 아니오 | v2 metadata `trigger` (`auto`, `voice`) |
| `auto_reported` | 아니오 | v2 metadata `auto_reported` boolean |
| `created_from` | 아니오 | 생성 시각 시작 |
| `created_to` | 아니오 | 생성 시각 끝 |
| `lat` | 반경 검색 시 필요 | 위도 |
| `lng` | 반경 검색 시 필요 | 경도 |
| `radius_m` | 반경 검색 시 필요 | 검색 반경 미터 |

`lat`, `lng`, `radius_m`는 셋을 함께 보내야 한다.


## GET /reports/summary

`GET /reports`와 같은 필터 조건으로 전체 신고 수, 상태/source count, 위치 있음/없음, grid cluster 요약을 반환한다. Admin 목록 limit와 별개로 현재 필터 전체를 요약한다.

주요 query는 `GET /reports`와 동일하며 `source=android`, `model_key`, `trigger`, `auto_reported`, `demo_filter`를 함께 사용할 수 있다.


## GET /reports/export

신고 목록을 내보낸다. 기본은 CSV이고 `format=json`, `format=geojson`을 지원한다. 필터는 `GET /reports`와 같은 v2 metadata 필터(`model_key`, `trigger`, `auto_reported`) 및 위치/시간 필터를 재사용한다. GeoJSON은 위치가 있는 신고를 Point Feature로 내보내며, 위치가 없는 신고는 `geometry: null`로 둔다.

쿼리:

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `format` | 아니오 | `csv` 기본, `json`, `geojson` 지원 |
| `status` | 아니오 | `new`, `reviewed`, `resolved` |
| `class_name` | 아니오 | v1/v2 class name 문자열 |
| `source` | 아니오 | `fake`, `onnx`, `server`, `android` |
| `model_key` | 아니오 | v2 metadata `model_key` |
| `trigger` | 아니오 | v2 metadata `trigger` |
| `auto_reported` | 아니오 | v2 metadata `auto_reported` boolean |
| `created_from`, `created_to` | 아니오 | 생성 시각 범위 |
| `lat`, `lng`, `radius_m` | 반경 검색 시 필요 | 셋을 함께 보내야 함 |
| `redacted` | 아니오 | `true`면 좌표 rounding, `image_path` 제거 |
| `bom` | 아니오 | CSV UTF-8 BOM |
| `manifest` | 아니오 | JSON export manifest wrapper |
| `aggregate` | 아니오 | `format=geojson`에서 `grid` 지원 |

Export 응답은 `Cache-Control: no-store`, `X-WalkSafe-Demo-Filter`, demo-aware `Content-Disposition` filename을 포함한다.

CSV 컬럼:

```text
id,status,class_name,confidence,source,latitude,longitude,accuracy_m,heading,bbox_x,bbox_y,bbox_width,bbox_height,captured_at,created_at,model_key,source_model,threshold_used,trigger,auto_reported,distance_m,trace_id,payload_sha256,image_sha256,data_origin,runtime_mode,performance_excluded,performance_exclusion_reason,location_quality,review_flags,status_history_count,review_note,resolution_reason,image_path
```

## GET /reports/duplicate-check

중복 신고 후보를 조회한다.

쿼리:

| 이름 | 기본값 | 설명 |
| --- | ---: | --- |
| `class_name` | 필수 | 탐지 클래스 |
| `captured_at` | 필수 | 탐지 시각 |
| `lat` | 필수 | 위도 |
| `lng` | 필수 | 경도 |
| `radius_m` | `10` | 같은 위치로 볼 반경 |
| `minutes` | `1` | `captured_at` 전후 시간 범위 |

응답:

```json
{
  "duplicate_report_ids": ["..."],
  "reports": []
}
```

## GET /reports/{report_id}

신고 상세 조회.

오류:

| HTTP | 의미 |
| ---: | --- |
| 404 | 신고 없음 |

## PATCH /reports/{report_id}/status

신고 상태 변경.

요청:

```json
{
  "status": "reviewed"
}
```

허용 상태:

```text
new, reviewed, resolved
```
