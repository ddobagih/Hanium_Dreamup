# Backend v2 API 계약

- 기준일: 2026-07-01 KST
- 구현 위치: `backend/app/api/`, `backend/app/services/`, `backend/app/schemas.py`
- 상태: v2 API contract 구현 완료, YOLO provider는 lazy-load 구조까지 연결됨. backend 운영 threshold와 Android TFLite threshold는 분리해 관리

## 2026-07-01 현재 우선순위 보정

- 주 사용자 앱 경로는 Android native ARCore/TFLite APK다.
- 이 문서는 backend/Web/PWA/voice/정책 기준으로 유지하되, Android Device evidence를 대체하지 않는다.
- Android report upload, TTS/haptic, navigation 코드 경로는 일부 연결되어 있다. 이 문서는 backend 계약을 설명하며, Android Device evidence를 대체하지 않는다.

## 1. v1과 v2 분리 원칙

v1 API는 기존 4-class 계약을 유지한다.

| endpoint | 상태 | 용도 |
|---|---|---|
| `GET /detect/health` | 유지 | legacy server detector 상태 |
| `POST /detect` | 유지 | legacy v1 탐지 |
| `GET /detect/v2/health` | 추가 | v2 provider mode/model path readiness 확인 |
| `GET /navigation/walking/health`, `POST /navigation/walking` | 추가 | TMAP 보행 경로 proxy와 앱 전용 route schema. Kakao fallback 유지 |
| `POST /reports` | 유지 | legacy v1 신고 저장 |
| `GET /reports`, `GET /reports/export`, `GET /reports/{id}`, `PATCH /reports/{id}/status` | 유지/확장 | admin/report 운영 |

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

기본값은 `fake` mode이며 이미지 업로드 검증 후 `unified_walksafe` fake v2 detection을 반환한다. `DETECT_V2_MODE=yolo` 또는 `real`에서 `DETECT_V2_UNIFIED_MODEL_PATH`가 존재하면 unified 단일 YOLO runtime을 우선 사용한다. unified path가 없으면 custom tactile/COCO model path가 모두 존재할 때 legacy fallback runtime을 사용한다. Ultralytics model load는 `/detect/v2/health`가 아니라 실제 `/detect/v2` 요청 시점까지 지연한다.

### 응답

