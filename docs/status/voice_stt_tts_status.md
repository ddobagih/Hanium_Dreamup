# 로컬 STT/TTS 진행 상태

작성 기준일: 2026-07-13 KST

## 2026-08-30 `길라잡이` 온디바이스 호출어 통합

- Android 사용자 앱에 Vosk Android `0.3.75`와 공식 한국어 small 모델 `vosk-model-small-ko-0.22`를 연결했다. 하나의 16kHz mono PCM16 스트림과 같은 Vosk 모델이 `길라잡이` 호출어와 이어지는 명령을 모두 인식한다.
- `길라잡이, 서울역으로 안내해줘`처럼 한 번에 말하는 방식과 `길라잡이` 뒤 6초 안에 명령을 말하는 방식을 지원한다. 추출한 명령은 새 실행기를 만들지 않고 기존 `AndroidVoiceCommand`·보행 음성 안전정책으로 전달한다.
- 로그인·기기점검·안전교육 등 기존 시작 gate, 버전된 호출어 고지 확인, 마이크 권한과 Android 13 이상 알림 권한을 모두 통과한 전경 `ACTIVE` 보행에서만 microphone foreground service를 시작한다. 앱이 background로 가거나 보행이 일시정지·종료되고, 필수 권한이 철회되거나 생명주기 epoch가 바뀌면 즉시 중지한다. 화면이 꺼진 background에서 계속 듣는 기능은 현재 범위가 아니다.
- Vosk final transcript의 유한한 평균 word confidence가 `0.60` 이상일 때만 호출어와 명령을 처리한다. 유사 호출어·저신뢰·신뢰도 누락·stale callback은 실행하지 않는다. 보행 종료는 기존 2단계 확인과 더 높은 `0.80` 기준을 그대로 사용한다.
- PCM은 메모리에서만 처리하고 별도 pre-roll, 음성 파일 저장, 서버 전송, 인식 원문 상태 로그를 만들지 않는다. 앱 TTS나 TalkBack 발화 중에는 decoder를 reset·억제해 자기 음성 재인식을 막고, 기존 `SpeechRecognizer`·Gateway AAC 녹음과는 마이크를 직렬 전환한다.
- 정적 화면·버튼·약관은 TalkBack 접근성 의미정보가 읽는다. 동적 길안내는 기존 오프라인 한국어 Android TTS를 우선 사용하고, 기존 위험·상호작용 경로는 TalkBack 활성 여부에 따라 한 발화 소유자만 사용한다. faster-whisper·Qwen3-TTS는 선택적 Gateway 경로이며 이번 상시 호출어에 넣지 않았다.
- 모델 준비 스크립트는 공식 URL·archive SHA-256·라이선스·개별 파일 hash를 고정하며, APK asset 병합 전에 모델 manifest와 파일 존재를 확인한다. runtime 최초 준비에서도 모든 asset hash를 검증한 뒤 앱 전용 `noBackupFilesDir`에 원자적으로 게시한다.
- JVM 전체 `:app:testDebugUnitTest`와 debug APK 빌드는 PASS했다. 최신 debug APK는 `213MB`, SHA-256 `09027b56400e191e09197585fc75d6b678d1eb868fb5ec2f809b0c4a220d2f6c`이다. 이 APK를 SM-G981N에 데이터 보존 업데이트 설치했고, 기기 `base.apk` hash 일치와 cold launch·252MB private 모델 준비를 확인했다. 해당 기기는 현재 로그아웃 상태이고 알림 권한도 미허용이어서 실제 발화 전이·오탐·미탐·장시간 발열/배터리는 아직 `NOT_RUN`이다.
- 213MB 단일 APK는 개발 sideload 근거일 뿐 운영 배포 크기 승인이 아니다. 출시 후보에서는 Android App Bundle/asset delivery 또는 동등한 hash-pinned 모델 전달 방식을 확정하고 같은 binary로 재시험해야 한다.

## 2026-08-30 Android Gateway 통합 보정

