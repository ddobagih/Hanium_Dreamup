# WalkSafe 기능 정책 검토·결정 인터뷰

이 디렉터리는 원본 286개 답변, Android 후속 75개 항목과 기존 144개 통합 감사 질문을 버리지 않고 내부 근거로 보존하면서, 사용자가 실제로 검토할 자료를 더 짧고 명확하게 제공한다.

## 지금 할 일

정책 기준선 승인은 완료됐다. 현재 정책 정본은 불변 1.0.0과 승인된 FP-035 정정 overlay를 합성한 `PB-WALKSAFE-FEATURE-POLICY-1.0.1`이다. 현재 결정 정본은 [1.0.0 정렬 원장](walksafe-effective-decision-register-aligned-20260721-r001.json) 135개에 [FP-035 compact successor](walksafe-effective-decision-register-current-20260726-r001.json)의 `CD-UPLOAD-NETWORK` replacement 한 건을 적용한 합성 결과다. 아래 1.0.0 승인문은 다시 제출할 문장이 아니라 이미 접수·검증된 역사 근거 원문이다.

```text
검토 종결 WS-FEATURE-POLICY-BASELINE-REVIEW-RESOLUTION-20260721-001 버전 1.0.0, 결정 지문 16064cabf95dc16109bfe315032905a12881cab40cf7730e97d8171d15476538을 근거로 문서 WS-FEATURE-POLICY-DRAFT-20260720 버전 1.0.0, 내용 지문 e49ffe0ee9e59de0cec173cac9425d61aa78e0ccd65980ba568045a3975e0b28를 WalkSafe 기능 정책 기준선 1.0.0으로 승인하며, 5개 미실행 검증 항목은 면제하지 않고 출시 상태는 NOT_ELIGIBLE로 유지한 채 0~6 정식 산출물 작성을 시작하도록 승인합니다.
```

