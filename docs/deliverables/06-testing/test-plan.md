# WalkSafe 마스터 시험 전략·시험계획·시험데이터 계획

> 포함 산출물: TST-01, TST-02, TST-03, TST-04, TST-05  
> 버전: 0.1.0 · 상태: Draft · 승인: 미승인  
> 정책 기준선: `PB-WALKSAFE-FEATURE-POLICY-1.0.1`  
> 검증: 아직 실행하지 않음 · 출시: NOT_ELIGIBLE

## 이 문서가 답하는 질문

무엇을 어떤 순서와 증거 기준으로 시험해야 정책을 구현했다고 말할 수 있는가?

## 쉬운 요약

이 문서는 확정된 기능 정책을 개발과 시험에 옮기는 첫 초안입니다. 저장소에 코드나 시험 파일이 있다는 사실만으로 기능이 완성됐거나 시험에 합격한 것은 아닙니다. 같은 코드·앱·모델·설정 묶음으로 다시 확인해야 합니다. 출시 전에 반드시 끝내야 할 검증 5개도 남아 있습니다.


## Phase 1 current-source·review binding

| Artifact | Controlled locator·register | Content disposition |
|---|---|---|
| `DLV-TST-02` | `docs/deliverables/06-testing/test-plan.md#tst-02` | 계층, 진입·종료, 중단·재개와 증거 경계가 작성됐으며 지정 검토 대기 |
| `DLV-TST-03` | `docs/deliverables/06-testing/test-plan.md#tst-03`; `docs/deliverables/06-testing/registers/environments.json` | as-of 환경 계약만 준비; formal instance 미프로비저닝 |
| `DLV-TST-05` | `docs/deliverables/06-testing/test-plan.md#tst-05`; `docs/deliverables/06-testing/registers/test-cases.json` | exact 279 case plan 결속; 279개 모두 `NOT_RUN` |

- Policy authority: `PB-WALKSAFE-FEATURE-POLICY-1.0.1` · manifest SHA-256 `b6f5b850a3983b8059b85d93dd07864520219d31fa65a65d740b6bab78231308` · effective decision register SHA-256 `4a448f65280c2cd8cd850a749434f4e124769e47b7b84e31e2e14c58328d2faf`.
- Current source provenance: `DIRTY_WORKTREE_EXACT_CONTENT_SNAPSHOT` · `2026-07-26` · 2,960 stable files · `path_set_sha256=971cee4fbded63faf05e30b0dc7343a9eff41e4612edff307f3df5581c880eff` · `content_set_sha256=f7a05a1abd7b89053dd7d7d052508dfd43b19821174c8de5a82fe754ae90cade` · `source_commit=null`; implementation inventory authority: `docs/deliverables/05-implementation/implementation-manifest-20260727-r002.json` · SHA-256 `f26709242bf7520ec9385feb70f5df859124700657d8fe2dc71c8d1d1df839c2`.
- Reviewer contract: 작성 `QA_OWNER`; 검토 `TECHNICAL_OWNER / SECURITY_AND_PRIVACY_OWNER / DEVICE_QA_OWNER`; 승인 `PROJECT_SCOPE_OWNER`; 현재 `NOT_PERFORMED / NOT_APPROVED`.
- 구조·정적 consistency는 content acceptance에만 사용한다. 기존 내부 PASS, diagnostic receipt, 문서·case 존재를 formal PASS, 실기기 검증, 운영 monitoring 또는 release 완료로 승격하지 않는다.

<a id="tst-01"></a>
## 시험 목적과 범위

정식 시험은 확정된 공통정책 9개와 기능정책 54개마다 정책·요구·합격조건 ID를 먼저 지정했습니다. 요구·설계 문서의 실제 ID 존재와 양방향 연결은 통합 구조검사에서 확인했으며, 이 확인은 실제 시험을 실행했다는 뜻이 아닙니다. Android 사용자 앱, 별도 Android 관리자 앱, 서버·DB·TMAP·모델·원본 저장 흐름이 대상입니다. Web/PWA는 과거 비교용이며 Android 정식 제품의 인수시험을 대신하지 않습니다.

현재 생성된 시험 케이스는 279개이고 모두 `NOT_RUN`입니다.

### 단계별 일정과 승인 경계

