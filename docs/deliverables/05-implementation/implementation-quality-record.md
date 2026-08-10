# WalkSafe 구현 품질·공급망·통합 기록

> 포함 산출물: DEV-15, DEV-16, DEV-17, DEV-18, DEV-19, DEV-20, DEV-21  
> 버전: 0.1.0 · 상태: Draft · 승인: 미승인  
> 정책 기준선: WalkSafe 기능 정책 1.0.0  
> 검증: 아직 실행하지 않음 · 출시: NOT_ELIGIBLE

## 이 문서가 답하는 질문

현재 구현 후보를 누가 검토했고, 정적분석·모듈·라이선스·소프트웨어 구성품 목록(SBOM)·빌드 생성 이력(provenance)·통합 결과가 준비됐는가?

## 쉬운 요약

이 문서는 확정된 기능 정책을 개발과 시험에 옮기는 첫 초안입니다. 저장소에 코드나 시험 파일이 있다는 사실만으로 기능이 완성됐거나 시험에 합격한 것은 아닙니다. 같은 코드·앱·모델·설정 묶음으로 다시 확인해야 합니다. 출시 전에 반드시 끝내야 할 검증 5개도 남아 있습니다.


<a id="dev-15"></a>
## DEV-15 코드리뷰 기록

적용은 `ACTIVE_DRAFT_SINGLE_DEVELOPER_CONTROL`로 정합니다. 정식 변경 검토가 생기면 검토 ID, 코드 버전·diff, 정책·요구·시험 추적, 자동검사, secret 확인, rollback, 지적·조치·재검토 결과를 `05-implementation/evidence/<evidence-id>.json`에 덮어쓰지 않는 새 파일로 추가합니다. 1인 개발일 때는 없는 다른 사람을 적지 않고 작성·재검토 시점을 나누어 기록합니다. 독립 전문검토가 명시된 5개 gate는 이 자체검토로 대체하지 않습니다. [quality-evidence-register.json](quality-evidence-register.json)은 빈 구조와 현재 집계만 담으며 현재 정식 검토 기록은 0건입니다.

### Phase 1 IN_SCOPE as-of 기준선

범위는 `IN_SCOPE`입니다. 현행 `quality-evidence-register-20260727-r002.json`의 12개 행은 lint·unit·typecheck·DB-free·disposable PostGIS·compileall·SBOM schema·source presence·formal-NOT_RUN 증거이며 코드리뷰 사건이 아닙니다. add-only `quality-evidence-register-20260728-r003.json`은 `2026-07-28` 기준 `NO_EVENTS_TO_DATE` owner attestation을 별도 기록하고 12개 기술행을 검토행으로 세지 않습니다.

review event schema는 `review_id`, event_time, actor/role, source snapshot·commit/diff hash, change summary, policy/requirement/test trace, quality/security/performance findings, action/status, re-review ref와 raw evidence locator를 요구합니다. 현재 source commit은 `null`, PR·commit·diff·독립 reviewer event는 없고 completion event를 주장하지 않습니다.

김민호의 범위 owner·현재 0건 진술은 `USER_SELF_ASSERTED_OWNER_ATTESTATION`으로 기록합니다. 이것과 기술책임자의 정식 승인행위는 분리하며 승인은 `NOT_PERFORMED`입니다. 개발·기술 역할 개인 배정은 확인되지 않았고 QA 검토자는 `UNASSIGNED`입니다. 실제 review가 생기면 기존 행을 고치지 않고 append-only event를 추가합니다.

정책 gate `GATE-PHONE-QUEUE-BYTE-LIMIT`, `GATE-SERVER-CAPACITY-STATE-CONTRACT`, `GATE-RAW-COLLECTION-RELEASE-REVIEW`, `GATE-CLOUD-COST-MEASUREMENT`, `GATE-SINGLE-ADMIN-RECOVERY-DRILL`는 모두 `NOT_RUN`·미면제입니다. self-review나 zero-event attestation은 독립 전문검토 5개 gate, formal PASS 또는 release approval을 대체하지 않습니다.

