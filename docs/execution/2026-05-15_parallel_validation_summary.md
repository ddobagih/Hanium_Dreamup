# 2026-05-15 Parallel Validation Summary

작성 시각: 2026-05-15 10:58 KST

## 범위

사용자 요청에 따라 PWA, backend, voice, model 검증을 병렬 에이전트로 나누어 실행했다. 부모 에이전트는 결과를 통합하고 추가로 포트/도커/워크트리 상태를 확인했다.

- PWA lane: `docs/execution/2026-05-15_pwa_validation.md`
- Backend lane: `docs/execution/2026-05-15_backend_validation.md`
- Voice lane: `docs/execution/2026-05-15_voice_validation.md`
- Model lane: `docs/execution/2026-05-15_model_validation.md`

## 결과 요약

| 영역 | 결과 | 근거 | 남은 확인 |
| --- | --- | --- | --- |
| PWA 정적 검증 | PASS | `npm run lint`, `npm run typecheck`, `npm run build` 통과 | Android 실폰, 카메라/GPS/방향/TTS/진동, PWA install/offline, 접근성 수동 체크 |
| Backend detect 계약 | PARTIAL PASS | `backend/tests/test_detect.py` 11개 통과, TestClient `/health`, `/detect/health`, `/detect` unavailable 계약 확인 | PostGIS 미기동으로 reports tests 11개 skip, 실제 DB smoke 필요 |
| Voice API 계약 | PASS | 임시 `uvicorn voice.server:app` 기동 후 `scripts/check_voice_contract.py` 통과 | 실제 STT 음성 전사, 브라우저/실폰 마이크 E2E, TTS cache hit |
| Model artifact 확인 | PASS with blocker | v2 artifact 존재 및 `sha256sum -c` 통과, 기존 metric 표 정리 | `/` 100% 사용 중이라 ONNX/export/failure sampling 전 cleanup 승인 필요 |

## 실행/확인한 핵심 명령

### PWA

```bash
cd apps/web && npm run lint && npm run typecheck && npm run build
adb devices
```

- 결과: lint/typecheck/build 통과.
- `adb`는 사용 가능하지만 연결된 Android device 없음.
- `next build`가 `apps/web/next-env.d.ts`를 production route type으로 바꿨으나 PWA lane에서 원복했다.

### Backend

```bash
source .venv/bin/activate
python -m pytest backend/tests
python -m pytest backend/tests/test_reports.py -rs -q
```

- 결과: `11 passed, 11 skipped`.
- skip 사유: `127.0.0.1:5432` PostGIS DB connection refused.
- TestClient smoke:
  - `GET /health` -> 200 `{ "status": "ok" }`
  - `GET /detect/health` -> 200 `model_status="unavailable"`, `reason="model_not_configured"`
  - `POST /detect` -> 503 `detail.code="model_unavailable"`

### Voice

```bash
.venv-voice/bin/python -m uvicorn voice.server:app --host 127.0.0.1 --port 9001
.venv-voice/bin/python scripts/check_voice_contract.py --base-url http://127.0.0.1:9001 --timeout 5
curl -sS -i http://127.0.0.1:9001/health
```

- 결과: voice contract smoke 통과.
- `/health`: CUDA 사용 가능, GPU `NVIDIA GeForce RTX 5070 Ti`, STT `medium`, TTS `Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice` 확인.
- `outputs/voice/cache`는 존재하지만 WAV cache 수는 0.
- `/speech/tts`는 모델 로드/다운로드 가능성을 피하려고 실행하지 않음.
- 임시 voice 서버는 종료했고 9001 리스너 없음.

### Model

```bash
df -h /home/ddobagi/Code/hanium-dreamup /home/ddobagi/Downloads
sha256sum -c runs/validation/walksafe_kr_tactile_v2_freeze_20260514/SHA256SUMS.txt
git ls-files '*.pt' '*.pth' '*.onnx' '*.engine' '*.tflite' 'runs/**' 'datasets/walksafe_kr_v2/**' 'datasets/**/images/**' 'datasets/**/labels/**'
```

- `/` 파티션: 915G 중 863G 사용, 가용 5.9G, 사용률 100%.
- v2 freeze artifact hash: `best.pt`, `last.pt`, `results.csv`, `args.yaml`, `data.yaml` 모두 OK.
- Git 추적 대형 artifact는 확인되지 않음. `.gitkeep`만 추적 중.

## 부모 에이전트 추가 확인

```bash
git status --short --untracked-files=all
ss -ltnp | grep -E ':(3000|8000|9001)\b'
docker compose ps
```

- 최종 변경은 execution 문서 5개와 daylog 갱신 대상뿐이다.
- 3000/8000/9001 리스너 없음.
- `docker compose ps` 기준 실행 중인 compose service 없음.
- PostGIS 실제 테스트는 디스크 100% 상태와 DB 미기동 때문에 이번 통합 검증에서 추가 기동하지 않았다.

## 실폰 테스트 메모

- 사용자 확인: 실폰 테스트는 이전에 수행한 적이 있다.
- 이번 2026-05-15 병렬 검증에서는 Android 기기가 연결되지 않아 실폰 테스트를 재실행하지 않았다.
- 후속 실폰 재검증은 나중에 별도 일정으로 진행한다.

## 이번 검증에서 통과로 보면 안 되는 항목

- Android 실폰 검증: 미실행.
- 카메라/GPS/방향/TTS/진동/PWA install/offline/TalkBack: 미실행.
- PWA server detector runtime E2E와 `source: "server"` 신고 저장: 미실행.
- PostGIS 기반 reports API 전체 통과: DB 미기동으로 미확인.
- 실제 STT 음성 전사/브라우저/실폰 마이크 E2E: 미실행.
- TTS cache hit/WAV 생성: 미실행.
- ONNX export, PT-vs-ONNX 동등성, latency, failure sampling: 미실행.

## 다음 액션 권장

1. 디스크 cleanup 승인 범위를 먼저 정한다. 현재 `/` 사용률 100%라 대형 검증/export는 위험하다.
2. PostGIS를 기동하고 `python -m pytest backend/tests`를 재실행해 reports tests 11개를 실제 통과시키다.
3. Android 실폰을 연결해 PWA fake mode 수동 E2E를 확인한다.
4. backend model env를 설정한 뒤 PWA server mode runtime E2E를 확인한다.
5. voice는 마이크 E2E와 TTS cache hit를 별도 검증한다.
