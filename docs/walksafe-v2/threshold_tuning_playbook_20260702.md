# WalkSafe threshold tuning playbook - 2026-07-02

목적: 사용자가 "보수적으로 시작하고, 테스트 후 조치 방향을 물어보면 결정할 수 있게 문서화"하라고 결정한 P-04 정책을 관리한다.

이 문서는 threshold 값 자체를 최종 확정하지 않는다. 테스트 결과를 보고 어떤 조치를 선택할지 판단하기 위한 기준표다.

## 현재 원칙

- 자동 신고 대상은 `damaged_tactile_block`만이다.
- 손상 점자블록은 report-only이며 사용자 보행 안내 TTS/진동을 내지 않는다.
- GPS가 없으면 report를 저장하지 않는다.
- Android/backend threshold drift는 숨기지 않고 `threshold_used`, `source_model`, `model_key`로 trace한다.
- `check_android_tflite_contract ok=true`는 unified asset 완료가 아니다.

## 초기 운영 철학

| 항목 | 방향 |
|---|---|
| 자동 신고 | precision 우선, 중복/오탐을 보수적으로 줄인다. |
| 위험 알림 | TTC 3초 STOP, 10초 WARNING, 30초 AWARE 후보를 테스트한다. |
| 진동 | STOP에서만 사용한다. |
| 로그인 | 주요 기능은 로그인 필수로 둔다. |
| 테스트 전 완료 주장 | 금지한다. 정지 smoke, offline replay, field walking evidence를 분리한다. |

## 조정 대상

| 정책 | 현재 후보 | 조정할 수 있는 값 | 바꾸는 이유 |
|---|---:|---|---|
| `damaged_tactile_block` report threshold | Android config 기준 0.35, 보수 후보 0.60~0.75 | 0.35 / 0.50 / 0.60 / 0.75 | 오탐 신고가 많거나 손상 누락이 많을 때 |
| STOP TTC | 3초 | 2~4초 | 즉시 정지 알림이 너무 빠르거나 늦을 때 |
| WARNING TTC | 10초 | 6~12초 | 경고가 너무 자주 나오거나 부족할 때 |
| AWARE TTC | 30초 | 15~30초 | 먼 위험을 내부 상태로만 볼지 판단할 때 |
| 자동 신고 중복 반경 | 10m 확정 | 5m / 10m / 25m | 같은 손상 반복 신고 또는 다른 손상 누락을 조절할 때 |
| 자동 신고 중복 시간창 | 1분 확정 | 1분 / 10분 / 30분 | 같은 장소 반복 업로드를 줄일 때 |

## 테스트별 해석

| 테스트 결과 | 해석 | 추천 조치 후보 |
|---|---|---|
| 같은 손상 지점이 반복 신고됨 | client cooldown이 위치 기반이 아니거나 시간창이 짧음 | Android cooldown을 위치 기반으로 바꾸고 backend duplicate window와 맞춘다. |
| 중복 후보인데 실제 다른 손상일 수 있음 | 위치 기반 duplicate는 완전한 동일성 판정이 아님 | 저장은 유지하고 duplicate tag를 붙여 운영자가 검수한다. |
| 손상 점자블록 오탐이 운영자 리스트에 많이 들어옴 | report threshold가 낮거나 gate가 약함 | threshold를 0.60/0.75로 올리고, coordinate/depth gate 조건을 강화한다. |
| 실제 손상 누락이 많음 | threshold가 높거나 bbox/depth gate가 과도함 | threshold를 낮추되 `performance_excluded`와 review flag를 유지한다. |
| STOP 진동이 너무 자주 울림 | TTC/거리 STOP 조건이 민감함 | STOP TTC를 2초 쪽으로 줄이고, WARNING은 TTS만 유지한다. |
| WARNING TTS가 보행을 방해함 | 10초 bucket이 너무 넓거나 rate limit이 짧음 | WARNING TTC를 줄이거나 rate limit을 늘린다. |
| 30초 안내가 산만함 | AWARE를 사용자 발화로 쓰고 있음 | 30초 bucket은 내부 상태/화면/debug로만 유지한다. |
| AIHub189 offline 결과는 좋은데 실기기 depth가 흔들림 | offline ZED reference와 ARCore runtime이 다름 | ARCore bbox-depth 좌표 정합과 field device evidence를 별도로 수집한다. |

## 질문을 받을 때 답변할 형식

사용자가 "이 테스트 결과면 어떻게 조치해?"라고 물으면 다음 순서로 답한다.

1. 어떤 증거 등급인지 먼저 말한다: offline reference, Android unit, 정지 smoke, field walking, PostGIS.
2. false positive, false negative, duplicate, UX fatigue 중 어디가 문제인지 분류한다.
3. 위 표에서 조정 후보를 하나만 추천한다.
4. 바꾸면 어떤 부작용이 있는지 같이 말한다.
5. 재검증 명령이나 수동 체크를 함께 제시한다.

## 금지 표현

- AIHub189 offline reference를 Android ARCore 최종 PASS로 쓰지 않는다.
- unified TFLite asset이 없는데 unified model 완료라고 쓰지 않는다.
- 정지 실기기 smoke를 field walking PASS로 쓰지 않는다.
- fake/demo 결과를 실제 안전 판단 근거로 쓰지 않는다.

## 현재 watchlist

- Android 자동 신고 cooldown은 위치 기반이 아니라 track/class/source 기준 10초다. 사용자 확정값은 10m/1분이다.
- 위치 기반 duplicate 최종 판정은 backend의 10m/1분 기준으로 수행하고, Android client cooldown은 짧은 반복 업로드 억제용으로만 본다.
- Android 명시적 voice 신고 경로와 "위치 정보가 필요합니다" TTS 안내는 native `SpeechRecognizer`/explicit report route에 연결됐다. 실폰 mic/TTS 체감 PASS는 후속 Device evidence다.
- Android/backend duplicate tag와 "이미 신고가 된 상태입니다" TTS 안내는 연결됐다. 실제 중복/비중복 판단 품질은 운영자 검수와 현장 데이터로 조정한다.
- route progress beep와 별도 volume 설정은 구현됐다. 현장 보행 중 피로도와 위험/길안내 TTS 충돌은 후속 Device evidence로 확인한다.
- Android local user id gate와 optional `/reports/v2` metadata/export 계약은 구현했다. 이 값은 서버 인증 subject가 아니며 Web은 현재 전송하지 않는다. 로그인/auth/RBAC/rate-limit/upload 접근 제어는 출시 lane이다.
- retention은 dry-run script만 있고 실제 6개월 자동 삭제 job은 아직 없다.
