# Report Operations Policy

작성 기준일: 2026-07-02 KST

## 목적

WalkSafe의 자동/음성 신고, 운영자 검수, CSV/JSON/GeoJSON 내보내기를 Web/PWA 주 사용자 앱, 백엔드, Web/Admin과 Android 검증 모듈이 같은 의미로 쓰기 위해 정리한다. 공공기관 자동 API 제출은 범위 밖이며 관리자가 CSV를 내려받아 수동 외부 신고한다.

## 2026-07-11 현재 위치

- 주 사용자 앱은 Web/PWA다. 실제 신고 경로는 `server-v2`이고 `fake-v2`는 개발·API contract 검증 전용이다.
- backend/admin `/reports/v2`와 `/reports/export`는 계속 운영 source-of-truth다.
- Android native report upload는 ARCore/depth/TFLite 실험·검증 보조 경로의 Device Gate 뒤에 연결되어 있다. Android evidence는 Web/PWA 제품 완료를 대체하지 않는다.
- Web/PWA 브라우저·Release evidence와 Android Device evidence는 서로 대체하지 않는다.
- field/admin HttpOnly actor session은 상호 대체되지 않는다. named account, fail-closed 설정, 파일 login limiter와 UI logout을 제공하고 status/export에 actor를 전달한다.
- 손상 점자블록 자동 신고는 confidence 0.70, GPS accuracy 15m, 동일 공간 후보 3프레임 gate 뒤에 실행된다. 자동·음성 모두 bbox를 만든 동일 snapshot을 사용하며 대기·성공·실패를 aria-live와 haptic으로 제공한다.
- 합성 원격 브라우저에서 epoch270 손상 후보가 위 gate를 통과해 PostGIS에 저장되는 E2E를 확인했고, 생성 row는 `synthetic_integration_test`로 검수·종결했다.

## 운영 원칙

1. 앱은 타일/점자블록 손상만 신고로 저장한다.
2. 일반 객체는 시설물 신고 대상이 아니며, 보행 위험 객체/상황일 때만 사용자에게 음성 경고한다.
3. 자동 신고는 성공·실패를 화면/aria-live와 haptic으로 알리되 불필요한 TTS는 하지 않는다.
4. 사용자가 음성으로 요청한 신고는 완료/실패를 짧게 안내한다.
5. 공공기관 자동 API 제출 기능은 두지 않는다. 관리자가 검수·필터링한 뒤 `/reports/export?profile=agency`의 정확 위치 최소 CSV/manifest를 내려받아 외부 기관에 수동 신고하고 receipt를 기록한다. 임의 선택·병합 기능은 없다.
6. Android bbox/depth가 불확실한 상태에서는 자동 upload 성공을 제품 완료 evidence로 쓰지 않는다.
7. Web은 `reporter_user_id`를 보내지 않는다. gateway actor는 ingestion/review/export audit에 남지만, Android local ID나 외부 IdP 인증 subject와 동일시하지 않는다.

## 신고 생성 경로

| 경로 | endpoint | 저장 대상 | 사용자 안내 | 현재 상태 |
|---|---|---|---|---|
| Android 자동 신고 | `POST /reports/v2` | `unified_walksafe` 또는 legacy `custom_tactile`의 `damaged_tactile_block` | 기본적으로 침묵 | Device Gate 뒤 코드 연결. field/PostGIS 검증 필요 |
| Android 음성 요청 신고 | `POST /reports/v2` | 현재 탐지된 신고 가능 `damaged_tactile_block` | 완료/실패 TTS. GPS가 없으면 위치 정보가 필요하다고 안내. 중복 후보이면 저장은 하되 "이미 신고가 된 상태입니다"라고 안내 | `SpeechRecognizer` 버튼 경로와 upload route 연결. 실기기 mic/TTS 체감 PASS는 후속 Device evidence |
| PWA v2 자동/음성 신고 | `POST /reports/v2` | `unified_walksafe` 또는 legacy `custom_tactile`의 `damaged_tactile_block` | 자동은 aria-live+haptic, 음성은 speech 설정 시 TTS 추가 | 동일 분석 snapshot·coordinate gate·server provenance |
| legacy 신고 | `POST /reports` | v1 계약에 따름 | v1 정책에 따름 | GPS 필수, server provenance, 성능 근거 제외로 유지 |

v2 신고는 자동/음성 모두 GPS가 있어야 저장한다. 위치가 없으면 앱은 저장하지 않고 위치 확인 대기/재시도 안내로 처리한다.

`reporter_user_id`는 optional client metadata다. Web은 이를 보내지 않고 Android native의 local user id도 서버 인증 subject가 아니다. 별도로 gateway named actor는 `ingested_by_actor_id`, 상태 이력과 export audit에 기록된다. 실제 운영 account/token/session secret 설정은 release evidence gate 대상이다.

v2 metadata 기준:

| field | 값 | 의미 |
|---|---|---|
| `model_key` | `unified_walksafe` 또는 `custom_tactile` | unified 13-class 결과. `custom_tactile`은 legacy fallback 결과 |
| `trigger` | `auto` 또는 `voice` | `auto_reported`와 일관되어야 하며 server가 조합을 검증 |
| `auto_reported` | boolean | `auto`이면 true, `voice`이면 false |
| `reporter_user_id` | optional client string | Web은 현재 미전송, Android는 로컬 입력값. 서버 인증 subject가 아니며 공개/export 시 redaction 검토 필요 |
| `source` | `fake`, `onnx`, `server`, `android` | backend `reports.source` enum. Android native upload는 `android`로 분리 |
| `source_model`/v2 metadata | model/checkpoint/runtime 식별자 | 제어문자·filesystem path·URL을 거부. server provenance 자체는 아님 |
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

`GET /reports/export`는 현재 필터 조건을 그대로 사용해 CSV를 기본으로 내보낸다. `format=json`과 `format=geojson`도 지원한다. 응답은 `Cache-Control: no-store`이며 demo/profile/location precision, actor와 audit ID가 filename/header/manifest에 남는다.

추가 옵션:

- `profile=internal`: 관리자 내부 검토용 전체 필드·정확 좌표.
- `profile=minimum`: 좌표를 소수 2자리로 반올림하고 image path·신고자/trace/bbox/중복 ID/note/reason을 비운다.
- `profile=agency`: named 관리자가 검수한 reviewed·damage·high≤15m·non-fake만 `id/status/class/confidence/정확 좌표/accuracy/시각/location_quality`로 남긴다. `performance_excluded`는 모델 성능 집계 경계로 유지하되 기관 human review 적격성과 분리한다. 손상 위치를 기관에 신고하기 위해 좌표를 반올림하지 않는다.
- `manifest=true`: JSON export에 필터, 건수, `rows_sha256`를 포함한다.
- `aggregate=grid`: GeoJSON에서 외부 지도 SDK 없이 관리자용 정확 위치 grid cluster를 만든다. `internal`만 허용하고 redacted/minimum/agency 조합은 거부한다.
- `bom=true`: CSV Excel 호환 UTF-8 BOM을 붙인다.

CSV는 `review_flags`를 포함한 모든 text cell의 `=`, `+`, `-`, `@` formula prefix를 escape한다. field duplicate-check는 정확 좌표나 metadata가 아니라 report ID 목록과 count만 반환한다.

Export는 관리자가 신고를 검수·필터링하고 수동 외부 신고용 CSV를 준비하는 다운로드 기능이다. `agency` export와 receipt CLI가 있어도 export 자체가 기관 자동 전송이나 실제 민원 접수는 아니다.

## 운영자 검수/export 흐름

```text
Web/PWA server-v2 주 사용자 경로에서 자동·음성 신고 저장
  -> Android 실험·검증 경로의 신고는 source=android로 별도 구분
  -> 관리자 화면에서 v2 필터로 후보 확인
  -> 이미지/위치/클래스/source/중복 검수
  -> 검수 결과에 따라 reviewed/resolved 상태를 기록하고 외부 신고 조건으로 필터링
  -> /reports/export?profile=agency에서 정확 위치 최소 CSV와 manifest 다운로드
  -> 관리자가 해당 외부 기관 채널에 수동 신고하고 접수 ID receipt 기록
  -> JSON/GeoJSON은 내부 분석·보수 의사결정에 사용
```

기관 자동 API 제출 기능을 두지 않는 이유:

- 기관별 공식 접수 권한/API와 제출 양식 확인이 필요하다.
- 오탐이 그대로 민원으로 접수되면 기관 업무 부담과 서비스 신뢰도 문제가 생긴다.
- 위치정보/이미지/신고자 정보 제공 동의와 보존 정책이 먼저 정해져야 한다.

현재 제품 정책은 `docs/walksafe-v2/public_agency_submission_policy.md`의 관리자 CSV 수동 외부 신고 기준을 따른다.

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

최종 발표나 보고서에서 PWA fake/headless 데이터를 사용할 경우 “개발·통합 테스트 데이터”로 명시한다. Web/PWA 브라우저·Release evidence와 Android TFLite/ARCore Device evidence는 각각의 범위를 함께 적는다.

## Retention과 release 증거

- Report row/image는 fake/demo 30일, active/resolved 180일 기준이다. `performance_excluded=true`만으로 30일 bucket으로 줄이지 않는다.
- `scripts/check_report_retention_dry_run.py`는 기본 dry-run이며, live `--apply`는 DB URL·upload root·명시 확인 문구·actor·audit manifest를 모두 요구한다. image를 quarantine한 뒤 DB commit하고 실패 전에는 rollback/복원을 시도한다. 일일 systemd scheduler 템플릿은 같은 guarded apply를 호출하지만 운영자가 fresh signed backup/restore pair를 갱신하고 1회 검증하기 전에는 enable하지 않는다.
- `scripts/check_walksafe_release_evidence_20260711.py`는 completed report retention apply manifest, 7일 field telemetry applied receipt, OpenPGP backup·복구 drill, 실제 기관 receipt와 사람의 제출 이미지 privacy receipt가 없으면 release를 FAIL한다.
- 이 도구·게이트는 구현됐지만 운영 DB 삭제, backup/복구, 기관 제출을 실제 수행한 증거는 아직 없다.
