# Report Operations Policy

작성 기준일: 2026-07-02 KST

## 목적

WalkSafe의 자동/음성 신고, 운영자 검수, CSV/JSON/GeoJSON 내보내기를 Android native 앱, 백엔드, Web/Admin이 같은 의미로 쓰기 위해 정리한다. 공공기관 제출 기능은 제품 범위에 넣지 않는다.

## 2026-07-02 현재 위치

- 주 사용자 앱은 Android native ARCore/TFLite APK다.
- backend/admin `/reports/v2`와 `/reports/export`는 계속 운영 source-of-truth다.
- Android native에서 실제 report upload 코드는 Device Gate 뒤에 연결되어 있다. 단, bbox/depth 좌표 정합과 PostGIS no-skip 검증 전에는 field/product 완료로 주장하지 않는다.
- Web/PWA `fake-v2`/`server-v2`는 demo/API contract 검증 경로이며 Android Device evidence를 대체하지 않는다.

## 운영 원칙

1. 앱은 타일/점자블록 손상만 신고로 저장한다.
2. 일반 객체는 시설물 신고 대상이 아니며, 보행 위험 객체/상황일 때만 사용자에게 음성 경고한다.
3. 자동 신고는 사용자에게 불필요한 TTS를 하지 않고 조용히 처리한다.
4. 사용자가 음성으로 요청한 신고는 완료/실패를 짧게 안내한다.
5. 공공기관 제출 기능은 두지 않는다. 신고는 운영자 대시보드 리스트로 들어가고, 운영자가 필요하면 `/reports/export`로 내부 다운로드한다.
6. Android bbox/depth가 불확실한 상태에서는 자동 upload 성공을 제품 완료 evidence로 쓰지 않는다.
7. 주요 기능은 로그인한 사용자에게만 제공하고, 신고자는 로그인 user id로 추적한다.

## 신고 생성 경로

| 경로 | endpoint | 저장 대상 | 사용자 안내 | 현재 상태 |
|---|---|---|---|---|
| Android 자동 신고 | `POST /reports/v2` | `unified_walksafe` 또는 legacy `custom_tactile`의 `damaged_tactile_block` | 기본적으로 침묵 | Device Gate 뒤 코드 연결. field/PostGIS 검증 필요 |
| Android 음성 요청 신고 | `POST /reports/v2` | 현재 탐지된 신고 가능 `damaged_tactile_block` | 완료/실패 TTS. GPS가 없으면 위치 정보가 필요하다고 안내. 중복 후보이면 저장은 하되 "이미 신고가 된 상태입니다"라고 안내 | `SpeechRecognizer` 버튼 경로와 upload route 연결. 실기기 mic/TTS 체감 PASS는 후속 Device evidence |
| PWA v2 자동/음성 신고 | `POST /reports/v2` | `unified_walksafe` 또는 legacy `custom_tactile`의 `damaged_tactile_block` | 정책상 동일 | `server-v2` 저장, `fake-v2`는 demo/API contract 검증 중심 |
| legacy 신고 | `POST /reports` | v1 계약에 따름 | v1 정책에 따름 | 유지 |

v2 신고는 자동/음성 모두 GPS가 있어야 저장한다. 위치가 없으면 앱은 저장하지 않고 위치 확인 대기/재시도 안내로 처리한다.

v2 신고는 로그인 user id를 `reporter_user_id`로 남긴다. Android native는 로컬 user id 입력이 없으면 depth start, route/search, explicit report를 막고, `/reports/v2` metadata/export는 이 값을 보존한다. 운영 auth/RBAC/rate-limit/upload 접근 제어는 별도 출시 lane이다.

v2 metadata 기준:

| field | 값 | 의미 |
|---|---|---|
| `model_key` | `unified_walksafe` 또는 `custom_tactile` | unified 13-class 결과. `custom_tactile`은 legacy fallback 결과 |
| `trigger` | `auto` 또는 `voice` | 자동 신고인지 사용자 음성 요청인지 |
| `auto_reported` | boolean | 자동 신고로 저장된 건인지 여부 |
| `reporter_user_id` | 로그인 user id | 신고자 식별자. 운영 auth 검증은 출시 lane |
| `source` | `fake`, `onnx`, `server`, `android` | backend `reports.source` enum. Android native upload는 `android`로 분리 |
| `source_model`/v2 metadata | `unified_walksafe`, `custom_tactile`, checkpoint/runtime 식별자 등 | report evidence와 성능 집계 분리. Android source 표기는 gate 이후 metadata allowlist로 결정 |
| Android debug metadata | APK hash, threshold, bbox/depth summary 후보 | gate 이후 최소 allowlist로 결정 |

`/reports/v2`는 `unified_walksafe:damaged_tactile_block`과 legacy `custom_tactile:damaged_tactile_block`만 허용한다. `tactile_damage_area`, `normal_tactile_block`, `coco_general` 객체와 unified 일반 객체는 거부한다. 일반 객체는 위험 알림에는 쓸 수 있지만 신고 테이블에 저장하지 않는다.

## 신고 상태

| 상태 | 의미 | 변경 주체 |
|---|---|---|
| `new` | 새로 접수된 신고 | 서버 기본값 |
| `reviewed` | 운영자가 이미지, 위치, 클래스, 중복 여부를 확인했지만 아직 조치 완료는 아님 | 운영자 또는 관리자 UI |
| `resolved` | 조치 완료, 중복 흡수, 오신고 처리 등 더 이상 새 신고로 볼 필요가 없음 | 운영자 또는 관리자 UI |

