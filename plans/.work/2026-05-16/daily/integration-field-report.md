# Hanium Dreamup / WalkSafe Assist - Integration/Field Test/Report lane note (2026-05-17)

## 최근 진행 근거
- `AGENTS.md` 확인(2026-05-16 조사): 저장소 내부에는 별도 `AGENTS.md`가 없고, 사용자 제공 지침을 적용해야 한다.
- `README.md`(확인일 2026-05-16): PWA/백엔드/모델/음성 실행 기준과 검증 명령이 정리되어 있다.
- `plans/daily/2026-05-17.md` 확인(2026-05-16 조사): 기존 파일 없음. 5/17 계획은 새로 통합해야 한다.
- `plans/daily/2026-05-16.md`(작성일 2026-05-15): 5/16 핵심 목표가 실폰 PWA, server detect, STT, TTS cache, 모델 handoff, fake/server 근거 분리였음.
- `docs/execution/2026-05-15_pwa_server_detection_e2e.md`(2026-05-15): headless Chromium fixture 기준 PWA server mode E2E PASS, `/reports` 저장 `source=server`, `metadata.source=server` 확인. 실폰 카메라 검증은 미실행.
- `docs/execution/2026-05-15_pwa_production_server_e2e.md`(2026-05-15): production `next build/start` 기준 server mode E2E PASS. 실제 배포/도메인/HTTPS와 실폰은 미검증.
- `docs/execution/2026-05-15_runtime_followup_after_reset.md`(2026-05-15): backend `/detect/health ready`, known-positive 이미지 detection, 단일 TTS 문구 cache hit 확인. 실폰 재검증은 보류.
- `docs/execution/2026-05-16_integration_field_report.md`(2026-05-16): fake/server/STT/TTS/실폰 field 근거를 분리했고, 문서 충돌 목록과 데모/보고 기준을 정리함. `adb` 없음, Docker socket 권한 없음, 실폰/목걸이 field test 미완료.
- `daylog/2026-05-16.md`(2026-05-16): Android 실폰 카메라/GPS/방향/진동/TTS/마이크, 목걸이 착용, PWA 설치/offline/TalkBack, fake 신고 후 `/admin` 확인, 브라우저/실폰 마이크 E2E가 아직 미실행으로 기록됨.
- `docs/report_operations.md`(작성일 2026-05-12): fake 신고는 API/UI 운영 흐름 검증용이며 정확도, 지연시간, 실제 안전 판단 근거로 사용 금지.
- `docs/neck_worn_phone_test_checklist.md`(작성일 2026-05-12): 목걸이 착용 시 카메라 각도, TTS, 진동, 신고 흐름, `/admin` 확인 기준이 정의되어 있음.
- `docs/execution/2026-05-16_model_data_mlops.md`(2026-05-16): v2는 class `0 damaged_tactile_block` 중심 baseline이며 4-class 서비스 성능 근거로 쓰면 안 됨. backend ready artifact는 `.pt` 기준.
- `docs/execution/2026-05-16_backend_postgis_api.md`(2026-05-16): reports 테스트 보강은 되었지만 PostGIS 접근 불가로 runtime PASS는 확인되지 않음.

## 내일 목표 후보
- 실폰 접속 방식부터 확정한다: ADB reverse, LAN IP, HTTPS 중 하나를 고르고 기기명/Android/Chrome/권한 프롬프트를 기록한다.
- 목걸이 착용 fake mode field smoke를 수행한다: 카메라 각도, 흔들림, GPS/heading, TTS 6초 쿨다운, 진동 구분, 화면 미주시 인지성을 확인한다.
- 실폰 fake 신고 1건을 생성하고 `/admin`에서 이미지, 위치 품질, `fake_source`, duplicate 후보, `new -> reviewed -> resolved`를 확인한다.
- server mode는 기존 headless E2E 근거와 실폰/통제 입력 결과를 분리해 재확인한다.
- 브라우저/실폰 마이크 STT E2E를 확인하고 `create_report`, `voice_on/off`, `repeat_last`, `get_current_location`이 실제 UI action으로 이어지는지 기록한다.
- TTS 전체 데모 문구 cache hit와 실폰 스피커 청취 평가를 확인한다.
- README/current status/PWA-backend/model integration 문서의 stale 문구를 5/15~5/16 실행 근거 기준으로 갱신할 PR 범위를 분리한다.
- 최종 데모/보고 기준을 정리한다: fake, server, model metric, voice, field test 근거를 섞지 않는다.

