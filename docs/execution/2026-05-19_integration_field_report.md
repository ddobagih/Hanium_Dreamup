# 2026-05-19 Integration/Field Test/Report Catch-up

실행 라운드: r2 정식 문서화

## 범위

- 담당 lane: Integration/Field Test/Report
- 초점: docs, scripts, 실폰/목걸이 착용 테스트, 모델-백엔드-PWA 연결, 데모/보고 기준
- 이번 r2는 `plans/catchup/2026-05-19-audit-r1.md`의 `A 계속 가능` 항목 중 담당 lane에 직접 해당하는 정식 execution 문서 승격만 수행했다.
- audit 지시상 런타임 재시도는 제외했다. 아래 결과는 기존 `plans/.work/2026-05-19/execute-r1/*.md`와 관련 문서 근거를 정리한 것이다.
- daylog는 merge 에이전트가 통합 작성해야 하므로 이 문서는 실행 근거만 남긴다.

## 확인한 기준 문서

- `plans/catchup/2026-05-19.md`
- `plans/catchup/2026-05-19-audit-r1.md`
- `plans/daily/2026-05-18.md`
- `daylog/2026-05-18.md`
- `daylog/2026-05-19.md`
- `README.md`
- `plans/.work/2026-05-19/execute-r1/backend-postgis-api.md`
- `plans/.work/2026-05-19/execute-r1/pwa-accessibility-sensors.md`
- `plans/.work/2026-05-19/execute-r1/voice-stt-tts.md`
- `plans/.work/2026-05-19/execute-r1/vision-model-data-mlops.md`
- `plans/.work/2026-05-19/execute-r1/integration-field-report.md`
- `plans/.work/2026-05-19/catchup/integration-field-report.md`
- `docs/execution/2026-05-18_integration_field_report.md`
- `docs/execution/2026-05-19_model_data_mlops.md`

저장소 내부 `AGENTS.md`는 없었고, 사용자 제공 AGENTS 지침을 적용했다.

## r1 실행 결과 정식 판정

| 항목 | 판정 | 근거 |
| --- | --- | --- |
| 통합 E2E 스크립트 문법 | PASS | `.venv/bin/python -m py_compile scripts/check_pwa_server_e2e.py` PASS |
| PWA 정적 회귀 | PASS | `node --check apps/web/public/sw.js`, `npm run lint`, `npm run typecheck`, `npm run build` PASS |
| PWA server-mode build | PASS | `NEXT_PUBLIC_DETECTOR_MODE=server NEXT_PUBLIC_API_BASE_URL=http://127.0.0.1:8000 npm run build` PASS |
| Backend no-DB 테스트 | PASS | `pytest backend/tests/test_detect.py backend/tests/test_uploads.py -q -rs` 기준 `13 passed` |
| Backend 전체 테스트 | PARTIAL | `pytest backend/tests -q -rs` 기준 `13 passed, 17 skipped`; PostGIS test DB 미접속으로 reports 테스트는 완료 아님 |
| Backend `.pt` health ASGI | PARTIAL | `/detect/health` ASGI `200 ready`; 실제 `/detect` 호출은 30초 timeout으로 PASS 처리하지 않음 |
| Model/Data 연결 근거 | PASS | v2 test split 80장 streaming smoke PASS, v3 후보 index 61행 생성, 대형 이미지 산출물 없음 |
| Voice direct/ASGI 계약 | PARTIAL | direct handler/ASGI로 health, intent, CORS preflight, TTS cache 계약 확인. 실제 HTTP/browser/phone 근거는 아님 |
| Server-mode headless E2E | FAIL | `scripts/check_pwa_server_e2e.py --web-mode dev ...`가 backend `/health` 대기 중 loopback socket 제한으로 실패 |
| PostGIS/Alembic/reports no-skip | BLOCKED | Docker socket permission denied, DB 접속 불가, reports tests `17 skipped` |
| 실제 HTTP smoke/upload/duplicate/cleanup | BLOCKED | loopback client socket 제한과 DB 미접속으로 `/health`, `/reports`, upload matrix, duplicate/radius, row/upload cleanup 미확인 |
| Android 실폰/목걸이 field | BLOCKED | `adb` 없음, 실기기/Android Chrome/권한 프롬프트 접근 없음 |
| `/admin` 운영 흐름 | PENDING | 실폰 또는 runtime 신고 row를 만들지 못해 목록/상세/이미지/상태 변경 확인 없음 |
| Voice browser/phone E2E | BLOCKED | loopback HTTP, 브라우저 권한, 실폰 마이크 환경 없음 |
| TTS HTTP/fallback/청취 | BLOCKED | direct-call cache만 확인. HTTP header, fallback, 휴대폰 스피커 청취 근거 없음 |

