# 2026-09-06 음성 집중 개선 결과

## 현재 판정

사용자 승인 오류 수정과 빌드, 단위/정적 계약 회귀, 최신 사용자 APK의 휴대폰 덮어 설치를 완료했다. 음성 기능 전체 완료나 실제 사람 발화 정확도 검증 완료를 뜻하지 않는다. 기존 앱 데이터는 삭제하지 않았으며 설치 후 실제 화면은 로그인 화면이었다.

## 적용 내용

- HOME의 일회성 음성 명령은 설치된 한국어 Android on-device 인식 엔진을 우선 사용한다. 해당 경로를 사용할 수 없으면 Vosk를 사용한다. 보행 중 연속 호출어 경로는 Vosk를 유지한다.
- 마이크 소유권, 요청 세대, 사용자/세션/보행 상태 검사를 유지하고, 일시적 단발 인식 엔진 오류 때문에 기능을 영구 제한하지 않도록 연결했다.
- `금오공대 목적지로 해줘`, `가고 싶어`, `찾아줘`, `안내해줘` 형태의 목적지 질의를 보강했다. 인식된 글자를 명령으로 해석하는 파서는 음성 인식 엔진과 별개이며, 모든 자연어를 이해하는 범용 대화 모델은 아니다.
- 검색 결과는 사용자 확인 없이 목적지로 자동 확정하지 않는다. 실제 TMAP 약칭 검색은 응답을 반환했으므로 학교별 별칭 하드코딩은 추가하지 않았다.
- 일반 명령 응답을 끝까지 들어야 하는 교육 음성과 분리했다. 실제 TTS 완료 콜백으로 입력 준비를 이어가고, 취소/화면 변경 후 낡은 응답이 UI를 덮어쓰거나 녹음을 다시 시작하지 않도록 했다.
- 명령 응답 취소는 명령 전용 음성 큐에만 적용한다. 위험/교육 음성을 무차별 중단하지 않는다.
- foreground Activity에서 서비스 시작 직후 취소할 때 발생하던 foreground service 시작 시간초과 경로를 수정했다. 런타임 보행/권한 조건은 우회하지 않았다.
- PCM 진단 변수 scope 오류 및 의도한 함수 위임/새 정책과 불일치한 정적 테스트 기대값을 교정했다.

## 검증 증거

| 범위 | 결과 | 의미와 한계 |
|---|---|---|
| 사용자 앱 단위/정적 계약 | 1,906개 중 1,905 통과, 1 skip, 실패 0 | 실제 사람 발화 또는 전체 화면 흐름 통과 수치가 아님 |
| 사용자 APK 및 AndroidTest APK | 빌드 성공 | release 배포 검증과 별개 |
| 서비스 즉시 취소 에뮬레이터 비교 | 수정 후 1개 실제 instrumentation 통과 | 보행 중 모든 서비스 흐름을 검증한 것은 아님 |
| 합성 PCM segmented 모드 | 3개 문장 텍스트 일치, 원형 confidence 0.0 | 제품 명령 수용/사람 발화 정확도 근거가 아님 |
| 합성 PCM 일반 모드 | 3개 모두 최종 text가 비어 실패, partial은 일치 | 외부 PCM 입력 경로의 최종결과 미수신. 실제 마이크 경로 성공/실패는 미확정 |
| SM-S931N 사용자 앱 | `adb install -r` 성공, 실행 후 로그인 화면 확인 | 로그인/교육/홈 실제 음성 명령 전체 E2E는 아직 미확인 |
| 실제 TTS 명령 응답 instrumentation | 에뮬레이터 1개 통과, 11.163초 | 실제 엔진의 두 응답 onDone, 취소 후 isSpeaking=false, 늦은 완료 차단 확인. 사람의 청취/TalkBack 동시 사용 및 실제 onError 강제 발생은 미검증 |

외부 PCM 진단이 실패했다고 partial 결과를 final로 승격하거나 confidence를 조작하지 않았다. 사람이 실제 휴대폰에서 입력한 최종 인식 결과, 명령 선택, 음성 출력 완료를 별도로 확인해야 한다.

## 실제 휴대폰 확인 순서

1. 로그인하고 필요한 교육 절차를 정상 완료한다. 제한 조건을 테스트용 PASS로 변경하지 않는다.
2. HOME에서 음성 명령을 누르고 입력 안내가 끝나면 `도움말`이라고 말한다. 결과 문구와 음성 안내 여부를 기록한다.
3. 안내가 끝난 뒤 같은 버튼으로 `도움말`을 다시 말한다. 두 번째에도 버튼이 활성화되고 응답하는지 확인한다.
4. `금오공대 목적지로 해줘`를 말하고 후보가 나오는지 확인한다. 선택/안내 시작은 별도 사용자 확인 절차다.
5. 무응답, 잘못 들은 말, 안내 도중 취소, 화면 이탈 후 재진입을 확인한다. 입력 실패와 출력 실패를 구분한다.

## 근거 파일

- `/tmp/walksafe-functional-repair-voice-feedback-unit-final.log`
- `/tmp/walksafe-functional-repair-voice-feedback-device-build.log`
- `/tmp/walksafe-functional-repair-fgs-safe-emulator.log`
- `/tmp/walksafe-functional-repair-ondevice-pcm-normal-emulator.log`
- `/tmp/walksafe-functional-repair-command-tts-build.log`
- `/tmp/walksafe-functional-repair-command-tts-emulator.log`
- `/home/ddobagi/work/functional-repair-20260906/`

관련 발화 표: `docs/planning/walksafe-voice-validation-20260906.md`.

## 미완료 범위

사람 발화 반복 시험, 최신 HOME 응답과 교육 이후 기능의 실기기 E2E, 실제 사용자 위치 기반 검색, TalkBack과 동시 사용, 카메라/보행 현장 시험은 위의 단위 테스트나 설치 성공으로 대체할 수 없다. 기존 프로젝트 전체 목표는 완료로 표시하지 않았다.

## 14:00 장시간 서버 점검 회수

- 실제 개발 환경 46 sample: 비인증 Backend 401은 46/46 예상 응답, Gateway 200은 46/46. monitor의 전체 ok=false를 서버 장애로 계산하지 않았다.
- 휴대폰 HTTP 200은 40/46. 나머지 6개는 USB 연결/reverse 등의 조건으로 HTTP 성공을 확인하지 못한 sample이며 서버 장애로 단정하지 않았다.
- 격리 인증 환경: 합성 계정 로그인 200 이후 session 200/auth=true, reports 200, 직접 DB SELECT 1이 각각 19/19 성공했다. 이 DB 조회는 개별 요청의 DB 내부 경로 추적과 별개다.
- 인증 monitor는 정상 종료 코드 0 확인. 실제 개발 monitor는 최종 session을 회수할 수 없어 종료 코드 자체는 미확인이다.
- 집계 근거: `/home/ddobagi/work/functional-repair-20260906/authenticated-monitor-w9c4e/final-summary.json`.
- 정리는 본 작업의 격리 테스트 서버/DB/에뮬레이터만 대상으로 한다. 운영 개발 서버, 기존 DB, Mailpit, 실폰 데이터와 reverse는 유지한다.
- 격리 테스트 환경 정상 정리를 완료했다. 증거와 private credentials는 보존했고 미정리 소유 리소스는 없다. 근거: `/home/ddobagi/work/functional-repair-20260906/emulator-stack-UxrvmW/cleanup-summary.json`.
