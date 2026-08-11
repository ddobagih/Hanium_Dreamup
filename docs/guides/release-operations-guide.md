# 릴리스·배포 운영 가이드

현재 WalkSafe 출시는 `NOT_ELIGIBLE`이다. 정식 시험 279건과 실제 기기·현장 시험은 `NOT_RUN`이며, 5개 release gate도 모두 `NOT_RUN`·미면제다. 이 문서는 그 상태를 바꾸는 승인서가 아니라 릴리스 작업의 순서와 증거 경계를 안내한다.

## 서로 다른 네 가지 확인

| 구분 | 답하는 질문 | 현재 의미 |
|---|---|---|
| 자동 테스트 | 코드 변경이 빠른 내부 회귀검사를 통과했는가? | 내부 개발 피드백. 정식 시험 결과가 아님 |
| 정식 279개 시험 | 승인된 후보·환경·절차로 각 case를 실행했는가? | [시험 케이스 원장](../deliverables/06-testing/registers/test-cases.json)의 279건 모두 `NOT_RUN` |
| release gate·승인 | 필수 실측·독립검토·복구훈련과 승인 권한이 충족됐는가? | 5개 gate `NOT_RUN`, 출시 `NOT_ELIGIBLE` |
| 배포·운영 | 승인된 후보를 실제 환경에 배포하고 smoke·canary·복귀를 확인했는가? | 실제 배포·운영 사건 `NOT_RUN` |

CI나 로컬 자동 테스트가 성공해도 정식 279건을 `PASS`로 바꾸지 않는다. 정식 시험은 같은 source, 앱·서버 build, 모델, 설정, DB migration, 기기·환경과 실행 원자료에 결속돼야 한다. 상세 진입·종료 기준은 [마스터 시험계획](../deliverables/06-testing/test-plan.md)을 따른다.

## 릴리스 흐름

1. **내부 검증**: 변경 범위의 자동 테스트, lint, build, 정적·보안 검사를 실행한다.
2. **후보 고정**: 명명된 후보의 source commit, Android 앱, 관리자 앱, gateway, backend, 모델, 설정, schema·migration과 hash를 한 세대로 고정한다.
3. **정식 시험**: 승인된 계획·환경·담당자로 279개 case 중 적용 대상 전체를 실행하고 결과·결함·잔여위험을 append-only 증거로 남긴다.
4. **5개 gate 확인**: 각 gate를 독립 증거와 권한 있는 판정으로 닫는다. 실행하지 않은 gate를 면제하거나 내부 테스트로 대신하지 않는다.
5. **출시 판단**: TST-22와 REL-02의 지정 검토·승인자가 같은 후보의 시험·보안·운영·gate 근거를 보고 `GO`, 조건부 `GO` 또는 `NO-GO`를 기록한다.
6. **배포 실행**: 승인된 대상 환경·권한·중단조건에 따라 staging부터 배포하고 migration, smoke, canary와 rollback 준비를 확인한다.
7. **운영·인계**: 모니터링, incident 대응, 복구, 지원, 기술자료 수령과 책임 이관을 실제 영수증으로 남긴다.

현재는 2~7단계가 완료됐다고 주장할 근거가 없다. 계획서, 빈 template, build 파일 또는 과거 Web/PWA 결과는 실행 증거가 아니다.

## 닫히지 않은 5개 gate

- `GATE-PHONE-QUEUE-BYTE-LIMIT`: 휴대전화 대기자료의 실제 용량 한도
- `GATE-SERVER-CAPACITY-STATE-CONTRACT`: 서버 용량상태를 휴대전화에 전달하는 규칙
- `GATE-RAW-COLLECTION-RELEASE-REVIEW`: 무가림 원본 수집의 출시 전 독립 검토
- `GATE-CLOUD-COST-MEASUREMENT`: 실제 클라우드 저장비 측정
- `GATE-SINGLE-ADMIN-RECOVERY-DRILL`: 관리자 휴대전화 분실 복구훈련

gate의 current 상태와 승인 조건은 [출시 준비도 판단](../deliverables/06-testing/release-readiness-decision.md)과 [릴리스 통제](../deliverables/09-release/release-control.md)를 확인한다.

## 후보·증거 규칙

- 여러 후보의 부분 성공을 합쳐 하나의 합격으로 만들지 않는다.
- 실행 증거에는 release/candidate ID, 대상 환경, artifact hash, 실행자·시각, 명령·결과, smoke와 rollback 결과를 남긴다.
- 실제 실행 결과는 기존 파일을 덮어쓰지 않고 새 instance로 추가한다. template 자체를 성공 증거로 사용하지 않는다.
- 실패와 중단도 숨기지 않고 원인, 영향, 복구 상태, 재실행 조건을 기록한다.
- 민감 운영 로그·비밀값·사용자 원본·서명 원본은 Git에 넣지 않고 통제 저장소 참조 ID와 최소 메타데이터만 남긴다.
- 배포 완료, 외부 수락, 인수·이관과 프로젝트 종료는 각각 권한 있는 사람의 실제 receipt가 있어야 한다.

배포 절차와 증거 형식은 [배포·복귀 절차](../deliverables/09-release/deployment-evidence.md), [배포 실행 template](../deliverables/09-release/templates/deployment-execution-template.json), [인도·인계 가이드](../deliverables/09-release/delivery-and-handover.md)를 따른다.

## 중단·복귀 기준

다음 중 하나라도 발생하면 promotion을 멈추고 현재 안전한 버전을 유지하거나 승인된 rollback을 실행한다.

- artifact·hash·환경이 승인 후보와 다름
- 필수 시험 실패, P0/P1 결함 또는 수용되지 않은 안전·보안·개인정보 위험
- gate가 `NOT_RUN`, 실패 또는 권한 없는 면제 상태
- migration·smoke·canary 실패, 관측 불가 또는 rollback 경로 미검증
- 운영 책임자·비밀·권한·incident 대응 채널이 준비되지 않음

실제 복구 계획은 [운영 복구 계획](../deliverables/10-operations/recovery-plan.md)을 확인한다. 복귀 성공도 계획 문서가 아니라 실제 대상과 실행 증거로 판정한다.

## 출시 전 최종 확인

- 같은 후보 세대의 manifest와 hash가 고정됐는가?
- 자동 테스트와 정식 시험 결과를 구분했는가?
- 정식 279개 시험의 적용 대상 결과와 결함·잔여위험이 승인됐는가?
- 5개 gate가 canonical release register가 요구하는 완료 상태이고 `waived=false`인가?
- TST-22·REL-02 승인자가 명시적으로 판정했는가?
- staging·production 권한, 비밀, migration, smoke, canary, rollback과 monitoring이 준비됐는가?
- 배포·인도·인계 receipt가 실제 사건 뒤 append-only로 기록됐는가?

하나라도 아니면 출시는 계속 `NOT_ELIGIBLE`이다.
