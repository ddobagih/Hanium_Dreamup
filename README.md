# Hanium DreamUp — WalkSafe

WalkSafe는 시각장애인의 도심 보행을 돕는 Android 프로젝트입니다. 카메라와 기기 내 AI로 가까운 위험을 찾고, TMAP 기반 경로와 음성·진동 안내를 제공하며, 손상된 점자블록 신고를 돕습니다. 흰지팡이·안내견·보호자를 대체하거나 보행 안전을 보장하는 제품으로 설명하지 않습니다.

## 최근 변경 — 2026-10-25 · temp

이번 반영은 `2e574472` 이후 작업 트리에 누적된 Android·Gateway·Backend·음성 서비스·시험·문서 변경을 함께 정리한 것입니다. 아래는 현재 코드의 변경 내용이며, 기능 구현과 실제 검증 범위를 구분합니다. 이전 분담 원장의 DONE/PARTIAL 수치를 이번 검증 결과로 다시 집계하지 않았습니다.

### 기기점검·연습과 화면 동작

| 상황 | 적용한 동작 |
|---|---|
| 같은 계정·같은 휴대폰에서 다시 로그인 | 완료한 기기점검·연습을 복원합니다. 미완료 연습은 저장된 진행을 이어갑니다. |
| 새 휴대폰에서 로그인 | 기기점검과 연습을 모두 다시 진행합니다. 다른 설치의 기록을 완료 근거로 사용하지 않습니다. |
| 최초 명시 기기점검 | 내장 시험 이미지로 실제 CPU·GPU 실행과 처리 시간을 비교합니다. |
| 일반 앱 재실행·카메라 진입 | 저장된 인식 방식을 준비하며 CPU·GPU 비교를 반복하지 않습니다. |
| 일반 모델 준비 후 명시 재점검 | 이전 모델 작업·해제를 확인한 뒤 비교하고 새 인스턴스도 다시 검증합니다. |
| 길라잡이 목적지 검색 | 화면의 두 음성 명령·취소 버튼과 “검색어를 입력하세요” 안내를 제거했습니다. 직접 입력·검색·선택·취소와 홈 음성 명령 카드는 유지합니다. |

### 누적 변경 내용

| 영역 | 바뀐 내용 |
|---|---|
| Android 구조·비동기 작업 | `MainActivity`에 모였던 길 안내·보정·음성·권한·카메라·신고·화면 책임을 controller/session으로 분리했습니다. 취소된 작업과 이전 계정·보행·화면의 늦은 결과가 현재 상태를 덮지 않도록 소유권을 확인합니다. |
| 경로·사용자 위치·방향 | GNSS·보행 관측과 불확실성을 경로 판정에 연결했습니다. 경로 밖 출발의 접근 안내, 실제 합류 확인, 이탈 재확인, 방향 복구와 도착 조건을 구분하며 음성 전달만으로 합류·도착을 확정하지 않습니다. |
| 일시적인 센서 오차 | 순간 위치 점프·방향 흔들림·작은 카메라 각도 초과에 정상 이력과 제한된 재확인 시간을 적용했습니다. 지속 오류·오래되거나 부적합한 측정에 대한 제한은 유지합니다. |
| 음성 안내 연속성 | 일반·주의 안내가 진행 중인 문장을 불필요하게 끊지 않도록 조정하고 긴급 위험·정지 우선순위는 유지했습니다. TTS 제출·시작·완료·오류·중단을 구분하고 실제 완료와 현재 소유권을 확인한 뒤 후속 안내·질문·교육으로 넘어갑니다. |
| 카메라·모델·추적 | 실제 처리 시간에 따른 추론 간격 조절을 카메라 테스트에 연결했습니다. 이미지·작업 티켓·카메라·모델 해제, 프레임 예약·완료 게시·취소와 추적 이력 경계를 보완했습니다. |
| 교육·기기점검 복원 | 계정·설치별 완료 기록과 부분 진행을 복원하고 재로그인이 최신 진행을 이전 단계로 되돌리지 않도록 수정했습니다. 설치 식별자가 다른 교육 기록은 원본을 지우지 않고 복원만 차단합니다. |
| 목적지 검색·TMAP 응답 | 역명 검색과 명시적인 주변 시설 검색을 구분했습니다. 실제 경로 응답의 빈 `facilityType`은 미상으로 정규화해 정상 경로가 거절되는 경우를 보완했습니다. |
| 신고·관리자·위치 평가 | 신고 후보·암호화 대기열·종료 후 전송과 관리자 업무·인증 authority를 분리했습니다. 계정·동의·세대·기기키·SAF 제출본을 재확인하며, 위치 평가도 취소·정리 후 결과를 게시합니다. |
| 서버·음성 서비스 구조 | Backend/Gateway의 기능 handler·저장소·codec·runtime과 STT/TTS 엔진 factory를 분리했습니다. 세션 변경·삭제 중 진행 요청을 취소하고 늦은 음성·경로 응답도 전달 직전에 재확인합니다. |
| 가입·동의·삭제 계약 | 가입 문서 정책 조회와 이메일 입력 벡터를 추가하고 SMTP 전체 제한 시간·중복 발송 방지를 보완했습니다. 설치별 서명 삭제 증거, 복구·외부 사본 확인과 관련 OpenAPI를 정리했습니다. |
| 선택형 길 안내 진단 | 기본 비활성인 제한된 metadata 진단 API를 추가했습니다. 좌표·음성 원문·미디어·헤더를 받지 않고 계정 세대, 보존 기간, 동의 철회·삭제 경계를 적용합니다. |

