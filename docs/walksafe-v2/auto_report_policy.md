# V2 automatic report and safety alert policy

- 기준일: 2026-07-02 KST
- 2026-07-11 구현 정합 보정: 서로 다른 3 camera frame AND 700ms, 동일 snapshot, session cooldown, aria-live/haptic, server trigger/provenance 검증 반영
- 구현 위치: `apps/web/lib/auto-report-v2.ts`, `apps/web/app/_walksafe/hooks/useAutoReportV2.ts`, `backend/app/services/report_policy.py`

## 현재 우선순위 보정

- 주 사용자 앱 경로는 Web/PWA다.
- Android native는 ARCore/depth/TFLite 실험·검증 보조 경로다. Web/PWA 브라우저·Release evidence와 Android Device evidence는 서로 대체하지 않는다.
- Android report upload, TTS/haptic, navigation 연결은 bbox/depth 좌표 정합 gate 이후 진행한다.

## 1. 핵심 원칙

- 시각장애인/저시력 사용자는 화면 신고 버튼보다 자동 감지, 음성 명령, TTS/진동 중심으로 앱을 사용한다.
- 신고 대상과 사용자 경고 대상은 분리한다.
- 시설물 손상은 자동 신고한다.
- 사용자가 들어야 하는 TTS는 신고 bookkeeping보다 즉시 보행 위험과 길 안내가 우선이다.

## 2. 자동 신고 대상

| model_key | class_name | 처리 |
|---|---|---|
| `unified_walksafe` | `damaged_tactile_block` | 자동 신고 대상 |
| `custom_tactile` | `damaged_tactile_block` | 자동 신고 대상 |

Backend/report 운영 threshold는 `damaged_tactile_block=0.50`으로 시작한다. 이 값은 test split image-level presence sweep에서 F1/recall 균형이 가장 좋았기 때문이다. 어드민 검수에서 오탐 부담이 크면 `0.60`, 필요 시 `0.75`로 올린다. Android TFLite debug threshold는 `apps/android/app/src/main/assets/model-config/two_model_runtime.json`을 따르며, backend 운영 threshold와 같다고 가정하지 않는다.

아래는 현재 신고 대상이 아니다.

- `tactile_damage_area` — 손상 부위 bbox 보조 정보이며 신고 기준은 아님
- `normal_tactile_block`
- 모든 `coco_general` 객체
- `unified_walksafe`의 일반 객체/경로·장애물 class
- unknown class

Backend `/reports/v2`도 같은 정책을 강제한다.

자동 신고는 `damaged_tactile_block`, 허용 model, confidence 0.70 이상, GPS accuracy 15m 이내를 요구한다. 같은 model/source/class이며 bbox IoU가 이어지는 후보가 **서로 다른 camera media frame 3개에서 최소 700ms 동안** 관찰돼야 한다. 같은 frozen/중복 frame의 반복 응답은 frame 수를 늘리지 않는다. 음성 요청 신고는 이 자동 안정화·confidence·accuracy·cooldown만 우회하며 대상·server-v2·GPS·동일 snapshot 조건은 우회하지 않는다.

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
| `waiting_location` | 자동 신고 대상은 있으나 GPS 없음 | aria-live + 필요 시 실패 haptic |
| `not_reportable` | 신고 대상 없음 | 없음 |
| `cooldown` | 최근 같은 class/근사 위치 신고됨 | 없음 |
| `sending` | 전송 중 | aria-live 상태 |
| `sent` | 저장 완료 | 5초 aria-live + 성공 haptic, 자동 TTS 없음 |
| `failed` | 실패 | 5초 aria-live + 실패 haptic, 자동 TTS 없음 |

## 5. 음성 요청 신고

- `create_report` intent는 현재 v2 detections 중 신고 가능한 `damaged_tactile_block`을 찾는다.
- 음성 요청 신고는 자동 신고보다 우선한다.
- PWA `server-v2` 경로에서는 `/reports/v2`로 저장한다.
- PWA `fake-v2` UI에서는 실제 저장하지 않고 데모 안내만 한다. backend fake-mode는 API contract/test 용도로 저장 동작을 검증할 수 있으므로 PWA demo 저장 정책과 구분한다.
- 음성 요청 신고는 자동 cooldown을 우회한다.
- 자동 cooldown은 sessionStorage에 저장해 같은 tab reload 뒤에도 유지한다.
- 음성 요청은 3초 이내 최신 분석 snapshot에서 bbox를 만든 동일 image/`captured_at`을 사용하며 오래된 snapshot은 거부한다.
- 완료/실패는 짧게 TTS로 안내한다.
- 신고 대상이 없으면 “현재 신고할 타일 손상이 없습니다”로 안내한다.
- 위치가 확인되지 않으면 “위치 확인 후 다시 신고해 주세요”로 안내하고 저장하지 않는다.

## 6. 위치와 중복

- 자동 신고(`trigger: auto`)는 GPS가 없으면 보내지 않는다.
- 음성 요청 신고(`trigger: voice`)도 GPS가 없으면 보내지 않는다.
- 제품 컨셉상 GPS는 거의 항상 잡히는 것으로 가정한다. 다만 GPS drift는 가능하므로 `accuracy_m`, 중복 후보, 어드민 지도 검수를 함께 본다.
- 자동/음성 신고 중복 후보는 같은 `class_name`, 위치 반경 `10m`, `captured_at` 전후 `1분` 기준으로 본다.
- 중복 후보여도 저장은 유지하고 `duplicate_candidate` tag와 후보 ID를 남긴다.
- 사용자가 명시적으로 신고를 요청했는데 중복 후보이면 "이미 신고가 된 상태입니다"라고 TTS로 안내한다.
- Backend 중복 후보는 class/location/time 기준이며, GPS가 없으면 중복 후보를 만들지 않는다.
- field duplicate-check 응답은 report ID 목록과 count만 포함한다.

## 7. Backend 저장

- API: `POST /reports/v2`
- DB: 기존 `reports` 테이블 재사용
- `source`: `source_model`이 `fake`로 시작하면 `fake`, 아니면 `server`
- 원본 v2 metadata는 JSON payload로 보존한다.
- backend가 `trigger`/`auto_reported` 조합과 `source_model` identifier를 검증하고 provenance/actor를 server 값으로 덮어쓴다.
- 운영 조회는 `GET /reports`에서 `class_name`, `model_key`, `trigger`, `auto_reported`로 필터링한다.
- 신규 migration은 추가하지 않았다.

## 8. 남은 결정·외부 검증

- Android 자동 신고 위치 기반 duplicate key와 10m/1분 반영
- Android voice/manual 중복 TTS 안내
- 실제 운영 named account/token/session secret 구성과 외부 IdP reporter 정책
- 실폰 microphone→STT→동일 snapshot report 저장 evidence
- 실제 폰 camera→server sampled 지연/FPS와 frozen frame fail-safe evidence
- 음성 “신고 취소” 정책
- `tactile_damage_area`를 계속 보조 표시로 둘지, inference 응답에서 숨길지 여부
- 지면 위험 class를 손상 점자블록 신고와 별도로 분리할지 여부
