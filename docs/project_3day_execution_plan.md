# WalkSafe Assist 3-Day Execution Plan

> **문서 상태(2026-06-02): superseded.** 2026-05-14~16 기간 계획 기록이다. 현재 작업은 `docs/current_status.md`와 Android/unified 문서를 기준으로 한다.


기준일: 2026-05-13
실행 기간: 2026-05-14 ~ 2026-05-16
기준 자료:

- `2026 ICT 한이음 드림업 프로젝트 개요서.pdf`
- `2026년 한이음 드림업 프로젝트 수행계획서.pdf`
- `docs/current_status.md`
- `docs/pwa_backend_status.md`
- `docs/model_v2_status.md`
- `docs/voice_stt_tts_status.md`
- 현재 `main` 브랜치 코드

## 목적

프로젝트 개요서와 수행계획서의 목표를 기준으로 현재 구현 상태를 정리하고, 프론트엔드, 백엔드, 모델 학습, STT/TTS를 3일 단위로 실행 가능한 체크리스트로 나눈다.

이번 3일의 목적은 새 기능을 무리하게 확장하는 것이 아니라, 다음 네 가지를 명확히 확인하는 것이다.

1. PWA 보행자 화면이 실제 스마트폰 사용 조건에서 데모 가능한지 확인한다.
2. FastAPI/PostGIS 신고 API와 `/detect` 연결 지점의 계약을 고정한다.
3. YOLO v2 모델을 test split 및 외부 validation 기준으로 검증해 실제 연결 가능 여부를 판단한다.
4. 로컬 STT/TTS가 PWA 음성 명령으로 연결 가능한 수준인지 실폰 기준으로 확인한다.

## 현재 상태 요약

### Frontend

- Next.js PWA 기반 보행자 화면과 관리자 화면이 있다.
- 카메라, fake detection, GPS/방향, TTS/진동, 신고 전송, `/admin` 신고 관리 흐름이 구현되어 있다.
- 실제 YOLO 모델과 STT 서버는 아직 PWA에 직접 연결되지 않았다.

상세 문서: `docs/frontend_3day_execution_plan.md`

### Backend

- FastAPI/PostGIS 기반 신고 API가 있다.
- 이미지 업로드 검증, 중복 신고 확인, 신고 상태 변경, 기본 `/detect/health`와 `/detect` placeholder가 있다.
- 실제 모델 추론 adapter, 운영 readiness, 관리자 API 보호선은 아직 확정 전이다.

상세 문서: `docs/backend_3day_execution_plan.md`

### Model Training

- AI Hub 513 TL8/TL9/TS8/TS9 기반 `walksafe_kr_v2` 학습이 50 epoch 완료되었다.
- v2 validation 기준 주요 지표는 precision `0.73656`, recall `0.58380`, mAP50 `0.66394`, mAP50-95 `0.49194`이다.
- test split 별도 검증은 2026-05-13에 실행했고, test 기준 precision `0.728`, recall `0.581`, mAP50 `0.657`, mAP50-95 `0.481`을 기록했다.
- AI Hub 513 외부 validation, ONNX export, 4개 클래스 한국 데이터 보강이 남아 있다.
- 데이터셋, runs, weights, zip, 이미지, 라벨은 로컬 전용이며 GitHub에 올리지 않는다.

상세 문서: `docs/model_training_3day_execution_plan.md`

### STT/TTS

- 로컬 `voice/server.py` 기반 음성 서버가 있다.
- STT는 `faster-whisper medium`, TTS는 `Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice`를 사용한다.
- 실제 사람 음성 테스트에서 known intent 7개 기준 7/7 성공, 평균 지연 `0.457s`, p95 `0.572s`를 기록했다.
- PWA는 아직 로컬 STT 서버와 연결되지 않았고, 현재는 브라우저 `speechSynthesis`와 진동 중심이다.

상세 문서: `docs/voice_stt_tts_3day_execution_plan.md`

## 3-Day Gate

### 2026-05-14

- 프론트엔드: 화면 상태 매트릭스와 접근성/모바일 점검 기준 확정
- 백엔드: API 계약, 로컬 재현성, 오류 계약, DB 스키마 점검
- 모델: v2 artifact 동결, test split 검증 절차 확정, val/test 비교 기준 확정
- STT/TTS: 음성 서버 기준선, 실제 음성 STT 회귀 테스트, intent schema 확정

완료 조건: 각 영역에서 "현재 구현으로 데모 가능한 것"과 "연결 전 대기 상태"가 분리되어 문서화된다.

### 2026-05-15

- 프론트엔드: 실폰 PWA 보행 화면, TTS/진동, 신고 E2E, 관리자 화면 확인
- 백엔드: 업로드/스토리지 경계, 모델 adapter 설계, 테스트 보강 범위 결정
- 모델: AI Hub 513 외부 validation 확보/변환/평가 준비, 실패 프레임 샘플링 기준 수립
- STT/TTS: PWA 녹음 UI 및 `/speech/stt` 연결 방식 검증, TTS 청취 평가

완료 조건: 실기기와 실제 데이터 기준으로 다음 연결 작업의 막힘 지점이 드러난다.

### 2026-05-16

- 프론트엔드: model waiting/server inference/STT UI/fallback 상태 확정
- 백엔드: `/detect` 실제 연결 리허설 또는 unavailable 사유 고정, 배포/보안 점검
- 모델: ONNX export, PT vs ONNX 동등성, latency 측정, v3/v4 데이터 보강 결정
- STT/TTS: 실폰 STT 종단 테스트, 보행 조건 재평가, voice fallback 점검

완료 조건: 2026-05-17 이후 작업이 PR 단위로 나뉘고, 모델/PWA/STT 연결 go/no-go가 결정된다.

## 운영 원칙

- 로컬 데이터셋, 학습 결과물, `.pt`, `.onnx`, zip, 이미지, 라벨, `runs/`, `outputs/` 산출물은 GitHub에 올리지 않는다.
- 문서는 GitHub에 올려도 되지만, 개인정보성 음성 샘플명이나 민감 경로는 필요한 수준으로만 기록한다.
- 실제로 연결되지 않은 기능은 "완료"라고 쓰지 않고 "대기", "placeholder", "연결 예정"으로 구분한다.
- 실폰, AI Hub 다운로드, 외부 validation처럼 수동 조건이 필요한 항목은 완료 기준과 막힘 조건을 함께 기록한다.
- 모델 재학습은 test/external validation 결과를 먼저 보고 결정한다.
