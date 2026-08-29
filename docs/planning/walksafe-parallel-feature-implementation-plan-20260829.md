# WalkSafe 기능 구현 병렬 진행 계획

- 작성일: 2026-08-29
- 기준 브랜치/커밋: `current` / `bd6260c`
- 목적: Android 사용자 앱, Android 관리자 앱, Gateway, Backend의 남은 기능을 의존 순서대로 구현한다.
- 현재 단계: Wave 1~6 기능 구현·회귀 검증 완료(외부 결정·실기기 범위 제외)

## 1. 범위와 완료 원칙

### 포함

- 사용자 앱: 위험 안내, 탐지·카메라 품질, 음성·접근성, 원본 수집, 신고 대기열, 기기 자원·안전정지, 모델·설정 수명주기
- 관리자 앱: 신고 목록·검색·필터·상세, 검수·상태 변경, 제출본·수신증거, 감사 조회
- Gateway/Backend: 원본 manifest/chunk/receipt, 신고 멱등 수신·상태 조회, 관리자 최소정보 API, 보존·삭제, 용량 admission
- 각 작업의 실패 우선 테스트, 계약 fixture, 대상별 회귀 검증

### 제외

- checkpoint, Goal graph, hash 봉인, 승인 자동화와 정식 gate 실행
- Web/PWA 제품 기능 개발
- 앱에서 기관으로 이메일·문자·기관 API를 호출하는 자동 전달
- 운영 cloud, DNS/TLS, KMS, 실제 backup/restore, 현장·실기기·정식 279건 시험
- 근거가 없는 기기 임계값, 호출어 모델, 계정·SMS·보호자 공급자의 임의 선택

### 기능 패키지 완료 조건

각 패키지는 다음을 모두 만족해야 구현 완료로 기록한다. DB·객체 저장이 없는 순수 UI/정책 패키지는 3~4번을 `N/A`로 기록한다.

1. 요구사항과 API/상태 계약이 코드에 명시돼 있다.
2. 실패 입력을 먼저 재현하는 테스트가 있고 구현 후 통과한다.
3. 성공 응답 전에 필요한 DB·암호화 객체·감사 기록이 영속화된다.
4. 재시도·응답 유실·프로세스 재시작에서 동일 식별자와 결과가 유지된다.
5. 권한·동의·보행 상태·사용자 소유권을 우회할 수 없다.
6. 관련 모듈 회귀가 통과하고 남은 실기기·운영 검증은 `NOT_RUN`으로 구분한다.

## 2. 판단 자료 우선순위

1. 승인 정책 `PB-WALKSAFE-FEATURE-POLICY-1.0.1`
2. 시스템 요구사항과 인수기준
3. 실제 Android/Gateway/Backend 소스와 테스트
4. 생성 OpenAPI, route 보안, 상태·저장 계약
5. `walksafe-feature-development-status-20260828.html`과 `walksafe-full-feature-reassessment-20260828.html`
6. `walksafe_feature_implementation_catalog.html`과 UX 설계 문서

`walksafe-safety-and-policy.md`와 요구·인수·UX 문서는 승인 정책과 일치하는 범위의 추적·설계 보조자료다. 8월 28일 상태표는 남은 범위와 순서를 찾는 보조 자료이지 규범 근거가 아니다. 상태표와 코드가 승인 정책에 어긋나면 승인 정책을 따른다. 이전 카탈로그의 용량·준비 상태처럼 후속 구현을 반영하지 못한 항목도 실제 코드로 다시 확인한다. UX 문서는 승인·실기기 시험 완료 증거로 사용하지 않는다.

## 3. 병렬 작업과 파일 소유권

### 동시 작업 한도

- 코드 작성 lane: 최대 2개
- 읽기 전용 검증 lane: 최대 1개
- coordinator를 포함한 live agent: 최대 5개
- Gradle, PostGIS, 전체 pytest, Node test/typecheck, 모델 실행: 합계 1개만 실행
- Android 사용자 앱과 관리자 앱 Gradle 작업은 동시에 실행하지 않는다.

### 영역별 소유권

| 영역 | 기본 소유 경로 | 단일 작성자 파일 |
|---|---|---|
| 사용자 앱 | `apps/android/app/**` | `MainActivity.kt`, Gradle/manifest/assets |
| 관리자 앱 | `apps/android/adminapp/**` | `AdminBoundaryActivity.java`, Gradle/manifest, 공통 resource |
| Backend | `backend/**` | `models.py`, `schemas.py`, `main.py`, migration head |
| Gateway | `apps/android-gateway/**` | `routes.ts`, `backend.ts`, Gateway OpenAPI |
| 통합 | `contracts/**`, 계획, daylog | 생성 OpenAPI, 공통 fixture, daylog |

