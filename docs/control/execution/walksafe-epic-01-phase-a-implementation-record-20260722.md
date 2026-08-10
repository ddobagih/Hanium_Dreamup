# EPIC-01 1차 제품 경계 구현 기록

문서 ID: `WS-EPIC-01-PHASE-A-IMPLEMENTATION-20260722-001`  
버전: `0.3.0`  
상태: `INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS`  
기준일: `2026-07-22`

## 무엇을 한 것인가

승인된 정책을 실제 프로젝트 구조와 실행 시작점에 처음 적용했다. 이번 단계에서 정한 것은 다음 다섯 가지다.

1. 일반 사용자는 Android 사용자 앱을 사용한다.
2. 관리자는 사용자 앱과 식별값이 다른 별도 Android 관리자 앱을 사용한다. 관리자 업무는 FP-003을 담당하는 EPIC-01에서 인증·복구를 구현할 때까지 잠근다.
3. 과거 Web 화면과 PWA는 참고·회귀용으로 분류했다. 현재 기술적으로 막은 범위는 CI의 Web release 생성과 공개 터널 실행기이며, 다른 역사적 builder·배포 입력의 폐쇄와 전용 보관은 아직 남아 있다.
4. Android 앱은 시작 전에 Android 버전, 카메라, GPS, 마이크, 진동, 휴대폰 내부 음성인식, 오프라인 한국어 음성안내를 확인한다. `FULL`은 안정적인 실제 미터 거리와 승인된 지정 기기 프로필·프로필 버전이 모두 있어야 하며 현재는 차단한다.
5. 사용자는 공식 제품 목적·안전 한계와 기기 판정을 확인해야 기능을 시작할 수 있다. 제한 판정은 TTS 완료 또는 TalkBack 전달 뒤에만 확인된다. STT/TTS 초기화·지속 실행 실패 시 확인과 경로를 무효화하고 카메라·위치·걸음·방향 출력을 중지하며 앱 재개·권한 callback 우회도 차단한다.

정책·요구·Gap 연결은 기계 판독 기록인 같은 이름의 JSON에 적었다.

## 왜 아직 EPIC-01 완료가 아닌가

내부 코드검사와 빌드는 통과했지만 다음이 남아 있다.

- 관리자 패스키/MFA, 분리된 복구수단, 원격 세션 폐기, 고위험 작업 동결을 구현하지 않았다. FP-003 추적이 끊기지 않도록 이 작업은 EPIC-01에서 닫아야 한다.
- 실제 ARCore session·Depth mode·안정적인 미터 거리 frame과 승인 기기 프로필·버전을 시작 전에 함께 확인하는 preflight가 없다. 완료 전 `FULL`은 차단한다.
- CI와 차단된 공개 launcher 이외의 Legacy Web builder·배포 입력을 기술적으로 폐쇄하거나 전용 읽기 전용 보관 경계로 옮기지 않았다. `run_cloudflare_field_test_services_20260711.sh`의 Web build/start와 별도 tunnel 지시도 이 OPEN inventory에 포함한다.
- Android가 현재 쓰는 Next `app/api`를 독립 Android API gateway로 추출하지 않았다.
- 목적·안전 한계를 동의 화면, 사용자 설명서, 배포 설명까지 연결 검증하지 않았다.
- 목적지 없이 가까운 위험을 안내해야 하는 `TC-FP-001-02`와 현재 거리 제한 advisory gate의 정합성을 닫지 않았다.
- 실제 서명·배포는 EPIC-11에서 구현한다.
- 지원 기기의 ARCore/Depth, 온디바이스 STT, 오프라인 한국어 TTS, TalkBack과 제한 모드는 실기기에서 검증하지 않았다.
- 정식 시험 279개와 출시 gate 5개는 전부 `NOT_RUN`이다.

따라서 현재 판정은 “1차 구현의 내부 검증 통과”이며 `IMPLEMENTATION_READY`, 정식 시험 PASS 또는 출시 허용이 아니다.

## 내부 검증 결과

| 확인 | 결과 | 한계 |
|---|---|---|
| Android JVM·assemble·lint | 강제 재실행 JVM 342/342 PASS, debug assemble PASS, lint 오류 0 | 실기기 실행 증거 아님 |
| Android·Legacy Web 경계 검사 | 12개 PASS | 서명·배포 완료 또는 실제 과거 주소 폐쇄 증거 아님 |
| 재개·승인 상태 변조 회귀 | 20개 PASS | 미커밋 작업트리 밖의 서명 anchor는 아님 |
| 테스트 inventory | 현재 회귀·역사 생성기 전부 중복·누락 없이 분류 | 역사 snapshot 검사를 현재 구현 CI 성공조건으로 보지 않음 |
| Web test·lint·typecheck·build | PASS | Web 제품 승인이나 Android 검증이 아님 |
| 형식 검사 | YAML parse·`git diff --check` PASS | 기능 시험을 대신하지 않음 |

## 다음 한 가지 작업

`EPIC-01 2단계에서 관리자 앱에 패스키 또는 MFA, 분리된 복구수단, 원격 세션 폐기와 고위험 작업 동결을 구현하고 복구시험 절차를 연결한다.`

이 작업이 끝나도 실기기·현장·접근성·복구 gate를 자동으로 완료 처리하지 않는다.
