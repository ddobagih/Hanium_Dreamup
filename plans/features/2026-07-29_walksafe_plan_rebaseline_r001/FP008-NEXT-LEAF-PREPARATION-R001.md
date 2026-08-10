# FP-008 다음 leaf 준비안 R001

- 작성일: 2026-07-30
- 상태: `PREPARATION_ONLY_NOT_MATERIALIZED_NOT_AUTHORIZED`
- 후보 Work Item ID: `EPIC-03-FP008-ADMIN-REVIEW-DELIVERY`
- 정책/Gap: `FP-008` / `GAP-017`
- 부모 후보: `WS-GOAL-EPIC-03`
- 선행 내부 leaf: `WS-GOAL-EPIC-03-FP-047-R001`
- 자원 분류: `HEAVY`

이 문서는 다음 제품 구현 leaf의 범위와 검증 경로만 준비한다. Goal 파일·event를
만들지 않고, r022를 정본에 적용하지 않으며, 제품 코드·checkpoint·release 상태를
변경하지 않는다.

## 1. 현재 확인된 기반

- Android `adminapp`은 사용자 앱과 분리된 보안 전용 셸이다.
- 관리자 로그인, 세션 폐기, 작업 결속 재인증, 복구와 denial audit 기반이 있다.
- backend에는 신고 목록·상세·중복 후보·상태 변경과 기관용 export가 있다.
- backend에는 read/status/export/admin-security 감사 저장 기반이 있다.
- 기관용 export는 내부 산출물일 뿐 실제 기관 제출이나 수신증을 뜻하지 않는다.

현재 `ADMIN_OPERATIONAL_WORKFLOWS_ENABLED=false`이고, flag만 켜면
`AdminBoundaryActivity`가 시작 시 예외를 발생시킨다. 관리자 앱이 만드는 로컬
UUID도 등록 기기 allowlist가 아니다.

현재 기반에는 다음 제한도 있다.

- 복제 가능한 로컬 UUID는 기기 소유 증명이 아니다.
- reauthentication은 action/method/path만 결속하므로 같은 경로의 body·query
  변경을 구분하지 못한다.
- read/status/export audit은 검증된 session·device 결속이 불완전하다.
- `ReportExportAudit`은 rows digest를 저장하지만 실제 CSV·manifest·ZIP bytes
  hash를 모두 보존하지 않는다.
- admin security가 꺼진 개발 경로의 legacy token과 unknown-route `ADMIN`
  fallback은 FP-008 운영 endpoint에 사용할 수 없다.
- 현 Android HTTP client는 64 KiB 단일 JSON object 응답만 다루므로 목록·사진·
  ZIP을 안전하게 받을 transport가 없다.

## 2. leaf 목표와 보수적 완료 상한

저장소 내부 목표는 다음 수직 흐름을 완성하는 것이다.

1. 외부 provisioning된 관리자 기기만 challenge를 통과하고 운영 화면에 접근한다.
2. 신고 목록·상세·사진·위치·중복 후보를 사람이 검수한다.
3. 승인·반려·중복 결정을 필수 사유와 함께 append-only로 기록한다.
4. 승인된 신고만 수동 기관 전달용 export 대상이 된다.
5. 실제 수동 제출 뒤 외부 접수번호와 제출본 hash를 별도 receipt로 결속한다.
6. 모든 민감 조회·결정·export·receipt 작업이
   actor/session/device/purpose/correlation/time 감사와 연결된다.

내부 코드와 저장소 시험을 통과해도 실제 관리자·등록 기기·기관 제출·외부 검토와
`TC-FP-008-01~04`가 `NOT_RUN`이면 `GAP-017`의 완료 상한은 `PARTIAL`이다.
`IMPLEMENTED`, formal PASS, gate CLOSED, release eligible은 주장하지 않는다.

## 3. 고정할 최소 설계

### 3.1 검수 결정

기존 `Report.status = new|reviewed|resolved`를 확대하지 않고 별도 typed,
append-only review decision을 둔다.

- decision: `APPROVED | REJECTED | DUPLICATE`
- 필수 공통: decision ID, report ID, 단조 revision, idempotency key,
  reason code, note, actor, session, device, server time
- 정정 결정: 직전 유효 결정의 `decision_id`를 `supersedes_decision_id`로 명시
- 승인: 사진·위치·중복·개인정보 체크 결과
- 반려: 명시적 reason code와 설명
- 중복: 자기 자신이 아닌 유효한 대상 report ID
- 동시성: report row lock 뒤 `expected_updated_at`와 expected review revision을
  모두 검사하고, decision append·revision 증가·domain audit을 한 transaction으로
  처리한다.
- 동일 idempotency key와 동일 intent는 같은 결과를 반환하고, 다른 intent 재사용은
  409로 거부한다.
- `ReportReviewDecision` revision과 대응 append-only
  `REVIEW_DECISION_APPENDED` event는 같은 transaction에서 정확히 한 번 insert한다.
  DB trigger는 두 row의 UPDATE/DELETE를 거부하고, `(report_id, revision)`,
  supersedes chain과 event FK/unique가 어긋나거나 event가 빠지면 commit을
  rollback한다.

유효 최신 결정은 끊기지 않은 revision/supersedes chain의 마지막 행으로 계산한다.
일반 `status=reviewed`나 기존 payload의 `agency_review_verified`는 새 typed
`APPROVED`로 자동 승격하지 않는다. 재검수된 최신 `APPROVED`와 필수 체크가 모두
있어야 기관 export 자격이 생긴다.

`APPROVED`는 lifecycle status/history를 제외한 export 관련 report 필드, 실제
image bytes hash, 위치, 중복·개인정보 체크 결과를 canonical하게 묶은
`review_evidence_sha256`를 저장한다. quote 시 live evidence digest가 승인 당시
digest와 다르면 재검수 전 부적격이고, quote 뒤 바뀌면 export에서 409로 거부한다.

`Report.status`는 기존 lifecycle projection으로 유지하고 typed decision과
독립시킨다. status patch는 decision을 만들거나 supersede하지 않으며, decision도
status를 암묵적으로 바꾸지 않는다. UI와 export는 lifecycle status와
`effective_review_disposition/revision`을 별도 필드로 읽는다.

### 3.2 기관 전달

첫 leaf는 자동 기관 connector를 만들지 않는다.

- 기존 selection/redaction 로직은 재사용하되, side-effect가 있는 agency export는
  typed `POST /report-exports`로 분리한다. 기존 `GET /reports/export` 결과나
  legacy flag는 새 delivery receipt의 입력이 아니다.
- 다건 필터 export는 두 단계로 고정한다.
  1. `POST /report-export-quotes`: filter와 idempotency key를 받고 filter를
     정규화한 뒤 report ID, report
     `updated_at`, source report/subject deletion epoch, 유효 decision
     ID/revision/hash, image/payload hash를 완전 정렬한 immutable selection과
     `selection_sha256`, 짧은 expiry를 만든다. quote 생성 transaction은 관련
     deletion state를 잠그고 `DELETION_DRAINING | DELETION_PENDING | DELETED`를
     거부한다.
  2. `POST /report-exports`: quote ID, selection SHA-256, idempotency key와
     선택적 `supersedes_incident_id`만 받고 exact selection에 재인증한다.
     모든 최초 quote consume과 deletion 경로는 §3.5 전역 lock 순서만 사용한다.
     해당 경로의 공통 subset은 정렬한 `source deletion rows → quote header →
     artifact lifecycle header`이며 아직 header가 없으면 reserved audit ID의
     advisory/range lock을 사용한다. 모든 lock을 얻은 뒤 quote가 `ACTIVE`이고 live
     revision/hash/deletion epoch가 모두 같으며 source deletion state가 eligible인지
     다시 검사하고 initial artifact lifecycle commit까지 유지한다. 하나라도 다르면
     409 또는 stable deletion 410이며 부분 artifact·audit은 만들지 않는다.
     replacement가 아니면 incident ID는 반드시 absent다.
- quote는 한 번 소비한다. 동일 idempotency key+intent의 재시도는 완료된 audit의
  immutable artifact를 찾고 보존기간 안에서만 byte-exact 같은 ZIP을 반환하며,
  다른 intent 재사용은 거부한다. artifact가 7일 만료됐거나 삭제요청으로
  `DELETION_DRAINING | DELETION_PENDING | DELETED`가 되면 idempotency tombstone을
  보존해 재생성 없이 항상
  `410 ARTIFACT_EXPIRED_OR_DELETED`를 반환한다.
- quote header는 `ACTIVE | CONSUMED | EXPIRED | CANCELLED_DELETION` current
  projection과 revision을 가지고 각 terminal 전이는 같은 transaction의 append-only
  quote event와 결속한다. terminal quote와 idempotency key는 되살리거나 새 selection에
  재사용하지 않는다. quote create idempotency retry·조회·consume이 lock 뒤
  `ACTIVE`이면서 trusted now가 expiry와 같거나 늦음을 처음 관찰하면 업무 transaction을
  rollback한 뒤 §3.5 순서의 별도 expiry transaction으로 `ACTIVE → EXPIRED`와
  `QUOTE_EXPIRED` event를 원자 commit한다. 같은 key+intent는 그 뒤 stable
  `410 QUOTE_EXPIRED`이고 새 quote에는 새 key가 필요하다.
- immutable `ReportExportAudit`은 lifecycle 전이로 수정하지 않는다. 별도
  `ReportExportArtifactLifecycle` header가 audit ID, revision과
  `AVAILABLE | DELETION_DRAINING | DELETION_PENDING | DELETED` current
  projection을 소유하고, 각 전이는 같은 transaction에서 append-only lifecycle
  event를 추가한다.
- 삭제요청 접수 transaction도 §3.5 전역 lock 순서만 사용하고 lock 뒤 source state,
  quote state/expiry와 lifecycle/lease를 다시 검사한다. writer-preferred fair
  deletion gate가 대기하는 순간 새 quote/grant/read/replay/receipt/incident/
  attestation/release/lease와 shared send admission을 막는다.
  ACTIVE lease가 있으면 source deletion state와 lifecycle을 durable
  `DELETION_DRAINING` barrier로 전환하고 `ARTIFACT_DELETION_DRAINING` event를
  commit한다. 같은 transaction에서 관련 ACTIVE quote는 append-only 취소 event와
  함께 `CANCELLED_DELETION`으로 terminal 처리한다. DRAINING commit 뒤 새 quote,
  grant, read, replay, receipt/incident, attestation/release와 lease 생성은 모두
  거부되고 barrier 전에 시작한 exact lease의 terminal/reconciler만 허용한다.
  current bounded send가 gate를 먼저 잡았으면 그 chunk까지만 끝나고 이후 byte는
  금지된다. 마지막 ACTIVE lease가 `COMPLETED | ERROR` terminal이 되면 새 transaction이
  `DELETION_DRAINING → DELETION_PENDING`과 pending event를 commit한다. ACTIVE lease가
  처음부터 없으면 `AVAILABLE → DELETION_PENDING`으로 직접 전이한다. 삭제 API는
  `DELETION_PENDING` commit 전 성공을 응답하지 않는다. 그 commit 시점부터
  idempotent POST replay, GET download, adapter 전달, delivery receipt/attempt/
  outcome incident와 release evidence 사용을 모두 410 또는 credit 0으로
  fail-closed한다. 물리 ciphertext 삭제는 7일 SLA worker가 수행하고 완료 뒤
  `DELETED`로 전이하지만, pending 기간에 quote나 접근권한이 다시 열리지 않는다.
  삭제가 quote보다 먼저 commit되면 새 quote/create/consume은 zero-artifact로
  거부된다. export가 먼저 commit되면 삭제 transaction이 새 lifecycle도 같은
  tombstone 범위에 포함한다.
