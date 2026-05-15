# Hanium Dreamup / WalkSafe Assist - Integration/Field Test/Report lane note (2026-05-16)

## 최근 진행 근거
- `plans/daily/2026-05-16.md`: 현재 확인되지 않음. 이 note를 2026-05-16 merge용 lane 입력으로 사용.
- `daylog/2026-05-15.md`(2026-05-15): 5/15에는 새 `npm`/`pytest`/브라우저/실폰 검증을 실행하지 않았고, PWA server detector wiring과 관련 미완료 항목을 문서 근거로 재정리함.
- `docs/project_3day_execution_plan.md`(2026-05-13): 2026-05-16 완료 조건은 5/17 이후 PR 단위 분리와 모델/PWA/STT 연결 go/no-go 결정.
- `docs/execution/2026-05-14_summary.md`(2026-05-14): 로컬 PWA/백엔드/모델/음성 검증은 대부분 완료, 실폰 카메라/GPS/방향/TTS/진동/PWA 설치는 수동 대기.
- `docs/execution/2026-05-14_next_step_parallel.md`(2026-05-14): voice 계약 smoke 통과, v2 `best.pt` 기반 backend `/detect` TestClient smoke 통과, PWA `NEXT_PUBLIC_DETECTOR_MODE=server` wiring 구현.
- `docs/execution/2026-05-14_pwa_server_detector_wiring.md`(2026-05-14): PWA가 server mode에서 `/detect/health`와 `/detect`를 호출하도록 연결됐고 lint/typecheck 통과. 실폰/브라우저 E2E는 남음.
- `docs/execution/2026-05-14_detect_adapter_implementation.md`(2026-05-14): backend `.pt` adapter 구현, normalized bbox와 `source="server"` 응답 경로 테스트 완료.
- `docs/execution/2026-05-14_voice_pwa_smoke.md`(2026-05-14): `scripts/check_voice_contract.py` smoke 통과. 실제 브라우저/실폰 마이크 E2E는 수동 대기.
- `docs/execution/2026-05-14_external_validation_subset.md`(2026-05-14): VL2+VS2 tactile subset 외부 검증 mAP50-95 `0.501`, 단 class `0 damaged_tactile_block` 전용. 추가 대형 작업 전 디스크 정리 필요.
- `docs/neck_worn_phone_test_checklist.md`(2026-05-12): 목걸이 착용 각도, TTS, 진동, 신고 흐름의 현장 smoke 기준 확인.
- `docs/report_operations.md`(2026-05-12): fake 신고는 API/UI 통합 검증용이며 성능·안전 판단 근거로 사용 금지.
- 현재 작업트리: `apps/web/app/page.tsx` 수정, `apps/web/lib/detect-api.ts` 신규 미추적, 5/15 계획/daylog 문서도 미추적 상태. 후속 작업은 덮어쓰기 전 diff 확인 필요.

## 내일 목표 후보
1. `fake`와 `server` 탐지 모드를 분리해 Android 실폰 통합 smoke를 수행하고, 각각의 근거를 혼용하지 않는다.
2. `MODEL_ARTIFACT_PATH`가 지정된 backend `/detect`와 PWA server mode를 실제 브라우저/실폰 카메라 프레임으로 연결해 본다.
3. 목걸이 착용 상태에서 카메라 각도, 흔들림, GPS/방향, TTS/진동, 신고 피드백을 체크리스트 기준으로 기록한다.
4. voice server와 PWA 마이크 녹음 E2E를 확인하고 `create_report` 음성 명령이 버튼 신고와 같은 흐름을 타는지 검증한다.
5. `/admin`에서 fake/server 신고의 이미지, 위치 품질, review flag, duplicate 후보, 상태 변경을 확인한다.
6. 5/17 이후 작업을 PR 후보로 나누기 위해 통과/실패/대기/막힘과 go/no-go 판단을 `docs/execution` 형식으로 정리한다.
7. 오래된 placeholder/STT 미연결 문서와 최신 5/14 구현 로그 사이의 충돌 목록을 정리한다.

