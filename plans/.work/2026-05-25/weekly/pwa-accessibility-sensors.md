# Hanium Dreamup / WalkSafe Assist - PWA/Accessibility/Sensors weekly lane note (2026-W22)

## 이번 주 목표 후보

- `plans/weekly/2026-W22.md`는 현재 없음. 이번 note는 `product/*.md`, `docs/current_status.md`, `plans/weekly/2026-W21.md`, `plans/daily/2026-05-25.md`, `plans/daily/2026-05-26.md`, 최근 daylog, `/home/ddobagi/PM/status/*`를 근거로 작성한다.
- 1순위: 5/25 PM feature batch의 PWA 관련 slice를 “검증 대기”에서 “인수 가능/보류”로 재분류한다.
  - 대상: Admin GeoJSON export URL, mock route navigation cue fixture, motion ROI fixture/helper, voice intent schema.
  - 주의: PM verify는 sandbox mount quota 오류로 `blocked`, `push_ready=False`이므로 완료로 쓰지 않는다.
- 2순위: 위험 TTS와 길안내 TTS 중재를 제품 정책으로 고정한다.
  - `riskActive`이면 navigation prompt 억제.
  - `normal_tactile_block` follow 안내는 위험이 없을 때만 보조 안내.
  - `damaged_tactile_block`은 기본 TTS 없이 조용한 자동 신고.
- 3순위: PWA 녹음/음성 신고 fallback slice를 추가한다.
  - 권한 거부, STT timeout, 서버 미연결, 빈 녹음, low confidence를 서로 다른 UI/TTS/진동 상태로 분리.
  - `create_report` intent는 `/reports/v2`에 `trigger=voice`, `auto_reported=false`로 연결하되 실폰 mic E2E와 구분.
- 4순위: motion ROI는 3~5초 이동 궤적 PoC로만 유지한다.
  - heading, speed, bbox center fixture로 ROI 내/외 판정.
  - 실제 사용자 TTS/진동 primary 판단에는 연결하지 않는다.
- 5순위: Android 목걸이 착용, TalkBack, install/offline, 카메라/GPS/heading/TTS/진동 smoke 준비.
  - 장비가 없으면 Device E2E는 `BLOCKED`로 두고 정적 접근성/fixture로 대체한다.

## 날짜별/단계별 체크리스트

- 2026-05-25 월: 기준선 고정
  - [ ] 현재 checkout, clean worktree, PM feature worktree 상태 분리 → 검증: `git status`, ahead/behind, PM commit `82ec532`, verify blocked 기록.
  - [ ] `plans/weekly/2026-W22.md`, 프로젝트 내부 `plans/features`, `plans/verify/ready`, `PM/` 부재 기록 → 검증: 외부 `/home/ddobagi/PM/...` 산출물과 혼동 방지.
  - [ ] 이번 주 PWA source-of-truth 확정 → 검증: `docs/current_status.md`, `docs/walksafe-v2/frontend_display_policy.md`, `navigation_integration_policy.md` 기준 반영.

- 2026-05-26 화: 5/25 feature batch 재검증
  - [ ] navigation cue fixture 재실행 → 검증: `prepare10`, `soon3`, `now`, risk TTS보다 낮은 priority.
  - [ ] motion ROI fixture 재실행 → 검증: heading, speed, 3초/5초 horizon, bbox center inside/outside.
  - [ ] Admin export URL 회귀 → 검증: CSV/JSON/GeoJSON format, 필터 보존, `limit` 제외.
  - [ ] PWA 정적 회귀 → 검증: `npm run lint`, `npm run typecheck`, `npm run build`, `node --check apps/web/public/sw.js`.

- 2026-05-27 수: PWA 음성 신고/fallback slice
  - [ ] 녹음 fallback 상태 세분화 → 검증: permission denied, timeout, server down, empty, low confidence fixture.
  - [ ] `create_report` PWA 연결 확인 → 검증: `trigger=voice`, `auto_reported=false`, 자동 cooldown 우회, GPS 없음 저장 금지.
  - [ ] 접근성 문구 점검 → 검증: `aria-live`, `aria-pressed`, `aria-describedby`, disabled reason, bbox `aria-hidden`.

- 2026-05-28 목: 위험/길안내/ROI 정책 고정
  - [ ] `riskActive`와 navigation prompt 중재 회귀 → 검증: risk policy smoke와 navigation guidance smoke.
  - [ ] `normal_tactile_block` follow 안내 조건 확인 → 검증: 위험 없음 조건에서만 낮은 우선순위 prompt.
  - [ ] motion ROI seam 유지 범위 확인 → 검증: ROI 밖 general 객체가 새 TTS/진동을 직접 만들지 않음.