- quote는 `STANDARD` 위험도로 active session·registered device PoP, actor/session/
  device/purpose, canonical filter intent, owner와 idempotency key에 결속하고 생성
  audit과 rate limit을 적용한다. quote/export idempotency key namespace를
  분리한다. 같은 quote key+같은 normalized filter는 expiry 전 `ACTIVE`인 동안 원래
  quote·selection·expiry를 반환하고 `EXPIRED` 뒤에는 위 stable 410을 반환한다. 다른
  intent는 409이며, 새 quote가 필요하면 새 key를 요구한다.
  quote insert와 생성 audit은 한 domain transaction이다. 같은 owner/session만
  quote를 소비할 수 있다.
- quote 생성 시 `reserved_export_audit_id`, artifact `generated_at`,
  application-envelope key version과 nonce도 고정해 이후 멱등 재시도에서
  manifest/ZIP과 암호문 bytes가 바뀌지 않게 한다. 같은 key/nonce는 그 quote의
  같은 plaintext에만 쓰고 plaintext hash가 다르면 재사용하지 않는다. nonce는
  CSPRNG로 만들고 `(key_version, nonce)`를 DB에서 unique로 예약한다. 신규 quote의
  예약 충돌은 정해진 작은 횟수만 새 nonce로 재시도하고 소진 시 fail-closed한다.
  같은 quote idempotency key+intent 재시도는 새 nonce를 만들지 않고 최초 예약값을
  반환한다.
- CSV·manifest·ZIP bytes를 결정적으로 먼저 만들고 각 SHA-256, rows digest,
  count와 selection boundary를 `ReportExportAudit`에 저장한다. ZIP plaintext는
  application-envelope로 암호화해 ciphertext SHA-256 기반 private immutable
  path에 보존하고, 응답 때 검증·복호화한 exact plaintext bytes만 전송한다.
- CSV 행/column·manifest key·ZIP entry 순서, UTF-8/newline, canonical JSON,
  ZIP timestamp·mode, `ZIP_STORED`, extra/comment 없음까지 고정해 재실행 bytes가
  환경이나 시각에 따라 달라지지 않게 한다.
- 고정 byte 상한 안의 ZIP plaintext는 bounded memory에서 만들고 filesystem
  plaintext temp를 만들지 않는다. application-envelope ciphertext만 같은
  filesystem의 private temp에 mode `0600`으로 쓰고 file `fsync`와 digest 확인
  뒤 content-addressed final path에 no-replace atomic install하고 parent
  directory도 `fsync`한다. oversize, encrypt 전후 예외, process death,
  ciphertext `fsync`/install 실패 어느 경로에서도 raw ZIP filesystem residue는
  0이어야 한다. 시작 시에는 불완전 ciphertext temp만 보수적으로 정리한다.
  service 전용 root의 regular file만 `no-follow`로 읽고 public static path로
  노출하지 않는다. audit의 검증된 관리자 download route만 접근할 수 있다.
- immutable ZIP 설치가 끝난 뒤 quote `ACTIVE → CONSUMED`, export audit insert,
  initial `ReportExportArtifactLifecycle(state=AVAILABLE, revision=1)` header와
  append-only `ARTIFACT_AVAILABLE` event를 같은 DB transaction으로 정확히 한 번
  commit한다. 하나라도 실패하면 audit·quote·lifecycle 전체를 rollback한다. 모든
  공통 lock을 얻은 뒤 최종 commit 직전 trusted server now가 strict하게
  `quote_expires_at`보다 이른지 다시 검사한다. now가 같거나 늦으면
  audit·quote consume·lifecycle을 모두 rollback하고 외부에 byte/성공을 응답하지
  않으며 설치된 ciphertext는 DB 미참조 orphan reconciliation/GC 대상으로만 남긴다.
  rollback 뒤 별도 transaction이 §3.5 순서로 quote를 다시 잠가 still `ACTIVE`이고
  trusted now가 expiry와 같거나 늦음을 재검사한 뒤 `ACTIVE → EXPIRED`와 append-only
  `QUOTE_EXPIRED` event를 원자 commit한다. 같은 idempotency key는 이후 stable
  terminal `410 QUOTE_EXPIRED`만 반환하고 새 quote/nonce/artifact를 만들지 않는다.
  concurrent deletion terminal이 먼저 commit됐으면 그 terminal 결과를 되쓰지 않는다.
  다른 DB rollback도 설치된 orphan을 성공 artifact로 노출하지 않는다. commit 뒤
  응답 실패는 audit가 가리키는 exact ZIP으로 멱등 재응답한다. audit가
  가리키는 artifact가 없거나 hash/length가 다르면 재생성하지 않고 fail-closed한다.
- pre-commit 재시도에서 final path가 이미 있으면 directory fd에 상대적인
  no-follow regular file, service owner, mode `0600`, `st_nlink == 1`,
  ciphertext hash와 length를 모두 검사하고 검증 전후 device/inode identity가
  같은 exact 파일만 재사용한다. hardlink나 검사 중 교체는 fail-closed한다.
  artifact install→DB commit 구간과 orphan GC는 같은 maintenance lock 또는
  staging lease로 상호배제한다.
- 실제 기관 전달은 승인된 수동 채널로만 수행한다.
- server-owned `ManualDeliveryChannelRegistry`는 immutable entry ID와
  `ACTIVE | REVOKED` lifecycle, revision/epoch 아래 exact institution, channel,
  issuer namespace, adapter package/component 또는 portal origin과
  signing-certificate SHA-256, recipient endpoint/account identity를 소유한다.
  verification mode도 `ISSUER_SIGNATURE`, `INDEPENDENT_EXTERNAL_VERIFIER`,
  `UNVERIFIABLE` 중 하나와 issuer trust anchor 또는 verifier identity/config
  revision에 결속한다. 한 issuer namespace는 한 고정 발급자 scope에만 매핑되고
  caller 입력으로 institution/channel/recipient를 바꿀 수 없다. lifecycle
  revision/event는 append-only이고 `REVOKED` entry는 되살리지 않는다.
- server-owned authorization policy는 `ACTIVE | REVOKED` lifecycle과
  revision/epoch 아래 actor/role, 허용 operation, institution/channel과 exact
  report ID set 또는 canonical report-set predicate를 소유한다. quote selection,
  export audit과 manual-delivery challenge/grant/lease는
  policy ID/revision/epoch와 authorized report-set digest를 결속한다. caller role
  문자열이나 기관 선택은 이 정책을 대체하지 않는다.
- grant consume마다 immutable `ManualDeliveryStreamAttempt` header와 append-only
  `ManualDeliveryStreamEvent` revision chain을 만든다. header는 attempt/grant/
  consume/lease ID, channel-registry entry/revision/epoch, authorization policy
  ID/revision/epoch와 report-set digest, actor/session/device, audit ID, artifact
  plaintext hash/length와 §3.6의 exact `stream_deadline_at`을 결속한다. event는
  `AUTHORIZED → STARTED → COMPLETED | ERROR`만 허용하고 server time, 실제 전송
  bytes, exact adapter/recipient tuple과 failure evidence를 남긴다. grant one-shot
  consume, attempt header와 `AUTHORIZED/STARTED`, session/device 및 artifact shared
  lease 획득은 한 transaction으로 commit하며 그 전에는 첫 byte를 내보내지 않는다.
  정상 exact-length 송신만 `COMPLETED`, timeout·revoke·expiry·client/network abort는
  다음 chunk 전에 중단하고 `ERROR`다. terminal event 저장 실패는 성공으로 응답하지
  않고 운영 incident에 남긴다. client terminal 요청은 audit/attempt/grant/consume/
  lease ID, expected event revision, `COMPLETED | ERROR`, transmitted bytes,
  failure evidence와 idempotency key를 raw intent에 결속한다. same-key/same-intent는
  기존 terminal event, 다른 intent는 409다. `COMPLETED`는 `stream_deadline_at` 전
  exact artifact length만 허용하고, revoke/expiry/timeout은 backend가 §3.5 전역
  lock과 재검증 아래 아직 non-terminal인 attempt에 `ERROR`를 append할 수 있다.
- `ReportExportAudit.audit_id`에 결속된 append-only delivery receipt 저장·조회
  경계를 추가한다.
- caller가 입력한 접수번호·상태만으로 authoritative receipt를 만들지 않는다.
  immutable `ExternalReceiptEvidence`는 bounded raw external response bytes와
  SHA-256/length/media type, capture source, trusted server capture time,
  issuer가 서명한 observed time·signature/key/chain 또는 독립 external verifier의
  signed attestation raw bytes/digest·identity/config revision을 보존한다.
  `VERIFIED` evidence는 issuer namespace/receipt ID, institution/channel/recipient,
  external status, manual attempt correlation과 exact submitted artifact
  hash/length 및 raw response SHA-256/length/media type을 모두 issuer signature가
  덮거나 독립 verifier attestation으로 검증해야 한다.
  verified external observed time은 attempt `COMPLETED`보다 이르지 않고 trusted
  capture/server now보다 늦지 않아야 한다. signature/verifier 검증 실패·시간
  역전·tuple/hash 불일치는 거부한다.
  `UNVERIFIABLE` channel이나 evidence는 별도 history-only observation만 남기고
  authoritative receipt stream, submitted/received 표시나 release credit을 만들 수
  없다.
- exact HIGH delivery-receipt route만 external evidence ingress를 소유한다. bounded
  request는 raw response bytes/media type과 issuer signature 또는 verifier input을
  포함하고 raw body intent가 그 bytes를 덮는다. backend가 configured trust
  path로 검증한 뒤 server-assigned evidence ID/capture time을 부여한다. nonlocking
  prelookup 뒤 §3.5 순서로 registry/session→channel/authz→source/quote/lifecycle
  gate→audit→attempt→lease→receipt range key→evidence raw-hash range key를 잠가
  immutable evidence와 receipt revision을 한 transaction으로 insert한다. 마지막 lock
  뒤 lifecycle `AVAILABLE`, deletion gate OPEN과 artifact expiry를 다시 검사하며 실패나
  `UNVERIFIABLE`이면 authoritative receipt delta는 0이다.