구체적인 책임 소유자는 [Android README](apps/android/README.md), [관리자 README](apps/android/adminapp/README.md), [신고 README](apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/README.md), [Gateway README](apps/android-gateway/README.md), [Backend README](backend/README.md)에서 확인할 수 있습니다.

### 이번에 직접 확인한 검증

- 최신 관련 Android 자동 검사 **69개 클래스·886개 통과**, 실패·오류·건너뜀 **0**. 사용자·시험 debug APK 빌드와 S25 설치에 성공했습니다. 이는 이번 변경 관련 부분 검사이며 전체 저장소·서버·출시 시험 통과를 뜻하지 않습니다.
- S25 실기기 시험 **3개 통과**: 실제 저장 로그인 HOME→길라잡이 화면·접근성·입력·취소, HOME→카메라 두 번 열기·닫기·재진입, 내장 이미지의 실제 native CPU·GPU 실행·캐시 분기입니다. 사용자 계정·교육·점검 상태를 가짜로 만들거나 초기화하지 않았습니다.
- 최신 사용자 APK SHA-256: `aad58eae1b4125f6092c2f63f2c3602183061add2c7b6f1e5cab2f9ff0830985`. 시험 APK SHA-256: `5363dbb9342cf14db583a73d53efdf5350402597c955f73dd260bcc2a1d7a6d8`. APK·개인 원로그·비밀 백업은 Git에 포함하지 않습니다.
- 카메라 시험은 약64초 동안 실제 홈 버튼으로 두 번 진입했고 카메라·주 모델·FastSAM의 해제를 각각 두 번 확인했습니다. 이 조건에서 심한 멈춤·비정상 종료는 재현되지 않았습니다.
- [최초 점검·카메라·길라잡이 검증](docs/testing/first-login-device-camera-20261025.md), [카메라 부하 비교](docs/testing/camera-reopen-20261025.md), [센서·음성 연속성](docs/testing/guidance-continuity-20261025.md), [제어 측정값의 실기기 검증](docs/testing/measured-input-device-20261025.md)에 APK별 결과와 근거 경계를 기록했습니다. 이전 버전의 성공을 최신 앱 전체 여정 성공으로 합치지 않습니다.

### 남은 문제와 미검증 범위

- 내장 사람 자료 두 개가 `person` 대신 `moving_e_scooter`로 출력되어 정확도 기준을 통과하지 못했습니다. CPU·GPU는 실제 실행됐지만 이 경우 **CPU를 유지**하며 GPU 선택·객체 정확도 해결로 보고하지 않습니다. 첫 시험 평균은 CPU 약241ms/GPU 약57ms입니다. 앱·TFLite 첨부 metadata·export의 21개 클래스 순서는 일치했으며 학습·전처리·export 중 원인은 아직 확정하지 못했습니다.
- 휴대폰 후면이 책상을 향한 상태였습니다. 실물 인식 정확도·유효 추적 중 FastSAM 추론(해당 카메라 시험의 완료 추론 0회)·야외 보행·음성 직접 청취·최신 앱의 실제 계정 HOME→도착 전체 여정은 검증 완료가 아닙니다.
- 새 기기의 기록 차단은 제어된 식별자 변경 시험으로 확인했습니다. 실제 두 번째 물리 휴대폰의 최초 로그인→기기점검→연습 전체 과정은 미수행입니다. native 선택 시험의 저장소도 폐기형 메모리 IO이므로 실제 새 휴대폰·디스크 저장 복원을 대신하지 않습니다.
- 로컬 기기 검증의 서버 연결은 USB 전달과 컴퓨터의 개발 서버를 사용했습니다. 운영 서버 배포·전체 서버 회귀·정식 안전시험·출시 승인은 이번 결과에 포함하지 않습니다.

