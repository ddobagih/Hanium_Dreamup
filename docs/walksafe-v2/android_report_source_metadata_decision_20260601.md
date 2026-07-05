# Android report source/metadata decision - 2026-07-01

## 목적

Android native 앱에서 `/reports/v2` upload에 쓰는 source, source_model, metadata allowlist, review flag, export, privacy/cooldown 계약을 정리한다.

이 문서는 2026-06-02 코드 변경 전 draft에서 출발했지만, 2026-07-01 현재는 `source=android`와 Android metadata allowlist가 코드에 반영되어 있다. 운영 DB, 배포, 외부 제출, secret, 비용 발생 API는 별도 사용자 승인 없이는 진행하지 않는다.

## 현재 backend 계약

| 항목 | 현재 구현 |
|---|---|
| endpoint | `POST /reports/v2` |
| 저장 table | 기존 `reports` table 재사용 |
| 허용 class | `model_key=unified_walksafe`, `class_name=damaged_tactile_block`, `model_class_id=8`; legacy fallback은 `model_key=custom_tactile`, `model_class_id=1` |
| 거부 class | `normal_tactile_block`, `tactile_damage_area`, COCO/general 객체, unified 일반 객체 |
| GPS | 필수. 없으면 422 |
| image | multipart image 필수, 서버 upload dir에 저장 |
| trigger | `auto` 또는 `voice` |
| auto_reported | boolean |
| source 결정 | Android metadata가 `source=android`이면 `android`, 그 외 `source_model`이 `fake`로 시작하면 `fake`, 나머지는 `server` |
| metadata allowlist | `REPORT_V2_METADATA_ALLOWLIST`에 있는 key만 우선 보존 |
| payload 보강 | `payload_sha256`, `image_sha256`, `data_origin`, `runtime_mode`, `performance_excluded` |
| export | CSV/JSON/GeoJSON, redacted, manifest, grid aggregate, no-store |
| admin filter | status, class_name, source, model_key, trigger, auto_reported, date, radius, demo_filter |

## Android debug log와 report upload 분리

2026-06-01 기준 Android depth 문제 분석용 endpoint는 `/android/debug/depth-logs`로 분리되어 있다.

- debug endpoint는 metadata-only JSONL 저장이다.
- 기본 비활성이고 `ANDROID_DEBUG_LOG_ENABLED=true`인 local/dev backend에서만 사용한다.
- image, GPS, report status, public agency submission 의미가 없다.
- `/reports/v2` upload 구현 여부와 별개다.
- debug log upload 성공을 Android report 기능 PASS로 쓰지 않는다.

관련 코드:

```text
backend/app/schemas.py
backend/app/api/reports.py
backend/app/api/android_debug.py
backend/app/services/report_policy.py
backend/app/services/report_serialization.py
backend/tests/test_reports_v2.py
backend/tests/test_android_debug_logs.py
```

## 현재 allowlist

`backend/app/api/reports.py`의 `REPORT_V2_METADATA_ALLOWLIST` 기준:

```text
schema_version
source
model_key
source_model
model_class_id
class_name
category
confidence
bbox
distance_m
distance_source
distance_confidence
threshold_used
captured_at
gps
heading
trigger
auto_reported
review_flags
fake_source
trace_id
data_origin
runtime_mode
apk_sha256
model_config_sha256
android_model_version
bbox_coordinate_space
depth_coordinate_space
depth_sample_count
depth_valid_sample_ratio
detection_age_ms
coordinate_gate_status
fallback_used
loaded_model_key
model_load_reason
```

`depth_median_m`은 별도 key로 allowlist하지 않고 현재 `distance_m`/`distance_source=sensor_depth`/`distance_confidence` 조합으로 표현한다.

## 결정 후보 A: `source=server` 유지

Android 초기 field candidate를 현재 schema 변경 없이 `source=server`로 저장하고, Android 여부는 `source_model`, `runtime_mode`, metadata로 구분한다.

| 장점 | 단점 |
|---|---|
| DB enum/schema/filter 변경이 적다 | admin/export에서 Android와 server inference가 `source`만으로 구분되지 않는다 |
| 기존 tests 영향이 작다 | Android field data 분리가 metadata 의존적이다 |
| 승인 부담이 낮다 | 장기적으로 source 의미가 흐려질 수 있다 |

필요 문서/코드 작업:

- `source_model` 표준을 `android-tflite/custom-tactile/<model-version>`처럼 고정한다.
- `runtime_mode=android-tflite`를 allowlist/문서에 명시한다.
- export에서 `source_model`/`runtime_mode`를 기준으로 Android를 식별한다.

## 결정 후보 B: `source=android` 또는 `source=android-tflite` 추가

backend `DetectorSource`에 Android source를 추가한다.

| 장점 | 단점 |
|---|---|
| admin/export/source filter에서 Android가 명확히 분리된다 | schema/API/test 변경 필요 |
| server inference와 on-device inference 의미가 분리된다 | 기존 `DetectorSource = fake | onnx | server` 호환성 확인 필요 |
| 장기 운영 지표 분리가 쉽다 | 운영 DB/배포 영향은 사용자 승인 필요 |

필요 문서/코드 작업:

- `backend/app/schemas.py`의 `DetectorSource` 확장.
- `report_v2_source()` Android source_model 처리 변경.
- `/reports` source filter, summary source_counts, export tests 갱신.
- 기존 admin/ops 문서 갱신.

## 결정 사항

2026-07-01 현재는 후보 B를 채택한 상태다.

