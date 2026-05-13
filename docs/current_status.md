# WalkSafe Assist 현재 진행 상태

작성 기준일: 2026-05-13 KST

## 한 줄 요약

WalkSafe Assist는 현재 **PWA/백엔드 신고 흐름은 fake detector로 통합 가능**, **점자블록 YOLO v2 학습은 로컬 완료**, **로컬 STT/TTS 프로토타입은 실제 사람 음성 기준 PWA 연동 검토 가능** 단계다.

## 목적별 상태 문서

| 문서 | 용도 |
| --- | --- |
| `docs/model_training_status.md` | YOLO 데이터셋, v1/v2 학습 결과, 모델 리스크와 다음 작업 |
| `docs/voice_stt_tts_status.md` | 로컬 STT/TTS 서버, 합성/실제 음성 테스트 결과, PWA 연동 판단 |
| `docs/pwa_backend_status.md` | PWA, FastAPI, 신고 API, fake detector, 모델 미연결 상태 |
| `docs/local_voice_server_plan.md` | 로컬 음성 서버 실행 방법과 상세 실험 기록 |
| `PROJECT_PLAN.md` | 전체 일정과 우선순위 |

## 현재 완료된 것

- PWA 보행 화면, fake detector, 카메라 오버레이, GPS/방향 표시, TTS 토글, 신고 버튼 흐름 정리
- 관리자 `/admin` 신고 목록, 필터, 상세, 상태 변경 기능 정리
- FastAPI 신고 API, PostGIS 좌표 저장, 이미지 업로드 검증, 중복 신고 확인, `/detect` placeholder 구현
- AI Hub 513 `TL8/TL9/TS8/TS9` 기반 점자블록 데이터셋 v1/v2 로컬 생성
- `walksafe_kr_tactile_v1`, `walksafe_kr_tactile_v2_full` 학습 완료
- 로컬 음성 서버 초안, faster-whisper medium STT, Qwen3-TTS CustomVoice TTS 검증
- 실제 사람 음성 8개 기준 STT intent 평가 및 규칙 보강

## 아직 미완료인 것

- 실제 YOLO/ONNX 모델을 PWA 또는 백엔드 `/detect`에 연결
- `best.pt`를 test split으로 별도 검증한 결과 기록
- AI Hub 513 validation zip `VL1/VL2/VS1/VS2` 다운로드 및 외부 검증
- AI Hub 159 1인칭 보행영상 일부 다운로드, 프레임 추출, 실패 프레임 분석
- 킥보드/자전거, 공사물, 포트홀 한국 데이터 보강
- 직접 촬영 데이터 기반 실환경 보강
- STT 음성 명령을 PWA 녹음 UI와 연결
- TTS 음질 사람 청취 평가 및 정적 안내문 캐시 전략 확정

## GitHub 업로드 원칙

GitHub에는 문서, 설정, 경량 소스만 올린다. 아래 항목은 로컬에서만 유지한다.

- AI Hub 원본 zip: `TL8.zip`, `TL9.zip`, `TS8.zip`, `TS9.zip`, `VL*.zip`, `VS*.zip`
- 데이터셋 이미지/라벨: `datasets/**/images/**`, `datasets/**/labels/**`
- 학습 결과: `runs/`, `*.pt`, `*.onnx`, `*.engine`, `*.tflite`
- 음성 샘플/출력/실험 결과: `samples/voice/stt/*`, `outputs/voice/*`, `logs/`

## 다음 우선순위

1. `best.pt`로 `walksafe_kr_v2` test split 검증을 따로 실행하고 결과를 문서화한다.
2. AI Hub 513 validation 파일을 확보해 외부 검증을 한다.
3. v2 모델을 ONNX로 변환해 PWA 또는 `/detect` 서버 추론 연결 가능성을 확인한다.
4. 음성 서버 `POST /speech/stt`를 PWA 녹음 UI와 연결한다.
5. 한국 보행 실환경 데이터와 AI Hub 159 영상 프레임으로 v3/v4 보강 데이터를 만든다.