상태 변경 규칙:

1. 신고 생성 시 기본 상태는 `new`다.
2. `reviewed`는 운영자가 이미지, 위치, 클래스, 중복 여부를 사람 기준으로 확인한 상태다. 기관 제출 가능 상태를 뜻하지 않는다.
3. `resolved`는 현장 조치 완료, 중복으로 흡수, 오신고 처리 등 더 이상 새 신고로 볼 필요가 없을 때 사용한다.
4. 현재 API는 상태 변경 이력 테이블을 따로 두지 않고 report metadata의 `status_history`에 변경 이력을 보존한다.

## 관리자 필터와 내보내기

관리자 화면과 `GET /reports`는 v2 운영을 위해 다음 필터를 지원한다.

| 필터 | 용도 |
|---|---|
| `status` | `new`, `reviewed`, `resolved` 상태별 검수 |
| `class_name` | `damaged_tactile_block` 등 클래스별 확인 |
| `source` | 현재 API enum 기준 `fake`/`server`/`onnx`/`android` 탐지 source 구분 |
| `model_key` | v2 unified/legacy tactile 신고 분리 |
| `trigger` | `auto`와 `voice` 신고 분리 |
| `auto_reported` | 자동 신고 여부 분리 |
| 날짜/위치 반경 | 특정 기간/지역 검수 |

`GET /reports/export`는 현재 필터 조건을 그대로 사용해 CSV를 기본으로 내보낸다. `format=json`과 `format=geojson`도 지원한다. Export 응답은 `Cache-Control: no-store`이며, demo 포함 여부가 filename과 `X-WalkSafe-Demo-Filter`에 남는다.

추가 옵션:

- `redacted=true`: 좌표를 낮은 정밀도로 반올림하고 `image_path`를 비운다.
- `manifest=true`: JSON export에 필터, 건수, `rows_sha256`를 포함한다.
- `aggregate=grid`: GeoJSON에서 외부 지도 SDK 없이 grid cluster FeatureCollection을 만든다.
- `bom=true`: CSV Excel 호환 UTF-8 BOM을 붙인다.

Export는 운영자 내부 다운로드 기능이며, “기관 자동 전송”이나 “기관 제출 기능”이 아니다.

## 운영자 검수/export 흐름

```text
Android는 bbox/depth gate 이후, PWA server-v2는 demo/API 검증 경로에서 자동·음성 신고 저장
  -> 관리자 화면에서 v2 필터로 후보 확인
  -> 이미지/위치/클래스/source/중복 검수
  -> 필요한 건 reviewed 또는 resolved 상태로 표시
  -> /reports/export CSV/JSON/GeoJSON 다운로드
  -> 운영자 내부 검수/보수 의사결정 자료로 사용
```

기관 제출 기능을 두지 않는 이유:

- 기관별 공식 접수 권한/API와 제출 양식 확인이 필요하다.
- 오탐이 그대로 민원으로 접수되면 기관 업무 부담과 서비스 신뢰도 문제가 생긴다.
- 위치정보/이미지/신고자 정보 제공 동의와 보존 정책이 먼저 정해져야 한다.

현재 제품 정책은 `docs/walksafe-v2/public_agency_submission_policy.md`의 no-submission 기준을 따른다.

## 중복 신고 기준

현재 중복 후보 기준:

| 항목 | 값 |
|---|---|
| 클래스 | 같은 `class_name` |
| 위치 | 기본 반경 `10m` |
| 시간 | `captured_at` 전후 `1분` |
| 위치 필수 여부 | `gps`가 있어야 후보 검색 가능 |

중복 후보는 자동 삭제나 자동 병합을 의미하지 않는다. 실제 중복이 아닐 수 있으므로 저장은 유지하고 duplicate tag를 붙인다. 사용자가 명시적으로 신고를 요청한 경우에는 "이미 신고가 된 상태입니다"라고 TTS로 알려준다.

## 위치 품질

| 값 | 기준 |
|---|---|
| `missing` | 위치 없음 |
| `low` | `accuracy_m` 없음 또는 50m 초과 |
| `medium` | 15m 초과 50m 이하 |
| `high` | 15m 이하 |

## 검토 플래그

| 값 | 의미 |
|---|---|
| `fake_source` | fake detector에서 생성됨 |
| `low_confidence` | confidence가 0.7 미만 |
| `missing_location` | GPS 없음 |
| `low_location_accuracy` | 위치 정확도 낮음 |
| `missing_heading` | 방향 정보 없음 |
| `coordinate_gate_pending` | Android bbox/depth 정합 gate 전 데이터 |
| `duplicate_candidate` | 10m/1분 기준 중복 후보가 있음 |

## Fake/미확정 신고 처리

fake 또는 미확정 모델 결과로 허용되는 것:

- 신고 생성/조회/상태 변경 흐름 확인
- v2 `model_key`/`trigger`/`auto_reported` 필터 확인
- CSV/JSON/GeoJSON export 확인
- 중복 후보 표시 확인
- 위치 품질과 검토 플래그 표시 확인

금지:

- 최종 모델 정확도 계산
- 최종 성능 지표 산출
- 실제 보행 안전 판단 근거로 단정
- 외부 제출 또는 운영 안전성 근거로 사용

최종 발표나 보고서에서 fake/PWA/headless 데이터를 사용할 경우 “통합 테스트 데이터”로 명시한다. Android TFLite/ARCore 결과도 APK hash와 Device evidence 범위를 함께 적는다.