## 이전 분담·개발 기록 — 2026-10-01 기준

아래 수치와 담당 상태는 당시 기록입니다. 현재 변경·시험 상태는 위의 2026-10-25 요약과 연결된 검증 문서를 우선합니다.

2026-10-01 기능 개선 원장 **rev135: DONE601 / PARTIAL256 / 미정합73 / 해당 없음12**입니다. 전체942개는 원문 분석872개와 정지·이동 추가 요구70개이며 확정 버그 수가 아닙니다. 최신 구현·시험 결과와 남은 검증 범위는 [A 완료 계획](docs/planning/walksafe-part-a-completion-plan-20260930.md)과 [daylog](daylog/2026-10-01.md), 분담 시작 상태는 [인계 현황](docs/handoff/2026-09-30/status.md)에 있습니다.

팀 공통 브랜치는 **`temp`**입니다. [두 사람 작업 인계](docs/handoff/2026-09-30/README.md)에서 A189개와 B188개를 나눴으며 아래 표는 rev119 분담 시작 snapshot입니다. 현재 A는 DONE48·PARTIAL101·미정합40, 잔여141개입니다. 기존 [보행·출력·준비46개와 경로·위치·이동56개](docs/handoff/2026-10-01/part-a-route-motion-split.md), [인식39개](docs/handoff/2026-10-01/part-a-two-session.md) 담당은 최신 사용자 지시에 따라 이미 착수한 묶음만 마무리하고 새 총괄1개·개발3개 세션으로 인계합니다. 전체 원문 DONE 목표는 유지하며, 기존 Goal은 paused로 확인해 보존했고, [새4세션 인계](docs/handoff/2026-10-01/four-session-transition/README.md)의 설정 담당이 작성 권한을 이양합니다.

| 담당 | PARTIAL | 미정합 | 합계 | 범위 |
|---|---:|---:|---:|---|
| [A 사용자](docs/handoff/2026-09-30/part-a.md) | 127 | 62 | 189 | 보행·경로·센서·인지·출력·기기 준비 |
| [B 팀원](docs/handoff/2026-09-30/part-b.md) | 155 | 33 | 188 | 계정·음성 입력·신고·개인정보·관리자·서버·검증 도구 |

PARTIAL은 원문의 남은 구현·검증 조건을 닫아야 합니다. 미정합은 원문과 현행 코드·근거의 대조부터 수행합니다. 필수 실기기·현장·외부 증거가 없으면 완료로 올리지 않습니다. 최근 사용자 앱 전체 시험에는 기존21개, Gateway에는 기존음성7개 실패가 남아 있습니다. APK 생성과 JVM 연동 시험은 실기기 완료를 뜻하지 않습니다.

## 빠른 링크

| 찾는 내용 | 시작점 |
|---|---|
| 현재 상태·남은 문제·담당 | [2026-09-30 인계](docs/handoff/2026-09-30/README.md) |
| 항목별 원문·잔여·기존 근거 | [잔여377개 상세](docs/handoff/2026-09-30/remaining-items.json) |
| 전체·단계 실행 계획 | [21개 분야 기능 개선 계획](docs/planning/walksafe-feature-improvement-execution-20260926.md) |
| 사용자 A 현재 완료 목표·단계·진행 | [A189개 완료 계획](docs/planning/walksafe-part-a-completion-plan-20260930.md) |
| 개발환경·새 clone 준비 | [개발환경 가이드](docs/guides/development-environment-guide.md) |
| 사용자·관리자 앱 빌드·기기 시험 | [Android 앱별 테스트 가이드](docs/guides/android-apps-testing-guide.md) |
| 코드·요청 흐름 | [코드 가이드](docs/guides/code-guide.md) |
| 직접·통합 시험과 결과 해석 | [테스트 가이드](docs/guides/testing-guide.md) |
| 팀 브랜치·파일 소유·통합 | [팀 작업 흐름](docs/guides/team-workflow.md) |
| 전체 문서 | [가이드 포털](docs/guides/README.md) |
| 기여·보안 | [CONTRIBUTING](CONTRIBUTING.md) · [SECURITY](SECURITY.md) |