- 2026-05-29 금: Device E2E 가능 시 실행
  - [ ] Android 목걸이 smoke → 검증: 기기명, Chrome, ADB reverse/LAN, 권한, 후면 카메라 각도, GPS accuracy, heading, TTS/진동.
  - [ ] PWA install/offline/TalkBack → 검증: standalone 실행, offline shell fallback, TalkBack 읽기 순서.
  - [ ] 불가 시 대체 검증 → 검증: 정적 접근성 체크와 fixture smoke만 남기고 Device E2E `BLOCKED`.

- 2026-05-30 토: 통합 trace 보조
  - [ ] PWA/Admin 관점의 report ID trace 확인 → 검증: detect/report/admin/status/export가 같은 report ID로 연결되는지 Integration lane과 대조.
  - [ ] server-v2/fake-v2 표시 분리 확인 → 검증: fake는 데모, server는 smoke 근거로만 기록.
  - [ ] Admin 운영자 화면 점검 → 검증: v2 metadata, model_key, trigger, auto_reported, GeoJSON export 링크.

- 2026-05-31 일: 주간 정리
  - [ ] PASS/BLOCKED/PENDING 정리 → 검증: Static, fixture, Integration, Device E2E, field 근거 분리.
  - [ ] 다음 주 이관 → 검증: Android/voice/PostGIS/TMAP/ROI 확인 필요와 safe alternative 표기.
  - [ ] daylog/실행 문서 반영은 실제 수정·검증을 수행한 실행 에이전트가 작성.

## 검증 계획

- PWA 기본 정적 검증:
  - `node --check apps/web/public/sw.js`
  - `cd apps/web && npm run lint && npm run typecheck && npm run build`
- 정책/fixture 검증:
  - `bash scripts/check_frontend_navigation_guidance_policy_20260524.sh`
  - `bash scripts/check_frontend_risk_evaluator_policy_20260523.sh`
  - PM feature worktree 또는 인수 후: `node apps/web/scripts/check-navigation-guidance-fixture.mjs`
  - PM feature worktree 또는 인수 후: `node apps/web/scripts/check-motion-roi-fixture.mjs`
- Admin export URL 검증:
  - `reportExportUrl`가 `format=csv/json/geojson`과 필터를 보존하고 `limit`를 제외하는지 테스트.
- 음성 신고/fallback 검증:
  - mic E2E 전에는 mock `VoiceSttResponse`/direct handler fixture만 사용.
  - 실폰 mic를 실행하지 않았으면 브라우저/실폰 STT 완료로 쓰지 않음.
- Device E2E:
  - Android Chrome + ADB reverse 기준으로 카메라, GPS, heading, TTS, 진동, TalkBack, install/offline을 수동 기록.
  - headless/static 결과를 field 성능으로 대체하지 않음.

## 리스크/확인 필요

- 현재 원본 checkout은 upstream보다 10커밋 behind이고 dirty/untracked 산출물이 많다. `git add .`, reset, 강제 push 금지.
- PM feature commit `82ec532`은 생성됐지만 verify가 quota 오류로 blocked다. push-ready나 완료로 계산하지 않는다.
- Android/ADB/TalkBack/목걸이/실폰 mic 환경은 확인 필요다.
  - safe alternative: 정적 접근성 점검, fixture, mock route, direct handler.
- PostGIS/운영 DB 접근이 없으면 report runtime PASS 금지.
  - safe alternative: Admin URL builder, serializer fixture, synthetic trace.
- TMAP key/약관/장기 저장은 확인 필요다.
  - safe alternative: offline timing fixture와 mock route.
- motion ROI는 field 검증 전이다.
  - safe alternative: JSON fixture + pure helper만 유지하고 TTS/진동 primary 판단에는 미연결.
- fake-v2와 server-v2 근거를 섞지 않는다. fake는 UI/API 데모 근거이고 안전 성능 근거가 아니다.

## 병렬 에이전트 활용 메모

- 이번 lane note 작성에는 별도 하위 에이전트를 새로 사용하지 않았다. 문서/상태 파일 확인은 병렬 shell 조회로 나눠 수행했다.
- 주간 실행 시에는 병렬화 권장:
  - PWA Fixture/Accessibility Agent: `apps/web/**` navigation, ROI, 접근성, 정적 회귀.
  - Voice/PWA Fallback Agent: 음성 신고 fallback fixture와 intent 연결.
  - Integration Evidence Agent: report ID trace와 Device E2E/BLOCKED 근거 통합.
- 같은 `apps/web/**` 파일을 동시에 수정할 가능성이 있으므로 PWA fixture 작업과 PWA voice fallback 작업은 구현 단계에서는 순차로 진행한다.