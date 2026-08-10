# Frontend v2 display, warning, and voice policy

- 기준일: 2026-06-02 KST
- 2026-07-11 구현 정합 보정: 전체 instance 위험 평가, 안정성/TTC/cooldown과 신고 비시각 피드백 반영
- 2026-07-16 capability 보정: Web 비계량 카메라 보조 경고와 TMAP-only 권한 경계 반영
- 구현 위치: `apps/web/app/_walksafe/`, `apps/web/lib/*-v2.ts`, `apps/web/types/inference-v2.ts`

## 현재 우선순위 보정

- 주 사용자 앱 경로는 Web/PWA다.
- Android native는 ARCore/depth/TFLite 실험·검증 보조 경로다. Web/PWA 브라우저·Release evidence와 Android Device evidence는 서로 대체하지 않는다.
- Web은 `CAMERA_NON_METRIC_ADVISORY`를 제공하되 전역 경로 권한은 TMAP-only로 유지한다. Android의 ARCore 지원·미지원 tier는 Android 설계 문서에서 별도로 관리한다.

## 1. 지원 모드

| mode | 동작 |
|---|---|
| `fake` | 기존 v1 브라우저 fake detector |
| `server` | 기존 v1 backend `/detect` 호출 |
| `fake-v2` | 브라우저에서 unified-v2 fake detections 생성. 실제 신고 저장 없음 |
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
| `normal_tactile_block` | 정상 점자블록 표시 가능 | 기존 risk가 비경고일 때 비계량 low 좌/중앙/우 보조 경고 가능 | 신고 안 함 |
| `crosswalk` | 횡단보도 안내 후보 | 경고가 아니라 경로/주의 안내 후보 | 신고 안 함 |
| `curb_step` | 보도 턱/단차 표시 | 표면 위험 경고 | 신고 안 함 |
| `uneven_sidewalk` | 고르지 않은 보도 표시 | 표면 위험 경고 | 신고 안 함 |
| `e_scooter_obstruction` | 방치 킥보드/PM 장애물 표시 | 경로 장애물 경고 | 신고 안 함 |
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

현재 v2 general 객체는 기본 `display_only`이며, 전체 detection을 서로 다른 camera media frame 간 IoU로 instance별 분리한다. 각 instance의 bbox history로 `stable_frames`, `stable_ms`, 접근 여부, area growth/간이 TTC 신호를 만들고 sensor depth를 포함한 모든 경고에 서로 다른 3 frames **AND** 700ms 안정화를 요구한다. frozen/중복 frame은 이력을 늘리지 않고 stale·server 실패는 탐지 불가로 표시한다. bbox TTC는 휴리스틱으로 표시하되 trusted depth 없이 단독 high/STOP으로 올리지 않는다.

전체 후보 중 risk level/type/distance/confidence가 가장 높은 active 객체를 안내하며, 화면 detail도 그 객체의 confidence/type을 사용한다. 손상 점자블록을 신고하는 동시에 접근/충돌 위험이 있는 사람/차량/자전거 등은 별도 TTS/진동 경고로 올릴 수 있다.

### Web 비계량 카메라 보조 경고

- `CAMERA_NON_METRIC_ADVISORY`는 기존 server-v2 risk evaluator가 alertable하지 않은 후보만 보완한다. 기존 stable bbox/ROI risk warning을 대체하거나 낮추지 않는다.
- 후면 camera·detector·활성 TMAP 안내·foreground session·page visibility·fresh detection이 필수다. Web IMU motion stability는 제공될 때만 추가 gate로 사용하며, IMU 미지원만으로 Web을 제외하지 않는다.
- 같은 후보의 서로 다른 연속 3 frame과 최소 700ms를 모두 확인한 뒤 `low`의 좌/중앙/우 방향만 안내한다.
- 거리(m)·N보·STOP·high·local steering·안전 경로·경로 변경·자동 신고를 만들지 않는다. 조건이 깨지면 `TMAP_ONLY`로 남는다.
- 이 보조 경고는 신고를 만들지 않는다. 기존 명시 동의 server-v2 damaged-report 경로는 그대로 유지한다.
- 동일 심각도 경고는 `3A` bounded sequential handoff로 순차 전달하고, 전달 직전에 tier·lifecycle·freshness·TTL을 다시 확인해 stale action을 폐기한다.
- Web 실폰 Field evidence는 `UNVERIFIED_OPEN`이다.

## 5. 음성/TTS 정책

- 자동 신고 완료/실패는 aria-live와 haptic으로 알리되 기본 TTS로 말하지 않는다.
- 사용자가 음성으로 신고를 요청한 경우에는 완료/실패를 짧게 말한다.
- `repeat_last` intent는 마지막 상태를 다시 말한다.
- `get_current_location`, 목적지 설정·변경·취소, 길 안내 시작·중지와 다음 안내 질의는 화면 없이 조작 가능한 흐름으로 유지한다.
- Web 출력은 browser `speechSynthesis`다. 음성은 위험(`risk`) > 명령 확인·재질문(`interaction`) > TMAP 길안내(`navigation`) > 비계량 보조 경고(`advisory`) 순서이며, 위험은 진행 중 STT 녹음과 부분 업로드를 취소할 수 있다. advisory는 TMAP 길안내를 선점하지 않는다.
- 길안내 음성은 거리(m) 단독 표현보다 시간/보폭 기반 표현을 우선한다. 예: “10초 뒤 좌회전 준비”, “약 15보 앞”, “지금 좌회전하세요”.
- 보폭 기본값은 임시값으로 두고 개인 보폭/키/캘리브레이션 기반 개인화 여지를 남긴다. GPS/센서 기반 속도 추정은 오차가 있어 보폭 안내는 “약”으로 표현한다.
- 길안내 중 `normal_tactile_block`이 TMAP route 방향과 약 3~5초 camera future ROI에 정렬되면 위험이 없을 때 근거리 보조 안내를 할 수 있다. 이를 출발지-목적지 점자블록 route graph로 해석하지 않는다.
- 보행 중 긴 문장, 반복 알림, 확인 모달은 피한다.
- 위험 TTS는 객체별 cooldown, high haptic은 별도 짧은 cooldown을 사용하고 “멈추세요”는 high에만 쓴다.

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
- 현재 필터 조건을 `internal`/`minimum`/`agency` profile로 내보낸다. `agency`는 신고 지점 식별용 정확 좌표 최소 필드다.
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
