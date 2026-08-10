# WalkSafe 공개 개요 PDF 20260731 R003 독립검수 R001

- 문서 ID:
  `WS-WALKSAFE-PUBLIC-OVERVIEW-PDF-20260731-R003-INDEPENDENT-REVIEW-R001`
- 검토일: `2026-07-31`
- 판정: `PASS_PUBLIC_OVERVIEW_DISTRIBUTION_ALLOWED`
- findings: `BLOCKING=0 / MAJOR=0 / MINOR=0`
- 공개 소개자료 사용 가능: `YES`
- 제품 일반 배포·출시 승인: `NO`

## 1. exact 검수 대상

### PDF

- path:
  `docs/walksafe-overview-public-20260731-r003.pdf`
- SHA-256:
  `59b2895e1988eddbff2ef18c389fbd059e3010b4ea3fbb9cb108ae04108136ef`
- bytes: `151,090`
- 실제 페이지 수: `3`
- PDF version: `1.7`
- page size: 전 페이지 `595.304 x 841.89 pt`, A4, 회전 `0`

### 대응 공개 원문

- path:
  `docs/walksafe-overview-public-20260731-r002.md`
- SHA-256:
  `14e5ced2be74d6b8c084ebe0046490eff8a64a33d74f7beb22eea641e90f589d`
- bytes: `6,506`
- encoding: UTF-8

검수 시작 직전 위 두 target의 identity를 독립 재계산했다. 검수 종료 뒤에도
같은 SHA-256, bytes와 PDF 페이지 수가 유지됐다. 이 review는 두 target을
수정하지 않았다.

`file(1)`은 outline의 열린 descendant count `22`를 페이지 수로 오인하지만,
이는 휴리스틱 오탐이다. PDF Page Tree `/Count`와 `pdfinfo`의 실제 페이지
수는 모두 `3`이고 세 Page object의 MediaBox도 동일하다.

## 2. 최종 판정

```text
BLOCKING=0
MAJOR=0
MINOR=0
PUBLIC_OVERVIEW_DISTRIBUTION_ALLOWED=true
PRODUCT_DISTRIBUTION_AUTHORIZED=false
PRODUCT_RELEASE_AUTHORIZED=false
PDF_UA_CERTIFICATION_CLAIMED=false
```

R003는 비전공자용 프로젝트 소개자료로 공개 배포할 수 있다. 이 판정은
소개자료의 내용·표현·기본 PDF 접근성·정본 의미 보존에 한정된다. WalkSafe
제품의 안전성, 정식시험 통과, 일반 배포 또는 출시를 승인하지 않는다.

## 3. 원문 의미와 태그 논리 텍스트

Markdown 표식, 목록 label과 반복 footer만 제외하고 공백을 정규화한 결과는
다음과 같다.

- 공개 원문 visible text: `2,670`자
- PDF visible text: `2,670`자
- PDF tagged logical text: `2,670`자
- 세 결과의 순서·문자열: exact equal
- 정규화 text SHA-256:
  `e7cf087f501376f86c1a7f520d795280ef4e2a68339e46e88d8821a4bfdf2624`
- Unicode replacement character `U+FFFD`: `0`
- private-use bullet `U+F0B7`: `0`
- 표준 bullet `U+2022`: `9`

첫 페이지의 상태, 버전·기준일, 안전 고지는 각각 별도 `P` tag다. 따라서
이전 후보에서 보였던 다음 결합은 R003에 없다.

```text
미승인프로젝트
2026-07-31이 자료는
```

heading은 `H1` 1개, `H2` 7개, `H3` 14개로 계층화돼 있다. 목록은
`L/LI/Lbl/LBody`로 구조화됐고 unordered list는 `/ListNumbering /Disc`,
ordered list는 `/ListNumbering /Decimal`이다. footer는 본문 구조에 섞이지
않는다. 태그 논리 텍스트의 읽기 순서는 공개 원문과 일치한다.

## 4. 비전공자 이해도와 공개 안전성

다음 핵심 경계가 처음부터 FAQ까지 일관되게 유지된다.

- 개발 중, 정식 검증 전, 일반 배포·출시 미승인
- 소개자료이지 제품 승인서나 안전성 증명서가 아님
- 탐지의 놓침·오판과 경로의 불확실성
- 흰지팡이·안내견·보호자 또는 기존 보행 지원을 대체하지 않음
- 정식 안전 검증·출시 승인 전 실제 보행에서 안내에 의존하면 안 됨
- TMAP 경로는 큰 이동 방향 정보이며 안전한 통행 가능 여부를 보장하지 않음
- 손상 점자블록 신고는 담당자 검토·기관 전달 준비 대상이며 기관 자동 접수가
  아님
- 휴대전화 내부 인식과 서버 사용 범위를 구분하고 실제 데이터 흐름,
  보관·삭제 방식은 공개 전에 검증·고지해야 함
- 과거 Web 화면은 개발 참고자료이며 현재 개발·출시 대상이 아님

R003 원문은 내부 용어였던 “무결성 검사”, 모호한 “제품 후보”, “승인된 기능
정책”을 각각 “개발 자료 점검”, “고정된 앱·서버 묶음”, “확정된 기능 범위”로
바꿨다. 현재 상태, 남은 검증과 출시 경계를 비전공자가 제품 승인으로 오인할
중대한 표현을 발견하지 않았다.

