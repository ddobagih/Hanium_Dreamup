# Current Status

작성 기준일: 2026-05-13

## 한 줄 요약

WalkSafe Assist는 실제 모델 없이 `fake detector`를 기준으로 `Next.js PWA`와 `FastAPI/PostGIS` 신고 흐름을 먼저 연결한 상태다. 핵심 사용 방식은 시각장애인이 휴대폰을 목걸이/스트랩 형태로 착용하고, 화면보다 TTS/진동을 우선으로 위험을 인지하는 보행 보조 웹앱이다.

## 현재 전제

- 사용자: 시각장애인 또는 저시력 보행자
- 착용 방식: 휴대폰을 목걸이/스트랩 형태로 목에 걸고 후면 카메라가 전방과 발밑 일부를 향함
- UI 우선순위: 화면 확인보다 TTS, 진동, 큰 터치 영역, 짧은 상태 문구가 우선
- 모델 상태: 실제 YOLO/ONNX 모델은 아직 연결하지 않았고, `fake detector`와 서버 헬스체크 placeholder로 흐름을 검증 중
- 신고 정책: 중복 의심은 안내만 하고, 보행 중 추가 확인 모달로 사용자를 멈추게 하지 않음

## 영역별 현재 상태

| 영역 | 현재 상태 | 아직 남은 일 |
| --- | --- | --- |
| 음성 STT/TTS | 앱 안에서는 브라우저 `speechSynthesis` 기반 TTS와 진동만 사용 중이다. 별도 GPU 컴퓨터에서 STT/TTS smoke test가 가능하다는 보고는 있으나, 이 repo에는 음성 서버 코드가 아직 없다. | `voice/server.py`, `/speech/stt`, `/speech/tts`, 실제 사람 음성 테스트, PWA 마이크 녹음/재생 연동 |
| 모델 | v2 학습은 별도 컴퓨터 기준 50 epoch 완료로 보고됐다. 현재 repo와 앱에는 v2 가중치가 없고, 실제 YOLO/ONNX 추론도 아직 연결되지 않았다. | `best.pt` 또는 ONNX 산출물 확보, `backend/app/detector.py` 어댑터 구현, `/detect` 실제 응답 연결 |
| 백엔드 | FastAPI/PostGIS 신고 API, 이미지 업로드 검증, 중복 후보 조회, 신고 상태 변경, `/detect/health` placeholder가 구현되어 있다. | 실제 모델 추론 연결, 외부 스토리지, 배포 환경, 로그인/권한 관리 |
| 프론트엔드 | Next.js PWA에서 카메라, fake 탐지 overlay, GPS/방향 상태, TTS/진동, 신고 전송, `/admin` 신고 관리 화면이 구현되어 있다. | 서버 추론 모드, STT 음성 명령, 실제 모델 상태 UI, 실폰 설치 PWA 검증 |

## 구현된 범위

### Frontend

- `apps/web/`에 Next.js App Router 기반 PWA 구현
- 첫 화면은 랜딩 페이지가 아니라 보행 보조 화면
- 카메라 프리뷰, 탐지 박스 오버레이, GPS/방향/정확도 상태 표시
- `NEXT_PUBLIC_DETECTOR_MODE=fake` 기준 4개 클래스 fake 탐지 이벤트 생성
- 탐지 클래스별 TTS 문구와 진동 패턴 적용
- 신고 전송, 전송 상태 표시, 중복 신고 advisory 표시
- `/admin` 신고 관리 화면 구현
- Android USB reverse 기반 로컬 테스트 흐름 정리

### Backend

- `backend/`에 FastAPI API 서버 구현
- PostGIS DB는 `docker-compose.yml`의 `postgis/postgis:16-3.5` 사용
- Alembic 초기 마이그레이션에서 `postgis` extension과 `reports` 테이블 구성
- 구현된 주요 엔드포인트:
  - `GET /health`
  - `GET /detect/health`
  - `POST /reports`
  - `GET /reports`
  - `GET /reports/{report_id}`
  - `PATCH /reports/{report_id}/status`
  - `GET /reports/duplicate-check`
- 신고 이미지는 외부 스토리지 없이 `backend/uploads/`에 로컬 저장

