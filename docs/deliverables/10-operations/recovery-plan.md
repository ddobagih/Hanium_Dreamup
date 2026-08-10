# WalkSafe backup·복원·재해복구

> Draft로 작성된 산출물: OPS-10, OPS-11, OPS-12, OPS-13  
> 버전: 0.1.0 · 승인: 미승인  
> 정책 기준선: PB-WALKSAFE-FEATURE-POLICY-1.0.0  
> 실제 실행 증거: 없음 · 출시: NOT_ELIGIBLE

## 이 문서가 답하는 질문

35일 backup을 어떻게 관리하고 실제 복원·재해훈련 전에는 어떤 주장을 금지하는가?

## 쉬운 요약

이 문서는 앞으로 해야 할 일과 판단 기준을 정리한 초안입니다. 절차나 빈 원장을 만들었다고 해서 릴리스·운영·종료가 실행된 것은 아닙니다. 실제 실행·외부 서명·결과는 이름 붙인 대상과 원본 증거가 생긴 뒤 별도 기록합니다.


## 현재 경계

- 현행 정식 제품 경계: Android 사용자 앱 + 별도 비공개 Android 관리자 앱
- Web/PWA: 재승인 전까지 레거시 참고이며 Android 합격·출시 근거가 아님
- 승인된 진행 순서: 개발 → 2026-07-26 통제 시연 → 베타 → 정식 출시. 통제 시연은 릴리스가 아님
- 배포 방향: 관리형 cloud의 staging 우선. 정해진 전체 프로젝트 예산은 없음
- FP-035: 정규화 지시 포착·묶음 승인 대기. 정정 후보 NOT_APPROVED/NOT_EFFECTIVE. 보행 중 미전송, 정지 뒤 이동통신망 명시 선택 시 이동통신망 허용, 미선택 시 Wi-Fi만 허용. 관련 시험 NOT_RUN
- 남은 gate: 5개 모두 NOT_RUN·미면제
- 출시: `NOT_ELIGIBLE`

## 이 묶음에서 아직 만들지 않은 결과

- 이 묶음에는 별도 Planned 결과 유형이 없습니다.

## 공통 보관 원칙

민감한 위치·영상·음성·서명·운영 원본은 Git에 넣지 않습니다. Git에는 통제 저장소 ID, SHA-256, 권한, 보존기간, 실행·검토 상태만 남깁니다. 실제 결과가 생기면 template을 복사한 새 instance를 append-only로 보관하고 이 정본에는 포인터만 연결합니다.

<a id="ops-10"></a>
## OPS-10 백업 정책

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 복구 목표에 맞춰 DB·업로드·설정·모델을 암호화·격리·보존하는 규칙을 정한다. |
| 적용 조건 | 복구해야 할 DB·업로드·설정·모델 자산을 운영하는 경우 활성 |
| 책임 | 작성 운영책임자 · 검토 기술책임자, 보안·개인정보책임자, 지원책임자 · 승인 서비스소유자 |
| 정본 | `docs/deliverables/10-operations/recovery-plan.md#ops-10` |
| 선행 유형 | DES-25, REQ-13 |
| 후행 유형 | OPS-11, OPS-13 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-053<br>결정 DEC-IBQ-092, DEC-IBQ-093, DEC-IBQ-102, DEC-IBQ-107, DEC-IBQ-142, DEC-IBQ-143<br>요구 RQ-FP-053-001<br>gate GATE-CLOUD-COST-MEASUREMENT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `AUTHORABLE_DRAFT_NO_EXECUTION_CLAIM` |
| 실행 책임 | 운영책임자 |
| 활성 시점 | 복구해야 할 DB·업로드·설정·모델 자산을 운영하는 경우 활성 |
| 필수 선행 | - 승인된 릴리스·배포 아키텍처·SLO<br>- 운영 환경·계정·외부 서비스·관측성 현황<br>- 지원·보안·복구 책임과 연락 체계 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 대상·제외·소유자, full·incremental 주기.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 서울 리전 주 원본 300 GiB와 별도 backup 300 GiB를 분리하고 backup은 35일 순환한다. 암호화·권한분리·manifest·삭제 만료를 함께 확인한다.

### 포함 내용과 필요한 입력

**포함 내용**

- 대상·제외·소유자
- full·incremental 주기
- 암호화·접근·격리
- 보존·prune
- 무결성·성공 확인
- 실패 alert·복원 시험

**작성 입력**