- 현재 Android 제품 앱은 플랫폼 음성만 쓰는 이전 상태와 달리, 로그인된 Gateway session에서 `/api/speech/stt`와 `/api/speech/tts`를 선택적으로 호출하는 `GatewaySpeechClient`를 포함한다.
- Gateway가 내부 Voice 서비스로 요청을 제한 중계하며 Android APK에는 Voice origin이나 service token을 넣지 않는다. 이 경로는 기본 OFF이고 Voice 장애 시 플랫폼 TTS·진동의 안전 fallback을 유지한다.
- faster-whisper와 Qwen3-TTS의 실제 GPU load·샘플 추론은 직접 서비스에서 확인했다. 최신 Gateway relay, 실기기 microphone/speaker, 청취 품질과 TalkBack 충돌은 아직 `NOT_RUN`이다.
- 아래 2026-07 기록의 Web/PWA·플랫폼 음성 설명은 당시 상태의 역사 기록이며 현재 Android 제품 경로의 정본으로 사용하지 않는다.

## 2026-07-13 안전 계약 보정

- local voice API 입력은 transcript 200자, phrase ID 80자, 직접 TTS text 180자로 제한한다.
- “서울역 말고 시청”, “서울역이 아니라 시청”, “서울역 또는 시청”처럼 정정·복수 목적지가 섞인 문장은 임의의 목적지를 실행하지 않고 `safe_noop` 재질문으로 닫는다.
- TTS 동시 실행은 기본 1개·설정 가능 최대 2개이고 queue 대기는 기본 2초다. 초과 시 `503 tts_busy`를 반환하며, 취소된 요청도 실제 worker가 끝날 때까지 capacity를 점유한다. blocking STT/TTS는 event loop 밖에서 실행한다.
- Android는 recognizer 최상위 가설 하나만 사용하며 confidence가 제공되면 0.55 미만·비유한 값은 실행하지 않는다. 낮은 순위의 positive 가설로 대체하지 않는다.
- Android 목적지 검색은 상위 3개 후보의 번호·이름·주소·거리를 bounded 음성으로 열거한다. 검색 실패나 후보 선택 전에는 기존 목적지·경로를 보존한다.
- Android TTS 우선순위는 `risk > interaction > navigation`이다. 위험 발화 전에는 진행 중 STT를 취소하고, 위험 발화 중 새 STT는 시작하지 않는다.

## 2026-07-11 field addendum

- Cloudflare 임시 HTTPS의 same-origin `/api/speech/stt`를 실제 음성 파일로 검증했다.
- `목적지 서울역으로 설정해`는 transcript와 `set_destination`, destination slot `서울역`, score 0.92로 실행 판정됐다.
- `길 안내 시작해` 음성은 transcript가 `길었네 시작해`로 오인식됐지만 intent는 `start_navigation`, score 0.90으로 실행 판정됐다.
- 이는 원격 HTTP/STT/intent 근거이며 실제 외출용 폰 마이크, 주변 소음과 브라우저 TTS 청취 PASS는 아니다.
- 실행 명령은 rule `score`만으로 허용하지 않는다. faster-whisper segment의 평균 `avg_logprob ≥ -1.0`, 최대 `no_speech_probability ≤ 0.6`을 모두 요구하며 acoustic 진단이 없거나 낮으면 fail-closed 재질문한다.
- “신고하지 마”, “목적지 취소하지 마”, “길안내 중지하지 마”처럼 지원 실행 명령에 부정형이 붙으면 positive substring rule보다 먼저 `safe_noop`으로 차단한다. “말하지 마”는 명시적 `voice_off`로 유지한다.
- Web strict parser는 `action=execute`, `should_execute=true`, rule score, acoustic gate의 계약이 모두 일치할 때만 순수 `voice-intent-executor`를 호출한다. callback spy가 목적지 설정·후보 선택·취소·다음 안내·시작·중지의 실제 dispatch를 검증한다.

## 2026-07-10 코드 감사 보정

