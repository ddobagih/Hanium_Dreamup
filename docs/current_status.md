# WalkSafe Assist 현재 진행 상태

작성 기준일: 2026-05-13 KST

## 2026-05-18 최신 보정

- 2026-05-15 기준 backend `/detect` `.pt` adapter와 PWA `NEXT_PUBLIC_DETECTOR_MODE=server` headless E2E smoke는 완료됐다. 다만 Android 실폰/목걸이 카메라 field 성능 근거는 아직 없다.
- fake detector는 여전히 기본 데모/API 흐름 확인용이다. `source: "fake"` 결과는 정확도, 지연시간, 실제 보행 안전 근거로 사용하지 않는다.
- v2 모델은 class `0: damaged_tactile_block` 중심 baseline이다. 4개 위험 클래스 전체 서비스 성능으로 표현하지 않는다.
- 2026-05-17 기준 ONNX full metric equivalence와 PT/ONNX CPU latency 측정, VL1+VS1 hard-negative 200장 FP 평가는 완료됐다. backend ready artifact는 여전히 `.pt`다.
- 2026-05-17 기준 STT 실제 사람 음성 8개와 TTS 7문구 direct-call cache hit는 확인됐다. 브라우저/실폰 마이크 E2E, TTS HTTP cache/fallback/청취 평가, PostGIS runtime 재검증, PWA 설치/offline/TalkBack 수동 점검은 미완료다.

## 한 줄 요약

WalkSafe Assist는 현재 **Next.js PWA와 FastAPI/PostGIS 신고 흐름은 fake detector로 데모 가능하고 `.pt` server detector smoke도 완료**, **점자블록 YOLO v2 학습은 로컬 완료**, **로컬 STT/TTS 프로토타입은 실제 사람 음성 기준 PWA 연동 검토 가능** 단계다.

핵심 사용 방식은 시각장애인 또는 저시력 보행자가 휴대폰을 목걸이/스트랩 형태로 착용하고, 화면보다 TTS/진동을 우선으로 위험을 인지하는 보행 보조 PWA다.

## 현재 전제

- 사용자: 시각장애인 또는 저시력 보행자
- 착용 방식: 휴대폰을 목걸이/스트랩 형태로 목에 걸고 후면 카메라가 전방과 발밑 일부를 향함
- UI 우선순위: 화면 확인보다 TTS, 진동, 큰 터치 영역, 짧은 상태 문구가 우선
- 모델 상태: v2 `best.pt` 서버 추론 adapter와 PWA server detector mode smoke는 있으나, 실폰 field 성능 근거와 4-class 서비스 성능 근거는 없음
- 신고 정책: 중복 의심은 안내와 운영자 검토 정보로 남기고, 보행 중 추가 확인 모달로 사용자를 멈추게 하지 않음

## 목적별 상태 문서

| 문서 | 용도 |
| --- | --- |
| `docs/model_training_status.md` | 로컬 YOLO v1/v2 데이터셋과 학습 결과 요약 |
| `docs/model_v2_status.md` | v2 모델 보관/외부 검증/AI Hub 159 계획 |
| `docs/model_integration_plan.md` | 실제 모델을 `/detect` 또는 PWA에 연결하는 계획 |
| `docs/voice_stt_tts_status.md` | 로컬 STT/TTS 현재 판단 요약 |
| `docs/local_voice_server_plan.md` | 로컬 음성 서버 실행 방법과 상세 실험 기록 |
| `docs/pwa_backend_status.md` | PWA, FastAPI, fake detector, `.pt` server adapter 상태 |
| `docs/api_reference.md` | 백엔드 API 계약 |
| `docs/frontend_api_examples.md` | 프론트엔드 API 호출 예시 |
| `docs/report_operations.md` | 신고 상태, 중복 신고, fake 신고 운영 기준 |
| `docs/figma_ui_handoff.md` | Figma UI 구현 인계 |
| `docs/figma_make_accessibility_review.md` | Figma Make 화면 접근성 검토 |

## 영역별 현재 상태

| 영역 | 현재 상태 | 아직 남은 일 |
| --- | --- | --- |
| PWA | 카메라, fake/server 탐지 overlay, GPS/방향 상태, TTS/진동, 신고 전송, 중복 후보 advisory, STT 업로드 UI, `/admin` 신고 관리 화면 구현 | 실폰 PWA 검증, 목걸이 착용 field test, 브라우저/실폰 마이크 E2E, offline/TalkBack 수동 점검 |
| 백엔드 | FastAPI/PostGIS 신고 API, 이미지 업로드 검증, 중복 후보 조회, 신고 상태 변경, `/detect` `.pt` adapter 구현 | PostGIS runtime 재검증, 외부 스토리지, 배포 환경, 인증/권한 |
| 모델 | AI Hub 513 `TL8/TL9/TS8/TS9` 기반 점자블록 v1/v2 로컬 학습, v2 test split/ONNX equivalence/latency, hard-negative 200장 FP 평가 완료 | class `1..3` 한국 GT 데이터 보강, 전체 hard-negative 확장 판단, browser ONNX Runtime Web latency |
| 음성 STT/TTS | `voice/server.py` 로컬 FastAPI 초안, faster-whisper medium, Qwen3-TTS CustomVoice, 실제 사람 음성 8/8 intent 성공, TTS 7문구 direct-call cache hit | PWA 브라우저/실폰 마이크 E2E, TTS HTTP cache/fallback/청취 평가, 더 많은 실환경 음성 샘플 |
| 문서 | API, 프론트 연동, 백엔드 환경, 모델 인계, Figma UI/접근성 문서 세분화 | 중복 문서 정리, test/external validation 결과 추가 |

