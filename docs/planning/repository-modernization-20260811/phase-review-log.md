# 단계 계획 독립 검토 기록

## 0단계

- 계획 검토: `수정 후 진행`
- 보존 계획 검토: `PASS`
- 통제 경계 검토: 신규 일반 가이드는 비통제 경로로 추가 가능, 기존 managed 문서 수정은 마지막에 content hash reconcile 필요
- 구조 범위 검토: `docs/control/**`, `docs/deliverables/**`, 현행 코드·테스트는 유지. 레거시는 참조·해시 결속 감사 후 선별 이동

반영한 수정:

1. 원격 ref뿐 아니라 권한 제한 로컬 bundle을 추가했다.
2. Goal graph 63건을 기존 실패 지문으로 고정했다.
3. 818개 managed 경로와 41개 canonical binding을 이동 금지 경계로 명시했다.
4. 모든 추적 파일의 단일 상태 분류를 2단계 성공 기준으로 추가했다.
5. 레거시 이동은 manifest 승인 대상만 허용하고, `apps/web`처럼 결속된 경로는 이번 정리에서 물리 이동하지 않도록 했다.

구현 후 독립 검증:

- 원격 archive 브랜치·annotated tag peeled commit 일치
- 원격 `main` 불변, GitHub default=`current`, private 확인
- 보존 디렉터리 `0700`, bundle·manifest·checksum `0600`
- bundle SHA-256·complete history·제한 권한 clone HEAD/tree·`git fsck --full --strict` PASS
- 계획 기준선의 continuation·Goal graph 63건·test-layer 누락 1건 지문 재현

판정: `PASS`, 0단계 완료.

후속 단계의 독립 검토와 판정은 각 단계 구현 전에 이 문서에 추가한다.

## 1단계

- 가이드 계획 검토: `수정 후 진행`
- 새 팀원 탐색 구조 검토: `수정 후 진행(PASS 조건부)`

반영한 수정:

1. 중앙 가이드를 정본이 아닌 탐색·요약 계층으로 선언한다. 우선순위는 checkpoint → 승인 정책·산출물 원장 → 코드·OpenAPI·lockfile → 가이드다.
2. `development-environment-guide.md`를 추가해 Python·Node·Gradle·PostgreSQL/PostGIS·Docker·비밀값 경계를 한곳에 둔다.
3. 루트에서 제품 코드·기능 분담·산출물·테스트·현재 상태·레거시·기여/보안으로 한 번에 이동하고, 실제 세부 경로에는 최대 두 번의 클릭으로 도달하게 한다.
4. 중앙 가이드는 절차만 소유하고 정책 전문·257개 목록·상태표·대표 테스트 명령을 여러 문서에 중복하지 않는다.
5. 활성 문서 수정 범위를 root README, AGENTS, docs/apps/Backend/Model/Data 진입점으로 제한한다. frozen·canonical `docs/control/**`와 `docs/deliverables/**` 본문은 수정하지 않고, 비canonical control 색인 2개에는 checkpoint가 우선한다는 최소 current override만 둔다.
6. frozen v2.4 protected 집합과 canonical binding 41개는 byte/path 불변으로 검증한다.
7. Gateway API 개수를 고정 문구로 복제하지 않고 OpenAPI에 연결하며, unified TFLite와 Git 추적 예외를 실제 파일 기준으로 설명한다.
8. CODEOWNERS에는 추측한 팀을 넣지 않고 확인 가능한 저장소 소유자만 사용한다.

수용 기준:

- 루트 Quick links와 중앙 색인의 모든 상대 링크가 존재하고 2-hop 탐색 조건을 만족한다.
- v2.4 continuation, 공식 test-layer 경계, `279/279 NOT_RUN`, 5개 gate `NOT_RUN`, `NOT_ELIGIBLE`를 과장 없이 표시한다.
- protected/canonical 파일 diff 0, 가이드 링크 오류 0, 문서 명령 smoke 검증, `git diff --check` PASS.

판정: 조건을 계획에 반영해 1단계 구현 진행 승인.

구현 후 독립 검증:

