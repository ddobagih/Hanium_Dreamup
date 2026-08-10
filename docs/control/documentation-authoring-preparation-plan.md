# WalkSafe 문서 작성 준비계획

> 상태: Draft · 통제 작성 준비 완료
>
> 범위: DOC-01~CLS-16 전체 257개 산출물의 통제·입력·순서·검토·변경 방법
>
> 기계 판독 기준: artifact-types.json
>
> 승인 입력: 기능 정책 기준선 `PB-WALKSAFE-FEATURE-POLICY-1.0.0`과 승인 기록 `WS-FEATURE-POLICY-BASELINE-APPROVAL-20260721-001`. 이 계획은 승인된 정책을 바꾸지 않으며 기존 후보 문서는 사실 확인용으로만 사용한다.

| 통제 항목 | 현재 값 |
|---|---|
| 문서 ID | ART-DOC-PREP-001 |
| 버전 | 0.3.0 |
| 생명주기 | Draft |
| owner | 프로젝트관리자 역할(실명 미배정) |
| reviewer | 문서통제·기술 검토자 미배정 |
| approver | 사용자/Product Owner 역할; 실제 문서 승인 때 실명·시각 기록 |
| 최종 갱신일 | 2026-07-21 KST |
| 승인 상태 | 정책 입력은 승인·기준선화됨; 이 작성계획과 실제 산출물은 아직 Draft |
| 결정 지문 | `16064cabf95dc16109bfe315032905a12881cab40cf7730e97d8171d15476538` |
| 정책 내용 지문 | `e49ffe0ee9e59de0cec173cac9425d61aa78e0ccd65980ba568045a3975e0b28` |

## 1. 목적과 완료 결과

이 준비계획의 목적은 DOC-01부터 CLS-16까지 257개 산출물 유형을 빠짐없이 다루면서도 257개의 고립된 파일을 양산하지 않는 것이다. 각 유형은 artifact-types.json에 목적, 적용 조건, 필수 내용, 입력, 책임, 의미 의존성, 안정적 catalog 표시 순서, 권장 묶음, 갱신 조건, 완료 기준과 기존 후보 경로를 가진다.

준비 단계의 완료 결과는 다음과 같다.

1. 산출물 유형 257개와 실제 산출물 인스턴스를 구분한다.
2. 기존 자료를 정본으로 오인하지 않고 신뢰등급과 오염 가능성으로 분류한다.
3. 완료된 사용자 답변과 이후 대화 결정을 승인 정책 기준선 및 산출물별 입력에 연결한다.
4. 승인 답변을 기준으로 어떤 문서 묶음을 어떤 순서로 작성할지 고정한다.
5. 요구→설계→코드→시험→릴리스 증거의 추적과 stale 전파 규칙을 정한다.
6. AI가 초안과 검증을 도울 수는 있지만 승인자가 될 수 없도록 한다.
7. 각 유형을 하나의 canonical bundle에만 배치하고 작성·검토·승인 역할의 독립성을 확보한다.

현재 결과는 유형 257개와 문서 묶음 40개를 모두 관리한다. 0~6은 128개 모두 Draft다. 7~12는 작성 가능한 계획·정책·절차·명세·사전개설 원장을 Draft로 만들고, 실제 시험·측정·배포·서명·운영사건·종료 결과와 환경·hash가 고정된 통제 산출물은 Planned/NOT_RUN으로 유지한다. 정확한 상태별 수량은 DOC-01 관리대장을 정본으로 삼는다. 미실행 gate 5개는 모두 `NOT_RUN`·미면제이고 출시는 `NOT_ELIGIBLE`이다. 추가 질문지를 만들지 않으며, 새 결정이 필요한 경우에만 변경요청으로 분리한다. 없는 조사·시험·승인 결과는 작성하지 않는다.

## 2. 식별 체계: 유형과 실제 내용의 분리

### 2.1 세 종류의 ID

| 대상 | 형식 예 | 의미 |
|---|---|---|
| 산출물 유형 | DLV-REQ-01 | “SRD”라는 문서 유형 |
| 실제 산출물 인스턴스 | ART-REQ-SRD-ANDROID-001 | Android 사용자 앱·관리자 앱을 포함한 SRD라는 실제 파일·기준선 |
| 제품 요구사항 | RQ-FP-001-001 | 제품이 충족해야 할 원자적 요구 |

사용자가 제시한 표시 코드 REQ-01은 산출물 유형 이름이다. 저장소에 이미 존재하는 제품 요구 ID REQ-001과 혼동하지 않도록 machine-readable type_code는 반드시 DLV-REQ-01처럼 DLV- 접두어를 사용한다.

같은 원칙으로 다음 ID 공간을 분리한다.

- 결정: DEC-0001
- 아키텍처 결정: ADR-0001
- 변경요청: CR-0001
- 위험·가정·이슈·의존성: RSK-, ASM-, ISS-, DEP-
- 테스트 케이스: TC-0001
- 결함: DEF-0001
- 증거: EVD-0001
- 기준선: CB-, RB-, DB-, BB-, VB-, RLB-, HB-

`artifact-types.json`의 `upstream_types`는 해당 유형을 작성·승인하기 위해 실제로 필요한 최소 의미 선행 유형이고, `downstream_types`는 그 역방향 소비 유형이다. 두 배열은 정확한 역인접 관계를 유지하며 단순 표시 순서나 인접 번호를 의존성으로 기록하지 않는다. `sequence_hint`는 사용자가 제시한 1~257 목록을 그대로 보존하는 안정적 catalog 표시·탐색 순서다. 권장 작성 순서나 위상 순서가 아니며, 더 큰 번호가 더 작은 번호의 의미 선행일 수 있다. 실제 작성·승인 순서, stale 전파와 승인 차단은 6장의 gate, 활성 유형의 의미 의존성, DOC-01·RTM에 등록된 `upstream_instances`·`downstream_instances` 양방향 실제 링크를 함께 기준으로 한다.

### 2.2 한 유형과 한 파일은 일대일이 아니다

하나의 통합 문서 안에 여러 유형이 장으로 들어갈 수 있고, 한 유형이 여러 릴리스 인스턴스를 가질 수도 있다. 예를 들어 MGT-01~04는 프로젝트 헌장의 네 장으로 통합하지만 관리대장에는 네 유형을 각각 어느 파일·heading이 충족하는지 기록한다.

다음 여섯 형태를 구분한다.