- 실제 제출 후 기존 agency submission receipt 계약을 원형으로 기관·수동 채널·
  issuer namespace·외부 접수번호·상태·회신, 승인 adapter의 exact package/component와
  signing-certificate SHA-256 또는 exact HTTPS portal origin, 실제 recipient
  endpoint/account identity, channel-registry entry/revision/epoch, audit ID와
  `manual_delivery_attempt_id`,
  CSV·manifest·ZIP hash, immutable external evidence ID/raw response
  SHA-256/length/media type와 verified external observed time,
  actor/session/device/time을 결속하고 저장된 export audit와 모두 대조한다.
- audit별 delivery receipt stream은 revision 1부터 단조 증가한다. authoritative
  receipt의 기존 `received | accepted` 계약을 보존해 첫 상태는 `RECEIVED` 또는
  `ACCEPTED`, 유일한 후속 전이는 `RECEIVED → ACCEPTED`다. 각 행은 receipt ID,
  expected revision, idempotency key와 직전 receipt ID를 결속하고 그 revision
  status와 일치하는 별도 `VERIFIED` external evidence를 참조한다. 첫
  authoritative receipt 전에 발생한 외부 거부·전송 실패만 별도 failed-attempt
  audit에 남긴 뒤 새 export부터 다시 시작한다. `RECEIVED` 뒤 후속 거부가 오면
  receipt revision을 되쓰거나 failed-attempt로 가장하지 않고 별도
  `DELIVERY_OUTCOME_INCIDENT`로 stream을 terminal 처리한 뒤 새 export부터
  시작한다. `ACCEPTED`는 receipt revision에는 terminal이지만 이후 확인된 기관
  철회·오접수·검증 무효를 append-only outcome incident로 남길 수 있다. incident
  뒤에는 receipt revision이나 기존 stream을 바꾸지 않고 새 export부터 시작한다.
- audit마다 유효 stream은 하나이고 `(audit_id, revision)`과 idempotency key는
  unique다. authoritative receipt stream header는 같은 target tuple의
  `COMPLETED` manual-delivery attempt를 필수로 참조하고
  `manual_delivery_attempt_id`에 1:1 unique다. 모든 receipt revision은 그
  immutable attempt를 상속하며 다른 attempt나 audit에 다시 결속할 수 없다.
- receipt intent는 audit ID, manual-delivery attempt/grant/consume ID, external
  raw response SHA-256/length/media type와 issuer signature/verifier input digest, expected
  revision, previous receipt ID, channel-registry entry/revision/epoch, 기관, 수동
  채널, 승인 adapter target identity, recipient endpoint/account identity,
  issuer namespace, 외부 접수번호, 상태와
  reply/evidence hash를 canonical하게 결속한다.
  동일 idempotency key+동일 intent 재시도는 기존 receipt/revision을 반환하고,
  다른 intent 재사용은 409다. row lock과 uniqueness로 병렬 같은 revision 생성은
  한 행만 성공시킨다. stream header ID, receipt ID와 UTC `server_created_at`은
  최초 성공 insert가 배정하고 request intent에는 넣지 않는다. revision 1의
  previous receipt ID는 canonical explicit null, 이후 revision은 직전 ID와
  byte-exact로 일치해야 한다. `receipt_fresh_until`은 server가
  `min(verified_external_observed_at + 7일, artifact_expires_at)`로 계산하며
  caller 값이나 receipt row의 늦은 backfill 시간이 아니다. `ACCEPTED` insert
  transaction은 nonlocking prelookup 뒤 §3.5 순서로 registry/session→channel/authz→
  source/quote/lifecycle gate→audit→attempt→lease→receipt/evidence를 잠근다. 마지막
  lock 뒤 lifecycle `AVAILABLE`, deletion gate OPEN, artifact/receipt freshness,
  verification mode가 verifiable+`VERIFIED`, external status가 accepted이며 tuple,
  attempt correlation, submitted hash/length가 모두 같은지 다시 검사한다.
- 외부 접수번호 tuple
  `(issuer_namespace_id, external_receipt_id)`은 receipt revision 행마다 unique로
  두지 않고 하나의 delivery stream header에서 조건부 unique다. 같은 header의
  `RECEIVED → ACCEPTED` revision은 그 tuple과 registry가 고정한 institution/channel을
  공유하고, 다른 audit/stream이 재사용할 수 없다. `issuer_namespace_id`는 승인된
  portal tenant/mailbox 등 외부 발급자가 ID uniqueness를 보장하는 고정 namespace로,
  registry FK와 uniqueness constraint로 한 발급자 scope에만 매핑되며 caller,
  institution/channel 또는 recipient 변경으로 바꿀 수 없다. recipient identity는
  별도 필수 binding이지만 uniqueness namespace를 넓히는 입력은 아니다.
- export audit row를 잠근 상태에서 `AUTHORITATIVE_RECEIPT_STREAM` 또는
  `FAILED_DELIVERY_TERMINAL` marker 중 정확히 하나만 조건부 unique로 생성한다.
  실패 marker가 먼저 commit된 audit에는 receipt를 추가할 수 없고, receipt
  stream이 시작된 audit에는 failed-attempt를 추가할 수 없다. 외부 거부·전송
  실패 뒤 다시 제출하려면 새 export audit부터 시작한다. receipt stream과
  공존할 수 있는 `DELIVERY_OUTCOME_INCIDENT`는 이미 결속된 external receipt
  tuple, incident reason/evidence/time을 append-only로 기록하고 기존
  `RECEIVED | ACCEPTED` 사실을 지우지 않는다. incident 생성 시 forward pointer를 요구하지
  않으며, 이후 새 export가 생성되면 그 새 audit가 `supersedes_incident_id`로
  뒤에서 결속한다. replacement export transaction은 old incident를 nonlocking
  prelookup하고 §3.5 순서로 source/quote/lifecycle과 reserved new audit range를
  old incident보다 먼저 잠근다. 마지막 lock 뒤 같은 actor와 같은 report ID set인지,
  incident가 terminal이고 아직 replacement가 없는지 다시 검사한 뒤 새 audit
  back-reference와 별도 append-only 1:1
  `ReplacementExportLink(incident_id, replacement_audit_id)` insert를 함께
  commit한다. old incident row는 수정하지 않는다. report revision은 재검수로
  달라질 수 있지만 새 quote의 latest typed approval을 다시 통과해야 한다.
- outcome incident는 export audit/receipt stream row lock 아래 latest 상태가
  `RECEIVED | ACCEPTED`이고 incident가 아직 없을 때 만든다. latest가 `RECEIVED`면
  incident-first는 후속 `ACCEPTED`를 막고, `ACCEPTED`-first는 새 expected
  revision/status에 결속한 incident만 허용한다. incident 뒤 receipt revision은
  금지되고 release credit은 항상 0이다. incident intent에는 audit ID,
  manual-delivery attempt ID, expected receipt revision/status, external receipt tuple,
  channel-registry entry/revision/epoch, recipient identity,
  reason/evidence hash,
  actor/session/device와 idempotency key를 모두 결속한다. server time은 최초
  성공 insert가 배정한 저장 결과이며 재시도 request intent에 넣지 않는다.
  same-key/same-intent는 기존 incident를 반환하고 same-key/different-intent는
  409다.
- failed-attempt intent와 행에는 audit ID, terminal manual-delivery attempt/grant/
  consume ID, channel-registry entry/revision/epoch, institution, 수동 channel,
  승인 adapter의 exact package/component 또는 portal origin, recipient
  endpoint/account identity, failure code, evidence hash, idempotency key와 외부
  응답 ID가 있으면 그 값을 포함한다. 전송 실패·abort는 같은 `ERROR` attempt를,
  exact-length 송신 뒤 receipt 전 외부 거부는 같은 `COMPLETED` attempt와 외부 실패
  evidence를 참조한다. canonical
  request intent에는 이 client 고정 필드만 넣고 attempt ordinal/server time은
  최초 성공 insert가 배정한 저장 결과로만 둔다.
- export 성공만으로 `submitted`, `received`, `complete`를 표시하지 않는다.
- backend, v1/v2 receipt CLI와 release-evidence validator 모두 `api` channel을
  허용하지 않는다.
- 기존 v1 agency manifest/receipt validator의
  `status=reviewed + agency_review_verified` 계약은 역사·legacy 입력에만 유지한다.
  FP-008 export/receipt에는 typed latest `APPROVED`, review evidence, export audit를
  요구하는 versioned v2 manifest/receipt validator와 CLI를 추가한다. v1 자료를
  v2로 자동 승격하지 않고 release-evidence validator도 schema별로 명시 dispatch한다.
  FP-008 candidate/control package가 선택된 실행에서는 v2만 release-evidence
  입력으로 인정하고 v1은 history-only·release credit 0이다. unknown, mixed
  v1/v2 또는 명시되지 않은 downgrade는 거부한다.
- v2 receipt serialization은 DB `RECEIVED | ACCEPTED`를 필수 lowercase
  `submission_status=received | accepted`로만 투영하고 stream header ID, receipt
  ID, previous receipt ID의 explicit null/value, UTC `server_created_at`,
  verified external observed time, trusted server capture time,
  `receipt_fresh_until`, external evidence
  ID/verification mode, issuer key/chain/signature digest 또는 verifier
  identity/config revision/attestation digest, raw response SHA-256/length/media
  type, manual-delivery attempt ID, channel-registry
  entry/revision/epoch, institution, channel, approved adapter target identity,
  recipient endpoint/account identity, issuer namespace, external receipt ID,
  actor/session/device, audit/CSV/manifest/ZIP hash, artifact
  `generated_at/expires_at`, selection/evidence digest와 revision을 모두 필수로 둔다.
- raw v2의 lowercase `received | accepted`는 signed attestation의 uppercase
  `receipt_status=RECEIVED | ACCEPTED`에 각각 exact 1:1 mapping한다. 다른 casing,
  unknown 값이나 raw/attestation 불일치는 거부한다.
- v2 release evidence는 verified external observed time 후 7일 안의 fresh receipt와
  artifact `generated_at` 후 7일 안에 실제 보존 중인 ZIP을
  함께 재검증하고, latest stream이 `ACCEPTED`이며 outcome incident가 없을 때만
  자격 후보가 된다. artifact lifecycle이
  `DELETION_DRAINING | DELETION_PENDING | DELETED`면 물리 파일 존재와 무관하게
  credit 0이다. `RECEIVED` 또는 incident terminal stream도
  release credit 0이고 `UNVERIFIABLE` channel/evidence는 status와 무관하게
  history-only·credit 0이다. ZIP이 만료·삭제된 뒤 audit/receipt는
  history-only·release credit 0이며, 기존 30일 v1 freshness를 이용해 이 경계를
  우회할 수 없다.
