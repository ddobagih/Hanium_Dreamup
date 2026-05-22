# Frontend v2 display, warning, and voice policy

- 기준일: 2026-05-22 KST
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
- `AssistPanel`: 위험/탐지/신고/위치/음성 상태 표시.
- v2 detection은 tactile/general 채널을 분리해 label한다.
- 화면은 보조 정보이며, 사용자는 주로 TTS/진동/음성 명령으로 앱을 사용한다.

## 3. 표시 우선순위

| 대상 | 화면 | TTS/진동 | 신고 |
|---|---|---|---|
| `tactile_damage_area` | 파손 영역 표시 | 기본 TTS 없음 | 자동 신고 대상 |
| `damaged_tactile_block` | 파손 점자블록 표시 | 기본 TTS 없음 | 자동 신고 대상 |
| `normal_tactile_block` | 정상 점자블록 표시 가능 | 경고 없음 | 신고 안 함 |
| COCO/general 객체 | 보조 표시 가능 | risk evaluator가 위험으로 판단할 때만 경고 | 신고 안 함 |

타일/점자블록 손상은 “시설물 신고” 성격이므로 사용자가 매번 들을 경고로 처리하지 않는다. 단, 별도 지면 위험 판단이 즉시 낙상/걸림 위험으로 판단하면 경고할 수 있다.

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

현재 v2 general 객체는 tracking/path/depth context 없이 기본 `display_only`다. 앞으로 bbox 변화, tracking 안정성, path intersection, depth/IMU/segmentation을 연결하면 접근/차단 위험을 경고로 올릴 수 있다.

## 5. 음성/TTS 정책

- 자동 신고 완료/실패/중복은 기본적으로 TTS로 말하지 않는다.
- 사용자가 음성으로 신고를 요청한 경우에는 완료/실패를 짧게 말한다.
- `repeat_last` intent는 마지막 상태를 다시 말한다.
- `get_current_location`, 목적지 설정, 길 안내 시작은 화면 없이 조작 가능한 흐름으로 유지한다.
- 보행 중 긴 문장, 반복 알림, 확인 모달은 피한다.

## 6. 접근성 원칙

- 색상만으로 의미를 전달하지 않고 텍스트 label을 함께 둔다.
- 화면 버튼은 보조 수단으로 두고, 핵심 흐름은 자동 감지/음성/진동이다.
- 한 순간에 여러 객체를 모두 읽지 않는다.
- 일반 객체 존재 알림은 collision/path risk가 있을 때만 제한적으로 사용한다.

## 7. 구현 파일

- Config: `apps/web/app/_walksafe/config.ts`
- Risk: `apps/web/app/_walksafe/risk-evaluator.ts`
- Feedback: `apps/web/app/_walksafe/feedback.ts`, `apps/web/app/_walksafe/hooks/useRiskFeedback.ts`
- Detection: `apps/web/app/_walksafe/hooks/useDetectionV1.ts`, `apps/web/app/_walksafe/hooks/useDetectionV2.ts`
- Auto report: `apps/web/app/_walksafe/hooks/useAutoReportV2.ts`, `apps/web/lib/auto-report-v2.ts`, `apps/web/lib/report-api-v2.ts`
- Voice: `apps/web/app/_walksafe/hooks/useVoiceCommands.ts`
