# Hanium Dreamup / WalkSafe Assist - Integration/Field Test/Report lane note (2026-05-20)

## 최근 진행 근거
- 2026-05-19 `docs/execution/2026-05-19_integration_field_report.md`: PWA 정적/build와 server-mode build는 PASS, server-mode headless E2E는 loopback socket 제한으로 FAIL. PostGIS/HTTP/Android/Voice E2E는 BLOCKED, `/admin` 운영 흐름은 PENDING.
- 2026-05-19 `docs/execution/2026-05-19_backend_postgis_api.md`: backend detect/uploads no-DB 테스트는 `13 passed`, reports 테스트는 PostGIS 미접속으로 `17 skipped`. Alembic, reports HTTP smoke, upload/duplicate/cleanup은 미완료.
- 2026-05-19 `docs/execution/2026-05-19_pwa_accessibility_sensors.md`: PWA lint/typecheck/build와 server-mode build는 PASS. Android 실폰/목걸이, GPS/heading, TTS/진동, offline/TalkBack은 장비/권한 환경 없음.
- 2026-05-19 `docs/execution/2026-05-19_voice_stt_tts.md`: voice direct handler/ASGI 계약은 확인됐지만 실제 HTTP contract, browser/phone mic E2E, TTS HTTP cache/fallback/청취는 미완료.
- 2026-05-19 `docs/execution/2026-05-19_model_data_mlops.md`: v2 test split 80장 streaming smoke와 v3 후보 61행 index는 정리됨. v2는 class `0 damaged_tactile_block` baseline으로만 보고해야 함.
- 2026-05-18 `product/decisions.md`: Android 접속은 ADB reverse 우선, PostGIS smoke는 disposable DB 우선, 시연/MVP detector는 `server(.pt)` 우선, 공식 field test 착용 방식은 사원증형 목걸이로 결정됨.
- 2026-05-17 보정 `docs/neck_worn_phone_test_checklist.md`: 실폰 테스트는 안전한 실내/통제 smoke로만 표현하고 실제 시각장애인 대상 현장 검증으로 표현하지 않음.
- 2026-05-12 `docs/report_operations.md`: `source=fake` 신고는 UI/API/운영 흐름 확인용이며 성능·안전 근거로 쓰지 않음. 중복 후보는 자동 병합이 아니라 advisory/운영자 검토 정보.
- 2026-W21 `plans/weekly/2026-W21.md`: 2026-05-20 후보는 fake mode 실폰/목걸이 smoke, 화면 미주시 TTS/진동 인지성, 데스크톱 브라우저 STT E2E, v3 후보 정책/소규모 sampler 정리.
- 저장소 내부 `AGENTS.md`와 `plans/daily/2026-05-20.md`는 확인되지 않았고, 사용자 제공 AGENTS 지침을 적용함.

## 내일 목표 후보
- 공통 runtime gate를 먼저 고정한다: PostGIS `5432`, backend `8000`, web `3000`, voice `9001`, Android 접속 방식, 주요 env.
- Android ADB reverse 기준 fake mode 목걸이 착용 smoke를 실행하고 카메라/GPS/heading/TTS/진동 인지성을 기록한다.
- fake 신고를 생성해 `/admin` 목록/상세/이미지/위치 품질/review flags/status 변경까지 신고 ID 기준으로 확인한다.
- gate가 열리면 server mode `source=server` headless 또는 통제 입력 smoke를 재확인하되, 실폰 field 성능과 분리한다.
- voice `create_report` intent가 버튼 신고와 같은 조건/중복/`POST /reports` 흐름을 타는지 통합 관점에서 확인한다.
- 실행 결과를 fake/server/model/voice/field 근거별로 분리해 보고 기준 표로 남긴다.