<a id="dev-16"></a>
## DEV-16 lint·정적분석 결과

정식 기준선 빌드에 대한 결과는 아직 실행하지 않았습니다(`NOT_RUN`). 문서 파일을 뺀 기존 시험 코드 후보는 130개이며 범위는 ANDROID_USER_PRODUCT 52개, LEGACY_WEB_REFERENCE 24개, PRODUCT_OR_TOOLING_REVIEW_REQUIRED 29개, SERVER_PRODUCT 19개, SUBMISSION_TOOLING 6개입니다. 과거 Web과 제출·검증 도구를 Android 제품 시험으로 계산하지 않으며, 어떤 후보도 실행 결과나 합격 증거가 아닙니다.

<a id="dev-17"></a>
## DEV-17 라이선스·고지

[licenses-and-notices.md](licenses-and-notices.md)에 검토 범위와 완료조건을 적었습니다. 한 번에 함께 출시할 최종 버전 묶음(release generation)의 의존성·모델·데이터·SDK 권리 검토는 `PENDING_REVIEW`입니다.

<a id="dev-18"></a>
## DEV-18 프로그램·모듈 목록

[module-register.json](module-register.json)에 Android 사용자·관리자, 서버, 모델, 음성, 과거 Web, 배포, 개발도구 경계를 나눴습니다. FP-035는 일반 활동원본을 보행 중 전송하지 않고, 정지 뒤에는 이동통신망을 명시 선택한 사용자만 이동통신망을 허용하며 미선택 사용자는 Wi-Fi만 허용하는 것으로 정규화 지시를 포착했습니다. 근거 정정 후보는 `NOT_APPROVED/NOT_EFFECTIVE`이며 새 산출물 묶음 승인 전에는 Android 사용자 앱과 백엔드의 관련 구현 확정·정식 시험을 대기시킵니다. 별도 관리자 앱이 없고 중복·미배정 경계가 남아 있어 모듈 목록은 완료로 표시하지 않습니다.

<a id="dev-19"></a>
## DEV-19 소프트웨어 구성품 목록(SBOM)

정식 Android·서버 빌드에 대한 소프트웨어 구성품 목록(SBOM)은 아직 생성하지 않았습니다. 생성할 때 구성품 버전, 사용권리, 공급자, 파일 지문(hash), 의존 관계와 그 시점의 취약점 상태를 출시 ID에 연결합니다.

<a id="dev-20"></a>
## DEV-20 빌드 생성 이력(provenance)·산출물 파일 지문(hash)

정식 빌드가 어떤 코드와 도구로 만들어졌는지 보여주는 생성 이력(provenance)은 아직 없습니다. 코드 버전, 빌드 도구, 의존성 버전 고정 파일(lock 파일), 모델, 설정, DB 구조 변경(migration), 결과 파일 지문(hash)과 서명 상태를 기록해야 합니다.

<a id="dev-21"></a>
## DEV-21 SIR

[software-integration-report.md](software-integration-report.md)는 현재 통합 실행 0건과 필요한 결속을 명시합니다. 코드가 같은 저장소에 있다는 이유로 통합됐다고 판정하지 않습니다.

## 남은 필수 검증

- GATE-PHONE-QUEUE-BYTE-LIMIT — 휴대전화 대기자료의 실제 용량 한도
- GATE-SERVER-CAPACITY-STATE-CONTRACT — 서버 용량상태를 휴대전화에 전달하는 규칙
- GATE-RAW-COLLECTION-RELEASE-REVIEW — 무가림 원본 수집의 출시 전 독립 검토
- GATE-CLOUD-COST-MEASUREMENT — 실제 클라우드 저장비 측정
- GATE-SINGLE-ADMIN-RECOVERY-DRILL — 관리자 휴대전화 분실 복구훈련

## 현재 종합판정

- 품질 증거: NOT_RUN / NOT_GENERATED
- 정식 통합: NOT_RUN
- 구현 완료: 주장하지 않음
- 출시: NOT_ELIGIBLE
