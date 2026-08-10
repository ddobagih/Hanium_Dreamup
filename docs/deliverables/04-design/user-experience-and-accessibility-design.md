# WalkSafe 사용자 경험·접근성 설계

이 문서는 WalkSafe 설계를 사람이 검토하기 위한 **독립적인 Draft**입니다. 승인된 정책을 설계 언어로 옮겼지만, 이 문서 자체는 아직 승인되지 않았고 현재 코드가 이 설계를 따른다는 판정이나 시험 완료를 뜻하지 않습니다.

| 통제 항목 | 값 |
|---|---|
| 문서 ID | `WS-DES-UX-ACCESS-DRAFT-20260721-001` |
| 포함 산출물 | `DES-14`, `DES-15`, `DES-16`, `DES-17`, `DES-18` |
| 버전·기준일 | `0.2.0` · `2026-07-22` |
| 문서 생명주기 | `DRAFT` |
| 문서 승인 | `NOT_APPROVED` |
| 설계·구현 적합성 | `NOT_ASSESSED` |
| 시험 | `NOT_RUN` |
| 출시 | `NOT_ELIGIBLE` |
| 남은 게이트 | `5개 NOT_RUN`, 면제 없음 |
| 요구사항 연결 상태 | `DRAFT_REFERENCE_PRESENT_NOT_BASELINED` |
| 기계 추적 | `docs/deliverables/04-design/design-traceability-register.json` |

## 먼저 읽을 핵심 경계

- 정식 사용자 제품은 **Android 사용자 앱**이다.
- 정식 관리 제품은 사용자 앱과 앱 ID·서명·배포·로그인을 분리한 **별도 Android 관리자 앱**이다. 현재 저장소에서는 이 독립 앱 경계를 확인하지 못했으므로 구현 공백으로 둔다.
- Web/PWA는 과거 구현을 이해하기 위한 `LEGACY_REFERENCE_ONLY`이며 정식 사용자·관리자 제품으로 채택하지 않는다.
- `contracts/walksafe.openapi.json`, Android·backend 코드, ORM·migration은 현재 상태를 보여 주는 후보 사실이다. 파일 경로와 SHA-256으로 묶지만 승인된 설계나 적합 증거로 승격하지 않는다.
- 정책 기준선은 승인·기준선화됐지만 이 설계 Draft, 구현, 시험, 배포는 별도 검토 대상이다.
- current effective baseline은 `PB-WALKSAFE-FEATURE-POLICY-1.0.1`이며 exact FP-035 overlay는 `APPROVED / EFFECTIVE / COMMITTED`이다. 과거 correction candidate의 `NOT_APPROVED / NOT_EFFECTIVE`는 `HISTORICAL_PRE_ACTIVATION_ONLY`이며 현재 정책 재승인 차단조건이 아니다. 구현 적합성·정식 시험·남은 gate는 별도 `NOT_ASSESSED / NOT_RUN` 경계를 유지한다.

## 입력 기준과 읽는 법

| 구분 | 기준 |
|---|---|
| 승인 정책 입력 | `PB-WALKSAFE-FEATURE-POLICY-1.0.1` · exact FP-035 overlay `APPROVED / EFFECTIVE / COMMITTED` |
| 정책 manifest | `docs/control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json` · SHA-256 `b6f5b850a3983b8059b85d93dd07864520219d31fa65a65d740b6bab78231308` |
| 기존 답변 정규화 근거 | `docs/control/decision-interview/source-records/walksafe-feature-policy-comprehensive-review-20260719-answers.json`의 `SP-13`, `FP-035` |
| FP-035 provenance | 과거 candidate는 `HISTORICAL_PRE_ACTIVATION_ONLY`; exact overlay는 현재 1.0.1에서 `APPROVED / EFFECTIVE / COMMITTED` |
| 현재 유효 결정 | `docs/control/decision-interview/walksafe-effective-decision-register-current-20260726-r001.json` · 135건 · SHA-256 `4a448f65280c2cd8cd850a749434f4e124769e47b7b84e31e2e14c58328d2faf` |
| 요구예정 | REQ 유형과 `RQ-FP-*`, `RQ-NPC-*`, `RQ-GATE-*` Draft ID; 요구사항 기준선 승인 전까지 계획 연결 |
| 구현 후보 | 아래 후보 근거 표의 경로·SHA-256; 적합성 `NOT_ASSESSED` |
| 상태 해석 | “설계한다”는 목표 구조, “현재 후보”는 저장소 관찰 사실, “남은 일”은 승인·구현·시험 전 차단 항목 |

각 DES 절의 관리표에는 왜 만드는지, 무엇을 채우는지, 누가 작성·검토·승인하는지, 언제 고치고 어떻게 대체·폐기하는지를 함께 적는다.

## 자주 나오는 기술용어를 쉽게 읽기

| 용어 | 쉬운 뜻 |
|---|---|
| gateway | 앱의 요청이 서버로 들어오는 한 개의 확인된 입구 |
| worker | 사용자 화면 없이 서버 뒤에서 접수·삭제·검사 같은 일을 처리하는 프로그램 |
| resource | 계정, 신고, 원본처럼 권한검사의 대상이 되는 자료 |
| object storage | 영상·음성 같은 큰 파일을 두는 서버 저장소 |
| digest·SHA-256 | 파일이 바뀌었는지 비교하는 긴 지문값 |
| TTL | 받은 상태나 자료를 다시 확인해야 하는 유효시간 |
| backoff·backpressure | 실패나 과부하 때 재시도·유입 속도를 늦추는 방법 |
| provenance | 파일·빌드·모델이 어떤 입력과 과정에서 만들어졌는지 남긴 이력 |
| idempotency | 같은 요청을 다시 보내도 한 번 처리한 것과 같은 결과가 되게 하는 성질 |
| lease | 한 기기나 작업자에게 일정 시간만 주는 임시 독점 권한 |
| session | 로그인이나 한 번의 보행처럼 시작과 끝이 있는 사용 단위 |
| token | 비밀번호를 매번 보내지 않고 로그인·권한을 증명하는 짧은 전자표 |
| MFA·passkey | 비밀번호 하나에만 의존하지 않는 추가 로그인 확인수단 |
| attestation | 등록된 진짜 앱·기기·보안수단인지 확인하는 절차 |
| KMS·HSM | 암호화·서명 열쇠를 일반 파일과 분리해 보호하는 전용 관리수단 |
| break-glass | 평상시에는 막아 두고 사고 때만 승인·기록 후 여는 긴급 접근 |
| rate limit·timeout·circuit | 요청 횟수를 제한하고, 오래 걸리는 요청을 끝내며, 연속 실패한 외부 호출을 잠시 막는 보호장치 |
| audit·append-only | 누가 무엇을 했는지 남기고 과거 기록을 덮어쓰지 않는 감사기록 방식 |
| redaction | 로그에서 비밀값·정확 위치·원본 주소 같은 민감정보를 가리는 처리 |
| alert·on-call | 이상을 자동 통보하고 정해진 담당자가 대응하는 운영체계 |
| RTO·RPO·drill | 장애 뒤 복구 목표시간, 허용 가능한 자료 손실범위, 실제 복구 연습 |
| quota·5xx | 외부서비스 사용한도와 외부 서버 쪽 오류 응답 |
| subprocessor·region | 자료처리에 다시 참여하는 하위업체와 자료가 저장·처리되는 국가·지역 |
| SBOM·SCA | 사용한 소프트웨어 부품 목록과 그 부품의 알려진 취약점 검사 |
| TLS | 앱과 서버 사이 전송내용을 암호화하고 상대 서버를 확인하는 통신 보호 |
| sandbox | 앱이나 작업이 다른 자료에 함부로 접근하지 못하게 나눈 격리공간 |


