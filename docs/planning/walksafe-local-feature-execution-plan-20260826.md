# WalkSafe 로컬 기능 완성 실행 계획

- 작성일: 2026-08-26 (Asia/Seoul)
- 범위: 로컬 코드·자동시험·저장소 내부 재평가
- 제외: cloud 자원 생성, 운영 비밀값, 실제 배포, 정식시험 279건, 출시 gate 5개, 실운영 전환
- 원칙: 한 기능의 실패 회귀 → 최소 구현 → 집중시험 → 독립검토가 끝나야 다음 기능으로 이동한다.

## 1. 팀 운영

| Lane | 책임 | 병렬 작업 | 합류 조건 |
|---|---|---|---|
| Control | 현재 Goal·start gate·snapshot 정합성 | 제품 코드와 충돌하지 않는 control 검사·회귀 | event-scoped 시작검사 PASS |
| Implementation | 해당 기능의 최소 코드 | 한 시점에 한 기능·한 소유자 | 집중시험 PASS |
| Verification | 실패 재현·경계·회귀 | 구현 파일을 수정하지 않는 독립 검증 | P0/P1 0 |
| Next-slice | 다음 기능 요구·기존 코드 재사용 조사 | 현재 구현과 다른 파일의 읽기 전용 조사 | 다음 기능의 red test가 명확함 |

문서와 전체 저장소 검사는 기능 중간마다 반복하지 않는다. 기능 코드와 집중시험을 먼저 끝내고 합류 지점에서만 catalog, diff, Goal 경계를 갱신한다.

## 2. 실행 순서

### 단계 0 — FP-048 R002 시작 상태 정합성

현재 canonical focus는 `WS-GOAL-EPIC-03-FP-048-R002`다. seq89 READY 이후 Wave 2~4 변경이 managed snapshot에 반영되지 않아 start gate가 fail-closed 상태다.

1. 과거 FP-046 실패 gate의 `directory` binding을 현재 FP-048 gate runner가 안전하게 읽도록 보정한다.
2. `directory`는 event namespace만 검증한다. exact `path` binding이 없는 빈 실패 디렉터리는 byte evidence로 승격하지 않는다.
3. 현재 live bytes와 seq89 snapshot 차이를 append-only `seq90 GOAL_START_CONTROL_REANCHORED`로 결속한다. `READY → READY`, `status_changes = {}`, 모든 완료·정식·기기·외부·출시 credit은 0이다.
4. seq90 독립검토는 기존 managed path의 predecessor hash/bytes → current hash/bytes와 새 path를 `NOT_GOAL_EVENT_NO_COMPLETION_CREDIT` successor manifest로 봉인한다. frozen 증거를 수정하거나 오류를 면제하지 않는다.
5. FP-048 R002 contract R002에 따라 전용 5개 검사를 순서대로 실행한다.
   - continuation
   - Goal graph
   - current test-layer registry
   - FP-048 R002 control regression
   - sealed repository state
6. fresh PASS receipt가 만들어진 뒤에만 `seq91 GOAL_STARTED`를 append하고 FP-048 R002 하나만 `READY → IN_PROGRESS`로 바꾼다.
7. 실제 제품 수정은 seq91 GOAL_STARTED 이후에만 추가한다.

검증:

- gate preflight가 checkpoint parsing과 빈 과거 실패 디렉터리에서 중단되지 않는다.
- read-only preflight는 gate evidence를 생성하지 않는다.
- continuation과 Goal graph의 현재 98개 진단은 snapshot·제품 successor·복구 authority의 실제 root군으로 해소하며, 광범위 ignore/filter를 추가하지 않는다.
- 5개 검사 모두 exit 0이고 seq89 history·정식/실기기/외부/배포 credit은 변하지 않는다.
- seq90과 seq91을 한 checkpoint write에 묶지 않는다. seq90 발행 후 gate PASS를 확인한 다음 seq91을 별도 CAS로 발행한다.

### 단계 1 — FP-048 Gateway 암호화 상태 회전

대상은 정확히 7개 상태 **종류**다. 실제 파일 수는 세션·동의 record 수에 따라 7개보다 많을 수 있다.

1. `short-session`
2. `field-long-session`
3. `field-walk-ledger`
4. `privacy-rights-ledger`
5. `privacy-deletion-v2`
6. `integrated-consent`
7. `server-capacity`

구현:

