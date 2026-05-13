# STT/TTS 현재 상황 정리

작성일: 2026-05-13

## 목적

이미지 추론 모델 학습이 진행되는 동안, WalkSafe Assist에 붙일 로컬 STT/TTS 기능의 현재 상태와 다음 작업을 따로 추적한다.

## 현재 결론

- STT/TTS는 아직 최종 기능 완성이 아니다.
- 현재까지 확인된 내용은 "로컬 GPU에서 모델 실행이 가능하다"는 smoke test 수준이다.
- 파인튜닝은 아직 하지 않는다.
- 실제 사람 음성 테스트와 앱 연동 가능성 확인 후에만 파인튜닝 필요 여부를 판단한다.

## 기준 하드웨어

| 항목 | 값 |
| --- | --- |
| GPU | RTX 5070 Ti |
| CUDA | 설치됨 |
| 실행 위치 | 별도 GPU 컴퓨터 |
| 현재 맥북 repo 동기화 상태 | 음성 서버 코드와 테스트 결과 파일은 아직 보이지 않음 |

## 현재 보고된 음성 작업 결과

| 항목 | 상태 |
| --- | --- |
| 음성 전용 가상환경 | `.venv-voice` 생성 완료로 보고됨 |
| STT 모델 | `faster-whisper medium` 사용 |
| TTS 모델 | `Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice` 사용 |
| 음성 서버 | `voice/server.py`, 포트 `9001` 생성 완료로 보고됨 |
| STT 테스트 스크립트 | `scripts/test_stt.py` 생성 완료로 보고됨 |
| TTS 테스트 스크립트 | `scripts/test_tts.py` 생성 완료로 보고됨 |
| 진행 문서 | `docs/local_voice_server_plan.md` 생성 완료로 보고됨 |
| Qwen3-TTS Base | `generate_voice_design` 미지원으로 실패 |
| Qwen3-TTS CustomVoice | 1문장 wav 생성 성공 |
| TTS 6문장 생성 | 성공, 평균 약 `2.377초` |
| 합성 음성 STT 테스트 | `7/7`, intent 기준 `100%`, 평균 약 `0.494초` |
| Qwen3-TTS CUDA 메모리 | 약 `2153 MiB` |

## 현재 한계

| 항목 | 설명 |
| --- | --- |
| STT 실제 성능 | 합성 음성 기반 테스트라 실제 사람 목소리 성능으로 볼 수 없음 |
| TTS 실시간성 | 평균 `2.377초`라 위험 경고를 매번 실시간 생성하기에는 느림 |
| 앱 연동 | PWA 마이크 녹음, `/speech/stt` 호출, intent 처리, 음성 재생 연결은 아직 확인되지 않음 |
| 서버 동기화 | 이 맥북 repo에는 `voice/server.py`, `scripts/test_stt.py`, `scripts/test_tts.py`, `docs/local_voice_server_plan.md`가 아직 없음 |
| 파인튜닝 판단 | 실제 사람 음성 테스트 전이라 판단 불가 |

## 결정 사항

1. STT/TTS는 pretrained 로컬 추론부터 사용한다.
2. 지금은 파인튜닝하지 않는다.
3. STT는 실제 사람 음성으로 intent 정확도를 다시 측정한다.
4. TTS 위험 경고문은 실시간 생성보다 미리 생성한 wav 캐시를 우선한다.
5. 브라우저 기본 TTS는 fallback으로 남긴다.
6. YOLO 이미지 학습과 음성 환경은 분리한다.

## 사람 음성 STT 테스트 계획

입력 폴더:

```text
samples/stt/myvoice
```

테스트 대상 문장:

```text
신고해
현재 위험 신고해
음성 꺼
음성 켜
다시 말해줘
목적지 서울역으로 설정해
길 안내 시작해
```

기록할 항목:

| 항목 | 설명 |
| --- | --- |
| 파일명 | 테스트 음성 파일 |
| expected intent | 파일명 또는 수동 라벨 기준 정답 |
| transcript | Whisper 출력 |
| predicted intent | intent rule 출력 |
| latency | 처리 시간 |
| result | 성공/실패/수동검토 |
| note | 오류 원인 또는 비고 |

통과 기준:

| 기준 | 목표 |
| --- | --- |
| intent 정확도 | 90% 이상 |
| 평균 지연시간 | 1초 이하 |
| p95 지연시간 | 2초 이하 |

## TTS 적용 계획

위험 경고문은 Qwen3-TTS로 매번 생성하지 않고 미리 생성해서 캐시한다.

캐시 후보 문장:

```text
전방에 점자블록 파손이 있습니다.
오른쪽에 방치된 킥보드가 있습니다.
공사 장애물이 감지되었습니다. 속도를 줄이세요.
노면 파임이 감지되었습니다. 전방을 확인하세요.
신고가 저장되었습니다.
목적지를 다시 말씀해 주세요.
```

권장 구조:

| 용도 | 방식 |
| --- | --- |
| 긴급 위험 경고 | 캐시된 wav/mp3 즉시 재생 |
| 신고 성공/실패 | 캐시된 wav/mp3 또는 브라우저 TTS |
| 목적지 재질문/긴 안내 | Qwen3-TTS 실시간 생성 가능 |
| 서버 실패 시 | 브라우저 `speechSynthesis` fallback |

## 필요한 API 형태

음성 서버 후보:

```text
GET  /health
POST /speech/stt
POST /speech/tts
POST /speech/intent
GET  /speech/cached/{key}
```

## 다음 작업

1. GPU 컴퓨터에서 생성된 음성 관련 파일을 이 repo로 복사한다.
   - `voice/server.py`
   - `scripts/test_stt.py`
   - `scripts/test_tts.py`
   - `docs/local_voice_server_plan.md`
   - 필요 시 `requirements-voice.txt`
2. `samples/stt/myvoice` 실제 사람 음성 파일로 STT 테스트를 실행한다.
3. 테스트 결과를 CSV와 이 문서에 반영한다.
4. TTS 캐시 wav 생성 구조를 추가한다.
5. YOLO 추론 서버와 동시에 실행해 VRAM을 확인한다.
6. PWA 마이크 녹음과 `/speech/stt` 연동을 설계 또는 구현한다.

## 현재 판단

| 질문 | 현재 답 |
| --- | --- |
| STT/TTS 모델이 로컬 GPU에서 도는가? | 예, 보고된 결과 기준 가능 |
| STT가 실제 사람 음성에서 충분한가? | 아직 모름 |
| TTS가 위험 경고 실시간 생성에 적합한가? | 현재 평균 지연 기준으로는 부적합, 캐싱 필요 |
| 지금 파인튜닝해야 하는가? | 아니오 |
| 앱에 바로 붙일 수 있는가? | 아직 아님, 서버 파일 동기화와 PWA 연동 필요 |
