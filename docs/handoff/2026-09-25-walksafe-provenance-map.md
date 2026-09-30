# WalkSafe 21개 분석·942항목 장기 자료 지도

## 이 문서의 범위

- 이 인계는 하루이틀의 작업이 아니다. 적어도 저장소 `daylog/`에는 2026-09-07부터 09-25까지의 날짜별 기록이 있으며, ChatGPT 분석 원문 수집은 09-17, 942행 실행계획 작성은 09-21, 그 뒤 제품·시험·독립 검토와 정합 작업이 이어졌다. 날짜형 파일은 한 시점의 근거이지 전체 역사를 대체하지 않는다.
- 이 지도는 문서의 **출처와 연결 순서**다. 942개 항목을 모두 다시 검토하거나, 모든 제품 변경·시험 결과를 이 문서에서 새로 검증한 것은 아니다.
- 작업 기준 디렉터리: `/home/ddobagi/Hanium_Dreamup/` (제품·시험·daylog), `/home/ddobagi/Hanium_chat_review_2026-09-17/` (분석 원문·계획·역사·증거). 다른 컴퓨터에서는 이 두 경로를 함께 옮기고 경로를 치환한다.

## 1. 원문: 21개 기능 분류, 20개 답변과 별도 00

- 출처 색인: `/home/ddobagi/Hanium_chat_review_2026-09-17/원문색인.md`. 분류별 원문 파일, ChatGPT 원래 채팅 URL과 보존 상태를 연결한다.
- 캡처 메타데이터: `originals/index.json`. 채팅 URL, 수집 시각, 문자 수 및 수집 방식을 보존한다.
- `originals/*.txt`는 최종 답변 본문 전체의 로컬 보존본이며 항목 판단의 우선 원문이다. HTML은 표·링크 보조본이다. `01`과 `02`는 전체 TXT가 있지만 HTML은 미수집이다. `sources/*.md`와 `sources/browser-captures.json`은 초기 전체 대화 수집의 보조본이다.
- 원문 채팅의 코드 확인·시험 서술은 당시 분석의 주장이다. 이 저장소에서 나중에 수행한 독립 제품·시험 검증과 혼동하지 않는다. 원래 채팅 링크는 로그인/접근권한에 의존할 수 있으므로 로컬 TXT를 함께 이전한다.

| 분류 | 우선 원문 TXT | HTML |
| --- | --- | --- |
| 최초 분류 00 | `originals/00.txt` | `originals/00.html` |
| 01 | `originals/01.txt` | 미수집 |
| 02 | `originals/02.txt` | 미수집 |
| 03 | `originals/03.txt` | `originals/03.html` |
| 04 | `originals/04.txt` | `originals/04.html` |
| 05 | `originals/05.txt` | `originals/05.html` |
| 06 | `originals/06.txt` | `originals/06.html` |
| 07 | `originals/07.txt` | `originals/07.html` |
| 08 | `originals/08.txt` | `originals/08.html` |
| 09 | `originals/09.txt` | `originals/09.html` |
| 10 | `originals/10.txt` | `originals/10.html` |
| 11 | `originals/11.txt` | `originals/11.html` |
| 12 | `originals/12.txt` | `originals/12.html` |
| 13 | `originals/13.txt` | `originals/13.html` |
| 14 | `originals/14.txt` | `originals/14.html` |
| 15 | `originals/15.txt` | `originals/15.html` |
| 16·17 합동 | `originals/16-17.txt` | `originals/16-17.html` |
| 18 | `originals/18.txt` | `originals/18.html` |
| 19 | `originals/19.txt` | `originals/19.html` |
| 20 | `originals/20.txt` | `originals/20.html` |
| 21 | `originals/21.txt` | `originals/21.html` |

## 2. 원문에서 942행 계획까지

| 자료 | 역할과 읽을 때 주의할 점 |
| --- | --- |
| `종합분석.md` | 21개 분류의 종합 분석. 원문을 대체하지 않으며 서로 다른 확인 범위를 유지해야 한다. |
| `전체실행계획-20260921.md` | 942 ID의 단계·완료 기준·배치·소유권 설계. 작성 당시 목표 `ACTIVE`와 역사 수치는 현재 상태가 아니다. |
| `implementation-plan-items-20260921.csv` | 원문/보강 요구 942행의 날짜형 행별 계획과 `current_status`. ID·원문 수용 기준·현재 근거를 연결할 기준 파일이지만 현행 정본 최신성은 별도 확인해야 한다. |
| `implementation-plan-summary-20260921.json` | 위 계획 생성 배치의 집계·출처 보조 자료. 큰 파일 전체를 함께 이전한다. |
| `implementation-plan-inputs-20260921.md` | 계획 생성 시 사용한 담당자 근거와 미매핑 주제. 원문 밖의 70개 보강 요구를 무조건 원문 항목으로 바꾸지 않는다. |

