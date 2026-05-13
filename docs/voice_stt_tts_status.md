# 로컬 STT/TTS 진행 상태

작성 기준일: 2026-05-13 KST

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
- STT: `faster-whisper medium`
- TTS: `Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice`
- 유료/클라우드 API: 사용하지 않음
- 파인튜닝: 수행하지 않음

## 지원 intent

| intent | 의미 |
| --- | --- |
| `create_report` | 현재 위험 신고 |
| `voice_on` | 음성 안내 켜기 |
| `voice_off` | 음성 안내 끄기 |
| `repeat_last` | 마지막 안내 반복 |
| `set_destination` | 목적지 설정 |
| `start_navigation` | 길 안내 시작 |
| `unknown` | 미지원 또는 불확실 |

실제 사람 음성 테스트 후 다음 alias를 intent rule에 추가했다.

- `음성꼭`, `음성끅` -> `voice_off`
- `길었네시작`, `길시작` -> `start_navigation`

## 합성 음성 기반 STT 테스트

Qwen3-TTS로 만든 합성 명령 7개를 faster-whisper medium으로 인식했다.

| metric | value |
| --- | ---: |
| samples | 7 |
| intent success | 7 |
| intent accuracy | 100% |
| average latency | 0.494 sec |

합성 음성은 깨끗하기 때문에 실제 보행 환경 성능을 보장하지 않는다. 이 결과는 로컬 STT 파이프라인 smoke test로만 본다.

## 실제 사람 음성 STT 테스트

확인한 실제 폴더:

```text
samples/voice/stt/myvoice
```

요청 경로 `samples/stt/myvoice`와 오타 후보 `smaples/stt/myvoice`는 없었다. 원본 오디오는 삭제하거나 이동하지 않았다.

입력 파일:

```text
길 안내 시작해.m4a
다시말해줘.m4a
목적지 서울역으로 설정해.m4a
신고해.m4a
음성꺼.m4a
음성켜.m4a
지금어디야.m4a
현재 위험 신고해.m4a
```

최종 결과:

| metric | value |
| --- | ---: |
| total files | 8 |
| known intent files | 7 |
| success | 7 |
| failure | 0 |
| needs manual review | 1 |
| known-intent accuracy | 100.00% |
| average latency | 0.457 sec |
| p95 latency | 0.572 sec |

지원 intent 기준 최종 실패는 없다. `지금어디야.m4a`는 현재 intent schema에 위치 질의 intent가 없어 `needs_manual_review`로 처리했다.

초기 실패 2개는 모델 파인튜닝 문제가 아니라 후처리 부족이었다.

| file | transcript | 조치 |
| --- | --- | --- |
| `길 안내 시작해.m4a` | `길었네 시작해` | `길...시작` 계열 alias 추가 |
| `음성꺼.m4a` | `음성꼭` | 짧은 명령 종성 오류 alias 추가 |

## TTS 테스트

`Qwen/Qwen3-TTS-12Hz-0.6B-Base`는 `generate_voice_design`을 지원하지 않아 실패했다. 기본 모델은 reference voice 없이 시연 가능한 `Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice`로 바꿨다.

CustomVoice 결과:

| metric | value |
| --- | ---: |
| prompts | 6 |
| success | 6 |
| average generation time | 2.377 sec |
| max generation time | 2.950 sec |
| sample rate | 24000 Hz |
| CUDA memory probe | 약 2153 MiB |

실시간 위험 경고는 매번 TTS를 생성하지 말고 자주 쓰는 문장을 WAV로 캐시해야 한다.

## 판단

- 현재 faster-whisper medium은 지원 명령 기준 PWA 프로토타입에 충분하다.
- 실제 사람 음성 known-intent 정확도가 90% 기준을 넘었으므로 파인튜닝은 지금 하지 않는다.
- 특정 표현 오류는 intent rule 보강으로 해결됐다.
- 평균 0.457초, p95 0.572초라 STT 자체 지연은 프로토타입 기준을 만족한다.
- 브라우저 녹음, 업로드, 서버 왕복 지연은 아직 별도 측정이 필요하다.

## 다음 작업

1. PWA에서 짧은 명령 녹음 후 `POST /speech/stt`로 전송한다.
2. `지금 어디야?`를 지원할지 결정하고 필요하면 `get_current_location` intent를 추가한다.
3. 보행 중 잡음, 바람, 휴대폰 마이크, 복수 화자 샘플을 더 모아 재평가한다.
4. TTS 안내문은 데모 전 사람이 직접 청취해 속도, 억양, 명료도를 평가한다.
5. 자주 쓰는 위험 경고는 `outputs/voice/cache`에 미리 생성해 지연을 줄인다.