- SECTION: 통합 정본 문서의 독립 heading
- REGISTER: 항목이 계속 추가·변경되는 원장
- CANONICAL_DOCUMENT: 승인 가능한 독립 정본 문서
- CONTROLLED_ARTIFACT: source control에서 직접 작성·검토·버전 관리하는 코드·설정·schema·fixture
- GENERATED_EVIDENCE: 코드·빌드·시험으로 재생성되는 결과
- EXTERNAL_RECORD: 사용자·검수자·외부기관 등 독립 주체가 작성·서명·발행한 원본 기록

`recommended_form`은 유형의 주 표현 하나만 나타내며 257개 수량 집계의 기준이다. 정본 보고서가 외부 원자료를 참조하거나 절차가 실행 증거를 낳는 hybrid는 `supporting_forms`에 보조 형태를 기록한다. 보조 형태는 별도 유형 수에 더하지 않고, 정본과 원자료·실행 receipt의 위치·버전·hash를 서로 연결한다.

## 3. 작성 원칙

### 3.1 한 사실에는 한 정본

동일한 범위·threshold·보존기간·지원환경을 여러 문서에 수기로 복제하지 않는다. 값의 정본을 하나 정하고 다른 문서는 ID 또는 링크로 참조한다. OpenAPI, SBOM, 해시, coverage, dashboard는 가능한 한 정본 입력에서 생성한다.

### 3.2 기존 문서는 입력 후보이지 정책 정답이 아니다

기존 문서에는 Web/PWA-primary 전제, 과거 모델 후보, local/mock/static 검증, 특정 RC의 임시 상태가 섞여 있을 수 있다. existing_candidate_paths는 “복사할 정본”이 아니라 검토할 후보 경로다. 사용자 승인 답변과 충돌하면 답변이 우선하며, 충돌은 숨기지 않고 DEC 또는 CR로 남긴다.

### 3.3 사실·정책·계획·증거를 분리한다

- 사실: 현재 코드·설정·실행에서 관찰되는 내용
- 정책: 사용자가 원하는 동작과 경계
- 계획: 앞으로 구현·검증할 순서
- 증거: 특정 commit·build·model·환경에서 실제 수행한 결과

코드가 현재 동작을 증명할 수는 있지만 사용자가 원하는 정책을 대신 결정할 수 없다. 계획 문서는 아직 실행하지 않은 시험을 PASS로 표현할 수 없다.

### 3.4 미결정을 숨기지 않는다

답이 없는 항목은 임의의 기본값으로 확정하지 않는다. 작성 전이면 Planned, 작성 중이면 Draft로 두고 `blocker`를 `OPEN_DECISION` 또는 `BLOCKED`로 기록하며 결정 ID, 결정권자, 목표일, 차단 산출물을 함께 남긴다. 실제 검토 요청이 시작된 경우에만 In Review로 전환한다. N/A도 상태가 아니라 적용성 판정이며 승인된 근거가 필요하다.

### 3.5 통합하되 원장은 분리한다

읽는 흐름이 같은 정적 내용은 한 문서의 장으로 묶는다. RAID, 결정, 변경요청, 결함, 모델 레지스트리처럼 수명과 갱신 주기가 다른 원장은 별도 파일·데이터로 둔다. 자동 생성 결과는 사람이 직접 수정하지 않는다.

### 3.6 증거는 대상 형상에 결속한다

시험·scan·현장 결과에는 최소한 source commit, build, model, config, DB migration, 환경, 기기, 수행 시각과 원자료 hash를 기록한다. 이 결속이 없으면 참고자료일 수는 있어도 현재 후보의 PASS 증거가 아니다.

### 3.7 안전·개인정보는 독립 검토한다

위치·영상·음성·취약 사용자·보행 안전·관리자 권한·외부 공개에 관계된 문서는 작성자·전문검토자·승인자 역할을 분리한다. 실제 검토자가 지정되지 않은 초안은 Draft에 유지하고, 검토가 시작됐지만 필요한 전문검토자를 확보하지 못한 경우에만 In Review와 `blocker=EXTERNAL_REVIEW_PENDING`을 사용한다.

### 3.8 AI 승인 금지

AI 에이전트는 inventory, 초안, 충돌 탐지, 링크 검사, schema 검증, 파생 문서 생성을 수행할 수 있다. AI는 사용자 의도를 추정해 기준선을 만들거나 검토자·승인자 서명을 대신할 수 없다.

### 3.9 작성·검토·승인 독립성

유형 catalog에서는 owner·reviewer·approver 역할을 분리한다. 실제 한 명 개발 프로젝트에서는 존재하지 않는 검토자 이름이나 승인을 만들지 않는다. 작성자는 자동검사와 자기점검을 수행할 수 있지만 그것을 독립 검토로 기록하지 않으며, 사용자/Product Owner가 최종 승인할 때 승인 범위·잔여 위험·시각을 명시한다. 안전·개인정보·보안·법률 전문검토가 필요한데 실제 검토자를 확보하지 못한 문서는 승인하지 않고 blocker를 유지한다.

역할명만 형식적으로 채우는 것으로는 충분하지 않다. 보안·개인정보 요구는 보안·개인정보책임자, 가용성·복구와 관측·외부장애 설계는 운영책임자, 모델 동등성·기기 성능은 기술책임자, 모델 배포는 릴리스·운영책임자, 품질계획은 QA책임자, 예산·자원계획은 재무·자원승인권자를 최소 전문검토자로 둔다. 종료 단계의 데이터·계정·폐기 산출물도 보안·개인정보 검토를 통과해야 한다.

## 4. 자료 신뢰와 오염 통제

### 4.1 source class

| source class | 의미 | 허용 용도 |
|---|---|---|
| CANONICAL | 현재 범위에 대해 사람이 승인한 정본 | 정책·요구·설계의 직접 입력 |
| GENERATED | 정본 입력에서 자동 생성한 결과 | 입력 hash와 generator가 일치할 때 파생 증거 |
| IMPLEMENTATION_EVIDENCE | 현재 코드·설정·계약·실행 결과 | 현재 구현 사실 확인; 목표 정책 확정에는 단독 사용 금지 |
| HISTORY | superseded·과거 계획·daylog·이전 RC | 배경·변경 이유·회고; 현행 값 복사 금지 |
| EXTERNAL | 법규·약관·사용자 조사·기관·검수 원본 | 출처·버전·지역·수집일 범위 안에서 사용 |
| UNCLASSIFIED | 출처·시점·대상이 불명확 | 분류 전 정본 작성에 사용 금지 |

### 4.2 adoption trust

source class와 별도로 실제 채택 신뢰도를 기록한다.

