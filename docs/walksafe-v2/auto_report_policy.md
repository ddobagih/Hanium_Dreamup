# V2 automatic report and safety alert policy

- 기준일: 2026-05-22 KST
- 구현 위치: `apps/web/lib/auto-report-v2.ts`, `apps/web/app/_walksafe/hooks/useAutoReportV2.ts`, `backend/app/services/report_policy.py`

## 1. 핵심 원칙

- 시각장애인/저시력 사용자는 화면 신고 버튼보다 자동 감지, 음성 명령, TTS/진동 중심으로 앱을 사용한다.
- 신고 대상과 사용자 경고 대상은 분리한다.
- 시설물 손상은 자동 신고한다.
- 사용자가 들어야 하는 TTS는 신고 bookkeeping보다 즉시 보행 위험과 길 안내가 우선이다.

## 2. 자동 신고 대상

| model_key | class_name | 처리 |
|---|---|---|
| `custom_tactile` | `tactile_damage_area` | 자동 신고 대상 |
| `custom_tactile` | `damaged_tactile_block` | 자동 신고 대상 |

아래는 현재 신고 대상이 아니다.

- `normal_tactile_block`
- 모든 `coco_general` 객체
- unknown class

Backend `/reports/v2`도 같은 정책을 강제한다.

## 3. 사용자 경고 대상

사용자 경고는 “객체가 보였는가”가 아니라 “지금 보행자가 피하거나 대비해야 하는가”로 판단한다.

경고 예시:

- 보행 경로를 막는 장애물
- 보행자에게 빠르게 다가와 충돌 가능성이 있는 객체
- 땅 꺼짐, 포트홀, 단차, 돌출 타일처럼 걸림/추락 위험이 있는 지면 위험

경고하지 않는 예시:

- 자동 신고만 필요한 타일/점자블록 손상
- 정상 점자블록
- 경로와 무관하거나 옆으로 지나가는 일반 객체

## 4. 자동 신고 상태

Frontend v2 상태:

| 상태 | 의미 | TTS |
|---|---|---|
| `idle` | 대기 | 없음 |
| `waiting_location` | 자동 신고 대상은 있으나 GPS 없음 | 없음 |
| `not_reportable` | 신고 대상 없음 | 없음 |
| `cooldown` | 최근 같은 class/근사 위치 신고됨 | 없음 |
| `sending` | 전송 중 | 자동 신고는 없음 |
| `sent` | 저장 완료 | 자동 신고는 없음 |
| `failed` | 실패 | 자동 신고는 반복 안내 없음 |

## 5. 음성 요청 신고

- `create_report` intent는 현재 v2 detections 중 신고 가능한 tactile damage를 찾는다.
- 음성 요청 신고는 자동 신고보다 우선한다.
- `server-v2`에서 `/reports/v2`로 저장한다.
- `fake-v2`에서는 실제 저장하지 않고 데모 안내만 한다.
- 음성 요청 신고는 자동 cooldown을 우회한다.
- 완료/실패는 짧게 TTS로 안내한다.
- 신고 대상이 없으면 “현재 신고할 타일 손상이 없습니다”로 안내한다.

## 6. 위치와 중복

- 자동 신고(`trigger: auto`)는 GPS가 없으면 보내지 않는다.
- 음성 요청 신고(`trigger: voice`)는 사용자가 명시 요청한 흐름이므로 GPS가 없어도 저장을 시도할 수 있다.
- Frontend 자동 신고 cooldown은 class + 위도/경도 소수점 4자리 기준, 기본 10분이다.
- Backend 중복 후보는 class/location/time 기준이며, GPS가 없으면 중복 후보를 만들지 않는다.

## 7. Backend 저장

- API: `POST /reports/v2`
- DB: 기존 `reports` 테이블 재사용
- `source`: `source_model`이 `fake`로 시작하면 `fake`, 아니면 `server`
- 원본 v2 metadata는 JSON payload로 보존한다.
- 신규 migration은 추가하지 않았다.

## 8. 남은 결정

- 자동 신고 cooldown 현장값 조정
- 음성 “신고 취소” 정책
- v2 report query/filter 확장
- 실제 YOLO adapter 연결 후 threshold 조정
- 지면 위험 class를 tactile damage와 별도로 분리할지 여부
