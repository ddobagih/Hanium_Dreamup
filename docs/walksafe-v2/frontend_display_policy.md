# Frontend v2 display, warning, and voice policy

- 기준일: 2026-05-23 KST
- 구현 위치: `apps/web/app/_walksafe/`, `apps/web/lib/*-v2.ts`, `apps/web/types/inference-v2.ts`

## 1. 지원 모드

| mode | 동작 |
|---|---|
| `fake` | 기존 v1 브라우저 fake detector |
| `server` | 기존 v1 backend `/detect` 호출 |
| `fake-v2` | 브라우저에서 two-model fake detections 생성. 실제 신고 저장 없음 |
| `server-v2` | backend `/detect/v2` 호출, tactile damage는 `/reports/v2` 저장 |

## 2. 화면 구조

- `CameraSurface`: 카메라 preview와 bbox overlay.
- `AssistPanel`: 위험/탐지/신고/위치/음성/길안내 상태 표시.
- v2 detection은 tactile/general 채널을 분리해 label한다.
- 화면은 보조 정보이며, 사용자는 주로 TTS/진동/음성 명령으로 앱을 사용한다.

## 3. 표시 우선순위

| 대상 | 화면 | TTS/진동 | 신고 |
|---|---|---|---|
| `damaged_tactile_block` | 파손 점자블록 표시 | 기본 TTS 없음 | 자동 신고 대상 |
| `tactile_damage_area` | 파손 영역 보조 표시 | 기본 TTS 없음 | 신고 안 함 |
| `normal_tactile_block` | 정상 점자블록 표시 가능 | 경고 없음 | 신고 안 함 |
| COCO/general 객체 | 보조 표시 가능 | risk evaluator가 위험으로 판단할 때만 경고 | 신고 안 함 |

타일/점자블록 손상은 “시설물 신고” 성격이므로 사용자가 매번 들을 경고로 처리하지 않는다. 손상 점자블록은 자동 신고만 수행하고 사용자 TTS는 기본으로 내보내지 않는다. `낙상 위험` 표현은 현재 MVP에서 별도 경고 카테고리로 쓰지 않는다.

## 4. 위험 평가 계층

위험 판단은 `apps/web/app/_walksafe/risk-evaluator.ts`에서 detection을 직접 TTS로 바꾸지 않고 `RiskDecision`으로 변환한다.

핵심 출력:

| field | 의미 |
|---|---|
| `alertable` | 사용자에게 지금 경고할지 |
| `reportable` | 신고 대상인지 |
| `risk_type` | `report_only_damage`, `path_obstacle`, `approaching_object`, `blocking_object`, `display_only` 등 |
| `risk_level` | `none`, `low`, `medium`, `high` |
| `recommended_message` | 말할 필요가 있을 때의 짧은 한국어 문구 |

현재 v2 general 객체는 기본 `display_only`이며, `useTwoModelRiskHistory`가 같은 객체 후보의 bbox history로 `stable_frames`, `stable_ms`, 접근 여부, area growth/간이 TTC 신호를 만든다. 이 tracking context가 접근/충돌 가능성을 보일 때만 `approaching_object` 경고로 올리고, 향후 heading/depth/segmentation/path intersection 신호는 같은 `RiskEvaluationContext`에 연결한다. 사용자에게 들리는 위험 경고는 장애물, 보행자에게 접근하는 객체, 경로 차단 상황 중심으로 제한한다.

`damaged_tactile_block`이 primary로 선택된 경우에도 secondary general 객체의 tracking risk는 별도로 평가한다. 따라서 손상 점자블록은 조용히 자동 신고하면서, 동시에 접근/충돌 위험이 있는 사람/차량/자전거 등은 TTS/진동 경고로 올릴 수 있다.

## 5. 음성/TTS 정책

- 자동 신고 완료/실패/중복은 기본적으로 TTS로 말하지 않는다.
- 사용자가 음성으로 신고를 요청한 경우에는 완료/실패를 짧게 말한다.
- `repeat_last` intent는 마지막 상태를 다시 말한다.
- `get_current_location`, 목적지 설정, 길 안내 시작은 화면 없이 조작 가능한 흐름으로 유지한다.
- TMAP 보행 길안내 prompt는 위험 경고보다 낮은 우선순위로 말한다.
- 길안내 음성은 거리(m) 단독 표현보다 시간/보폭 기반 표현을 우선한다. 예: “10초 뒤 좌회전 준비”, “약 15보 앞”, “지금 좌회전하세요”.
- 보폭 기본값은 임시값으로 두고 개인 보폭/키/캘리브레이션 기반 개인화 여지를 남긴다. GPS/센서 기반 속도 추정은 오차가 있어 보폭 안내는 “약”으로 표현한다.
- 길안내 중 `normal_tactile_block`이 감지되면 위험이 없을 때 “점자블록을 따라 이동하세요” 보조 안내를 할 수 있다.
- 보행 중 긴 문장, 반복 알림, 확인 모달은 피한다.

## 6. 접근성 원칙

- 색상만으로 의미를 전달하지 않고 텍스트 label을 함께 둔다.
- 화면 버튼은 보조 수단으로 두고, 핵심 흐름은 자동 감지/음성/진동이다.
- 한 순간에 여러 객체를 모두 읽지 않는다.
- 일반 객체 존재 알림은 collision/path risk가 있을 때만 제한적으로 사용한다.

## 7. 관리자 화면

- `apps/web/app/admin/page.tsx`는 v1/v2 신고를 같은 목록에서 운영한다.
- 기본 상태/위험 유형/source/날짜/위치 필터에 v2 운영 필터를 추가했다.
- v2 운영 필터: `model_key`, `trigger`, `auto_reported`.
- 목록 행에는 v2 metadata 요약을 짧게 표시한다.
- 현재 필터 조건을 `/reports/export` CSV 링크로 내보낼 수 있다.
- `/detect/v2/health`를 1회 조회해 mode/status/reason을 작게 표시한다.

## 8. 구현 파일

- Config: `apps/web/app/_walksafe/config.ts`
- Risk: `apps/web/app/_walksafe/risk-evaluator.ts`
- Feedback: `apps/web/app/_walksafe/feedback.ts`, `apps/web/app/_walksafe/hooks/useRiskFeedback.ts`, `apps/web/app/_walksafe/hooks/useTwoModelRiskHistory.ts`
- Detection: `apps/web/app/_walksafe/hooks/useDetectionV1.ts`, `apps/web/app/_walksafe/hooks/useDetectionV2.ts`
- Auto report: `apps/web/app/_walksafe/hooks/useAutoReportV2.ts`, `apps/web/lib/auto-report-v2.ts`, `apps/web/lib/report-api-v2.ts`
- Voice: `apps/web/app/_walksafe/hooks/useVoiceCommands.ts`
- Navigation: `apps/web/app/_walksafe/hooks/useNavigationGuidance.ts`, `apps/web/lib/navigation-api.ts`
- Admin reports: `apps/web/app/admin/page.tsx`, `apps/web/lib/report-api.ts`
- Policy smoke: `apps/web/tests/risk-evaluator-policy.test.ts`, `apps/web/tests/auto-report-v2-policy.test.ts`, `scripts/check_frontend_risk_evaluator_policy_20260523.sh`