## 5. 사실 교차검증

주요 사실 주장은 2026-07-31 현재 저장소의 다음 근거와 일치한다.

| 공개 소개자료 주장 | 교차검증 근거 |
|---|---|
| Android 보행 보조 프로젝트, 가까운 위험·TMAP 큰 방향·점자블록 신고 | `README.md:3`, `apps/android/README.md:3` |
| 사용자 앱, 별도 Android 관리자 앱, 서버, Web legacy 경계 | `README.md:9-17` |
| Android 기기 내 TFLite 인식과 서버 신고·경로 경계 | `backend/README.md:7-11`, `apps/android/README.md:62-68` |
| 기관 자동 API 접수가 아니라 관리자 검수·수동 전달 | `backend/README.md:9` |
| TMAP은 큰 방향의 기준이며 실시간 장애물 안전 판단을 맡지 않음 | `docs/deliverables/11-walksafe/walksafe-safety-and-policy.md:24-33` |
| 정식시험·실기기·현장·release gate·출시 증거가 아직 없음 | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-PROJECT-MASTER-HANDOFF-20260730-R001.md:115-126` |
| 불변 앱·서버·모델·설정 묶음 뒤 정식 검증과 별도 출시 승인 필요 | `docs/deliverables/09-release/release-control.md:68-71` |

일부 Android asset load/invoke 내부 계측은 존재하지만 대화형
camera→inference→안내→gateway 현장 검증을 대신하지 않는다
(`apps/android/README.md:239-247`). 따라서 소개자료의 “출시 판단에 필요한
실제 휴대전화와 현장 검증 증거가 아직 없다”는 한정 표현은 사실과
모순되지 않는다.

## 6. 시각·페이지 검수

세 페이지를 144 dpi와 288 dpi로 렌더링하고 text bounding box와 Ghostscript
bbox를 함께 확인했다.

- 잘림·겹침·본문/푸터 충돌: 없음
- 고아 제목·답변 없는 FAQ·분리된 목록 label: 없음
- 단일 열 읽기 순서: 정상
- 페이지별 마지막 본문과 footer 사이 공간:
  - 1쪽 `165.57 pt / 58.4 mm`, 사용 영역의 `22.2%`
  - 2쪽 `145.82 pt / 51.4 mm`, 사용 영역의 `19.2%`
  - 3쪽 `203.57 pt / 71.8 mm`, 사용 영역의 `26.8%`
- 세 페이지 밀도 차이는 허용 가능한 범위이며 이전 후보의 강제 page break형
  불균형은 없다.
- 흑색 본문과 흰색 배경, 제목 계층, 목록 indentation은 확대·인쇄 검토에서
  판독 가능하다.

## 7. PDF 기본 접근성·기술 항목

| 항목 | 결과 |
|---|---|
| Tagged PDF | `yes`, `Suspects: no` |
| document language | Catalog `/Lang(ko-KR)` |
| title | Info/XMP `WalkSafe 한눈에 보기` |
| display title | `/DisplayDocTitle true` |
| heading/list structure | `H1/H2/H3`, `L/LI/Lbl/LBody` 확인 |
| page tab order | 세 페이지 모두 `/Tabs/S` |
| selectable text | 원문과 exact equal, `U+FFFD=0` |
| fonts | 3개 모두 embedded=`yes`, subset=`yes`, Unicode=`yes` |
| URL annotation | `0` |
| images | `0`; image 대체텍스트 적용 대상 없음 |
| encryption / JavaScript | 없음 / 없음 |
| A4 / page count | 전 페이지 A4 / 실제 3쪽 |

이 검수는 PDF의 기본 접근성 항목과 논리 text를 점검한 것이다. PAC,
veraPDF, 실제 TalkBack·NVDA·JAWS 조합의 전수시험 또는 PDF/UA 적합성
인증을 수행하거나 주장하지 않는다.

## 8. 재현 명령

```bash
sha256sum \
  docs/walksafe-overview-public-20260731-r003.pdf \
  docs/walksafe-overview-public-20260731-r002.md
stat -c '%n %s' \
  docs/walksafe-overview-public-20260731-r003.pdf \
  docs/walksafe-overview-public-20260731-r002.md
pdfinfo -box docs/walksafe-overview-public-20260731-r003.pdf
pdfinfo -meta docs/walksafe-overview-public-20260731-r003.pdf
pdfinfo -struct docs/walksafe-overview-public-20260731-r003.pdf
pdfinfo -struct-text docs/walksafe-overview-public-20260731-r003.pdf
pdfinfo -url docs/walksafe-overview-public-20260731-r003.pdf
pdffonts docs/walksafe-overview-public-20260731-r003.pdf
pdftotext -enc UTF-8 docs/walksafe-overview-public-20260731-r003.pdf -
pdfimages -list docs/walksafe-overview-public-20260731-r003.pdf
gs -q -dBATCH -dNOPAUSE -sDEVICE=bbox \
  docs/walksafe-overview-public-20260731-r003.pdf
```

사용자 지시에 따라 daylog는 작성하지 않았다.