같은 단일 작성자 파일을 둘 이상의 lane이 동시에 수정하지 않는다. 계약 변경은 Backend/Gateway/Android 구현보다 먼저 고정하고, 공통 생성물은 통합 담당자만 갱신한다.

## 4. 자원 예산과 중단 기준

### 무거운 작업 시작 조건

- available memory 12 GiB 이상
- swap 사용량 1 GiB 미만
- 루트 디스크 여유 50 GiB 이상
- 1분 load average 20 미만
- 다른 Gradle/pytest/Node build/model 프로세스가 실행 중이지 않음

### 즉시 새 작업을 중단할 조건

- available memory 8 GiB 미만
- 이번 작업으로 swap 사용량이 2 GiB 이상 증가
- 루트 디스크 여유 40 GiB 미만
- 1분 load average 28 초과

### 실행 제한

- Gradle: `--no-daemon --max-workers=2`, JVM 1개
- Python: `pytest-xdist` 금지, DB 테스트 1 process, 전용 test PostGIS만 사용
- Node: `NODE_OPTIONS=--max-old-space-size=3072`, typecheck/test/build 직렬
- `/tmp`에 Gradle, APK, 모델, 대형 Node 산출물을 복제하지 않는다.
- 자원 초과 뒤 자동 재시도하지 않는다. 대상 테스트를 축소하고 원인을 확인한다.
- current runner를 실행해야 할 때는 `GRADLE_OPTS=-Dorg.gradle.workers.max=2`와 `NODE_OPTIONS=--max-old-space-size=3072`를 전달한다. runner가 포함하는 Legacy Web 검증도 자원 점유에는 포함한다.
- 새 test 파일은 통합 단계에서 현행 test-layer runner 목록에 정확히 한 번 등록한다.
- 긴 integration 단계 사이마다 memory, swap, disk, load를 다시 측정한다.

## 5. 의존성 순서

```text
FP-020 위험 계약
        └─> FP-019/021 탐지·품질 pipeline ─> 기기 자원·중앙 안전정지

원본 공통 계약 ─> 서버 manifest/chunk/receipt ─> Android 암호화 원본 저장·업로드
        └───────────────────────────────> 보존·삭제·계정삭제 결속

고정 report ID/상태 계약 ─> Android 영속 queue ─> receipt 기반 단말 삭제
                         └> 관리자 목록·상세 ─> 상태 ─> 제출본 ─> 감사

승인 bundle 계약 ─> 모델/class/threshold 결속 ─> 이전 정상판 복구
```

## 6. 단계별 구현 파도

### Wave 1 — 명확한 안전 충돌 수정과 원본 수신 기반

#### U1. FP-020 위험 안내 계약

- 행동
  - STOP 행동 문구를 `멈추세요. 주변을 확인하세요.`로 고정한다.
  - 정책 단계와 enum을 `주의=CAUTION`, `경고=WARNING`, `즉시 행동=STOP`으로 고정한다.
  - CAUTION 행동 문구를 `속도를 늦추고 주변을 확인하세요.`로 고정한다.
  - WARNING 행동 문구를 `멈출 준비를 하세요.`로 고정한다.
  - WARNING은 STOP과 구분되는 고유 1회 진동을 사용한다.
  - CAUTION/AWARE/INFO는 위험 진동을 사용하지 않는다.
  - stale·불확실·무결정 입력은 위험 단계와 행동 문장을 만들지 않는다.
  - 검증된 빈 공간이 없으면 좌우 이동 문구를 만들지 않는다.
- 변경 후보
  - `depth/MessagePolicy.kt`
  - `feedback/WalkSafeFeedbackPolicy.kt`
  - 두 정책의 JVM 테스트와 actuator 정적 테스트
- 실패 우선 검증
  - 단계별 exact 문구
  - WARNING 진동 non-null, STOP과 distinct
  - 저위험 단계 무진동
  - STOP 우선순위·TTL·cooldown 회귀
- 완료
  - 대상 테스트와 사용자 앱 unit 회귀 통과

#### B1. EPIC-07 원본 수신 수직 슬라이스

B1은 한 번에 합치지 않고 아래의 독립 검증 가능한 slice로 나눈다.