## 현재 구현 후보 근거와 SHA-256

이 표는 현재 저장소를 재현하기 위한 근거다. `NOT_ASSESSED`이므로 설계 충족이나 시험 통과를 의미하지 않는다.

| 근거 ID | 분류 | 경로 | SHA-256 | 읽는 법 |
|---|---|---|---|---|
| `SRC-ANDROID-MANIFEST` | `CANDIDATE_IMPLEMENTATION_FACT` | `apps/android/app/src/main/AndroidManifest.xml` | `30efe0264c2d27512e9b63b96a8838ae560809902e41a348975baaf07ff592c2` | 현재 권한·기기기능·activity 선언 후보 |
| `SRC-ANDROID-MAIN-ACTIVITY` | `CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED` | `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt` | `ed9c8df7e7402a6214871a099d9994b182a01030bb01fab7662f062025887803` | 현재 개발·디버그 중심 조합 화면; 정식 무버튼 보행 화면과 정합성 미확인 |
| `SRC-ANDROID-STYLES` | `CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED` | `apps/android/app/src/main/res/values/styles.xml` | `e4d7e6828932ca6d969086c37e51af2a076d9d22388a0854eef78d5cf6b3579c` | 현재 Android 시각 스타일 후보 |
| `SRC-ANDROID-ROUTE` | `CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED` | `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigator.kt` | `2fe1c8200cb4f1e302be2123df9160297256c3b6d8b7ccf05c7ae5451cd8e39b` | 현재 경로 진행·이탈 후보 로직 |
| `SRC-ANDROID-STEP` | `CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED` | `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/StepLengthEstimator.kt` | `ec69d3e873b2ec408b50ad08a1216af590f34e9c3efd4886a3bc66c0ad4e38fd` | 현재 보폭 추정 후보 로직 |
| `SRC-ANDROID-VOICE` | `CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED` | `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidVoiceCommand.kt` | `d46b2e5a38201addc108f0c18d9d5ef529b3f047724ce5ad1aec515bdb450755` | 현재 Android 음성 명령 후보 |
| `SRC-ANDROID-REPORT-UPLOADER` | `CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED` | `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportUploader.kt` | `ac8186277ec3f88e0ff848818ef15059a6512ff5f8a7bd24deb1f00dd9259205` | 현재 신고 전송 후보 |
| `SRC-WEB-PACKAGE` | `LEGACY_REFERENCE_ONLY` | `apps/web/package.json` | `16caa5d7c305b90b74daa6e6c8ffe175da8c9991a459993b7b4a61164c45aa54` | Web/PWA 과거 구현 버전; 정식 제품 설계가 아님 |

## Phase 1 current-source·review binding

| 항목 | 결속 값 |
|---|---|
| 대상 | `DLV-DES-15` · controlled locator `docs/deliverables/04-design/user-experience-and-accessibility-design.md#des-15` |
| 정책 authority | `PB-WALKSAFE-FEATURE-POLICY-1.0.1` · manifest SHA-256 `b6f5b850a3983b8059b85d93dd07864520219d31fa65a65d740b6bab78231308` · effective decision register SHA-256 `4a448f65280c2cd8cd850a749434f4e124769e47b7b84e31e2e14c58328d2faf` |
| 현재 source provenance | `DIRTY_WORKTREE_EXACT_CONTENT_SNAPSHOT` · `2026-07-26` · 2,960 stable files · `path_set_sha256=971cee4fbded63faf05e30b0dc7343a9eff41e4612edff307f3df5581c880eff` · `content_set_sha256=f7a05a1abd7b89053dd7d7d052508dfd43b19821174c8de5a82fe754ae90cade` · `source_commit=null` |
| 구현 inventory authority | `docs/deliverables/05-implementation/implementation-manifest-20260727-r002.json` · SHA-256 `f26709242bf7520ec9385feb70f5df859124700657d8fe2dc71c8d1d1df839c2` |
| 내용 판정 | 정상·권한·동의·오류·복구 흐름은 기존 DES-15 내용으로 충족하며 중복 본문을 추가하지 않는다. 현행 source와의 적합성은 아직 `NOT_ASSESSED`이다. |
| reviewer contract | 작성 `TECHNICAL_OWNER`; 검토 `PRODUCT_OWNER / SECURITY_AND_PRIVACY_OWNER / QA_OWNER / INDEPENDENT_TECHNICAL_REVIEWER`; 승인 `PROJECT_SCOPE_OWNER`; 현재 `NOT_PERFORMED / NOT_APPROVED` |
| 금지 승격 | 이 binding은 설계 승인, 구현 적합성, 실제 기기시험, formal PASS, release 완료가 아니다. |

## 사용자를 중심에 둔 설계 전제

주 사용자는 전맹·저시력 보행자이며 두 사용자군을 같은 우선순위로 둔다. 보행 중 화면을 오래 보거나 작은 버튼을 찾는 것을 전제로 하지 않는다. 정상 보행 화면은 카메라 중심 읽기 전용 상태로 두고, 위험·경로·오류는 짧은 음성 문장과 구별되는 진동으로 전달한다. 단, 음성·진동이 정확히 작동한다는 주장은 실제 기기·소음·TalkBack·현장시험 뒤에만 할 수 있다.

현재 `MainActivity.kt`에는 개발·디버그 조작이 함께 보이므로 목표 무버튼 보행 UI의 채택 근거가 아니다. 개발 기능은 production build·배포에서 제거 또는 강하게 격리해야 한다. Web/PWA 화면도 Android 정식 화면의 근거가 아니라 legacy 참고자료다.


<a id="des-14"></a>
## DES-14 화면 정보구조

### Android 사용자 앱 정보구조

```text
앱 시작
├─ 서비스 목적·안전 제한
├─ 연령 조건·필요 시 보호자 확인
├─ 동의
│  ├─ 서비스 필수 처리
│  ├─ 무가림 원본 수집
│  ├─ 모델 개선 목적
│  ├─ 자동신고
│  └─ 이동통신망 전송 선택
├─ 가입·휴대전화 확인·로그인
├─ 필요한 기능을 처음 쓸 때 권한
├─ 기기 기능 점검·안전 연습
└─ 보행 홈
   ├─ 자동 시작 전 상태 확인
   ├─ 정상 보행(카메라 중심·읽기 전용)
   ├─ 목적지 검색·후보 확인
   ├─ 보행 일시중지·재개 확인
   ├─ 안전정지·복구 행동
   └─ 설정
      ├─ 동의·자동신고·이동통신망
      ├─ 연결 기기·로그아웃
      ├─ 자료 열람·철회·삭제요청
      └─ 접근성·안내 연습
```

### 별도 Android 관리자 앱 정보구조

```text
관리자 로그인(MFA/패스키)
├─ 운영 요약(민감 원본 없음)
├─ 신고 목록·필터
│  └─ 신고 상세·상태 변경(재인증 가능)
├─ 용량·전송·장애 상태
├─ 감사기록 조회
├─ 모델·release 상태(승인된 읽기 범위)
└─ 보안·기기 session·복구
```

관리자 앱은 사용자 앱 안의 숨은 메뉴가 아니다. 현재 독립 앱이 없으므로 위 구조는 목표 Draft이며 구현 완료가 아니다.

### 역할·상태별 화면 원칙

