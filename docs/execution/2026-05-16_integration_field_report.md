# 2026-05-16 Integration/Field Test/Report Catch-up

실행 라운드: 1

## 범위

- 담당 lane: Integration/Field Test/Report
- 초점: 실폰/목걸이 착용 테스트, 모델-백엔드-PWA 연결, fake/server/STT/admin 근거 분리, 데모/보고 기준
- daylog는 merge 에이전트가 통합 작성해야 하므로 이 문서만 남긴다.

## 확인한 기준 문서

- `plans/catchup/2026-05-16.md`
- `plans/daily/2026-05-15.md`
- `plans/.work/2026-05-16/catchup/integration-field-report.md`
- `daylog/2026-05-15.md`
- `README.md`
- `docs/report_operations.md`
- `docs/neck_worn_phone_test_checklist.md`
- `docs/execution/2026-05-15_runtime_followup_after_reset.md`
- `docs/execution/2026-05-15_pwa_server_detection_e2e.md`
- `docs/execution/2026-05-15_pwa_production_server_e2e.md`
- `docs/execution/2026-05-15_backend_postgis_followup.md`
- `docs/execution/2026-05-15_model_onnx_followup.md`
- `docs/execution/2026-05-15_voice_cache_followup.md`

## 5/15 완료 근거 재분류

| 항목 | 판정 | 근거 | 보고 기준 |
| --- | --- | --- | --- |
| PWA 정적 검증 | 완료 | `npm run lint`, `npm run typecheck`, `npm run build` 통과 기록 | PWA build readiness 근거 |
| Backend/PostGIS | 완료 | PostGIS healthy, Alembic `202605120001 (head)`, `backend/tests` `22 passed` 기록 | 신고 API 개발 환경 smoke 근거 |
| Backend `/detect` ready | 완료 | v2 `best.pt` env에서 `/detect/health ready`, known-positive 이미지 `source=server` detection 기록 | 서버 모델 API smoke 근거 |
| PWA server mode E2E | 완료 | dev/prod headless Chromium fixture E2E에서 `source=server`, `metadata.source=server` 신고 저장 PASS | 모델-백엔드-PWA 연결 smoke 근거 |
| TTS 단일 cache hit | 완료 | `안전하게 이동하세요.` 2차 요청 `x-voice-cached:true` 기록 | 단일 문구 cache smoke 근거 |
| ONNX export | 완료 | `best.onnx` export, raw tensor PT/ONNX allclose smoke 기록 | 산출물 준비 smoke 근거 |
| 실폰/목걸이 field test | 미완료 | 5/15 `adb devices` 장치 없음 기록, 5/16 세션은 `adb` 없음 | field test 근거로 사용 금지 |
| 브라우저/실폰 마이크 STT E2E | 미완료 | voice contract는 PASS이나 실제 마이크 발화 E2E 없음 | 음성 field 근거로 사용 금지 |
| fake 신고 `/admin` 실폰 운영 흐름 | 미완료 | headless server report 저장은 있으나 실폰 fake report와 admin UI 상태 변경 근거 없음 | 운영 수동 검증 대기 |
| PWA install/offline/TalkBack | 미완료 | service worker/ARIA 구현 위치만 확인, 설치/offline/TalkBack 수동 결과 없음 | 접근성 수동 검증 대기 |

## 5/16 실제 실행 결과

| 명령/확인 | 결과 |
| --- | --- |
| `git status --short --untracked-files=all` | 내 변경 파일은 `docs/execution/2026-05-16_integration_field_report.md`. 그 외 `apps/web/**`, `backend/tests/test_reports.py`, `scripts/test_stt.py`, 5/15 실행 문서, 5/16 다른 lane 문서가 이미 수정/미추적 상태로 존재함 |
| `adb devices` | 실패: `adb: command not found` |
| `docker compose ps` | 실패: Docker socket permission denied |
| `ss -ltnp` | 실패: netlink socket permission denied |
| `df -h .` | `/` 가용 `18G`, 사용률 `98%` |
| `.venv/bin/python -m py_compile scripts/check_pwa_server_e2e.py` | PASS |
| `cd apps/web && npm run lint` | PASS |
| `cd apps/web && npm run typecheck` | PASS |
| `git diff --check` | PASS |
| `.venv/bin/python -m pytest backend/tests/test_detect.py -q` | 3분 이상 출력 없이 반환되지 않아 결과 미확정. 완료 근거로 사용하지 않음 |

## 코드/구현 확인

- PWA server mode는 `NEXT_PUBLIC_DETECTOR_MODE=server`일 때 `/detect/health` 확인 후 카메라 프레임을 `/detect`로 보내고, 가장 높은 confidence detection을 화면과 신고 흐름에 연결한다.
- `apps/web/lib/detect-api.ts`는 backend detection을 `DetectionEvent`로 변환할 때 `source: "server"`를 명시한다.
- PWA voice UI는 `MediaRecorder`로 오디오를 녹음해 `/speech/stt`에 업로드하고, `create_report` intent는 `handleReport()`를 호출한다.
- service worker는 `/`, `/manifest.webmanifest`, `/icon.svg` shell asset을 캐시한다.
- 접근성 구현 위치는 확인했다: 위험 배너/상태에 `aria-live`, 음성 토글/녹음 버튼에 `aria-pressed`.
- 위 항목은 코드/정적 검토 근거이며 Android Chrome, TalkBack, offline shell 수동 통과 근거는 아니다.

