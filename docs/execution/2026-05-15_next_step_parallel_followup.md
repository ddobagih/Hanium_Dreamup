# 2026-05-15 Next-step Parallel Follow-up Summary

작성 시각: 2026-05-15 KST

## 범위

사용자 요청에 따라 다음 스텝을 병렬 lane으로 진행했다.

- Backend/PostGIS follow-up
- PWA server-mode/static follow-up
- Model ONNX/handoff follow-up
- Voice STT/TTS cache follow-up

실폰 재검증은 사용자 메모에 따라 후속 일정으로 미뤘다.

## 결과 요약

| 영역 | 결과 | 핵심 근거 | 남은 일 |
| --- | --- | --- | --- |
| Backend/PostGIS | PASS | `docker compose up -d db`, Alembic `202605120001 (head)`, `backend/tests` `22 passed` | 테스트 row/upload 정리 정책 결정, `/detect` ready smoke |
| PWA server-mode static | PASS | `npm run lint`, `npm run typecheck`, `NEXT_PUBLIC_DETECTOR_MODE=server ... npm run build` 통과 | 브라우저 runtime `/detect` 호출, bbox/report source 확인 |
| Model ONNX | PASS | v2 `best.pt` hash OK, `best.onnx` export 성공, ONNX/PT smoke equivalence 통과 | full test-split metric equivalence, backend/PWA 연결 방식 결정 |
| Voice cache | PARTIAL | voice contract PASS | `/speech/tts` 500: offline metadata 조회 + `sox` missing. cache hit 미확인 |

## Backend/PostGIS

상세 문서: `docs/execution/2026-05-15_backend_postgis_followup.md`

- PostGIS 컨테이너 `walksafe-postgis`: healthy, 현재 기동 상태.
- Alembic current: `202605120001 (head)`.
- `python -m pytest backend/tests`: `22 passed in 0.40s`.
- TestClient smoke:
  - `GET /health`: `200 {"status":"ok"}`
  - `GET /detect/health`: `model_status=unavailable`, `reason=model_not_configured`
- 부작용:
  - PostGIS `reports` row: `6 -> 12` (`+6`)
  - `backend/uploads/test/` 테스트 이미지 6개, 총 `120 bytes`, ignored 상태.

## PWA server-mode static

상세 문서: `docs/execution/2026-05-15_pwa_server_detection_e2e.md`, `docs/execution/2026-05-15_pwa_production_server_e2e.md`

- `npm run lint`: PASS.
- `npm run typecheck`: PASS.
- `NEXT_PUBLIC_DETECTOR_MODE=server NEXT_PUBLIC_API_BASE_URL=http://127.0.0.1:8000 npm run build`: PASS.
- `next build`가 만든 `apps/web/next-env.d.ts` generated churn은 lane에서 원복됨.
- Android/브라우저 runtime 검증은 수행하지 않음.

## Model ONNX

상세 문서: `docs/execution/2026-05-15_model_onnx_followup.md`

- `best.pt`:
  - path: `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt`
  - sha256: `02a6be87626e9ba00bb72715d45d8c27d06103d851882a08f404260e453d8e94`
- `best.onnx` export:
  - path: `runs/detect/walksafe_kr_tactile_v2_full/weights/best.onnx`
  - size: `10,567,352 bytes`
  - sha256: `c21f47013ad340761a2743bc20ba36da8aa4110c40a16bb0ddf7b6913858efe7`
- Smoke equivalence:
  - output shape: `(1, 8, 8400)`
  - max abs diff: `0.0010375977`
  - mean abs diff: `0.0000131680`
  - `allclose(rtol=1e-3, atol=1e-3)`: `True`
- CPU latency smoke 30장:
  - PT p95: `13.70ms`
  - ONNX Runtime CPU p95: `68.67ms`

## Voice STT/TTS

상세 문서: `docs/execution/2026-05-15_voice_cache_followup.md`

- `.venv-voice` voice server 기동 성공 후 종료.
- `scripts/check_voice_contract.py`: PASS.
- `outputs/voice/cache`: WAV `0개`.
- GPU 여유: RTX 5070 Ti, free 약 `15207 MiB` 관측.
- `/speech/tts` 1회 시도:
  - HTTP `500`
  - cache WAV 생성 없음.
  - 로그상 blocker:
    - `sox: not found`
    - `OfflineModeIsEnabled`: Hugging Face offline mode에서 metadata 조회 실패.

## 현재 상태

- PostGIS DB 컨테이너는 계속 기동 중이다.
- Voice server는 종료됐고 `9001` listener 없음.
- ONNX artifact는 `runs/` 아래 ignored 산출물이다.
- 루트 파티션 가용 공간은 약 `18G`다.

## 다음 후보

1. `/detect` ready smoke: `MODEL_ARTIFACT_PATH=.../best.pt` 또는 ONNX adapter 방향을 정해 backend 실제 모델 응답을 확인한다.
2. PWA runtime server mode: backend ready 상태에서 브라우저가 `/detect`를 호출하고 `source: "server"` 신고를 저장하는지 확인한다.
3. Voice TTS blocker 처리: `sox` 설치 여부와 Hugging Face offline/local loading 방식을 결정한다.
4. Backend test side-effect 정리 정책: persistent PostGIS test rows를 reset할지 유지할지 결정한다.
