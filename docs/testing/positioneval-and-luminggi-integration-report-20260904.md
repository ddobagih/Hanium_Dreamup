# 위치 정답지 분석 앱 및 Luminggi 선별 통합 보고서

- 작성일: 2026-09-04
- 기준 저장소: `/home/ddobagi/Hanium_Dreamup`
- Luminggi 대상 브랜치: `origin/feature/appdesign-and-device-check-20260904`
- 공통 기준 커밋: `6ed59af06e64c05cfa07055ef9e402e95dc6fa3c`
- 분석 대상 끝 커밋: `835df5c`

## 1. 목표와 완료 기준

이번 작업의 목표는 두 가지다.

1. WalkSafe 앱과 분리된 Android 분석 앱에서 WalkSafe 위치 trace와 GnssLogger 원시 로그를 가져오고, 한국 NGII 기준국 자료를 자동으로 받아 사후 PPK 정답 후보를 만든다.
2. 팀원 Luminggi 브랜치를 먼저 분석한 뒤 현재 프로젝트의 위치 추적·가입·신고·기기점검 기능을 훼손하지 않는 변경만 선별 적용한다.

구현 완료 기준은 Android 빌드, 단위 테스트, Lint, Python trace 평가 테스트, RTKLIB 호스트 골든 테스트 통과다. 실제 2m 정확도 달성 판정은 같은 시간대의 실외 WalkSafe trace와 GnssLogger 로그가 없으므로 이번 코드 완료와 분리한다.

## 2. 단계별 진행 결과

| 단계 | 수행 내용 | 검증 | 결과 |
|---|---|---|---|
| 1 | 현재 코드와 Luminggi 브랜치 차이 분석 | 커밋·파일·기능별 충돌 검토 | 완료 |
| 2 | Luminggi UI/UX·음성 데이터·기기점검 선별 통합 | 사용자 앱 단위 테스트·Lint·APK 빌드 | 완료 |
| 3 | 위치 trace v2와 사후 평가 계약 확정 | Android/Python 공용 fixture와 계약 테스트 | 완료 |
| 4 | GnssLogger 원시 로그를 RINEX 3.03으로 변환 | 변환기 테스트와 RTKLIB `readrnxt()` 교차검증 | 완료 |
| 5 | NGII 기준국 검색·자료 자동 다운로드 | 압축·무결성·거리·시간 범위 테스트 | 코드 완료, 실키 검증 미실행 |
| 6 | 내장 RTKLIB PPK와 FIX-only 평가 | ARM64 빌드와 호스트 골든 테스트 | 완료 |
| 7 | Android 분석 앱 UI·보안·결과 JSON 통합 | 전체 strict Gradle 게이트 | 완료 |
| 8 | 실기기·실외 정확도 검증 | 연결 기기와 동시 수집 로그 필요 | 미실행 |

## 3. 별도 Android 위치 분석 앱

모듈은 `apps/android/positionevalapp`이며 패키지는 `kr.co.hanium.dreamup.walksafe.positioneval`이다. 본 WalkSafe 앱과 별도 APK로 설치된다.

처리 순서는 다음과 같다.

1. SAF로 WalkSafe `walksafe.positioning_trace.v2` JSONL과 GnssLogger TXT를 선택한다.
2. 입력을 앱 전용 저장소로 복사하고 SHA-256을 계산한다.
3. trace의 GNSS 기준 좌표와 세션 시간을 이용해 30km 이내 NGII 기준국을 가까운 순서로 찾는다.
4. 사용자가 한 번 저장한 NGII File-Key로 해당 시간의 관측·항법 RINEX를 자동 다운로드한다.
5. GnssLogger TXT를 RINEX 3.03 관측 파일로 변환한다.
6. 앱에 내장된 RTKLIB-EX demo5로 후처리 PPK를 실행한다.
7. `Q=1 FIX` 위치만 정답 후보로 사용해 raw, filtered, matched 위치의 P50, P95, RMSE, 최대오차, 2m 이내 비율, 가용률을 계산한다.
8. FIX 표본이 30개 미만이거나 30초 미만이거나 시간 매칭률이 90% 미만이면 성공 수치 대신 `정답 부족`을 출력한다.

