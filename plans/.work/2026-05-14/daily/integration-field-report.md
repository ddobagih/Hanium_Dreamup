# Hanium Dreamup / WalkSafe Assist - Integration/Field Test/Report lane note (2026-05-15)

## 최근 진행 근거

- 저장소 `AGENTS.md`는 없음, 사용자 메시지의 AGENTS 지침 적용. `daylog/`에는 파일 없음, `plans/daily/2026-05-15.md`도 없음. 최근 기록은 `docs/execution/` 기준.
- `docs/project_3day_execution_plan.md`(2026-05-13): 2026-05-15 목표가 실폰 PWA, 신고 E2E, 모델 외부 validation 준비, STT/PWA 연결 검증으로 잡힘.
- `docs/execution/2026-05-14_summary.md`(2026-05-14): 로컬 프론트/백엔드/모델/음성 검증은 대체로 완료, 실폰 카메라/GPS/방향/TTS/진동/PWA 설치는 대기.
- `docs/execution/2026-05-14_frontend.md`(2026-05-14): `npm run lint`, `npm run typecheck`, `/`, `/admin`, manifest, SW HTTP 200 통과. 브라우저 카메라 권한과 실폰 E2E는 미검증.
- `docs/execution/2026-05-14_backend.md`(2026-05-14): PostGIS, Alembic, `backend/tests`, `/health`, `/detect/health`, `/reports` smoke 통과.
- `docs/execution/2026-05-14_detect_adapter_implementation.md`(2026-05-14): 로컬 Ultralytics `.pt`용 `/detect` adapter 구현, 테스트 통과. 단 PWA는 아직 서버 `/detect`를 호출하지 않고 `NEXT_PUBLIC_DETECTOR_MODE=fake` 중심.
- `docs/execution/2026-05-14_pwa_stt_implementation.md`, `docs/execution/2026-05-14_voice_contract_implementation.md`(2026-05-14): PWA `MediaRecorder` 녹음 업로드와 voice CORS/error/`get_current_location` intent 구현. 실제 브라우저/실폰 마이크 E2E는 대기.
- `docs/neck_worn_phone_test_checklist.md`(2026-05-12): 목걸이 착용 테스트의 카메라 각도, TTS, 진동, 신고 흐름 통과 기준 정리.
- `docs/report_operations.md`(2026-05-12): fake 신고는 API/UI 통합 검증용이며 모델 성능·최종 안전 판단 근거로 사용 금지.
- `docs/execution/2026-05-14_external_validation_subset.md`(2026-05-14): AI Hub 513 VL2+VS2 tactile subset 외부 검증 완료, mAP50-95 `0.501`. 단 class `0` 전용 검증이고 남은 디스크가 약 `6.6G`로 낮음.

## 내일 목표 후보

1. Android 실폰에서 PWA, 백엔드, voice 서버, 관리자 화면까지 한 번에 연결되는 통합 smoke를 수행한다.
2. 목걸이 착용 상태에서 카메라 각도, 흔들림, TTS/진동 인지성, 신고 성공/실패 피드백을 체크리스트 기준으로 기록한다.
3. fake detector 신고 E2E와 `/admin` 운영 흐름을 확인하되, fake 데이터는 성능 근거에서 분리한다.
4. 로컬 `.pt` 기반 `/detect` adapter를 별도 API smoke로 확인하고, PWA가 아직 server detect를 직접 쓰지 않는 gap을 명시한다.
5. PWA 음성 명령 녹음 → `/speech/stt` → intent 처리 흐름을 데스크톱 또는 실폰에서 검증한다.
6. 2026-05-15 실행 결과를 `docs/execution` 형식으로 남길 수 있게 통과/실패/막힘 항목을 정리한다.

## 상세 체크리스트 초안

- [ ] 통합 실행 환경 고정 → 검증: `db:5432`, backend `8000`, web `3000`, voice `9001` 기동 후 `/health`, `/detect/health`, voice `/health`, `/`, `/admin` 응답 확인
- [ ] 실폰 접속 방식 선택 → 검증: ADB reverse 또는 LAN IP 중 하나로 고정하고 Android Chrome에서 PWA 접속, 카메라/위치/마이크 권한 프롬프트 확인
- [ ] 목걸이 착용 카메라 각도 점검 → 검증: 전방 1~3m와 바닥 일부가 함께 보이고 줄/옷깃이 렌즈를 가리지 않는지 기록
- [ ] fake 탐지 보행 화면 smoke → 검증: bbox, 위험 문구, TTS 6초 쿨다운, 진동 패턴을 실폰에서 확인
- [ ] 신고 E2E 실행 → 검증: `현재 위험 신고` 또는 `create_report` intent로 신고 생성 후 `/admin`에서 이미지, 위치 품질, `fake_source`, `duplicate_report_ids` 확인
- [ ] 관리자 운영 흐름 확인 → 검증: 상태/유형/source 필터, 상세 조회, `new → reviewed → resolved` 상태 변경 확인
- [ ] PWA 설치/오프라인 shell 확인 → 검증: Android Chrome 설치 가능 여부, standalone 실행, 네트워크 차단 후 shell fallback 확인
- [ ] voice contract smoke 실행 → 검증: `scripts/check_voice_contract.py`로 intent 예시와 STT 오류 계약 확인
- [ ] 브라우저 마이크 E2E 확인 → 검증: “신고해”, “음성 꺼/켜”, “다시 말해줘”, “지금 어디야” 발화 후 transcript/intent/UI 상태 확인
- [ ] `/detect` adapter API smoke → 검증: `MODEL_ARTIFACT_PATH`와 `MODEL_VERSION` 설정 후 `/detect/health`가 `ready`인지 확인하고, 샘플 이미지 POST 결과가 `source: "server"` 또는 빈 detections로 정상 반환되는지 확인
- [ ] fake/server 결과 분리 기준 정리 → 검증: 보고 메모에 fake 신고, server `/detect`, STT 결과를 각각 별도 근거로 기록
- [ ] 결과 로그 초안 작성 → 검증: 실행한 명령, 실폰 기종/착용 방식, 통과/실패, 막힘, 다음 수정 필요 항목을 날짜와 함께 정리

## 리스크/확인 필요

- PWA는 현재 서버 `/detect` adapter를 직접 호출하지 않는다. 2026-05-15에 “모델-백엔드-PWA 완전 연결”까지 요구하면 별도 프론트 구현 범위가 필요하다.
- Android에서 LAN IP로 접속하면 카메라/위치/마이크 권한이 secure context 문제로 막힐 수 있다. ADB reverse + localhost 우선 검토가 필요하다.
- 현재 v2 모델 검증은 실질적으로 `damaged_tactile_block` 중심이다. 킥보드/자전거, 공사물, 포트홀 성능을 같은 수준으로 주장하면 안 된다.
- fake 신고는 데모/API 통합 확인용이다. 최종 보고서의 정확도, 지연시간, 실제 안전 판단 근거로 사용 금지.
- 남은 디스크가 낮아 추가 대형 validation, 이미지 추출, 새 dataset build는 정리 승인 전 보류가 안전하다.
- 실폰 테스트는 안전 확보된 실내/통제 환경에서 시야가 있는 테스트 진행자가 수행해야 한다. 실제 시각장애인 대상 현장 테스트로 간주하면 안 된다.