## 모델-백엔드-PWA 연결 판정

- `server(.pt)`는 demo/MVP 우선 detector mode로 유지한다. 현재 r1 근거는 `.pt` adapter health ready와 PWA server-mode build이며, 실제 HTTP `/detect`와 PWA 저장 흐름은 완료가 아니다.
- `fake` detector는 demo fallback과 UI/API/운영 흐름 확인용으로만 사용한다. 정확도, 지연시간, field 성능 근거로 섞지 않는다.
- `source=server`는 모델-백엔드-PWA 연결 smoke 근거로만 사용한다. headless fixture 결과와 실폰 목걸이 field 성능은 별도 근거로 분리해야 한다.
- v2 모델은 class `0 damaged_tactile_block` baseline이다. 킥보드/공사장애물/포트홀까지 포함한 4-class 서비스 성능으로 표현하지 않는다.
- browser/ONNX Runtime Web latency는 아직 없다. 보고에서는 backend `.pt` 추론 준비 상태와 browser ONNX 미측정을 분리한다.

## 데모/보고 기준

- PASS로 표현 가능한 항목: PWA 정적 build, server-mode build, E2E 스크립트 문법, backend no-DB detect/uploads 테스트, model 80장 sampler smoke.
- PARTIAL로 표현할 항목: Backend 전체 테스트, `.pt` health ready, Voice direct/ASGI 계약. 이 항목들은 실제 HTTP/field 근거와 구분한다.
- FAIL로 표현할 항목: 2026-05-19 r1 server-mode headless E2E 재시도. 실패 원인은 sandbox loopback socket 제한이다.
- BLOCKED로 표현할 항목: Docker/PostGIS, local TCP HTTP smoke, ADB/Android, browser/phone mic, TTS HTTP/fallback/청취.
- PENDING으로 표현할 항목: `/admin` 운영 흐름, fake/server 신고 ID 기반 추적, cleanup. runtime 신고 생성이 막혀 아직 검증 대상 데이터가 없다.
- 실폰/목걸이 테스트는 안전한 실내 통제 smoke로만 보고한다. 실제 시각장애인 대상 현장 검증이나 실사용 안전 검증으로 표현하지 않는다.

## 다음 실행 조건

1. Docker/PostGIS와 local TCP socket이 허용되는 일반 개발 세션을 확보한다.
2. `5432/8000/3000/9001` 접근 방식과 `NEXT_PUBLIC_*`, `MODEL_*`, `VOICE_*`, `DATABASE_URL`, `UPLOAD_DIR` 값을 기록한다.
3. Android SDK/ADB reverse를 우선 사용하고, 불가하면 LAN/HTTPS와 secure context 제약을 명확히 기록한다.
4. 신고 생성 시 report ID, upload 파일, `source`, `metadata.source`, 위치 품질, 상태 변경 전후를 같은 표에 묶어 추적한다.
5. 생성한 report row와 upload 파일은 disposable DB 또는 명시 cleanup 기준 중 하나로 정리한다.

## 미완료

- Backend/PostGIS reports no-skip, Alembic head, 실제 HTTP smoke, upload matrix, duplicate/radius, cleanup
- PWA Android 실폰/목걸이, 카메라/GPS/heading, TTS/진동, 설치/offline, TalkBack
- Server mode headless E2E PASS, 실폰/통제 입력 기반 `source=server` 신고 저장
- `/admin` 목록/상세/이미지/위치품질/검토플래그/상태 변경
- Voice HTTP contract, browser CORS, desktop/phone mic E2E, TTS HTTP cache/fallback/청취
- Daylog 통합 반영

## 병렬 에이전트 활용 메모

이번 r2 정식 문서화에는 하위/병렬 에이전트를 사용하지 않았다. 작업 범위가 기존 r1 note를 하나의 integration execution 문서로 승격하는 단일 파일 수정이었고, daylog와 다른 lane execution 문서에는 손대지 않았다.