- [불변 승인 기록](../baselines/walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json)
- [정책 1.0.0 기준선 manifest](../baselines/walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json)
- [현재 정책 1.0.1 합성 manifest](../baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json)
- [현재 결정 compact successor](walksafe-effective-decision-register-current-20260726-r001.json)
- [승인 적용 COMMITTED receipt](../baselines/walksafe-artifact-baseline-application-receipt-20260722-r001.json)
- [0~6 Draft 최상위 안내](../../deliverables/README.md)
- [OPEN 위험·변경요청](../../deliverables/01-management/project-control-registers.md#mgt-14)

현재 할 일은 승인된 정책 1.0.1에 맞춰 산출물·구현·시험을 정합화하는 것이다. 남은 검증 5개는 계속 `NOT_RUN`·미면제이고 출시는 `NOT_ELIGIBLE`이다. FP-035 후보 파일 내부의 `NOT_APPROVED / NOT_EFFECTIVE`는 승인 전 불변 snapshot이며, 후행 일괄 승인 기록과 유효한 COMMITTED receipt가 현재 효력에서 우선한다. 이 합성은 구현 적합성이나 시험 완료를 주장하지 않는다.

승인 대상 파일 이름에 `DRAFT`가 남는 이유는 사용자가 실제로 읽은 원문을 승인 뒤 몰래 바꾸지 않기 위해서다. 승인 후에는 별도 불변 승인 기록과 기준선 manifest가 이 원문의 내용 지문을 `정책 기준선 1.0.0`으로 승격하며, 그 두 파일이 현행 정본이 된다. 검토용 draft는 근거 원본으로 보존한다.

## 이번 단계에서 바꾼 방식

1. 기존 답변과 감사 질문은 수정하지 않고 근거 자료로 보존한다.
2. 이미 확정으로 분류된 결정도 원문 근거를 다시 확인하고, 실제 정책문이 확인된 것만 54개 기능 정책에 승계한다.
3. 144개 항목을 제품책임자 결정, 기술 제안, 측정 후 확정, 전문가 검토, 생성 증거, 이미 확정으로 한 번씩 분류한다.
4. 제품책임자가 선택해야 하는 중복 없는 결정만 10개 질문으로 제시한다.
5. 한 답변을 여러 산출물 유형과 문서 묶음에 연결해 문서마다 같은 질문을 반복하지 않는다.

## 파일 역할

| 파일 | 역할 | 편집 원칙 |
|---|---|---|
| `walksafe-feature-policy-baseline.json` | 현재 답변을 합쳐 기능별 목적·작동 순서·안내·권한·단말/서버·데이터·장애·구현 상태를 정리한 기준 데이터 | 정책 설명을 수정할 때 편집 |
| `walksafe-decision-responsibility.json` | 기존 IBQ-001~144를 누가 어떤 방식으로 닫을지 분류한 책임 원장 | 원 질문은 정확히 한 canonical 결정에만 연결 |
| `walksafe-owner-decision-questions.json` | 이미 확정된 값과 기술·실측 항목을 뺀 10개 제품책임자 질문 | 질문 하나에 결정 하나, 추천은 자동 선택 금지 |
| `feature-policy-review-template.html` | 기능 정책 검토·수정과 남은 질문 UI | 화면 동작을 수정할 때 편집 |
| `walksafe-feature-policy-and-decision-review-20260718.html` | 사용자가 여는 독립 실행 HTML | 생성물이므로 직접 수정 금지 |
| `walksafe-feature-policy-decisions-20260718-answers.json` | 사용자가 제출한 10개 답변의 byte-for-byte 통제 사본 | 내용을 고치지 않고 새 답변은 새 버전으로 등록 |
| `walksafe-effective-baseline-rules.json` | 답변 선택값을 기능 정책문·영향 기능·검토 항목으로 변환하는 명시 규칙 | 해석을 바꿀 때 근거와 함께 편집 |
| `walksafe-canonical-decision-trace.json` | 135개 canonical 결정·gate와 영향 기능의 추적 관계 | 원 결정의 의미와 연결 기능을 독립 검토한 기록 |
| `review-fragments/walksafe-decision-trace-*.json` | 135개 추적 관계를 45개씩 나눠 독립 검토한 원기록 3개 | trace에 상대경로·SHA-256을 기록하고 생성 때 범위를 재검증 |
| `walksafe-effective-decision-register.json` | 10개 답변, 기존 확정 근거, 기술·실측·전문가·증거 gate를 합친 유효 결정 원장 후보 | 생성물이므로 직접 수정 금지 |
| `walksafe-feature-policy-effective-candidate.json` | 54개 기능에 유효 결정과 미결 항목을 반영한 정책 후보 | 생성물이므로 직접 수정 금지 |
| `walksafe-effective-policy-approval-review-20260719.md` | 1~6단계 통합 결과와 추적성을 보존한 이전 요약 검토서 | 생성물이므로 직접 수정 금지 |
| `comprehensive-report-fragments/walksafe-policy-proposals-fp-*.json` | 54개 기능의 입력·출력·설계규칙·금지사항·시험과 151개 세부 추천안을 18개씩 나눈 검토 원본 | 원문 미결 항목과 정확히 연결해 편집 |
| `feature-policy-comprehensive-template.html` | 기능별 종합보고서의 독립 실행 HTML 구조와 검토 UI | 화면 구조·가독성·검토 동작을 수정할 때 편집 |
| `walksafe-feature-policy-comprehensive-proposals.json` | 54개 기능별 151개와 기능 간 공통 15개를 합친 166개 쉬운 질문·추천안의 기계 판독 자료 | 생성물이므로 직접 수정 금지 |
| `walksafe-feature-policy-comprehensive-review-20260719.html` | 비전공자가 기능별 전체 정책을 읽고 진행·수정·보류를 기록하는 주 검토보고서 | 생성물이므로 직접 수정 금지 |
| `source-records/walksafe-feature-policy-comprehensive-review-20260719-answers.json` | 전체·공통·기능 정책 76개에 대한 최종 답변의 byte-for-byte 통제 사본 | 내용을 고치지 않고 재검토본은 새 버전·새 hash로 등록 |
| `source-records/walksafe-feature-policy-comprehensive-review-20260719-answers.intake.json` | 외부 수령 위치, 통제 경로, 답변 hash·버전·변경 이유·보고서 결속과 승인 경계를 기록한 반입 manifest | Downloads 경로는 provenance로만 사용 |
| `walksafe-feature-policy-review-resolution-rules.json` | 후속 메모 우선순위, 9개 공통 상수, 6개 cascade, 기존 충돌 대체와 남은 gate를 구조화한 적용 규칙 | 정책 해석·연쇄 범위를 바꿀 때 근거와 함께 편집 |
| `walksafe-feature-policy-review-resolution.json` | 답변 76개를 54개 기능에 직접·상속·cascade로 연결한 다음 작성 단계 입력 | 생성물이므로 직접 수정 금지, 기준선·정식 산출물이 아님 |
| `walksafe-feature-policy-document-rules.json` | 후속 확정과 충돌한 문장의 억제·교체, 26개 기능 보조설명 교정, 문서 승인 경계를 고정한 작성 규칙 | 정책 문장을 바꿀 때 근거와 함께 편집 |
| `feature-policy-document-template.html` | 검색·필터·인쇄와 63개 검토 입력·자동저장·JSON 입출력을 제공하는 독립 실행 문서 틀 | 화면 동작과 접근성을 바꿀 때 편집 |
| `walksafe-feature-policy-comprehensive-draft.json` | 54개 기능의 정상 흐름·실패 복구·권한·실행 경계·자료 생명주기·시험·추적을 합친 기계 판독 정책 초안 | 생성물이므로 직접 수정 금지 |
| `walksafe-feature-policy-comprehensive-draft-20260720.html` | 비전공자가 공통 정책 9개와 기능 54개를 읽고 확정·수정·보류를 남긴 주 검토 문서 | 생성물이므로 직접 수정 금지. 파일명은 DRAFT지만 승인 기록·manifest가 이 내용 지문을 정책 기준선 1.0.0으로 결속함 |
| `source-records/walksafe-feature-policy-baseline-review-20260720-r001-answers.json` | 공통정책 9개와 기능 54개의 최종 검토 답변을 바이트 그대로 보존한 최초 통제 사본 | 내용을 고치지 않고 재접수본은 새 revision·새 hash로 등록 |
| `source-records/walksafe-feature-policy-baseline-review-20260720-r001-answers.intake.json` | 외부 수령 위치, 통제 경로, 답변·검토 문서 지문과 승인 경계를 기록한 반입 manifest | Downloads 경로는 provenance로만 사용, 검토 완료를 기준선 승인으로 바꾸지 않음 |
| `walksafe-feature-policy-baseline-review-resolution-20260721-r001.json` | 63개 최종 검토 결과를 원문 변경 없이 고정하고 별도 기준선 승인에 넘기는 검토 종결 기록 | 생성물이므로 직접 수정 금지, 승인 기록이나 정식 산출물이 아님. 후속 접수는 r002 새 파일과 선행 지문으로 연결 |

상세 원문 286개, Android 후속 75개와 감사 질문 144개는 `../questionnaire/`에 그대로 보존한다. 새 HTML은 이를 생략하거나 폐기한 자료가 아니라, 중복 질문을 줄인 1차 사용자 검토 화면이다.

## 상태의 의미

- `QUESTIONNAIRE_COMPLETE`: 활성 필수 질문과 기능 수정 요청을 형식상 작성했다.
- `REVIEW_COMPLETE_ALL_CONFIRMED`: 63개 정책 문장이 그대로 맞다고 검토했다. 승인자·승인시각·승인 기록은 아직 없다.
- `BASELINE_APPROVED`: 정책 선택을 동결하고, 아직 실행하지 않은 검증은 후속 차단조건으로 명시한 채 프로젝트 관리자가 문서 기준선을 승인했다.
- `VALIDATION_GATES_CLOSED`: 기준선에 기록한 실측·독립 검토·복구훈련을 끝내고 증거를 연결했다.
- `RELEASE_ELIGIBLE`: 구현·시험·법률·안전·운영 출시 조건까지 모두 통과했다.

`정책 검토 완료 → 정책 기준선 승인 → 남은 검증 완료 → 출시 가능`은 서로 다른 단계다. 앞 단계가 뒤 단계를 자동으로 뜻하지 않는다.

현재 상태는 `QUESTIONNAIRE_COMPLETE`, `REVIEW_COMPLETE_ALL_CONFIRMED`, `BASELINE_APPROVED`, `NOT_ELIGIBLE`이다. 최종 검토 63개와 정책 기준선 승인은 완료됐지만, 0~6 Draft·구현·시험·출시는 별도 검토·승인 대상이다.

## 생성·검증

저장소 루트에서 실행한다.

```bash
python3 -B scripts/build_walksafe_decision_interview.py
python3 -B scripts/build_walksafe_decision_interview.py --check
python3 -B -m unittest tests.test_walksafe_decision_interview

python3 -B scripts/build_walksafe_effective_baseline.py
python3 -B scripts/build_walksafe_effective_baseline.py --check
python3 -B -m unittest tests.test_walksafe_effective_baseline

python3 -B scripts/build_walksafe_feature_policy_report.py
python3 -B scripts/build_walksafe_feature_policy_report.py --check
python3 -B -m unittest tests.test_walksafe_feature_policy_report

python3 -B scripts/build_walksafe_feature_policy_resolution.py
python3 -B scripts/build_walksafe_feature_policy_resolution.py --check
python3 -B -m unittest tests.test_walksafe_feature_policy_resolution

python3 -B scripts/build_walksafe_feature_policy_document.py
python3 -B scripts/build_walksafe_feature_policy_document.py --check
python3 -B -m unittest tests.test_walksafe_feature_policy_document

python3 -B scripts/build_walksafe_feature_policy_baseline_review_20260721.py
python3 -B scripts/build_walksafe_feature_policy_baseline_review_20260721.py --check
python3 -B -m unittest tests.test_walksafe_feature_policy_baseline_review
```

생성기는 다음을 차단한다.

- 54개 기능의 영역·상태·출처·산출물 연결 누락
- IBQ 144개의 누락·중복 분류
- 이미 확정된 항목의 재질문
- 한 canonical 결정을 여러 번 묻는 질문
- 존재하지 않는 산출물 257종 또는 문서 묶음 40종 참조
- 질문 조건의 잘못된 참조·순환
- 입력 hash와 다른 오래된 HTML
- JSON 임베딩을 통한 script 종료 문자열 삽입

유효 기준선 생성기는 추가로 다음을 차단한다.

- 통제 답변·원본·delta의 hash 불일치
- 135개 결정·gate 또는 54개 기능의 고아 연결
- 허용되지 않은 선택값, gate 상태, 출처 분류와 자기 승인
- 명시 규칙에 없는 사용자 답변 해석과 기능 patch
- 미결 항목을 숨긴 기준선 승인 또는 출시 가능 표시

종합 검토 반영 생성기는 추가로 다음을 차단한다.

- 통제 답변·intake·proposals·이전 후보의 경로 또는 hash 불일치
- GP 7개·SP 15개·FP 54개의 누락·중복과 저장된 검토 요약의 오류
- `revise` 또는 `hold`의 빈 이유와 `accept + 후속 메모` 누락
- 서버 300GiB 기준을 휴대폰에 적용하거나 용량·보존·경로 상수의 산술이 달라지는 변경
- 존재하지 않는 기능·검토·공통 상수의 cascade 연결
- 검토 완료를 기준선 승인·출시 가능·구현 검증으로 잘못 승격하는 변경

기능별 종합 정책서 생성기는 추가로 다음을 차단한다.

- 54개 기능·9개 정규화 공통정책·22개 이미 검토한 전체/공통정책·5개 gate의 누락
- 과거 권한 전면종료·자동 재탐색·후보별 자동신고 알림·서버/휴대전화 용량 혼용·0원 저장비 문구의 재등장
- 정상 흐름·실패/복구·현재 자료 처리·증거 대기 중 임시 안전규칙의 누락
- 76개 검토 적용기록, 135개 결정과 428개 영향 기능 연결의 고아·허위 참조
- 원천 파일 경로·지문, 공통정책, gate, 승인 경계, 구현상태를 다시 봉인한 위조
- 검토 전 문장 배열 순서가 바뀌어 잘못된 문장을 억제·교체하는 경우

최종 검토 종결 생성기는 추가로 다음을 차단한다.

- 답변 63개의 누락·중복·순서 변경, 허용되지 않은 결정값과 수정·보류 이유 누락
- 반입 manifest에 기록한 외부 원본 지문과 통제 사본의 불일치 또는 다른 정책서 답변의 혼입
- 검토된 JSON·HTML, 반입 manifest와 생성기의 경로·지문 변경
- 63개 전부 확정인데 정책 본문을 불필요하게 다시 만들어 검토 지문이 바뀌는 처리
- 검토 완료를 기준선 승인·검증항목 면제·출시 허가·0~6 산출물 작성 완료로 바꾸는 처리

## 현재 승인·변경 방법

정책 기준선 1.0.0의 명시적 승인과 FP-035 exact overlay의 일괄 승인은 각각 불변 승인 기록에 결속됐다. 유효한 COMMITTED receipt를 전제로 현재 정책은 1.0.1이며 같은 승인문을 다시 제출하지 않는다.

추가로 정책 뜻을 바꾸려면 대상 정책·변경 전후 문장·영향 요구/설계/시험·변경 이유를 새 변경요청에 기록하고, 기존 1.0.0·1.0.1과 승인 후보를 덮어쓰지 않은 새 revision에서 다시 검토·승인한다. 현재 FP-035 전송망 정정은 승인·효력 발생을 마쳤고 구현·시험 정합화가 남았다.

기존 승인은 정책 기준선과 0~6 정식 산출물 작성 시작만 허가했다. 남은 검증항목 5개를 면제하거나 구현·시험 완료 또는 출시 가능 상태를 뜻하지 않는다.

`미실행 검증 항목(gate)`은 정책을 다시 고르는 질문이 아니라, 정한 정책이 실제로 가능한지 증거를 만들 때까지 특정 다음 단계를 막는 확인 작업이다. `NOT_RUN`은 아직 증거가 없다는 뜻이고 `NOT_ELIGIBLE`은 아직 출시할 수 없다는 뜻이다.

| 남은 확인 | 해야 할 일 | 완료 전 막는 단계 |
|---|---|---|
| 휴대전화 대기자료 용량 | 지원 기기별 저장공간과 자료 크기를 재서 바이트 상한 확정 | 지원 기기별 용량 기준, TST-22 출시 준비도 승인 |
| 서버 용량상태 전달 규칙 | 조회주기·유효시간·오프라인 동작을 정하고 통합시험 | 관련 연동 통합시험 완료, TST-22 승인 |
| 무가림 원본수집 독립 검토 | 고지·동의·권리행사 절차를 독립 검토하고 필요한 변경 재승인 | 원본수집 출시 적합 판정, REL-02 릴리스 승인 |
| 실제 클라우드 저장비 | 실제 저장·요청·복원 비용이 월 30,000원 안인지 측정 | 운영비 기준 확정, TST-22 승인 |
| 관리자 휴대전화 분실 복구 | 외부 복구수단·기기 세션 폐기·작업 동결·복구를 실제 훈련 | 실제 사용자시험 시작, REL-02 승인 |

정책 기준선을 승인한 뒤에는 위 5개가 남아 있어도 0~6 정식 산출물 작성과 통제된 개발·시험 준비를 진행할 수 있다. 다만 표의 차단 단계와 출시는 통과할 수 없다.

## 현재 통합 결과

현재 정책 선택 미결은 0개다. 남은 대표 검증은 위 5개이며, 기능별 구현·시험 증거 대기 105개는 산출물 작성 중 근거를 채울 작업이다. 이 숫자들은 사용자가 다시 답해야 할 110개 질문이라는 뜻이 아니다.

### 현재 유효 상태

- 최종 검토 63개: `confirm` 63개, `revise` 0개, `hold` 0개로 통제 반입됨
- 정책 본문 재생성 없음: 내용 지문 `e49ffe0ee9e59de0cec173cac9425d61aa78e0ccd65980ba568045a3975e0b28` 유지
- 대표 후속 검증 5개: 모두 `NOT_RUN`, 정책 기준선 승인 뒤에도 면제되지 않음
- 기능별 구현·시험 증거 대기 105개, 기존 구현 근거 재검증 대상 기능 50개

### 이전 단계의 추적 수치

아래 수치는 최종 정책을 만들기까지 어떤 입력을 합쳤는지 보존한 이력이다. 당시의 기술 제안 54개·측정 21개·독립 검토 16개·생성 증거 2개와 세부사항 151개는 최종 문서에서 정책 본문, 대표 gate 5개, 기능별 증거 대기 105개로 재분류됐으며 별도의 현재 사용자 질문 수가 아니다.

- 제품책임자 질문 10개: 모두 유효하게 접수되어 결정 후보에 반영됨
- 종합 검토 76개: `accept` 54개, `revise` 22개, `hold` 0개로 통제 반입됨
- 비어 있지 않은 메모 37개: 2026-07-20 후속 확정 35개와 일반 검토 메모 2개를 모두 보존함
- 직접 바뀐 기능 31개, cascade 영향 38개와 수용된 현행변경 제안을 함께 계산해 총 50개 기능의 기존 구현 근거를 `REVALIDATION_REQUIRED`로 표시함
- 서버·휴대폰 용량, 원본 생명주기, 자동신고, 권한·세션, 경로·방향, 단일 관리자 복구를 6개 공통 cascade로 고정함
- 휴대폰 바이트 상한, 서버 용량상태 계약, 원본수집 독립 검토, 실제 클라우드 비용, 단일 관리자 복구훈련은 `NOT_RUN` gate 5개로 분리함
- 기존 `already_confirmed` 32개: 실제 정책 근거가 확인된 27개는 승계, 잘못 분류된 5개는 출처 정규화 대기
- 별도 미결 queue: 기술 제안 54개, 측정 21개, 독립 전문가 검토 16개, 생성 증거 2개
- 이전 기능 정책 후보 54개: `CONFIRMED_WITH_OPEN_DETAILS` 35개, `CONDITIONAL` 18개, `CONFLICTING` 1개였음
- 새 resolution: FP-053의 비용·용량 정책 충돌은 해소하고 실제 비용 측정 gate는 유지함. 기존 후보 본문은 아직 다시 만들지 않음
- 기능별로 명시된 미결 세부사항: 151개
- 여러 기능에 반복 연결된 결정 추가 확인: 33개를 중복 없이 공통 질문 15개로 정리
- 새 종합 정책 초안: 정상 흐름·실패/복구·자료 생명주기와 105개 증거 대기 항목의 임시 안전규칙을 54개 기능에 표시
- 과거 문장 67개는 비활성 처리하고 활성 충돌문장 24개는 후속 결정에 맞는 쉬운 문장으로 교체
- GP 7개·SP 15개는 적용 내역으로 보존하고, 새 검토 입력은 후속 확정으로 정규화한 공통정책 9개와 기능 54개만 대상으로 함

숫자가 많아 보여도 사용자가 모든 기술 수치를 직접 정해야 한다는 뜻은 아니다. 기술 설계, 시험 측정, 전문가 확인과 증거 생성처럼 담당자가 다른 작업도 기능별로 드러내고, 보고서에는 추천안과 확정 방법을 함께 적었다.

## 다음 단계

현재 compact successor로 정책 1.0.1과 135개 결정의 합성 관계를 검증한 뒤, 요구·설계·구현·시험의 FP-035 연결을 동일한 규칙으로 정합화한다. 새 정책 결정이 없는 한 기존 정책 승인 질문을 다시 만들지 않는다.

승인 후에는 별도 불변 승인 기록과 기준선 manifest를 현행 정본으로 지정하고, 135개 결정대장을 승인 정책과 맞춘다. 그 다음 REQ-16 요구사항 추적 원장의 골격을 먼저 개설하고, 0~6(산출물 통제, 프로젝트 관리, 제품 기획, 요구사항, 아키텍처·상세설계, 구현·빌드, 시험·품질검증) 문서를 작성하면서 요구사항·설계·코드·시험 연결을 채운 뒤 각 문서 기준선에서 추적 누락을 검사한다.
