# 2026-05-14 Next Step Parallel Execution

## Scope

사용자가 요청한 "다음 스텝"을 병렬로 진행했다. 삭제/이동은 사용자 승인 전까지 수행하지 않았다.

진행 범위:

- voice/PWA 자동 smoke 확인
- 실제 v2 `best.pt` 기반 backend `/detect` smoke 확인
- PWA `NEXT_PUBLIC_DETECTOR_MODE=server` wiring 구현

## Changed Files

- `apps/web/lib/detect-api.ts`
- `apps/web/app/page.tsx`
- `docs/execution/2026-05-14_pwa_server_detector_wiring.md`
- `docs/execution/2026-05-14_next_step_parallel.md`

## Voice/PWA Smoke

실행:

```bash
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. .venv-voice/bin/python -m uvicorn voice.server:app --host 127.0.0.1 --port 9001
PYTHONDONTWRITEBYTECODE=1 .venv-voice/bin/python scripts/check_voice_contract.py --base-url http://127.0.0.1:9001
cd apps/web && npm run lint
cd apps/web && npm run typecheck -- --incremental false
```

결과:

- `scripts/check_voice_contract.py`: 통과
- `/health`: 통과
- `/speech/intent`: 7개 케이스 통과
- `/speech/stt` empty/unsupported 오류 계약: 통과
- voice server 종료 후 `9001` 포트 리스너 없음 확인
- 브라우저 마이크 E2E는 권한 승인이 필요하므로 수동 검증 대기

## Backend Detect Smoke

사용:

- model: `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt`
- image: `datasets/walksafe_kr_v2/images/val/aihub513_tactile_2_09_1_1_1_2_20210820_0000315742.jpg`

실행 방식:

- FastAPI `TestClient`로 `/detect/health`, `/detect` 호출
- 별도 서버 프로세스는 띄우지 않음

결과:

- `/detect/health`: HTTP `200`
- `model_status`: `ready`
- `/detect`: HTTP `200`
- `model_version`: `best`
- detections count: `4`
- first detection:
  - class: `damaged_tactile_block`
  - confidence: `0.9443610310554504`
  - source: `server`

## PWA Server Detector Wiring

구현:

- `NEXT_PUBLIC_DETECTOR_MODE=server`일 때만 `/detect/health` 확인
- 모델 ready 상태에서 카메라 프레임을 `/detect`로 전송
- 성공 응답의 `detections`를 기존 `DetectionEvent`로 매핑
- `source: "server"` 유지
- fake detector 흐름은 유지
- 모델 미준비, 네트워크 오류, 응답 오류는 UI 상태 메시지로 표시

상세 문서:

- `docs/execution/2026-05-14_pwa_server_detector_wiring.md`

## Final Verification

추가 확인:

```bash
cd apps/web && npm run lint && npm run typecheck
git diff --check
```

결과:

- `npm run lint`: 통과
- `npm run typecheck`: 통과
- `git diff --check`: 통과

## Remaining Manual Checks

- `NEXT_PUBLIC_DETECTOR_MODE=server`로 PWA dev server 실행
- backend를 `MODEL_ARTIFACT_PATH`와 함께 실행
- 브라우저/실폰 카메라 권한 허용
- PWA에서 `/detect` 실제 호출 확인
- 탐지 박스 표시 확인
- `source: "server"` 신고 payload 저장 확인
- 브라우저 마이크 E2E 확인

## Notes

- 대용량 데이터셋, zip, weights, runs 산출물은 수정/삭제하지 않았다.
- 디스크 여유가 매우 낮으므로 다음 대형 작업 전 삭제/이동 결정이 필요하다.
- `plans/` 아래 untracked 파일은 이번 작업에서 수정하지 않았다.