- receipt 파일 자체를 latest-state 증명으로 쓰지 않는다. release 검증 직전에
  backend가 관련 coordination key를 nonlocking prelookup하고 §3.5 순서로
  registry/session, channel/authz, source/quote/lifecycle, export audit,
  attempt/lease, receipt/incident/link와 immutable external evidence를 한 read
  transaction에서 잠가 `walksafe.delivery-current-stream-attestation.v2`를 만들고, 기존
  lifecycle이 `AVAILABLE`일 때만 승인된 release-evidence signer로 서명한다.
  `DELETION_DRAINING`부터는 새 attestation을 만들지 않는다. attestation은 audit ID, latest
  stream header/receipt/previous receipt ID, revision/status,
  `failed_delivery_terminal_absent=true`,
  `delivery_outcome_incident_absent=true`, receipt `server_created_at/fresh_until`,
  verified external observed time, trusted server capture time, channel-registry
  `ACTIVE` state, verification
  mode, issuer key/chain/signature digest 또는 verifier identity/config/attestation
  digest, raw external response SHA-256/length/media type,
  institution/channel/issuer namespace/external receipt ID,
  channel-registry entry/revision/epoch, manual-delivery attempt ID와 terminal
  `COMPLETED` event revision/digest/transmitted bytes/time,
  adapter/recipient identity, exact v2 receipt raw SHA-256/bytes와 canonical row
  digest, artifact plaintext/ciphertext hash·length와 `generated_at/expires_at`,
  deletion state, DB snapshot/as-of와
  one-shot gate invocation nonce를 결속한다. attestation은 같은 release-gate
  invocation에서만 생성·검증·소비하고 캐시하거나 최대시간 동안 재사용하지 않는다.
  생성과 소비는 artifact lifecycle의 fair shared gate 안에서 이뤄지고 같은 read
  transaction의 receipt-stream/outcome-incident locks도 consume 또는 rollback까지
  유지한다. attestation-first면 incident가 consume 뒤까지 기다리고
  incident-first면 `delivery_outcome_incident_absent=true`를 만들 수 없다.
  attestation-shared-first면 같은 invocation의 소비 또는 deadline/disconnect
  rollback 뒤 gate를 반환하고, deletion-writer-first면 새 attestation을 만들지
  않는다. server가 정한 짧은 `attestation_deadline_at`을 signed payload에 넣고
  그 전에만 소비한다. timeout, validator disconnect/process death면 read
  transaction과 shared gate를 rollback·해제해 deletion writer를 붙잡지
  않는다. consume linearization에서 trusted server now가 receipt freshness,
  artifact expiry와 attestation deadline보다 모두 이르고 channel/trust config가
  여전히 `ACTIVE`+same revision인지 다시 검사한다. validator는
  signer·raw bytes·invocation nonce, immutable external raw response bytes의
  hash/length/media type, issuer signature 또는 verifier attestation과
  receipt/attempt/artifact를 모두 대조한다. raw swap이나 attestation 없이는 credit
  0이다.

사용자 정정 피드백의 종단 상태기계와 자동 기관 연동은 `FP-033/GAP-042` 등 인접
범위와 겹치므로 이 leaf에 묵시적으로 포함하지 않는다.

### 3.3 등록 기기

- 관리자 앱은 Android Keystore의 non-exportable 기기 key로 server challenge에
  서명하고, backend registry는 public-key fingerprint와
  `PENDING | ACTIVE | REVOKED` lifecycle을 보존한다.
- proof challenge 발급 endpoint만 per-request PoP의 명시적 예외다. exact
  allowlist와 rate limit을 적용하고, bearer용 challenge는 ACTIVE registry/session을
  먼저 검증한다.
- provisioning·login·recovery와 bearer 요청용 challenge는 server가 발급한 짧은
  TTL의 일회성 nonce, purpose, key fingerprint, method, concrete path,
  device-registry revision/epoch, 적용되는 authorization policy
  ID/revision/epoch와 authorized report-set digest를 결속한다. manual-delivery
  operation이면 channel-registry entry/revision/epoch도 결속하고, 적용되지 않는
  public auth 필드는 canonical explicit absent marker를 사용한다. 이 필드와
  `walksafe-admin-intent-v1`을 함께 결속한다. session이 없는 provisioning·login·
  recovery는 session ID의 명시적 empty marker를, bearer 요청은 검증된 session
  ID를 서명한다. provisioning activation은 외부 승인된 PENDING key에만 허용한다.
- self-enrollment는 금지한다. 별도 승인된 provisioning 절차만 `ACTIVE`로 전이할
  수 있고 actor·근거·시각을 append-only로 남긴다.
- PENDING key는 provisioning activation에만 쓰고 login/recovery/business challenge는
  ACTIVE key에만 발급한다. REVOKED key에는 어떤 challenge도 발급하지 않는다.
- login·recovery를 포함한 public authentication 요청과 모든 bearer read/write
  요청은 각각 새 challenge에 대한 Keystore signature를 보내는 per-request PoP를
  사용한다. backend는 parsing 전에 실제 raw query/body bytes로 intent를 다시
  계산하고 proof 소비 시 registry row를 잠가 현재 revision/epoch를 다시 대조한다.
  provisioning activation은 그 시점에도 `PENDING`, login/recovery와 모든 bearer
  요청은 그 시점에도 `ACTIVE`여야 한다. registry/key, TTL, purpose, session,
  method, path, intent와 applicable channel/authz policy row를 잠가 모두
  `ACTIVE`+same revision/epoch이고 actor/role/operation/institution/report set이
  허용되는지 admission transaction에서 검사한 뒤 challenge를 한 번만 소비한다.
  nonce 재사용·만료·
  다른 key/session/method/path/intent와 body/query 1 byte 변경은 업무 진입 전에
  거부한다.
- admission commit 뒤 effect transaction은 provisioning activation이면 같은
  registry row가 여전히 `PENDING`+same epoch인지, login/recovery이면
  `ACTIVE`+same epoch인지 다시 잠가 검사한다. activation 또는 새 session insert,
  recovery의 기존 session 폐기와 해당 domain/security audit을 그 recheck와 함께
  commit한다. admission과 effect 사이 revoke/revision 변경이 먼저 commit되면
  session·activation은 0이고 이미 소비한 nonce와 admission audit만 보존한다.
- credential-bearing login/recovery/reauth의 raw intent digest와 signature는
  admission 검증 뒤 보존하지 않고 durable audit·로그에도 기록하지 않는다.
- 기기 폐기는 registry 전이와 해당 기기의 모든 session 폐기를 한 transaction으로
  처리한다.
- header/body/session의 device ID 일치나 앱이 만든 UUID allowlist는 등록 기기
  판정을 대체하지 않는다.
- 실제 기기 등록, production 서명과 비공개 배포는 외부 잔여로 둔다.

### 3.4 Android 보안 경계

- access token은 계속 `AdminSecurityController` 내부에 두고 token getter를
  추가하지 않는다. controller 소유 authorized executor는 closed typed command에서
  실제 보낼 immutable method/path/query/body bytes와 intent를 먼저 확정한다.
- public login/recovery는
  `challenge → sign → 동일 immutable request 전송`, STANDARD read/quote는
  `challenge → session-bound sign → 동일 immutable request 전송`, HIGH operation은
  `immutable business bytes 확정 → reauth 요청용 challenge/sign → exact reauth로
  operation grant 발급 → business 요청용 새 challenge/session-bound sign →
  local grant one-shot consume → 재인증 때 확정한 동일 business bytes 전송`을
  한 직렬 작업으로 수행하고 coordinator에는 typed 결과만 반환한다.
- 각 business mutation은 서로 다른 exact operation binding을 사용한다.
  - HIGH 검수 결정: `report.review-decision.create + POST +
    /reports/{uuid}/review-decisions`
  - HIGH 기존 상태 변경: `report.status.patch + PATCH +
    /reports/{uuid}/status`
  - STANDARD export quote 생성: `report.export-quote.create + POST +
    /report-export-quotes`
  - HIGH 기관 export·audit 생성: `report.export.create + POST +
    /report-exports`
  - HIGH 전달 수신증: `report.delivery-receipt.create + POST +
    /report-exports/{audit_id}/delivery-receipts`
  - HIGH 전달 실패기록: `report.delivery-attempt.record + POST +
    /report-exports/{audit_id}/delivery-attempts`
  - HIGH 수신/접수 뒤 outcome incident:
    `report.delivery-outcome-incident.create + POST +
    /report-exports/{audit_id}/delivery-outcome-incidents`
  - HIGH 수동 전달 grant: `report.manual-delivery.grant.create + POST +
    /report-exports/{audit_id}/manual-delivery-grants`
  - HIGH 수동 전달 stream 시작:
    `report.manual-delivery.stream.open + POST +
    /report-exports/{audit_id}/manual-delivery-grants/{grant_id}/consume`
  - HIGH 수동 전달 stream terminal:
    `report.manual-delivery.stream.terminal + POST +
    /report-exports/{audit_id}/manual-delivery-attempts/{attempt_id}/terminal`
- 민감 read도 별도 closed exact operation binding을 사용한다.
  - STANDARD 목록: `report.list.read + GET + /reports`
  - STANDARD 상세: `report.detail.read + GET + /reports/{uuid}`
  - HIGH 원본 사진: `report.image.read + GET + /reports/{uuid}/image`
  - STANDARD 중복 후보:
    `report.duplicate-check.read + GET + /reports/duplicate-check`
  - STANDARD export audit/receipt 조회: `report.export-audit.read + GET +
    /report-exports/{audit_id}` 및 `report.delivery-receipt.read + GET +
    /report-exports/{audit_id}/delivery-receipts`
  - HIGH export download: `report.export.download + GET +
    /report-exports/{audit_id}/artifact`
- 검수 결정을 기존 `report.status.patch`로 대체하지 않는다.
- failed-attempt는 append-only domain audit이며 receipt 상태를 바꾸지 않는다.
  §3.5 nonlocking prelookup과 전역 순서로 export audit 뒤 위 delivery terminal
  marker를 잠그고 audit ID,
  terminal manual attempt/grant/consume ID, channel-registry entry/revision/epoch,
  institution/channel/issuer namespace, 승인 adapter/recipient identity, failure
  code/evidence hash, 선택적 external response ID와 idempotency key를 결속한다.
  attempt ordinal/server time은 insert 결과에만 포함한다. same-key/same-intent는 기존
  행, same-key/different-intent는 409로 처리한다.
