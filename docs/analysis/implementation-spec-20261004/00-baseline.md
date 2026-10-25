# 분석 기준과 증거 해석

[전체 목차](README.md) · [원본 파일 해시](evidence/source-manifest.json) · [기존 942개 항목 대응](evidence/legacy-item-mapping.csv)

이 문서는 **현재 작업트리를 고정한 정적 구현 명세**다. 요청에 전제된 전체 DONE 진행표와 최종 통합 커밋은 확인되지 않았다. 발견한 코드·원장·문서 차이를 보존하며 상태를 승격하거나 제품 문제를 수정하지 않는다.

## 고정 기준

| 항목 | 확인값 |
|---|---|
| 실제 저장소 | `/home/ddobagi/Hanium_Dreamup` |
| 앱에 등록된 프로젝트 경로 | `/home/ddobagi/ChatGPT/한이음 프로젝트` — 실행 환경에 해당 디렉터리 없음 |
| 브랜치 | `temp` |
| HEAD | `2e5744725a8c033a1817f3cc8a8ec679bc15ac9d` |
| HEAD 일시와 제목 | 2026-09-30 13:32:45 KST · docs: record verified temp publication |
| 분석 복사본 생성 시각 | 2026-10-04T10:33:40.003443+09:00 |
| 미커밋 상태 | 추적 파일 수정 199개, 미추적 상태 항목 342개 |
| 분석 파일 | 6,489개 해시·크기 목록, 그중 UTF-8 텍스트 6,196개 고정 복사 |
| 읽기용 복사본 | `/tmp/walksafe-spec-20261004-source` |
| 명세의 줄 번호 | 고정된 작업트리 파일의 줄 번호. HEAD 커밋의 줄 번호로 간주하지 않음 |
| 바이너리 | 해시·설정·로더 연결을 근거화. 모델 실행·기기 시험은 이번 작업에서 하지 않음 |

복사본은 분석 도중 다른 작업의 변경을 섞지 않기 위한 임시 읽기 기준이다. 장기 식별은 `source-manifest.json`의 파일별 SHA-256을 사용한다. 링크는 원본 저장소 파일로 연결되므로 이후 코드가 바뀌면 줄 번호와 해시를 대조해야 한다. 일부 ignored `work/`·비공개 과거 실행 기록은 고정본 바깥의 기존 증거이며 현재 코드 전체 검증으로 확대하지 않는다.

## 완료 기록과 실제 코드의 차이

| 구분 | 전체 | DONE | PARTIAL | 미정합 | 해당 없음 |
|---|---:|---:|---:|---:|---:|
| A 담당 | 189 | 57 | 132 | 0 | 0 |
| B 담당 | 188 | 0 | 155 | 33 | 0 |
| 기존 종결분 | 565 | 553 | 0 | 0 | 12 |
| 전체 | 942 | 610 | 287 | 33 | 12 |

`current_status` 공란 33개는 미정합으로 표시했다. 원문 값을 변경하지 않았으며 빈값이 구현 부재·확정 결함이라는 의미는 아니다. A/B는 [기존 배정](../../handoff/2026-09-30/remaining-items.json)의 `source_id`로 결합했다. 배정 377개 중 중복·누락·A/B 겹침은 없다. A189는 보행60+경로60+인식50+기존 DONE19이며, 기존 21개 분야와 추가 motion70개를 새 구현 분야의 탐색 색인에 연결한다.

최신 rev241 인계는 **개발 완료142, 개발 잔여47, 현장 테스트 필요85, 원문 DONE57/PARTIAL132**를 기록한다. 개발142는 원문 DONE57과 현장 테스트 필요85의 합이며 전체 요구 완료와 같지 않다. B188의 상태가 그대로라는 사실도 B 코드에 변경이 없다는 뜻은 아니다.

근거: [A 완료 인계](../../planning/walksafe-part-a-completion-plan-20260930.md), [4세션 범위](../../handoff/2026-10-01/four-session-transition/scope.json), [복사한 현재 CSV](evidence/implementation-plan-items-20260921.csv).

### 요약 JSON의 내부 불일치

`implementation-plan-summary-20260921.json` 최상위 `current_status`에는 DONE605/PARTIAL264/미정합61/NA12가 남아 있고 최상위 `plan_revision`은 142다. 뒤쪽 `latest_part_a_user_stop_handoff`와 `latest_revision241_development_stage`가 최신 인계를 기록한다. 이번 집계는 고정 CSV를 직접 다시 계산했으며 JSON의 앞부분만 최신으로 취급하지 않았다. CSV 해시는 `73cef61183331641139f0f84ed4ede50dab085693f2090b001a01f242ba623fa`다.

## 증거 수준

| 표기 | 의미 | 허용되는 결론 |
|---|---|---|
| CODE_STATIC | 고정 코드·설정과 호출 경로 직접 읽기 | 구현된 조건과 상태변화 설명 |
| DOCUMENT_REQUIREMENT | 기존 정책·요구·인계 문서 | 문서가 요구하거나 기록한 내용 |
| TEST_EXPECTATION | 테스트 코드의 단언·fixture | 해당 시험이 기대하는 행위; 이번 실행 통과 아님 |
| HISTORICAL_EXECUTION | 기존 실행 로그·빌드·설치 기록 | 당시 대상/환경/범위에 한정한 결과 |
| CURRENT_DOC_CHECK | 이번 문서 연결·JSON·Mermaid 렌더링 검사 | 문서 구조와 그림 생성 확인 |
| UNCONFIRMED | 근거 부재/부분판독/연결 또는 환경 미확인 | 한계와 이유 표시 |

DONE은 기존 개선 작업의 기록이다. 이번 분석에서 모든 동작을 직접 실행했다는 뜻이 아니다. 정식 시험·출시 gate의 과거 `NOT_RUN`/`NOT_ELIGIBLE`도 문서 작성으로 닫지 않는다.

## 과거 실행의 현재 적용 한계

- 2026-10-03 APK build-result의 original source pin11개 중 현재 파일과 10개 일치, MainActivity 1개 상이였다는 조사 결과가 있다. 이 APK 성공은 현재 전체 소스 검증이 아니다.
- 2026-10-04 최신 field-test-handoff의 상태는 `VOICE_FIRST_INSTALLED_MODEL_ACCURACY_UNRESOLVED`, `selected_original_source_pins_unchanged=false`다. 설치 해시 일치 기록과 모델 정확도 미해결 기록을 함께 보존한다.
- 최신 daylog의 JVM210과 후속59는 중복 포함 별도 실행이다. 합산한 독립269건으로 쓰지 않는다. 실제 호출어 발화·실외 경로 시작 음성·보행·청취·모델 개선 완료는 미검증이다.
- 이번 분석에서는 제품 테스트·빌드·설치·외부 API 변경·계정 조작·실기기 및 현장 시험을 수행하지 않는다. 문서 검사 결과는 별도 [검수 보고](08-review.md)에 기록한다.

근거: `/home/ddobagi/Hanium_Dreamup/work/part-a-four-sessions-20261001/apk-handoff/original-user-20261003T130105Z/build-result.json`, `/home/ddobagi/Hanium_Dreamup/work/outdoor-field-20261003/field-test-handoff.json`, [2026-10-04 daylog](../../../daylog/2026-10-04.md).
