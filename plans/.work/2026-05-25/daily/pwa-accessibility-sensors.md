# Hanium Dreamup / WalkSafe Assist - PWA/Accessibility/Sensors lane note (2026-05-26)

## 최근 진행 근거
- 저장소 루트 `AGENTS.md`는 없음. 사용자 제공 AGENTS 지침을 적용한다.
- `product/vision.md`, `product/roadmap.md`, `product/backlog.md`, `product/done-criteria.md`, `product/decisions.md`(2026-05-18 기준): 제품 방향은 화면보다 TTS/진동/스크린리더/음성 명령을 우선하는 착용형 스마트폰 PWA. 운영 모드는 `feature-growth`.
- `docs/current_status.md`(2026-05-24): `apps/web/app/_walksafe/`에 카메라, 센서, 탐지, 음성, 신고, 위험 피드백, TMAP 길안내 hook이 분리되어 있음. Android 실폰/TalkBack/목걸이/실폰 mic는 미검증.
- `docs/walksafe-v2/frontend_display_policy.md`(2026-05-23), `docs/report_operations.md`(2026-05-23): `damaged_tactile_block`은 조용한 자동 신고 대상, `tactile_damage_area`는 보조 표시, general 객체는 접근/충돌/경로 차단 때만 경고.
- `docs/walksafe-v2/navigation_integration_policy.md`(2026-05-24): 기본 길안내 provider는 TMAP, 위험 TTS가 길안내 TTS보다 우선. 목적지 검색/geocoding과 실폰 보폭·TTS 지연 보정은 남음.
- `docs/execution/2026-05-19_pwa_accessibility_sensors.md`: 카메라/센서 재연결, heading 상태 세분화, 신고 접근성 보강. 기록상 `node --check`, lint, typecheck, build, server-mode build PASS.
- `daylog/2026-05-24.md`: TMAP guide point, navigation TTS timing, Admin CSV/JSON/GeoJSON export, detect→report→export trace가 전진. 기록상 frontend lint/typecheck와 navigation/risk policy smoke PASS.
- `daylog/2026-05-25.md`, `/home/ddobagi/PM/status/2026-05-25-features.md`: PM feature worktree commit `82ec532`에 GeoJSON export admin URL, mock route navigation cue fixture, motion ROI fixture/helper, voice intent schema endpoint가 생성됨.
- `/home/ddobagi/PM/status/2026-05-25-verify.md`: Hanium verify는 sandbox mount quota 오류로 `blocked`, `push_ready=False`. 이 결과는 본선 통합 완료나 검증 완료로 계산하면 안 됨.
- `/home/ddobagi/PM/status/2026-05-25-product-audit.md`: Hanium product 품질 점수 91. 보강 포인트는 v2 canonical summary와 legacy detector 용어 drift 정리.
- 프로젝트 내부 `plans/features`, `plans/verify/ready`, `plans/verify/reports`는 현재 파일 없음. `plans/daily/2026-05-26.md`도 없음. PM 산출물은 `/home/ddobagi/PM/...` 외부 경로에 존재.
- 현재 원본 checkout은 upstream보다 10커밋 behind이고 dirty 상태다. 대형 산출물과 미추적 파일이 많으므로 `git add .`, 임의 reset, 강제 push는 금지.

## 내일 목표 후보
- 1순위: 5/25 PM feature worktree의 PWA 관련 신규 slice를 검증 가능한 상태로 재확인한다. 특히 motion ROI fixture/helper와 navigation cue fixture는 verify blocked 해소 전까지 “검증 대기”로 둔다.
- 2순위: motion ROI를 PWA product slice로 정리한다. 3초/5초 horizon, heading, speed, bbox center 판정은 fixture/정적 helper까지만 두고 TTS/진동 사용자 경고에는 연결하지 않는다.
- 3순위: 길안내 TTS와 위험 TTS 우선순위 회귀를 고정한다. `riskActive`이면 navigation prompt가 억제되고, 정상 점자블록 follow 안내는 위험이 없을 때만 동작해야 한다.
- 4순위: `/admin` GeoJSON export UI/URL 회귀를 확인한다. 현재 필터 조건이 CSV/JSON/GeoJSON export URL에 유지되는지 확인한다.
- 5순위: 접근성 정적 점검과 PWA 정적 회귀를 실행한다. Android/ADB가 가능할 때만 TalkBack, install/offline, 목걸이 착용 field smoke를 별도 Device E2E로 기록한다.