1. 관리 디렉터리와 허용 파일명 inventory를 확정한다.
2. 모든 대상 lock을 안정된 정렬 순서로 먼저 획득한다.
3. 모든 파일을 read → envelope classify → decrypt → schema validate한다.
4. corrupt, unknown/removed key, lock busy, schema invalid 중 하나라도 있으면 encoded 후보와 실제 mutation을 모두 0으로 유지한다.
5. 전 대상 preflight가 성공한 뒤에만 기존 file별 atomic replace를 시작한다.
6. 회전 후 각 파일이 active key envelope이고 원래 값과 같은지 확인한다.

필수 회귀:

- fixture의 7종 exact inventory와 경로 일치
- 정상 plaintext inspect/migrate 및 decrypt-only rotate
- 정렬상 마지막 대상의 authentication tag·ciphertext·unknown key·schema 오류에서 전 대상 byte 불변
- 정렬상 마지막 대상 lock busy에서 전 대상 byte 불변
- privacy-rights writer와 maintenance가 같은 ledger lock 사용
- service-stop acknowledgement 없이는 maintenance 거부

비주장 경계:

- 전 preflight 뒤 두 번째 이후 rename/fsync/process crash가 발생할 때 cohort 전체 rollback은 이번 목표가 아니다.
- 해당 경계는 `NOT_CLAIMED`로 남기고 현재 합격조건에 사후 crash-atomic을 섞지 않는다.
- 대량 session record에서 모든 lock을 동시에 잡는 규모 시험은 후속 성능 항목이다.

완료 검증:

- Gateway typecheck와 build PASS
- FP-048 집중시험 PASS
- Gateway 전체 회귀 PASS
- 독립 감사 P0/P1 0
- GAP-057은 저장소 내부 결과만 재평가하고 release는 `NOT_ELIGIBLE` 유지

### 단계 2 — FP-023 경로 이탈·재탐색·TMAP 장애

확정된 10단계를 그대로 구현한다.

1. 최초 TMAP 경로 전체를 현재 보행 세션용 암호화 저장소에 저장한다.
2. 현재 위치와 저장 경로를 휴대전화에서 비교한다.
3. GPS 한 점만으로 이탈을 확정하지 않는다.
4. 위치 정확도·경로 거리·연속 관측을 함께 사용한다.
5. 이탈 의심 즉시 오래된 회전안내를 중지한다.
6. 이탈 확정 시 상황을 설명한다.
7. `새 경로 요청`, `위치 다시 확인`, `길안내 종료`만 제공한다.
8. `새 경로 요청`을 선택한 경우에만 TMAP을 호출한다.
9. 안전시험 전에는 `기존 경로 계속` 선택을 제공하지 않는다.
10. 저장 경로는 세션 종료와 24시간 중 먼저 도달할 때 삭제한다.

구현 단위:

- `RouteNavigator`: accuracy·route distance·연속 관측 기반 순수 상태기계
- 암호화 route snapshot store와 fake-clock TTL
- MainActivity decision controller와 접근 가능한 세 선택
- TMAP/GPS 실패 후 global safe-stop, 재검사와 사용자 확인 전 자동 재개 금지

검증:

- 한 점 spike에서 reroute 0회
- 확정 이탈 전 turn speech 중지
- 세 선택 외 action 0개, reroute 선택에서만 TMAP 1회
- TMAP 반복 실패 시 전체 안전정지
- 회복 신호만으로 재개 0회, 사용자 확인 뒤 1회
- TTL 경계 ±1과 plaintext route 부재

### 단계 3 — Navigation 보조 완결

R030 순서를 생략하지 않는다.

- `NPC-NAVIGATION-ROUTE-DIRECTION`: TMAP 경로, GPS, 진행방향, 보폭의 책임 분리와 도착 사용자 확인
- `FP-024`: 점자블록은 TMAP 방향을 확정할 수 있을 때만 보조하며 손상·불확실 블록은 경로 지시에서 제외

검증:

- GPS 불신 시 보폭으로 위치·방향 대체 0회
- 점자블록 단독 방향 변경 0회
- 도착 후보가 자동 종료하지 않고 사용자 확인을 기다림

### 단계 4 — 객체탐지와 위험안내

#### FP-019 탐지 후보 안전성

- 승인 model/config/hash/class allowlist만 활성화한다.
- 탐지 DTO에는 후보 관측값만 두고 거리·위험·행동 지시를 넣지 않는다.
- 교차·가림·재진입의 연결 불확실성은 새 observation으로 시작한다.
- 탐지기 반복 실패는 전체 safe-stop으로 연결한다.

