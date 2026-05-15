# 2026-05-15 Runtime Follow-up After Reset

작성 시각: 2026-05-15 12:10 KST

## 전제 메모

- 사용자 확인: STT/TTS는 외부 API로 처리하지 않는다.
- 현재 프로젝트 기준:
  - STT: 로컬 `faster-whisper medium`.
  - TTS: 로컬 voice server + 로컬 캐시의 `Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice`.
- 실폰 재검증은 이전에 수행했고, 이번 범위에서는 나중에 하는 것으로 유지했다.

## 1. PostGIS test row reset

사용자 지시에 따라 테스트로 남은 persistent row와 테스트 업로드 파일을 정리했다.

- reset 전 상태: `reports` row `12`, `backend/uploads/test/` 파일 `6개`, 총 `120 bytes`.
- 실행:
  - `TRUNCATE TABLE reports RESTART IDENTITY`
  - `backend/uploads/test` 테스트 파일 삭제 후 디렉터리 재생성
- reset 후 확인:
  - `SELECT COUNT(*) FROM reports;` -> `0`
  - `backend/uploads/test` -> 파일 없음, `du -sh` 기준 `4.0K`

## 2. Backend `/detect` 실제 모델 ready smoke

실제 v2 모델을 backend에 연결해 FastAPI TestClient로 확인했다.

- env:
  - `MODEL_ARTIFACT_PATH=/home/ddobagi/Code/hanium-dreamup/runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt`
  - `MODEL_VERSION=walksafe-kr-tactile-v2-full-20260514-best-02a6be87`
- `GET /detect/health`: HTTP `200`, `model_status=ready`, `reason=null`.
- 첫 후보 이미지 1장은 8MB 초과로 HTTP `413` 확인.
- 작은 test 이미지:
  - `datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_5_1_20210807_0000239770.jpg`
  - HTTP `200`, `model_status=ready`, `detections_count=0`
- known-positive val 이미지:
  - `datasets/walksafe_kr_v2/images/val/aihub513_tactile_2_09_1_1_1_2_20210820_0000315742.jpg`
  - size `1,104,219 bytes`
  - HTTP `200`, `detections_count=4`
  - 첫 detection: `class=damaged_tactile_block`, `confidence=0.9443610310554504`, `source=server`

## 3. PWA server mode 브라우저 runtime smoke

backend와 PWA dev server를 임시로 띄워 headless Chromium fake media로 server mode runtime을 확인했다.

- backend: `127.0.0.1:8000`, 위 `best.pt` env로 기동.
- PWA: `NEXT_PUBLIC_DETECTOR_MODE=server NEXT_PUBLIC_API_BASE_URL=http://127.0.0.1:8000`, `127.0.0.1:3000`.
- browser: headless Chromium + fake media stream.
- CDP network 관측:
  - 반복 `GET /detect/health` HTTP `200`
  - `POST /detect` HTTP `200`
- DOM 관측:
  - `서버 탐지 모드`
  - `서버 연결됨 · 위험 없음 (walksafe-kr-tactile-v2-full-20260514-best-02a6be87)`
  - video info: `w:1280 h:720 paused:false readyState:4 srcObject:true`
- 제한:
  - fake camera 프레임에서 detection이 없어 UI report 저장의 `source:"server"` payload까지는 확인하지 못했다.

## 4. Voice TTS `sox` 설치 후 로컬 cache smoke

이전 blocker였던 `sox: not found`를 해결하고, 로컬 voice server에서 TTS cache 동작을 확인했다.

- 설치: `sox`, `libsox-fmt-all`.
- voice server: `127.0.0.1:9001` 임시 기동.
- `scripts/check_voice_contract.py --base-url http://127.0.0.1:9001 --timeout 5`: PASS.
- TTS 문구: `안전하게 이동하세요.`
- 1차 `/speech/tts`:
  - HTTP `200`
  - `x-voice-cached:false`
  - content-length `122924`
  - generation seconds `1.708`
  - curl total `7.665567s`
- 2차 같은 요청:
  - HTTP `200`
  - `x-voice-cached:true`
  - curl total `0.001282s`
- 생성 cache:
  - `outputs/voice/cache/qwen3_tts_cd250a528361467f9b26.wav`
  - size `122924 bytes`

## 5. 서비스/포트 최종 상태

- backend uvicorn: 종료됨.
- PWA dev server: 종료됨.
- Chromium: 종료됨.
- voice server: 종료됨.
- PostGIS: `walksafe-postgis`는 계속 기동 중이며 healthy.
- 포트 확인: `3000`, `8000`, `9001` listener 없음. `5432`만 PostGIS listener 존재.

## 6. 현재 남은 판단 사항

- PostGIS는 기동 상태로 남겨뒀다. 끄려면 `docker compose stop db`를 별도로 실행하면 된다.
- PWA fake camera smoke는 통과했지만, 실제 카메라/실폰 runtime 재검증은 이번 범위에서 하지 않았다.
- report 저장 payload의 `source:"server"`까지 보려면 검출이 발생하는 browser input 또는 테스트용 fixture 주입 방식이 필요하다.
