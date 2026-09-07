# WalkSafe unattended implementation plan, 2026-09-06

## Scope and constraints

- User authorized research, implementation and tests across the user app, administrator app, position evaluation app, Gateway, Backend and database integration.
- At most seven concurrent agents including the parent. Workers own separate files; only the parent edits the user MainActivity and controls the connected device and live server restarts.
- Preserve existing users, credentials, encryption keys, database contents and consent selections. Keep provider credentials outside the repository and logs.
- The connected phone is face-up with its rear camera facing the floor. Camera, depth, real walking, collision/object performance, GNSS ground-truth quality and accessibility user acceptance cannot be passed from this setup.
- An older field-position goal remains blocked on missing independent walking data. The goal API refused creation of another goal. Do not falsely complete the older goal; track this approved scope here.

## Stages and acceptance evidence

1. Accessibility and speech design -> authoritative sources, UI/app speech ownership, supported commands and state-dependent confirmation/cancellation catalogue.
2. User speech implementation -> one primary local STT path, offline Korean TTS, bounded recovery, stale-callback rejection, semantic accessibility and no double announcements; related unit tests and device-observable checks.
3. TMAP -> live server credential configuration, real POI and pedestrian requests using public test locations, schema/unit handling, stair-avoidance behavior and explicit failure if known constraints cannot be met.
4. Report and data collection -> bounded explicit development queue profile, private server storage and consent gates; no forced opt-in, no external report submission.
5. Administrator and position evaluator -> targeted fixes for confirmed state/race/time/reference-quality errors; relevant regression tests.
6. Integration -> serialized Android builds and tests, preserved-data APK installation, supported stationary device smoke, concrete success/failure and NOT_RUN report.

## Ownership

- Parent: user MainActivity integration, runtime configuration, device, integration build, final report/daylog.
- Accessibility worker: research/design document only.
- Speech worker: speech engines, feedback actuator and required capability probes/tests.
- Navigation worker: TMAP provider and navigation contract tests.
- Report/raw worker: explicit development build profile, queue/raw tests and configuration requirements.
- Administrator worker: adminapp controllers/tests.
- Position evaluator worker: positionevalapp reference parsing/validation/tests.

## Initial decisions

- TalkBack owns ordinary UI navigation, editable fields, labels and accessible text. App-owned explicit listening owns education playback completion; listening is not itself agreement.
- Primary app STT: bundled Korean Vosk for both button and hands-free capture. Place-name recognition remains fallible and requires candidate confirmation.
- Primary app TTS: Android offline Korean TTS. Server Whisper/Qwen paths are not silently selected as the default.
- Use official pedestrian stair-avoidance options and provider evidence. Do not invent walkable geometry or label an unverified route safe.

## Progress

- Initial bounded read-only audit completed before this task: TMAP mock mode/no live key, report queue disabled, raw ingest disabled, voice recovery gaps.
- Worker implementation and targeted tests are in progress. No release/field-safety completion is implied by this plan.

## 2026-09-06 야간 실행 결과

- 영역별 병렬 구현을 통합했으며 동시 활성 수는 최종 담당자 포함 7개 이하로 유지했다.
- TalkBack/앱 TTS 역할 분리, Vosk 입력 통일, 명령·복구 처리, TMAP 실호출·계단 회피/차단, 신고 설정 표시, 관리자 페이지 상태, 분석 앱 시각 검증을 보완했다.
- 자동검사 통과: 사용자 앱 1,823(1 skip), 관리자 261, 분석 55, Backend 비DB 1,515(165 skip), 격리 DB 109, Gateway 161. Android Lint 3개 통과.
- 사용자·분석 APK 업데이트 설치, 새 합성 계정의 실제 폰 로그인과 정상 첫 안전 안내 화면 확인. 데이터 초기화나 초기 절차 우회 없음.
- 가입 전 약관 TTS의 전체 기기점검 게이트 문제를 실제 폰에서 재현·수정했다. TalkBack OFF/ON에서 실제 완료 상태 확인. ON 최종 시험은 접근성 서비스에 간섭하는 uiautomator dump를 제외하고 screencap으로 확인했으며 원래 접근성 설정을 복원했다.
- 완료 범위와 미검증 항목은 `docs/reports/overnight-stability-20260906.md` 참조. 실제 당사자·음성 발화·카메라·실보행·독립 정답/2m 정확도 검증은 남아 있다.
- 기존 현장 Goal은 미달성이므로 완료 처리하지 않았다. 새 Goal 생성이 기존 미완료 Goal로 거절된 제약을 유지해 기록한다.