- local voice server는 STT, intent/slot 분류, TTS WAV 반환을 담당하며 신고나 길안내를 직접 실행하지 않는다.
- 주 사용자 앱인 Web/PWA는 `/speech/stt`를 호출하고 callback으로 intent를 실행한다. 사용자 안내 TTS는 local `/speech/tts`가 아니라 브라우저 `speechSynthesis`를 사용한다.
- Android 실험·검증 보조 경로는 local voice server가 아니라 플랫폼 `SpeechRecognizer`/`TextToSpeech`를 사용한다. `AndroidVoiceCommand`가 명시 신고·목적지 설정/변경·후보 번호 선택·취소·다음 안내·길안내 중지를 실제 action으로 연결하고 부정문·검색 중·범위 밖 선택을 fail-closed 처리한다.
- intent `score`는 음향 인식 confidence가 아니라 rule마다 정한 고정 heuristic 값이다. 실행 여부는 이 점수와 별도의 acoustic evidence를 함께 사용한다. fuzzy matcher와 NLU fallback은 구현되어 있지 않다.
- telemetry는 raw audio/transcript를 제외한 payload schema만 정의되어 있고 수집·저장 endpoint는 없다.

## 2026-07-10 보정

- 서버 기반 STT는 Web/PWA에 연결된 현재 음성 인식 prototype이다. 제품 완료에는 브라우저·실폰 E2E와 운영 배포 검증이 필요하다.
- Web/PWA의 사용자 안내는 브라우저 `speechSynthesis`를 사용하므로 local Qwen TTS 완료를 기다리지 않는다.
- Android native에는 `SpeechRecognizer` 기반 명시 신고, 목적지 설정/변경, 후보 번호 선택, 목적지 취소, “다음 경로 뭐야?”, 길안내 중지 경로가 있다. 검색 완료 TTS가 번호 선택을 안내하고 유효한 번호만 TMAP route 요청으로 이어진다. 다음 안내는 confirmed off-route가 아닐 때만 `RouteNavigator.currentInstruction`을 사용한다.
- 현재 음성 P0는 Web/PWA에서 목적지·길안내·신고 intent가 브라우저 mic부터 UI action과 `speechSynthesis`까지 이어지는지 확인하는 것이다.
- `create_report` intent의 정책 의미는 유지한다. Web/PWA와 Android 각각의 실폰 mic/TTS 청취, 보행 중 인식률, TalkBack 충돌 평가는 별도 Device evidence다.
- Cloud/유료 STT/TTS와 비용 발생 API는 사용하지 않는다. 2026-07-11 field에는 승인된 Cloudflare 임시 공개 URL만 사용한다.

## 범위

이 문서는 WalkSafe Assist 음성 명령과 안내음 프로토타입의 현재 상태를 요약한다. 원본 음성 샘플, 생성 WAV, CSV, 로그는 로컬 산출물로만 보관하고 GitHub에는 요약 수치와 코드/문서만 올린다.

## 구현 상태

- 음성 전용 가상환경: `.venv-voice`
- 서버: `voice/server.py`
- 포트: `9001`
- API 초안:
  - `GET /health`
  - `POST /speech/intent`
  - `GET /speech/intents`
  - `GET /speech/intent-telemetry/schema`
  - `GET /speech/phrases`
  - `POST /speech/stt`
  - `POST /speech/tts`
  - `POST /speech/tts/cache-status`
- PWA 연동: `apps/web/lib/voice-api.ts`, `apps/web/app/_walksafe/hooks/useVoiceCommands.ts`
- intent/TTS 정책 회귀 테스트: `tests/test_voice_intents.py`, `tests/test_voice_tts.py`
- STT: `faster-whisper medium`
- TTS: `Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice`
- 유료/클라우드 API: 사용하지 않음
- 파인튜닝: 수행하지 않음
- Android native: 전경 `ACTIVE` 보행에서는 Vosk 한국어 모델이 `길라잡이` 호출어와 명령을 연속 인식하며, 기존 `SpeechRecognizer` 버튼형·확인형 경로도 유지한다. 명시 신고·목적지 설정/변경·후보 번호 선택·취소·다음 안내·길안내 중지를 기존 안전 parser로 실행하고 raw audio/transcript를 서버에 저장하지 않는다.

## 최신 policy 요약