| 등급 | 판정 |
|---|---|
| A | 사용자 또는 권한 있는 승인자가 승인한 current baseline |
| B | A 등급 입력에서 재생성되고 hash·generator 검증을 통과한 generated evidence |
| C | source commit·환경이 정확히 결속된 현재 구현 증거 또는 범위가 확인된 외부 권위 자료 |
| D | 현재 후보이나 사용자 승인·상충 해소가 끝나지 않음 |
| E | history·superseded·stale 또는 다른 플랫폼·모델·릴리스 전제 |
| U | 아직 분류하지 못함 |

외부 자료라고 자동으로 낮은 등급은 아니다. 법령 원문은 해당 관할·시점에서 C가 될 수 있지만, 오래된 블로그나 약관 사본은 D 또는 E일 수 있다.

### 4.3 기존 후보 감사 절차

1. 파일·코드·설정·외부 원본을 inventory에 등록한다.
2. source class, 대상 플랫폼, 모델, 환경, 작성일, source commit을 식별한다.
3. 다음 오염 표식을 검사한다.
   - Web/PWA-primary 또는 superseded 플랫폼 전제
   - 과거 모델·class·threshold·dataset 전제
   - mock·fake·static·local-only 결과
   - 특정 RC에만 해당하는 artifact·hash·환경
   - 계획을 실행 결과처럼 표현한 문장
   - 생성본을 정본처럼 수동 수정한 흔적
   - 사용자 승인 없이 선택된 정책
4. 같은 사실을 주장하는 자료를 묶어 일치·충돌·미확인으로 판정한다.
5. 충돌·미확인 항목을 사용자 질문 ID로 연결한다.
6. 승인 답변 전에는 새 정본에 값을 확정하지 않는다.
7. 승인 후 채택 자료만 canonical instance에 연결하고 나머지는 history 또는 rejected candidate로 남긴다.

## 5. 권장 물리 문서 묶음

257개 유형을 아래 40개 묶음에 배치한다. 범위는 coverage를 뜻하며 한 유형당 파일 하나를 뜻하지 않는다. 기계 판독 정본은 `metadata.bundle_ids`와 각 유형의 단일 `recommended_bundle_id`이고, 아래 표는 그 mapping의 사람이 읽는 요약이다. 중복 coverage는 두 번째 배치가 아니라 정본 참조로 처리한다.

| 묶음 ID | 범주 | 권장 묶음 | 포함 유형 | 작성 방식 |
|---|---|---|---|---|
| BND-DOC-MANUAL | DOC | 문서통제 매뉴얼 | DOC-02~04 | 명명·검토·기준선 규칙을 장으로 통합 |
| BND-DOC-REGISTER | DOC | 통제 원장 | DOC-01, DOC-05 | 유형과 인스턴스 대장, 변경이력은 별도 register |
| BND-MGT-CHARTER | MGT | 프로젝트 헌장 | MGT-01~04 | 필요성·목표·범위가 같은 승인 단위를 구성 |
| BND-MGT-PMP | MGT | PMP | MGT-05~13 | WBS·일정·자원·RACI·품질·형상을 장과 첨부 표로 통합 |
| BND-MGT-CONTROL | MGT | 관리 원장·대시보드 | MGT-14~18 | RAID·결정·CR·Action은 register, dashboard는 원장에서 생성 |
| BND-DSC-DISCOVERY | DSC | Discovery Pack | DSC-01~09 | 문제·사용자·조사·여정·가설·PoC를 근거별 장으로 구성 |
| BND-DSC-PRODUCT | DSC | Product Brief | DSC-10~13 | 비전·KPI·MVP·로드맵을 하나의 제품 승인 단위로 구성 |
| BND-DSC-REGISTER | DSC | 제품 원장 | DSC-14~15 | backlog와 Go/No-Go 결정은 별도 register |
| BND-REQ-BASELINE | REQ | SRD·SRS 기준선 | REQ-01~04, REQ-07~15 | 시스템·소프트웨어·품질·분야별 요구를 장으로 통합 |
| BND-REQ-ACCEPTANCE | REQ | 유스케이스·인수조건 | REQ-05~06 | 목표 흐름과 관찰 가능한 완료 조건 결속 |
| BND-REQ-TRACE | REQ | 추적·기준선·용어 | REQ-16~19 | RTM·변경·용어는 register, 요구 baseline은 manifest |
| BND-DES-ARCH | DES | Architecture Pack | DES-01~08 | 컨텍스트·모듈·시퀀스·배포·ADR·통합 계획 |
| BND-DES-INTERFACE-DATA | DES | Interface & Data Pack | DES-09~13, DES-26 | API·OpenAPI·ERD·사전·생명주기·migration |
| BND-DES-UX-ACCESS | DES | UX & Accessibility Pack | DES-14~18 | IA·흐름·prototype·design system·접근성 |
| BND-DES-SECOPS | DES | Security & Operations Design | DES-19~25, DES-27 | 인증·위협·privacy·오류·관측·성능·복구·외부 장애 |
| BND-DEV-GUIDE | DEV | Developer Guide | DEV-02~06, DEV-08 | README·환경·실행·기여·coding·설정 |
| BND-DEV-CONFIGURATION | DEV | 구현 형상 | DEV-01, DEV-07, DEV-09~14 | source·lock·build·pipeline·IaC·migration·fixture 자체를 등록 |
| BND-DEV-QUALITY | DEV | 품질·공급망·통합 | DEV-15~21 | review·scan·module·SBOM·provenance·SIR |
| BND-TST-PLAN | TST | Test Plan Pack | TST-01~05 | 전략·STP·환경·데이터·case 기준 |
| BND-TST-EVIDENCE | TST | 시험 구현·실행 증거 | TST-06~17 | 자동·수동 시험은 해당 형상별 generated/external record |
| BND-TST-QUALITY | TST | 품질 판정 Pack | TST-18~23 | 결함·지표·STR·잔여위험·준비도·인수 |
| BND-SEC-PLAN | SEC | Security & Privacy Plan | SEC-01~09 | 계획·위협·위험·review·PIA·동의·보존·RBAC·secret |
| BND-SEC-VERIFICATION | SEC | 보안 검증 증거 | SEC-10~14 | SAST·SCA·secret·DAST는 생성, penetration은 외부 기록 |
| BND-SEC-RESPONSE | SEC | 조치·예외·대응 | SEC-15~19 | 취약점·예외 register와 사고·신고·감사 기준 |
| BND-AIML-DATA | AIML | Data Pack | AIML-01~10 | 계획·card·권리·schema·품질·label·split·leakage |
| BND-AIML-MODEL | AIML | Model Pack | AIML-11~16 | 재현 학습·experiment·baseline·registry·card·artifact |
| BND-AIML-EVALUATION | AIML | Evaluation Pack | AIML-17~23 | protocol·class 결과·오류·강건성·동등성·성능·threshold |
| BND-AIML-OPS | AIML | Model Operations | AIML-24~26 | 배포·rollback·drift·재학습 승인 |
| BND-REL-CONTROL | REL | Release Control Pack | REL-01~10 | 계획·canonical checklist·후보별 signed receipt·version·manifest·artifact·무결성·known issue |
| BND-REL-DEPLOYMENT | REL | Deployment Evidence Pack | REL-11~15 | 절차와 후보별 smoke·canary·rollback 결과 |
| BND-REL-DELIVERY | REL | Delivery Pack | REL-16~22 | 설치·사용자·관리자·교육·license 정본과 REL-20 통제 package를 묶고 수령 서명은 external primary record로 연결 |
| BND-OPS-GUIDE | OPS | Operator Guide | OPS-01~09 | 유지보수·소유·지원·SLO·관측·dashboard·alert·runbook·점검 |
| BND-OPS-RECOVERY | OPS | Recovery Pack | OPS-10~13 | backup·restore·RTO/RPO·DR |
| BND-OPS-CONTROL | OPS | 운영 통제 원장 | OPS-14~24 | 권한·secret·patch·incident·변경·backlog·부채·비용·데이터·외부 의존 |
| BND-WS-SAFETY | WS | WalkSafe Safety & Policy Pack | WS-01~05, WS-08, WS-18~19, WS-22 | WS-08 위험물 안전분석을 포함한 시나리오·정책·외부장애·고지의 정본 |
| BND-WS-ACCEPTANCE | WS | WalkSafe Acceptance Matrix | WS-06~07, WS-09~17, WS-20 | GPS·경로·기기·TFLite·음성·접근성·Android 설치·호환·실폰 E2E를 기록하고, 레거시 Web/PWA가 별도 활성화될 때만 해당 PWA 시험을 추가하며 WS-08은 BND-WS-SAFETY 정본을 참조 |
| BND-WS-FIELD-SAFETY | WS | 현장시험 보호 기록 | WS-21 | 참여자 서명 동의서는 EXTERNAL_RECORD primary, 내부 승인 안전계획은 CANONICAL_DOCUMENT supporting form으로 연결 |
| BND-CLS-CLOSURE | CLS | Closure Pack | CLS-01~04, CLS-11~12 | 완료·인수·PCR·KPI·회고·후속계획 |
| BND-CLS-HANDOVER | CLS | 최종 인덱스·이관 원장 | CLS-05~10 | archive·결함·위험·부채·권한 이관 |
| BND-CLS-DECOMMISSION | CLS | 계약·데이터·폐기 Pack | CLS-13~16 | 해당 조건이 생길 때만 작성 |

