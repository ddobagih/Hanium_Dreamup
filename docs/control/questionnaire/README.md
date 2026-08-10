# WalkSafe 프로젝트 의사결정 질문지

이 질문지는 기존 문서의 충돌과 오염 가능성을 사용자 결정으로 해소하고, 제품·정책·스펙·구현 경계·완료 기준을 새 문서 작성 전에 확정하기 위한 도구다.

> 현재 운영 방식: 이 디렉터리의 원본 286개, Android 후속 75개와 통합 감사 144개는 상세 내부 근거로 보존한다. 사용자가 우선 검토할 기능별 쉬운 설명과 중복을 제거한 10개 제품책임자 질문은 `../decision-interview/`에서 관리한다.

## 파일 역할

| 파일 | 역할 |
|---|---|
| `walksafe-project-decision-questions.json` | 질문·선택지·추천·근거·의존성·충돌 규칙의 기준 데이터 |
| `source-records/walksafe-project-decisions-20260717-answers.json` | 원본 286개 답변의 byte-for-byte 통제 사본 |
| `source-records/walksafe-android-baseline-delta-review.json` | Android 후속 75개 답변 delta의 byte-for-byte 통제 사본 |
| `source-conflict-audit.md` | 질문을 만든 코드·설정·문서 충돌 근거 |
| `questionnaire-template.html` | UI와 동작의 원본 template |
| `walksafe-project-decision-questionnaire.html` | 사용자가 실행하는 생성된 단일 HTML |
| `walksafe-answer-review-analysis.json` | 제출 답변의 16개 영역별 의미·영향·불일치와 검토 flag |
| `walksafe-answer-review-followups.json` | Android 전환 뒤 필요한 75개 추가 결정, 적용 조건, 교차 충돌·상세 근거 규칙 |
| `answer-review-template.html` | 답변 검토와 보완 답변 UI의 원본 template |
| `walksafe-project-decision-answer-review-20260718.html` | 기존 286개 답변을 검토하고 보완 delta를 작성하는 단일 HTML |
| `walksafe-integrated-baseline-analysis.json` | 원본·delta·최신 사용자 설명을 합친 16개 분야 정의, 충돌, 실제 런타임 근거 |
| `walksafe-integrated-baseline-questions.json` | 남은 모호성을 기능별로 닫는 쉬운 최종 질문과 교차 충돌 규칙 |
| `integrated-baseline-template.html` | 통합 정의·전체 근거·새 질문 UI의 원본 template |
| `walksafe-integrated-baseline-questionnaire-20260718.html` | 현재 정의를 읽고 새 답변을 내보내는 독립 실행 HTML |

생성된 HTML은 직접 수정하지 않는다. 질문은 JSON을, 화면 동작은 template 또는 builder를 수정한 뒤 다시 생성한다.

## 재생성·검증

저장소 루트에서 canonical JSON 또는 template을 수정한 뒤 아래 순서로 실행한다.

```bash
python3 -B scripts/build_walksafe_project_questionnaire.py
python3 -B scripts/build_walksafe_project_questionnaire.py --check
python3 -B scripts/validate_walksafe_document_preparation.py
python3 -B -m unittest tests.test_walksafe_document_preparation tests.test_walksafe_project_questionnaire
```

첫 명령만 생성 HTML을 갱신한다. 나머지는 생성물 최신성, 257개 산출물·질문·추적 무결성, 안전한 JSON 임베딩과 standalone 계약을 검사한다.

제출 답변 검토 HTML은 저장소 안의 통제 사본을 기본 입력으로 생성·검증한다.

```bash
python3 -B scripts/build_walksafe_answer_review.py
python3 -B scripts/build_walksafe_answer_review.py --check
python3 -B -m unittest tests.test_walksafe_answer_review
```

검토 생성기는 답변 schema·질문 세트 hash·286개 답변·원본 충돌을 다시 계산해 검증한다. 분석·추가 질문 설정도 strict JSON으로 검사하며 통제 사본은 수정하지 않는다. Downloads 같은 개인 임시 경로는 재현 가능한 입력으로 사용하지 않는다.
현재 검토 기준은 추가 질문 75개, 조건부 비활성 규칙 12개, 교차 모순 차단 규칙 64개, 구체값 필수 규칙 36개이며 독립 의미 감사에서 blocker·high 누락 0건을 확인했다.

원본 답변과 Android delta를 합친 최종 확인 HTML은 아래처럼 생성한다.

```bash
python3 -B scripts/build_walksafe_integrated_baseline.py
python3 -B scripts/build_walksafe_integrated_baseline.py --check
python3 -B -m unittest tests.test_walksafe_integrated_baseline
```

통합 생성기는 원본 286개, Android 추가질문 75개, 두 입력의 hash 결속과 delta의 활성·메모·충돌 상태를 다시 계산한다. 최신 사용자 설명은 기존 선택을 자동 덮어쓰지 않고 `확정`, `조건부 희망`, `충돌`, `출시 검토 필요`로 나눠 표시한다. 새 질문의 완료는 후속 문서 작성 검토가 가능하다는 뜻이며 앱 구현·실기기 시험·정식 출시 완료를 뜻하지 않는다.