## 구현된 범위

### Frontend

- `apps/web/`에 Next.js App Router 기반 PWA 구현
- 첫 화면은 랜딩 페이지가 아니라 보행 보조 화면
- 카메라 프리뷰, 탐지 박스 오버레이, GPS/방향/정확도 상태 표시
- `NEXT_PUBLIC_DETECTOR_MODE=fake` 기준 4개 클래스 fake 탐지 이벤트 생성
- `NEXT_PUBLIC_DETECTOR_MODE=server` 기준 backend `/detect` 결과를 PWA 신고 흐름에 연결하는 smoke 확인
- 탐지 클래스별 TTS 문구와 진동 패턴 적용
- 음성 꺼짐 상태에서도 더 강한 진동 패턴 사용
- 신고 전 중복 후보 조회, 신고 전송, 전송 상태 표시
- `/admin` 신고 목록, 정렬, 필터, 상세, 위치 품질, 검토 플래그, 상태 변경 UI 구현
- Android USB reverse 기반 로컬 테스트 흐름 문서화

### Backend

- `backend/`에 FastAPI API 서버 구현
- PostGIS DB는 `docker-compose.yml`의 `postgis/postgis:16-3.5` 사용
- Alembic 초기 마이그레이션에서 `postgis` extension과 `reports` 테이블 구성
- 구현된 주요 엔드포인트:
  - `GET /health`
  - `GET /detect/health`
  - `POST /detect`
  - `POST /reports`
  - `GET /reports`
  - `GET /reports/{report_id}`
  - `PATCH /reports/{report_id}/status`
  - `GET /reports/duplicate-check`
  - `GET /uploads/{filename}`
- 신고 이미지는 현재 외부 스토리지 없이 `backend/uploads/`에 로컬 저장
- 업로드 검증은 MIME, 확장자, 빈 파일, 크기, 이미지 헤더 불일치를 구분해 반환

### Model

- `datasets/walksafe_kr_v1`: 6,000 images, 10,135 boxes
- `datasets/walksafe_kr_v2`: 23,475 images, 40,161 boxes
- v2 full training: `50/50` epochs 완료
- v2 validation best.pt:
  - precision `0.73656`
  - recall `0.58380`
  - mAP50 `0.66394`
  - mAP50-95 `0.49194`
- 현재 실제 학습 라벨은 class `0: damaged_tactile_block` 중심이다. 앱 계약은 4개 클래스를 유지하지만, 킥보드/자전거/공사물/포트홀 한국 데이터는 별도 보강이 필요하다.

### Voice

- 로컬 전용 음성 서버: `voice/server.py`
- STT: `faster-whisper medium`
- TTS: `Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice`
- 실제 사람 음성 테스트:
  - 총 8개 파일
  - 지원 intent 8개 기준 성공 8개
  - known-intent accuracy `100.00%`
  - 평균 지연 `1.807 sec`
  - p95 지연 `2.048 sec`
- 현재 기준 파인튜닝은 하지 않는다. 짧은 명령 오류는 intent rule 보강으로 해결 가능한 범위로 판단한다.

## 현재 임시 시스템

- `fake detector`: 실제 모델 대신 탐지 이벤트와 bbox를 순환 생성
- `source: "fake"`: 모델 기반 결과가 아님을 이벤트 계약에서 명시
- `GET /detect/health`: 모델 env 미설정 시 unavailable, v2 `best.pt` 설정 시 ready smoke 확인
- 브라우저 TTS/진동: 서버 기반 음성 모델이 아니라 Web Speech API와 Vibration API 기반
- 로컬 업로드: 운영 스토리지 대신 `backend/uploads/`에 이미지 저장

## GitHub 업로드 원칙

GitHub에는 문서, 설정, 경량 소스만 올린다. 아래 항목은 로컬에서만 유지한다.

- AI Hub 원본 zip: `TL8.zip`, `TL9.zip`, `TS8.zip`, `TS9.zip`, `VL*.zip`, `VS*.zip`
- 데이터셋 이미지/라벨: `datasets/**/images/**`, `datasets/**/labels/**`
- 학습 결과: `runs/`, `*.pt`, `*.onnx`, `*.engine`, `*.tflite`
- 음성 샘플/출력/실험 결과: `samples/voice/stt/*`, `outputs/voice/*`, `logs/`

## 남은 우선순위

1. 실폰을 다시 ADB에 연결하고 목걸이 착용 상태에서 카메라 각도, 흔들림, TTS/진동 인지성을 확인한다.
2. PostGIS/HTTP smoke와 PWA server mode `source: "server"` 신고 저장을 일반 개발 세션에서 재확인한다.
3. 모델은 v3 후보 61행의 min-box/privacy 정책을 확정하고, AI Hub 159 또는 직접 촬영 실패 프레임 수집을 시작한다.
4. Android 실폰/목걸이 착용 상태에서 server mode 카메라 입력과 `source: "server"` 신고 저장을 재확인한다.
5. 브라우저/실폰 마이크 STT E2E와 TTS HTTP cache/fallback/청취 평가를 확인한다.
6. class `1..3` 한국 GT를 확보하기 전까지 4-class 서비스 성능으로 보고하지 않는다.