| 상태 | 사용자에게 보일 핵심 | 허용 조작 | 금지 |
|---|---|---|---|
| 준비 전 | 부족한 동의·권한·기기 기능과 해결 순서 | 해당 단계 이동, 도움 | 보행·원본수집 자동 시작 |
| ACTIVE/FULL | 카메라와 현재 보행·경로 상태 | 음성 명령, 물리 뒤로가기 시 일시중지 | 개발버튼·민감 원본 상세 노출 |
| ACTIVE/DISTANCE_LIMITED | 거리·충돌·해제 판단 불가를 지속 인지 | 종류 경고, 일시중지·종료 | 거리 표현·자동신고 위험판정 |
| PAUSED | 중지 이유와 재검사 필요 | 상태 재검사 후 명시적 재개 | 자동 재개 |
| ROUTE_DEVIATION_SUSPECTED | 회전안내 중지, 위치 확인 중 | 위치 재확인·길안내 종료 | 오래된 회전지시 반복 |
| ROUTE_DEVIATION_CONFIRMED | 새 경로·위치 확인·종료 선택 | 사용자 선택 | 자동 TMAP 재호출 |
| SAFE_STOP | 신뢰하지 못하는 기능과 안전 행동 | 종료·다시 확인·도움 | “정상”처럼 조용히 계속 안내 |
| 빈 목록/오류 | 무엇이 없거나 실패했는지 | 다시 시도·뒤로·문의 | 빈 화면·무한 spinner |

### 이 산출물의 작성·관리 기준

| 항목 | 현재 값 |
|---|---|
| 작성 목적 | 사용자·관리자 화면의 콘텐츠 계층과 탐색 구조를 역할·작업 중심으로 정한다. |
| 필수/조건 | `REQUIRED` · 요구사항 기준선 승인 후 구현·통합 전에 활성 |
| 들어갈 내용 | - 화면·route 목록<br>- 콘텐츠·기능 계층<br>- 역할별 접근<br>- 전역·지역 navigation<br>- 상태·빈 화면·오류 화면<br>- 접근성 landmark |
| 작성 입력 | - 승인된 요구사항 기준선과 RTM<br>- 현재 코드·OpenAPI·DB migration·배포 형상<br>- ADR 후보와 품질·보안·안전 제약 |
| 선행 → 후속 | `REQ-05`, `REQ-11` → `DES-15`, `DES-16` |
| 작성·검토·승인 | 기술책임자 · 제품책임자, 보안·개인정보책임자, QA책임자, 독립기술검토자 · 프로젝트책임자 |
| 형식·정본 위치 | `SECTION` · `docs/deliverables/04-design/user-experience-and-accessibility-design.md#des-14` |
| 보조 파일 | `docs/deliverables/04-design/design-traceability-register.json` |
| 완료·승인 기준 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 화면·route 목록, 콘텐츠·기능 계층.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |
| 갱신 조건 | - 요구·아키텍처·API·DB·배포·보안 경계 변경<br>- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경<br>- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료 |
| 검토 주기 | 초안의 필수내용을 채운 때, 상위 요구·정책·설계 경계가 바뀐 때, 설계 기준선 승인 전에 검토한다. |
| 변경·대체·폐기 | 승인본을 덮어쓰지 않는다. 변경요청과 영향분석을 남겨 새 버전을 만들고, 대체된 버전은 Superseded로 표시한 뒤 정해진 보존기간이 끝나면 Archived로 옮긴다. |

쉽게 말하면, 이 표는 이 설계 산출물을 왜 만들고 누가 언제까지 무엇을 확인하며, 바뀌면 어떻게 새 버전으로 관리할지를 정한 약속이다.

### 추적과 판정 경계

- 입력: 현재 승인 정책 [`PB-WALKSAFE-FEATURE-POLICY-1.0.1`](../../control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json), [현재 유효 결정 등록부](../../control/decision-interview/walksafe-effective-decision-register-current-20260726-r001.json), [기존 답변](../../control/decision-interview/source-records/walksafe-feature-policy-comprehensive-review-20260719-answers.json), [FP-035 역사 pre-activation snapshot](../../control/decision-interview/walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json), 산출물 유형 작성계약.
- 정책 결정: 영역 `FA-02`, `FA-03`, `FA-04`, `FA-05`, `FA-06`, `FA-10`; 기능 `FP-004`, `FP-005`, `FP-006`, `FP-007`, `FP-008`, `FP-009`, `FP-010`, `FP-011`, `FP-012`, `FP-013`, `FP-014`, `FP-015`, `FP-016`, `FP-017`, `FP-018`, `FP-028`, `FP-029`, `FP-030`; 공통정책 `NPC-PERMISSION-SESSION-LIFECYCLE`; 흐름 `FLOW-02`, `FLOW-03`.
- 기존 답변 정규화: 없음; 적용 요구유형 없음. 해당 없는 산출물은 `없음`이다.
- 현재 정책 재승인 의존성: 없음. exact FP-035 overlay는 1.0.1에서 유효하며, 구현 적합성·정식 시험·남은 gate는 별도 검토한다.