1. **B1a 계약·route 보안** — strict schema/fixture, FIELD route 분류, method/path/purpose/walk/consent/operation별 body digest에 결속된 raw 전용 Gateway assertion, account generation, 목적별 연속 consent, tombstone, request/chunk 상한, rate-limit group, 기본 비활성 설정, direct-backend 우회 negative test
2. **B1b 단일 object 저장** — manifest와 한 object/한 chunk의 암호화 저장, replay/conflict, DB·filesystem reservation
3. **B1c commit receipt** — 누락 범위, 전체 hash/크기 검증, immutable receipt와 concurrent commit
4. **B1d crash reconciliation** — rename/DB commit 전후 journal과 startup fail-closed
5. **B1e capacity·삭제 결속** — workload admission, 보존 class, 기존 account-deletion worker 연결

각 slice는 migration과 API가 다음 slice에서도 그대로 사용될 형태여야 하며 임시 in-memory 성공 경로를 만들지 않는다.

- 공통 object kind
  - `VIDEO`, `AUDIO`, `EXACT_LOCATION`, `SENSOR`, `ROUTE`, `DETECTION`, `REPORT`, `PERFORMANCE`
- API
  - `PUT /raw-collections/{collection_id}/manifest`
  - `PUT /raw-collections/{collection_id}/objects/{object_id}/chunks/{index}`
  - `GET /raw-collections/{collection_id}`
  - `POST /raw-collections/{collection_id}/commit`
- 상태
  - `MANIFEST_ACCEPTED -> RECEIVING -> READY_TO_COMMIT -> COMMITTED`
  - 내부 정합성 실패만 `QUARANTINED`; 검증 오류는 상태를 전진시키지 않는다.
- 불변식
  - collection/object ID는 client가 생성한 canonical UUID다.
  - 같은 ID+같은 hash는 같은 결과, 같은 ID+다른 내용은 409다.
  - 다른 actor의 collection은 404로 숨긴다.
  - chunk의 길이·SHA-256을 실제 body와 비교하고 기존 객체를 덮어쓰지 않는다.
  - 모든 chunk와 전체 object hash/크기, DB와 암호화 객체 영속화가 끝나야 receipt를 발행한다.
  - commit 재호출은 같은 immutable receipt를 반환한다.
  - receipt는 collection/manifest/object별 ID·bytes·SHA-256·보존등급·만료시각을 결속한다.
  - `GENERAL_RAW`는 raw consent, `AUTO_REPORT`는 raw+automatic-reporting consent를 요구한다. 이동통신망 동의는 실제 mobile drain에서만 추가한다.
  - Backend route는 `FIELD`로 명시 등록하고 actor identity와 account generation을 필수로 한다.
  - 일반 FIELD actor assertion만으로 raw route를 사용할 수 없다. raw 전용 assertion은 method, exact path, actor, account generation, operation/purpose, walk ID, 목적 동의 receipt와 manifest/chunk/commit SHA-256을 Gateway secret에 결속한다. 적용되지 않는 digest도 생략하지 않고 명시적 `null`로 canonical payload에 넣는다.
  - Gateway walking ledger가 연결되기 전에는 `WALKSAFE_RAW_INGEST_ENABLED=false`가 기본이다. 비활성일 때 상태 GET만 허용하고 manifest/chunk/commit raw write는 모두 fail-closed한다. 이미 받은 부분 자료는 삭제하지 않고 queue가 보존·재시도한다.
  - manifest/object/chunk count, declared/actual byte, request body, actor rate의 상한을 DB·파일 쓰기 전에 검사한다.
  - chunk body는 bounded memory에서 길이와 SHA-256을 먼저 검증하고, 그 뒤에만 consent/deletion shared transaction fence를 잡는다. 느리거나 중단된 body가 철회·삭제를 지연시키지 않는다.
  - manifest에 결속한 consent receipt 이후 목적에 필요한 동의가 한 번도 철회되지 않았고 현재도 활성일 때만 이어서 받는다. mobile/training처럼 해당 목적과 무관한 동의 변경은 collection을 stale 처리하지 않는다.
- 저장
  - 신고 이미지 `UPLOAD_DIR`와 분리된 raw object root를 사용한다.
  - chunk별 별도 AES-256-GCM AAD domain을 사용한다.
  - staging, fsync, atomic rename, DB commit journal과 시작 시 reconciliation을 사용한다.