- 승인된 릴리스·배포 아키텍처·SLO
- 운영 환경·계정·외부 서비스·관측성 현황
- 지원·보안·복구 책임과 연락 체계

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 대상·제외·소유자, full·incremental 주기.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 운영 환경·SLO·책임·외부 의존성·incident 또는 비용 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="ops-11"></a>
## OPS-11 복원 절차·실제 시험 결과

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT 절차 / 실행 결과 NOT_RUN` |
| 목적 | 승인된 복원 절차를 정본으로 유지하고, 격리 환경의 generated 시험 결과로 backup의 실제 사용 가능성·정합성·복원시간을 검증한다. |
| 적용 조건 | 백업 정책이 활성화되고 격리 복원 환경이 준비된 경우 활성 |
| 책임 | 작성 운영책임자 · 검토 기술책임자, 보안·개인정보책임자, 지원책임자 · 승인 서비스소유자 |
| 정본 | `docs/deliverables/10-operations/recovery-plan.md#ops-11` |
| 선행 유형 | DES-25, OPS-10 |
| 후행 유형 | OPS-13 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-053<br>결정 DEC-IBQ-092, DEC-IBQ-093, DEC-IBQ-102, DEC-IBQ-107, DEC-IBQ-142, DEC-IBQ-143<br>요구 RQ-FP-053-001<br>gate GATE-CLOUD-COST-MEASUREMENT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 운영책임자 |
| 활성 시점 | 백업 정책이 활성화되고 격리 복원 환경이 준비된 경우 활성 |
| 필수 선행 | - 승인된 릴리스·배포 아키텍처·SLO<br>- 운영 환경·계정·외부 서비스·관측성 현황<br>- 지원·보안·복구 책임과 연락 체계 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 시험 backup ID·hash, 격리 환경·권한.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 복원 절차 Draft는 작성하지만 실제 복원시험은 NOT_RUN이다. backup bundle 전체를 격리환경에 복원하고 object 수·bytes·hash·DB 참조·권한을 검증해야 결과가 된다.

### Ready25 내부 작성·실행 차단 계약

| 항목 | 현재 판정 |
|---|---|
| applicability | `IN_SCOPE` |
| content authored | `true` · plan/schema/checklist만 작성 |
| activation | `IN_SCOPE_RESTORE_EXERCISE_NOT_RUN` |
| accepted | `false` |
| approval / actual event / formal evidence / release credit | `0 / 0 / 0 / 0` |
| project/service/product owner | 김민호 / 김민호 / 김민호 |
| independent QA reviewer | `UNASSIGNED` · self-review credit 금지 |
| 실제 receipt | `[]` |

#### 내부 작성 checklist

- 정본 복원 절차와 실행 결과를 분리하고 backup 선택·격리환경·검증 schema를 정한다.
- object count·bytes·hash·DB reference·권한·RTO·RPO checklist를 둔다.
- 실제 backup ID/hash·복원 결과·결함·receipt는 시험 전 생성하지 않는다.

#### 열린 blocker

| blocker ID | 상태 | owner | due |
|---|---|---|---|
| `READY25-OPS-11-REAL-TRIGGER-OR-EVIDENCE-PENDING` | `OPEN` | `SERVICE_OWNER` / 김민호 | `BEFORE_BACKUP_RESTORE_CAPABILITY_APPROVAL` / due_at=`None` |


### 포함 내용과 필요한 입력

**포함 내용**

- 시험 backup ID·hash
- 격리 환경·권한
- 복원 순서·명령
- DB·파일·설정 검증
- 실측 RTO·RPO
- 결함·판정·다음 시험
- 정본 복원 절차 version과 generated 시험 결과·원자료 hash 링크

**작성 입력**

- 승인된 릴리스·배포 아키텍처·SLO
- 운영 환경·계정·외부 서비스·관측성 현황
- 지원·보안·복구 책임과 연락 체계

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 시험 backup ID·hash, 격리 환경·권한.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 운영 환경·SLO·책임·외부 의존성·incident 또는 비용 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="ops-12"></a>
## OPS-12 RTO·RPO

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 서비스·데이터별 허용 중단시간과 허용 손실시점을 사업·안전 영향에 맞게 승인한다. |
| 적용 조건 | 서비스 중단·데이터 손실 허용치를 사업·안전 관점에서 정해야 할 때 활성 |
| 책임 | 작성 운영책임자 · 검토 기술책임자, 보안·개인정보책임자, 지원책임자 · 승인 서비스소유자 |
| 정본 | `docs/deliverables/10-operations/recovery-plan.md#ops-12` |
| 선행 유형 | OPS-04, REQ-13 |
| 후행 유형 | OPS-13 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-053<br>결정 DEC-IBQ-092, DEC-IBQ-093, DEC-IBQ-102, DEC-IBQ-107, DEC-IBQ-142, DEC-IBQ-143<br>요구 RQ-FP-053-001<br>gate GATE-CLOUD-COST-MEASUREMENT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `AUTHORABLE_DRAFT_NO_EXECUTION_CLAIM` |
| 실행 책임 | 운영책임자 |
| 활성 시점 | 서비스 중단·데이터 손실 허용치를 사업·안전 관점에서 정해야 할 때 활성 |
| 필수 선행 | - 승인된 릴리스·배포 아키텍처·SLO<br>- 운영 환경·계정·외부 서비스·관측성 현황<br>- 지원·보안·복구 책임과 연락 체계 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 서비스·데이터 등급, 영향 분석.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- RTO·RPO 수치는 실제 서비스 영향·복원 측정·비용을 근거로 승인하기 전까지 미정이다. 미정 값을 0 또는 무손실로 표현하지 않는다.

