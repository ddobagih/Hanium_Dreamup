# 2026-05-17 Integration/Field Test/Report Catch-up

실행 라운드: 1

## 범위

- 담당 lane: Integration/Field Test/Report
- 초점: docs, scripts, 실폰/목걸이 착용 테스트, 모델-백엔드-PWA 연결, 데모/보고 기준
- daylog는 merge 에이전트가 통합 작성해야 하므로 이 문서는 실행 근거만 남긴다.

## 확인한 기준 문서

- `plans/catchup/2026-05-17.md`
- `plans/daily/2026-05-16.md`
- `plans/daily/2026-05-17.md`
- `plans/.work/2026-05-17/catchup/integration-field-report.md`
- `daylog/2026-05-16.md`
- `README.md`
- `docs/current_status.md`
- `docs/pwa_backend_status.md`
- `docs/model_integration_plan.md`
- `docs/neck_worn_phone_test_checklist.md`
- `docs/execution/2026-05-15_pwa_server_detection_e2e.md`
- `docs/execution/2026-05-15_pwa_production_server_e2e.md`
- `docs/execution/2026-05-15_runtime_followup_after_reset.md`
- `docs/execution/2026-05-16_integration_field_report.md`

저장소 내부 `AGENTS.md`는 없었고, 사용자 제공 AGENTS 지침을 기준으로 적용했다.

## 실제 실행 결과

| 명령/확인 | 결과 |
| --- | --- |
| `git status --short --untracked-files=all` | 시작 시 기존 변경으로 `daylog/2026-05-16.md` 수정, 5/16 daily work note와 5/17 catch-up/daily plan 미추적 파일 존재. 되돌리거나 덮어쓰지 않음 |
| `printenv | rg '^(NEXT_PUBLIC_\|MODEL_)'` | 출력 없음. 현재 셸에 `NEXT_PUBLIC_*`, `MODEL_*` env 미설정 |
| `ss -ltnp` | `Cannot open netlink socket: Operation not permitted`. 포트 `5432/8000/9001/3000` 상태 확인 불가 |
| `docker compose ps` | Docker socket permission denied. PostGIS/backend runtime 검증 불가 |
| `adb devices` | `adb: command not found`. Android 실폰/ADB reverse 검증 불가 |
| `ls -l` model/fixture | `best.pt`와 known-positive fixture 이미지 존재 확인 |
| `which chromium` | `/snap/bin/chromium` 존재 확인 |
| `.venv/bin/python -m py_compile scripts/check_pwa_server_e2e.py` | PASS |
| `node --check apps/web/public/sw.js` | PASS |
| `cd apps/web && npm run lint` | PASS |
| `cd apps/web && npm run typecheck` | PASS |
| `cd apps/web && npm run build` | PASS |
| `.venv/bin/python scripts/check_pwa_server_e2e.py --web-mode dev --browser-timeout 60 --report-timeout 20` | FAIL. `http://127.0.0.1:8000/health` 대기 중 `<urlopen error [Errno 1] Operation not permitted>` |
| `ps -ef | rg 'uvicorn\|next dev\|next start\|remote-debugging-port\|check_pwa_server_e2e'` | E2E 실패 후 잔여 backend/web/browser 프로세스 없음 |

## 판정

| 항목 | 판정 | 근거 |
| --- | --- | --- |
| PWA 정적 회귀 | 통과 | lint/typecheck/build, service worker syntax check PASS |
| server mode headless E2E 재실행 | 막힘 | 로컬 loopback 연결이 sandbox에서 `Operation not permitted`로 차단됨 |
| 모델 산출물/fixture 존재 | 통과 | v2 `best.pt`, known-positive fixture 이미지 존재 |
| 통합 포트/env 고정 | 부분 확인 | env 미설정은 확인. 포트 상태는 netlink 권한 제한으로 확인 불가 |
| PostGIS/backend runtime | 막힘 | Docker socket permission denied, loopback 연결 차단 |
| Android 실폰/목걸이 field test | 대기 | `adb` 미설치, 실제 기기/접속 경로 확인 불가 |
| fake 신고 `/admin` 운영 흐름 | 대기 | 실폰/backend runtime이 막혀 새 신고 생성과 상태 변경 확인 불가 |
| 브라우저/실폰 마이크 E2E | 대기 | voice server/browser/phone runtime 접근이 막혀 확인하지 않음 |
| PWA 설치/offline/TalkBack | 대기 | 실제 Android Chrome 또는 DevTools 수동 세션 없음 |