## 6. 범주별 작성 순서와 gate

아래 단계 번호는 관리·기준선 묶음이지 모든 유형의 일괄 위상 순서가 아니다. 각 instance의 실제 작성·승인 순서는 적용성 판정, 활성 `upstream_types`, 다음 교차 단계 gate로 결정한다. 따라서 catalog에서 뒤에 표시된 WS·SEC·REL·CLS 유형이 앞 번호의 요구·구현·시험·실행 기록보다 먼저 승인되는 것은 정상이다.

| 교차 단계 gate | 선행·후행 규칙 |
|---|---|
| WalkSafe 정책 선행 | WS-01~05·18~19 중 활성 정책을 먼저 기준선화해 REQ·DES·DEV의 입력으로 사용한다. WS-22 안전 제한·고지는 REL-09·10·17보다 먼저 승인한다. |
| 보안 개발 진입 | SEC-01 보안 개발계획과 SEC-04 보안 설계 검토가 DEV-01 구현 진입 전에 승인되어야 한다. |
| 지속 원장 사전 개설 | TST-18은 TST-02 승인 후, SEC-15는 SEC-03 승인 후 빈 구조가 아닌 통제 원장으로 미리 개설한다. TST-06~17의 결함과 SEC-10~14의 finding은 유형 간 선행 링크가 아니라 실행별 instance row·evidence 링크로 적재한다. |
| 현장시험 보호 | WS-21 내부 안전계획·참여자 동의 기준을 먼저 승인해 TST-05 현장 절차에 반영하고, 참여자별 서명 원본을 확보한 instance만 WS-06·07 현장시험을 수행한다. |
| 모델 평가·설명 결속 | AIML-09 split hash와 AIML-16 평가 대상 artifact를 결속해 AIML-17 protocol을 결과 열람 전에 동결한다. AIML-18·20·23 평가·안전·threshold 결과가 승인된 뒤 AIML-15 최종 모델 카드가 이를 집계한다. |
| 출시 준비도 입력 | REL-01 릴리스 계획과 REL-02 canonical gate checklist를 TST-22 판단 전에 승인한다. 후보 ID·artifact hash·증거 snapshot·판정·서명은 별도 signed receipt다. TST-21 snapshot, 활성 SEC-14, TST-20이 TST-22 입력이다. |
| 릴리스 generation 결속 | REL-05 source commit·tag를 고정해 REL-06 artifact, REL-07 hash·서명, REL-08 SBOM·provenance를 생성한 뒤 REL-04 manifest가 같은 release generation을 최종 결속한다. tag와 manifest는 하나의 통제 작업 단위로 추적한다. |
| 침투시험 대상 결속 | SEC-14는 일반 설계가 아니라 정확한 REL-04 manifest·REL-06 artifact·환경을 시험 대상으로 기록한다. finding은 사전 개설된 SEC-15의 instance row로 연결한다. |
| Go 후 배포 | REL-13 production smoke와 REL-14 canary는 TST-22 Go·Conditional Go 승인 이후에만 수행한다. 조건부 승인의 제한·만료·관찰 항목을 각 실행 evidence에 결속한다. |
| rollback 선행 | REL-15 canonical rollback 절차를 TST-17 rollback 시험과 REL-14 canary 전에 승인한다. 시험·실행 receipt는 수행 후 별도 generated evidence로 연결한다. |
| 운영 책임 선행 | OPS-02 서비스 owner를 먼저 배정하고 OPS-01 유지보수 계획을 승인한 뒤 REL-20 인수인계 package가 두 기준선을 소비한다. 수령 서명은 별도 외부 원본이다. |
| 프로젝트 완료 판정 | CLS-04·05·07·08·09·10에서 목표·산출물·결함·위험·부채·운영 이관을 먼저 집계한 뒤 CLS-01이 완료 여부를 판정한다. 최종 외부 수락 CLS-02는 그 이후다. |
| 종료 실행 분기 | 서비스 폐기 branch는 CLS-16을 먼저 승인한다. 운영 이관 branch는 CLS-10·REL-20의 승인된 처분계획을 사용한다. 두 branch 모두 CLS-14 데이터 처리와 CLS-15 계정·키·인프라 조치를 실행 증거에 연결한다. |

