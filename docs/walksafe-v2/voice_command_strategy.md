# WalkSafe v2 voice command strategy

- 기준일: 2026-07-11 KST
- 구현 위치: `voice/intents.py`, `voice/server.py`, `apps/web/lib/voice-api.ts`, `apps/web/app/_walksafe/hooks/useVoiceCommands.ts`
- 상태: rule-based intent 1차 구현과 regression test 추가. 전체 문장 녹음/나열 방식은 사용하지 않는다.

## 2026-07-10 현재 우선순위 보정

- 주 사용자 앱 경로는 Web/PWA다. local STT 응답은 Web hook callback으로 실행되고 사용자 안내는 브라우저 `speechSynthesis`를 사용한다.
- Android native는 ARCore/depth/TFLite 실험·검증 보조 경로다. Web/PWA 브라우저·Release evidence와 Android Device evidence는 서로 대체하지 않는다.
- Android native에는 별도 `SpeechRecognizer` 기반 "신고해줘" 버튼 경로가 explicit report upload/TTS 정책에 연결되어 있다.
- Web/PWA 및 Android 각각의 실폰 mic/TTS 청취, TalkBack, 보행 중 인식률은 아직 Device PASS가 아니다.

## 2026-07-08 현재 보정

- backend `GET /navigation/destinations/search`는 Web/PWA navigation hook과 Android 목적지 검색 UI에서 사용한다.
- 이 문서의 주 제품 흐름은 Web/PWA/server intent 전략이며 Android native 음성은 별도 보조 UX로 구분해서 읽는다.
- Android native voice는 현재 명시 신고 버튼 중심이며, 목적지 설정/후보 선택/길안내 시작까지의 음성 UX는 아직 Device PASS가 아니다.

## 실행 경계

| 경로 | 인식/분류 | 동작 실행 | 음성 출력 |
|---|---|---|---|
| local voice server | faster-whisper + `voice/intents.py` | 실행하지 않고 intent/slot 반환 | Qwen3-TTS WAV API |
| Web/PWA | local `/speech/stt` 응답 사용 | React hook callback | 브라우저 `speechSynthesis` |
| Android native | 플랫폼 `SpeechRecognizer`, 신고 phrase 확인 | 명시 신고 경로만 연결 | 플랫폼 `TextToSpeech` |

local intent `score`는 rule마다 정한 heuristic 값이며 faster-whisper의 음향 confidence가 아니다. 현재 fuzzy matcher, 학습형 NLU fallback, intent telemetry 저장은 없다.

## 1. 기본 방향

보이스 커맨드는 사용자가 말할 수 있는 모든 문장을 미리 녹음하거나 하드코딩하는 방식으로 풀지 않는다.

권장 흐름:

```text
사용자 음성
  -> STT
  -> 정규화
  -> intent 분류
  -> slot 추출
  -> 신뢰도/안전 정책 확인
  -> 앱 동작 실행 또는 재질문
```

예를 들어 “이거 신고해줘”, “여기 위험 신고”, “지금 발견한 거 저장해”는 모두 같은 `create_report` intent로 묶는다.

## 2. 현재 intent 목록

| intent | 목적 | 현재 처리 |
|---|---|---|
| `create_report` | 현재 탐지된 신고 가능 대상 신고 | server는 분류만 한다. Web/Android handler가 tactile damage를 `/reports/v2`, `trigger=voice`로 저장 |
| `voice_on` | 음성 안내 켜기 | Web handler 지원. Android local-server intent와 미연결 |
| `voice_off` | 음성 안내 끄기 | Web handler 지원. Android local-server intent와 미연결 |
| `repeat_last` | 최근 안내 반복 | Web handler 지원 |
| `get_current_location` | 현재 위치 안내 | Web handler가 GPS 상태 기반 문구 생성 |
| `set_destination` | 목적지 저장 | Web handler가 destination slot을 검색 hook에 전달 |
| `cancel_destination` | 목적지 변경 취소/현재 목적지 해제 | Web handler가 활성 길안내와 목적지 상태를 함께 정리 |
| `select_destination_candidate` | 목적지 후보 선택 | Web handler가 1-based 후보 번호를 선택 |
| `start_navigation` | 길 안내 시작 | Web handler가 `/navigation/walking` 경로 요청 |
| `reroute_navigation` | 경로 재탐색 | Web handler가 navigation callback 재호출 |
| `stop_navigation` | 길 안내 중지 | Web handler가 navigation 상태 중지 |
| `next_navigation_instruction` | 다음 경로/안내 질의 | 활성 TMAP route의 다음 guide point를 답하고, 경로가 없으면 없다고 안내 |
| `unknown` | 이해 실패 또는 안전 no-op | 실행하지 않고 재발화 요청 |

## 3. 신고 관련 정책