- 실패 우선 검증
  - manifest replay/conflict/actor hiding
  - chunk hash·size mismatch/replay/conflict
  - missing ranges와 incomplete commit
  - commit 동시 실행 시 receipt 1개
  - 응답 유실 뒤 GET/resume
  - 동의 철회·계정삭제 fence
  - 미등록 route가 ADMIN으로 오분류되지 않는지와 admin bearer가 field raw API를 사용할 수 없는지
  - 기능 기본 비활성, signed assertion 누락, account generation 불일치, 목적별 consent 누락
  - generic FIELD assertion replay와 다른 method/path/purpose/walk/consent/manifest/chunk/commit digest로의 raw assertion replay
  - 네 raw operation의 교차언어 proof golden vector, TTL/future skew, 완전히 같은 idempotent request의 replay
  - 느린 chunk body 수신 중 deletion/consent transaction lock 0개
  - unrelated consent 변경 뒤 재개 허용, 필요한 consent 철회·재동의 사이의 오래된 receipt 재사용 거부
  - 용량 stale/hold/reservation
  - 파일 rename·DB commit 전후 crash reconciliation
- 완료
  - B1a~B1e가 각각 대상 테스트를 통과하고, 최종적으로 Backend에서 실제 암호화 chunk를 저장하며 재시작 뒤 같은 상태와 receipt를 반환한다.
  - Gateway stream proxy와 Android 수집은 다음 세부 단계로 연결하되 API 계약은 변경하지 않는다.

### Wave 2 — 탐지·품질과 Gateway 원본 전송

#### U2. FP-019/021 탐지·품질 pipeline

- 탐지 DTO에서 거리·위험·행동·신고 결정을 분리한다.
- 밝기·가림·흔들림·장착각·frame freshness의 pre-gate 실패 프레임은 detector에 넣지 않는다.
- 승인 guidance allowlist, class threshold, track certainty, 같은 시각의 metric depth, bundle version을 통과한 후보만 FP-020에 전달한다.
- 교차·가림·재진입으로 연결이 불확실하면 track/TTC를 초기화한다.
- 같은 class의 복수 track/detection은 기존 IoU gate로 상호 유일한 겹침이 확인되거나 남은 후보가 완전한 1:1일 때만 기존 ID를 유지한다. 모호한 다대다 연결은 최소 비용 하나를 임의 선택하지 않는다.
- 반복 카메라·모델 실패는 중앙 안전정지에 전달한다.
- 운영 임계값은 실기기 승인 전 default-deny 상태를 유지한다.

#### G1. Gateway raw stream/gate

- Backend와 같은 경로를 `/api` prefix로 노출한다.
- field auth와 account generation은 항상 검사한다. `GENERAL_RAW`는 raw consent, `AUTO_REPORT`는 raw+automatic-reporting consent를 검사하고 실제 mobile drain에서만 이동통신망 consent를 추가한다.
- 보행 ledger가 ACTIVE이거나 확인 불가하면 body를 읽기 전에 업로드를 거부한다.
- chunk 전체를 메모리에 올리지 않고 bounded stream으로 전달한다.
- client ID/hash/header를 다시 만들지 않는다.
- Backend raw ingest 활성화와 Gateway route 배포는 같은 candidate에서만 수행하며 Backend를 public ingress에 노출하지 않는다.

#### A0/A1 관리자 선행 작업 병렬 착수

- B1a의 공통 Backend route/security 계약이 고정되면 장기 raw storage 작업과 독립적으로 관리자 A0 계약을 시작한다.
- Android 관리자 앱은 A0 fixture를 기준으로 model/repository/controller와 목록 fake-repository UI를 먼저 구현할 수 있다.
- Backend 공용 보안 파일은 B1a와 A0를 같은 작성자가 직렬 수정하고, 관리자 UI lane은 `AdminBoundaryActivity.java`를 건드리지 않는다.
- 상세 범위와 완료 기준은 Wave 4의 A0~A2를 따른다.

### Wave 3 — Android 원본 저장과 접근성·음성 기반

#### U3. Android 암호화 원본 저장

- versioned manifest와 typed chunk, ordinal/timestamp/size/SHA-256을 저장한다.
- AndroidKeyStore 별도 키, crash-safe append, atomic manifest를 사용한다.
- raw consent+ACTIVE에서만 기록하고 pause/end/권한철회 즉시 닫는다.
- 철회·계정삭제 fence와 fake-clock 30일 TTL을 적용한다.
- partial manifest는 업로드하지 않고 receipt inventory가 일치할 때만 정확한 로컬 사본을 삭제한다.

#### U4. 접근성·음성 기반