- 루트·중앙 가이드·코드 지도·기여·보안 문서의 변경 Markdown 링크와 fragment 오류 0
- Android·Gateway의 hash 결속 README와 재개 안내서는 HEAD byte-exact 보존하고, 현행 안내는 source·OpenAPI·checkpoint·focus Goal 계약으로 분리
- Gateway OpenAPI 9 paths/13 operations, Android unified 768 primary, dataset·model register 상태를 실제 파일과 대조
- 정식 시험 279/279 `NOT_RUN`, release gate 5/5 `NOT_RUN`·미면제, `NOT_ELIGIBLE` 경계 유지
- protected 집합과 canonical binding 41개 diff 0
- checkpoint 변경은 두 snapshot content hash뿐이며 v2.4 continuation PASS
- Goal graph는 보존 clone의 기존 63건과 exact 동일하고 신규 실패 0
- artifact materialization 102/27/53/75와 locked Python OpenAPI currentness 검사 PASS
- `git diff --check` PASS

독립 검수 판정: `PASS` (P0 0, P1 0). 1단계 완료.

## 2단계

- 전수 카탈로그 계획 검토: `수정 후 진행 승인`

반영한 구현 계약:

1. Git tracked(삭제 제외), non-ignored untracked와 선언된 생성 출력 경로를 NUL-safe 입력 집합으로 사용한다.
2. 모든 경로는 `CURRENT_PRODUCT`, `SUPPORT`, `GOVERNANCE`, `EVIDENCE`, `GENERATED`, `LEGACY_REFERENCE` 중 정확히 하나로 분류하고 미일치·규칙 충돌은 실패한다.
3. managed 818개와 canonical 41개 속성은 checkpoint에서 직접 읽으며 결속 경로는 `KEEP_AT_PATH`로 고정한다.
4. script는 lifecycle과 가장 강한 부작용, test는 framework·area·lifecycle을 전수 기록한다. 파일명 날짜만으로 상태를 추정하지 않는다.
5. 출력 자기 포함, 정렬된 path-set hash, 생성 전후 입력 경쟁 감지, 고정 JSON 직렬화로 재현성을 보장한다.
6. `--check`는 쓰기 없이 byte-exact 비교하고 변조·미분류·중복 규칙 회귀 테스트를 둔다.

수용 기준:

- source universe와 repository catalog path set exact 일치, 중복·미분류 0
- managed 818·canonical 41 표시 exact, bound 경로의 archive 정책 0
- script/test 후보와 각 catalog path set exact 일치, 필수 속성 누락 0
- 연속 생성 diff 0, `--check` PASS, 변조·미분류·중복 rule 회귀 PASS

판정: 조건을 구현 계약에 반영해 2단계 진행 승인.

구현 후 1차 독립 검수:

- source universe·managed·canonical·이동 정책·결정성 검사는 통과했다.
- 파일명 동사 fallback 때문에 DB migration·기기·외부 호출·지속 출력 capability를 실제보다 낮게 분류한 P0가 발견돼 1차 판정은 `FAIL`이었다.
- 구 실행기에서 test inventory를 읽어 새 current runner와 catalog가 어긋났고, test lifecycle·framework·area에도 우선순위 오류가 있었다.

반영한 수정:

1. 237개 script 경로를 명시 inventory로 고정하고 신규 미분류 경로는 fail-closed한다.
2. 두 runner와 detect/report/PWA/voice/remote-browser 등 감사 대상의 가능한 최강 부작용을 실제 capability 기준으로 교정한다.
3. 완료된 과거 Goal one-off 52개와 구 실행기는 `HISTORICAL`, 새 실행기는 `CURRENT`로 고정한다.
4. 새 실행기의 historical·active-session 배열을 분리해 읽고 active-session membership이 v2.2/v2.3 파일명보다 우선하게 한다.
5. standalone STT/TTS는 `PYTHON_CLI`, voice-model 검사는 `VOICE`, superseded Goal package는 `HISTORICAL`로 교정한다.
6. Phase 4의 docs/execution·plans·samples·bound Web·정확한 2개 `ARCHIVE_READY` 집합을 회귀 테스트로 고정한다.

최종 독립 검증:

- source 3,463 / scripts 237 / tests 339 exact, 중복·미분류 0
- managed 818 / canonical 41 exact, bound archive 0
- 전용 unittest 10/10, 연속 생성 byte-exact, `--check` 무수정, 변조·중복·race 회귀 PASS
- 세 catalog의 source-set SHA-256 일치
- 1차 P0/P1 전부 교정 확인

독립 검수 판정: `PASS` (P0 0, P1 0, P2 0). 2단계 완료.

## 3단계

- CI·테스트·문서 검증 계획 검토: `수정 후 진행 승인`

반영한 구현 계약:

1. 해시 결속된 `run_walksafe_test_layers_20260711.sh`는 byte-exact 역사 자료로 보존하고, 현행 변경은 새 `run_walksafe_test_layers_current.sh`에만 둔다.
2. CI·활성 가이드·현재 테스트는 새 실행기만 호출하며 구 실행 명령은 활성 문서 검사에서 거부한다.
3. `all`은 unit→functional→integration만 실행하고 model-audit와 활성 checkpoint managed snapshot·transition 결속 active-session 검사는 분리한다.
4. 누락 test inventory를 실제 파일과 맞추고 helper가 보존되지 않은 과거 검사는 역사 분류한다.
5. 활성 문서 검사는 중앙 가이드와 현재 module README의 링크·fragment·script·runner selector를 검사하되 역사 증거 전체를 현재 문서로 승격하지 않는다.
6. quality CI는 `current` push/PR에서 잠긴 Python·Node·Java, 격리 PostGIS를 사용하고 기기·TFLite host opt-in은 명시적으로 끈다.

수용 기준:

- 구 실행기 HEAD byte-exact, 새 실행기 `bash -n`·`validate` PASS
- active 문서의 구 실행 명령 0, runner case·usage·문서 selector exact
- 문서 parser의 경로 탈출·외부 URL·fence·Setext·image/reference link 회귀 PASS
- model-audit와 관련 meta test PASS, continuation·Goal 기존 실패 집합 불변
- 독립 검수에서 P0/P1 0

판정: 조건을 구현 계약에 반영해 3단계 진행 승인.

구현 후 독립 검수와 보정:

1. 구 날짜형 runner를 직접 수정했을 때 frozen hash cascade가 생기는 것을 확인해 HEAD byte-exact로 복원하고 새 current runner로 분리했다.
2. active 문서의 구 runner 실행, 잘못된 selector, shell quoting·무인자·pipe·command substitution 우회를 모두 거부하도록 path-first 검사를 추가했다.
3. Markdown의 상대경로 탈출, script root 경계, fence·Setext·HTML comment, inline/image/reference·shortcut·중첩 괄호·escape를 회귀 테스트로 고정했다.
4. Web build의 lock→backup→복원 순서를 원자화하고 lock은 worktree Git metadata, cleanup은 절대경로를 사용해 병렬 실행 경쟁과 잘못된 상대경로 정리를 차단했다.
5. current runner의 array duplicate·overlap을 fail-closed하고 CI·가이드·catalog·테스트가 같은 runner를 사용하게 했다.
6. Python lock 충돌, Node·Java·Gradle 보장 범위와 package dependency 조회의 부작용 분류를 실제 동작에 맞췄다.

최종 독립 검증:

- old runner HEAD/worktree byte·mode exact, current runner `bash -n`·`validate` PASS
- active docs 39 / local links 432 / script refs 70 / runner selectors 7 PASS
- targeted regression 75 PASS, model-audit 12 PASS
- OpenAPI·catalog `--check`·continuation·`git diff --check` PASS
- Goal graph 출력은 보존 clone의 기존 63건과 byte-exact이며 신규 실패 0
- quality workflow는 `current` push/PR, 기기·TFLite opt-in false, active-session 분리 확인

독립 검수 판정: `PASS` (P0 0, P1 0, P2 0). 3단계 완료. 전체 `all`과 신규 clone 재현은 5단계에서 수행한다.

## 4단계

- 레거시 격리 계획 검토: `수정 후 진행 승인`

반영한 구현 계약:

1. 전수 카탈로그에서 `ARCHIVE_READY`이고 managed·canonical·checkpoint·runtime 결속이 없는 두 AI handoff 문서만 이동한다.
2. 보존 commit의 blob·mode·SHA-256·byte 길이를 manifest에 기록하고 payload bytes는 바꾸지 않는다.
3. 현재 작업 목록에서는 제거하되 `legacy/` 안내와 archive manifest를 통해 출처와 복원 위치를 찾을 수 있게 한다.
4. source 경로 부재, 대상 경로 exact 집합, `R100`, runtime 의존 0을 회귀 검사한다.
5. catalog·활성 문서·continuation·Goal graph를 이동 뒤 다시 검증한다.

구현 후 독립 검증:

- 두 payload 모두 `R100`, mode `100644`, blob·SHA-256·byte 길이가 보존 commit `f0093863e82bfc80d9f11915cef33a51d44b8730`과 exact
- 기존 source 경로 부재, symlink·hardlink·runtime/source 신규 결속 0
- catalog source set 일치, `ARCHIVE_READY` 0, legacy payload exact 2, 전용 unittest 12/12와 `--check` PASS
- active docs 40 / local links 436 / script refs 70 / runner selectors 7 PASS
- current runner `validate`, continuation, `git diff --check` PASS
- Goal graph 출력은 보존 clone의 기존 63건과 byte-exact

독립 검수 판정: `PASS` (P0 0, P1 0, P2 0). 4단계 완료.

## 5단계

- 재현성 계획 검토: `FAIL` (P0 1, P1 6, P2 2)
- Git·게시 계획 검토: `FAIL` (P0 1, P1 0, P2 3)
- 보안·민감정보 계획 검토: `수정 후 진행` (P0 0, P1 1, P2 3)

계획 검토에서 발견한 핵심 결함:

1. 기존 계획 문구는 신규 clone 검증이 commit보다 앞선 것으로 읽혀 현대화 전 `f009386`만 검증할 수 있었다.
2. daylog가 catalog source universe에 포함되므로 daylog 생성 뒤 최종 catalog를 생성해야 한다.
3. managed 파일의 마지막 변경 뒤 checkpoint content hash 두 곳을 reconcile해야 한다.
4. `active-session-control`은 활성 checkpoint managed snapshot·transition 결속 검사로 분리하고, commit-stable이므로 원 작업트리와 신규 clone에서 모두 재현해야 한다.
5. 게시 뒤 최신 실행이 아니라 새 commit SHA에 정확히 결속된 CI를 감시해야 한다.
6. 최종 staged 집합에 대한 secret·파일 유형·mode·크기 검사가 필요하고, CI의 지속적인 secret scan 게이트가 없었다.
7. 신규 clone의 backup integrity 재현은 일반 품질 환경과 분리한 정확한 CPython 3.14.6·`tests/backup-integrity-cp314.lock`·`WALKSAFE_BACKUP_PYTHON_BIN`이 필요하다.
8. secret 검사는 dirty worktree가 아니라 최종 staged index tree를 대상으로 하고, 그 tree ID와 commit tree의 일치를 게시 전에 확인해야 한다.

반영한 실행 순서:

`독립 검수·변경 동결 → daylog → catalog → checkpoint hash → 원 작업트리 전체 검사와 active-session-control → staged exact 대조·secret scan → commit → 제한 권한 no-hardlink clone 재현 → current 명시 push → exact-SHA CI → 원격 refs·local-memory·최종 보고`

판정: 결함을 5단계 구현 계약에 반영해 진행 승인. 구현 후 별도 독립 최종 검수를 수행한다.

구현 중 독립 검수와 보정:

1. hash 결속된 `deploy/README.md`를 HEAD byte-exact로 복원하고, 과거 README의 4경로 설명·실제 nginx의 5경로·현행 OpenAPI 9경로의 차이는 비결속 중앙 Gateway 가이드에 분리했다.
2. Gateway 책임을 field session·walk local 종결, navigation·report Backend 중계, consent·deletion local durable state·Backend sync 혼합으로 source와 일치시켰다.
3. 일반 품질 검사는 CPython 3.12.13, backup integrity 54건은 정확한 CPython 3.14.6·pytest 8.4.2·전용 hash lock으로 분리했다. 실행기와 CI는 버전·Linux memfd sealing preflight 실패 시 fallback 없이 중단한다.
4. Web Next production trace의 exact 제외 2개와 전체 exclude set, Android unsigned release의 사용자·관리자 test origin 동시 주입, CI `all`의 기기·host TFLite opt-in 비활성을 meta test로 고정했다.
5. 팀 분담 HTML은 119개 기능, 검색·담당 저장·재로드·JSON import/export를 실제 브라우저에서 검증하고 console error 0건을 확인했다.