### 단계 0 — 통제 기반

대상: DOC-01~05

- 유형 catalog와 실제 instance register의 schema를 확정한다.
- 명명·상태·버전·기준선·승인 규칙을 승인한다.
- 기존 파일 inventory와 신뢰등급 판정을 시작한다.

검증: 유형 257개, 중복 type_code 0건, 후보 경로 오류 0건, 승인 없는 canonical 0건.

### 단계 1 — 승인 입력 확정(완료)

대상: 질문 HTML, source conflict audit, DEC 후보

- 기존 질문 답변과 이후 대화 결정을 기능 정책 기준선 1.0.0으로 결속했다.
- 새 문서 작성은 이 승인 입력을 사용하며 같은 질문을 반복하지 않는다.
- 새 결정이 필요한 경우에만 변경요청과 영향분석을 만든다.

검증: 정책 기준선 승인 기록·결정 지문·정책 내용 지문 일치, 5개 미실행 gate 유지, 출시 `NOT_ELIGIBLE` 유지.

### 단계 2 — 개념 기준선 CB

대상: MGT-01~18, DSC-01~15 중 활성 항목

- 프로젝트 헌장, Product Brief, MVP와 별도 연구 작업선을 확정한다.
- WBS와 마일스톤을 실제 산출물·인수조건 중심으로 구성한다.
- RAID·결정·CR 원장을 연다.

검증: Android 사용자 앱·비공개 Android 관리자 앱 주제품, Web/PWA 레거시 참고 범위, 모델 개선 작업선의 경계가 한 가지로 해석된다.

### 단계 3 — 요구사항 기준선 RB

대상: REQ-01~19, WS 정책 관련 유형

- 사용자 답변을 원자적 요구와 Given-When-Then 인수조건으로 변환한다.
- 활성 WS-01~05·18~19 정책을 먼저 기준선화하고 요구·안전·개인정보·장애 대응의 출처로 연결한다.
- 기능·품질·안전·개인정보·접근성·호환성의 수치와 예외를 확정한다.
- RTM과 glossary를 만든다.

검증: P0 요구마다 출처·우선순위·인수조건·검증법이 있고 미결정 값이 숨어 있지 않다.

### 단계 4 — 설계 기준선 DB

대상: DES-01~27, SEC-01~09, WS-01~05·08·18·19·22

- 핵심 세 사용자 흐름을 먼저 설계한다.
  1. 카메라→탐지→위험 안내
  2. 음성→목적지→TMAP 경로 안내
  3. 신고→검증→PostGIS→관리자·CSV
- API·DB·권한·오류·fallback·데이터 생명주기를 같은 흐름에 결속한다.
- SEC-01·04 승인과 활성 WS 정책의 설계·구현 입력 연결이 확인되기 전에는 DEV-01 구현 gate를 열지 않는다.

검증: P0 요구가 설계 요소에 연결되고, 각 외부 장애와 권한 거부의 사용자 동작이 명확하다.

### 단계 5 — 빌드 기준선 BB

대상: DEV-01~21, 현재 모델 계약에 필요한 AIML-14~16·21·23~24

- 기존 구현을 승인 설계와 비교해 재사용·수정·폐기 후보로 판정한다.
- 현재 후보 모델은 AIML-16에서 ID·class·입출력·artifact·hash를 먼저 고정한다. AIML-15는 초안으로 둘 수 있으나, AIML-17 사전 protocol과 AIML-18·20·23 결과를 반영하기 전에는 최종 승인하지 않는다.
- 모델 재학습 문서선은 activation condition이 충족될 때까지 별도 유지한다.

검증: source·lock·config·migration·model을 한 manifest로 재현하고 P0 RTM 구현 연결 누락이 없다.

### 단계 6 — 검증 기준선 VB

대상: TST-01~23, WS-06~20 중 활성 항목

- 정적 policy test와 실제 사용자 경로 E2E를 구분한다.
- TST-18 결함 원장은 TST-02 승인 직후 미리 개설하고, 각 시험에서 발견한 결함은 시험 instance·evidence와 row 단위로 연결한다.
- TST-21 snapshot을 먼저 동결한 뒤 TST-20 STR과 TST-22 출시 준비도 판단에 사용한다. REL-01·02도 TST-22 전에 승인되어야 한다.
- REL-15 rollback 절차가 승인된 뒤 TST-17을 수행하고, 실행 receipt는 절차 정본과 분리해 보존한다.
- WS-21 안전계획을 TST-05 현장 절차보다 먼저 승인하고, 실제 참여자 동의 원본이 연결된 뒤 WS-06·07을 수행한다.
- SEC-14가 활성화되면 REL-04 manifest·REL-06 artifact·환경을 정확한 시험 대상으로 동결한다.
- WS-20 실제 휴대폰 E2E가 활성화되면 그 결과를 TST-20 STR에 포함해야 하며, 미수행·실패 상태에서는 TST-22 Go로 진행할 수 없다. 범위 제외 시에는 승인된 activation/N/A 근거를 남긴다.
- 로컬 구성요소·Android 정지 실기기·통제된 실외 현장 순으로 안전 gate를 통과한다. Web/PWA 시험은 WS-16이 별도 재활성화된 경우에만 추가한다.
- skip·mock·부분 성공을 PASS로 합산하지 않는다.

검증: 명명된 build에서 세 핵심 흐름이 요구된 환경·기기 증거와 함께 판정된다.

### 단계 7 — 릴리스·운영 기준선 RLB

대상: REL-01~22와 OPS-01~24 중 실제 환경에 활성화된 항목

- 안정적인 공유 환경과 운영 책임이 정해진 뒤 릴리스 증거를 생성한다.
- REL-01·02는 TST-22의 선행 gate로 먼저 승인한다. REL-05 tag→REL-06 artifact→REL-07·08 무결성·공급망 증거 순으로 생성하고 REL-04 manifest가 동일 release generation을 최종 결속한다.
- 활성 SEC-14 결과까지 포함해 TST-22가 Go·Conditional Go를 승인한 뒤에만 REL-13·14 배포 evidence를 생성한다.
- REL-15 rollback 절차를 REL-14 canary 전에 승인하며, WS-22 기준선은 릴리스 노트·known issue·사용자 설명서보다 먼저 확정한다.
- OPS-02 owner·지원 책임과 OPS-01 유지보수 계획을 먼저 확정하고 REL-20 인수인계 package와 외부 수령 서명에 연결한다.
- 모든 commit이 아니라 명명된 RC·beta·release 후보만 SBOM·provenance·승인 대상으로 삼는다.
- 배포·migration·smoke·rollback·지원 경로를 함께 승인한다.