## 문서 충돌 목록

| 문서 | 충돌/구식 문장 | 최신 판단 |
| --- | --- | --- |
| `README.md` | PWA가 fake detector로 동작하고 모델 미구현 대체 시스템을 별도 관리한다고 설명 | 기본/데모 fake mode 설명으로는 유효하지만, 5/15 기준 server mode headless E2E와 `/detect` adapter 구현 완료 사실을 함께 갱신해야 함 |
| `docs/current_status.md` | 모델이 아직 PWA나 백엔드 `/detect`에 연결되지 않았고 `/detect/health`가 placeholder라고 설명 | 5/15 기준 backend `/detect` adapter와 PWA server mode E2E는 완료. 단 실폰 field 근거는 여전히 없음 |
| `docs/pwa_backend_status.md` | `/detect/health`, `/detect` 상태가 placeholder이며 `POST /detect`는 모델 연결 전 `503`이라고 설명 | 모델 env 미설정 시에는 맞지만, v2 `best.pt` env에서는 `ready`와 `source=server` detection 가능 |
| `docs/model_integration_plan.md` | adapter 구현 전 계획 문서로 `model_adapter_not_implemented` 전제를 둠 | 계획 문서 성격은 유지하되, 5/14 adapter 구현과 5/15 E2E 완료 링크를 추가해야 함 |
| `docs/frontend_handoff_without_model.md` | 실제 모델은 아직 PWA에 연결하지 않고 `/detect`는 `503 model_unavailable`이라고 설명 | 모델 없는 handoff 문서로는 유효하지만, server detector mode가 생겼으므로 적용 범위를 "모델 env 미설정/미사용 시"로 좁혀야 함 |
| `docs/backend_3day_execution_plan.md` | `/detect` placeholder, 실제 추론 미수행, adapter 미구현 상태를 현재 정상 상태로 설명 | 5/15 이후에는 과거 계획/기준으로 보아야 하며 최신 상태 문서에서는 adapter 구현 완료와 모델 env 필요 조건을 반영해야 함 |
| `docs/neck_worn_phone_test_checklist.md` | 현재 한계에 실제 모델이 아직 연결되지 않았으므로 fake detector 기준이라고 설명 | fake field checklist로는 유효하지만, server mode field checklist와 source 분리 기준을 추가해야 함 |

## 데모/보고 기준

- `source=fake`: API/UI/운영 흐름 데모 근거로만 사용한다. 정확도, 지연시간, 실제 안전 판단, field 성능 근거로 쓰지 않는다.
- `source=server`: 모델-백엔드-PWA 연결 smoke 근거로 사용할 수 있다. 현재 근거는 headless fixture와 known-positive 이미지 기반이며, 실폰/목걸이 카메라 field 성능 근거가 아니다.
- v2 모델: class `0 damaged_tactile_block` 중심 baseline으로 보고한다. 4-class 서비스 성능으로 표현하지 않는다.
- STT/TTS: voice contract와 단일 TTS cache hit는 API smoke 근거다. 실제 브라우저/실폰 마이크와 청취 평가는 별도 근거가 필요하다.
- 실폰 테스트: 안전 확보된 실내/통제 환경의 목걸이 착용 검증으로만 표현한다. 실제 시각장애인 대상 현장 검증으로 쓰지 않는다.

## 5/17 후속 후보

1. Android SDK/ADB 또는 LAN/HTTPS 접속 방식을 확보하고 기기명, Android/Chrome 버전, 권한 프롬프트부터 기록한다.
2. 목걸이 착용 상태에서 fake mode 카메라 각도, TTS 6초 쿨다운, 진동, GPS/heading, 화면 미주시 인지성을 확인한다.
3. fake 신고를 생성한 뒤 `/admin`에서 이미지, 위치 품질, `fake_source`, duplicate 후보, `new -> reviewed -> resolved`를 수동 확인한다.
4. server mode를 실폰 카메라 또는 통제 입력으로 재확인하고, headless fixture E2E와 field 결과를 분리 기록한다.
5. 브라우저/실폰 마이크로 `신고해`, `음성 켜/꺼`, `다시 말해줘`, `지금 어디야`의 transcript/intent/confidence/UI action을 기록한다.
6. 4개 위험 문구와 운영 문구 TTS cache를 생성하고 2회 요청 cache hit, voice-off fallback, 실폰 스피커 청취 평가를 기록한다.
7. 위 문서 충돌 목록을 최신 상태 문서 갱신 PR 범위로 분리한다.

## 병렬 에이전트 활용 메모

이번 5/16 r1 실행에서는 새 하위/병렬 에이전트를 사용하지 않았다. 작업 범위가 문서 통합과 현재 세션에서 가능한 정적 검증 중심이고, 동일 실행 문서 수정 충돌을 피하기 위해 단일 에이전트로 처리했다.
