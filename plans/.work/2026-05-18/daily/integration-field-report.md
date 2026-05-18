# Hanium Dreamup / WalkSafe Assist - Integration/Field Test/Report lane note (2026-05-19)

## 최근 진행 근거
- 2026-05-18 `plans/daily/2026-05-19.md`: 없음 확인. 저장소 내부 `AGENTS.md`도 없어 사용자 제공 지침 기준 적용.
- 2026-05-18 `daylog/2026-05-18.md`, `docs/execution/2026-05-18_integration_field_report.md`: PWA 정적 검증과 server-mode build는 PASS, backend no-DB 테스트는 `13 passed, 17 skipped`, PWA server-mode headless E2E는 loopback `Operation not permitted`로 실패.
- 2026-05-18 `docs/execution/2026-05-18_integration_field_report.md`: Docker/PostGIS, local TCP, ADB 접근 제한으로 PostGIS runtime, 실폰/목걸이 field, `/admin` 운영 흐름, voice HTTP/PWA E2E는 완료 근거 없음.
- 2026-05-18 `docs/current_status.md`, `docs/pwa_backend_status.md`: `.pt` server adapter와 PWA server detector smoke는 완료됐지만 Android 실폰 field 성능 근거는 없음. fake detector는 데모/API 흐름 근거로만 사용.
- 2026-05-15 `docs/execution/2026-05-15_pwa_server_detection_e2e.md`, `docs/execution/2026-05-15_pwa_production_server_e2e.md`: known-positive fixture 기반 `source=server`, `metadata.source=server` 신고 저장은 dev/start 모두 PASS 후 row/upload cleanup 완료. 실폰 카메라 검증은 아님.
- 2026-05-18 `docs/voice_stt_tts_status.md`: 실제 사람 음성 8개 intent 성공, TTS 7문구 direct-call cache hit 확인. HTTP cache header, PWA CORS, 브라우저/실폰 마이크, fallback, 휴대폰 스피커 청취는 미완료.
- 2026-05-18 `docs/execution/2026-05-18_model_data_mlops.md`: v2는 class `0 damaged_tactile_block` baseline이며 4-class 서비스 성능 근거가 아님. browser/ONNX Runtime Web latency와 class `1..3` 한국 GT metric은 미실행.
- 2026-05-12/2026-05-17 `docs/neck_worn_phone_test_checklist.md`, `docs/report_operations.md`: 목걸이 착용 테스트는 안전한 실내/통제 smoke로만 표현하고, fake 신고는 정확도/안전 판단 근거로 쓰지 않음.

## 내일 목표 후보
- 1순위: Docker/PostGIS, backend `8000`, web `3000`, voice `9001`, Android 접속 경로를 먼저 고정한다.
- 2순위: PostGIS reports HTTP smoke, duplicate/radius, upload, `/admin` 상태 변경, row/upload cleanup을 실제 runtime에서 확인한다.
- 3순위: Android 실폰/목걸이 fake mode smoke로 카메라 각도, GPS/heading, TTS/진동, 화면 미주시 인지성을 기록한다.
- 4순위: server mode는 headless fixture 재실행과 실폰/통제 입력을 분리해 `source=server` 근거를 남긴다.
- 5순위: voice HTTP/PWA CORS, 브라우저/실폰 마이크 명령, TTS HTTP cache/fallback/청취 평가를 확인한다.
- 6순위: fake/server/model/voice/field 근거를 섞지 않도록 2026-05-19 실행 문서와 daylog에 실제 결과만 기록한다.

## 상세 체크리스트 초안
- [ ] 작업 기준 확인 → 검증: `git status`, `plans/daily/2026-05-19.md` 존재 여부, `NEXT_PUBLIC_*`, `MODEL_*`, `VOICE_*` env 기록
- [ ] 통합 runtime gate 확보 → 검증: `5432/8000/9001/3000` 접근, `/health`, `/detect/health`, `/reports?limit=1`, voice `/health` 응답 기록
- [ ] Android 접속 방식 확정 → 검증: ADB reverse/LAN/HTTPS 중 실제 동작 경로, 기기명, Android/Chrome 버전, 권한 프롬프트 기록
- [ ] PostGIS/reports smoke 실행 → 검증: Alembic head, `backend/tests/test_reports.py` no-skip, `POST /reports`, duplicate/radius, status patch, upload URL, cleanup 기록
- [ ] fake mode 실폰/목걸이 smoke → 검증: 후면 카메라, bbox, GPS accuracy, heading, TTS/진동, `source=fake`, 화면 미주시 인지성 기록
- [ ] fake 신고 `/admin` 운영 확인 → 검증: 신고 ID, 이미지, 위치 품질, `fake_source`, duplicate 후보, `new -> reviewed -> resolved` 기록
- [ ] server mode headless E2E 재실행 → 검증: `scripts/check_pwa_server_e2e.py --web-mode dev|start`, `source=server`, `metadata.source=server`, cleanup 기록
- [ ] server mode 실폰/통제 입력 확인 → 검증: `/detect/health ready`, bbox 표시, 신고 payload `source=server`; detection이 없으면 미확인으로 분리
- [ ] 음성 신고 통합 확인 → 검증: `신고해`, `음성 켜/꺼`, `다시 말해줘`, `지금 어디야` transcript/intent/confidence/UI action과 버튼 신고 경로 비교
- [ ] TTS HTTP/fallback/청취 점검 → 검증: 7문구 2회 HTTP 요청, 두 번째 `X-Voice-Cached: true`, voice-off/repeat/server-down fallback, 휴대폰 스피커 평가 기록
- [ ] PWA 설치/offline/TalkBack 점검 → 검증: standalone 실행, offline shell, `aria-live`, `aria-pressed`, disabled reason, bbox `aria-hidden`, 터치 타깃 기록
- [ ] 통합 보고 문서 작성 → 검증: `docs/execution/2026-05-19_integration_field_report.md`, `daylog/2026-05-19.md`에 통과/실패/대기/막힘만 분리 기록

## 리스크/확인 필요
- 같은 sandbox 조건이면 Docker, local TCP, 포트 조회, ADB가 다시 막힐 가능성이 큼. 일반 개발 세션 또는 장비 접근 가능한 세션 필요.
- Android에서 `127.0.0.1`은 휴대폰 자신을 가리키므로 ADB reverse가 아니면 LAN IP 또는 HTTPS 경로가 필요.
- PostGIS smoke 후 테스트 row/upload를 삭제할지, disposable DB를 쓸지 확인 필요.
- `source=fake`는 UI/API/운영 흐름 근거일 뿐 정확도, 지연시간, 실제 보행 안전 근거가 아님.
- `source=server` headless PASS는 fixture smoke이며 실폰 목걸이 field 성능 근거와 분리해야 함.
- v2 모델은 class `0` baseline이다. 4-class 서비스 성능으로 표현하면 안 됨.
- STT/TTS direct-call 성공은 HTTP/PWA/실폰 성공 근거가 아님.
- `서울역으로 안내해줘`를 `set_destination`으로 확장할지는 제품 판단 필요.
- 원본 이미지, 위치 데이터, 음성 샘플, 생성 WAV, `.pt`, `.onnx`, `runs/`, `outputs/`는 GitHub 업로드 대상이 아님.

## 병렬 에이전트 활용 메모
- 이번 lane note 작성에는 하위/병렬 에이전트를 사용하지 않았다.
- 근거 수집은 문서/로그 조회 중심이고 최종 파일 수정도 하지 않는 범위라, 병렬 shell 조회만으로 충분했다.