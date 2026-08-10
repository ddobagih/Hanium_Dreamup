# WalkSafe 구현 Gap 집중 재평가 r003

- 보고서: `WS-IMPLEMENTATION-GAP-ANALYSIS-20260722-003` v0.3.0
- 선행 보고서: `WS-IMPLEMENTATION-GAP-ANALYSIS-20260722-002` — 불변 보존
- 재평가 범위: `GAP-018 / FP-009` 한 건
- 출시 상태: **NOT_ELIGIBLE**

## 판정

`GAP-018`은 r002의 `CONFLICTING`에서 r003의 **PARTIAL**로 바뀌었다.

FP-009의 runtime metric 사전검사와 프로필 실패 닫힘 코드·내부 JVM 검증이 생겨 승인 규칙과 반대뿐이던 CONFLICTING에서 PARTIAL로 바뀐다. 운영 승인 프로필 0개, 실기기·정식 시험 미실행, Legacy Web 미폐쇄 때문에 IMPLEMENTED나 완료로 올릴 수 없다.

## GAP-018에 결속된 runtime metric 정책 계약

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

## 상속한 Legacy Web 증거 경계

`EVD-PRODUCT-WEB`은 `WS-IMPLEMENTATION-GAP-ANALYSIS-20260722-002`(`r002`)에서 상속했다. r003에서는 이 증거를 **재검증하지 않았으며**, Legacy Web 기술 폐쇄가 남았다는 선행 관찰을 PARTIAL 판정의 잔여 근거로만 직접 연결한다.

운영 승인 프로필은 0개이며 실제 기기와 279개 정식 시험은 미실행이다. Legacy Web 전체 기술 폐쇄도 남아 있어 완료 판정이 아니다.

전체 68개 상태 집계는 `{"BLOCKED": 5, "CONFLICTING": 22, "EVIDENCE_MISSING": 4, "MISSING": 19, "PARTIAL": 18}`다. 나머지 67개 assessment는 r002에서 그대로 운반했으며 이번에 재평가하지 않았다.

5개 gate는 `NOT_RUN`·미면제다.

내용 지문: `ad64e2a4214e680c21326dfa7e3410e53414b61c20c8d1fdabc4e79b0a6f73e9`
