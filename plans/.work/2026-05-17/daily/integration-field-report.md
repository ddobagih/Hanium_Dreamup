# Hanium Dreamup / WalkSafe Assist - Integration/Field Test/Report lane note (2026-05-18)

## 최근 진행 근거
- `docs/execution/2026-05-17_integration_field_report.md` (2026-05-17): PWA 정적 검증은 PASS였으나, sandbox loopback 제한으로 `scripts/check_pwa_server_e2e.py`는 `/health` 접근 단계에서 실패. Android `adb` 없음, Docker socket 권한 없음도 확인됨.
- `daylog/2026-05-17.md` (2026-05-17): backend ASGI smoke, PWA build, model ONNX equivalence/latency, TTS 7문구 cache hit는 진전됨. PostGIS runtime, 실폰/목걸이, `/admin` 수동 흐름, 브라우저/실폰 마이크 E2E는 미완료.
- `docs/execution/2026-05-15_pwa_server_detection_e2e.md`, `docs/execution/2026-05-15_pwa_production_server_e2e.md` (2026-05-15): dev/prod headless fixture에서 `source=server`, `metadata.source=server` 신고 저장 PASS.
- `docs/current_status.md`, `docs/pwa_backend_status.md`, `docs/neck_worn_phone_test_checklist.md` (2026-05-17 보정): fake/server/model/field 근거를 분리했고, 실폰 field 근거는 아직 없다고 명시.
- `docs/report_operations.md` (2026-05-12): fake 신고는 API/UI/운영 흐름 검증용이며 정확도, 지연시간, 실제 안전 판단 근거로 쓰지 않는 정책 확인.
- `plans/daily/2026-05-18.md`: 현재 없음. 2026-05-18 계획은 5/17 daylog와 실행 note 기준으로 새로 병합 필요.

## 내일 목표 후보
1. 일반 개발 세션에서 통합 runtime gate 확보: PostGIS, backend `8000`, web `3000`, voice `9001`, `/admin` 응답과 env 고정.
2. Android 실폰 접속 경로 확정: ADB reverse, LAN IP, HTTPS 중 하나를 실제 접속으로 검증하고 기기/브라우저/권한 정보를 기록.
3. fake mode 목걸이 착용 smoke와 `/admin` 운영 흐름 확인: 카메라, GPS, 방향, TTS, 진동, 신고 저장, duplicate, 상태 변경까지 수동 기록.
4. server mode 재검증: headless E2E를 network 허용 세션에서 재실행하고, 가능하면 실폰/통제 입력에서도 `source=server` 신고 저장 확인.
5. 음성 신고 통합 확인: 브라우저/실폰 마이크에서 `신고해`, `음성 켜/꺼`, `다시 말해줘`, `지금 어디야`의 STT intent와 UI action 기록.
6. 데모/보고 기준 정리: fake, server, model metric, STT/TTS, 실폰 field 관찰을 서로 다른 근거로 분리해 통합 report에 반영.

## 상세 체크리스트 초안
- [ ] 작업트리와 실행 환경 고정 → 검증: `git status --short --branch --untracked-files=all`, `NEXT_PUBLIC_*`, `MODEL_*`, 포트 `5432/8000/9001/3000` 기록.
- [ ] PostGIS/backend runtime 재검증 → 검증: `docker compose up -d db`, Alembic head, `python -m pytest backend/tests -q -rs`, reports row/upload cleanup 기록.
- [ ] 통합 health smoke → 검증: backend `/health`, `/detect/health`, voice `/health`, web `/`, `/admin`, `GET /reports?limit=1` 응답 기록.
- [ ] Android 접속 전략 확정 → 검증: ADB reverse/LAN/HTTPS 중 하나로 Android Chrome에서 web/backend/voice 접근, 기기명과 Android/Chrome 버전 기록.
- [ ] fake mode 목걸이 착용 테스트 → 검증: 후면 카메라 각도, 전방 1~3m, 흔들림, GPS accuracy, heading, TTS 6초 쿨다운, 진동 패턴 기록.
- [ ] fake 신고 `/admin` 운영 흐름 확인 → 검증: 신고 ID, 이미지, 위치 품질, `fake_source`, duplicate 후보, `new -> reviewed -> resolved` 상태 변경 기록.
- [ ] server mode headless E2E 재실행 → 검증: `scripts/check_pwa_server_e2e.py --web-mode start` PASS 또는 실패 사유, `source=server`, `metadata.source=server` 기록.
- [ ] server mode 실폰/통제 입력 확인 → 검증: `/detect/health ready`, `/detect` 호출, bbox 표시, 신고 payload `source=server` 기록. detection이 없으면 미확인으로 분리.
- [ ] 음성 신고와 버튼 신고 비교 → 검증: `create_report` intent와 `현재 위험 신고` 버튼이 같은 duplicate check와 `POST /reports` 흐름을 타는지 확인.
- [ ] 브라우저/실폰 마이크 E2E → 검증: transcript, intent, confidence, UI action, CORS 오류 여부 기록.
- [ ] TTS 청취/fallback 점검 → 검증: 7문구 cache WAV 사용, 휴대폰 스피커 명료도, voice-off, repeat_last, voice server down fallback 기록.
- [ ] PWA 설치/offline/TalkBack 확인 → 검증: standalone 실행, offline shell, `aria-live`, `aria-pressed`, disabled reason, 터치 타깃 수동 기록.
- [ ] 테스트 데이터 정리 → 검증: 생성 report row와 upload 파일을 ID 기준으로 삭제 또는 보존 사유 기록.
- [ ] 통합 결과 문서화 → 검증: `docs/execution/2026-05-18_integration_field_report.md`에 통과/실패/대기/막힘과 go/no-go 판단 분리.

## 리스크/확인 필요
- 현재 sandbox에서는 Docker socket, local TCP, 포트 조회, ADB가 막혀 있다. 같은 환경이면 실 runtime 검증은 다시 막힐 가능성이 높다.
- Android에서 `127.0.0.1`은 휴대폰 자신을 가리킨다. ADB reverse가 아니면 LAN IP 또는 HTTPS 개발 URL이 필요하다.
- `source=fake`는 운영 흐름 검증용이다. 정확도, 지연시간, 실제 보행 안전 근거로 쓰면 안 된다.
- `source=server` headless PASS는 fixture 기반 smoke다. 실폰 목걸이 카메라 field 성능 근거와 분리해야 한다.
- v2 모델은 class `0 damaged_tactile_block` baseline이다. 4-class 서비스 성능으로 표현하면 안 된다.
- `docs/model_integration_plan.md` 일부 ONNX 상태 문구는 `docs/execution/2026-05-17_model_data_mlops.md`의 최신 ONNX metric equivalence 통과 기록과 불일치 가능성이 있어 정리 필요.
- `/reports`, `/uploads`, status patch는 인증/권한 없이 열려 있다. 외부 공개 데모 전에는 접근 범위를 제한해야 한다.
- 실폰 테스트는 안전한 실내/통제 환경 smoke로만 표현하고 실제 시각장애인 대상 현장 검증으로 쓰지 않는다.

## 병렬 에이전트 활용 메모
- 하위/병렬 에이전트는 사용하지 않았다.
- 이번 작업은 2026-05-18 lane note 작성용 문서 조사였고, 2026-05-17 lane별 실행 note와 daylog가 이미 충분히 분리돼 있어 단일 통합이 더 안전했다. 독립 파일 조회만 병렬 shell로 처리했다.