NGII 자료 다운로드는 자동이지만 File-Key의 최초 발급까지 자동화하지 않는다. NGII 포털에서 발급받은 개인 키를 분석 앱에 한 번 입력하면 Android Keystore 기반 AES-GCM으로 암호화해 보관하고 이후 다운로드에 사용한다.

동일 스마트폰의 원시 GNSS로 만든 PPK는 매우 유용한 사후 기준선이지만 독립 측량 장비의 절대 ground truth는 아니다. 결과 JSON에도 `same-phone postprocessed`, `development-only`, `non-independent`를 기록한다.

## 4. 분석 앱의 안전·실패 정책

| 정책 | 적용 내용 |
|---|---|
| 권한 최소화 | `INTERNET`만 사용하고 위치·광역 저장소 권한은 요청하지 않음 |
| 입력 접근 | Android SAF를 통해 사용자가 고른 파일만 가져옴 |
| 키 보관 | Android Keystore AES-GCM envelope, 손상 시 키와 envelope 초기화 |
| 화면 보호 | 민감 입력 화면에 `FLAG_SECURE` 적용 |
| 네트워크 제한 | NGII 정확한 HTTPS origin만 허용하고 다른 host redirect 거부 |
| 압축 방어 | 크기·파일 수·압축률·경로 탈출·중첩 archive 제한 |
| 기준국 검증 | 30km 거리, 파일명, RINEX 종류, marker, ECEF, 세션 시간 범위를 확인하고 실패 시 다음 기준국 시도 |
| 정답 부족 | 기준국 거리 초과, FIX 부족, 시간 부족, 매칭률 부족 등을 일반 오류가 아니라 `truth_insufficient`로 분류 |
| 결과 분리 | 새 import 실패 시 이전 결과를 즉시 제거해 잘못 내보내는 상황 차단 |
| 시간 안전 | 현재 leap-second 표의 보증 범위를 넘는 2027-01-01 이후 GPST 변환은 갱신 전까지 fail-closed |

## 5. Luminggi 변경 분석과 선별 적용

### 적용

| 항목 | 적용 내용 | 주요 위치 |
|---|---|---|
| 홈 UI | 기존 기능을 유지한 1열 카드형 홈, 목적지·음성·손상 점자블록 신고 진입점 | `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt` |
| 접근성 | 콘텐츠 설명, 키보드 포커스 테두리, 안전 상태 배너, 입력 helper/label 보강 | `MainActivity.kt`, `values/styles.xml`, `values-v29/styles.xml` |
| 명도 대비 | 본문 4.5:1, 포커스·경계 3:1 기준 테스트와 파란 강조색 `#1b4cd8` | `WalkSafePaletteContrastTest.kt`, styles |
| 음성 데이터 점검 | 한국어 오프라인 TTS가 없으면 시작·재개 차단, 보행 중 감지하면 안전 종료, 설치 후 재점검 | `WalkSafeStartupCapability.kt`, `MainActivity.kt` |
| 기기점검 | 기존 실제 capability 검사 흐름과 재검사 동작에 Luminggi 안내 UX를 결합 | `WalkSafeStartupCapability.kt`, 관련 테스트 |
| 신고 표현 | 일반 위험 신고가 아니라 현재 지원 범위인 `손상 점자블록 신고`로 문구 제한 | `MainActivity.kt` |
| 계정·가입 UX | 기존 `동의 -> 상세정보` 순서를 보존하며 헤더·필드 안내·중복 단계 음성 제거 | `MainActivity.kt`, 가입·계정 정적 테스트 |

### 보류

| 항목 | 이유 |
|---|---|
| 서버 기반 기기 지원 매트릭스 | 브랜치에는 초안 성격의 표면만 있고 운영 데이터 출처·관리 권한·실패 정책이 확정되지 않음 |
| Luminggi 이미지·폰트 자산 | 저장소 배포에 필요한 라이선스와 출처가 확인되지 않음 |
| 다운로드한 RINEX 자료 재사용 캐시 | 민감 자료 수명주기와 무결성 정책을 늘리지 않기 위해 이번 최소 범위에서는 매 실행 재다운로드 |