- 전체 화면의 읽기 순서, focus, 확대/reflow, 색 외 상태표현, 48dp target을 정리한다.
- 음성 명령의 pause/resume/end 상태전이와 비가역 동작 확인을 분리한다.
- 승인된 한국어 호출어 asset이 없으므로 호출어 controller는 default-off 경계까지만 구현하며 FP-025 완료로 주장하지 않는다.

### Wave 4 — EPIC-08 영속 신고 queue와 관리자 핵심 화면

#### R1. 고정 신고 식별자와 상태

- canonical report UUID와 payload hash를 client에서 한 번 생성한다.
- transport idempotency와 업무상 중복 판정을 분리한다.
- 서버 status 조회 뒤 미완료 부분만 재시도한다.
- 서버 상태를 사용자 최소정보 projection으로 제공한다.

#### U5. Android 암호화 영속 queue

- 현재 direct upload를 제거하고 automatic/explicit 신고를 모두 queue에 넣는다.
- ACTIVE 보행 중 raw/report upload와 queue drain network call은 0건이어야 한다. TMAP 등 보행에 필요한 네트워크는 이 규칙의 대상이 아니다.
- 안정 정지+허용망에서만 drain하고 움직임 재개 시 새 chunk 전송을 중단한다.
- 재부팅 뒤 같은 ID/payload를 복원한다.
- 30일 TTL과 byte 상한, report 우선순위, 단일 drain lease를 적용한다.
- 서버 receipt의 ID·byte·full SHA·persistence marker가 모두 일치해야 삭제한다.

#### A0. 관리자 API·보안 선행 계약

- 신규 관리자 report route마다 ADMIN bearer, app kind, role, audience, active session/device, correlation ID를 고정한다.
- 읽기 route는 canonical query hash와 exact read purpose, device proof, fail-closed read audit을 요구한다.
- status/export/original grant/제출본은 action 등록, 고위험 재인증, 단일사용 nonce와 device proof를 요구한다.
- `field_test_security.py`, `admin_device_proof.py`, `admin_security.py`, `openapi_contract.py`와 보안 테스트는 Backend 계약 소유자 한 명만 수정한다.
- 관리자 DTO는 strict 최소정보 projection과 stable cursor를 사용하며 민감한 storage path, 복구자료, exact location을 기본 노출하지 않는다.
- 목록·상세·상태·export의 성공과 실패 감사에는 actor/session/device/correlation을 append-only로 결속한다. 현 감사 schema가 부족하면 projection 전에 migration과 write path를 보강한다.
- 관리자 앱의 새 진입점과 네트워크 호출은 기존 operational gate 안에 둔다. release flag를 켜지 않으며 완료 판정은 `내부 debug 구현`, 실배포는 `NOT_RUN`으로 유지한다.

검증:

- field/user token, 잘못된 app kind/role/audience/session/device/proof/read purpose/query hash를 모두 거부한다.
- 감사 commit 실패 시 성공 응답을 반환하지 않는다.
- strict DTO, cursor 안정성, OpenAPI security/action/read-purpose 계약을 고정한다.

#### A1/A2. 관리자 목록·검색·필터·상세·검수

- `/admin/reports`와 `/admin/reports/{id}`는 최소정보 DTO와 stable cursor를 사용한다.
- 관리자 device proof, read purpose, read audit이 실패하면 응답을 차단한다.
- Android 관리자 앱은 loading/empty/error/retry, 검색·필터, pagination, stale response 폐기를 구현한다.
- 원본은 일회성 purpose-bound grant와 재인증을 통과해야 표시한다.
- 검수 결정은 기존 append-only review 흐름을 사용한다.
- Android 구현은 `model -> repository/client -> controller/state -> 고유 panel/layout`로 나누고 pure JVM state test를 먼저 둔다.
- `AdminBoundaryActivity.java`, manifest, 공통 style/string, Gradle은 통합 담당자만 수정한다.
- loading/empty/error/retry, rotation/state restore, stale response, 48dp target, 읽기 순서, 확대/reflow, 색 외 상태 표현을 완료 기준에 포함한다. 실제 TalkBack 기기 시험은 `NOT_RUN`이다.

### Wave 5 — 상태, 제출본, 감사, 수명주기

#### A3. 관리자 상태 전이

- 서버가 허용한 next state와 optimistic version만 사용한다.
- 409에서는 최신 상태를 다시 읽고 자동 재제출하지 않는다.
- 고위험 재인증과 status audit commit 뒤 UI를 갱신한다.

#### A4. 단일 신고 제출본과 수신증거