- 사용자가 명시적으로 “신고해줘”라고 요청하면 자동 신고보다 우선한다.
- 음성 요청 신고는 자동 신고 cooldown을 우회한다.
- 현재 신고 대상은 `unified_walksafe` 또는 legacy `custom_tactile`의 `damaged_tactile_block`이다.
- 일반 객체는 신고하지 않는다.
- 음성 요청 신고는 완료/실패를 짧게 TTS로 알려야 한다.
- 자동 신고는 사용자가 들어야 할 안내가 아니므로 성공 TTS를 하지 않는다.
- Web server-v2 음성 신고는 최대 3초 이내의 동일 분석 프레임에서 detection, 촬영 시각, 이미지를 함께 사용한다. 최신 분석 프레임이 없으면 신고를 실행하지 않는다.
- 녹음 중 화면이 background/pagehide로 바뀌거나 마이크 track이 종료되면 partial audio를 업로드하지 않고 명령을 취소한다.
- Web STT는 browser `MediaRecorder`로 최대 5초 audio를 같은 origin BFF의 `/speech/stt`에 보내고 local faster-whisper/rule intent 결과를 받는다. 출력은 browser `speechSynthesis`이며 Qwen3-TTS HTTP WAV는 별도 prototype으로 주 Web 피드백 경로에 연결하지 않는다.

## 4. 모든 경우의 수를 녹음하지 않는 방법

### 4.1 1차: rule-based intent

현재 구현처럼 자주 쓰는 표현과 STT 오인식 후보를 intent별 키워드/패턴으로 묶는다.

예:

- `create_report`: “신고”, “위험 신고”, “현재 위험 신고”, “싱고”
- `voice_off`: “음성 꺼”, “소리 꺼”, “말하지 마”, “음소거”
- `repeat_last`: “다시 말해줘”, “반복해”, “한 번 더”

장점:

- 빠르고 오프라인/로컬 친화적이다.
- 안전 동작을 예측하기 쉽다.
- 테스트 작성이 쉽다.

한계:

- 새 표현을 지속적으로 추가해야 한다.
- 복합 명령과 긴 자연어에는 약하다.

### 4.2 2차: NLU fallback 후보

현재 구현에는 NLU fallback이 없다. 향후 heuristic score가 낮거나 `unknown`일 때만 더 비싼 NLU를 붙이는 후보를 검토한다.

가능한 방식:

- 소형 intent classifier 학습
- 로컬 LLM 또는 서버 LLM으로 intent/slot JSON 추출
- embedding similarity로 intent 예문과 매칭

fallback도 앱 동작을 바로 실행하지 말고 아래 안전 규칙을 따라야 한다.

- confidence가 낮으면 “다시 말씀해 주세요.”
- 신고/길 안내 시작처럼 상태를 바꾸는 명령은 intent가 명확할 때만 실행
- 목적지 변경처럼 slot이 중요한 명령은 slot이 비면 재질문
- 민감하거나 되돌리기 어려운 명령은 짧은 확인 단계를 둔다

## 5. slot 설계

intent만으로 부족한 정보는 slot으로 뽑는다.

| intent | slot | 예 |
|---|---|---|
| `set_destination` | `destination` | “한양대학교로 목적지 설정” -> `한양대학교` |
| `set_destination` | `destination` | “목적지를 서울역으로 바꿔” -> `서울역`. “목적지 바꿔”처럼 값이 없으면 재질문 |
| `create_report` | 현재는 없음 | 추후 “타일 손상 신고”, “위치만 저장” 분리 가능 |
| `start_navigation` | 현재는 없음 | 추후 경로 옵션 추가 가능 |

slot이 없으면 잘못 추정해서 실행하지 말고 재질문한다.

## 6. UX 원칙

- 화면 버튼은 최소화한다.
- 사용자는 주로 TTS/진동/음성 명령으로 앱 상태를 이해한다.
- 사용자에게 말해야 하는 순서는 위험 경고(`risk`) > 음성 명령 확인·재질문(`interaction`) > 길안내(`navigation`)다.
- 보행 안전 경고는 장애물, 보행자에게 접근하는 객체, 경로 차단 상황 중심으로 말한다. `낙상 위험`은 현재 MVP에서 별도 경고 카테고리로 쓰지 않는다.
- 녹음 중에는 일반 상호작용·길안내 TTS를 보류한다. 즉시 위험 경고는 녹음보다 우선하며 진행 중 녹음과 부분 업로드를 취소할 수 있다.
- 길 안내 TTS는 거리(m) 단독 표현보다 시간/보폭 기반 표현을 우선한다. 예: “10초 뒤 좌회전 준비”, “약 15보 앞”, “지금 좌회전하세요”.
- 보폭은 기본값으로 시작하되 추후 개인 보폭/키/캘리브레이션으로 개인화할 수 있다. GPS/센서 기반 속도 추정에는 한계가 있으므로 보폭 안내는 기본적으로 근사 표현으로 말한다.
- 신고 bookkeeping은 자동 신고의 경우 침묵하고, 음성 요청 신고만 완료/실패를 말한다.
- `unknown` 또는 낮은 confidence는 길게 설명하지 말고 짧게 재발화를 요청한다.