- 모든 protected request는 media type, raw query bytes SHA-256과 실제 전송할 raw body bytes
  SHA-256을 길이-prefix한 `walksafe-admin-intent-v1` record로 만들고 그
  SHA-256을 intent로 사용한다. JSON 재직렬화 결과를 비교하지 않는다.
- record의 magic/version과 field tag/order, media type 표현, UTF-8, unsigned
  big-endian 길이 폭, absent/empty query/body 표현과 최대 길이를 Java/Python
  byte-level golden vector로 고정한다.
- Android는 보낼 UTF-8 body bytes를 먼저 확정해 intent를 계산하고, backend는
  parsing 전 bounded raw ASGI body/query bytes로 같은 record를 계산한다.
- 새 nonce를 action/method/concrete path/intent에 함께 결속한다.
- controller는 reauth server echo의 action/method/path/intent/grant ID를 모두
  검사하고 business proof를 만든 뒤 local grant를 실제 요청 직전에 한 번
  선소비한다. backend도 business raw body/query에서 intent를 다시 계산하고
  business proof nonce와 같은 grant를 admission transaction에서 함께 소비한다.
- 위 `{uuid}`와 `{audit_id}`는 schema template일 뿐이다. 재인증 요청·서버 echo·
  실제 business 요청은 placeholder가 아니라 대상 ID가 치환된 동일한 concrete
  canonical path에 결속한다.
- 구형 3인자 reauthenticate와 coarse high-risk gate는 거부 전용으로 유지한다.
- FP-008 route는 exact operation allowlist와 server-issued scope를 요구하고
  unknown route를 default-deny한다. legacy admin token, 사용자 앱 token과 사용자
  gateway audience/role은 어느 admin route에서도 인정하지 않는다.
- read 요청에는 필수 purpose와 correlation ID를 포함하고 검증된
  `request.state.admin_security_identity`의 session/device를 audit에 사용한다.
- 목록·상세·사진·export download도 closed typed operation, concrete path,
  per-request PoP와 controller-owned executor만 사용한다. 민감 read audit을 먼저
  durable commit하지 못하면 503으로 거부하고 JSON·image·ZIP의 첫 byte도
  반환하지 않는다. GET의 empty body와 raw query도 intent에 포함한다.
- 완료된 export POST의 멱등 replay도 fresh HIGH grant를 요구하고 원 audit의
  actor/session/device/purpose 및 원 quote owner와 모두 일치해야 한다. 다른
  session/device는 POST replay를 사용할 수 없고, 별도 HIGH
  `report.export.download`에서 새 read audit을 남긴 경우에만 보존 중 artifact를
  받을 수 있다.
- replacement export의 optional `supersedes_incident_id`도
  `report.export.create` HIGH raw intent와 idempotency intent에 결속한다. unknown
  incident, cross-actor, 다른 report ID set, non-terminal 또는 이미 replacement가
  연결된 incident는 artifact 생성 전에 거부한다.

### 3.5 감사와 transaction

현재 보안 구조에 맞춰 두 transaction을 명시적으로 구분한다.

1. admission transaction: STANDARD는 business proof nonce, HIGH는 business proof
   nonce와 operation grant를 동시 선소비하고 security
   `SUCCESS/DENIED/ERROR` audit
2. effect/domain transaction: provisioning activation, login/recovery session,
   review/status/quote/export/receipt/failed-attempt/outcome-incident/manual-delivery
   grant·consume·client terminal 변경과 해당 domain audit

FP-008 coordination row의 유일한 전역 lock 순서는 다음과 같다.

| 순서 | row/fence class | 같은 class 안의 정렬 key |
|---:|---|---|
| 1 | device registry | device ID |
| 2 | admin session | session ID |
| 3 | proof challenge / security operation grant | type, challenge/grant ID |
| 4 | manual channel registry | channel entry ID |
| 5 | actor/role/report-set authorization policy | policy ID |
| 6 | source deletion state / source report | source type, source/report ID |
| 7 | export quote header | quote ID |
| 8 | artifact lifecycle / fair deletion gate | reserved export audit ID |
| 9 | export audit / manual-delivery grant | row type, audit/grant ID |
| 10 | manual-delivery attempt | attempt ID |
| 11 | protected access lease | lease ID |
| 12 | receipt stream/marker/revision/incident/link | row type, audit ID, revision, row ID |
| 13 | external receipt evidence | evidence ID 또는 raw-hash idempotency range key |

모든 protected effect/terminal, timeout·process-death reconciler, device/channel/policy
revoke, deletion/retention, receipt와 same-invocation attestation 경로는 먼저
nonlocking prelookup으로 immutable FK와 필요한 key 전체를 열거한다. 그 결과로 위
순서의 applicable row/range/advisory lock만 잡고, 같은 class는 표의 bytewise
canonical key로 정렬한다. 마지막 lock 뒤 registry/session/channel/policy state와
epoch, source deletion/report set, quote/lifecycle/audit, attempt/lease,
receipt/incident/evidence 및 prelookup key set 전체를 다시 읽어 검증한다. FK/key
set이 바뀌었거나 뒤늦게 더 이른 class가 필요하면 모든 lock을 풀고 prelookup부터
재시도하며 역순·late lock을 허용하지 않는다. 외부 verifier/network 호출은 lock
전에 끝내고 signed result/config revision을 lock 뒤 다시 검증한다.

admission이 거부되면 security denial audit은 남고 domain table은 변하지 않는다.
admission 뒤 domain 처리가 실패해도 소비한 nonce와 security audit은 되돌리지
않으며 동일 mutation은 새 nonce로 재시도한다. domain audit 저장 실패는 그
domain 변경만 함께 rollback한다.
effect/domain transaction은 §3.5의 첫 두 class에서 registry/session revision을
잠근 채 유지하고 commit 직전에 operation이 요구하는 `PENDING | ACTIVE` state와
admission epoch가 같은지 다시 읽어 확인한다.
manual-delivery grant create/consume/client terminal은 channel-registry
revision/epoch와 artifact lifecycle/expiry도 함께 잠가 재검사한다. 모든
report/admin effect는 applicable authorization policy와 exact target report set도
잠가 `ACTIVE`+same revision/epoch와 actor/role entitlement를 다시 검사한다.
admission 뒤 revoke나 registry/channel/policy
revision 변경이 먼저 commit되면 effect/domain 변경은 rollback하고 이미 소비된
nonce와 security audit만 보존한다.
- DB `ProtectedAccessLease` header는 lease ID, resource kind/ID, audit/attempt,
  actor/session/device, registry/channel/authz/lifecycle epochs, `deadline_at`과
  `ACTIVE | COMPLETED | ERROR` projection을 보존하고 각 전이를 append-only lease
  event와 결속한다. `ACTIVE` conflict index/FK로 row-lock transaction이 끝난 뒤에도
  deletion/revoke가 outstanding lease·attempt를 발견한다. lease 시작은 §3.5의
  applicable earlier lock과 lifecycle lock 아래 `AUTHORIZED/STARTED`와 원자 commit하고,
  terminal audit과 lease terminal도 한 transaction이다. deletion은 nonlocking
  prelookup 뒤 §3.5의 applicable earlier class와 lifecycle/fair deletion gate를 잡고
  audit→attempt→ACTIVE lease rows를 이어서 잠근다. 전체 state/key set을 재검증한 뒤
  `DELETION_DRAINING` barrier를 durable commit해 신규 lease/read를 차단한다.
  process death·deadline expiry reconciler는 아직 ACTIVE인
  lease와 manual attempt에 idempotent `ERROR` event를 append하고 권한을 회수한다.
  terminal/reconciler도 applicable earlier class를 전역 순서로 잡은 뒤
  attempt→lease를 잡고 전체 epoch/barrier를 재검증하며, DRAINING에서는 barrier 전에
  존재한 lease의 terminal 외 domain delta를 만들지 않는다.
read는 audit transaction에서 registry/session row를 fresh snapshot으로 잠가
applicable channel/authz policy, exact result/target report set까지
`ACTIVE`+same epoch와 entitlement이고 deletion gate가 OPEN인지 다시 검사한 뒤
session/device shared read lease를 획득한다.
append-only `AUTHORIZED/STARTED`를 원자 commit한다. JSON도 bounded response
terminal까지 logical lease를 유지하고, image/ZIP/manual adapter binary는 첫 byte를
포함한 매 chunk 직전에 유효 lease를 검사한다. bounded JSON response 전체 또는 각
binary chunk의 실제 send 동안 session/device shared send lock을 보유한다.
writer-preferred deletion gate가 queued/DRAINING이면 새 send lock을 주지 않는다.
revoke/session 폐기는 §3.5 순서로 같은 row와 lease를 exclusive로 잠가 새 send를 차단하고
lease를 무효화한다. shared-send-first면 현재 bounded JSON response 또는 한 chunk만
끝난 뒤 revoke가 commit하고, revoke-exclusive-first면 첫 byte 또는 다음 chunk를
거부한다. lease 취소·timeout이면 다음 byte 전에 중단하고 `ERROR`를 남긴다.
channel-registry 또는 authorization policy revoke도 epoch를 올리고 outstanding
challenge/grant/active lease/attempt를 같은 방식으로 무효화하며 backend가
non-terminal stream에 `ERROR`를 append한다.

모든 read/status/export/decision/receipt audit은 actor, session, device,
purpose/reason, correlation ID, operation, target, outcome과 server time을 가진다.
binary lifecycle audit은 HIGH 원본 image GET, 최초 `POST /report-exports`, 같은
POST의 멱등 재응답, export GET download와 manual-delivery grant consume stream
모두에 적용한다. 응답 첫 byte 전에
append-only `AUTHORIZED`와
`STARTED`를 binary target identifier와 content hash/length에 결속해 durable
commit한다. image는 report UUID와 image hash/length를, export는 audit ID와
plaintext ZIP hash/length를 사용한다. manual stream은 attempt/grant/consume/lease
ID와 channel-registry target tuple도 사용하며 위 `ManualDeliveryStreamEvent`가
authoritative binary lifecycle audit이다.
최초 export POST, 같은 POST의 replay와 export GET의 artifact stream deadline은 각각
`min(session_expiry, operation_grant_expiry, artifact_expires_at,
stream_started_at + server_configured_max_duration)`이고 STARTED audit과 durable
lease에 결속한다.
첫 byte와 매 chunk 직전 trusted server now가 strict하게 deadline 전이고 lifecycle이
`AVAILABLE`인지 검사한다. now가 같거나 늦거나 lifecycle/lease가 바뀌면 다음 byte
전에 중단하고 `ERROR` terminal/lease event를 남긴다.
정상 송신 뒤 `COMPLETED`, server/client 중단·abort에는 `ERROR`를 추가한다.
terminal event 저장 실패도 성공으로 가장하지 않고 운영 incident에 남긴다.