- 최신 APPROVED 신고만 최소 CSV+manifest 제출본을 생성한다.
- 버전과 SHA-256을 delivery event에 결속한다.
- exact single-report export audit ID, package schema/version, manifest SHA-256, payload SHA-256을 delivery revision에 결속한다.
- Android SAF 저장만 제공하고 자동 메일·문자·기관 호출을 넣지 않는다.
- ACKNOWLEDGED/RESOLVED에는 외부 수신증거가 필요하다.
- SAF 문서 생성 성공과 실제 기관 수동 제출은 별도 사건이다. 취소·short write·hash 불일치에서는 SUBMITTED를 만들지 않고 앱 private cache에 영구 사본을 남기지 않는다.

#### A5. 감사 조회

- 보안·읽기·상태·검수·export·전달 사건을 allowlist projection으로 제공한다.
- 감사 조회 자체도 감사하고 stable cursor를 사용한다.
- secret, recovery material, exact location, storage path는 노출하지 않는다.

#### A6. 관리자·사용자 잔여 범위

- 관리자 사용자 피드백 확인, 중대 장애 확인, 본인 logout은 별도 패키지로 추적한다.
- 사용자 앱에는 본인 신고 접수·기각·기관 제출·해결 상태, 기각 이유, 정정·삭제 요청 경로를 별도 패키지로 구현한다.
- A1~A5만으로 FP-008/FP-033 전체 완료를 주장하지 않는다.

#### B2. 보존·삭제

- quarantine 14일, 일반/자동신고 180일, 승인 학습자료 3년 경계를 적용한다.
- backup은 35일 tombstone/reapply 계약과 metadata까지만 구현하고 실제 backup 순환·복원은 `NOT_RUN`으로 남긴다.
- 기존 account-deletion 상태기계에 raw 저장소를 연결하고 평행 삭제 상태기계를 만들지 않는다.
- preview/apply와 별도 삭제 권한, crash journal, 삭제 receipt를 사용한다.

### Wave 6 — 기기 자원·모델/앱/설정 수명주기

- 핵심 camera/depth/GPS/risk/TTS 신뢰 상실은 latched safe-stop으로 보낸다.
- queue·통신망·자료 전송 문제는 사용자 안전 출력을 방해하지 않고 조용히 보류한다.
- battery/storage/thermal/frame age/inference latency를 중앙 coordinator에 연결한다.
- 모델, class, guidance allowlist, threshold, 앱, 설정을 APK/AAB에 포함된 하나의 검증 bundle로 pin한다.
- 앱 시작 전에 config byte·모델 집합·SHA-256을 검증하고 active walk 중에는 어떤 bundle도 교체하지 않는다.
- detector·bundle 신뢰를 잃으면 안전 중지한다. 동적 다운로드, 모델 전용 signer, previous-known-good 자동 전환과 rollback은 구현 범위에서 제외하고 앱 업데이트를 통한 forward-fix만 사용한다.

## 7. 첫 구현 묶음

계획 확정 직후 다음 두 lane만 동시에 작성한다.

### Lane U — FP-020

1. CAUTION/WARNING/STOP exact 문구와 진동 구분 테스트를 먼저 실패시킨다.
2. `MessagePolicy`와 `VibrationPatterns`만 최소 수정한다.
3. 대상 JVM 테스트를 실행한다.
4. 사용자 앱 unit 전체는 Backend 경량 검증과 겹치지 않게 단독 실행한다.

### Lane B — EPIC-07 B1a부터 순차 구현

1. B1a의 schema, route security, 기본 비활성, negative fixture를 먼저 구현·검증한다.
2. B1b의 migration과 단일 object/chunk 암호화 저장을 구현·검증한다.
3. B1c의 missing ranges, 전체 검증, immutable receipt를 구현·검증한다.
4. B1d의 재시작 reconciliation을 구현·검증한다.
5. B1e의 capacity/retention/deletion 결속을 구현·검증한다.
6. 각 slice는 DB 없는 정책·저장 테스트를 먼저 실행한 뒤 필요한 전용 PostGIS 통합을 단독 실행한다.

관리자 앱 lane은 첫 묶음 동안 쓰지 않는다. raw/report/admin 계약을 동시에 변경해 공통 Backend 파일이 충돌하는 일을 막기 위해 B1a 계약이 고정된 뒤 A0를 시작한다.

## 8. 검증 순서

각 wave에서 아래 순서를 지킨다.

1. 변경 파일에 대응하는 단일 test class/file
2. 해당 모듈 unit
3. 계약 fixture와 OpenAPI/router 일치
4. Gateway Node typecheck/test
5. Android 사용자 또는 관리자 Gradle unit/compile/lint 한 모듈씩
6. 전용 PostGIS migration/integration
7. wave 합류 시에만 넓은 functional/integration 회귀

