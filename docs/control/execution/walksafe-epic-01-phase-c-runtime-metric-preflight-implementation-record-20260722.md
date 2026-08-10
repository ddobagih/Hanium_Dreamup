# EPIC-01 Phase C 실행 중 미터 거리 사전검사 내부 구현 기록

- 문서 ID: `WS-EPIC-01-PHASE-C-RUNTIME-METRIC-PREFLIGHT-IMPLEMENTATION-20260722-001`
- 버전: `0.1.0`
- 상태: `INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS`
- EPIC: `EPIC-01 IN_PROGRESS`

## 이번에 내부 구현한 것

- **STABLE_RUNTIME_METRIC_FRAME_PREFLIGHT** — 별도 세대의 실제 ARCore 프레임에서 추적·미터 depth·유효 표본·서로 다른 프레임·관찰시간·통과율을 검사하며 시간초과, 순서 오류, 일시 실패와 생명주기 취소를 UNKNOWN·BLOCKED로 닫는다.
- **EMPTY_APPROVED_PROFILE_FAIL_CLOSED** — 운영 승인 프로필 목록은 빈 목록이며 제조사·모델·device·SDK 범위와 비어 있지 않은 프로필 버전이 정확히 하나 일치할 때만 승인한다. 중복·빈 값·무일치는 실패 닫힘이다.
- **PREFLIGHT_RUNTIME_ISOLATION_AND_RECHECK** — MainActivity의 사전검사 AR session은 탐지·신고·피드백·길안내를 시작하지 않고 종료한다. 사용자가 확인한 뒤 새 runtime session에서 미터 프레임을 다시 검사하며 근거가 사라지면 탐지 이후 출력과 대기 피드백을 중단한다.

## runtime metric 정책 계약

- 정책 ID: `WS-RUNTIME-METRIC-PREFLIGHT-POLICY`
- 정책 버전: `1.0.0`
- Kotlin 원본: `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/RuntimeMetricPreflight.kt`
- Kotlin SHA-256: `633f7ebb848ac99ad36cc42186b119f07f297c472378fc5ca8785eaa96d2c3f5`

| 규칙 | 정확한 계약 |
|---|---|
| 최대 검사 시간 | `10,000 ms` |
| 서로 다른 프레임 | `>= 10` |
| 관찰 구간 | `>= 1,000 ms` |
| 통과 프레임 | 추적 중이고 미터 depth가 있으며 유효 거리 표본 `>= 30` |
| 최소 통과 비율 | `>= 0.80` |
| 유효 미터 거리 | `0.2 <= distance <= 8.0 m` — 양쪽 경계 포함, 유한값만 허용 |

계약 지문: `03fac0b7b77fd4c22a9ec5797599171a30d49b32d15abf680aa556f8bab66a7b`

## 운영 승인 기기와 시험 경계

운영 승인 프로필 목록은 **0개(빈 목록)**다. 따라서 현재 어떤 기기도 이 기록만으로 FULL 승인되지 않는다. 실제 휴대전화 검증과 정식 시험은 실행하지 않았다. 내부 JVM 검사는 구현 회귀일 뿐 실기기·정식시험 증거가 아니다.

## 공개 미결사항

- `PHASE-C-PRODUCTION-PROFILE-EMPTY` / **OPEN** — 운영 승인 지정 기기 프로필 목록은 비어 있어 현재 어떤 기기도 FULL로 승인되지 않는다.
- `PHASE-C-ACTUAL-DEVICE-NOT-RUN` / **NOT_RUN** — 실제 지정 휴대전화에서 ARCore session·Depth·미터 프레임 안정성과 runtime 재검사를 실행하지 않았다.
- `PHASE-C-FORMAL-TESTS-NOT-RUN` / **NOT_RUN** — FP-009 연결 시험을 포함한 정식 시험 279개는 모두 미실행이다.
- `EPIC-01-LEGACY-WEB-TECHNICAL-CLOSURE` / **OPEN_NEXT** — 과거 Web 실행·배포·외부 접근 경로의 전체 기술 폐쇄가 남아 있어 GAP-018을 완료로 판정할 수 없다.

정식 시험 **279/279 NOT_RUN**, 5개 gate **NOT_RUN·미면제**, 출시는 **NOT_ELIGIBLE**이다.

## 다음 한 가지 작업

`EPIC-01-LEGACY-WEB-TECHNICAL-CLOSURE` — 남은 Legacy Web 실행·빌드·배포·외부 접근 경로를 기술적으로 닫고 읽기 전용 참고 경계를 검증한다.

내용 지문: `c469228bfb0662c5eccfc3a9aa835ff05d28a69a94d8b32e886de3a2c65d6906`