## 7. 길안내 intent와 TMAP API 연결

현재 `set_destination`은 새 목적지 이름 slot을 받아 기존 목적지를 교체하고, `cancel_destination`은 목적지와 활성 길안내를 정리한다. `start_navigation`은 프론트 `useNavigationGuidance` callback을 호출하며, `next_navigation_instruction`은 활성 TMAP route에서 다음 guide point를 답한다.

주의:

- backend 목적지 이름 검색은 `GET /navigation/destinations/search`로 제공되고 Web/PWA handler에서 목적지 slot과 후보 선택을 길안내 callback으로 연결한다. Android UI도 별도 검증 경로로 같은 API를 사용한다.
- Web/PWA의 테스트/개발용 목적지 좌표는 `NEXT_PUBLIC_WALKSAFE_DESTINATION_LAT/LNG/NAME`으로 둘 수 있다.
- Android native voice에서 목적지 slot을 검색 후보 선택과 길안내 시작까지 이어 주는 UX는 아직 후속이다.
- TMAP appKey는 백엔드 `TMAP_APP_KEY`에만 둔다. 목적지 검색과 보행 경로 provider는 TMAP 고정이며 다른 provider로 전환하지 않는다.
- TMAP 경로의 거리/구간 정보는 그대로 읽기보다 WalkSafe가 현재 속도와 보폭 추정값으로 재가공해 안내한다.

## 8. 구현 단계 제안

1. 현재 rule-based intent에 테스트 케이스를 늘린다.
   - 신고/위치/음성 on/off/반복/목적지 표현을 문장 변형 단위로 검증한다.
2. intent 결과 로그를 익명 통계로 모으는 기능을 설계한다. 현재는 telemetry schema만 있고 저장 경로는 없다.
   - 테스트/개발 단계에서는 표현 개선을 위해 transcript, intent, confidence, 실패 사유를 수집할 수 있다.
   - raw audio는 용량/민감도 부담이 크므로 필요할 때만 내부 테스트/명시 동의 범위에서 수집한다.
   - 외부 공유나 운영 장기 보존 정책은 개인정보 정책 확정 후 별도로 정한다.
3. 반복적으로 실패하는 표현만 rule 사전에 추가한다.
4. 그 후에도 unknown이 많으면 NLU fallback을 붙인다.
5. NLU fallback은 intent/slot JSON만 반환하게 하고, 최종 실행 여부는 앱의 안전 정책이 결정한다.

## 9. 남은 결정

- 로컬 NLU를 쓸지, 서버 LLM fallback을 쓸지
- 음성 명령 로그의 운영 보존/비식별 정책
- “신고 취소” intent를 제공할지
- 목적지 설정 후 길 안내 시작을 자동으로 이어갈지
- 이어폰/골전도 등 실제 보행 환경에서 TTS 길이와 반복 정책을 어떻게 할지

## 10. 검증

현재 rule-based classifier regression:

```bash
.venv/bin/python -m pytest tests/test_voice_intents.py -q
python3 scripts/check_frontend_voice_report_wiring_20260711.py
```

2026-07-11 현재 intent와 TTS unit test 전체 결과:

```text
81 passed
```

이 수치는 실제 모바일 browser microphone, browser TTS 청취 또는 Android Device PASS가 아니다.
실폰 microphone→STT→신고 저장은 `check_walksafe_release_evidence_20260711.py`의 `voice_report_microphone_e2e` 증거가 없으면 release PASS로 올리지 않는다.

## 11. 2026-05-25 안전 slice 메모

- 보호자 긴급 연락처는 MVP에서 브라우저 `localStorage` 저장과 화면 표시까지만 제공한다. 전화/SMS 자동 발신은 사용자 확인 UX와 개인정보/오발신 정책 확정 전까지 구현하지 않는다.
- 개인 보폭 설정은 길안내의 “약 N보 앞” 계산에만 연결한다. 값이 없으면 기존 `NEXT_PUBLIC_WALKSAFE_STEP_LENGTH_M`/기본 0.65m 정책을 유지한다.
- NLU fallback은 외부 LLM/API를 바로 호출하지 않는다. rule-based intent가 `unknown` 또는 낮은 confidence일 때도 앱 동작은 실행하지 않고 재발화를 요청한다. 추후 fallback을 붙이더라도 intent/slot 후보만 반환하고, 신고·길안내 시작 같은 상태 변경은 기존 안전 정책에서 최종 판단한다.
- TTS HTTP cache header 확인은 `scripts/check_voice_tts_http_cache_20260525.py`로 로컬 loopback 서버에 대해서만 수행한다. 서버 URL이 없거나 서버가 내려가 있으면 `BLOCKED`와 실행 힌트로 종료하며 외부 URL은 거부한다.