검증: 출시 준비도 gate가 통과되고 artifact·환경·운영 owner·known issue가 한 release manifest에 결속된다.

### 단계 8 — 종료·이관 기준선 HB

대상: CLS-01~16 중 activation condition이 충족된 항목

- 실제 종료·이관 시점에만 작성한다.
- CLS-04·05와 CLS-07·08·09·10 각각을 활성 산출물 또는 승인된 N/A 판정으로 해소한 뒤 CLS-01 완료 gate가 이를 소비한다. CLS-02 최종 인수는 CLS-01 이후에만 진행한다.
- 서비스 폐기면 CLS-16을 먼저 승인하고, 운영 이관이면 CLS-10·REL-20의 처분계획을 먼저 승인한다. 선택된 branch 아래에서 CLS-14 데이터 처리와 CLS-15 계정·키·인프라 조치를 실행하고 receipt를 남긴다.
- 빈 종료 문서를 사전에 생성하지 않는다.

검증: 인수·archive·권한·데이터·계약·미해결 업무의 소유자가 모두 확인된다.

## 7. 개별 산출물 작성 절차

각 artifact type은 다음 절차를 거친다.

1. 적용성 판정
   - REQUIRED, CONDITIONAL, N/A 중 판정한다.
   - CONDITIONAL은 artifact-types.json의 activation_condition을 평가한다.
   - N/A는 이유·판정자·재검토 trigger를 기록한다.
2. instance 생성
   - instance ID, canonical 위치, owner, reviewer, approver, 예정 기준선을 등록한다.
3. 입력 동결
   - required_inputs와 활성화된 의미 upstream_types의 승인본·버전·hash를 기록한다. sequence_hint는 앞·뒤 번호 모두 작성·입력·승인 조건이 아닌 안정적 표시값이다.
   - 미결정·충돌이 있으면 작성 전에 질문 또는 CR로 차단한다.
4. 후보 자료 감사
   - existing_candidate_paths를 source class와 adoption trust로 판정한다.
   - 채택할 사실·거부할 가정·추가 확인할 값을 분리한다.
5. 초안 작성
   - required_contents를 heading 또는 register field로 모두 다룬다.
   - 주장마다 근거·범위·시점·불확실성을 붙인다.
6. 자동 검증
   - schema, ID, 경로, 링크, 중복, trace, hash, stale, 금지 secret을 검사한다.
7. 전문 검토
   - reviewer_roles가 정확성·일관성·안전·보안·시험 가능성을 검토한다.
   - 의견과 반영 commit을 기록한다.
8. 승인
   - approver_role이 범위와 잔여 미결정을 확인한 뒤 명시적으로 승인한다.
   - AI는 승인자가 될 수 없다.
9. 기준선
   - 승인된 instance와 입력·hash를 baseline manifest에 결속한다.
   - 직접 덮어쓰지 않는다.
10. 변경·폐기
   - update_triggers 발생 시 stale 표시와 CR 영향분석을 수행한다.
   - 새 version 승인 후 이전 것은 Superseded, 보존기간 종료 후 Archived로 전환한다.

## 8. 상태·신선도·검증의 분리

하나의 current_status로 모든 의미를 합치지 않는다.

### 생명주기

Planned → Draft → In Review → Approved → Baselined → Superseded → Archived

- In Review에서 변경 요구가 나오면 Draft로 되돌린다.
- Approved는 내용 승인이고 Baselined는 특정 입력·버전 묶음의 동결이다.
- 기준선 문서는 직접 수정하지 않는다.

### 신선도

- Current
- Review Due
- Stale
- Invalid

승인 문서라도 상위 요구·코드·외부 약관이 바뀌면 Baselined + Stale이 될 수 있다.

### 검증

- Not Run
- Partial
- Pass
- Fail
- Waived

Waived는 Pass가 아니다. waiver에는 위험, 보상통제, 승인자, 만료일이 필요하다.

## 9. 검토·승인 책임

| 활동 | 기본 책임 |
|---|---|
| 제품 목표·범위·MVP·출시 결정 | 사용자/Product Owner |
| 산출물·일정·CR·RAID·기준선 통제 | PM·문서통제담당 |
| 요구사항 품질과 RTM | 요구사항책임자 + Product Owner |
| 예산·자원계획 | 재무·자원승인권자 + 프로젝트책임자 |
| 아키텍처·API·DB·구현 형상 | 기술책임자 |
| 시험 계획·결함·준비도 | QA책임자 |
| 보안·개인정보·권한·사고 | 보안·개인정보책임자 |
| 법률·라이선스·동의·면책 | 법무·라이선스검토자(필요 시 외부) |
| 데이터·모델·평가·승격 | 데이터·ML책임자 + Product Owner |
| 배포·복구·운영·지원 | 릴리스·운영책임자 |
| 보행 안전·접근성·현장시험 | 접근성·안전책임자 |
| 초안·inventory·자동검사 | AI 에이전트 가능 |
| 최종 승인 | AI 불가; 지정된 사람만 가능 |

한 사람이 여러 역할을 겸임할 수 있으나 다음은 작성자와 승인자를 분리한다.

- 프로젝트 범위와 출시 승인
- 보행 안전 주장과 현장시험
- 개인정보 영향·보존·삭제
- 관리자 권한과 보안 예외
- 모델 승격과 배포 동등성
- 최종 인수·운영 이관

시험·인수 산출물은 특히 다음 승인 경계를 적용한다.

- TST-10 사용자 인수 테스트: QA가 조정하되 제품책임자와 사용자대표가 승인한다.
- TST-20 STR: QA가 작성하고 제품책임자가 결과·잔여위험을 승인한다.
- TST-22 출시 준비도: QA가 판정 근거를 작성하고 제품책임자가 출시 여부를 승인한다.
- TST-23 인수 확인서: 요구·인수조건에 대한 제품·사용자 기능 인수 단계다. QA가 증거를 준비하고 지정 인수자가 해당 제품 동작을 승인한다.
- REL-21 검수·승인 기록: 특정 release와 인도 산출물이 manifest·계약된 검수 항목에 맞는지 지정 검수자가 확인하는 릴리스 검수 단계다. TST-23을 대신하지 않는다.
- CLS-02 최종 인수·승인서: REL-21 이후 프로젝트·계약 범위 전체의 완료, 잔여 의무와 운영 이관을 지정 인수자가 최종 수락하는 종료 단계다. 제품 기능 또는 단일 release 승인과 동일시하지 않는다.

