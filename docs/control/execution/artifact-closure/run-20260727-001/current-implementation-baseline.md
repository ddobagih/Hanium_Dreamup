# WalkSafe Current Implementation Baseline

- 문서 ID: `WALKSAFE-CURRENT-IMPLEMENTATION-BASELINE-20260727-001`
- 기준일: `2026-07-27`
- 정책 기준선: `PB-WALKSAFE-FEATURE-POLICY-1.0.1`
- 소스 기준: `2026-07-26` W3 dirty-worktree exact content snapshot
- 승인 상태: `NOT_APPROVED`
- 출시 상태: `NOT_ELIGIBLE`

## 기준선 경계

현재 파일 정체성은
`docs/control/execution/artifact-remediation/20260726/w3/evidence-20260726-003/source-snapshot.json`
에 결속한다. 이 snapshot은 2,960파일을 안정적으로 읽은
`DIRTY_WORKTREE_EXACT_CONTENT_SNAPSHOT`이며 `source_commit=null`이다.
path set은
`971cee4fbded63faf05e30b0dc7343a9eff41e4612edff307f3df5581c880eff`,
content set은
`f7a05a1abd7b89053dd7d7d052508dfd43b19821174c8de5a82fe754ae90cade`
이다.

이 문서는 clean commit, 전체 구현 완료, 정식 시험 완료, 실기기 현장
완료, 배포, 법무 승인 또는 출시 승인을 주장하지 않는다.

## 역사 문서 판정

| 문서 | 판정 | 이유 |
|---|---|---|
| `implementation-manifest.json` | `HISTORICAL_STALE` | 2026-07-21, 정책 1.0.0, 588개 tracked 후보 기준 |
| `module-register.json` | `HISTORICAL_STALE` | 관리자 앱을 미구현으로 기록하고 Android Gateway를 포함하지 않음 |
| `quality-evidence-register.json` | `HISTORICAL_STALE` | W3 내부 exact-source 실행 근거 이전의 후보 시험 소스 원장 |
| `walksafe-implementation-gap-analysis-20260722-r001.json` | `HISTORICAL_STALE` | 핵심 앱·경계 파일 해시가 변경돼 기존 Gap 상태 수치 재판정 필요 |

기존 파일은 보존한다. 이 판정은 역사 기록을 삭제하거나 과거 시점의
관찰을 부정하지 않고, 현재 상태 주장에 재사용하지 못하게 한다.

## 현재 구현 단위

| 단위 | 현재 사실 | 주장하지 않는 범위 |
|---|---|---|
| Android 사용자 앱 | `:app`, ID `kr.co.hanium.dreamup.walksafe`, 버전 `0.1.0`; 최초 사용·동의·로그인·사용자 확인 재개 경로 존재 | 정식 출시, 전체 실기기 E2E, 안전 성능 승인 |
| Android 관리자 앱 | 별도 `:adminapp`, ID `kr.co.hanium.dreamup.walksafe.admin`, 버전 `0.2.0-security`; 인증·재인증·세션 폐기·복구 보안 경계 존재 | 신고 검수·기관 전달 등 전체 운영 워크플로, 복구 훈련 승인, 서명·배포 |
| Android Gateway | 독립 Node.js 22 + TypeScript 서비스; session/navigation/search/report/privacy 제어면 존재 | 상태는 `NOT_DEPLOYED`; 실기기 연결과 cross-process 완료 아님 |
| Backend/PostGIS | 현행 서비스 후보; W3의 DB-free 및 임시 PostGIS exact-subject 근거 존재 | production migration, backup·restore·rollback, 전체 E2E |
| Model | `unified_walksafe` 개발 primary와 legacy fallback 구성; registry stage `candidate` | `deployment_eligible=false`, `release_eligible=false`; 운영 승인 모델 아님 |
| Voice | Android 플랫폼 STT/TTS 경로와 별도 후보 소스 존재 | 지원 기기 전체의 offline 음성 완료나 정식 접근성 PASS 아님 |
| Web/PWA | `LEGACY_REFERENCE_ONLY` | 현재 사용자·관리자 제품이나 Android 출시 근거 아님 |

## W3 내부 시험 근거

다음 결과는 exact source 또는 exact subject에 한정된 내부 근거다.

| 구분 | 결과 |
|---|---|
| Android 사용자 내부 단위시험 | `728 PASS`, unchanged exact source 재사용 |
| Android 관리자 내부 단위시험 | `38 PASS`, unchanged exact source 재사용 |
| Android Gateway 내부 Node 시험 | `62 PASS`, unchanged exact source 재사용 |
| Android 사용자·관리자 lint | `PASS_REUSED_EXACT_SOURCE` |
| Gateway typecheck | `PASS_REUSED_EXACT_SOURCE` |
| Backend DB-free | `33 PASS`, current exact subject |
| 임시 migrated PostgreSQL exact node | 동일 DB에서 순차 2회 `PASS` |
| Backend/model compileall | `PASS_CURRENT_SOURCE` |
| SPDX 2.3, CycloneDX 1.6 구조 검사 | `PASS` |

재사용 PASS는 같은 source content set에만 유효하다. 새 소스 변경 뒤 자동
승계하지 않는다.

## 정식 검증 상태

| 항목 | 상태 |
|---|---|
| 계획된 formal 시험 279건 | `NOT_RUN`, formal PASS 0 |
| reproducible rebuild | `NOT_RUN` |
| attestation | `NOT_ASSESSED` |
| signing | `NOT_ASSESSED` |
| 실제 기기 대화형 전체 흐름 | `NOT_RUN` |
| Android→Gateway→Backend→PostGIS cross-process | `NOT_RUN` |
| release | `NOT_ELIGIBLE` |

후속 구현 원장은 내부 PASS, 재사용 PASS, formal `NOT_RUN`을 같은 PASS로
합산하면 안 된다.

## P0 보완

1. 구현 manifest를 정책 1.0.1과 current exact snapshot 기준 successor로 만든다.
2. module register에 두 Android 모듈과 Android Gateway를 반영한다.
3. quality evidence register에 W3 내부 evidence를 결과 경계별로 반영한다.
4. 2026-07-22 Gap 68건은 현재 파일 해시에 대해 전면 재판정한다.
5. 기존 canonical/generated 파일은 직접 수정하지 않고 새 revision과 검증 receipt를 만든다.