```json
{
  "schema_version": "detect.v2",
  "detections": [
    {
      "schema_version": "detect.v2",
      "model_key": "unified_walksafe",
      "source_model": "fake/unified-walksafe-contract",
      "model_class_id": 8,
      "class_name": "damaged_tactile_block",
      "category": "tactile_damage",
      "confidence": 0.93,
      "bbox": { "x": 0.10, "y": 0.16, "width": 0.46, "height": 0.40 },
      "distance_m": null,
      "distance_source": null,
      "distance_confidence": null,
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
| `unified_walksafe` | YOLO26n COCO+WalkSafe 13-class 후보 | `person`, `bicycle`, `car`, `motorcycle`, `bus`, `truck`, `traffic light`, `normal_tactile_block`, `damaged_tactile_block`, `crosswalk`, `curb_step`, `uneven_sidewalk`, `e_scooter_obstruction` |

`/detect/v2` fake contract는 기본적으로 `unified_walksafe` 13-class payload를 반환한다. unified에서는 `bench`를 쓰지 않는다. legacy fallback에서만 COCO allowlist와 cross-model NMS 미적용 정책이 의미 있다.

`distance_m`은 명시적 거리값이 있을 때만 전달한다. 허용 범위는 `0 <= distance_m <= 50`이며, `distance_source`는 `sensor_depth`, `manual_fixture`, `model_estimate`, `unknown`, `distance_confidence`는 `0..1`이다. 프론트는 source/confidence가 없으면 보폭 TTS 문구를 만들지 않는다.

## 3. `GET /detect/v2/health`

### 응답 예시

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

### 관련 환경변수

| env | 기본값 | 의미 |
|---|---|---|
| `DETECT_V2_MODE` | `fake` | `fake`, `yolo`, `real` |
| `DETECT_V2_UNIFIED_MODEL_PATH` | unset | unified 13-class YOLO checkpoint. 있으면 이 경로를 우선 사용 |
| `DETECT_V2_CUSTOM_TACTILE_MODEL_PATH` | unset | legacy fallback custom tactile YOLO checkpoint |
| `DETECT_V2_COCO_MODEL_PATH` | unset | legacy fallback COCO helper model |
| `DETECT_V2_RUNTIME_CONFIG_PATH` | unset | threshold/runtime config |

`fake` mode는 모델 path 없이 ready다. `yolo`/`real` mode에서는 unified path도 없고 legacy custom tactile+COCO path pair도 완성되지 않으면 `/detect/v2`는 503을 반환한다. model path가 있으면 health는 ready가 될 수 있지만, 실제 추론은 첫 `/detect/v2` 요청에서 모델 로딩과 런타임 의존성을 확인한다.

2026-05-23 KST 기준 local Stage1 env와 health-only smoke는 legacy fallback 참고 기록이다. 필요하면 `docs/execution/2026-05-23_reviewed_yolo26s_final_selection.md`와 `scripts/check_detect_v2_stage1_candidate_health_20260523.sh`를 참고하되, 현재 primary는 unified path를 우선한다.

## 4. `POST /reports/v2`

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
| `source` | optional `android` | Android native on-device upload일 때만 사용. 없으면 `source_model` 기준으로 `fake`/`server` 저장 |
| `coordinate_gate_status` | string or null | Android bbox/depth gate 상태. non-pass 값은 성능 집계 제외 근거가 될 수 있음 |

### 허용 대상

| model_key | class_name | 저장 |
|---|---|---|
| `unified_walksafe` | `damaged_tactile_block` | 허용 |
| `unified_walksafe` | 일반 객체/경로·장애물 class | 거부, 위험 경고 입력만 가능 |
| `custom_tactile` | `damaged_tactile_block` | 허용 |
| `custom_tactile` | `tactile_damage_area` | 거부, 보조 bbox 정보 |
| `custom_tactile` | `normal_tactile_block` | 거부 |
| `coco_general` | any | 거부 |

거부 시 422을 반환한다.

### 저장 정책

- 기존 `reports` 테이블을 재사용한다.
- `class_id`에는 v2 `model_class_id`를 저장한다.
- `class_name`에는 v2 `class_name`을 저장한다.
- `source`가 `android`이면 `reports.source=android`로 저장한다. 그 외에는 `source_model`이 `fake`로 시작하면 `fake`, 아니면 `server`로 저장한다.
- v2 payload는 허용된 detection/report/review flag 필드만 report JSON metadata에 저장한다. Android `apk_sha256`, `model_config_sha256`, `android_model_version`, `bbox_coordinate_space`, `depth_coordinate_space`, `depth_sample_count`, `depth_valid_sample_ratio`, `detection_age_ms`, `coordinate_gate_status`, `fallback_used`, `loaded_model_key`, `model_load_reason`은 현재 allowlist에 포함되어 있다. 보호자 연락처 등 unknown extra field는 저장하지 않는다.
- v2 손상 점자블록 신고는 GPS가 있어야 저장한다. GPS가 없으면 422로 거부한다.
- 중복 후보는 class/location/time 기준으로 기존 정책을 재사용한다.

## 5. `GET /reports` v2 운영 필터

`GET /reports`는 v1/v2 신고를 같은 `reports` 테이블에서 조회한다. v1 호환 필터는 유지하고, v2 운영을 위해 metadata 기반 필터를 추가했다.

| query | type | note |
|---|---|---|
| `status` | `new`, `reviewed`, `resolved` | 기존 상태 필터 |
| `class_name` | string | v1 enum 외 v2 class name도 허용 |
| `source` | `fake`, `onnx`, `server`, `android` | detector/upload source. Android native upload는 `android`로 분리한다 |
| `model_key` | string | v2 metadata `model_key`와 매칭 |
| `trigger` | string | v2 metadata `trigger`와 매칭. 현재 `auto`, `voice` |
| `auto_reported` | boolean | v2 metadata `auto_reported`와 매칭 |
| `created_from`, `created_to` | datetime | 생성 시각 범위 |
| `lat`, `lng`, `radius_m` | number | 세 값을 함께 줄 때만 반경 검색 |

예:

```http
GET /reports?class_name=damaged_tactile_block&model_key=unified_walksafe
GET /reports?trigger=voice&auto_reported=false
```

## 6. `GET /reports/export`

관리자/운영자가 현재 필터 조건의 신고 목록을 외부 검수나 기관 제출 준비용으로 내보내는 endpoint다.

```http
GET /reports/export?model_key=unified_walksafe
GET /reports/export?trigger=voice&auto_reported=false
GET /reports/export?format=json&class_name=damaged_tactile_block
GET /reports/export?format=geojson&status=reviewed
GET /reports/export?format=json&manifest=true&demo_filter=exclude_fake
GET /reports/export?format=geojson&aggregate=grid&demo_filter=exclude_fake
```

- 기본 포맷: CSV
- 선택 포맷: `format=json`, `format=geojson`
- GeoJSON은 `FeatureCollection`을 반환한다. 위치가 있는 신고는 Point Feature, 위치가 없는 신고는 `geometry: null`로 둔다.
- `aggregate=grid`는 외부 지도 SDK 없이 summary grid cluster를 GeoJSON FeatureCollection으로 반환한다.
- 필터: `GET /reports`와 같은 query를 사용한다.
- JSON/GeoJSON export row/properties는 v2 metadata의 `distance_m`, `trace_id`, `payload_sha256`, `image_sha256`, `data_origin`, `performance_excluded`를 포함한다.
- JSON/CSV/GeoJSON export는 `Cache-Control: no-store`, `X-WalkSafe-Demo-Filter`, demo-aware attachment filename을 반환한다.
- `redacted=true`는 좌표를 소수 4자리로 낮추고 `image_path`를 비운다. `bom=true`는 CSV에 UTF-8 BOM을 붙인다.
- `manifest=true`는 JSON export를 manifest wrapper로 감싸 필터, 건수, `rows_sha256`를 함께 기록한다.
- CSV fields:
  - `id`
  - `status`
  - `class_name`
  - `confidence`
  - `source`
  - `latitude`
  - `longitude`
  - `accuracy_m`
  - `heading`
  - `bbox_x`, `bbox_y`, `bbox_width`, `bbox_height`
  - `captured_at`
  - `created_at`
  - `model_key`
  - `source_model`
  - `threshold_used`
  - `trigger`
  - `auto_reported`
  - `distance_m`
  - `trace_id`
  - `payload_sha256`
  - `image_sha256`
  - `data_origin`
  - `runtime_mode`
  - `performance_excluded`
  - `performance_exclusion_reason`
  - `location_quality`
  - `review_flags`
  - `status_history_count`
  - `review_note`
  - `resolution_reason`
  - `image_path`

현재 export는 “기관 자동 전송”이 아니라 검토/제출 준비용이다.

## 7. `GET /reports/summary`

`GET /reports`와 같은 필터 조건으로 전체 개수, 상태/source count, 위치 있음/없음, 좌표 격자 Top cluster를 반환한다. Admin 목록 limit 50개와 별개로 전체 filtered row를 요약하며 외부 지도 SDK를 호출하지 않는다.

## 8. `GET /navigation/destinations/search`

목적지 이름을 앱 전용 후보 schema로 정규화한다. 기본은 TMAP POI live provider이며, `TMAP_POI_PROVIDER=mock`이면 외부 호출 없이 로컬 mock/alias 후보만 반환한다.

```http
GET /navigation/destinations/search?query=서울역&limit=5&origin_lat=37.56&origin_lng=126.97
GET /navigation/destinations/search/health
```

응답 후보는 `id`, `name`, `point`, `address`, `road_address`, `category`, `result_type`, `distance_m`를 포함한다. `result_type`은 `poi`, `address`, `alias` 중 하나다. 검색 health는 key 설정 여부만 확인하고 실제 provider를 호출하지 않는다.

## 9. `POST /navigation/walking`

TMAP 보행자 경로안내 API를 서버에서 호출하고, 프론트가 쓰기 쉬운 앱 전용 schema로 정규화한다. TMAP appKey는 백엔드 env에만 둔다. `WALKING_ROUTE_PROVIDER=kakao_mobility`를 설정하면 기존 Kakao Mobility provider를 fallback으로 사용할 수 있다. Kakao fallback은 `DISTANCE`, `MAIN_STREET` priority만 지원한다.

```http
POST /navigation/walking
Content-Type: application/json
```

요청:

| field | type | note |
|---|---|---|
| `origin` | `RoutePoint` | 현재 GPS. `latitude`, `longitude`, optional `name` |
| `destination` | `RoutePoint` | 목적지 좌표 |
| `waypoints` | `RoutePoint[]` | 선택, 최대 5개. TMAP `passList`로 변환 |
| `priority` | `RECOMMEND`, `MAIN_STREET`, `DISTANCE`, `STAIR_AVOID` | 기본 `STAIR_AVOID` |
| `radius_m` | int | Kakao fallback용. 기본 5000, 최대 12000 |
| `default_speed` | number or null | TMAP speed override. 기본 `TMAP_PEDESTRIAN_SPEED_KMH` |

TMAP priority mapping:

| priority | TMAP `searchOption` | 의미 |
|---|---:|---|
| `RECOMMEND` | `0` | 추천 |
| `MAIN_STREET` | `4` | 추천 + 대로 우선 |
| `DISTANCE` | `10` | 최단 |
| `STAIR_AVOID` | `30` | 최단거리 + 계단 제외 |

응답 schema:

```json
{
  "schema_version": "walksafe.walking_route.v1",
  "provider": "tmap_pedestrian",
  "provider_route_id": null,
  "priority": "STAIR_AVOID",
  "summary": { "distance_m": 1281, "duration_s": 1220 },
  "polyline": [{ "latitude": 37.3947, "longitude": 127.1101 }],
  "steps": [
    {
      "index": 0,
      "distance_m": 20,
      "duration_s": 18,
      "instruction": "테스트길, 20m",
      "road_name": "테스트길",
      "turn_type": null,
      "facility_type": 0,
      "points": [{ "latitude": 37.3947, "longitude": 127.1101 }]
    }
  ],
  "guide_points": [
    {
      "index": 0,
      "point": { "latitude": 37.398, "longitude": 127.109 },
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

`guide_points`는 TMAP Point feature의 안내 지점을 정규화한 배열이다. `description`이 `", 73m"`처럼 안내 의미가 없는 거리 문구뿐이면 `instruction`은 `null`이 될 수 있다. `bearing_deg`는 route polyline의 다음 segment 방향에서 계산한 0 이상 360 미만의 보조 heading이다. Kakao fallback은 guide point를 제공하지 않아 빈 배열을 반환한다.

`GET /navigation/walking/health`는 실제 provider를 호출하지 않고 key 설정 여부만 보여준다.

관련 env:

| env | 의미 |
|---|---|
| `WALKING_ROUTE_PROVIDER` | 기본 `tmap_pedestrian`. fallback은 `kakao_mobility` |
| `TMAP_APP_KEY` | TMAP appKey. Git에 올리지 않는다 |
| `TMAP_PEDESTRIAN_ROUTE_URL` | TMAP provider endpoint |
| `TMAP_PEDESTRIAN_API_VERSION` | TMAP version query |
| `TMAP_TIMEOUT_SECONDS` | TMAP provider timeout |
| `TMAP_PEDESTRIAN_SPEED_KMH` | TMAP 기본 보행 속도 |
| `KAKAO_MOBILITY_REST_API_KEY` | Kakao fallback REST API key. Git에 올리지 않는다 |
| `KAKAO_MOBILITY_WALKING_DIRECTIONS_URL` | Kakao fallback endpoint |
| `KAKAO_MOBILITY_SERVICE_NAME` | Kakao 요청 `service` header |
| `KAKAO_MOBILITY_TIMEOUT_SECONDS` | Kakao fallback timeout |

오류 코드 `tmap_invalid_api_key`는 appKey가 잘못됐거나 해당 앱에 보행자 경로안내 상품 권한이 없을 때 반환될 수 있다.

현재 이 API는 경로 geometry/요약을 받아오는 proxy다. 목적지 이름 검색은 위 `GET /navigation/destinations/search`에서 별도 제공한다.

## 8. 안전 주의

- v1 `ClassId`/`ClassName` validator에 v2 class를 억지로 추가하지 않는다.
- v2 `model_class_id`를 전역 class id로 해석하지 않는다.
- 일반 객체를 `/reports/v2` 저장 대상으로 열지 않는다.
- 실제 YOLO adapter를 붙일 때도 `model_key`, `source_model`, `threshold_used`를 유지한다.
- 두 모델 결과를 class name 또는 bbox만 보고 단일 NMS로 제거하지 않는다.
- TMAP/Kakao provider 응답을 실사용 안전 판단으로 과장하지 않는다. 위험 경고는 WalkSafe risk evaluator가 우선한다.

## 9. 검증

현재 관련 테스트:

```bash
PATH="$PWD/.venv/bin:$PATH" PYTHONPATH=. python3 -m pytest \
  backend/tests/test_detect_v2.py \
  backend/tests/test_reports_v2.py \
  backend/tests/test_navigation_routes.py \
  backend/tests/test_detect.py \
  backend/tests/test_reports.py -q
```

Voice intent classifier regression:

```bash
.venv/bin/python -m pytest tests/test_voice_intents.py -q
```

Local CPU-only contract smoke:

```bash
.venv/bin/python scripts/check_detect_v2_contract_smoke_20260523.py
```