## 상세 체크리스트 초안
- [ ] 작업트리와 기준 문서 확인 → 검증: `git status --short --untracked-files=all`, `plans/daily/2026-05-16.md` 부재, `docs/execution/2026-05-15*.md` 부재/존재를 기록
- [ ] 통합 실행 환경 고정 → 검증: DB `5432`, backend `8000`, web `3000`, voice `9001` 기동 후 `/health`, `/detect/health`, voice `/health`, `/`, `/admin` 응답 기록
- [ ] Android 접속 방식 확정 → 검증: ADB reverse 또는 HTTPS/LAN IP 중 하나를 선택하고 기기명, Android/Chrome 버전, 카메라/위치/마이크 권한 프롬프트 기록
- [ ] fake mode 기준 field smoke → 검증: 후면 카메라 프리뷰, bbox, 위험 문구, TTS 6초 쿨다운, 진동 패턴, GPS/heading 상태를 목걸이 착용 상태로 기록
- [ ] fake 신고 E2E와 관리자 확인 → 검증: `source: "fake"` 신고 생성 후 `/admin`에서 이미지, 위치 품질, `fake_source`, `duplicate_report_ids`, `new → reviewed → resolved` 확인
- [ ] server detect PWA E2E → 검증: `MODEL_ARTIFACT_PATH`, `MODEL_VERSION`, `NEXT_PUBLIC_DETECTOR_MODE=server` 설정 후 실브라우저 카메라 프레임 `/detect` 호출, bbox 표시, `source: "server"` 신고 저장 확인
- [ ] server detect 실패 경로 기록 → 검증: 모델 미준비/네트워크 오류/빈 detections일 때 PWA 상태 문구와 신고 가능 여부가 과장 없이 표시되는지 확인
- [ ] voice 계약 smoke 재확인 → 검증: `scripts/check_voice_contract.py --base-url http://127.0.0.1:9001` 통과 여부 기록
- [ ] 브라우저/실폰 마이크 E2E → 검증: “신고해”, “음성 꺼/켜”, “다시 말해줘”, “지금 어디야” 발화의 transcript, intent, confidence, UI action 기록
- [ ] 음성 신고와 버튼 신고 비교 → 검증: `create_report` intent와 `현재 위험 신고` 버튼이 같은 검증 조건, duplicate check, `POST /reports` 흐름을 타는지 확인
- [ ] PWA 설치/오프라인/accessibility quick check → 검증: standalone 실행, offline shell fallback, TalkBack 또는 DevTools로 `aria-live`, `aria-pressed`, disabled reason, 장식 요소 낭독 여부 기록
- [ ] 통합 결과 로그 작성 → 검증: fake 신고, server `/detect`, voice STT, 모델 metric, 실폰 관찰을 분리해 `docs/execution/2026-05-16_integration*.md` 초안 기준으로 정리

## 리스크/확인 필요
- `docs/execution/2026-05-15*.md`가 없어 5/15 실폰/브라우저 추가 검증 완료 근거는 아직 없음.
- PWA server detector wiring은 구현됐지만 실제 브라우저/실폰에서 `/detect` 호출, bbox 표시, `source: "server"` 신고 저장은 완료로 표시하면 안 됨.
- v2 모델은 class `0 damaged_tactile_block` 중심 baseline이다. 4개 위험 클래스 전체 성능으로 확장 금지.
- fake 신고는 데모/API 통합 확인용이다. 정확도, 지연시간, 실제 안전 판단 근거로 사용 금지.
- Android에서 `localhost`/`127.0.0.1` 의미가 접속 방식에 따라 달라진다. `3000/8000/9001` 포트 전략을 먼저 고정해야 함.
- 카메라, 마이크, 위치, DeviceOrientation, Vibration, PWA 설치는 브라우저/OS 차이가 크므로 Android Chrome 결과와 다른 환경 결과를 분리해야 함.
- external validation 이후 `/` 여유 공간이 약 `6.6G`로 낮다는 기록이 있다. ONNX/export/추가 validation/failure sampling 전 정리 승인 필요.
- `/admin`, `/reports`, `/uploads`는 인증 없는 로컬 개발 전제다. 외부 네트워크 노출 데모는 별도 보안 판단 필요.
- 실폰 테스트는 안전한 실내/통제 환경에서 수행하고, 실제 시각장애인 대상 현장 검증으로 표현하지 않는다.

## 병렬 에이전트 활용 메모
- 하위/병렬 에이전트는 사용하지 않음.
- 기존 `plans/.work/2026-05-15/catchup` lane note와 5/14 실행 문서가 이미 분야별 조사 결과 역할을 하고 있어, 이번에는 integration lane 관점에서 직접 대조해 통합했다.