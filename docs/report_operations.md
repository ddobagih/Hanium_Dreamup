# Report Operations Policy

작성 기준일: 2026-05-23 KST

## 목적

WalkSafe v2의 자동/음성 신고, 운영자 검수, CSV/JSON/GeoJSON 내보내기, 공공기관 제출 전 검토 흐름을 백엔드와 프론트엔드가 같은 의미로 쓰기 위해 정리한다.

## 운영 원칙

1. 앱은 타일/점자블록 손상만 신고로 저장한다.
2. 일반 객체는 시설물 신고 대상이 아니며, 보행 위험 객체/상황일 때만 사용자에게 음성 경고한다.
3. 자동 신고는 사용자에게 불필요한 TTS를 하지 않고 조용히 처리한다.
4. 사용자가 음성으로 요청한 신고는 완료/실패를 짧게 안내한다.
5. 공공기관 직접 자동 제출은 아직 연결하지 않는다. 내부 검수 후 `/reports/export`로 내보내 제출 준비한다.

## 신고 생성 경로

| 경로 | endpoint | 저장 대상 | 사용자 안내 |
|---|---|---|---|
| 자동 신고 | `POST /reports/v2` | `custom_tactile`의 `damaged_tactile_block` | 기본적으로 침묵 |
| 음성 요청 신고 | `POST /reports/v2` | 현재 탐지된 신고 가능 `damaged_tactile_block` | 완료/실패 TTS |
| legacy 신고 | `POST /reports` | v1 계약에 따름 | v1 정책에 따름 |

v2 신고는 자동/음성 모두 GPS가 있어야 저장한다. 위치가 없으면 앱은 저장하지 않고 위치 확인 대기/재시도 안내로 처리한다.

v2 metadata 기준:

| field | 값 | 의미 |
|---|---|---|
| `model_key` | `custom_tactile` | 점자블록/타일 custom model 결과 |
| `trigger` | `auto` 또는 `voice` | 자동 신고인지 사용자 음성 요청인지 |
| `auto_reported` | boolean | 자동 신고로 저장된 건인지 여부 |

`/reports/v2`는 `tactile_damage_area`, `normal_tactile_block`, `coco_general` 객체를 거부한다. `tactile_damage_area`는 손상 부위 bbox 보조 정보일 뿐이며, 일반 객체는 위험 알림에는 쓸 수 있지만 신고 테이블에 저장하지 않는다.

## 신고 상태

| 상태 | 의미 | 변경 주체 |
|---|---|---|
| `new` | 새로 접수된 신고 | 서버 기본값 |
| `reviewed` | 운영자가 이미지, 위치, 클래스, 중복 여부를 확인했지만 아직 조치 완료는 아님 | 운영자 또는 관리자 UI |
| `resolved` | 조치 완료, 중복 흡수, 오신고 처리 등 더 이상 새 신고로 볼 필요가 없음 | 운영자 또는 관리자 UI |

상태 변경 규칙:

1. 신고 생성 시 기본 상태는 `new`다.
2. `reviewed`는 기관 제출 후보로 볼 수 있을 만큼 사람이 검토한 상태다.
3. `resolved`는 현장 조치 완료, 중복으로 흡수, 오신고 처리 등 더 이상 새 신고로 볼 필요가 없을 때 사용한다.
4. 현재 API는 상태 변경 이력 테이블을 따로 저장하지 않는다.

## 관리자 필터와 내보내기

관리자 화면과 `GET /reports`는 v2 운영을 위해 다음 필터를 지원한다.

| 필터 | 용도 |
|---|---|
| `status` | `new`, `reviewed`, `resolved` 상태별 검수 |
| `class_name` | `damaged_tactile_block` 등 클래스별 확인 |
| `source` | fake/server 등 탐지 source 구분 |
| `model_key` | v2 custom tactile 신고 분리 |
| `trigger` | `auto`와 `voice` 신고 분리 |
| `auto_reported` | 자동 신고 여부 분리 |
| 날짜/위치 반경 | 특정 기간/지역 검수 |

