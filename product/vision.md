# WalkSafe 제품 비전

상태: `CURRENT_POLICY_ALIGNED`

정책 기준선: `PB-WALKSAFE-FEATURE-POLICY-1.0.1`

기준일: `2026-07-22`

## 한 문장 제품 목적

워크세이프는 시각장애인의 도심 보행 중 가까운 위험과 이동 방향을 알려 주고 손상 점자블록 신고를 돕는 안드로이드 보행 보조 서비스입니다.

워크세이프는 보행 안전을 보장하지 않으며 흰지팡이·안내견·보호자를 대신하지 않습니다.

## 해결하려는 문제

- 화면을 계속 보기 어려운 사용자가 가까운 위험을 짧은 음성·진동으로 알아차리게 한다.
- TMAP 경로를 기준으로 목적지까지의 큰 이동 방향을 안내한다.
- 손상된 점자블록을 별도 신고 후보로 만들어 검수와 기관 전달을 돕는다.
- 불확실한 탐지·거리·경로를 안전한 것처럼 말하지 않고 제한 또는 안전정지한다.

## 제품 경계

| 구성 | 역할 | 현재 상태 |
|---|---|---|
| Android 사용자 앱 | 보행 준비·위험안내·길안내·신고 | 정식 제품 후보, EPIC 단위 구현 중 |
| 별도 Android 관리자 앱 | 한 명의 지정 관리자가 신고 검수·기관 전달·감사 업무 수행 | 앱 경계만 분리, 인증·업무는 EPIC-01 관리자 보안 구현 전까지 잠금 |
| Android API gateway·backend | 계정·TMAP 중계·신고·자료·운영 API | 현재 Next BFF 의존성을 독립 gateway로 전환 예정 |
| Web/PWA UI·Web 관리자 화면 | 과거 구현 참고 | `LEGACY_REFERENCE_ONLY`, 외부 실행·정식 배포 금지 |

사용자 앱과 관리자 앱은 앱 ID, 서명, 세션, 서버 대상과 배포경로를 공유하지 않는다.

## 대상 사용자와 사용 범위

| 사용자 | 범위 |
|---|---|
| 전맹·저시력 사용자 | 일반 도심 보도에서 승인된 휴대전화와 장착 방법으로 사용 |
| 지정 관리자 | 승인된 별도 Android 관리자 앱과 추가 본인확인으로 제한 업무 수행 |
| 현장시험 참여자·안전요원 | 승인된 시험계획·동의·중단 기준 안에서 검증 |

첫 버전에는 보호자 추적과 넘어짐 탐지를 완료 기능으로 넣지 않는다.

## 단계별 목표

1. 통합 시연: 같은 앱·서버·모델·설정 묶음으로 내부 기능을 확인한다.
2. 제한된 사용자 시험: 초대한 참여자와 승인된 지원 기기에서 안전요원·중단계획을 포함해 검증한다.
3. 정식 공개: 기능·안전·개인정보·접근성·모델 시험과 5개 gate가 모두 종료된 불변 출시 후보만 단계적으로 공개한다.

현재 단계는 `PRE_DEMO_DEVELOPMENT`, 배포 대상은 `NO_EXTERNAL_DISTRIBUTION`, 출시는 `NOT_ELIGIBLE`이다.

## 완료로 오해하면 안 되는 것

- 앱 빌드나 단위시험 통과는 실폰·현장 안전 검증이 아니다.
- 모델 실행은 보행 안전 입증이 아니다.
- Web/PWA 회귀시험은 Android 제품 완료 근거가 아니다.
- 승인된 102개 산출물은 구현·시험·배포 완료가 아니라 구현 기준의 승인이다.
- 5개 gate는 실제 측정·독립검토·복구훈련 없이 닫지 않는다.

## 현재 정본

- 재개 안내: `docs/control/walksafe-project-resumption-runbook.md`
- 제품 경계 설정: `configs/walksafe_product_boundary_20260722.json`
- 산출물 관리대장: `docs/deliverables/00-control/artifact-register.json`
- 요구사항 추적: `docs/deliverables/03-requirements/rtm.json`
- 구현 Gap과 순서: `docs/control/audits/walksafe-implementation-gap-analysis-20260722-r001.json`, `walksafe-implementation-remediation-backlog-20260722-r001.json`

2026-07-22 이전의 Web/PWA 주제품 비전은 역사 snapshot이며 현재 제품 결정으로 재사용하지 않는다.