REL-20과 WS-21처럼 내부 canonical package·안전계획과 외부 수령·동의 원본이 결합되는 유형은 두 승인을 합치지 않는다. 내부 정본에는 `canonical_approver`를, 외부 원본에는 실제 `external_signer_or_issuer`와 서명·발행 시각·원본 hash를 instance register에 별도로 기록한다. 외부 서명자는 내부 문서 approver를 자동으로 대체하지 않는다.

검토를 아직 요청하지 않았다면 Draft에 둔다. 검토가 시작된 뒤 필요한 독립 검토자가 없는 경우에만 In Review와 `blocker=EXTERNAL_REVIEW_PENDING`을 기록한다.
법률·라이선스·규제·동의·면책 판단도 법무·라이선스검토자를 확보하지 못하면 승인하지 않으며 개발자나 AI의 해석만으로 승인하지 않는다.

## 10. 실제 산출물 관리대장 필드

DOC-01 실제 instance register에는 최소한 다음 필드를 둔다.

| 필드군 | 필드 |
|---|---|
| 식별 | artifact_type_code, artifact_instance_id, title, category, artifact_form, bundle_id |
| 적용성 | applicability, activation_result, N/A_reason |
| 책임 | author, content_owner, reviewers, canonical_approver, external_signer_or_issuer |
| 상태 | lifecycle_status, freshness_status, verification_status, blocker |
| 버전 | document_version, baseline_id, source_commit, build/model/config version |
| 위치 | canonical_path, coverage_anchor/section_id, generated_paths, external_source |
| 추적 | upstream_instances, downstream_instances, requirement_ids, test_ids, finding_or_defect_row_ids, evidence_ids, risk_ids, CR_ids |
| 승인 입력 | policy baseline ID, decision IDs, feature policy IDs, gate IDs, open issue refs |
| 무결성 | SHA-256, generator, generator_version, last_verified_at |
| 일자 | created_at, updated_at, approved_at, external_signed_or_issued_at, next_review_at, expires_at |
| 변경 | previous_instance, supersedes, waiver_id |
| 관리계약 | review_profile_id, review_cadence, next_review_rule, change_profile_id, change_supersede_retire_method, retention_profile_id |
| 통제 | source_class, adoption_trust, external_record_hash, external_signature_status, confidentiality, personal_data, retention_class, raw_evidence_storage_rule, notes |

사용자가 제시한 ID, 산출물명, 필수/조건부, 담당자, 검토자, 현재 상태, 버전, 위치, 연결 요구사항, 승인일, 다음 검토일, 비고는 모두 포함된다. 여기에 승인자 분리, 세 가지 상태 축, 기준선·무결성·변경·신뢰 정보를 추가한다.

검토·변경 규칙은 257개 행에 다음 profile을 명시적으로 연결한다.

- 설명형 정본·SECTION: 변경요청과 영향분석 후 새 버전을 승인하고 이전판은 Superseded 후 Archived한다.
- REGISTER: 기존 이력을 삭제하지 않고 행과 상태변경을 추가하며 정기 snapshot을 남긴다.
- CONTROLLED_ARTIFACT: commit·tag·lock·hash로 대체 이력을 결속한다.
- GENERATED_EVIDENCE: 손으로 수정하지 않고 동일 입력을 결속해 새 실행 instance로 재생성한다.
- EXTERNAL_RECORD: 서명·발행 원본을 수정하지 않고 정정본을 새 원본으로 연결한다.
- 위치·영상·음성·서명이 포함될 수 있는 원자료는 Git에 저장하지 않고 암호화 통제 저장소의 ID·hash·권한·보존정책만 연결한다.

## 11. 변경과 stale 전파

변경 흐름은 다음으로 고정한다.

~~~text
변경요청
→ 영향분석
→ 승인/반려
→ 사용자 결정·문서·코드 수정
→ 검증
→ 새 version
→ 새 기준선 승인
→ 이전 기준선 Superseded
→ CR 종료
~~~

영향분석은 최소한 다음 링크를 따라간다.

- 제품 비전·MVP·범위
- 요구사항·인수조건
- 설계·API·DB·데이터 흐름
- 코드·dependency·config·model
- 시험 case·환경·증거
- 보안·개인정보·접근성·안전
- deployment·migration·rollback
- 운영·지원·사용자 안내
- 제출·인수·archive

상위 instance가 변경되면 DOC-01·RTM의 실제 downstream instance 링크를 따라 Stale 후보로 표시한다. 유형 catalog의 `downstream_types`는 `upstream_types`의 정확한 역인접 의미 링크로서 영향 탐색의 시작점만 제공하며, `sequence_hint`나 단순 번호 인접성은 stale·승인 차단 근거가 아니다. 실제 instance 링크를 대체하지 않으며, 검토자가 영향 없음 근거를 승인하거나 새 version을 작성하기 전에는 Current로 되돌리지 않는다.

## 12. 자동 검증 계획

artifact-types.json과 실제 instance register에 대해 다음을 CI 또는 로컬 validator로 검사한다.

- metadata.expected_count와 실제 유형 수
- 범주별 expected_count
- display_code·type_code 중복과 DLV- 접두어
- 필수 field와 허용 enum
- upstream_types·downstream_types 대상 존재, 정확한 역방향 대칭, self-loop·cycle 없음
- 인접 번호나 표시 순서만을 근거로 한 dependency edge 없음
- sequence_hint 1~257 유일·연속성과 사용자 원목록 기준의 안정적 표시 의미; 위상·권장 작성 순서로 사용하는 검사 없음
- TST-18·SEC-15 사전 개설 원장에 실행 산출물을 유형 선행조건으로 연결하지 않고 finding·defect를 instance row·evidence로 연결했는지
- SEC-01·04→DEV-01, WS 정책→REQ·DES·DEV, WS-21→TST-05·WS-06·07, WS-20→TST-20, WS-22→릴리스 고지의 교차 단계 gate 유지
- AIML-09·16→AIML-17→AIML-18·20·23→AIML-15, REL-05→06→07·08→04, SEC-14→TST-22→REL-13·14, OPS-02→OPS-01→REL-20, CLS-04·05·07~10→CLS-01 순서 유지
- REL-01·02→TST-22, TST-21→TST-20·22, REL-15→TST-17·REL-14 유지. CLS-14·15는 폐기 branch의 CLS-16 또는 운영이관 branch의 CLS-10·REL-20 중 승인된 경로에 연결
- metadata.bundle_ids 40개와 recommended_bundle_id 257개의 허용값·단일 coverage, 표의 BND ID 집합 일치
- owner와 canonical approver 역할 분리, reviewer_roles의 owner self-review 없음, 유형별 필수 전문검토자 누락 없음
- REL-20·WS-21 등 hybrid의 canonical approver와 external signer·issuer 필드 분리
- supporting_forms 허용값·중복·primary form 자기참조 없음
- existing_candidate_paths 실제 존재 여부
- canonical 경로 중복
- 허용되지 않은 상태 전이
- CR 없는 기준선 내용 변경
- 연결된 상위 instance 변경 후 stale 누락
- P0 요구의 설계·코드·시험·증거 고아 링크
- generated evidence의 input·generator·hash 누락
- reviewer·approver·승인일 누락
- N/A 근거와 conditional activation 판정 누락
- waiver 만료와 Pass 오표기
- source commit·build·model·config·migration 불일치
- 비밀값·실제 개인정보·원본 영상의 잘못된 포함
- Markdown·HTML·diagram의 끊어진 상대 링크