### Docs

- `DESIGN.md`: 고대비, 저인지부하, 모바일 우선 UI 기준
- `docs/inference_contract.md`: fake/onnx/server 공통 탐지 이벤트 계약
- `docs/model_placeholder_systems.md`: 모델 미구현으로 인한 임시 시스템 목록
- `docs/frontend_handoff_without_model.md`: 모델 없이 UI/프론트 진행하는 기준
- 이 문서는 음성, 모델, 백엔드, 프론트엔드 현재 상태를 한 파일에서 확인하기 위한 요약본

## 최근 확인된 상태

- 프론트 개발 서버: 현재 `3000` 포트 listen 프로세스 없음. 필요 시 아래 실행 명령으로 다시 시작
- 백엔드 개발 서버: 현재 `8000` 포트 listen 프로세스 없음. 필요 시 아래 실행 명령으로 다시 시작
- ADB 상태: 현재 `adb devices`에는 `emulator-5554`만 표시됨. 실폰 테스트 전 실제 휴대폰이 목록에 보이는지 다시 확인해야 함
- 최근 작업 기준 `npm run lint`, `npm run typecheck`, `npm run build`는 통과한 상태
- `/detect/health`는 실제 모델 미연결 상태를 알려주는 placeholder로 사용 중
- `/admin`은 실제 신고 API를 바라보는 관리 화면으로 구성됨

## 로컬 실행 기준

### Backend

```bash
docker compose up -d db
.venv/bin/python -m alembic -c backend/alembic.ini upgrade head
CORS_ORIGINS=http://localhost:3000,http://127.0.0.1:3000 .venv/bin/python -m uvicorn backend.app.main:app --host 0.0.0.0 --port 8000
```

### Frontend

```bash
cd apps/web
NEXT_PUBLIC_API_BASE_URL=http://localhost:8000 npm run dev -- --hostname 0.0.0.0 --port 3000
```

### Android USB 테스트

```bash
adb devices
adb reverse tcp:3000 tcp:3000
adb reverse tcp:8000 tcp:8000
```

휴대폰 Chrome에서는 `http://localhost:3000`으로 접속한다. 카메라 권한은 HTTPS 또는 localhost 계열에서만 안정적으로 동작하므로, 현재는 LAN IP보다 USB reverse 흐름을 우선 사용한다.

## 현재 임시 시스템

- `fake detector`: 실제 모델 대신 탐지 이벤트와 bbox를 순환 생성
- `source: "fake"`: 모델 기반 결과가 아님을 이벤트 계약에서 명시
- `GET /detect/health`: 모델 서버 연결 전 상태 확인용 placeholder
- 브라우저 TTS/진동: 서버 기반 음성 모델이 아니라 Web Speech API와 Vibration API 기반
- 로컬 업로드: 운영 스토리지 대신 `backend/uploads/`에 이미지 저장

상세 내용은 `docs/model_placeholder_systems.md`에서 계속 관리한다.

## 남은 우선순위

1. 실폰을 다시 ADB에 연결하고 목걸이 착용 상태에서 카메라 각도, 흔들림, TTS/진동 인지성을 확인
2. 목걸이 착용 기준으로 탐지 화면의 불필요한 시각 의존 요소를 더 줄이고, 음성/진동 우선 흐름을 보강
3. fake detector와 동일한 `DetectionEvent` 계약을 구현하는 실제 모델 어댑터 작성
4. 학습 완료 모델의 class name, bbox 정규화, confidence 기준을 `docs/inference_contract.md`와 맞춰 검증
5. `/detect/health`를 실제 모델 서버 또는 ONNX 로컬 추론 상태와 연결
6. 신고 중복 판단을 운영 화면에서 더 명확히 보여주는 관리 기능 보강
7. 데모 전용 runbook을 별도 문서로 정리해 실행 순서, 실패 시 대체 흐름, 발표용 체크포인트를 고정

## 보류 중인 항목

- 실제 YOLO/ONNX 모델 연결
- 실제 학습 데이터셋 기반 성능 검증
- 백그라운드 PWA 동작 보장
- 서버 기반 STT/TTS 통합
- 외부 스토리지 업로드
- 배포 환경 구성