## 상세 체크리스트 초안
- [ ] 통합 실행 환경 고정 → 검증: DB `5432`, backend `8000`, web `3000`, voice `9001` 기동 후 `/health`, `/detect/health`, voice `/health`, `/`, `/admin` 응답과 env 값을 기록한다.
- [ ] 실폰 접속 방식 선택 → 검증: ADB reverse, LAN IP, HTTPS 중 하나로 Android Chrome 접속을 확인하고 기기명, Android/Chrome 버전, 카메라/GPS/마이크 권한 프롬프트를 남긴다.
- [ ] 목걸이 착용 fake mode smoke → 검증: 전방 1~3m와 바닥 일부가 보이는지, 렌즈 가림/흔들림, GPS 정확도, heading 상태, `source=fake` 표시를 기록한다.
- [ ] TTS/진동 체감 확인 → 검증: 4개 위험 문구, 음성 켜짐/꺼짐 상태, 신고 성공/실패/유사 신고 진동 패턴을 실폰 기준으로 구분 가능한지 기록한다.
- [ ] fake 신고와 `/admin` 운영 흐름 확인 → 검증: 실폰에서 만든 신고가 `/admin`에 보이고 이미지, 위치 품질, `fake_source`, duplicate 후보, 상태 변경 `new -> reviewed -> resolved`가 확인되는지 기록한다.
- [ ] server mode 자동 회귀 재실행 → 검증: `scripts/check_pwa_server_e2e.py` dev 또는 production mode PASS 여부와 저장된 `source=server`, `metadata.source=server`를 확인한다.
- [ ] server mode 실폰/통제 입력 확인 → 검증: `/detect/health ready`, `/detect` 호출, bbox 표시, 신고 payload `source=server`를 확인한다. 실제 카메라에서 detection이 없으면 “실폰 server field detection 미확인”으로 남긴다.
- [ ] 브라우저/실폰 STT E2E 확인 → 검증: `신고해`, `음성 켜`, `음성 꺼`, `다시 말해줘`, `지금 어디야`의 transcript, intent, confidence, UI action을 기록한다.
- [ ] TTS cache 전체 문구 확인 → 검증: 위험 문구 4개와 운영 문구를 2회 요청해 두 번째 응답 `X-Voice-Cached: true`, WAV 존재, 실폰 스피커 청취 결과를 기록한다.
- [ ] PWA 설치/offline/TalkBack 빠른 점검 → 검증: standalone 실행, offline shell fallback, `aria-live`, `aria-pressed`, 신고 버튼 disabled reason, 터치 타깃을 수동 확인한다.
- [ ] 테스트 데이터 정리 → 검증: 생성한 report row와 upload 파일을 ID 기준으로 확인하고, 필요 시 reset 후 `reports count`와 업로드 디렉터리 상태를 기록한다.
- [ ] 데모/보고 기준 문서화 → 검증: fake 신고, server detect, STT 명령, TTS cache, 모델 metric, 실폰 관찰을 별도 근거로 표기하고 fake 데이터를 성능 근거에 섞지 않는다.
- [ ] 문서 충돌 갱신 범위 분리 → 검증: `README.md`, `docs/current_status.md`, `docs/pwa_backend_status.md`, `docs/model_integration_plan.md`, `docs/neck_worn_phone_test_checklist.md` 중 stale 문구와 최신 근거 링크를 목록화한다.

## 리스크/확인 필요
- Android 실폰, USB 디버깅, ADB 또는 LAN/HTTPS 접속이 없으면 핵심 field 항목은 완료가 아니라 `수동 검증 대기`로 남겨야 한다.
- 5/16 실행에서는 `adb` 없음, Docker socket 권한 없음이 기록되어 있다. 일반 개발 세션에서 재시도 필요.
- server mode E2E PASS는 headless fixture 기반이다. 실폰 목걸이 카메라 field 성능 근거로 표현하면 안 된다.
- v2 모델은 class `0 damaged_tactile_block` baseline이다. 킥보드/자전거, 공사 장애물, 포트홀까지 포함한 4-class 성능으로 주장하면 안 된다.
- fake 신고는 API/UI/운영 흐름 검증용이다. 정확도, 지연시간, 실제 보행 안전 판단 근거로 사용 금지.
- 브라우저/실폰 마이크 E2E와 voice CORS는 아직 미검증이다.
- TTS는 단일 문구 cache hit 근거만 확실하다. 전체 데모 문구 cache와 실폰 청취 평가는 별도 확인 필요.
- 디스크 사용률이 높았던 기록이 있다. 대형 validation/export/로그 생성 전 용량을 먼저 확인해야 한다.
- 실폰 테스트는 안전한 실내/통제 환경에서만 수행하고, 실제 시각장애인 대상 현장 검증으로 표현하지 않는다.
- 촬영 이미지, 위치, 음성 샘플, 생성 WAV, `.pt`, `.onnx`, `runs/`, `outputs/`는 GitHub 업로드 대상이 아니다.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 이번 작업은 새 파일을 수정하지 않는 lane note 작성이고, 핵심 근거가 `docs/execution`, `daylog`, 기존 계획 문서에 모여 있어 단일 에이전트의 직접 탐색으로 충분했다.
- 대신 독립적인 문서/스크립트 조회는 병렬 shell 호출로 대조했고, 통합 결론은 위 체크리스트에 반영했다.