#### FP-020 위험 판단과 출력

- fresh·linked·신뢰 가능한 입력만 pure risk engine에 전달한다.
- 불확실·누락 입력은 `no decision`이다.
- STOP 문구를 `멈추세요. 주변을 확인하세요.`로 고정한다.
- WARNING 고유 진동을 사용하고 좌우 추정 지시는 금지한다.
- severity 우선순위, TTL, repeat suppression, preemption을 고정한다.

#### FP-021 전·후 품질 gate

- 탐지 전: 밝기·가림·흔들림·카메라각·신선도
- 탐지 후: class·거리·confidence·model version
- nonmetric 제한모드는 승인 profile과 명시 확인이 있을 때만 허용한다.
- runtime metric 상실은 안내 억제 → 안전정지 → 재검사 → 사용자 확인 순서로 복구한다.

검증:

- 미승인 model/class, stale frame, 낮은 confidence에서 출력 0회
- 불확실 후보에서 STOP/WARNING 0회
- STOP exact 문구·WARNING 진동·좌우 지시 금지
- 실제 거리·날씨·기기 시험은 `NOT_RUN` 유지

### 단계 5 — 음성·진동·접근성

R030 순서: `FP-025 → FP-027 → FP-029 → FP-028 → FP-030 → FP-026`.

- `FP-025`: ACTIVE+microphone consent+raw sink ready에서만 `길라잡이` 오프라인 한국어 인식 시작, 듣기 시작·종료 신호와 원음 생명주기 연결
- `FP-027`: 보행 시작 전 offline TTS self-test, 제한 재시도 실패 시 진동·접근 가능한 화면·전체 safe-stop
- `FP-029`: 권한 거부와 지속 장애를 하나의 안전정지 화면으로 통합, 재검사와 명시 확인 전 재개 금지
- `FP-028`: 가입부터 삭제요청까지 읽는 순서·role·상태변경 알림·focus 완결
- `FP-030`: 큰 글자·대비·터치영역·상태 설명을 모든 사용자 화면에 적용
- `FP-026`: 승인 음성명령 allowlist와 상태전이, 낮은 확신 무실행, 비가역 명령 재확인

검증:

- 동의/ACTIVE/raw sink 중 하나라도 없으면 microphone listener 0개
- 낮은 음성 확신에서 명령 실행 0회
- TTS 실패 시 보행 시작 0회
- 안전정지 화면 action allowlist와 focus order 고정
- 회복 신호만으로 자동 재개 0회
- 실제 TalkBack 사용자 end-to-end는 `NOT_RUN` 유지

### 단계 6 — EPIC-07 원본자료 수집·보존·삭제

R030 순서: `NPC-RAW-ORIGINAL-COLLECTION → NPC-DATA-LIFECYCLE`.

구현:

1. video·audio·exact-location·sensor·route·detection·report·performance의 exact manifest/chunk schema를 고정한다.
2. 동의+ACTIVE 조건의 휴대전화 암호화 append store와 별도 key를 사용한다.
3. 서버 ingest는 idempotency key와 chunk SHA-256을 확인하고 부적합 자료를 quarantine한다.
4. 검증된 receipt 뒤에만 휴대전화 사본을 삭제한다.
5. repository별 retention/delete 상태기계를 fake clock으로 구현한다.
6. 기존 Noop capture 경로를 concrete collector로 교체하고 Backend privacy lifecycle에 연결한다.

검증:

- exact category/schema와 consent/ACTIVE negative cases
- plaintext 원본·token·secret scan 0건
- crash/retry 중복 생성 0건
- 잘못된 receipt 뒤 단말 삭제 0건
- 모든 보존 경계의 직전/정확/직후 시험
- 삭제 fan-out과 receipt 생성·멱등성

## 3. 공통 합류 gate

각 기능 단위가 끝날 때만 다음을 실행한다.

1. 변경 컴포넌트 집중시험
2. 해당 컴포넌트 전체 회귀
3. `git diff --check`
4. 독립 read-only 감사
5. repository catalog 결정론 검사
6. event-scoped Goal 검사와 daylog/local-memory 기록

실제 기기·정식시험·외부 검토·cloud·배포·출시 결과는 실제 증거가 생기기 전까지 모두 `NOT_RUN` 또는 0으로 유지한다.
