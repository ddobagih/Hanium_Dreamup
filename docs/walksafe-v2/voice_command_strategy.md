# WalkSafe v2 voice command strategy

- 기준일: 2026-06-02 KST
- 구현 위치: `voice/intents.py`, `voice/server.py`, `apps/web/lib/voice-api.ts`, `apps/web/app/_walksafe/hooks/useVoiceCommands.ts`
- 상태: rule-based intent 1차 구현과 regression test 추가. 전체 문장 녹음/나열 방식은 사용하지 않는다.

## 2026-06-02 현재 우선순위 보정

- 주 사용자 앱 경로는 Android native ARCore/TFLite APK다.
- 이 문서는 backend/Web/PWA/voice/정책 기준으로 유지하되, Android Device evidence를 대체하지 않는다.
- Android native에는 `SpeechRecognizer` 기반 "신고해줘" 버튼 경로가 explicit report upload/TTS 정책에 연결되어 있다.
- 실폰 mic/TTS 청취, TalkBack, 보행 중 인식률은 아직 Device PASS가 아니다.

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
| `create_report` | 현재 탐지된 신고 가능 대상 신고 | v2에서는 tactile damage가 있을 때 `/reports/v2`에 `trigger=voice`로 저장 |
| `voice_on` | 음성 안내 켜기 | 즉시 실행 |
| `voice_off` | 음성 안내 끄기 | 즉시 실행 |
| `repeat_last` | 최근 안내 반복 | 즉시 실행 |
| `get_current_location` | 현재 위치 안내 | GPS 상태 기반 TTS |
| `set_destination` | 목적지 저장 | destination slot 추출 후 navigation hook에 전달 |
| `start_navigation` | 길 안내 시작 | GPS와 목적지 좌표가 있을 때 `/navigation/walking`으로 TMAP 보행 경로 요청 |
| `unknown` | 이해 실패 | 재발화 요청 |

## 3. 신고 관련 정책

- 사용자가 명시적으로 “신고해줘”라고 요청하면 자동 신고보다 우선한다.
- 음성 요청 신고는 자동 신고 cooldown을 우회한다.
- 현재 신고 대상은 `unified_walksafe` 또는 legacy `custom_tactile`의 `damaged_tactile_block`이다.
- 일반 객체는 신고하지 않는다.
- 음성 요청 신고는 완료/실패를 짧게 TTS로 알려야 한다.
- 자동 신고는 사용자가 들어야 할 안내가 아니므로 성공 TTS를 하지 않는다.

## 4. 모든 경우의 수를 녹음하지 않는 방법

### 4.1 1차: rule/fuzzy intent

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

### 4.2 2차: NLU fallback

rule/fuzzy score가 낮거나 `unknown`일 때만 더 비싼 NLU를 fallback으로 붙인다.

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
| `create_report` | 현재는 없음 | 추후 “타일 손상 신고”, “위치만 저장” 분리 가능 |
| `start_navigation` | 현재는 없음 | 추후 경로 옵션 추가 가능 |

slot이 없으면 잘못 추정해서 실행하지 말고 재질문한다.

## 6. UX 원칙

- 화면 버튼은 최소화한다.
- 사용자는 주로 TTS/진동/음성 명령으로 앱 상태를 이해한다.
- 사용자에게 말해야 하는 것은 보행 안전 경고와 길 안내가 우선이다.
- 보행 안전 경고는 장애물, 보행자에게 접근하는 객체, 경로 차단 상황 중심으로 말한다. `낙상 위험`은 현재 MVP에서 별도 경고 카테고리로 쓰지 않는다.
- 길 안내 TTS는 위험 경고보다 낮은 우선순위다.
- 길 안내 TTS는 거리(m) 단독 표현보다 시간/보폭 기반 표현을 우선한다. 예: “10초 뒤 좌회전 준비”, “약 15보 앞”, “지금 좌회전하세요”.
- 보폭은 기본값으로 시작하되 추후 개인 보폭/키/캘리브레이션으로 개인화할 수 있다. GPS/센서 기반 속도 추정에는 한계가 있으므로 보폭 안내는 기본적으로 근사 표현으로 말한다.
- 신고 bookkeeping은 자동 신고의 경우 침묵하고, 음성 요청 신고만 완료/실패를 말한다.
- `unknown` 또는 낮은 confidence는 길게 설명하지 말고 짧게 재발화를 요청한다.