`GET /reports/export`는 현재 필터 조건을 그대로 사용해 CSV를 기본으로 내보낸다. `format=json`과 `format=geojson`도 지원한다. Export 응답은 `Cache-Control: no-store`이며, demo 포함 여부가 filename과 `X-WalkSafe-Demo-Filter`에 남는다.

추가 옵션:

- `redacted=true`: 좌표를 낮은 정밀도로 반올림하고 `image_path`를 비운다.
- `manifest=true`: JSON export에 필터, 건수, `rows_sha256`를 포함한다.
- `aggregate=grid`: GeoJSON에서 외부 지도 SDK 없이 grid cluster FeatureCollection을 만든다.
- `bom=true`: CSV Excel 호환 UTF-8 BOM을 붙인다.

Export 용도:

- 내부 운영 검수 목록 공유
- 중복/오탐 정리
- 기관 제출 전 후보 묶음 생성
- 발표/보고용 신고 운영 샘플 정리

Export는 “기관 자동 전송”이 아니다.

## 공공기관 제출 전 검수 흐름

권장 흐름:

```text
앱 자동/음성 신고 저장
  -> 관리자 화면에서 v2 필터로 후보 확인
  -> 이미지/위치/클래스/중복 검수
  -> 필요한 건 reviewed 상태로 표시
  -> /reports/export?demo_filter=exclude_fake&redacted=true CSV/JSON/GeoJSON 생성
  -> 운영자가 기관별 양식/채널에 맞춰 제출
```

직접 자동 제출을 보류하는 이유:

- 기관별 공식 접수 권한/API와 제출 양식 확인이 필요하다.
- 오탐이 그대로 민원으로 접수되면 기관 업무 부담과 서비스 신뢰도 문제가 생긴다.
- 위치정보/이미지/신고자 정보 제공 동의와 보존 정책이 먼저 정해져야 한다.

자세한 정책은 `docs/walksafe-v2/public_agency_submission_policy.md`를 따른다.

## 중복 신고 기준

현재 중복 후보 기준:

| 항목 | 값 |
|---|---|
| 클래스 | 같은 `class_name` |
| 위치 | 기본 반경 `25m` |
| 시간 | `captured_at` 전후 `10분` |
| 위치 필수 여부 | `gps`가 있어야 후보 검색 가능 |

중복 후보는 자동 삭제나 자동 병합을 의미하지 않는다.

- 신고 생성 전: 비슷한 신고가 있음을 사용자에게 표시할 수 있다.
- 신고 생성 후: `duplicate_report_ids`가 있으면 운영자 화면에서 함께 보여준다.
- 상태 변경: 중복이라고 판단되면 운영자가 `resolved`로 바꿀 수 있다.

## 위치 품질

`location_quality`는 운영자 화면에서 신고 우선순위를 판단하는 보조 정보다.

| 값 | 기준 |
|---|---|
| `missing` | 위치 없음 |
| `low` | `accuracy_m` 없음 또는 50m 초과 |
| `medium` | 15m 초과 50m 이하 |
| `high` | 15m 이하 |

## 검토 플래그

`review_flags`는 신고를 막는 값이 아니라 운영자가 주의할 이유다.

| 값 | 의미 |
|---|---|
| `fake_source` | fake detector에서 생성됨 |
| `low_confidence` | confidence가 0.7 미만 |
| `missing_location` | GPS 없음 |
| `low_location_accuracy` | 위치 정확도 낮음 |
| `missing_heading` | 방향 정보 없음 |

## Fake 신고 처리

현재 `/detect/v2`는 fake 기본값이며, `yolo`/`real` provider 연결 구조가 준비되어 있다. 최종 운영 checkpoint와 threshold는 모델 학습/선정 완료 후 확정해야 한다.

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
- 실제 공공기관 자동 제출 근거로 사용

최종 발표나 보고서에서 fake 데이터를 사용할 경우 “모델 미확정 또는 fake contract 기반 통합 테스트 데이터”로 명시한다. 학습 수치를 넣는 경우에도 “마지막 확인 기준”과 확인 날짜를 함께 적는다.