### 3.6 Android transport와 UI

- coordinator 입력은 operation별 closed typed command만 허용하고 임의 URL,
  method, path 또는 generic JSON map을 받지 않는다.
- JSON object, JSON array와 streaming image/ZIP client를 분리한다.
- 각 응답은 exact MIME, 길이 상한, redirect 금지, approved origin, digest header를
  검증한다.
- binary 요청은 `Accept-Encoding: identity`를 고정한다. export 응답의 audit ID,
  profile, selection SHA header는 인증된 target과 ZIP manifest의 같은 값에 모두
  일치해야 한다.
- binary plaintext는 bounded streaming buffer에서 on-the-fly hash/length 계산과
  Keystore envelope encryption만 수행하고 filesystem plaintext temp/cache를 만들지
  않는다. ciphertext만 private 임시파일에 쓰고 plaintext hash·length와 AEAD
  metadata 검증 뒤 atomic rename하며 timeout·취소·process death 뒤 startup
  cleanup에서 불완전 ciphertext를 제거한다.
- Android의 image/ZIP bytes는 외부 저장소나 평문 cache에 두지 않고
  app-private no-backup 영역에서 Keystore-backed envelope encryption으로 저장한다.
  파일 key는 session/device에 결속하고 logout, session/device revoke, 계정
  recovery, image 사용 종료, ZIP의 receipt 기록/명시 삭제 또는
  `min(session expiry, 생성 후 24시간)`에 삭제한다.
  시작 시 stale temp/만료 ciphertext를 정리하고 key 또는 metadata 불일치 시
  복호화·표시하지 않는다.
- 공용 storage, generic file chooser와 unrestricted share intent는 금지한다.
  승인된 수동 채널 adapter만 exact target package/component 또는 portal origin과
  signing-certificate SHA-256, audit ID/plaintext hash를 검증한 뒤 복호화 stream을
  직접 전달한다. 실제 recipient endpoint/account identity도 caller 문자열이 아니라
  server가 발급한 HIGH delivery grant의 registry-controlled identity와 byte-exact로
  대조하며 institution/channel/issuer namespace/adapter/recipient 중 하나라도
  불일치하면 반출을 fail-closed한다.
  grant는 actor/session/device, channel-registry entry/revision/epoch,
  authorization policy ID/revision/epoch와 authorized report-set digest, exact
  institution/channel/issuer namespace/adapter/recipient, audit ID/plaintext
  hash·length, artifact `generated_at/expires_at`, exact create·consume
  path/method/raw intent, idempotency key, stored `grant_expires_at`과 artifact
  lifecycle revision/deletion epoch를 결속한다. stored grant expiry는
  `min(grant_issued_at + 60초, operation_grant_expiry, artifact_expires_at,
  session_expiry)`다. manual `stream_deadline_at`은 exact
  `min(stored_grant_expires_at, operation_grant_expiry, artifact_expires_at,
  session_expiry, stream_started_at + server_configured_max_duration)`이며 attempt의
  STARTED event와 durable lease에 결속한다. 남은 시간이 없으면 발급·소비하지
  않는다. 같은 create idempotency key+같은 intent는 기존
  grant 또는 그 terminal consumed/expired/deleted 결과만 반환하고 expired/consumed
  grant를 되살리지 않는다. consume도 same-key/same-intent면 기존 attempt와 현재
  terminal/lease 상태만 반환하고 새 stream을 열지 않으며, create/consume 어느
  same-key/different-intent도 409다.
  online consume은 §3.5 순서로 registry/session→channel/authz→source/quote/
  lifecycle→audit/grant→attempt→lease를 잠가 `AVAILABLE`, deletion gate OPEN,
  stream deadline 전, same epoch/revision, unconsumed grant를
  다시 검증하고 한 번만 stream lease와 attempt로 전환한다. stream lease가 shared
  lock을 장기 보유하지 않고 각 chunk send에만 fair shared gate를 잡는다. 매 chunk
  전에 trusted now가 strict하게 stream deadline 전이고 lifecycle/gate/lease가
  유효한지 검사한다. timeout/expiry/network loss 또는 queued/DRAINING deletion이면
  다음 byte 전에 중단·`ERROR` 처리한다. deletion gate가 먼저면 새 grant/consume은
  zero-byte이고, current chunk가 먼저면 그 chunk 뒤 DRAINING barrier가 commit되어
  기존 lease는 terminal만 가능하다. 삭제 API 성공은 후속 `DELETION_PENDING`
  commit 뒤에만 응답하며 그 뒤에는 byte가 나가지 않는다.
  URI/stream 권한은
  one-shot·non-persistable이고 완료·취소·abort 즉시 회수한다. 잘못된 signer,
  component/origin 또는 소비된 grant의 재사용은 거부한다.
- image와 ZIP은 서로 다른 고정 byte 상한을 두고 caller 제공 filesystem path를
  받지 않는다. `Content-Disposition` filename은 표시용으로만 제한된 문자 집합으로
  정규화하며 저장 경로를 결정하지 않는다.
- binary는 `Content-Length`와 `X-WalkSafe-Content-SHA256`을 필수로 검증하고
  export download에서는 두 값이 export audit의 저장된 length/hash 및 인증된
  request target과도 byte-exact인지 대조한다.
  ZIP은 entry 수·각 entry와 누적 uncompressed byte 상한, exact allowlist,
  absolute/`..`/중복 path와 symlink 금지를 central directory에서 검사하고 단말에서
  임의 경로로 extract하지 않는다.
- image는 byte 상한 외에도 codec decode 전후 최대 width/height/pixel 수를 검사해
  decompression bomb를 거부한다.
- 공용 “고위험 작업 재인증” 버튼은 제거하고 각 실제 업무 동작이 exact 재인증을
  시작한다.
- `internalOperationalDebug`라는 명시적 내부 variant에서만 workflow를 열고,
  일반 debug와 release는 production
  서명·active registry·비공개 배포 승인이 결속될 때까지 hard-false로 유지한다.

### 3.7 FP-033/GAP-042 공유 경계

typed review, export와 receipt 기반은 FP-033/GAP-042도 재사용할 공유 backend
domain이다. 이 FP-008 leaf는 관리자 제품의 안전한 접근·검수·수동 전달 조립만
소유한다. 공유 기반을 구현해도 사용자 정정 피드백, 실제 기관 연동 또는
`GAP-042` 완료·상태 전이를 주장하지 않는다.

## 4. 예상 구현 slice

충돌을 줄이기 위해 다음 순서로 직렬화한다.

1. backend typed review domain/service와 단위시험
2. migration, provisioned-device registry와 session revocation
3. append-only review, deterministic export hash와 receipt 저장 경계
4. intent-bound reauthentication, exact route scope와 두 transaction audit
5. report/admin-security route와 OpenAPI
6. controller-owned Android authorized executor와 분리 transport
7. admin report coordinator/model의 순수 Java 시험
8. Activity의 최소 조립·렌더링과 variant별 flag 검증
9. 전체 targeted 회귀와 새 full implementation-start gate

핵심 예상 파일군:

- `backend/app/models.py`, `backend/app/schemas.py`
- `backend/app/api/reports.py`
- `backend/app/api/uploads.py`
- `backend/app/services/admin_security.py`
- 새 review service와 Alembic migration
- 새 device-registry provisioning contract와 migration
- `backend/app/openapi_contract.py`, `contracts/walksafe.openapi.json`
- `scripts/record_walksafe_agency_submission_20260711.py`의 v2 successor,
  `scripts/check_walksafe_release_evidence_20260711.py`
- `scripts/check_report_retention_dry_run.py`와 report-retention service/timer
  successor
- `apps/android/adminapp/.../security/AdminSecurityApi.java`
- `AdminSecurityHttpClient.java`, `AdminSecurityController.java`
- 새 admin report client/coordinator/model
- 새 app-private encrypted binary store
- 새 allowlisted manual-channel adapter registry와 one-shot stream grant
- `AdminBoundaryActivity.java`와 관련 단위·정적 검사

현재 위 backend 파일 다수와 `adminapp` 전체가 사용자 dirty/untracked 상태다. 구현
시작 전 exact path·SHA-256·Git status를 다시 고정하고, 현 Alembic head를 확인한 뒤
한 writer가 직렬로 수정해야 한다.

## 5. 수용 기준

- 미등록 기기, 사용자 앱 token, 잘못된 role/audience/device, 만료 session과
  재인증 누락은 모든 FP-008 admin route에서 업무 변경·응답 byte 전에 거부되고
  security denial audit에 남는다.
- self-generated UUID와 폐기된 key는 등록 증거가 아니며, 기기 폐기 시 기존
  session도 함께 무효화된다.
- PENDING key의 login/recovery와 REVOKED key의 모든 challenge는 거부된다.
- challenge/proof nonce의 만료·두 번째 사용과 다른 key/session/path/intent 서명은
  거부된다.
- challenge 발급 뒤 registry가 revoke되거나 revision/epoch가 바뀌면 proof 소비
  시점의 row-lock 재검증에서 거부되고 session·domain 변경이 없다.
- proof 소비 뒤 effect 전 revoke/revision 변경도 provisioning activation,
  login/recovery session과 manual-delivery grant·consume을 만들지 못하며 소비된
  nonce와 admission audit만 남는다.
- channel-registry 또는 actor/role/report-set authorization policy가 revoke되거나
  revision/epoch가 바뀌면 challenge, grant, effect와 read가 거부되고 outstanding
  lease/attempt는 다음 byte 전 `ERROR`다.
- 승인·반려·중복은 필수 사유와 actor/session/device/server time을 append-only로
  남긴다.
- typed review-decision revision/event는 UPDATE/DELETE할 수 없고 event 없는 decision,
  decision 없는 event나 깨진 revision/supersedes chain은 commit되지 않는다.
- nonce의 다른 report/path 재사용과 두 번째 사용은 거부된다.
- review-decision nonce를 status patch·export·delivery receipt에 사용하거나 반대로
  사용하는 cross-operation 재사용은 업무 변경 전에 거부된다.
- action/method/path/intent 중 하나라도 다르거나 decision, reason, duplicate target,
  expected revision, export filter/profile가 바뀌면 거부된다.
- Java가 확정한 raw request bytes와 Python이 parsing 전에 관찰한 bytes에서
  `walksafe-admin-intent-v1` hash가 일치하며 body/query 1 byte 변경은 거부된다.
- 거부 시 security audit은 추가되지만 report, decision, export audit과 delivery
  receipt는 변하지 않는다.
- stale `expected_updated_at`는 409이며 report, decision, export/receipt와 domain
  audit 변화가 없다. 별도 admission security audit은 보존된다.
- domain 변경과 domain audit은 한 transaction이며 domain audit 실패 시 함께
  rollback된다. admission nonce·security audit은 별도 transaction으로 보존된다.