## 상세 체크리스트 초안
- [ ] 공통 runtime gate 기록 → 검증: `DATABASE_URL`, `UPLOAD_DIR`, `MODEL_ARTIFACT_PATH`, `NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_DETECTOR_MODE`, `NEXT_PUBLIC_VOICE_API_BASE`, `VOICE_CORS_ORIGINS`, 포트 접근 결과를 PASS/BLOCKED로 기록
- [ ] 테스트 데이터 격리 기준 확정 → 검증: disposable DB 또는 테스트 전용 `UPLOAD_DIR`, 실행 전후 report row 수와 upload 파일 수 기록
- [ ] Android 접속 경로 확정 → 검증: ADB reverse/LAN/HTTPS 중 실제 경로, 기기명, Android/Chrome 버전, 카메라/위치/마이크 권한 상태 기록
- [ ] fake mode 목걸이 착용 smoke → 검증: 전방 1~3m와 바닥 일부, 렌즈 가림, 흔들림, bbox, 4개 fake 위험 순환, `source=fake` 기록
- [ ] 화면 미주시 인지성 확인 → 검증: 4개 위험 TTS 문구, 6초 쿨다운, 음성 on/off별 진동, 신고 성공/실패/유사 신고 패턴 구분 가능 여부 기록
- [ ] fake 신고 `/admin` 운영 흐름 확인 → 검증: 신고 ID, 이미지, 위치 품질, `fake_source`, `duplicate_report_ids`, `new -> reviewed -> resolved` 상태 변경 기록
- [ ] server mode 선행 조건 확인 → 검증: `/detect/health ready`, 실제 `/detect` timeout 없는 응답, 탐지 결과 `source=server` 또는 정상 빈 detections 기록
- [ ] server mode PWA/headless smoke 재실행 → 검증: `scripts/check_pwa_server_e2e.py --web-mode dev|start`, bbox, `source=server`, `metadata.source=server`, report ID, cleanup 기록
- [ ] 음성 신고 통합 확인 → 검증: `신고해` transcript/intent/confidence/UI action과 버튼 신고 조건, duplicate check, `POST /reports` 경로가 일치하는지 기록
- [ ] 테스트 데이터 cleanup → 검증: 생성 report row와 upload 파일 삭제 결과 또는 보존 사유 기록
- [ ] 통합 실행 문서 작성 → 검증: `docs/execution/2026-05-20_integration_field_report.md` 후보에 PASS/FAIL/BLOCKED/PENDING을 분리하고 실행하지 않은 항목은 PASS로 쓰지 않음
- [ ] daylog 통합 입력 제공 → 검증: merge 에이전트가 `daylog/2026-05-20.md`에 변경 파일, 실제 검증, 미완료/막힘을 단일 통합으로 기록할 수 있게 요약 제공

## 리스크/확인 필요
- 같은 sandbox 조건이면 Docker, local TCP, ADB, 브라우저 권한 검증이 다시 막힐 수 있다. 이 경우 재시도보다 BLOCKED 조건을 명확히 남긴다.
- Android LAN HTTP는 카메라/위치/마이크/service worker secure context 제약으로 막힐 수 있다. ADB reverse 우선 결정은 유지한다.
- PostGIS가 열리지 않으면 `/admin`, duplicate, status patch, cleanup, fake/server 신고 ID 기반 추적은 완료할 수 없다.
- `/detect/health ready`는 실제 `/detect` 성공이나 PWA 신고 저장 근거가 아니다.
- v2 모델은 class `0` baseline이다. 4-class 서비스 성능, 정확도 90%, 실제 보행 안전 성능으로 표현하면 안 된다.
- `source=fake`는 UI/API/운영 흐름 근거이고, `source=server` headless fixture는 모델-백엔드-PWA smoke 근거다. 둘 다 실폰 목걸이 field 성능으로 확대하지 않는다.
- STT 8/8 실제 음성 샘플과 TTS direct-call cache는 브라우저/실폰 마이크, HTTP cache header, 휴대폰 스피커 청취 근거가 아니다.
- 원본 이미지, 위치 데이터, 음성 샘플, WAV, `.pt`, `.onnx`, `runs/`, dataset images/labels는 GitHub 업로드 대상이 아니다.
- 조사 시점 `git status`에는 `daylog/2026-05-19.md` 수정이 보였으므로 내일 실행 전 작업트리 상태를 다시 확인해야 한다.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 이번 작업은 파일 수정 없는 lane note 합성이며, 2026-05-19 execution/daylog/weekly/product 문서에 integration 결론이 이미 모여 있어 단일 조사로 충분했다.