- backend `DetectorSource`는 `android`를 포함한다.
- `ReportV2Metadata.source`는 optional `android`를 허용한다.
- `report_v2_source()`는 Android payload를 `reports.source=android`로 저장한다.
- Admin/Web 타입과 summary/source count는 Android source를 표시한다.
- Android upload 코드는 Device Gate, fresh metric depth, trusted GPS, `damaged_tactile_block`, 허용 model key 조건을 통과할 때만 시도한다.
- gate 전/미검증 Android field data는 성능 metric이나 Device PASS 근거로 쓰지 않는다.

## Android metadata 후보

| metadata | 목적 | 현재 저장 여부 | 권장 |
|---|---|---|---|
| `apk_sha256` | APK build 식별 | 저장 | 유지 |
| `model_config_sha256` | runtime JSON 식별 | 저장 | 유지 |
| `android_model_version` | 앱/model version | 저장 | 유지 |
| `bbox_coordinate_space` | bbox 좌표계 설명 | 저장 | 유지 |
| `depth_coordinate_space` | depth sample 좌표계 설명 | 저장 | 유지 |
| `depth_median_m` | Android depth 요약 | 현재 `distance_m` 대체 가능 | `distance_m`와 의미 분리 필요 |
| `depth_sample_count` | depth 품질 | 저장 | 유지 |
| `depth_valid_sample_ratio` | depth sample 유효 비율 | 저장 | 유지 |
| `depth_source` | Raw/Full/pseudo | 현재 `distance_source`와 일부 중복 | naming 통일 필요 |
| `detection_age_ms` | stale guard 근거 | 저장 | 유지 |
| `coordinate_gate_status` | gate pending/pass 구분 | 저장 | 유지 |
| `fallback_used` | unified asset 부재 등 detector fallback 여부 | 저장 | 유지 |
| `loaded_model_key` | 실제 로드된 Android detector key | 저장 | 유지 |
| `model_load_reason` | detector load/fallback 사유 | 저장 | 유지 |
| `performance_excluded` | metric 제외 | 서버가 보강 | client 임의값보다 서버 정책 우선 |
| `data_origin` | demo/field_candidate | 서버가 보강 가능 | gate 전 `field_candidate`라도 metric 제외 여부 결정 필요 |

## review flag 정책

후보 flag:

| flag | 붙이는 조건 | 계산 위치 후보 |
|---|---|---|
| `coordinate_gate_pending` | G1/G2 통과 전 Android upload | server 권장 |
| `android_field_candidate` | Android native에서 온 초기 field data | server 또는 client |
| `low_depth_sample_count` | sample count 부족 | client metadata 기반 server 계산 |
| `stale_detection` | detection age 초과 후보 | client metadata 기반 server 계산 |
| `missing_heading` | heading 없음 | 기존 server serialization |
| `low_location_accuracy` | GPS accuracy 낮음 | 기존 server serialization |

권장: gate 전에는 upload를 붙이지 않는다. 붙이는 단계에서는 server가 review flag를 계산하는 쪽이 admin/export 일관성에 유리하다.

## privacy/payload 기준

- 현재 `/reports/v2`는 image upload가 필수이고 서버가 파일을 저장한다.
- Android에서 depth raw, confidence map, screenshot, 주변 이미지 원본 저장을 확대하려면 별도 승인과 privacy 문서가 필요하다.
- GPS는 report 저장에 필수지만, export redaction/보존 정책을 admin 운영 문서와 맞춰야 한다.
- 보호자 연락처, 사용자 식별자, 주소 원문은 report payload에 넣지 않는다.

## cooldown/duplicate 기준

현재 backend duplicate 후보는 class/location/time 기준이다. Android 자동 신고 전에는 다음 결정을 해야 한다.

- client cooldown 시간창
- 동일 bbox/동일 장소 반복 감지 시 미전송 기준
- backend duplicate 반경/시간창과 client cooldown의 관계
- voice trigger는 auto cooldown과 별도 우선순위를 가질지 여부

## 업로드 실패 UX 후보

| 상황 | HTTP/원인 | Android UX 후보 |
|---|---|---|
| GPS 없음 | 422 | 자동 신고는 조용히 보류, 음성 요청이면 짧게 실패 안내 |
| non-reportable class | 422 | 자동 신고 요청 생성 자체를 하지 않음 |
| metadata too large | 413 | metadata 축소 후 1회 재시도 또는 보류 |
| image content-type/size 오류 | 400/413 | 자동 신고는 보류, debug log만 남김 |
| network/server unavailable | 5xx/network | rate-limited retry 후보, 사용자 과잉 TTS 금지 |

## 코드 변경 전 승인 필요한 항목

- `DetectorSource` 확장이나 DB/API 호환성에 영향 있는 schema 변경.
- 운영 DB migration, 운영 데이터 수정/삭제/초기화.
- backend/admin 운영 배포.
- Android release signing, 배포, 외부 공개.
- 공공기관 자동 제출/API 연동.
- TMAP/Kakao/Cloud STT/TTS secret 설정 또는 비용 발생 live API 호출.
- Android 이미지/depth 파일 저장 확대, 정밀 위치 보존 정책 변경.

## 다음 작업

- [단계] P0~P2 gate 통과 전까지 Android upload 구현은 보류한다.
  -> 검증: Android code에 `/reports/v2` 자동 호출이 없다.

- [단계] Android source 임시안을 문서로 유지한다.
  -> 검증: `source=server` 유지안과 `source=android` 확장안의 영향이 비교되어 있다.

- [단계] report upload 구현 직전 allowlist 확장안을 확정한다.
  -> 검증: Android 좌표/depth/stale metadata가 저장될 항목과 버릴 항목이 표로 분리된다.

- [단계] admin/export smoke test 기준을 작성한다.
  -> 검증: POST `/reports/v2`, GET `/reports`, GET `/reports/export?demo_filter=exclude_fake&redacted=true`, status 변경 흐름이 연결된다.