동결 전 독립 판정:

- 가이드·사실·레거시 경계: `PASS` (P0 0, P1 0)
- runner·CPython 분리·CI 구조: `PASS` (P0 0, P1 0)
- Goal graph의 신규 결속 오류 0건. checkpoint hash 미조정 1건만 예정된 pending으로 남음
- 게시 진입 조건: 최종 daylog·catalog·checkpoint 동결, 원 작업트리 `all`, index tree 검사, no-hardlink clone 재현, exact-SHA CI 모두 PASS

최종 실행 중 발견한 분류 결함과 보정:

- 1차 `active-session-control`에 완료된 과거 phase·Goal·evidence suite 42개가 현행 검사로 잘못 등록돼 973 PASS·3 SKIP·146 FAIL·210 ERROR가 발생했다. 대표 원인은 과거 `BASE_COMMIT`·HEAD·source byte 결속이었다.
- assertion 완화·skip·실패 무시 없이 42개를 `HISTORICAL_CONTROL_PYTHON_TESTS`로 이동했다. `ACTIVE_SESSION_CONTROL_PYTHON_TESTS`는 활성 v2.4 checkpoint에 `continuation.validate` 및 managed content 재계산을 직접 적용하는 1개만 유지했다.
- 보정 수용 기준은 active 1개의 38/38 PASS, 179개 test 파일 exact partition, catalog lifecycle 일치, 독립 P0/P1 0으로 정했다.

최종 동결 재검토와 추가 보정:

- 독립 재검수에서 일부 README가 v2.2·v2.3 history adapter를 현재 gate로 설명하고, `active-session-control` 단독 실행은 strict continuation 검사를 전제로 하지 않는 불일치를 발견했다.
- history adapter 설명을 runner·catalog의 `HISTORICAL` 분류와 맞추고, current runner가 strict continuation checker를 먼저 통과한 뒤 singleton transition test를 실행하도록 fail-closed했다.
- 수용 기준은 runner 내부 순서 회귀, strict continuation와 active 38/38, catalog·활성 문서, 기존 Goal 실패 지문, 전체 `all` 재검증으로 정했다.

사용자-facing 최종 사실 감사:

- Android source-near README 네 곳의 Backend 직결·과거 README 안내가 실제 Gateway 경로와 충돌해, 수정 가능한 현재 문서만 `/api/navigation/*`·`/api/reports/v2` 및 중앙 현행 코드 지도로 교정했다. hash 결속된 과거 Android·Gateway README는 수정하지 않았다.
- 기능 분담표의 인쇄 CSS가 담당자·완료 입력을 숨기던 문제를 교정해 PDF/인쇄에서도 분담 정보가 유지되도록 했다.
- 정책 추적 재검토에서 기관 제출용 최소정보 파일의 생성·내보내기·판본 결속 작업이 분담표에서 빠진 것을 발견해 `AD-08`로 추가했다.
- 수용 기준은 source 경로 대조, 링크·catalog·active docs, 119개 unique ID·브라우저 동작과 인쇄 표면 재검증으로 정했다.

게시 및 exact-SHA CI 교정:

- 현대화 commit `3506e3afd113d1350ed74e82e0791b53161870a3`을 `current`에 게시하고 원격 `main`·archive branch·보존 tag가 그대로임을 확인했다.
- 첫 exact-SHA quality run `31495903939`는 setup-node toolcache가 공식 Node archive와 byte·mode exact하지 않아 `Run commit-stable test layers`의 root closure 검사에서 실패했다. 실패 전 secret·OpenAPI·continuation·checkpoint·catalog·문서·inventory·model 검사는 통과했다.
- 수정 전 계획 검토에서 `GITHUB_PATH`는 다음 step부터 적용되므로 같은 dependency install step의 npm이 ambient Node를 쓸 수 있는 문제와, restrictive umask에서 tar 권한이 달라지는 문제를 확인했다.
- CI가 공식 `node-v22.23.1-linux-x64.tar.xz`를 고정 URL·SHA-256으로 내려받아 `--same-permissions`로 추출하고, lock checker를 통과한 root만 쓰도록 교정했다. 같은 step은 PATH를 즉시 공식 root로 고정하고 다음 step은 `GITHUB_PATH`·`WALKSAFE_NODE_BIN_DIR`를 사용한다.
- 독립 임시 재현에서 archive SHA-256, Node 22.23.1, npm 10.9.8, 5,866-entry root closure와 npm closure가 lock과 exact 일치했다. 새 commit 게시 뒤 exact-SHA CI 성공을 최종 종료 조건으로 유지한다.