`source-records/`의 파일은 외부에서 받은 답변을 그대로 보존하는 입력 기록이다. 내용을 고치지 말고, 수정 답변은 새 파일·새 hash·새 변경이력으로 등록한다.
`walksafe-integrated-baseline-analysis.json`의 `source_materials.path`에는 최초 수령 위치가 provenance로 남아 있을 수 있다. 생성기는 알려진 source ID를 저장소 통제 사본에 매핑해 hash를 검사하며, 생성 보고서에는 저장소 상대경로를 표시하므로 개인 Downloads 경로의 존재 여부가 결과를 바꾸지 않는다.

HTML을 배포하거나 질문 기준선을 바꿀 때는 데스크톱과 모바일 viewport에서 다음 browser gate도 수행한다.

- 전체 질문·범주 수, 검색·범주·미응답·충돌·검토 필터 확인
- 답변·결정 메모 입력 후 reload 복원과 답변 지우기 확인
- 비추천안의 메모 gate와 의도적으로 만든 충돌의 완료 차단 확인
- 정상 JSON import/export, 다른 hash·잘못된 형식 거부, 기존 답변 덮어쓰기 취소 확인
- 초기화 확인창, 키보드 focus, skip link, 고대비·reduced motion·모바일 layout 확인
- console runtime error와 외부 network request 0건 확인

## 사용 방법

1. `walksafe-project-decision-questionnaire.html`을 최신 브라우저에서 연다.
2. 범주별 설명과 각 질문의 필요 이유를 읽는다.
3. 추천안은 참고하되 원하는 답을 직접 선택하거나 입력한다.
4. 비추천안·사용자 정의·수치 답변을 선택하거나 경고를 수용하면 결정 메모에 구체 값·예외·근거·책임자를 적는다.
5. 필수 미응답, 선행 질문 누락, 결정 메모 누락과 답변 충돌이 없어질 때까지 검토한다.
6. 중간 작업은 브라우저에 자동 저장되지만 주기적으로 JSON으로 내보낸다.
7. 다른 브라우저나 기기에서는 같은 질문 세트·hash·답변 스키마의 JSON만 가져온다.
8. 최종 JSON을 별도 검토한 뒤 제품 결정 기준선으로 승인한다.

## 완료의 의미

질문지의 `완료`는 문서 작성에 필요한 결정 입력이 채워졌다는 뜻이다. 코드 구현·시험·현장 검증·출시가 완료됐다는 뜻이 아니다.

완료 조건은 다음 두 가지다.

- 필수 질문 응답률 100%
- 오류 수준 답변 충돌·선행 질문 누락·필수 결정 메모 누락 0건

경고는 결정을 막지 않을 수 있지만, 문서 작성 전에 모두 검토하고 수용 사유를 남긴다.

## 제출 답변 검토 방법

1. `walksafe-project-decision-answer-review-20260718.html`을 열어 영역별로 선택값, 그 의미, 현재 구현과의 차이, Android 전환으로 대체할 항목을 확인한다.
2. 원 질문의 필수 결정 메모 오류 102개에 구체 값·예외·근거·책임자를 보완한다.
3. 추가 질문은 P0부터 답한다. 추천안은 표시만 되고 자동 선택되지 않는다.
4. 선택에 따라 적용되지 않는 질문은 자동으로 비활성화된다. 예를 들어 단말에서 추론만 하면 단말 학습 세부 질문은 완료 조건에서 제외된다.
5. 상세 수치·역할·단말·보존기간이 필요한 문항은 선택과 함께 근거 입력을 채운다.
6. 필수 활성 질문, 필수 근거, 원 질문 보완과 교차 충돌을 모두 해소한 뒤 보완 delta JSON을 내보낸다.
7. 원본 답변과 delta를 함께 검토·승인한 뒤에만 Android 제품 결정 기준선과 후속 문서를 작성한다.

검토 화면의 완료는 원본 답변이 자동 수정되었거나 제품 구현·시험·출시가 끝났다는 뜻이 아니다. 내보낸 delta는 원본 hash와 질문·분석·추가 질문 hash에 결속된 별도 검토 자료다.

## 답변 데이터 주의사항

- 비밀번호, API key, token, 인증서 private key를 입력하지 않는다.
- 실제 사용자 이름, 연락처, 정밀 위치, 영상·음성 원본 같은 개인정보를 입력하지 않는다.
- 답변 JSON은 제품 정책과 운영 구조를 포함할 수 있으므로 공개 여부를 별도로 판단한다.
- 브라우저 저장소 삭제, 시크릿 모드 종료, 브라우저 교체 시 자동 저장 답변을 잃을 수 있으므로 JSON 내보내기를 사용한다.
- 답변 파일은 `walksafe.questionnaire-answers.v2` 형식이며 질문 세트 hash가 다르면 가져오지 않는다.
- 질문 JSON을 바꾸기 전에는 기존 답변을 먼저 내보낸다. 질문 세트 hash가 바뀌면 이전 브라우저 저장값은 새 HTML에 자동 승계되지 않으므로, 백업을 확인한 뒤 브라우저의 해당 파일/사이트 데이터를 정리한다.

## 답변 이후 절차

1. JSON schema와 질문 세트 식별자를 검증한다.
2. 충돌·미응답·자유 입력의 모호한 표현을 검토한다.
3. 답변을 제품 결정 원장과 변경요청에 반영한다.
4. 제품·요구사항·설계·시험 산출물을 의존성 순서로 작성한다.
5. 각 문서가 어떤 질문 답변을 사용했는지 trace link를 남긴다.