### 적용하지 않음

| 항목 | 이유 |
|---|---|
| 브랜치 전체 merge 또는 커밋 cherry-pick | 기존 위치 추적, ARCore/CameraX, 신고 상태·이력, 가입 흐름을 삭제하거나 되돌리는 충돌이 큼 |
| 가입 순서 `상세정보 -> OTP -> 동의` | 동의를 뒤로 미루고 동의 화면 전에 gate를 거는 논리 오류가 있음 |
| 고정 5단계 가입 문구 | 실제 단계와 맞지 않아 접근성 안내가 거짓이 됨 |
| 설정 화면 고정 placeholder | 실제 기능으로 이어지지 않는 잠긴 표면임 |
| 기존 ARCore·신고·상태·이력 제거 | 현재 프로젝트의 구현된 제품 기능을 퇴행시킴 |
| 명도 대비 `1.0 이상` 판정 | 접근성 기준으로 의미가 없어 4.5:1 및 3:1 테스트로 대체함 |
| macOS 전용 `aapt2` checksum | 현재 Linux/Android 빌드에 불필요하고 의존성 검증 범위를 불필요하게 넓힘 |

## 6. 주요 구현 위치

- 분석 앱: `apps/android/positionevalapp/`
- 파이프라인 조정: `apps/android/positionevalapp/src/main/java/kr/co/hanium/dreamup/walksafe/positioneval/AnalysisCoordinator.kt`
- NGII 다운로드: `apps/android/positionevalapp/src/main/java/kr/co/hanium/dreamup/walksafe/positioneval/core/NgiiClient.kt`
- GnssLogger 변환: `apps/android/positionevalapp/src/main/java/kr/co/hanium/dreamup/walksafe/positioneval/core/GnssLoggerRinex3Converter.kt`
- RTKLIB 연결: `apps/android/positionevalapp/src/main/java/kr/co/hanium/dreamup/walksafe/positioneval/core/PpkEngines.kt`
- 네이티브 엔진: `apps/android/positionevalapp/src/main/cpp/`
- 앱 사용법과 제한: `apps/android/positionevalapp/README.md`
- 사전 Luminggi 분석: `docs/testing/luminggi-selective-integration-analysis-20260904.md`

## 7. 최종 검증

| 검증 | 결과 |
|---|---|
| 사용자 앱 단위 테스트·Lint·debug APK | PASS |
| 분석 앱 단위 테스트·Lint·debug APK·AndroidTest APK | PASS |
| 전체 strict Gradle 게이트 | `BUILD SUCCESSFUL`, 136 tasks |
| Python trace v2 평가 | 14/14 PASS |
| RTKLIB 호스트 골든 | 120 epochs, 11 satellites, Q1 FIX 44 epochs, PASS |
| GnssLogger 변환 RINEX 교차검증 | 2 epochs, 4 satellites, pseudorange 8, carrier phase 8, PASS |
| 최종 독립 converter 재검토 | 이전 Critical/High 해결, 새 Critical/High 없음 |
| 실제 NGII File-Key 다운로드 | NOT_RUN |
| 실기기 설치·instrumentation | ADB 연결 기기 없음, NOT_RUN |
| 실제 동시 보행 로그 E2E 및 2m 판정 | 입력 데이터 없음, NOT_RUN |

## 8. 최종 판정

요청한 분석 앱과 Luminggi 선별 통합의 코드 구현 및 자동 검증은 완료했다. 실제 정확도 정답지는 실외에서 WalkSafe trace와 GnssLogger 로그를 같은 시간에 수집한 뒤 이 분석 앱으로 생성한다.

현재 상태만으로 위치 오차 2m 목표를 달성했다고 판정할 수는 없다. NGII File-Key를 입력한 운영 다운로드, 연결 기기 instrumentation, 실제 보행 로그의 FIX 가용률과 오차 결과가 남아 있다. 기준이 부족하면 앱은 수치를 꾸미지 않고 `정답 부족`으로 판정한다.