- 최신 보이스 커맨드 전략 기준 문서는 `docs/walksafe-v2/voice_command_strategy.md`다.
- 보이스 커맨드는 모든 문장을 녹음해서 매칭하지 않고, STT 결과를 정규화한 뒤 regex/substring rule과 slot 추출로 후처리한다.
- `create_report`는 자동 신고보다 우선하는 음성 요청 신고 intent다.
- unknown 또는 저신뢰 명령은 실행하지 않고 재질문한다.
- 지원 실행 명령의 부정형은 `safe_noop`으로 먼저 차단하며, STT acoustic evidence가 없거나 `avg_logprob`/`no_speech_probability` gate를 통과하지 못하면 실행하지 않는다.
- Android native parser도 지원 action의 부정문을 실행하지 않는다. 목적지 검색 후보를 임의로 자동 선택하지 않고 상위 3개 후보를 번호·이름·주소·거리와 함께 읽은 뒤 명시한 유효 번호만 선택하며 검색 중·범위 밖 번호는 no-op 처리한다.
- 위험·상호작용·길안내 TTS 중재는 `risk > interaction > navigation` 순서다.
- 위험 안내로 길안내 TTS가 막히면 navigation cooldown을 소비하지 않고 같은 key를 400ms 간격·최대 30회로 제한 재시도한다.
- 자동 타일 손상 신고는 사용자에게 굳이 말하지 않는다.

## 지원 intent

| intent | 의미 |
| --- | --- |
| `create_report` | 현재 위험 신고. v2에서는 음성 요청 신고로 자동 신고보다 우선 |
| `voice_on` | 음성 안내 켜기 |
| `voice_off` | 음성 안내 끄기 |
| `repeat_last` | 마지막 안내 반복 |
| `get_current_location` | 현재 위치 안내 |
| `set_destination` | 목적지 설정 또는 현재 목적지 변경 |
| `select_destination_candidate` | 목적지 검색 후보 번호 선택 |
| `start_navigation` | 길 안내 시작 |
| `reroute_navigation` | 경로 재탐색 요청 |
| `stop_navigation` | 길 안내 중지 |
| `cancel_destination` | 현재 목적지와 길안내 취소 |
| `next_navigation_instruction` | 현재 진행도를 기준으로 다음 경로 안내 질의 |
| `unknown` | 미지원 또는 불확실 |

## 검증된 로컬 범위

- 실제 사람 음성 sample 기준 known intent 성공 기록이 있다. 2026-07-10에 `신고해.m4a`를 cached `faster-whisper medium`으로 재실행해 `신고해`/`create_report`를 확인했다.
- 합성 음성 기반 STT smoke 기록이 있다.
- 음성 회귀는 부정문·모호한 목적지 `safe_noop`, acoustic gate, 입력 길이, bounded queue/cancellation, 응답 계약과 TTS fallback/cache를 포함해 PASS했다. 정확한 건수는 최종 전체 재실행 결과를 기준으로 기록한다.
- `/speech/tts` fallback/cache 정책이 구현되어 있다.

주의: unit test와 원격 파일 upload smoke는 실제 Qwen speaker 재생, Android/Web mic/TTS/field evidence가 아니다.

## 아직 남은 것

1. SM-G981N에서 로그인→기기점검→안전교육→전경 ACTIVE 보행으로 들어가 알림 권한을 직접 허용한 뒤, 한 문장 호출·분리 호출·6초 timeout·저신뢰 거부를 실제 음성으로 확인한다.
2. 조용한 실내·도로 소음·바람·주머니/장착 위치·복수 화자에서 호출어 오탐·미탐과 명령 성공률을 반복 측정한다.
3. 15분 이상 camera/GPS/TFLite/Vosk/TTS 동시 실행에서 cold start, command latency, RSS, 배터리, thermal throttling과 native crash를 확인한다.
4. TalkBack을 켠 상태에서 버튼·약관·상태 초점과 앱 길안내/위험 안내가 겹치거나 자기 음성으로 재호출되지 않는지 청취 평가한다.
5. 운영 배포용 모델 전달 방식과 APK/AAB 크기 정책을 확정하고 clean checkout·CI·서명 release에서 hash-pinned 모델을 재검증한다.
6. 선택적 Gateway faster-whisper·Qwen3-TTS 실기기 mic/speaker E2E와 timeout/fallback을 별도로 확인한다.

## 판단

- 현재 faster-whisper medium은 제한된 지원 명령 sample에서 프로토타입 동작을 확인했다.
- 특정 표현 오류는 intent rule 보강으로 해결됐다.
- `STT 서버` 자체보다 Web/PWA의 mic→intent→UI action→브라우저 TTS 연결과 실폰 인식률을 P0로 검증한다. Android coordinate/depth는 별도 실험·검증 gate다.