- latest typed decision revision만 자격 판정에 사용하며 legacy
  `agency_review_verified`는 재검수 전 export 부적격이다.
- live `review_evidence_sha256`가 latest `APPROVED`에 결속된 digest와 다르면
  quote/export 모두 부적격이다.
- lifecycle `Report.status` 변경은 typed decision을 생성·변경하지 않고, typed
  decision도 status를 암묵적으로 변경하지 않는다.
- 승인·필수 체크를 마친 신고만 agency export에 포함된다.
- 반려·중복·fake·저정밀 위치·비대상 유형은 export에서 제외된다.
- quote selection은 완전 정렬된 ID·report revision·decision revision·data
  hash·source deletion epoch에 결속되고 drift 시 409다. 삭제가 먼저 commit되면
  관련 ACTIVE quote가 `CANCELLED_DELETION`이고 consume은 zero-artifact stable 410다.
  동일 idempotency retry는 같은 audit ID와 byte-exact ZIP을 보존기간 안에서만
  반환하며 만료·삭제 뒤에는 재생성 없이 안정적인 410이다.
- 모든 effect/terminal/reconciler/revoke/deletion/attestation은 §3.5 전역 lock
  order만 사용하고 nonlocking prelookup 뒤 전체 key set/state/epoch를 다시 검사한다.
  final commit trusted now가 `quote_expires_at`과 같거나 늦으면 audit/lifecycle은
  0이고 설치 ciphertext는 orphan GC만 가능하다. 별도 expiry transaction은
  `ACTIVE → EXPIRED`+event를 원자 commit하고 같은 key에 stable 410을 반환한다.
- quote consume/export audit commit 전·후 crash, DB rollback, orphan artifact,
  missing/corrupt committed artifact를 시험하고 부분 성공을 외부에 노출하지 않는다.
- export audit의 actor/session/device/count/rows digest와 CSV·manifest·ZIP hash가
  실제 응답 bytes와 일치한다.
- 완료 export POST replay는 원 actor/session/device/purpose와 fresh HIGH grant가
  모두 같을 때만 허용하고, 다른 session/device는 별도 HIGH download read audit을
  사용한다.
- read/status/export/decision/receipt audit에 purpose/reason과 correlation ID가
  있고 FP-008 route는 default-deny·legacy-token 거부다.
- 원본 image/export download는 HIGH operation grant 없이는 첫 byte도 반환하지
  않는다.
- JSON/image/ZIP/manual stream은 fresh registry/session row-lock 재검사와 shared
  read lease를 얻고 `AUTHORIZED/STARTED`를 commit하기 전 byte를 반환하지 않는다.
  shared-send-first는 현재 bounded JSON response 또는 binary 한 chunk까지만
  허용하고 revoke commit 뒤에는 zero-byte 또는 다음 chunk 전 `ERROR`다.
- durable ACTIVE protected lease/attempt가 남아 있으면 `DELETION_PENDING`과 삭제 API
  성공은 commit/응답되지 않지만 durable `DELETION_DRAINING` barrier는 먼저 commit된다.
  writer-preferred gate와 그 barrier가 신규
  quote/grant/read/replay/lease를 먼저 막고, 기존 lease terminal 또는 process-death/
  expiry reconciler의 `ERROR` event 뒤에만 `DELETION_PENDING`이 commit된다.
- 최초 export POST, 그 POST replay와 export GET은 각각 session/grant/artifact/server
  cap 중 가장 이른 deadline을 STARTED audit에 결속하고 첫 byte와 매 chunk에서
  trusted now를 재검사한다.
- JSON array·image·ZIP은 각 size/MIME/digest/redirect/atomic-file negative test를
  통과한다.
- Android image/ZIP 평문·external/backup 저장이 없고 logout/revoke/recovery/
  delete/session-expiry/24시간 만료와 startup cleanup 뒤 ciphertext/key가 남지 않는다.
- 실제 authoritative receipt 전에는 제출·수신 완료 상태가 생기지 않는다.
- 모든 manual-delivery consume은 exact target과 byte count를 결속한 하나의
  append-only attempt lifecycle을 남긴다. authoritative receipt stream은 같은
  `COMPLETED` attempt 하나와 immutable 1:1이고, failed-attempt와 outcome incident도
  원 terminal attempt를 참조한다.
- delivery receipt는 audit별 단일 revision chain과
  `RECEIVED → ACCEPTED`만 따른다. 첫 receipt 전 외부 거부는 failed-attempt,
  `RECEIVED | ACCEPTED` 뒤 거부·철회·검증 무효는 outcome incident이며 모두 새
  export에서 다시 시작한다.
  한 export audit에는 authoritative receipt stream과 failed-delivery terminal
  marker가 동시에 존재하지 않는다. outcome incident는 기존 `ACCEPTED` 사실과
  공존할 수 있지만 생성 즉시 terminal·release credit 0이다.
- `ACCEPTED`는 immutable raw external response와 issuer signature 또는 독립 verifier가
  exact external tuple, COMPLETED attempt와 submitted hash/length를 검증한 경우만
  허용한다. unverifiable/stale/backfilled/future/raw-swapped evidence는
  history-only·credit 0이다.
- v2 receipt와 signed current-stream attestation의 receipt/previous IDs, server
  time, freshness, artifact expiry와 raw hash/bytes가 일치해야 한다.
  `receipt_status=ACCEPTED`, failed terminal 부재와 outcome incident 부재를 서로
  다른 필드로 증명하고 server-now 재검사를 통과해야만 release credit 후보가 된다.
- manual-delivery grant는 server channel-registry의 exact institution/channel/
  namespace/adapter/recipient와 authorization policy/report-set digest의
  revision/epoch를 상속한다. create retry는 terminal
  grant를 되살리지 않는다. manual stream deadline은 stored grant, HIGH operation
  grant, artifact, session expiry와 started+server cap 중 가장 이른 시각이고 이를
  넘지 않는다.
- 관리자 workflow 장애는 사용자 보행 runtime 상태를 바꾸지 않는다.
- actual device, actual agency, formal test와 release credit은 모두 0으로 유지한다.
- export ZIP은 `docs/operations/data_retention_policy.md`의 export-file class로
  분류해 생성 후 최대 7일만 보존하고 전체 삭제요청이면 7일 안에 제거한다.
  자연 7일 만료도 file unlink만 하지 않는다. retention transaction은 위 공통
  §3.5 lock order와 fair deletion gate, ACTIVE-lease conflict, trusted now 재검사를
  거친다. ACTIVE lease가 있으면 `AVAILABLE → DELETION_DRAINING`과
  `ARTIFACT_DELETION_DRAINING(reason=RETENTION_EXPIRED)` event로 신규 접근을 먼저
  막고 terminal/reconciliation 뒤 `DELETION_PENDING`으로 전이한다. ACTIVE lease가
  없으면 `AVAILABLE → DELETION_PENDING` 및 append-only
  `ARTIFACT_DELETION_PENDING(reason=RETENTION_EXPIRED)` event를 직접 commit한다.
  그 뒤 no-follow verified ciphertext를 unlink하고 parent directory를 `fsync`한 뒤
  별도 transaction에서 `DELETION_PENDING → DELETED`와
  `ARTIFACT_DELETED` event/삭제 receipt를 commit한다. 각 crash 지점은 startup/worker
  reconciliation이 draining/pending event, ACTIVE lease, filesystem 존재 여부와 hash를
  대조해 idempotent하게 이어가며 `AVAILABLE`로 되돌리지 않는다.
  server 저장은 외부 secret에 결속된 application-envelope AEAD를 적용한다.
  audit에는 plaintext ZIP hash/length와 ciphertext hash/length, key version,
  nonce/tag를 서로 다른 필드로 저장하고 AAD는 audit ID와 plaintext hash를
  결속한다. install/EEXIST/GC는 ciphertext를, download는 AEAD tag 복호화 뒤
  plaintext hash/length를 검증한다. nonce reservation 충돌은 bounded
  regenerate/fail-closed이고 같은 quote retry는 최초 nonce를 보존한다. backend
  실패·crash 뒤에도 filesystem raw ZIP residue는 0이다. raw ZIP은
  운영 backup에서 제외하고, audit/receipt DB backup은 최대 35일 순환과
  restore-time tombstone 재적용을 따른다. 원본 없이 식별 hash·처리시각·결과만
  남기는 삭제/이관 receipt는 3년 기술 목표로 분리한다. 이 기간은 승인된 법적
  보존기간이 아니며 release 전 개인정보 검토가 필요하다.
- retention/download/replay/receipt/release 경로는 응답·domain commit 직전에
  artifact lifecycle의 logical deletion state를 row lock 또는 동일 snapshot에서 다시
  검사한다. byte stream은 chunk send 동안만 fair shared gate를, same-invocation
  attestation은 bounded consume/rollback까지 shared gate를 유지한다. deletion
  writer가 queued되면 새 shared admission을 막고 현재 holder 뒤
  `DELETION_DRAINING`을 commit한다. 그 barrier 전 ACTIVE lease는 다음 byte 없이
  `COMPLETED | ERROR` terminal만 허용되며, 마지막 terminal 뒤
  `DELETION_PENDING`을 commit한다. deletion gate/barrier가 먼저면 이후 동작은
  zero-byte/zero-domain-delta다. non-stream domain-row-lock이 먼저면 그 domain
  commit 뒤 deletion이 진행되고 deletion writer가 먼저 queued되면 새 domain
  admission을 차단한다. 어느 순서든 DRAINING 뒤 새 byte가 없고, 삭제 API는
  `DELETION_PENDING` commit 전 성공을 응답하지 않는다.
- 새 artifact root는 retention worker의 lock·reconciliation·삭제 receipt 범위에
  포함한다. 만료 전에는 audit/receipt 참조 파일을 GC하지 않고, DB 미참조 orphan만
  staging lease/maintenance lock 아래 grace와 directory-fd 기반
  no-follow/owner/mode/`st_nlink == 1`/ciphertext hash 및 전후 inode identity 검증
  뒤 제거한다. scheduler enable/start는 이 leaf에서 하지 않는다.
- DB trigger는 immutable `ReportReviewDecision/Event`, `ReportExportAudit`,
  receipt, incident, replacement-link, `ExternalReceiptEvidence`,
  `ManualDeliveryStreamAttempt/Event` row와 모든 lifecycle event의 UPDATE/DELETE를
  거부한다. quote, artifact, channel/authz policy와 protected-lease header만 허용된
  revision·상태 전이를 갱신할 수 있고, 대응 append-only event 없이는 commit하지
  못한다.

## 6. 검증 경로

materialization과 새 full start gate 뒤 다음을 실행한다.

