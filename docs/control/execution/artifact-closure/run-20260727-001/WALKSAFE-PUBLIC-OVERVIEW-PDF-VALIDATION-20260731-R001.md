# WalkSafe 공개 소개 PDF 검증 기록 20260731 R001

- 문서 ID:
  `WS-WALKSAFE-PUBLIC-OVERVIEW-PDF-VALIDATION-20260731-R001`
- 작성일: `2026-07-31`
- 판정:
  `PASS_PUBLIC_OVERVIEW_DISTRIBUTION_ALLOWED`
- 공개 소개자료 사용 가능:
  `YES`
- 제품 일반 배포·출시 승인:
  `NO`
- PDF/UA 적합성 인증 주장:
  `NO`

## 1. 권고 공개본

### PDF

- 경로:
  `docs/walksafe-overview-public-20260731-r003.pdf`
- SHA-256:
  `59b2895e1988eddbff2ef18c389fbd059e3010b4ea3fbb9cb108ae04108136ef`
- 크기:
  `151,090 bytes`
- 형식:
  PDF 1.7, A4, 3쪽, tagged PDF
- 파일 속성:
  regular file, non-symlink, mode `0644`, hard-link count `1`

### 대응 공개 원문

- 경로:
  `docs/walksafe-overview-public-20260731-r002.md`
- SHA-256:
  `14e5ced2be74d6b8c084ebe0046490eff8a64a33d74f7beb22eea641e90f589d`
- 크기:
  `6,506 bytes`
- 파일 속성:
  regular file, non-symlink, mode `0664`, hard-link count `1`

기존 내부 소개 원문
`docs/walksafe-overview.md`
(`bef1580449e96d2dae870037aba1433e0e6cfc190424372f32db032746edb630`)
은 수정하지 않았다. 공개본은 내부 경로와 작업 통제 링크를 제거하고,
개발 중·정식 검증 전·일반 배포 및 출시 미승인이라는 한계를 첫 페이지와
FAQ에 명확히 표시한 별도 파생본이다.

## 2. 후보 이력과 처분

| 후보 | SHA-256 | 처분 |
|---|---|---|
| `docs/walksafe-overview.pdf` | `a32ffc7499a9df887f401632a48ce696da3fb8f083aaa1539a5dafe289a01a20` | 4쪽 초기 후보. 페이지 균형 검수에서 기각. 공개 사용 금지 |
| `docs/walksafe-overview-public-20260731-r002.pdf` | `23876c07aba67c880a6bb073dbe3a29751b102a8adf86e5e7d89cbf23beeecbb` | 3쪽 후보. 태그 논리 텍스트 경계와 용어 명확성 문제로 기각. 공개 사용 금지 |
| `docs/walksafe-overview-public-20260731-r003.pdf` | `59b2895e1988eddbff2ef18c389fbd059e3010b4ea3fbb9cb108ae04108136ef` | 자동·전 페이지 시각·독립 내용 검수 통과. 권고 공개본 |

앞선 후보는 검수 이력을 위해 add-only로 보존한다. 같은 이름으로
덮어쓰거나 권고본으로 재사용하지 않는다.

## 3. 자동 검증 결과

| 항목 | 결과 |
|---|---|
| SHA-256 / 크기 | 기대값과 일치 |
| `pdfinfo` 페이지 | 3 |
| 페이지 크기 | 전 페이지 A4, `595.304 x 841.89 pt` |
| Tagged | `yes` |
| 문서 언어 | Catalog `/Lang(ko-KR)` |
| 제목 | `WalkSafe 한눈에 보기` |
| 폰트 | Noto Sans CJK KR 3개, 모두 embedded/subset/Unicode `yes` |
| 표준 글머리표 | `U+2022`, private-use `U+F0B7=0` |
| 깨진 문자 | `U+FFFD=0` |
| URL annotation / 공개 URL | `0 / 0` |
| 내부 경로·통제 ID 노출 | `0` |
| 첨부파일 / JavaScript / 암호화 | 없음 / 없음 / 없음 |
| Ghostscript null-page render | PASS |
| 150dpi 렌더 | 3쪽 모두 생성, 잘림·오류 없음 |

150dpi 페이지 렌더 SHA-256:

```text
page-1.png  90b5057adc8081d93fb4c46a77dd3faa3604bbc85c5c286eba75767adff5c2c8
page-2.png  e6dc9affb695218d6183fa41f9098ddfd658795848569cea489dc215837bd90f
page-3.png  e4067d76460052dc3e88fd077498f096c58d45a0420594b0ae4b9fcd38945f1a
```

태그 논리 텍스트 SHA-256:

```text
6ab97a0e6f7776bd4870b4125549afa8af5a7f1e192231c856fc525939203ff6
```

공개 원문, PDF 가시 텍스트와 PDF 태그 논리 텍스트는 Markdown 표식,
목록 label과 반복 footer를 제외하고 공백을 정규화했을 때
`2,670`자로 exact equal이다. 이전 후보의
`미승인프로젝트`,
`2026-07-31이 자료는`
결합은 R003에 없다.

일부 `file(1)` 버전은 tagged outline의 descendant count `22`를 페이지
수로 오인한다. PDF Page Tree, `pdfinfo`와 실제 렌더 페이지 수는 모두
`3`이므로 이는 libmagic 휴리스틱 오탐이며 PDF 페이지 결함이 아니다.