## 13. 257개 coverage

### 범주별 수량

| 순서 | 범주 | 수량 | 누계 |
|---:|---|---:|---:|
| 0 | DOC | 5 | 5 |
| 1 | MGT | 18 | 23 |
| 2 | DSC | 15 | 38 |
| 3 | REQ | 19 | 57 |
| 4 | DES | 27 | 84 |
| 5 | DEV | 21 | 105 |
| 6 | TST | 23 | 128 |
| 7 | SEC | 19 | 147 |
| 8 | AIML | 26 | 173 |
| 9 | REL | 22 | 195 |
| 10 | OPS | 24 | 219 |
| 11 | WS | 22 | 241 |
| 12 | CLS | 16 | 257 |

### 권장 형태별 수량

| 형태 | 수량 | 관리 의미 |
|---|---:|---|
| CANONICAL_DOCUMENT | 120 | 독립 승인 가능한 정본 |
| SECTION | 30 | 통합 정본 문서 안의 독립 장 |
| REGISTER | 42 | 행 단위로 계속 변경되는 원장 |
| CONTROLLED_ARTIFACT | 14 | source control에서 직접 작성·검토하는 코드·설정·fixture·dashboard definition |
| GENERATED_EVIDENCE | 42 | 입력·generator·hash로 재생성하는 실행 결과 |
| EXTERNAL_RECORD | 9 | 사용자·검수자·외부기관 등 독립 주체가 작성·서명·발행한 원본 |
| 합계 | 257 | 유형 누락 없음 |

이 수량은 `recommended_form`만 한 번 집계한 값이다. DSC-04의 외부 조사 원자료, REL-02의 후보별 signed receipt, REL-15의 rollback 실행 receipt, REL-20의 통제 인수인계 package, OPS-06의 live snapshot, OPS-11의 복원 시험 결과, OPS-13의 훈련 기록, WS-21의 내부 승인 안전계획처럼 `supporting_forms`에 등록한 보조 형태는 중복 합산하지 않는다.

### 현재 우선순위별 수량

| 우선순위 | 수량 | 의미 |
|---|---:|---|
| P0 | 119 | Android 제품·정책·핵심 E2E 기준선의 첫 작성 묶음에서 다룰 coverage 항목 |
| P1 | 105 | P0 기준선 이후 품질·릴리스·운영 성숙도에 따라 작성 |
| P2 | 10 | 운영 데이터·성숙도가 있어야 의미 있는 심화 항목 |
| CONDITIONAL | 23 | 외부시험·IaC·Android TFLite·종료 등 조건이 생길 때만 활성 |
| 합계 | 257 | 우선순위는 파일 수가 아니라 유형 coverage다 |

P0가 119개라는 뜻은 119개 파일을 즉시 작성한다는 뜻이 아니다. 프로젝트 헌장, Product Brief, SRS, Architecture Pack, Developer Guide, Test Plan, Security & Privacy Pack, WalkSafe Safety Pack 등 약 10~12개 첫 작성 묶음의 heading·register row·generated evidence가 이 유형들을 충족한다는 뜻이다.

### 적용성별 수량

| 적용성 | 수량 |
|---|---:|
| REQUIRED | 148 |
| CONDITIONAL | 109 |
| 합계 | 257 |

CONDITIONAL은 생략이 아니다. activation_condition을 평가해 활성·비활성·N/A 근거를 관리대장에 남겨야 한다.

## 14. 준비 단계 종료 기준

실제 문서 작성을 시작하기 위한 준비 완료 기준과 현재 판정은 다음과 같다.

- artifact-types.json이 JSON parsing과 257개·범주별 수량·중복 검사를 통과한다.
- 모든 유형에 목적·필수 내용·입력·독립 역할·의미 의존성·표시 전용 sequence_hint·단일 recommended_bundle_id·갱신·완료 기준이 있다.
- 의미 dependency graph가 역방향 대칭과 cycle 0건을 만족하고 번호 인접 chain이나 sequence_hint를 작성·승인 blocker로 사용하지 않는다.
- 6장의 교차 단계 gate와 사전 개설 원장·instance row 연결·세 인수 단계·외부 서명 분리 규칙이 catalog 및 관리대장 schema와 일치한다.
- 40개 canonical bundle이 257개 유형을 중복·누락 없이 한 번씩 포함하며 hybrid 보조 형태는 supporting_forms로 분리된다.
- existing_candidate_paths가 실제 존재하며 정본이 아닌 후보라는 표시가 있다.
- 질문 HTML의 필수 답변과 충돌 검사가 완료된다.
- 승인 답변에서 CB 범위·MVP·플랫폼·모델 작업선이 한 가지로 해석된다.
- 실제 instance register에 257개가 한 번씩 등록되고 0~6의 128개 Draft와 7~12의 계획·정책 Draft/실행 증거 Planned 경계가 구분된다.
- 각 행에 예정 경로·검토주기·변경/대체/폐기 profile·보존 profile·승인 입력 trace가 있다.
- 첫 작성 묶음은 역할 수준 owner·reviewer·approver를 가지며, 실제 사람은 검토·승인 전 배정한다. 없는 사람이나 승인을 만들어내지 않는다.

현재 위 기준에 따라 7~12의 작성 가능한 정식 초안과 실행 전 계약을 만들고 DOC-01·DOC-05·통합 추적 보고서를 현행화한다. 작성 순서는 승인된 정책 기준선과 0~6 Draft를 입력으로 SEC·WS 안전/개인정보 계획, AIML 데이터·모델 계획, REL·OPS 계획, CLS 조건부 계획 순이다. 실행 증거는 조건이 실제로 충족된 뒤 별도 instance로 생성한다. 기능 구현이나 기존 문서 대량 복사는 DEV-01 진입 gate를 포함한 P0 미결정을 해소한 뒤 시작한다.