## 7. 길안내 intent와 TMAP API 연결

현재 `set_destination`은 목적지 이름 slot을 저장하고, `start_navigation`은 프론트 `useNavigationGuidance` callback을 호출한다.

주의:

- 현재 구현은 목적지 이름을 좌표로 변환하지 않는다.
- 테스트/개발용 목적지 좌표는 `NEXT_PUBLIC_WALKSAFE_DESTINATION_LAT/LNG/NAME`으로 둔다.
- 실제 서비스에서는 TMAP/지도 provider 기반 목적지 검색 또는 geocoding을 추가해야 한다.
- TMAP appKey는 백엔드 `TMAP_APP_KEY`에만 둔다. Kakao provider는 제휴/권한 확보 시 fallback으로 유지한다.
- TMAP 경로의 거리/구간 정보는 그대로 읽기보다 WalkSafe가 현재 속도와 보폭 추정값으로 재가공해 안내한다.

## 8. 구현 단계 제안

1. 현재 rule-based intent에 테스트 케이스를 늘린다.
   - 신고/위치/음성 on/off/반복/목적지 표현을 문장 변형 단위로 검증한다.
2. intent 결과 로그를 익명 통계로 모은다.
   - 테스트/개발 단계에서는 표현 개선을 위해 transcript, intent, confidence, 실패 사유를 수집할 수 있다.
   - raw audio는 용량/민감도 부담이 크므로 필요할 때만 내부 테스트/명시 동의 범위에서 수집한다.
   - 외부 공유나 운영 장기 보존 정책은 개인정보 정책 확정 후 별도로 정한다.
3. 반복적으로 실패하는 표현만 rule/fuzzy 사전에 추가한다.
4. 그 후에도 unknown이 많으면 NLU fallback을 붙인다.
5. NLU fallback은 intent/slot JSON만 반환하게 하고, 최종 실행 여부는 앱의 안전 정책이 결정한다.

## 9. 남은 결정

- 로컬 NLU를 쓸지, 서버 LLM fallback을 쓸지
- 음성 명령 로그의 운영 보존/비식별 정책
- “신고 취소” intent를 제공할지
- 목적지 설정 후 길 안내 시작을 자동으로 이어갈지
- 이어폰/골전도 등 실제 보행 환경에서 TTS 길이와 반복 정책을 어떻게 할지

## 9. 검증

현재 rule-based classifier regression:

```bash
.venv/bin/python -m pytest tests/test_voice_intents.py -q
```

## 10. 2026-05-25 안전 slice 메모

- 보호자 긴급 연락처는 MVP에서 브라우저 `localStorage` 저장과 화면 표시까지만 제공한다. 전화/SMS 자동 발신은 사용자 확인 UX와 개인정보/오발신 정책 확정 전까지 구현하지 않는다.
- 개인 보폭 설정은 길안내의 “약 N보 앞” 계산에만 연결한다. 값이 없으면 기존 `NEXT_PUBLIC_WALKSAFE_STEP_LENGTH_M`/기본 0.65m 정책을 유지한다.
- NLU fallback은 외부 LLM/API를 바로 호출하지 않는다. rule-based intent가 `unknown` 또는 낮은 confidence일 때도 앱 동작은 실행하지 않고 재발화를 요청한다. 추후 fallback을 붙이더라도 intent/slot 후보만 반환하고, 신고·길안내 시작 같은 상태 변경은 기존 안전 정책에서 최종 판단한다.
- TTS HTTP cache header 확인은 `scripts/check_voice_tts_http_cache_20260525.py`로 로컬 loopback 서버에 대해서만 수행한다. 서버 URL이 없거나 서버가 내려가 있으면 `BLOCKED`와 실행 힌트로 종료하며 외부 URL은 거부한다.