## 제품 구성

승인 정책은 [PB-WALKSAFE-FEATURE-POLICY-1.0.1](docs/control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json)을 따릅니다.

| 구성 | 역할 |
|---|---|
| [`apps/android/app`](apps/android/app) | 일반 사용자용 Android 앱 |
| [`apps/android/adminapp`](apps/android/adminapp) | 지정 관리자용 별도 Android 앱 |
| [`apps/android-gateway`](apps/android-gateway) | 세션·보행·길찾기·신고·동의·개인정보 제어 |
| [`backend`](backend) | 계정·신고·관리자·공간 데이터·외부 API 서버 |
| [`model`](model) · [`data_sources`](data_sources) | 모델·데이터와 provenance |
| [`voice`](voice) | Gateway 뒤의 선택형 STT·TTS 지원 서비스, 기본 OFF |
| [`apps/web`](apps/web) | 과거 Web/PWA 참고 코드, `LEGACY_REFERENCE_ONLY` |

관리자 앱의 기관 전달은 앱 밖에서 수행한 수동 전달 사실의 기록입니다. 외부 기관 자동 전송이나 기관 응답을 앱이 검증한 결과로 설명하지 않습니다.

## 작업 시작

1. [AGENTS.md](AGENTS.md)의 **현재 작업 원칙 — 기능 구현 우선**을 읽습니다.
2. [자기 파트](docs/handoff/2026-09-30/README.md)에서 작은 ID 묶음을 고르고 원문·현행 코드·관련 시험을 확인합니다.
3. 개발환경을 준비합니다. 새 clone에는 Android SDK 경로·venv·Node 의존성·비밀값·한국어 Vosk 모델·생성 APK가 포함되지 않습니다. [모델 준비 절차](docs/guides/android-apps-testing-guide.md#모델-자산-준비)를 먼저 확인합니다.
4. 현행 반례 → 최소 수정 → 같은 조건의 직접 시험 → 필요한 독립 검토 → 통합 순서로 진행합니다.

과거 Goal graph나 checkpoint의 focus는 현재 기능 분담을 대신하지 않습니다. 과거 통제 문서·승인본·gate 원출력은 보존하며, 제품과 무관한 통제 복구를 기능 개선 시작 조건으로 확대하지 않습니다. 빌드·시험 명령은 [현행 테스트 가이드](docs/guides/testing-guide.md)를 따릅니다.

## 정식 검증·출시 경계

다음은 기존 [승인·통제 기록](docs/control/walksafe-project-continuation-checkpoint.json)의 상태입니다. 위 기능 개선 원장의 DONE 수와 별도로 관리합니다.

- Goal package: v2.4 `ACTIVE`
- 정식 시험: 279/279 `NOT_RUN`
- 출시 gate: 5/5 `NOT_RUN`, 면제 없음
- 출시 상태: `NOT_ELIGIBLE`

내부 시험·debug build·국소 기능 완료를 정식 안전시험, 운영 배포 또는 출시 승인으로 바꾸지 않습니다. [2026-08-30 실행 보고서](docs/testing/walksafe-real-environment-execution-report-20260830.md)는 해당 시점의 제한된 실행 기록입니다.

## 데이터·모델과 보존

현재 모델 선택·class·tensor·hash는 [runtime config](apps/android/app/src/main/assets/model-config/two_model_runtime.json), 빌드에 포함되는 asset은 실제 Gradle 계약을 따릅니다. 기존 추적 모델의 예외는 새 weight·dataset·사용자 자료 반입 허가가 아닙니다.

비밀값, 원본 데이터셋, 사용자 영상·음성·정확 위치, 운영 로그, 임시 build/cache, `work/`는 Git에 넣지 않습니다. [데이터·AI 가이드](docs/guides/data-ai-guide.md)와 [저장소 가이드](docs/guides/repository-guide.md)를 확인합니다. 현대화 이전 저장소의 복구 지점은 [보존 기록](docs/planning/repository-modernization-20260811/preservation-record.md)에 있으며, 기존 `main`은 이번 인계의 통합 대상이 아닙니다.