- `942 = 원문 분석 기반 872 + 보강 요구 70`은 **계획 행 수**다. 모든 행이 확정 버그는 아니며 설계, 조건부 위험, 외부 검증도 포함된다.
- `S01-7`처럼 `S` 다음 번호가 있는 항목은 먼저 대응 분류 TXT를 찾는다. `S16-*`와 `S17-*`의 답변은 공동 `16-17.txt`다. 단, 보강 요구와 예외 매핑은 추정하지 말고 CSV의 해당 행과 `implementation-plan-inputs-20260921.md`를 확인한다.
- 항목 완료는 원문 요구, 현행 제품, 실제 적용, 직접 회귀, 독립 검토, 잔여를 연결한 후에만 판단한다. 시험 통과 수와 완료 항목 수를 더하지 않는다.

## 3. 역사 원장과 실행 증거

| 위치 | 용도 |
| --- | --- |
| `implementation-progress-20260917.csv` 및 `.md` | 기존 진행 원장. 현재 942행 계획과 같은 열·시점의 정본으로 가정하지 않는다. |
| `implementation-progress-20260917-snapshot-221127.csv` 및 `.md` | 09-17 당시 역사 스냅샷. 뒤의 제품 수정으로 자동 갱신되지 않는다. |
| `implementation-trace-20260917.csv`, `implementation-results-20260917-183821.md`, `implementation-scope-20260917.md` | 초기 추적·실행·범위 근거. 각 파일의 시점과 주장 범위를 유지한다. |
| `progress-slices/` | 진행 기록의 분할 자료. 행별 최신 판단의 대체물이 아니다. |
| `implementation-evidence-20260917-l6ol4h/`, `implementation-evidence-20260917-resumed/`, `implementation-evidence-20260919-resumed/` | 날짜와 재개 회차가 다른 patch, writer receipt, 실행·리뷰 증거. 해당 ID와 파일명으로 좁혀 찾고 첫 실패/재시도/최종 결과를 구별한다. 세 폴더 전체를 함께 이전한다. |
| 분석 디렉터리 `daylog/`와 제품 저장소 `daylog/` | 원문 수집과 제품 구현의 다일 작업 기록. 당일 항목만 읽고 전체 기간을 대표한다고 결론 내리지 않는다. |
| `RUNBOOK.md`, `progress.json`, `pending-prompts.json` | 초기 Chrome 분석 수집의 역사 운영 자료. 새 컴퓨터에서 과거 자동 전송·폴링을 다시 시작하라는 현행 지시가 아니다. |

- 09-17/09-20의 과거 진행 집계와 09-21 계획 이후 `current_status`는 기준이 다르다. 09-25 날짜형 CSV를 이번 인계에서 읽기 전용으로 집계한 값은 `DONE 52`, `PARTIAL 36`, 공란 854이다. 오래된 `DONE 61` 역사 집계를 더하거나 현재 완료율로 바꾸지 않는다.
- 이 자료들만으로 모든 현재 코드의 동일성, 실DB/운영 네트워크/실기기 안전성, 원문 전체 `DONE`을 증명하지 않는다. `2026-09-25-walksafe-status-report.md`는 마지막 **국소 종료 시점**의 결과다.

## 4. 다른 컴퓨터에서 한 항목을 따라가는 순서

1. 프로젝트 `AGENTS.md`와 이 인계의 재개 가이드·현황·작업 목록을 읽고, 전체 작업 디렉터리와 분석 디렉터리가 모두 이전됐는지 확인한다.
2. CSV에서 정확한 항목 ID와 원문 출처·현재 상태·미확인 게이트를 찾는다. `00` 또는 해당 분류 TXT, 필요한 경우 HTML과 `원문색인.md`로 원래 요구를 읽는다.
3. `종합분석.md`와 `전체실행계획-20260921.md`는 해석·계획으로 읽고, 역사 `implementation-progress-*`, 항목별 증거·daylog에서 **언제 무엇을 실행했는지** 분리한다.
4. 새 컴퓨터의 제품 코드와 직접 시험을 확인한다. 기존 WIP·타 담당 파일을 보존하고 파일당 한 명만 수정한다.
5. 해당 범위의 실패/직접 회귀/독립 검토를 기록하고 원문 전체 성공 기준에 맞을 때만 정본 상태를 바꾼다. 모르면 공란 또는 `PARTIAL`을 유지하고 이유를 남긴다.

## 5. 이전 범위의 한계

- 이 문서는 파일명을 표본 확인한 자료 지도이며 증거 폴더 수천 파일의 무결성 해시나 전체 인용 관계를 새로 검증한 manifest는 아니다. 따라서 일부 파일만 골라 복사한 뒤 완전 이전이라고 주장하지 않는다.
- 제품 저장소 밖의 분석 디렉터리를 빠뜨리면 21개 원문, 942행 계획, 과거 실패/리뷰 영수증을 재현할 수 없다. 반대로 그 분석 디렉터리만 옮겨도 미커밋 제품·시험 변경은 복원되지 않는다.
- 비밀값·운영 자격정보는 문서나 공개 저장소로 복사하지 않고 별도 안전한 이전 절차를 사용한다.
