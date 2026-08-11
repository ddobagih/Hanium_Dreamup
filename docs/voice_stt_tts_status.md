# 로컬 STT/TTS 진행 상태

작성 기준일: 2026-06-02 KST

## 2026-07-02 보정

- 서버 기반 STT/TTS는 현재 **프로토타입/후속 UX lane**이다.
- Android native 주경로는 STT 서버 완료를 기다리지 않는다.
- Android native에는 `SpeechRecognizer` 기반 "신고해줘" 음성 신고 버튼 경로가 있고, 인식 성공 시 explicit `/reports/v2` 신고 route로 이어진다.
- 현재 P0는 Android APK에서 bbox overlay, ARCore depth, TFLite detector 좌표 정합을 확인하는 것이다.
- `create_report` intent의 정책 의미는 유지한다. Android 실폰 mic/TTS 청취, 보행 중 인식률, TalkBack 충돌 평가는 후속 Device evidence다.
- Cloud/유료 STT/TTS, 외부 공개 URL, 비용 발생 API는 승인 전 사용하지 않는다.

## 범위

이 문서는 WalkSafe Assist 음성 명령과 안내음 프로토타입의 현재 상태를 요약한다. 원본 음성 샘플, 생성 WAV, CSV, 로그는 로컬 산출물로만 보관하고 GitHub에는 요약 수치와 코드/문서만 올린다.

## 구현 상태

- 음성 전용 가상환경: `.venv-voice`
- 서버: `voice/server.py`
- 포트: `9001`
- API 초안:
  - `GET /health`
  - `POST /speech/intent`
  - `POST /speech/stt`
  - `POST /speech/tts`
- PWA 연동: `apps/web/lib/voice-api.ts`, `apps/web/app/_walksafe/hooks/useVoiceCommands.ts`
- intent/TTS 정책 회귀 테스트: `tests/test_voice_intents.py`, `tests/test_voice_tts.py`
- STT: `faster-whisper medium`
- TTS: `Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice`
- 유료/클라우드 API: 사용하지 않음
- 파인튜닝: 수행하지 않음
- Android native: `SpeechRecognizer` 버튼형 음성 신고. raw audio/transcript 서버 저장 없음.

## 최신 policy 요약

- 최신 보이스 커맨드 전략 기준 문서는 `docs/walksafe-v2/voice_command_strategy.md`다.
- 보이스 커맨드는 모든 문장을 녹음해서 매칭하지 않고, STT 결과를 정규화한 뒤 intent rule/fuzzy matching과 slot 추출로 후처리한다.
- `create_report`는 자동 신고보다 우선하는 음성 요청 신고 intent다.
- unknown 또는 저신뢰 명령은 실행하지 않고 재질문한다.
- 위험 안내와 길안내 TTS 중재는 위험 active 상태를 우선한다.
- 자동 타일 손상 신고는 사용자에게 굳이 말하지 않는다.

## 지원 intent

| intent | 의미 |
| --- | --- |
| `create_report` | 현재 위험 신고. v2에서는 음성 요청 신고로 자동 신고보다 우선 |
| `voice_on` | 음성 안내 켜기 |
| `voice_off` | 음성 안내 끄기 |
| `repeat_last` | 마지막 안내 반복 |
| `get_current_location` | 현재 위치 안내 |
| `set_destination` | 목적지 설정 |
| `start_navigation` | 길 안내 시작 |
| `unknown` | 미지원 또는 불확실 |

## 검증된 로컬 범위

- 실제 사람 음성 sample 기준 known intent 성공 기록이 있다.
- 합성 음성 기반 STT smoke 기록이 있다.
- intent/TTS 정책 회귀 테스트가 존재한다.
- `/speech/tts` fallback/cache 정책이 구현되어 있다.

주의: 이 근거는 Android mic/TTS/field evidence가 아니다.

## 아직 남은 것

1. Android 실폰 mic E2E에서 "신고해줘" 인식, GPS missing 안내, duplicate 안내, 성공/실패 TTS를 확인한다.
2. Android 또는 브라우저/실폰 mic E2E에서 transcript, intent, confidence, UI action 기록 정책을 결정한다.
3. TTS HTTP 2회 cache header와 실제 스피커 청취 평가.
4. TalkBack/음성 안내 충돌 평가.
5. 보행 중 잡음, 바람, 휴대폰 마이크, 복수 화자 샘플 재평가.

## 판단

- 현재 faster-whisper medium은 지원 명령 기준 프로토타입에 충분하다.
- 특정 표현 오류는 intent rule 보강으로 해결됐다.
- 하지만 지금 `STT 서버`를 별도 P0로 진행할 이유는 없다. Android native 실기기 coordinate/depth gate가 먼저다.