| 단계 | 시점 | 실행 주체 | 필수 선행 | 판정 |
|---|---|---|---|---|
| 통제 시연 | 2026-07-26 목표 | 프로젝트 책임자·개발자 | 시연 build·기기·사전 안전 checklist | 기술 시연 결과만 기록. 대상 사용자 안전성·출시 합격을 주장하지 않음 |
| 제한 사용자 시험 | 날짜 미정 | QA와 WS-21에서 승인된 안전요원·참여자 | 개발자 통제시험, 동의, 중단·응급계획, 실기기 기준 | 사전 승인된 scenario별 PASS/FAIL/BLOCKED |
| 정식 베타·출시 | 날짜 미정 | QA·보안·개인정보·안전·제품책임자 | 5개 gate 종결, TST-20·21·22, 복귀·운영 준비 | 같은 release generation에 결속된 근거로만 GO/CONDITIONAL GO/NO-GO |

<a id="tst-02"></a>
## TST-02 시험계획과 계층

1. 파일·설정 검사: 정해진 구조, 비밀값 노출, 사용권리, 코드·모델·설정의 파일 지문 확인
2. 작은 기능 단위 검사: 위험판정, 상태 변화, 권한, 삭제·재시도, 입력 해석
3. API 약속 검사: Android/관리자/서버/TMAP 요청·응답과 DB 변경 순서 확인
4. 통합시험: 로그인→동의→보행→탐지·길안내→전송·삭제·관리자 검수
5. 실제 휴대전화 전체 흐름 검사: 카메라·모델·GPS·센서·음성인식·음성안내·진동·화면읽기
6. 실패·복구 검사: 인터넷 끊김, 권한 철회, 저장공간 부족, 장애 지속, 이전 버전 복귀
7. 성능·호환성·접근성·사용성: 지원 기기별 처리속도·발열·배터리·소음·조작
8. 통제 현장시험과 사용자 인수: WS-21 안전계획과 복구훈련 완료 뒤
9. 다시 확인하는 시험과 출시 준비도: 한 시점의 필수 증거 묶음을 만든 뒤 TST-22 판단

<a id="tst-05"></a>
## TST-05 시험 케이스·절차

각 케이스는 주 검증방법 하나와 필요한 보조방법 여러 개를 함께 기록합니다. 아래 수는 주 검증방법 기준이며 케이스별 전체 방법·시험유형·환경 후보는 JSON 원장에 있습니다. 원장은 policy 1.0.1과 current source snapshot에 결속되며 실행 결과는 별도 append-only instance에만 기록합니다.

| 주 검증방법 | 케이스 수 | 현재 결과 |
|---|---:|---|
| 접근성·사용성 확인 (`ACCESSIBILITY_OR_USABILITY`) | 15 | 아직 실행하지 않음 |
| 실제 기기·통제 현장 확인 (`DEVICE_OR_FIELD`) | 54 | 아직 실행하지 않음 |
| 기술 설계 검토 (`ENGINEERING_REVIEW`) | 1 | 아직 실행하지 않음 |
| 장애·복구 확인 (`FAILURE_OR_RECOVERY`) | 46 | 아직 실행하지 않음 |
| 기능 확인 (`FUNCTIONAL`) | 123 | 아직 실행하지 않음 |
| 독립 검토 (`INDEPENDENT_REVIEW`) | 1 | 아직 실행하지 않음 |
| 구성요소 연결·API 약속 확인 (`INTEGRATION_OR_CONTRACT`) | 32 | 아직 실행하지 않음 |
| 실측 (`MEASUREMENT`) | 2 | 아직 실행하지 않음 |
| 성능·용량 측정 (`PERFORMANCE_OR_CAPACITY`) | 4 | 아직 실행하지 않음 |
| 복구훈련 (`RECOVERY_DRILL`) | 1 | 아직 실행하지 않음 |

## 합격·불합격 규칙

- 합격(PASS)은 사용한 코드 버전, 앱 설치파일, 모델, 설정, DB 변경, 환경·기기, 수행시각, 원자료 파일 지문이 모두 같은 실행으로 연결된 경우만 허용합니다.
- 기대결과 일부만 확인했거나, 단계를 건너뛰었거나, 가짜 입력만 썼거나, 다른 빌드·과거 Web 결과를 썼거나, 파일이 존재하기만 하는 경우는 합격이 아닙니다.
- 필수 단계 실패를 다른 기능의 성공으로 상쇄하지 않습니다.
- 안전·개인정보·접근성·인증·삭제 결함은 영향과 잔여위험을 검토하기 전 닫지 않습니다.
- 실패한 case는 결함 ID를 만들고 수정 build에서 해당 case와 회귀 범위를 다시 실행합니다.