정식 279건, 실제 기기, 현장, TalkBack 관찰, 운영 backup/restore, 출시 gate는 기능 구현 테스트 결과로 대체하지 않는다.

## 9. 실패 복구와 변경 보존

- 시작·합류 전마다 `git status`로 사용자 변경을 확인한다.
- 기존 `daylog/2026-08-28.md`와 재판정 HTML을 덮어쓰거나 정리하지 않는다.
- `git clean`, 강제 reset, 광범위 삭제를 사용하지 않는다.
- 계약 테스트 실패는 하위 구현을 멈추고 fixture를 억지로 성공 형태로 바꾸지 않는다.
- DB migration 실패 시 운영 DB나 무관한 DB를 수정하지 않고 전용 test DB만 사용한다.
- partial export/upload는 성공으로 기록하지 않고 기존 영속 객체를 덮어쓰지 않는다.
- 자원 임계 초과 시 새 작업과 무거운 검증을 중단하고 현재 변경·로그를 보존한다.

## 10. 외부 결정이 필요한 항목

다음은 내부 구현 경계를 만들 수 있지만 실제 기능 활성화·완료에는 별도 결정이나 증거가 필요하다.

- 실제 계정 DB·SMS·보호자 확인 공급자
- 승인된 한국어 `길라잡이` 호출어 모델과 라이선스
- 승인 기기별 저장공간·배터리·발열·추론 임계값
- 최종 개인정보 문구와 무가림 원본 수집의 독립 검토
- 운영 object storage, KMS, TLS, backup/restore와 alert/on-call
- 승인 모델·독립평가·서명키·단계 배포 정책

이 항목은 임의 값이나 가짜 provider로 닫지 않고 default-off 또는 fail-closed로 유지한다.

## 11. 2026-08-29 첫 구현 묶음 실행 결과

### 완료한 기능

- U1 FP-020의 CAUTION/WARNING/STOP 문구와 단계별 진동 정책을 구현했다.
- U2 중 동일 class 다중 후보가 모호할 때 기존 track ID를 임의 연결하지 않고, 사라졌다 다시 나타난 후보의 ID와 위험 이력을 초기화하는 범위를 구현했다. U2 전체 품질 pipeline 완료를 의미하지는 않는다.
- B1a~B1e를 구현했다.
  - raw 전용 인증·요청 결속, strict schema와 기본 비활성 경계
  - 단일 object/단일 chunk 암호화 저장과 immutable commit receipt
  - 프로세스 종료 전후 journal reconciliation과 DB/파일 inventory 검증
  - stale·95%·100% 용량 상태의 신규 session 차단과 기존 exact replay 허용
  - envelope, journal, filesystem block·directory metadata와 inode를 포함한 보수적 reservation
  - `RAW_ORIGINAL_180D` 불변 보존 class와 migration head `202608290003`
  - 기존 account-deletion worker의 report+raw 원자적 삭제, commit 응답 유실 복구, 미완료 raw write fail-closed
  - raw API write와 backend 시작 reconciliation의 공용 maintenance lock 참여
- 기관 전달 자동화와 checkpoint·승인·제어 자동화는 추가하지 않았다.

### 검증 결과

- Backend 전체: `1073 passed`
- raw storage·계정삭제 PostgreSQL 집중 회귀: `180 passed`
- 계정삭제·backup 운영 계약: `42 passed`
- Android 사용자 앱 unit: `1075 passed`
- Android 사용자 앱 `lintDebug`: 통과
- canonical OpenAPI·raw fixture 생성 검사: current
- test-layer 구성 검사: 통과
- 전용 PostgreSQL 두 DB의 Alembic head: `202608290003`
- 실제 기기 진동, 현장 보행, 운영 backup/restore: `NOT_RUN`

### 차단 상태

G1 Gateway raw stream은 다음 두 계약이 확정되지 않아 구현하지 않고 default-off를 유지한다.

1. Gateway 동의 receipt hash와 Backend raw admission이 요구하는 원본 consent event receipt hash의 의미·변환 규칙이 현재 서로 다르다.
2. Gateway walking ledger의 짧은 session과 긴 session 중 raw upload 금지 범위를 판정할 정본 scope가 정해지지 않았다.

이 두 항목은 조용히 한 해석을 선택하면 인증 우회나 정상 업로드 오차단으로 이어질 수 있으므로 계약 결정 뒤 G1을 시작한다.