## 상세 체크리스트 초안
- [ ] 시작 gate 기록 → 검증: `git status --short --branch --untracked-files=all`, ahead/behind, PM feature/verify 상태, `plans/features`, `plans/verify`, `plans/daily/2026-05-26.md` 존재 여부 확인.
- [ ] PM feature worktree 검증 재시도 조건 정리 → 검증: quota 오류 해소 후 `node apps/web/scripts/check-navigation-guidance-fixture.mjs`, `node apps/web/scripts/check-motion-roi-fixture.mjs`, `git diff --check` 재실행 계획 기록.
- [ ] motion ROI fixture 인수 여부 판단 → 검증: heading, speed, 3초/5초 horizon, bbox center inside/outside fixture 4개 이상이 통과해야 하며 Device E2E나 안전 성능 근거로 쓰지 않음.
- [ ] motion ROI와 risk evaluator 연결 범위 제한 → 검증: ROI 결과가 사용자 TTS/진동 primary 판단을 직접 바꾸지 않고, 실험적 context/seam으로만 남는지 확인.
- [ ] navigation cue fixture 회귀 → 검증: `prepare10`, `soon3`, `now` 경계값과 `navigation` priority가 `risk` priority보다 낮음을 확인.
- [ ] 기존 navigation policy smoke 실행 → 검증: `bash scripts/check_frontend_navigation_guidance_policy_20260524.sh`.
- [ ] 기존 risk evaluator policy smoke 실행 → 검증: `bash scripts/check_frontend_risk_evaluator_policy_20260523.sh`.
- [ ] Admin export URL 회귀 → 검증: `reportExportUrl`이 `format=csv/json/geojson`과 `status`, `class_name`, `source`, `model_key`, `trigger`, `auto_reported`, 날짜/반경 필터를 유지하고 `limit`는 제외하는지 확인.
- [ ] 접근성 정적 점검 → 검증: `aria-live`, `aria-pressed`, `aria-describedby`, 신고 disabled reason, bbox `aria-hidden`, 주요 버튼 터치 영역, 카메라/센서 재연결과 신고 버튼 혼동 가능성 확인.
- [ ] PWA 정적 회귀 → 검증: `cd apps/web && npm run lint && npm run typecheck && npm run build`, `node --check apps/web/public/sw.js`.
- [ ] Android 목걸이 smoke 가능 시 실행 → 검증: 기기명, Chrome, ADB reverse/LAN, 권한, 후면 카메라 각도, GPS accuracy, heading, TTS/진동, 6초 cooldown, `fake-v2/server-v2` source 분리 기록.
- [ ] PWA install/offline/TalkBack 가능 시 확인 → 검증: standalone 실행, offline shell fallback, TalkBack 읽기 순서와 live region 기록. 미실행 시 BLOCKED.
- [ ] 실행 근거 등급 분리 → 검증: Static, fixture, headless, Integration, Device E2E, field를 구분하고 fake/mock 결과를 안전 성능으로 쓰지 않음.

## 리스크/확인 필요
- 5/25 PM feature batch는 commit은 있으나 verify blocked다. safe alternative는 fixture smoke 재실행과 본선 적용 전 diff 범위 확인이다.
- 원본 checkout은 dirty/behind 상태다. safe alternative는 isolated worktree 또는 파일 단위 적용이며, `git add .`와 reset은 금지한다.
- Android 실폰, ADB, TalkBack, offline, 목걸이 착용 환경은 확인 필요다. safe alternative는 DevTools/정적 접근성 점검과 fixture smoke이며 Device E2E PASS로 쓰지 않는다.
- motion ROI는 실제 센서 field 검증 전까지 사용자 경고에 연결하지 않는다. safe alternative는 JSON/TS fixture와 helper 계산이다.
- TMAP live API와 key/약관은 확인 필요다. safe alternative는 mock route/offline timing fixture이며 secret은 코드나 로그에 남기지 않는다.
- PostGIS/운영 DB, secret, 외부 배포, AWS/S3, Cloud STT/TTS, 지자체 API, destructive QA는 자동 계획에서 직접 실행하지 않는다.

## 병렬 에이전트 활용 메모
- 사용함.
- Explorer 1은 product/README/current status/accessibility/navigation/report 정책을 조사했다. 통합 결론: PWA lane은 TTS/진동/스크린리더 우선, TMAP 기본 provider, fake/field 근거 분리, blocked_env의 safe alternative를 유지해야 한다.
- Explorer 2는 최근 daylog, PWA 실행 기록, PM feature/verify 산출물을 조사했다. 통합 결론: 5/25 신규 slice는 유효하지만 verify blocked라 5/26에는 재검증과 본선 인수 판단이 먼저다.
- 부모 에이전트는 현재 `apps/web` 코드와 PM worktree 차이를 확인했다. 원본 checkout에는 기존 navigation/risk policy smoke가 있고, PM worktree에는 별도 `navigation-guidance.ts`, `useMotionContext.ts`, fixture scripts가 검증 대기 상태로 존재한다.