## 문서 갱신

- `README.md`: fake 기본 경로와 server detector mode 가능 상태를 분리하고, 아직 미완료인 실폰/voice/cache/PostGIS 검증을 명시했다.
- `docs/current_status.md`: 2026-05-17 최신 보정 섹션을 추가하고, `/detect` adapter와 PWA server mode smoke 완료 범위를 실폰 field 성능 근거와 분리했다.
- `docs/pwa_backend_status.md`: `/detect` placeholder 서술을 모델 미설정 상태와 server mode 상태로 분리했다.
- `docs/model_integration_plan.md`: `.pt` adapter 구현 상태, `best.pt` ready artifact, ONNX 제한을 반영했다.
- `docs/neck_worn_phone_test_checklist.md`: Android 접속 방식, fake/server source 분리, 아직 미검증인 field 항목을 추가했다.

## 작업트리 주의

- 종료 상태에서 `backend/app/database.py`, `backend/app/detector.py`, `backend/app/main.py` 수정과 `plans/.work/2026-05-17/execute-r1/pwa-accessibility-sensors.md` 미추적 파일을 확인했다. 이번 Integration lane 작업에서 만든 변경이 아니므로 되돌리지 않았다.
- `daylog/2026-05-16.md`와 기존 `plans/.work/**`, `plans/catchup/2026-05-17.md`, `plans/daily/2026-05-17.md` 변경/미추적 파일도 기존 상태로 두었다.

## 데모/보고 기준

- `source=fake`: API/UI/운영 흐름 데모 근거로만 사용한다. 정확도, 지연시간, 실제 안전 판단, field 성능 근거로 쓰지 않는다.
- `source=server`: 모델-백엔드-PWA 연결 smoke 근거로 사용할 수 있다. 현재 확실한 근거는 5/15 headless fixture와 known-positive 이미지 기반이며, 실폰 목걸이 카메라 field 성능 근거가 아니다.
- v2 모델: class `0 damaged_tactile_block` baseline으로 보고한다. 4-class 서비스 성능으로 표현하지 않는다.
- STT/TTS: dry-run, 실제 샘플 재평가, 단일 TTS cache hit는 API/샘플 smoke 근거다. 브라우저/실폰 마이크 E2E와 7문구 cache hit는 별도 근거가 필요하다.
- 실폰 테스트: 안전 확보된 실내/통제 환경의 목걸이 착용 검증으로만 표현한다. 실제 시각장애인 대상 현장 검증으로 쓰지 않는다.

## 남은 작업

1. Docker/PostGIS와 loopback socket 접근이 가능한 일반 개발 세션에서 backend `/health`, `/detect/health`, reports API smoke, cleanup을 재실행한다.
2. Android SDK/ADB 또는 LAN/HTTPS 접속 방식을 확보하고 기기명, Android/Chrome 버전, 권한 프롬프트를 기록한다.
3. fake mode 실폰/목걸이 상태에서 카메라 각도, GPS/heading, TTS/진동, 화면 미주시 인지성을 확인한다.
4. fake 신고를 생성한 뒤 `/admin`에서 이미지, 위치 품질, `fake_source`, duplicate 후보, `new -> reviewed -> resolved`를 확인한다.
5. server mode를 실폰 카메라 또는 통제 입력으로 재확인하고, headless fixture E2E와 field 결과를 분리 기록한다.
6. 브라우저/실폰 마이크로 `신고해`, `음성 켜/꺼`, `다시 말해줘`, `지금 어디야`의 transcript/intent/confidence/UI action을 기록한다.
7. 4개 위험 문구와 운영 3문구 TTS cache를 생성하고 2회 요청 cache hit, voice-off fallback, 실폰 스피커 청취 평가를 기록한다.

## 병렬 에이전트 활용 메모

이번 r1 실행에서는 새 하위/병렬 에이전트를 사용하지 않았다. 실제 수정 범위가 문서 최신화와 현재 세션에서 가능한 정적 검증으로 좁혀졌고, 동일 통합 문서와 상태 문서 수정 충돌을 피하기 위해 단일 에이전트로 처리했다.