- backend review/admin-security/report targeted pytest
- agency submission receipt와 release-evidence validator 회귀
- v1 legacy와 typed-decision v2 agency manifest/receipt schema dispatch 회귀
- FP-008 v2-only release dispatch, v1/unknown/mixed downgrade 거부와 ZIP
  만료 뒤 history-only·release-credit 0, RECEIVED/incident stream 거부와
  incident 없는 fresh ACCEPTED만 자격 후보인 회귀
- raw `submission_status=received|accepted`와 signed attestation
  `receipt_status=RECEIVED|ACCEPTED`의 exact mapping, casing/unknown/mismatch 거부 시험
- current-stream attestation signer/raw-byte/snapshot/freshness tamper와
  exact receipt raw SHA/bytes·stream/receipt/previous ID·server time·fresh-until·
  artifact generated/expires-at·issuer namespace·external raw response
  bytes/hash/length/media type·issuer signature/verifier identity·invocation nonce 재사용,
  future/UTC offset/정확한 7일 경계와 생성·소비 사이 ACCEPTED 대
  incident/deletion 경합 시험
- signed external observed/trusted capture time의 pre-completion/stale/future/
  backfill/tamper, raw response swap, 잘못된 issuer key/verifier config,
  attempt/submission hash·tuple 불일치와
  `UNVERIFIABLE` history-only·credit0 시험
- attestation deadline 초과, validator disconnect/process death의 transaction
  rollback·shared-lock 해제와 대기 중 deletion exclusive-lock 획득 시험
- Alembic 단일 head와 upgrade 검증. 자료를 삭제할 수 있는 downgrade는 폐기 가능한
  빈 시험 DB에서만 검증한다.
- OpenAPI 결정론적 생성 `--check`
- `:adminapp:testDebugUnitTest`
- `:adminapp:assembleDebug`
- `:adminapp:lintDebug`
- `:adminapp:testInternalOperationalDebugUnitTest`
- `:adminapp:assembleInternalOperationalDebug`
- 일반 debug/release generated `BuildConfig`의 workflow hard-false와 generic
  reauth UI 부재를 실제 variant 산출물 기반 정적 검사로 각각 확인
- intent body/query tamper, cross-operation nonce와 revoked-device negative tests
- 사용자 Android app token 및 user gateway audience/role로 backend의 모든 FP-008
  admin route를 호출해 401/403, zero-byte/zero-domain-delta를 확인하는
  user-app→gateway→backend 회귀
- quote selection revision/data/deletion-epoch drift·expiry·reuse, idempotent
  byte-exact ZIP 재응답과 deletion-first ACTIVE quote 취소·stable 410 시험
- quote route 미등록·다른 action/intent 사용, receipt 동일/상이 intent와 병렬
  revision 멱등성 시험
- external receipt issuer namespace 변경·recipient 변경을 이용한 ID 재사용과
  institution/channel 변경 우회, channel-registry revision/epoch 및
  adapter/recipient/namespace receipt binding tamper 시험
- manual stream attempt의 grant/consume/lease ID, target tuple, byte count와
  `AUTHORIZED/STARTED/COMPLETED/ERROR` 전이, receipt의 COMPLETED attempt 1:1
  uniqueness, terminal endpoint expected-revision/idempotency, backend timeout/revoke
  ERROR, cross-attempt/cross-audit 재사용 및 failed/incident lineage 시험
- RECEIVED와 ACCEPTED 각각 뒤 outcome incident의 HIGH operation/idempotency,
  RECEIVED에서 incident-first 대 ACCEPTED-first row-lock 순서, ACCEPTED 뒤 incident
  terminal·credit0과 새 export의 backward supersession 결속 시험
- replacement export의 absent/unknown/cross-actor/cross-report-set/already-linked
  incident와 optional incident ID raw-intent tamper 시험
- quote/export key namespace, quote same/different/expired intent, reserved audit ID/
  generated-at 결정성, quote+audit+initial AVAILABLE lifecycle/event 원자 commit과
  rollback, source deletion 대 quote/create/consume 양방향 commit-order와
  zero-artifact 시험
- §3.5의 registry/session→challenge/grant→channel/authz→source→quote→lifecycle→
  audit/grant→attempt→lease→receipt→evidence 전역 lock order와 multi-key 정렬,
  prelookup key-set drift 재시작 및 deadlock 없음 시험
- receipt insert 대 attestation, client terminal/timeout reconciler 대
  deletion/device·channel·policy revoke를 양방향 barrier로 반복해 역순 lock·late
  lock·부분 domain delta가 없음을 확인하는 시험
- final audit/lifecycle commit의 trusted now가 quote expiry 직전/equal/직후인 경계,
  equal/late rollback·zero-response와 installed ciphertext orphan reconciliation,
  별도 `ACTIVE→EXPIRED`+`QUOTE_EXPIRED` event 및 same-key stable 410 시험
- idle `ACTIVE` quote의 same-key retry·조회·consume 첫 관찰자가 모두 별도 expiry
  transaction/event와 stable 410으로 수렴하고 deletion terminal이 먼저면 그 결과를
  덮어쓰지 않는 양방향 경합 시험
- `(key_version, nonce)` 강제 충돌, 병렬 quote reservation, bounded retry 소진
  fail-closed와 same-quote retry의 최초 nonce 보존 시험
- install→DB commit crash의 exact EEXIST 재사용, hardlink/inode-swap 거부와
  GC lock/lease 경쟁 시험
- 승인 뒤 report/image/check evidence drift, read-audit commit 실패 시 503·zero-byte
  응답, export ZIP 7일·삭제요청 7일·backup35일·receipt3년·참조/orphan GC 경계 시험
- 자연 7일 만료의 ACTIVE-lease 유무별
  AVAILABLE→DELETION_DRAINING→DELETION_PENDING 또는 direct pending event→
  unlink/fsync→DELETED event/receipt, 각 DB/filesystem crash 지점과 startup/worker
  idempotent reconciliation 시험
- deletion writer queued 시 신규 quote/grant/read/replay/receipt/incident/
  attestation/release/lease 폭주에도 기아 없이 `DELETION_DRAINING`이 먼저 commit되고
  기존 lease terminal만 허용되는 fair-gate 시험
- receipt ingress·outcome incident 대 deletion-first/DRAINING 양방향 경합에서
  receipt/evidence/incident zero-domain-delta와 stable deletion 응답을 확인하는 시험
- DRAINING 중 holder process death/timeout의 reconciler `ERROR`, 마지막 lease 뒤
  `DELETION_PENDING`, commit 전 성공응답 금지와 이후 zero-byte/zero-domain-delta,
  pending credit0 및 7일 내 물리 삭제 시험
- device challenge/PoP TTL·single-use·wrong-key/session/path/intent 시험
- PENDING login/recovery, REVOKED challenge, HIGH image/export download grant 누락,
  challenge 발급 후 및 proof consume 뒤 session/activation insert 전
  revoke/revision 변경 TOCTOU와 session 0 시험
- admission 성공 뒤 review/export/receipt/manual-grant create·consume commit 전
  revoke/session/channel-registry/authz-policy 변경 경합과 domain rollback,
  nonce/security-audit 보존 시험
- channel-registry ACTIVE→REVOKED와 actor/role/report-set policy epoch 변경의
  outstanding challenge/grant/lease/attempt invalidation, cross-institution 및
  unentitled report-set read/write, admission/effect/read 양방향 race 시험
- JSON read의 shared-send-first/revoke-exclusive-first, read audit/첫 byte 사이
  revoke와
  image/ZIP/manual binary stream 중 device/session lease 폐기 시 zero-byte 또는
  다음 chunk 전 abort·ERROR 시험
- public login/recovery body·path tamper, read query tamper, reauth proof nonce의
  business 요청 재사용, credential-bearing intent/signature의 audit·로그 잔존 시험
- HIGH image GET, 최초/멱등 export POST, export GET download와 manual adapter
  stream의 binary audit
  `AUTHORIZED/STARTED/COMPLETED/ERROR`, client abort와 terminal audit 실패 시험
- 최초 export POST/replay/GET 각각의
  `min(session,grant,artifact,server-cap)` deadline, first-byte/매-chunk trusted-now
  equal·late abort와 `ERROR` 시험
- durable ACTIVE lease/attempt 대 deletion/revoke 양방향 경합, process death·expiry
  reconciler의 전역-order idempotent `ERROR`/권한 회수 후 draining 진행 시험
- backend·v1/v2 CLI·release validator의 `api` channel 거부와 failed-attempt/
  outcome-incident exact HIGH operation/idempotency 시험
- ZIP entry-count·uncompressed-size·path traversal·symlink·duplicate-path 시험
- Android encrypted binary store의 no-backup, key mismatch, logout/revoke/
  recovery/TTL/startup cleanup, timeout/process-death 뒤 plaintext 파일 0과
  불완전 ciphertext 정리 시험
- backend ZIP oversize, encrypt 전후 crash, ciphertext fsync/install 실패 뒤
  filesystem plaintext residue 0과 ciphertext-only temp/root scan 시험
- application-envelope plaintext/ciphertext hash·length, key version,
  nonce/tag/AAD tamper와 승인 adapter component/origin/certificate, one-shot
  non-persistable grant의 완료·취소·abort 정리와 재사용 시험
- adapter target과 server-issued institution/channel/issuer namespace/recipient
  endpoint/account identity의 불일치·caller 위조·cross-institution/channel grant
  거부 시험
- manual-delivery grant/consume/client-terminal exact operation/path/intent/
  idempotency, registry/lifecycle epoch, consumed/expired/deleted terminal 재시도와
  resurrection 거부, stored grant/operation grant/artifact/session/started+cap 각각이
  minimum인 exact stream deadline, 매 chunk equal/late abort와 one-shot consume 시험
- immutable review-decision/event, export audit/receipt/incident/replacement-link/
  external-evidence/manual-attempt/event UPDATE·DELETE trigger와 quote/artifact/
  channel/authz/lease lifecycle header/event의 허용 전이·event 누락 rollback 시험
- test-layer registry validate
- v2.4 또는 승인된 successor의 continuation/Goal graph 검사

제품 구현을 실제 시작하거나 세션을 재개할 때마다 19-check full gate와 Android
heavy semaphore/lease를 새로 통과해야 한다.

## 7. 시작 전 필수 선행

- 독립검수 findings 0인 r022 R002 후보 확정
- bulk r022를 수용할 non-effective successor control candidate 검토
- r022 정본 적용과 checkpoint/event 전이를 묶은 별도 사용자 승인
- 승인된 control package에서 FP-008 leaf materialize 및 READY 전이
- fresh full implementation-start gate PASS

현재 다음 행동은
`WAIT_FOR_V2_5_CANDIDATE_FINDINGS_ZERO_AND_SEPARATE_ACTIVATION_APPROVAL`이다.