## 4. 전 페이지 시각 검수

- 1쪽:
  제품 목적, 문제, 사용자 경험과 세 기능이 한 흐름으로 읽힌다.
- 2쪽:
  앱·서버 구성, 현재 상태와 남은 여섯 단계가 한 페이지 안에서 완결된다.
- 3쪽:
  안전 한계, FAQ와 자료 안내가 제목 단위로 빠르게 구분된다.
- 공통:
  글자 잘림, 요소 겹침, 고아 제목·고아줄, 푸터 충돌이 없다.
- 목록:
  비순서 목록은 표준 bullet, 순서 목록은 `1`부터 `6`까지 유지된다.
- 페이지 균형:
  세 쪽 모두 본문과 하단 여백이 안정적이며 비전공자가 제목만 훑어도
  현재 상태와 안전 한계를 찾을 수 있다.

독립 시각 검수:

- 경로:
  `WALKSAFE-PUBLIC-OVERVIEW-PDF-20260731-R003-visual-review-r001.md`
- review SHA-256:
  `1fc10a81911213a56f2ebf07e612396a3c4a59b089d1ce58efd230a03a9b2018`
- 판정:
  `BLOCKING/MAJOR/MINOR=0/0/0`, `PASS`

## 5. 독립 내용·공개 안전성 검수

독립 검수는 공개 원문과 PDF를 같은 exact SHA로 고정하고 다음을
교차 확인했다.

- 비전공자 이해도
- 개발 중·정식 검증 전·출시 미승인 표시
- 보행 안전을 보장하지 않는다는 한계
- 흰지팡이·안내견·보호자·기존 보행 지원을 대체하지 않는다는 경계
- TMAP 경로가 큰 방향 정보일 뿐 안전한 통행을 보장하지 않는다는 경계
- 점자블록 신고가 담당자 검토·기관 전달 준비 대상이며 기관 자동 접수가
  아니라는 설명
- Android 사용자 앱·별도 Android 관리자 앱·서버와 과거 Web 참고 코드의
  구분
- 휴대전화 내부 처리와 서버 사용 범위의 구분
- 저장소의 제품·안전·release 문서와 주요 사실의 일치
- 태그 구조, 읽기 순서, 언어·제목 메타데이터와 폰트 내장

독립 내용 검수:

- 경로:
  `WALKSAFE-PUBLIC-OVERVIEW-PDF-20260731-R003-independent-review-r001.md`
- review SHA-256:
  `e21528fa74e1b38e399b247596fc52934e03d0e6fe88c96b5d74fc20f72ffe2f`
- 판정:
  `BLOCKING/MAJOR/MINOR=0/0/0`,
  `PASS_PUBLIC_OVERVIEW_DISTRIBUTION_ALLOWED`

두 독립 검수 모두 R003 PDF의 공개 소개자료 사용은 허용하지만 WalkSafe
제품의 일반 배포, 안전성, 정식시험 통과 또는 출시를 승인하지 않는다.

## 6. 생성·검증 도구

| 도구 | 확인 버전·용도 |
|---|---|
| LibreOffice | `26.2.4.2`, UTF-8 공개 원문에서 tagged PDF 생성 |
| Poppler `pdfinfo` | `26.01.0`, 메타데이터·페이지·태그·URL 확인 |
| Poppler `pdftotext` | 가시 텍스트와 태그 논리 텍스트 확인 |
| Poppler `pdffonts` | 폰트 내장·subset·Unicode 확인 |
| Poppler `pdftoppm` | 150dpi 전 페이지 렌더 |
| Ghostscript | `10.06.0`, null-page 렌더 확인 |

PAC, veraPDF와 실제 TalkBack·NVDA·JAWS 조합의 전수시험은 수행하지 않았다.
따라서 PDF/UA 적합성 인증을 주장하지 않는다.

## 7. 사용 규칙

외부에 전달할 파일은 exact SHA가 고정된
`docs/walksafe-overview-public-20260731-r003.pdf`
한 개다.

다음 중 하나가 바뀌면 기존 검수 결과를 재사용하지 않고 새 revision을
add-only로 생성해 다시 검수한다.

- 공개 원문 내용
- PDF bytes
- 프로젝트 개발·검증·배포 상태
- 제품 범위, 안전 한계 또는 개인정보 처리 설명

최신 상태 안내 없이 이 기준일 PDF를 장기간 재사용하지 않는다. 정식 공개를
시작할 때는 공개 시점의 상태·연락처·개인정보 안내를 별도 확인해야 한다.

## 8. claim ceiling

```text
PUBLIC_OVERVIEW_PDF_READY=true
PUBLIC_OVERVIEW_DISTRIBUTION_ALLOWED=true
PRODUCT_DISTRIBUTION_AUTHORIZED=false
PRODUCT_RELEASE_AUTHORIZED=false
PRODUCT_SAFETY_CERTIFIED=false
FORMAL_PASS_DELTA=0
DEVICE_EVENT_DELTA=0
GATE_DELTA=0
PRODUCTION_DELTA=0
PDF_UA_CERTIFICATION_CLAIMED=false
```