<a id="tst-03"></a>
## TST-03 시험 환경·지원 기기

환경·기기 한 건의 기록은 [registers/environments.json](registers/environments.json)에 관리합니다. 원장은 2026-07-27 exact source snapshot과 policy 1.0.1에 결속했지만 현재 정식 실행 환경은 준비되지 않았고 지원 기기 목록은 초안입니다. 환경 smoke evidence와 승인된 instance ID가 없으면 formal 실행에 사용할 수 없습니다.

<a id="tst-04"></a>
## TST-04 시험데이터 계획

- 인공 시험자료·고정 시험자료·실사용 원본을 분리하고 출처·동의·사용권리·버전·파일 지문을 기록합니다.
- 실제 영상·음성·정확 위치는 저장소와 문서에 직접 넣지 않고 승인된 암호화 저장소의 통제 ID로 참조합니다.
- 원본수집 시험은 승인 정책의 가리지 않은 원본 범위를 사용하되 독립 출시 검토 전 외부 공개·출시 근거로 사용하지 않습니다.
- 모델 평가는 학습·검증·시험 분할과 평가 대상 TFLite 파일 지문(hash, 파일이 바뀌었는지 확인하는 값)을 고정합니다.
- 삭제시험은 운영 데이터가 아닌 격리된 시험 계정·DB·시험 저장경로에서 수행합니다.
- 전맹·저시력 사용자를 같은 우선순위로 포함하는 시각장애인 현장시험은 안전요원·중단조건·동의·사고 대응을 먼저 승인합니다.

## 아직 실행하지 않은 필수 검증

| 검증 ID | 내용 | 방식 | 상태 | 면제 |
|---|---|---|---|---|
| GATE-PHONE-QUEUE-BYTE-LIMIT | 휴대전화 대기자료의 실제 용량 한도 | 실측 | NOT_RUN | 미면제 |
| GATE-SERVER-CAPACITY-STATE-CONTRACT | 서버 용량상태를 휴대전화에 전달하는 규칙 | 개발 설계 검토 | NOT_RUN | 미면제 |
| GATE-RAW-COLLECTION-RELEASE-REVIEW | 무가림 원본 수집의 출시 전 독립 검토 | 독립 검토 | NOT_RUN | 미면제 |
| GATE-CLOUD-COST-MEASUREMENT | 실제 클라우드 저장비 측정 | 실측 | NOT_RUN | 미면제 |
| GATE-SINGLE-ADMIN-RECOVERY-DRILL | 관리자 휴대전화 분실 복구훈련 | 복구훈련 | NOT_RUN | 미면제 |

## 조건부 시험 적용성

| 유형 | 현재 판정 | 이유 |
|---|---|---|
| TST-10 사용자 인수 | ACTIVE_BEFORE_LIMITED_USER_TEST | WS-21 동의·안전계획과 대상 사용자가 준비되면 실행 |
| TST-12 성능·부하 | ACTIVE_BEFORE_BETA | 탐지 지연·발열·배터리·서버 용량·비용 실측 필수 |
| TST-13 호환성 | ACTIVE_BEFORE_BETA | 지원 Android 기기·OS·Depth 범위를 실측으로 확정 |
| TST-15 사용성 | ACTIVE_BEFORE_RELEASE | 전맹·저시력 사용자의 이해·조작·중단 가능성 확인 |
| TST-16 장애·복구 | ACTIVE_BEFORE_BETA | 안전정지·오프라인·저장공간·외부 API·관리자 복구 실행 |
| TST-17 설치·업데이트·이전 버전 복귀 | ACTIVE_BEFORE_BETA | 서명 release generation의 설치·교체·rollback 검증 필수 |

## 진입·종료 기준

시험을 시작하려면 승인된 요구·시험계획, 구분 가능한 빌드, 격리 환경, 필요한 현장 안전계획이 있어야 합니다. TST-22 출시 판단을 시작하려면 TST-20, TST-21, REL-01·02, 적용 대상 SEC-14, WS-20과 남은 검증 5개를 모두 끝내야 합니다. 현재는 이 기준을 충족하지 않아 출시 심사 대상이 아닙니다(`NOT_ELIGIBLE`).

## 이번 버전 변경점

- TST-01~05를 처음 개설하고 정책 63개와 남은 필수 검증 5개에서 시험 케이스를 생성했다.
- 과거 시험 자료와 이번 정식 시험 실행을 분리했다.