- 정렬 결정: 등록부의 64건(`DEC-SAFETY-POSITION`, `DEC-USER-AGE`, `DEC-USE-ENVIRONMENT`, `DEC-CROSSWALK-SCOPE`, `DEC-POOR-IMAGE-BEHAVIOR`, `DEC-PHONE-MOUNT`, `DEC-LANGUAGE-SCOPE`, `DEC-APP-SEPARATION`, `DEC-IBQ-011`, `DEC-IBQ-012`, `DEC-IBQ-013`, `DEC-DEPTH-UNSUPPORTED` 외 52건). 전체 목록과 해시는 추적 등록부에 있다.
- 요구예정 유형: [`REQ-05`](../03-requirements/acceptance-specification.md#req-05) (DRAFT_FILE_PRESENT_NOT_BASELINED); [`REQ-11`](../03-requirements/system-requirements.md#req-11) (DRAFT_FILE_PRESENT_NOT_BASELINED).
- 요구예정 상세: 21건([`RQ-FP-004-001`](../03-requirements/system-requirements.md#RQ-FP-004-001), [`RQ-FP-005-001`](../03-requirements/system-requirements.md#RQ-FP-005-001), [`RQ-FP-006-001`](../03-requirements/system-requirements.md#RQ-FP-006-001), [`RQ-FP-007-001`](../03-requirements/system-requirements.md#RQ-FP-007-001), [`RQ-FP-008-001`](../03-requirements/system-requirements.md#RQ-FP-008-001), [`RQ-FP-009-001`](../03-requirements/system-requirements.md#RQ-FP-009-001), [`RQ-FP-010-001`](../03-requirements/system-requirements.md#RQ-FP-010-001), [`RQ-FP-011-001`](../03-requirements/system-requirements.md#RQ-FP-011-001), [`RQ-FP-012-001`](../03-requirements/system-requirements.md#RQ-FP-012-001), [`RQ-FP-013-001`](../03-requirements/system-requirements.md#RQ-FP-013-001), [`RQ-FP-014-001`](../03-requirements/system-requirements.md#RQ-FP-014-001), [`RQ-FP-015-001`](../03-requirements/system-requirements.md#RQ-FP-015-001) 외 9건); 상태 `DRAFT_REFERENCE_PRESENT_NOT_BASELINED`.
- 현재 후보 근거: `SRC-ANDROID-MANIFEST`, `SRC-ANDROID-MAIN-ACTIVITY`, `SRC-ANDROID-STYLES`, `SRC-ANDROID-ROUTE`, `SRC-ANDROID-STEP`, `SRC-ANDROID-VOICE`, `SRC-ANDROID-REPORT-UPLOADER`, `SRC-WEB-PACKAGE`; 구현 적합성 `NOT_ASSESSED`.
- 이 절의 문서 상태는 `DRAFT`, 승인 `NOT_APPROVED`, 검증 `NOT_RUN`이다.


<a id="des-15"></a>
## DES-15 사용자 흐름

### 1. 첫 실행→보행 시작

1. 목적과 안전 제한, 만 14세 이상 조건을 쉬운 문장으로 읽는다.
2. 서비스 필수 처리와 원본·모델 개선·자동신고·이동통신망 선택을 구분해 설명하고 선택 결과를 다시 읽는다.
3. 가입·휴대전화 확인·필요 시 보호자 확인, 계정 활성화, 로그인을 마친다.
4. 카메라·정확 위치·마이크 등은 기능을 처음 쓰기 직전에 이유를 설명하고 운영체제 권한을 요청한다.
5. 기기 기능수준, 모델, 필수 서버, 저장상태를 점검하고 안전 연습을 제공한다.
6. 모든 필수 조건이 갖춰지면 보행 안내를 시작한다. 거리 기능이 없지만 제한모드 기준을 만족하면 제한을 먼저 알리고 확인받는다.

한 단계 실패 시 보행 화면으로 넘어가지 않는다. 이미 안전하게 확인된 단계만 복원하며, 동의 전에 카메라·음성·정밀위치 원본 수집을 시작하지 않는다.

### 2. 목적지→경로→이탈

사용자는 음성으로 목적지를 말하고, 후보의 이름·주소·거리를 듣고 하나를 확인한다. 받은 TMAP 경로는 version·시각과 함께 저장한다. 남은 거리와 도착은 GPS와 저장 경로가 주 기준이고 보폭은 보조다. 도착 후보가 되면 사용자에게 실제 도착 여부를 확인해 확정한다. 이탈 확정 뒤에는 “새 경로 찾기 / 현재 위치 다시 확인 / 길안내 끝내기” 중 하나를 사용자가 고른다.

### 3. 손상 점자블록 수동·자동신고

수동신고는 사용자가 음성으로 요청한 결과를 알려 준다. 자동신고는 최초 동의 뒤 후보마다 음성·진동·푸시나 개별 취소를 제공하지 않는다. 설정에서 자동신고 전체를 끌 수 있고, 끈 뒤 새 후보와 미전송 전송을 막는다. 이 조용한 처리 때문에 **실시간 탐지·길안내 오류까지 숨겨서는 안 된다**.

### 4. 앱 이탈·권한 철회·비정상 종료

- 앱이 뒤로 가거나 잠기면 보행 기능을 즉시 일시중지한다.
- 돌아오면 과거 검사결과를 재사용하지 않고 현재 상태를 다시 확인하고 사용자의 분명한 확인 뒤 재개한다.
- 권한 철회는 그 권한 의존 기능만 멈추되 남은 기능이 안전하지 않으면 전체 안전정지를 알린다.
- 재부팅·강제종료·오류종료 뒤 이전 보행·경로는 자동 재개하지 않는다.
- 로그아웃은 OS 권한·서버 동의·서버 자료를 자동 삭제하지 않으며, 이를 쉬운 문장으로 구분한다.

### 5. 삭제 권리행사

설정뿐 아니라 앱 밖에서도 열람·철회·삭제요청 경로를 제공한다. 요청을 받으면 새 수집·전송을 즉시 중단하고 단말·서버·학습자료·백업별 기한과 예외를 보여 준다. 실패를 성공으로 표시하지 않고 진행상태·재처리·문의방법을 제공한다.

### 이 산출물의 작성·관리 기준

| 항목 | 현재 값 |
|---|---|
| 작성 목적 | 목표 달성 단계와 분기·권한·오류·회복 경로를 화면과 시스템 동작에 연결한다. |
| 필수/조건 | `REQUIRED` · 요구사항 기준선 승인 후 구현·통합 전에 활성 |
| 들어갈 내용 | - 시작·종료 상태<br>- 단계별 사용자 행동<br>- 화면·시스템 응답<br>- 권한·동의 분기<br>- 오류·취소·복구<br>- 요구·시험 연결 |
| 작성 입력 | - 승인된 요구사항 기준선과 RTM<br>- 현재 코드·OpenAPI·DB migration·배포 형상<br>- ADR 후보와 품질·보안·안전 제약 |
| 선행 → 후속 | `DES-14`, `REQ-05`, `WS-02`, `WS-04` → `DES-16`, `DES-18`, `REL-17`, `TST-09`, `WS-07` |
| 작성·검토·승인 | 기술책임자 · 제품책임자, 보안·개인정보책임자, QA책임자, 독립기술검토자 · 프로젝트책임자 |
| 형식·정본 위치 | `SECTION` · `docs/deliverables/04-design/user-experience-and-accessibility-design.md#des-15` |
| 보조 파일 | `docs/deliverables/04-design/design-traceability-register.json` |
| 완료·승인 기준 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 시작·종료 상태, 단계별 사용자 행동.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |
| 갱신 조건 | - 요구·아키텍처·API·DB·배포·보안 경계 변경<br>- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경<br>- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료 |
| 검토 주기 | 초안의 필수내용을 채운 때, 상위 요구·정책·설계 경계가 바뀐 때, 설계 기준선 승인 전에 검토한다. |
| 변경·대체·폐기 | 승인본을 덮어쓰지 않는다. 변경요청과 영향분석을 남겨 새 버전을 만들고, 대체된 버전은 Superseded로 표시한 뒤 정해진 보존기간이 끝나면 Archived로 옮긴다. |

쉽게 말하면, 이 표는 이 설계 산출물을 왜 만들고 누가 언제까지 무엇을 확인하며, 바뀌면 어떻게 새 버전으로 관리할지를 정한 약속이다.

### 추적과 판정 경계

- 입력: 현재 승인 정책 [`PB-WALKSAFE-FEATURE-POLICY-1.0.1`](../../control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json), [현재 유효 결정 등록부](../../control/decision-interview/walksafe-effective-decision-register-current-20260726-r001.json), [기존 답변](../../control/decision-interview/source-records/walksafe-feature-policy-comprehensive-review-20260719-answers.json), [FP-035 역사 pre-activation snapshot](../../control/decision-interview/walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json), 산출물 유형 작성계약.
- 정책 결정: 영역 `FA-04`, `FA-05`, `FA-06`, `FA-07`, `FA-08`, `FA-09`, `FA-10`, `FA-11`, `FA-15`; 기능 `FP-010`, `FP-011`, `FP-012`, `FP-013`, `FP-014`, `FP-015`, `FP-016`, `FP-017`, `FP-018`, `FP-019`, `FP-020`, `FP-021`, `FP-022`, `FP-023`, `FP-024`, `FP-025`, `FP-026`, `FP-027`, `FP-028`, `FP-029`, `FP-030`, `FP-031`, `FP-032`, `FP-033`, `FP-043`, `FP-044`; 공통정책 `NPC-AUTO-REPORT`, `NPC-PERMISSION-SESSION-LIFECYCLE`, `NPC-NAVIGATION-ROUTE-DIRECTION`; 흐름 `FLOW-02`, `FLOW-03`, `FLOW-04`, `FLOW-05`, `FLOW-06`, `FLOW-07`, `FLOW-10`, `FLOW-11`.
- 기존 답변 정규화: 없음; 적용 요구유형 없음. 해당 없는 산출물은 `없음`이다.
- 현재 정책 재승인 의존성: 없음. exact FP-035 overlay는 1.0.1에서 유효하며, 구현 적합성·정식 시험·남은 gate는 별도 검토한다.

- 정렬 결정: 등록부의 94건(`DEC-USER-AGE`, `DEC-POOR-IMAGE-BEHAVIOR`, `DEC-PHONE-MOUNT`, `DEC-LANGUAGE-SCOPE`, `DEC-DEPTH-UNSUPPORTED`, `DEC-IBQ-015`, `DEC-IBQ-019`, `DEC-SIGNUP-DATA`, `DEC-IDENTITY-VERIFY`, `DEC-LOGIN-PERSISTENCE`, `DEC-MULTI-DEVICE-KEEP`, `DEC-CONCURRENT-WALK` 외 82건). 전체 목록과 해시는 추적 등록부에 있다.
- 요구예정 유형: [`REQ-05`](../03-requirements/acceptance-specification.md#req-05) (DRAFT_FILE_PRESENT_NOT_BASELINED); [`REQ-06`](../03-requirements/acceptance-specification.md#req-06) (DRAFT_FILE_PRESENT_NOT_BASELINED); [`REQ-11`](../03-requirements/system-requirements.md#req-11) (DRAFT_FILE_PRESENT_NOT_BASELINED).
- 요구예정 상세: 32건([`RQ-FP-010-001`](../03-requirements/system-requirements.md#RQ-FP-010-001), [`RQ-FP-011-001`](../03-requirements/system-requirements.md#RQ-FP-011-001), [`RQ-FP-012-001`](../03-requirements/system-requirements.md#RQ-FP-012-001), [`RQ-FP-013-001`](../03-requirements/system-requirements.md#RQ-FP-013-001), [`RQ-FP-014-001`](../03-requirements/system-requirements.md#RQ-FP-014-001), [`RQ-FP-015-001`](../03-requirements/system-requirements.md#RQ-FP-015-001), [`RQ-FP-016-001`](../03-requirements/system-requirements.md#RQ-FP-016-001), [`RQ-FP-017-001`](../03-requirements/system-requirements.md#RQ-FP-017-001), [`RQ-FP-018-001`](../03-requirements/system-requirements.md#RQ-FP-018-001), [`RQ-FP-019-001`](../03-requirements/system-requirements.md#RQ-FP-019-001), [`RQ-FP-020-001`](../03-requirements/system-requirements.md#RQ-FP-020-001), [`RQ-FP-021-001`](../03-requirements/system-requirements.md#RQ-FP-021-001) 외 20건); 상태 `DRAFT_REFERENCE_PRESENT_NOT_BASELINED`.
- 현재 후보 근거: `SRC-ANDROID-MANIFEST`, `SRC-ANDROID-MAIN-ACTIVITY`, `SRC-ANDROID-STYLES`, `SRC-ANDROID-ROUTE`, `SRC-ANDROID-STEP`, `SRC-ANDROID-VOICE`, `SRC-ANDROID-REPORT-UPLOADER`, `SRC-WEB-PACKAGE`; 구현 적합성 `NOT_ASSESSED`.
- 이 절의 문서 상태는 `DRAFT`, 승인 `NOT_APPROVED`, 검증 `NOT_RUN`이다.


<a id="des-16"></a>
## DES-16 와이어프레임·프로토타입

아래는 화면 배치를 확정한 시각 디자인이 아니라 정보 우선순위와 접근성 동작을 검토하는 저충실도 Draft다.

### 정상 보행 화면

```text
┌──────────────────────────────┐
│ WalkSafe · 보행 중           │  ← TalkBack: 상태 먼저
│ [카메라 미리보기 전체 영역]  │
│                              │
│ 경로: 80m 뒤 오른쪽          │  ← 큰 글자·고대비·읽기 전용
│ 위험: 전방 가까운 장애물     │  ← 색만 쓰지 않고 문장+음성
│ 기능: 전체 / GPS 정확도 양호 │
└──────────────────────────────┘
조작: 음성 명령, 시스템 뒤로가기→즉시 일시중지
production 화면에 모델·URL·임계값·업로드 debug 버튼 없음
```

### 일시중지·재개 확인

```text
┌──────────────────────────────┐
│ 보행 안내가 멈췄습니다        │
│ 이유: 화면을 벗어났습니다     │
│ 카메라·위치·마이크 다시 확인  │
│ [다시 확인]   [보행 끝내기]    │
└──────────────────────────────┘
확인 성공 뒤에도 “보행을 다시 시작할까요?” 명시적 확인
```

### 경로 이탈 확정

```text
┌──────────────────────────────┐
│ 저장된 경로에서 벗어났습니다 │
│ 회전 안내를 멈췄습니다        │
│ [새 경로 찾기]                │
│ [현재 위치 다시 확인]         │
│ [길안내 끝내기]               │
└──────────────────────────────┘
TalkBack focus는 제목→이유→세 선택 순서
```

### 원본 동의

```text
┌──────────────────────────────┐
│ 활성 보행 원본 수집          │
│ 무엇: 영상·음성·정확 위치…   │
│ 왜: 서비스 처리 / 모델 개선  │
│ 언제 전송: 보행 종료 뒤…     │
│ 얼마나: 위치별 보존표        │
│ 삭제: 앱 밖 요청경로 포함     │
│ [목적별 선택] [전체 다시 듣기]│
└──────────────────────────────┘
```

각 prototype은 음성만, TalkBack+터치, 큰 글꼴, 가로폭 축소, 권한거부, offline, 오류 메시지 조건으로 사용자 검토해야 한다. 피드백·승인 기록은 아직 없다.

### 이 산출물의 작성·관리 기준

| 항목 | 현재 값 |
|---|---|
| 작성 목적 | 구현 전 화면 배치·상태·상호작용을 낮은 비용으로 검증한다. |
| 필수/조건 | `REQUIRED` · 요구사항 기준선 승인 후 구현·통합 전에 활성 |
| 들어갈 내용 | - 핵심 화면과 viewport<br>- 콘텐츠 우선순위<br>- 상태·모달·알림<br>- 탭·키보드 순서<br>- 클릭·음성 상호작용<br>- 피드백·승인 상태 |
| 작성 입력 | - 승인된 요구사항 기준선과 RTM<br>- 현재 코드·OpenAPI·DB migration·배포 형상<br>- ADR 후보와 품질·보안·안전 제약 |
| 선행 → 후속 | `DES-14`, `DES-15` → `TST-15` |
| 작성·검토·승인 | 기술책임자 · 제품책임자, 보안·개인정보책임자, QA책임자, 독립기술검토자 · 프로젝트책임자 |
| 형식·정본 위치 | `CANONICAL_DOCUMENT` · `docs/deliverables/04-design/user-experience-and-accessibility-design.md#des-16` |
| 보조 파일 | `docs/deliverables/04-design/design-traceability-register.json` |
| 완료·승인 기준 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 핵심 화면과 viewport, 콘텐츠 우선순위.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |
| 갱신 조건 | - 요구·아키텍처·API·DB·배포·보안 경계 변경<br>- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경<br>- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료 |
| 검토 주기 | 초안의 필수내용을 채운 때, 상위 요구·정책·설계 경계가 바뀐 때, 설계 기준선 승인 전에 검토한다. |
| 변경·대체·폐기 | 승인본을 덮어쓰지 않는다. 변경요청과 영향분석을 남겨 새 버전을 만들고, 대체된 버전은 Superseded로 표시한 뒤 정해진 보존기간이 끝나면 Archived로 옮긴다. |

쉽게 말하면, 이 표는 이 설계 산출물을 왜 만들고 누가 언제까지 무엇을 확인하며, 바뀌면 어떻게 새 버전으로 관리할지를 정한 약속이다.

### 추적과 판정 경계

- 입력: 현재 승인 정책 [`PB-WALKSAFE-FEATURE-POLICY-1.0.1`](../../control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json), [현재 유효 결정 등록부](../../control/decision-interview/walksafe-effective-decision-register-current-20260726-r001.json), [기존 답변](../../control/decision-interview/source-records/walksafe-feature-policy-comprehensive-review-20260719-answers.json), [FP-035 역사 pre-activation snapshot](../../control/decision-interview/walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json), 산출물 유형 작성계약.
- 정책 결정: 영역 `FA-02`, `FA-04`, `FA-05`, `FA-06`, `FA-08`, `FA-09`, `FA-10`, `FA-11`; 기능 `FP-004`, `FP-005`, `FP-006`, `FP-010`, `FP-013`, `FP-014`, `FP-016`, `FP-017`, `FP-018`, `FP-022`, `FP-023`, `FP-025`, `FP-027`, `FP-028`, `FP-029`, `FP-030`, `FP-033`; 공통정책 `NPC-PERMISSION-SESSION-LIFECYCLE`, `NPC-NAVIGATION-ROUTE-DIRECTION`; 흐름 `FLOW-02`, `FLOW-03`, `FLOW-04`, `FLOW-05`, `FLOW-06`, `FLOW-07`.
- 기존 답변 정규화: 없음; 적용 요구유형 없음. 해당 없는 산출물은 `없음`이다.
- 현재 정책 재승인 의존성: 없음. exact FP-035 overlay는 1.0.1에서 유효하며, 구현 적합성·정식 시험·남은 gate는 별도 검토한다.

- 정렬 결정: 등록부의 64건(`DEC-SAFETY-POSITION`, `DEC-USER-AGE`, `DEC-USE-ENVIRONMENT`, `DEC-CROSSWALK-SCOPE`, `DEC-POOR-IMAGE-BEHAVIOR`, `DEC-PHONE-MOUNT`, `DEC-LANGUAGE-SCOPE`, `DEC-IBQ-019`, `DEC-SIGNUP-DATA`, `DEC-IDENTITY-VERIFY`, `DEC-CONCURRENT-WALK`, `DEC-IBQ-028` 외 52건). 전체 목록과 해시는 추적 등록부에 있다.
- 요구예정 유형: [`REQ-05`](../03-requirements/acceptance-specification.md#req-05) (DRAFT_FILE_PRESENT_NOT_BASELINED); [`REQ-11`](../03-requirements/system-requirements.md#req-11) (DRAFT_FILE_PRESENT_NOT_BASELINED).
- 요구예정 상세: 20건([`RQ-FP-004-001`](../03-requirements/system-requirements.md#RQ-FP-004-001), [`RQ-FP-005-001`](../03-requirements/system-requirements.md#RQ-FP-005-001), [`RQ-FP-006-001`](../03-requirements/system-requirements.md#RQ-FP-006-001), [`RQ-FP-010-001`](../03-requirements/system-requirements.md#RQ-FP-010-001), [`RQ-FP-013-001`](../03-requirements/system-requirements.md#RQ-FP-013-001), [`RQ-FP-014-001`](../03-requirements/system-requirements.md#RQ-FP-014-001), [`RQ-FP-016-001`](../03-requirements/system-requirements.md#RQ-FP-016-001), [`RQ-FP-017-001`](../03-requirements/system-requirements.md#RQ-FP-017-001), [`RQ-FP-018-001`](../03-requirements/system-requirements.md#RQ-FP-018-001), [`RQ-FP-022-001`](../03-requirements/system-requirements.md#RQ-FP-022-001), [`RQ-FP-023-001`](../03-requirements/system-requirements.md#RQ-FP-023-001), [`RQ-FP-025-001`](../03-requirements/system-requirements.md#RQ-FP-025-001) 외 8건); 상태 `DRAFT_REFERENCE_PRESENT_NOT_BASELINED`.
- 현재 후보 근거: `SRC-ANDROID-MANIFEST`, `SRC-ANDROID-MAIN-ACTIVITY`, `SRC-ANDROID-STYLES`, `SRC-ANDROID-ROUTE`, `SRC-ANDROID-STEP`, `SRC-ANDROID-VOICE`, `SRC-ANDROID-REPORT-UPLOADER`, `SRC-WEB-PACKAGE`; 구현 적합성 `NOT_ASSESSED`.
- 이 절의 문서 상태는 `DRAFT`, 승인 `NOT_APPROVED`, 검증 `NOT_RUN`이다.


<a id="des-17"></a>
## DES-17 디자인 시스템

### Draft token과 의미

| token | 목적 | Draft 규칙 |
|---|---|---|
| `text.primary` / `surface.primary` | 기본 정보 | 최소 WCAG AA 대비 목표, 실제 Android rendering 측정 필요 |
| `risk.info/warning/critical` | 위험 수준 | 색+아이콘+문장+음성/진동을 함께 사용; 색 단독 금지 |
| `type.body/status/action` | 본문·상태·행동 | 시스템 글꼴 확대를 막지 않고 잘림·겹침 금지 |
| `space.touch` | 터치 대상 | 최소 48dp 후보, 대상 사이 간격 확보 |
| `motion.duration` | 상태 전환 | 장식적 움직임 최소화, OS 애니메이션 축소 존중 |
| `haptic.warning/critical/confirmation` | 촉각 패턴 | 의미 중복 방지, TTS를 방해하지 않게 우선순위 조정 |

### 구성요소 규칙

- **Primary action**: 화면당 핵심 행동 하나, 동사형 이름, TalkBack role·상태 제공.
- **Permission explanation**: 기능 이유→필요 자료→거부 시 가능한 기능→OS 요청 순서.
- **Status banner**: `정상/제한/일시중지/안전정지`를 동일한 모양·색으로 혼동시키지 않고 문장으로 명시.
- **Risk announcement**: “무엇 / 어느 방향 / 얼마나 가까운지 또는 모름 / 사용자가 할 행동” 순서. 거리 제한모드에서는 숫자 거리 금지.
- **Error**: 기술코드가 아니라 실패한 일·영향·사용자가 할 일·다시 시도 여부. request ID는 문의용 보조정보.
- **Destructive action**: 삭제 범위·위치별 기한·되돌릴 수 없음·예외를 먼저 읽고 명시적 확인.
- **Admin sensitive action**: 상태변경·export·삭제·권한변경은 목적과 단계상승 인증, 결과 감사.

token의 실제 색상값·폰트 크기·진동 파형은 접근성·실제 기기 시험 뒤 확정한다. 현재 `styles.xml`은 후보일 뿐 이 시스템의 승인 구현이 아니다.

### 이 산출물의 작성·관리 기준

| 항목 | 현재 값 |
|---|---|
| 작성 목적 | 색·타이포·간격·컴포넌트·상태 표현을 일관되고 접근 가능하게 재사용한다. |
| 필수/조건 | `REQUIRED` · 요구사항 기준선 승인 후 구현·통합 전에 활성 |
| 들어갈 내용 | - design token<br>- 타이포·색·대비<br>- 버튼·입력·알림 컴포넌트<br>- 상태·위험 수준 표현<br>- 반응형·터치 규칙<br>- 버전·변경·사용 예시 |
| 작성 입력 | - 승인된 요구사항 기준선과 RTM<br>- 현재 코드·OpenAPI·DB migration·배포 형상<br>- ADR 후보와 품질·보안·안전 제약 |
| 선행 → 후속 | `REQ-11` → `DES-18`, `WS-15` |
| 작성·검토·승인 | 기술책임자 · 제품책임자, 보안·개인정보책임자, QA책임자, 독립기술검토자 · 프로젝트책임자 |
| 형식·정본 위치 | `CANONICAL_DOCUMENT` · `docs/deliverables/04-design/user-experience-and-accessibility-design.md#des-17` |
| 보조 파일 | `docs/deliverables/04-design/design-traceability-register.json` |
| 완료·승인 기준 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: design token, 타이포·색·대비.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |
| 갱신 조건 | - 요구·아키텍처·API·DB·배포·보안 경계 변경<br>- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경<br>- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료 |
| 검토 주기 | 초안의 필수내용을 채운 때, 상위 요구·정책·설계 경계가 바뀐 때, 설계 기준선 승인 전에 검토한다. |
| 변경·대체·폐기 | 승인본을 덮어쓰지 않는다. 변경요청과 영향분석을 남겨 새 버전을 만들고, 대체된 버전은 Superseded로 표시한 뒤 정해진 보존기간이 끝나면 Archived로 옮긴다. |

쉽게 말하면, 이 표는 이 설계 산출물을 왜 만들고 누가 언제까지 무엇을 확인하며, 바뀌면 어떻게 새 버전으로 관리할지를 정한 약속이다.

### 추적과 판정 경계

- 입력: 현재 승인 정책 [`PB-WALKSAFE-FEATURE-POLICY-1.0.1`](../../control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json), [현재 유효 결정 등록부](../../control/decision-interview/walksafe-effective-decision-register-current-20260726-r001.json), [기존 답변](../../control/decision-interview/source-records/walksafe-feature-policy-comprehensive-review-20260719-answers.json), [FP-035 역사 pre-activation snapshot](../../control/decision-interview/walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json), 산출물 유형 작성계약.
- 정책 결정: 영역 `FA-02`, `FA-06`, `FA-09`, `FA-10`; 기능 `FP-004`, `FP-005`, `FP-006`, `FP-016`, `FP-027`, `FP-028`, `FP-029`, `FP-030`; 공통정책 없음; 흐름 `FLOW-02`, `FLOW-04`, `FLOW-05`, `FLOW-06`.
- 기존 답변 정규화: 없음; 적용 요구유형 없음. 해당 없는 산출물은 `없음`이다.
- 현재 정책 재승인 의존성: 없음. exact FP-035 overlay는 1.0.1에서 유효하며, 구현 적합성·정식 시험·남은 gate는 별도 검토한다.

- 정렬 결정: 등록부의 25건(`DEC-SAFETY-POSITION`, `DEC-USER-AGE`, `DEC-USE-ENVIRONMENT`, `DEC-CROSSWALK-SCOPE`, `DEC-PHONE-MOUNT`, `DEC-LANGUAGE-SCOPE`, `DEC-IBQ-030`, `DEC-TALKBACK-SCOPE`, `DEC-CAMERA-ONLY-WALK-VIEW`, `DEC-IBQ-039`, `DEC-IBQ-040`, `DEC-IBQ-043` 외 13건). 전체 목록과 해시는 추적 등록부에 있다.
- 요구예정 유형: [`REQ-11`](../03-requirements/system-requirements.md#req-11) (DRAFT_FILE_PRESENT_NOT_BASELINED).
- 요구예정 상세: 8건([`RQ-FP-004-001`](../03-requirements/system-requirements.md#RQ-FP-004-001), [`RQ-FP-005-001`](../03-requirements/system-requirements.md#RQ-FP-005-001), [`RQ-FP-006-001`](../03-requirements/system-requirements.md#RQ-FP-006-001), [`RQ-FP-016-001`](../03-requirements/system-requirements.md#RQ-FP-016-001), [`RQ-FP-027-001`](../03-requirements/system-requirements.md#RQ-FP-027-001), [`RQ-FP-028-001`](../03-requirements/system-requirements.md#RQ-FP-028-001), [`RQ-FP-029-001`](../03-requirements/system-requirements.md#RQ-FP-029-001), [`RQ-FP-030-001`](../03-requirements/system-requirements.md#RQ-FP-030-001)); 상태 `DRAFT_REFERENCE_PRESENT_NOT_BASELINED`.
- 현재 후보 근거: `SRC-ANDROID-MANIFEST`, `SRC-ANDROID-MAIN-ACTIVITY`, `SRC-ANDROID-STYLES`, `SRC-ANDROID-ROUTE`, `SRC-ANDROID-STEP`, `SRC-ANDROID-VOICE`, `SRC-ANDROID-REPORT-UPLOADER`, `SRC-WEB-PACKAGE`; 구현 적합성 `NOT_ASSESSED`.
- 이 절의 문서 상태는 `DRAFT`, 승인 `NOT_APPROVED`, 검증 `NOT_RUN`이다.


<a id="des-18"></a>
## DES-18 접근성 설계

### TalkBack·semantic

- 모든 입력·버튼·선택·오류·진행상태에 역할, 쉬운 이름, 현재값, 사용 가능 여부를 제공한다.
- focus 순서는 제목→핵심 상태→설명→행동 순으로 고정하고 비동기 갱신이 focus를 빼앗지 않게 한다.
- 위험 안내는 polite/live 영역을 구분하고, 중대한 위험만 높은 우선순위로 중단 안내한다. 같은 문장을 빠르게 반복하지 않는다.
- 카메라 preview 자체를 불필요하게 읽지 않고 현재 보행·위험·경로·기능수준을 별도 semantic status로 제공한다.
- 모달이 열리면 focus를 제목으로 옮기고 닫을 때 원래 논리 위치로 돌린다.

### 음성·진동·시각의 중복 전달

| 정보 | 음성 | 진동 | 화면 |
|---|---|---|---|
| 가까운 위험 | 짧은 행동 문장 | 위험 수준별 구별 패턴 | 종류·방향·제한상태 큰 글자 |
| 회전 안내 | 시점이 겹치지 않는 방향 문장 | 선택적 방향 패턴 | 다음 행동·거리 또는 거리 모름 |
| 경로 이탈 | 회전안내 중지 이유와 선택지 | 상태변경 패턴 | 세 선택지 |
| 안전정지 | 실패한 기능과 즉시 행동 | 중대 패턴 | 이유·재확인·종료 |
| 자동신고 후보·단순 queue 보류 | 후보별 알림 없음 | 없음 | 일반 사용자에게 노출 없음; 관리자 지표만 |

마지막 행은 오류를 숨기라는 뜻이 아니다. 실시간 안전기능이 신뢰되지 않으면 사용자에게 이유와 안전정지를 반드시 알린다.

### 동작·시간·입력 대안

- 음성 인식이 실패하면 반복 듣기, 제한된 명령 도움, TalkBack으로 조작 가능한 대안을 제공한다.
- 자동 timeout으로 중요한 동의·복구 선택을 닫지 않는다. 보안 session 만료는 이유와 안전한 재로그인 경로를 제공한다.
- drag·복잡한 gesture만 요구하지 않는다. 모든 기능에 단일 tap/표준 접근성 action 또는 음성 대안을 둔다.
- 동적 글꼴, 고대비, 색각 차이, 진동 비활성, TTS 속도에 견디도록 한다.

### 아직 실행하지 않은 검증

TalkBack 탐색, 실제 전맹·저시력 사용자 사용성, 소음환경 STT, TTS 겹침, 진동 구별, 48dp·대비·큰 글꼴, 지원 OS·기기, 현장 보행 안전시험은 모두 `NOT_RUN`이다. 설계 검토와 실제 기기 증거가 생기기 전에는 접근성 적합 또는 사용성 완료로 표시하지 않는다.

### 이 산출물의 작성·관리 기준

| 항목 | 현재 값 |
|---|---|
| 작성 목적 | 스크린리더·키보드·확대·음성·진동 사용자를 위한 구조와 대체 상호작용을 설계한다. |
| 필수/조건 | `REQUIRED` · 요구사항 기준선 승인 후 구현·통합 전에 활성 |
| 들어갈 내용 | - semantic 구조·landmark<br>- focus 순서·관리<br>- 레이블·상태 announcement<br>- 색 외 위험 전달<br>- 동작·시간 제한 대안<br>- 보조기기 시험 포인트 |
| 작성 입력 | - 승인된 요구사항 기준선과 RTM<br>- 현재 코드·OpenAPI·DB migration·배포 형상<br>- ADR 후보와 품질·보안·안전 제약 |
| 선행 → 후속 | `DES-15`, `DES-17`, `REQ-11` → `DEV-01`, `TST-14`, `WS-13`, `WS-14`, `WS-15` |
| 작성·검토·승인 | 기술책임자 · 제품책임자, 보안·개인정보책임자, QA책임자, 접근성·안전책임자, 독립기술검토자 · 프로젝트책임자 |
| 형식·정본 위치 | `SECTION` · `docs/deliverables/04-design/user-experience-and-accessibility-design.md#des-18` |
| 보조 파일 | `docs/deliverables/04-design/design-traceability-register.json` |
| 완료·승인 기준 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: semantic 구조·landmark, focus 순서·관리.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |
| 갱신 조건 | - 요구·아키텍처·API·DB·배포·보안 경계 변경<br>- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경<br>- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료 |
| 검토 주기 | 초안의 필수내용을 채운 때, 상위 요구·정책·설계 경계가 바뀐 때, 설계 기준선 승인 전에 검토한다. |
| 변경·대체·폐기 | 승인본을 덮어쓰지 않는다. 변경요청과 영향분석을 남겨 새 버전을 만들고, 대체된 버전은 Superseded로 표시한 뒤 정해진 보존기간이 끝나면 Archived로 옮긴다. |

쉽게 말하면, 이 표는 이 설계 산출물을 왜 만들고 누가 언제까지 무엇을 확인하며, 바뀌면 어떻게 새 버전으로 관리할지를 정한 약속이다.

### 추적과 판정 경계

- 입력: 현재 승인 정책 [`PB-WALKSAFE-FEATURE-POLICY-1.0.1`](../../control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json), [현재 유효 결정 등록부](../../control/decision-interview/walksafe-effective-decision-register-current-20260726-r001.json), [기존 답변](../../control/decision-interview/source-records/walksafe-feature-policy-comprehensive-review-20260719-answers.json), [FP-035 역사 pre-activation snapshot](../../control/decision-interview/walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json), 산출물 유형 작성계약.
- 정책 결정: 영역 `FA-02`, `FA-04`, `FA-05`, `FA-06`, `FA-09`, `FA-10`, `FA-11`, `FA-17`; 기능 `FP-004`, `FP-005`, `FP-006`, `FP-010`, `FP-013`, `FP-014`, `FP-016`, `FP-017`, `FP-018`, `FP-025`, `FP-027`, `FP-028`, `FP-029`, `FP-030`, `FP-033`, `FP-049`, `FP-050`; 공통정책 `NPC-AUTO-REPORT`, `NPC-PERMISSION-SESSION-LIFECYCLE`; 흐름 `FLOW-02`, `FLOW-03`, `FLOW-04`, `FLOW-05`, `FLOW-06`, `FLOW-07`, `FLOW-11`.
- 기존 답변 정규화: 없음; 적용 요구유형 없음. 해당 없는 산출물은 `없음`이다.
- 현재 정책 재승인 의존성: 없음. exact FP-035 overlay는 1.0.1에서 유효하며, 구현 적합성·정식 시험·남은 gate는 별도 검토한다.

- 정렬 결정: 등록부의 66건(`DEC-PRODUCT-RELEASE`, `DEC-SAFETY-POSITION`, `DEC-USER-AGE`, `DEC-USE-ENVIRONMENT`, `DEC-CROSSWALK-SCOPE`, `DEC-POOR-IMAGE-BEHAVIOR`, `DEC-PHONE-MOUNT`, `DEC-LANGUAGE-SCOPE`, `DEC-IBQ-013`, `DEC-IBQ-019`, `DEC-SIGNUP-DATA`, `DEC-IDENTITY-VERIFY` 외 54건). 전체 목록과 해시는 추적 등록부에 있다.
- 요구예정 유형: [`REQ-11`](../03-requirements/system-requirements.md#req-11) (DRAFT_FILE_PRESENT_NOT_BASELINED).
- 요구예정 상세: 21건([`RQ-FP-004-001`](../03-requirements/system-requirements.md#RQ-FP-004-001), [`RQ-FP-005-001`](../03-requirements/system-requirements.md#RQ-FP-005-001), [`RQ-FP-006-001`](../03-requirements/system-requirements.md#RQ-FP-006-001), [`RQ-FP-010-001`](../03-requirements/system-requirements.md#RQ-FP-010-001), [`RQ-FP-013-001`](../03-requirements/system-requirements.md#RQ-FP-013-001), [`RQ-FP-014-001`](../03-requirements/system-requirements.md#RQ-FP-014-001), [`RQ-FP-016-001`](../03-requirements/system-requirements.md#RQ-FP-016-001), [`RQ-FP-017-001`](../03-requirements/system-requirements.md#RQ-FP-017-001), [`RQ-FP-018-001`](../03-requirements/system-requirements.md#RQ-FP-018-001), [`RQ-FP-025-001`](../03-requirements/system-requirements.md#RQ-FP-025-001), [`RQ-FP-027-001`](../03-requirements/system-requirements.md#RQ-FP-027-001), [`RQ-FP-028-001`](../03-requirements/system-requirements.md#RQ-FP-028-001) 외 9건); 상태 `DRAFT_REFERENCE_PRESENT_NOT_BASELINED`.
- 현재 후보 근거: `SRC-ANDROID-MANIFEST`, `SRC-ANDROID-MAIN-ACTIVITY`, `SRC-ANDROID-STYLES`, `SRC-ANDROID-ROUTE`, `SRC-ANDROID-STEP`, `SRC-ANDROID-VOICE`, `SRC-ANDROID-REPORT-UPLOADER`, `SRC-WEB-PACKAGE`; 구현 적합성 `NOT_ASSESSED`.
- 이 절의 문서 상태는 `DRAFT`, 승인 `NOT_APPROVED`, 검증 `NOT_RUN`이다.


## 검토자가 특히 확인할 질문

- 첫 실행의 긴 동의 내용을 TalkBack으로 빠짐없이 듣되 피로를 줄이는 순서가 적절한가?
- 정상 보행에 화면 버튼이 없어도 일시중지·종료·도움 요청을 안전하게 할 수 있는가?
- 거리 제한모드와 전체 기능모드를 사용자가 오해하지 않는가?
- 경로 이탈 뒤 선택권이 명확하고 TMAP을 자동 재호출하지 않는가?
- 조용한 자동신고·용량 보류 정책과 안전 핵심 오류 고지가 확실히 구분되는가?
- 별도 관리자 앱이 사용자 앱과 완전히 다른 역할·접근성 흐름을 갖는가?
