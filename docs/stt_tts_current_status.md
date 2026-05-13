# STT/TTS 현재 상황 정리

작성일: 2026-05-13

## 문서 위치

상세 기준 문서는 아래 두 파일로 나누어 관리한다.

- `docs/voice_stt_tts_status.md`: 현재 판단 요약
- `docs/local_voice_server_plan.md`: 실행 방법과 실험 상세 기록

이 문서는 Figma/프론트/백엔드 인계 문서에서 참조하기 쉬운 짧은 요약이다.

## 현재 결론

- 로컬 음성 서버 코드는 현재 repo에 있다.
- STT/TTS는 최종 제품 기능이 아니라 PWA 연동 전 프로토타입 단계다.
- `faster-whisper medium`은 현재 지원 명령 기준으로 PWA 프로토타입에 붙일 수 있는 수준이다.
- 파인튜닝은 아직 하지 않는다.
- TTS 위험 경고는 매번 실시간 생성하지 말고 캐시된 WAV를 우선 사용한다.

## 구현 파일

```text
requirements-voice.txt
voice/server.py
voice/stt.py
voice/tts.py
voice/intents.py
scripts/test_stt.py
scripts/test_tts.py
```

## 현재 모델

| 항목 | 값 |
| --- | --- |
| STT | `faster-whisper medium` |
| TTS | `Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice` |
| 서버 포트 | `9001` |
| 유료 API | 사용 안 함 |
| 파인튜닝 | 수행 안 함 |

## 검증 결과

| 항목 | 결과 |
| --- | ---: |
| 합성 음성 STT | 7/7 intent 성공 |
| 합성 음성 STT 평균 지연 | 0.494초 |
| 실제 사람 음성 파일 | 8개 |
| 실제 사람 음성 known intent | 7개 |
| 실제 사람 음성 성공 | 7개 |
| 실제 사람 음성 known-intent accuracy | 100.00% |
| 실제 사람 음성 평균 지연 | 0.457초 |
| 실제 사람 음성 p95 지연 | 0.572초 |
| TTS 생성 | 6/6 성공 |
| TTS 평균 생성 시간 | 2.377초 |
| TTS CUDA memory probe | 약 2153 MiB |

`지금어디야.m4a`는 현재 intent schema에 위치 질의 intent가 없어 `needs_manual_review`로 처리했다. 제품에서 이 명령을 지원하려면 `get_current_location` 같은 intent를 추가한다.

## 현재 지원 intent

```text
create_report
voice_on
voice_off
repeat_last
set_destination
start_navigation
unknown
```

## PWA 적용 방향

1. PWA에서 짧은 음성 녹음을 만든다.
2. 로컬 음성 서버 `POST /speech/stt`로 전송한다.
3. 반환된 `intent`와 `slots`를 기존 PWA 상태/신고 흐름에 연결한다.
4. 위험 경고 TTS는 캐시 WAV 또는 브라우저 `speechSynthesis`를 우선 사용한다.
5. 보행 중 잡음, 휴대폰 마이크, 복수 화자 샘플을 추가 수집해 재평가한다.
