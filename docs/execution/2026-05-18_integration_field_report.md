# 2026-05-18 Integration/Field Test/Report Catch-up

실행 라운드: 1

## 범위

- 담당 lane: Integration/Field Test/Report
- 초점: docs, scripts, 실폰/목걸이 착용 테스트, 모델-백엔드-PWA 연결, 데모/보고 기준
- daylog는 merge 에이전트가 통합 작성해야 하므로 이 문서는 실행 근거만 남긴다.

## 확인한 기준 문서

- `plans/catchup/2026-05-18.md`
- `plans/daily/2026-05-17.md`
- `plans/.work/2026-05-18/catchup/integration-field-report.md`
- `plans/.work/2026-05-18/catchup/{pwa-accessibility-sensors,backend-postgis-api,voice-stt-tts}.md`
- `daylog/2026-05-17.md`
- `README.md`
- `docs/current_status.md`
- `docs/pwa_backend_status.md`
- `docs/model_integration_plan.md`
- `docs/voice_stt_tts_status.md`
- `docs/neck_worn_phone_test_checklist.md`
- `docs/execution/2026-05-17_integration_field_report.md`
- `docs/execution/2026-05-17_model_data_mlops.md`

저장소 내부 `AGENTS.md`는 없었고, 사용자 제공 AGENTS 지침을 기준으로 적용했다.

## 실제 실행 결과

| 명령/확인 | 결과 |
| --- | --- |
| `git status --short --branch --untracked-files=all` | 기존 backend/docs/voice/daylog/plans 변경과 미추적 파일 존재 확인. 이번 lane에서 되돌리거나 덮어쓰지 않음 |
| `printenv \| rg '^(NEXT_PUBLIC_\|MODEL_)'` | 출력 없음. 현재 셸에 `NEXT_PUBLIC_*`, `MODEL_*` env 미설정 |
| `ss -ltnp` | `Cannot open netlink socket: Operation not permitted`. 포트 `5432/8000/9001/3000` 상태 확인 불가 |
| `docker compose ps` | Docker socket permission denied. PostGIS/backend runtime 검증 불가 |
| `adb devices` | `adb: command not found`. Android 실폰/ADB reverse 검증 불가 |
| model/fixture 존재 | v2 `best.pt`와 known-positive fixture 이미지 존재 확인 |
| Chromium 존재 | `/snap/bin/chromium` 확인 |
| `.venv/bin/python -m py_compile scripts/check_pwa_server_e2e.py` | PASS |
| `node --check apps/web/public/sw.js` | PASS |
| `cd apps/web && npm run lint` | PASS |
| `cd apps/web && npm run typecheck` | PASS |
| `cd apps/web && npm run build` | PASS |
| `NEXT_PUBLIC_DETECTOR_MODE=server NEXT_PUBLIC_API_BASE_URL=http://127.0.0.1:8000 npm run build` | PASS |
| `.venv/bin/python -m pytest backend/tests -q -rs` | `13 passed, 17 skipped`. skip 사유는 PostGIS test database 미접속 |
| `.venv/bin/python scripts/check_pwa_server_e2e.py --web-mode dev --browser-timeout 60 --report-timeout 20` | FAIL. `http://127.0.0.1:8000/health` 대기 중 `<urlopen error [Errno 1] Operation not permitted>` |
| `ps -ef \| rg 'uvicorn\|next dev\|next start\|remote-debugging-port\|check_pwa_server_e2e'` | E2E 실패 후 잔여 backend/web/browser 프로세스 없음 |

## 판정

| 항목 | 판정 | 근거 |
| --- | --- | --- |
| PWA 정적 회귀 | 통과 | lint/typecheck/build, service worker syntax check PASS |
| PWA server-mode build | 통과 | `NEXT_PUBLIC_DETECTOR_MODE=server` build PASS |
| 통합 E2E 스크립트 문법 | 통과 | `py_compile scripts/check_pwa_server_e2e.py` PASS |
| backend ASGI 회귀 | 부분 통과 | no-DB 테스트 `13 passed`; PostGIS tests `17 skipped` |
| server mode headless E2E 재실행 | 막힘 | sandbox loopback 연결 제한으로 `/health` 접근 실패 |
| 통합 포트/env 고정 | 부분 확인 | env 미설정 확인. 포트 상태는 netlink 권한 제한으로 확인 불가 |
| PostGIS/reports runtime | 막힘 | Docker socket permission denied, PostGIS test DB 미접속 |
| Android 실폰/목걸이 field test | 대기 | `adb` 미설치, 실제 기기/접속 경로 확인 불가 |
| fake 신고 `/admin` 운영 흐름 | 대기 | backend/PostGIS runtime과 실폰 흐름 미확보 |
| 브라우저/실폰 마이크 E2E | 대기 | voice HTTP/browser/phone runtime 접근 미확인 |
| TTS HTTP/fallback/청취 평가 | 대기 | direct-call cache hit 근거만 있고 HTTP/실폰 청취 근거 없음 |