### 다음 구현 순서

1. 사용자 앱 lane은 U2의 카메라 품질 pre-gate와 stale metric-depth 결속을 이어서 구현한다.
2. Backend/관리자 lane은 기존 A0~A2 구현을 실제 코드·테스트 기준으로 재점검한 뒤 부족한 목록·상세·검수 기능부터 보완한다.
3. 두 lane 합류 뒤 B2의 일반 retention preview/apply와 raw 만료 삭제를 기존 account-deletion·maintenance lock 계약에 연결한다.
4. G1은 위 두 계약 결정 전까지 건드리지 않는다.

## 12. 2026-08-29 Wave 2~6 실행 결과

이 절은 11절의 첫 구현 묶음 이후 진행 결과이며, 11절의 “다음 구현 순서”를 대체한다. 기능 구현과 자동화된 회귀 검증은 Wave 6까지 진행했지만, 근거가 없는 외부 계약이나 운영 증거를 임의로 채워 완료로 처리하지 않았다.

| 단계 | 구현 결과 | 의도적으로 남긴 경계 |
|---|---|---|
| Wave 1 | 위험 단계별 안내·진동, raw 수신/암호화 저장/receipt/reconciliation/capacity 기반을 구현했다. | Gateway raw 전송은 consent receipt와 walking-ledger scope 계약 전까지 default-off다. |
| Wave 2 | camera detector admission, stale metric-depth 차단, detector/runtime 감독과 관리자 A0/A1 보안 계약을 구현했다. | 승인 기기별 실제 임계값과 raw Gateway 활성화는 적용하지 않았다. |
| Wave 3 | Android 암호화 raw manifest/chunk 저장·복구·삭제 fence, 접근성 상태 표현, 보행 음성 pause/resume/end 정책을 구현했다. | 승인된 한국어 호출어 asset이 없어 wake-word 감지는 default-off다. |
| Wave 4 | 고정 report ID/payload hash/receipt/status 계약, Android 암호화 영속 queue와 bounded drain, 관리자 목록·상세·검수 화면을 구현했다. | production report queue capacity profile은 승인값이 없어 `null`/default-off이며 기관 자동 제출은 없다. |
| Wave 5 | 관리자 상태 CAS, 단일 신고 제출본·SAF 수동 저장, 감사 조회, 사용자 본인 신고 목록·상세·정정/삭제 요청, 관리자 요청 처리를 구현했다. raw 180일 삭제는 별도 최소권한 역할의 수동 preview/apply/reconcile로 구현했다. | 신고 단위 삭제는 append-only 처리 요청이며 즉시 물리 삭제가 아니다. 14일 quarantine, 3년 학습자료, backup restore tombstone reapply는 영속 모델·승인 부족으로 차단했다. 중대 장애는 source/severity/ack 계약이 없어 구현하지 않았다. |
| Wave 6 | camera/depth/GPS/risk/TTS 안전정지 조정, thermal/storage/frame/latency admission, APK 내장 모델 bundle hash 검증과 active-walk 교체 금지를 구현했다. | 재검토 결과 previous-known-good 상태기계와 legacy 자동 전환은 승인 기준선 밖이므로 병합 대상에서 제외한다. 동적 다운로드·모델 전용 signer·rollback은 두지 않고 앱 업데이트 forward-fix만 사용한다. |

### 최종 자동 검증

- Backend 전체: `1169 passed`
- Android Gateway 전체: `112 passed`
- Android 사용자 앱 unit 전체: `1179 passed`; `lintDebug` 통과
- Android 관리자 앱 unit 전체: `129 passed`; `assembleDebug`, `lintDebug` 통과
- Backend canonical OpenAPI·fixture: current
- Alembic 전용 PostGIS DB: `202608290008 (head)`
- 수동 raw retention 운영 계약: `3 passed`
- Python test-layer 구성: 통과
- 전체 변경 whitespace 검사: 통과

### 완료로 주장하지 않는 항목

- 실제 기관 전송·접수 E2E와 기관 자동화
- 실제 기기 TalkBack·진동·현장 보행 시험, release 서명·배포
- 운영 object storage/KMS/TLS/backup·restore와 formal 279건 시험
- checkpoint, Goal graph, hash 봉인, 승인·제어 자동화

따라서 현재 판정은 “Wave 1~6의 내부 기능 구현과 자동 회귀 검증 완료”다. 위 외부 계약·운영 증거 항목은 `BLOCKED`, `DEFAULT_OFF` 또는 `NOT_RUN`으로 유지한다.
