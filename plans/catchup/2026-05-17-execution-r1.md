# Hanium Dreamup / WalkSafe Assist catch-up 실행 보고 - 2026-05-17 r1

## 수행 요약
- 실행 note 5개와 `git status`/`git diff`를 대조해 r1 수행 결과를 통합했다.
- r1이라 이전 audit의 `A 계속 가능` 제한 검사는 적용 대상이 아니다.
- Backend는 AnyIO/TestClient hang 회피, async endpoint 조정, `/uploads/{filename}` 직접 응답, ASGI 테스트 클라이언트 전환이 수행됐다.
- Voice는 로컬 Hugging Face cache snapshot 우선 로드로 Qwen3 TTS 오프라인 로드 실패를 보완했다.
- Model/Data는 ONNX full metric equivalence, 120장 latency, VL1+VS1 hard-negative 200장 inference, failure 40행 triage가 수행됐다.
- Integration 문서는 stale 상태를 최신 기준으로 보정했고, fake/server/model/field 근거를 분리했다.
- git commit/push는 하지 않았다.

## 변경 파일
- Backend: `backend/app/database.py`, `backend/app/detector.py`, `backend/app/main.py`, `backend/tests/asgi_client.py`, `backend/tests/test_detect.py`, `backend/tests/test_reports.py`, `backend/tests/test_uploads.py`
- Voice: `voice/tts.py`
- 문서: `README.md`, `docs/current_status.md`, `docs/model_integration_plan.md`, `docs/neck_worn_phone_test_checklist.md`, `docs/pwa_backend_status.md`
- 실행 문서: `docs/execution/2026-05-17_integration_field_report.md`, `docs/execution/2026-05-17_model_data_mlops.md`
- 계획/note: `plans/catchup/2026-05-17.md`, `plans/daily/2026-05-17.md`, `plans/.work/2026-05-16/daily/*.md`, `plans/.work/2026-05-17/catchup/*.md`, `plans/.work/2026-05-17/execute-r1/*.md`
- 기존 작업트리에 `daylog/2026-05-16.md` 수정이 남아 있었고, 이번 통합에서는 보존했다.
- 로컬 ignored 산출물: `outputs/voice/*2026-05-17.csv`, `outputs/voice/cache/qwen3_tts_*.wav`, `runs/benchmark/*20260517*`, `runs/validation/*20260517*`, `runs/failure_sampling/*manual_review_20260517*`

## 검증
- Backend: `test_detect.py` 11 passed, `test_uploads.py` 2 passed, 전체 `backend/tests`는 `13 passed, 17 skipped`이며 skip 사유는 PostGIS test DB 미접속.
- Backend ASGI smoke: `/detect/health` unavailable/ready, resized 실제 이미지 `/detect` `source=server`, upload negative 5종 확인.
- PWA: `node --check`, `npm run lint`, `npm run typecheck`, `npm run build`, server-mode build PASS.
- Model: PT/ONNX test split 전체 metric equivalence 기준 통과, PT p95 `12.6493ms`, ONNX CPU p95 `58.1289ms`.
- Hard-negative: VL1+VS1 200장 기준 conf `0.25` FP `32/200`, conf `0.35` FP `21/200`.
- Voice: Python compile PASS, STT dry-run 8개 success, 실제 음성 8개 success, TTS 7문구 2회 요청 cache hit 확인.
- Integration E2E: sandbox loopback 제한으로 `/health` 접근에서 `Operation not permitted` 실패. 통과 처리하지 않음.
- 통합 확인: `git diff --check` PASS.

## daylog
- daylog/2026-05-17.md

## 남은 미완료/확인 필요
- PostGIS/Docker 접근 불가로 reports 성공 생성, duplicate/radius, Alembic runtime, row/upload cleanup 미확인.
- local TCP 제한으로 curl 기반 HTTP smoke와 server-mode headless E2E는 미완료.
- Android 실폰 카메라/GPS/방향/진동/TTS/마이크, 목걸이 착용, PWA 설치/offline, TalkBack은 수동 검증 대기.
- fake 신고 후 `/admin` 이미지/source/duplicate/status 변경 확인 미실행.
- 브라우저/실폰 마이크 STT E2E, PWA CORS, TTS 청취 평가는 미실행.
- VL1+VS1 전체 1,038장 hard-negative, full failure sampling 재시도, browser/ONNX Runtime Web latency, 4-class metric은 미완료.
- v2 모델은 class `0 damaged_tactile_block` baseline이며 4-class 서비스 성능 근거로 쓰면 안 된다.

## 병렬 에이전트 활용 메모
- 이번 통합 작업에서는 신규 하위/병렬 에이전트를 사용하지 않았다.
- r1 실행 note가 lane별 수행/변경/검증/미완료를 이미 분리해 담고 있어 직접 대조해 통합했다.