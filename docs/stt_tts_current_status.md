# STT/TTS 현재 상황 정리

작성일: 2026-05-25

## 문서 위치

상세 기준 문서는 아래 파일로 나누어 관리한다.

- `docs/voice_stt_tts_status.md`: 현재 판단 요약
- `docs/walksafe-v2/voice_command_strategy.md`: 최신 보이스 커맨드 전략 source of truth
- `docs/local_voice_server_plan.md`: 실행 방법과 실험 상세 기록

이 문서는 Figma/프론트/백엔드 인계 문서에서 참조하기 쉬운 짧은 요약이다.

## 2026-05-25 업데이트

- STT intent 실행 정책을 서버 응답에 명시했다: `action=execute|reprompt`, `should_execute`, `reason`, `prompt`.
- `unknown`, 낮은 신뢰도(`<0.7`), 목적지 slot 누락은 앱 동작을 실행하지 않고 재질문한다.
- TTS 서버는 `VOICE_TTS_FALLBACK_WAV`가 설정되어 있으면 RuntimeError 시 로컬 fallback WAV를 반환한다.
- PWA는 녹음 시작 전에 현재 브라우저 TTS를 중단해 listening/speaking 충돌을 줄인다.
- 위험 안내가 길안내보다 우선되도록 프론트 우선순위 helper와 정책 smoke를 추가했다.

## 현재 결론

- 로컬 음성 서버 코드는 현재 repo에 있다.
- STT/TTS는 최종 제품 기능이 아니라 PWA 연동 전 프로토타입 단계다.
- Android native에는 서버 STT와 별개로 `SpeechRecognizer` 기반 "신고해줘" 음성 신고 버튼 경로가 있다. raw audio/transcript는 서버에 저장하지 않고, 인식 성공 시 explicit report upload route를 호출한다.
- `faster-whisper medium`은 현재 지원 명령 기준으로 PWA 프로토타입에 붙일 수 있는 수준이다.
- 파인튜닝은 아직 하지 않는다.
- TTS 위험 경고는 매번 실시간 생성하지 말고 캐시된 WAV를 우선 사용한다.
- 보행 중 사용자에게 들려줄 것은 위험 경고, 길 안내, 사용자 요청 처리 결과 중심이다.
- 자동 신고 성공은 기본적으로 음성 안내하지 않고, 음성 요청 신고는 완료/실패 안내가 필요하다.

## 구현 파일

```text
requirements-voice.txt
voice/server.py
voice/stt.py
voice/tts.py
voice/intents.py
apps/web/lib/voice-api.ts
apps/web/app/_walksafe/hooks/useVoiceCommands.ts
tests/test_voice_intents.py
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
| 실제 사람 음성 known intent | 8개 |
| 실제 사람 음성 성공 | 8개 |
| 실제 사람 음성 known-intent accuracy | 100.00% |
| 실제 사람 음성 평균 지연 | 1.807초 |
| 실제 사람 음성 p95 지연 | 2.048초 |
| TTS 생성 | 6/6 성공 |
| TTS 평균 생성 시간 | 2.377초 |
| TTS CUDA memory probe | 약 2153 MiB |
| voice intent/TTS policy regression | 50개 통과 |

`지금어디야.m4a`는 현재 `get_current_location`으로 처리된다. 오래된 `needs_manual_review`/미지원 표현은 최신 상태가 아니다.

## 현재 지원 intent

```text
create_report
voice_on
voice_off
repeat_last
get_current_location
set_destination
start_navigation
unknown
```

보이스 커맨드는 모든 문장을 녹음해서 매칭하는 방식이 아니라, STT 결과를 정규화한 뒤 intent rule/fuzzy matching과 slot 추출로 후처리하는 전략을 따른다. 최신 설명은 `docs/walksafe-v2/voice_command_strategy.md`를 기준으로 한다.

## PWA 적용 방향

1. PWA에서 짧은 음성 녹음을 만든다.
2. 로컬 음성 서버 `POST /speech/stt`로 전송한다.
3. 반환된 `intent`와 `slots`를 기존 PWA 상태/신고 흐름에 연결한다.
4. `create_report`는 v2에서 음성 요청 신고로 처리하고 자동 신고보다 우선한다.
5. 위험 경고/길 안내/음성 요청 처리 결과 TTS는 캐시 WAV 또는 브라우저 `speechSynthesis`를 우선 사용한다.
6. 보행 중 잡음, 휴대폰 마이크, 복수 화자 샘플을 추가 수집해 재평가한다.