### 포함 내용과 필요한 입력

**포함 내용**

- 서비스·데이터 등급
- 영향 분석
- RTO·RPO 목표
- 백업·복구 수단
- 측정·시험 결과
- 불충족 gap·승인

**작성 입력**

- 승인된 릴리스·배포 아키텍처·SLO
- 운영 환경·계정·외부 서비스·관측성 현황
- 지원·보안·복구 책임과 연락 체계

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 서비스·데이터 등급, 영향 분석.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 운영 환경·SLO·책임·외부 의존성·incident 또는 비용 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="ops-13"></a>
## OPS-13 재해복구 계획·훈련 결과

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT 절차 / 실행 결과 NOT_RUN` |
| 목적 | 재해복구 계획을 정본으로 유지하고, generated 또는 독립 훈련 기록으로 대체 환경 전환·복귀 능력을 검증한다. |
| 적용 조건 | 주 환경 상실을 대비한 대체 인프라가 필요한 서비스 수준에서 활성 |
| 책임 | 작성 운영책임자 · 검토 기술책임자, 보안·개인정보책임자, 지원책임자 · 승인 서비스소유자 |
| 정본 | `docs/deliverables/10-operations/recovery-plan.md#ops-13` |
| 선행 유형 | DES-25, OPS-10, OPS-11, OPS-12 |
| 후행 유형 | 없음 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-053<br>결정 DEC-IBQ-092, DEC-IBQ-093, DEC-IBQ-102, DEC-IBQ-107, DEC-IBQ-142, DEC-IBQ-143<br>요구 RQ-FP-053-001<br>gate GATE-CLOUD-COST-MEASUREMENT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 운영책임자 |
| 활성 시점 | 주 환경 상실을 대비한 대체 인프라가 필요한 서비스 수준에서 활성 |
| 필수 선행 | - 승인된 릴리스·배포 아키텍처·SLO<br>- 운영 환경·계정·외부 서비스·관측성 현황<br>- 지원·보안·복구 책임과 연락 체계 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 재해 시나리오·범위, 지휘·연락·권한.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 재해복구 계획은 계정·DB·원본·모델·설정·키를 포함한다. 실제 훈련 결과는 NOT_RUN이며 훈련 없이는 복구 가능을 주장하지 않는다.

### Ready25 내부 작성·실행 차단 계약

| 항목 | 현재 판정 |
|---|---|
| applicability | `IN_SCOPE` |
| content authored | `true` · plan/schema/checklist만 작성 |
| activation | `IN_SCOPE_DR_DRILL_NOT_RUN` |
| accepted | `false` |
| approval / actual event / formal evidence / release credit | `0 / 0 / 0 / 0` |
| project/service/product owner | 김민호 / 김민호 / 김민호 |
| independent QA reviewer | `UNASSIGNED` · self-review credit 금지 |
| 실제 receipt | `[]` |

#### 내부 작성 checklist

- 재해 시나리오·지휘·연락·권한·대체 infra·failover/failback 계획을 정한다.
- 훈련 시간·판정·gap·개선·재훈련 schema를 둔다.
- 실제 drill·전환·복귀·서명 결과는 훈련 전 생성하지 않는다.

#### 열린 blocker

| blocker ID | 상태 | owner | due |
|---|---|---|---|
| `READY25-OPS-13-REAL-TRIGGER-OR-EVIDENCE-PENDING` | `OPEN` | `SERVICE_OWNER` / 김민호 | `BEFORE_DR_CAPABILITY_APPROVAL` / due_at=`None` |


### 포함 내용과 필요한 입력

**포함 내용**

- 재해 시나리오·범위
- 지휘·연락·권한
- 대체 인프라·데이터
- 전환·복귀 절차
- 훈련 시간·결과
- gap·개선·재훈련
- 정본 재해복구 계획 version과 generated/external 훈련 결과·서명 링크

**작성 입력**

- 승인된 릴리스·배포 아키텍처·SLO
- 운영 환경·계정·외부 서비스·관측성 현황
- 지원·보안·복구 책임과 연락 체계

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 재해 시나리오·범위, 지휘·연락·권한.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 운영 환경·SLO·책임·외부 의존성·incident 또는 비용 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.