## 문서 갱신

- `README.md`: TTS 7문구 direct-call cache 완료와 HTTP/PWA/청취 미완료를 혼동하지 않도록 미완료 표현을 보정했다.
- `docs/current_status.md`: 2026-05-17 ONNX equivalence/latency, hard-negative, STT 8개, TTS direct-call cache 근거와 남은 field/runtime 항목을 분리했다.
- `docs/model_integration_plan.md`: `best.onnx` full metric equivalence 완료와 browser latency 미실행, backend ready artifact `.pt` 유지 판단을 반영했다.
- `docs/pwa_backend_status.md`: 다음 작업의 TTS 항목을 HTTP cache/fallback/청취 평가로 구체화했다.
- `docs/voice_stt_tts_status.md`: 2026-05-17 STT 8개 재실행, TTS direct-call cache, 남은 HTTP/PWA/실폰 검증을 최신 보정으로 추가했다.

## 데모/보고 기준

- `source=fake`: API/UI/운영 흐름 데모 근거로만 사용한다. 정확도, 지연시간, 실제 안전 판단, field 성능 근거로 쓰지 않는다.
- `source=server`: 모델-백엔드-PWA 연결 smoke 근거로 사용할 수 있다. 현재 확실한 근거는 5/15 headless fixture와 known-positive 이미지 기반이며, 실폰 목걸이 카메라 field 성능 근거가 아니다.
- v2 모델: class `0 damaged_tactile_block` baseline으로 보고한다. 4-class 서비스 성능으로 표현하지 않는다.
- ONNX: full metric equivalence는 통과했지만 browser/ONNX Runtime Web latency가 없고 PT 대비 CPU p95가 느려 backend ready artifact는 `.pt`로 유지한다.
- STT/TTS: 실제 사람 음성 8개와 TTS 7문구 direct-call cache hit는 로컬 프로토타입 근거다. 브라우저/실폰 마이크 E2E, HTTP cache header, fallback, 청취 평가는 별도 근거가 필요하다.
- 실폰 테스트: 안전 확보된 실내/통제 환경의 목걸이 착용 검증으로만 표현한다. 실제 시각장애인 대상 현장 검증으로 쓰지 않는다.

## 남은 작업

1. Docker/PostGIS와 loopback socket 접근이 가능한 일반 개발 세션에서 backend `/health`, `/detect/health`, reports API smoke, cleanup을 재실행한다.
2. Android SDK/ADB 또는 LAN/HTTPS 접속 방식을 확보하고 기기명, Android/Chrome 버전, 권한 프롬프트를 기록한다.
3. fake mode 실폰/목걸이 상태에서 카메라 각도, GPS/heading, TTS/진동, 화면 미주시 인지성을 확인한다.
4. fake 신고를 생성한 뒤 `/admin`에서 이미지, 위치 품질, `fake_source`, duplicate 후보, `new -> reviewed -> resolved`를 확인한다.
5. server mode를 실폰 카메라 또는 통제 입력으로 재확인하고, headless fixture E2E와 field 결과를 분리 기록한다.
6. 브라우저/실폰 마이크로 `신고해`, `음성 켜/꺼`, `다시 말해줘`, `지금 어디야`의 transcript/intent/confidence/UI action을 기록한다.
7. TTS 7문구 HTTP cache header, voice-off/repeat/server-down fallback, 휴대폰 스피커 청취 평가를 기록한다.

## 병렬 에이전트 활용 메모

이번 r1 실행에서는 새 하위/병렬 에이전트를 사용하지 않았다. 실제 수정 범위가 문서 최신화와 현재 세션에서 가능한 정적/ASGI 검증으로 좁혀졌고, 같은 통합 문서 수정 충돌을 피하기 위해 단일 에이전트로 처리했다.