두 번째 exact-SHA CI의 계층 경계 검토:

- run `31497090277`은 Node·Web·Gateway·두 Python lock 환경과 모든 정적 gate를 통과했으나 unit에서 28건이 실패했다. 원인은 제품 코드가 아니라 전용 venv가 아닌 setup-python base, 과거 LibreOffice host bytes, runner-owned Java trust였다.
- 독립 lifecycle 검토에서 `test_walksafe_product_quality_receipt.py`, `test_walksafe_operator_attestation.py`, `test_release_evidence_gate.py`가 모두 catalog상 `HISTORICAL`인 Web 포함 2026-07-13 Full-RC 스크립트를 직접 검증하면서 현행 Unit/Integration에 섞인 것을 확인했다.
- 세 파일을 history inventory로 이동하고, 현재 제출 정책 파일의 portable 회귀는 유지하되 실제 LibreOffice host lock 대조 1건만 별도 history 파일로 분리한다. production 보안·attestation 코드는 수정하지 않는다.
- general CPython은 history 분류와 무관하게 문서·runner 계약대로 exact 3.12.13 real venv로 만들고 hash lock을 설치한 뒤 모든 현행 Python 단계에 절대경로로 전달한다.
- 수정 전 독립 판정은 이 경계와 meta 회귀를 반영하는 조건으로 진행 승인이다. 종료 기준은 180개 Python test exact partition, current 계층 전체 로컬 PASS, staged tree 검사, 새 exact-SHA CI PASS다.

교정 후 로컬 수용 결과:

- 현행 runner의 `all`을 단일 실행해 Unit Python 743 PASS·8 SKIP, Functional Python 741 PASS, Gateway 88 PASS, Backup 54 PASS, Integration Python 73 PASS를 확인했다.
- Web lint·typecheck·production build·53 trace/1,295 unique file 검증과 Android JVM·lint·debug·AndroidTest·양 앱 unsigned release·model asset 검사가 모두 PASS했다.
- 첫 로컬 release packaging의 일시적 incremental 실패 뒤 같은 task 재실행과 전체 `all` 재실행이 모두 성공했다. 제품 코드 변경 없이 깨끗한 단일 전체 실행을 최종 근거로 채택했다.
- 독립 최종 lifecycle·exact-env 검수, checkpoint reconcile, staged index 검사와 새 exact-SHA GitHub CI만 최종 게시 종료 조건으로 남겼다.
- lifecycle 독립 검수는 P0/P1/P2 0이었다. exact-env 독립 검수의 비차단 P2 1건인 초기 setup-python PATH 의존도 첫 action의 공식 `python-path` 출력 저장·절대경로 사용으로 제거했다. 관련 workflow meta 회귀와 실제 CI로 최종 확인한다.

세 번째 exact-SHA CI의 fresh Gradle dependency verification:

- run `31500636819`는 모든 환경·정적 gate와 model audit를 통과한 뒤 최초 Android fresh dependency resolution에서 중단됐다.
- 실패 artifact는 `com.google.guava:guava-parent:33.3.1-android:guava-parent-33.3.1-android.pom` 한 개다. 같은 Android Guava의 JAR·module과 JRE parent는 metadata에 있으나 Android parent POM만 빠져 있었다.
- Maven Central HTTPS payload, 게시 SHA-1, 별도 로컬 Gradle cache payload를 대조해 20,632 bytes와 SHA-256 `6e11986ea7250b51f847157e2dc937f32a306804dfce0007a5e81ddb9b95c579`를 확인했다.
- 다른 dependency trust 항목은 바꾸지 않고 해당 component·artifact·checksum 하나와 current exact 회귀만 추가한다. 빈 Gradle user home에서 CI 실패 task를 통과시킨 뒤 전체 메타·checkpoint·index 검사를 다시 수행한다.
- 빈 Gradle user home에서 동일한 `:app:processDebugNavigationResources --rerun-tasks`가 PASS했고 current Unit은 Python 744 PASS·8 SKIP, Gateway 88 PASS, Web 정책 suite와 Android 양 앱 JVM 테스트까지 PASS했다.
- 독립 검수는 공식 Maven Central 두 경로·게시 SHA-1·fresh Gradle cache의 일치, metadata +5/-0과 component/artifact/SHA 각각 +1, lock/build/settings diff 0을 확인했다. 최종 판정은 P0/P1/P2 0이다.

봉인된 runtime binding 승계 검토:

- 과거 FP008·FP046 receipt와 seq47·48·53 event는 당시 verification metadata SHA를 일관되게 봉인한다. 이를 현재 SHA로 고치면 이후 event chain을 소급 재작성하므로 금지했다.
- 기존 FP047 remediation의 exact predecessor/successor 패턴을 따라 실제 과거 event 3개에서만 `apps/android/gradle/verification-metadata.xml`의 `0f2fc21a…c0084` → `eaa662a4…d17` 한 쌍을 continuation checker에 승인한다.
- 공용 validator는 event ID·binding 수·순서·exact 필드·비-symlink를 유지하고, 승인 경로의 역사 receipt SHA와 현재 live SHA를 각각 고정한다. 다른 event·경로·digest·순서·live drift는 계속 기존 규칙으로 검증한다.
- 실제 FP008 시작·재개와 FP046 시작 receipt PASS, sealed digest 대체·임의 digest·순서 변경·다른 경로 재사용·제3 live digest FAIL을 회귀로 고정했다. 과거 receipt·event·repository-state log 변경은 0건이다.
- 독립 회귀가 최초 path-global 구현의 새 gate 호환성 결함을 발견해 event-scoped로 축소했다. 비대상 synthetic/current gate의 receipt==live PASS와 기존 FP008 start-gate 회귀를 다시 확인했다.
- 최종 회귀는 CPython 3.12.13 관련 116 PASS, strict continuation+active singleton 42 PASS였다. 비대상 event의 과거 sealed SHA 재사용 FAIL도 직접 고정해 독립 최종 판정을 P0/P1/P2 0으로 닫았다.
- catalog·runner validate·active docs와 XML 구조·유일성·origin 검사를 통과했다. 과거 receipt·repository-state log·transition history·anchor diff는 0건이다.

네 번째 exact-SHA CI의 Gateway timing race:

- run `31504235536`은 마지막 `all` 전 정적·보안·환경 gate를 전부 통과했고, Unit Python 744 PASS·8 SKIP와 Web 정책 suite도 PASS했다.
- 첫 Gateway 실행은 88 PASS였으나 두 번째 실행의 동시 8-writer 테스트에서 racer 1개가 제품의 의도된 1초 대기 뒤 `busy`를 반환했다. 각 실행의 state directory가 다르고 crash holder 종료도 확인돼 이전 실행 오염이 아닌 I/O 속도 의존 one-shot 가정으로 판정했다.
- 제품 timeout 증가·racer 축소·runner 중복 제거는 범위 밖이며 계약을 바꿀 수 있어 채택하지 않았다. test child가 `busy`만 10초 안에서 25ms 간격으로 재시도하고, all-settled 뒤 거부 사유를 전파하는 최소 수정만 적용했다.
- exact Node 22.23.1 typecheck와 Gateway 88 PASS 연속 2회, diff-check를 확인했다. 독립 검수는 다른 오류 은폐 0, 무한 대기 0, orphan child 0, 기존 actor·ledger·tamper assertions 유지로 P0/P1/P2 0이다.
- 동일한 단일 CPU·강한 부하 재현 조건에서 패치 전 `busy` 실패를 확인한 뒤, 패치 후 5/5 PASS와 모든 후속 ledger assertions 실행을 확인했다. 진단 child·부하 프로세스와 임시 로그는 남지 않았다.
