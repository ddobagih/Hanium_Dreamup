# WalkSafe 실운영 전 필수 데이터베이스 보강 백로그 — 2026-08-30

> 상태: **실운영 전 필수 / 데모 비차단 / 구현 일시 중단**  
> 중단 시각: 2026-08-30 18:11 KST  
> 범위: 관리자 신고 검토, 원본 증거, 동의·삭제 결속과 관련된 PostgreSQL 권한·무결성 경계  
> 원칙: 이 문서의 항목을 완료하기 전에는 현재 결과를 운영 출시 승인으로 해석하지 않는다.

## 1. 현재 판정

- 정상적인 로컬 데모 경로에는 알려진 기능 차단 문제가 없다.
- 마지막 검증 체크포인트의 Backend 전체 회귀는 `1440 passed, 2 skipped`였다. 두 skip은 별도 PostgreSQL cluster가 필요한 복원 시험이며 이전 별도 실행에서 통과했다.
- 아래 항목은 일반 사용자가 정상 화면을 조작할 때 바로 발생하는 문제가 아니다.
- 실제 운영에서 DB 역할이 잘못 결합되거나, 권한·트리거·제약조건이 변조 또는 오설정될 때 신고 내용·동의·삭제 기록의 신뢰성이 훼손될 수 있는 문제다.
- 따라서 데모는 진행할 수 있지만, 아래 `P0`와 `P1`을 닫기 전 운영 배포는 금지한다.

## 2. 중단 시 작업트리 상태

마지막으로 검증된 범위:

- 관리자 무결성 head `202608300005`의 v3 fail-closed 경계
- 역할 속성과 상위 역할 상속 금지
- runtime direct/SET/transitive `MEMBER` 신고 INSERT 검사
- 관리자 mutation claim의 PK·UNIQUE·CHECK·FK
- 현재 결속된 17개 함수의 definition digest와 실행 권한
- 표적 단위/readiness `56 passed`
- fresh PostgreSQL 핵심 통합시험 `1 passed`

중단 직전에 추가돼 당시에는 미검증이었으나, 중단 후 최소 안전 검증을 마친 부분:

- `backend/app/services/admin_report_integrity.py`
  - `admin_security_controls`, `walksafe_recovery_custody_capabilities`, `admin_security_sessions`를 owner·ACL 검사 범위에 추가
  - `admin_security_controls`, `admin_security_sessions`의 runtime `SELECT` 계약 추가
- `backend/tests/test_admin_report_integrity_postgres_integration.py`
  - 제3자에게 `admin_security_controls UPDATE`를 부여한 권한 drift 회귀 추가

위 두 파일은 중단 후 `py_compile`, 표적 단위/readiness `56 passed`, fresh PostgreSQL 통합시험 `3 passed`를 확인했다. 실행 중인 휴대폰 데모 DB도 새 코드로 읽기 전용 검사해 관리자 boundary `true`를 확인했다. 따라서 현재 작업트리는 문법·표적 경로나 데모 DB 경계가 깨진 상태는 아니다. 다만 P0-1~P0-6과 P1 전체가 끝난 것은 아니므로 실운영 보강 완료로 취급하지 않는다.

## 3. P0 — 운영 전 반드시 구현할 항목

### P0-1. 보호 역할 graph 분리

위험:

- 하나의 login 또는 bridge role이 Backend runtime과 삭제 worker 양쪽의 member가 되면 정상 ACL 행을 유지한 채 신고 삭제 권한을 함께 상속할 수 있다.
- 보호 역할 graph의 `ADMIN OPTION`이 열리면 하위 역할이 권한을 재부여할 수 있다.

완료 기준:

- `walksafe_backend_runtime`과 4개 worker descendant 집합을 서로 겹치지 않게 검사한다.
  - `walksafe_account_deletion_worker`
  - `walksafe_report_deletion_worker`
  - `walksafe_report_restore_worker`
  - `walksafe_training_lifecycle_worker`
- 모든 보호 graph edge는 `admin_option=false`여야 한다.
- 실제 Backend login은 runtime에만 정확한 direct edge 한 개를 가진다.
  - `admin=false`, `inherit=true`, `set=false`
- Backend login은 evidence owner와 worker의 member가 아니어야 한다.
- 보호 역할이 다른 상위 역할을 상속하지 않는 기존 계약을 유지한다.
- direct·transitive·bridge·공용 login·`ADMIN OPTION` 변조 회귀시험을 추가한다.

### P0-2. 관리자 session-lock 신뢰 테이블 고정

대상:

- `public.admin_security_controls`
- `public.walksafe_recovery_custody_capabilities`
- `public.admin_security_sessions`

완료 기준:

- 세 테이블의 owner가 migration owner와 정확히 일치해야 한다.
- 정상 ACL은 다음으로 고정한다.
  - controls/sessions: runtime `SELECT`만 허용
  - capability: relation owner 이외 직접 ACL 없음
- 세 테이블에 PUBLIC·제3자·grant option·column ACL을 허용하지 않는다.
- runtime과 제3자의 `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, `TRIGGER`, `REFERENCES`를 금지한다.
- forged control·capability·session DML drift에서 readiness와 mutation이 fail-closed인지 실제 PostgreSQL로 확인한다.

### P0-3. `reports` 수정 권한을 필요한 열로 축소

위험:

- runtime의 table-level `UPDATE`로 개인정보 주체 결속, 삭제 세대, 위치, 원본 경로 등을 INSERT 이후 교체할 수 있다.

완료 기준:

- runtime의 table-level `UPDATE`를 제거한다.
- 실제 write path에 필요한 다음 DB 열만 column-level `UPDATE`로 허용한다.
  - `status`
  - `status_version`
  - `updated_at`
  - `metadata` — ORM 속성명은 `payload`
  - `content_revision`
- `privacy_subject_hmac`, `account_generation`, 위치, 이미지 경로와 그 밖의 열은 runtime에서 수정할 수 없어야 한다.
- migration `202608300005` downgrade는 predecessor의 table-level `UPDATE` 계약을 정확히 복원한다.
- 허용 5개 열의 정상 경로와 금지 열 변경 거부를 실제 PostgreSQL로 확인한다.

### P0-4. 보호 트리거 graph와 함수 결속

완료 기준:

- 보호 테이블의 non-internal trigger 집합이 fresh head manifest와 정확히 일치해야 한다.
- 추가 trigger는 0개여야 하며, 모든 trigger는 enabled, 무조건 실행, 인자 0개여야 한다.
- 일반·constraint·deferrable·initially deferred 속성과 trigger 함수가 manifest와 정확히 일치해야 한다.
- 최소 manifest에는 다음 보호 장치를 포함한다.
  - `reports`: runtime ingest, content projection revision, deletion effect guard
  - device proof challenges·reconfirmations: consume-once guard
  - consent·tombstones·export audits: validation/append-only guard
  - content revisions: append-only와 projection guard
  - original access audits·review decisions·delivery packages·delivery events: append-only guard
  - admin security controls: custody audit와 initial recovery marker/audit
  - admin security sessions: transition-only guard
  - image objects·original grants·mutation claims·custody capability: user trigger 없음
- 위 trigger 함수도 definition digest, owner, security mode, search path, ACL과 함수 속성을 결속한다.
- trigger disable·교체·추가 BEFORE trigger·함수 body 변경 회귀시험을 추가한다.

### P0-5. RLS·policy·rewrite rule drift 차단

완료 기준:

- 모든 보호 relation은 ordinary permanent table이어야 한다.
  - `relkind='r'`
  - `relpersistence='p'`
- `relrowsecurity=false`, `relforcerowsecurity=false`여야 한다.
- PostgreSQL policy는 0개여야 한다.
- 사용자 정의 rewrite rule은 0개여야 한다.
- tombstone을 숨기는 RLS와 INSERT/audit을 바꾸는 rule을 주입한 회귀에서 fail-closed인지 확인한다.

### P0-6. 보호 제약조건·고유 인덱스 manifest 결속

위험:

- 예를 들어 `uq_report_content_revisions_report_revision`이 제거되면 동일 revision에 서로 다른 hash를 넣을 수 있고, 이후 패키지 생성이 임의 row를 선택할 수 있다.

완료 기준:

- fresh head에서 보호 relation의 다음 항목을 결정론적으로 추출해 manifest로 고정한다.
  - PK
  - UNIQUE
  - FK와 update/delete action
  - CHECK와 validated 상태
  - unique index의 열 순서·predicate·expression·valid/ready/live 상태
- 누락·추가·이름 변경·열 순서 변경·비활성화·미검증 제약을 모두 거부한다.
- 최소한 content revision 중복과 subject/report 결속 우회 회귀를 실제 PostgreSQL로 확인한다.

## 4. P1 — migration 자체 fail-closed

위험:

- runtime readiness는 이상 상태를 발견하지만 migration `202608300005` 자체가 기존 제3자 ACL·role·RLS·rule drift를 모두 거부하지는 않는다.

완료 기준:

- migration preflight 또는 final assertion에서 위 P0 manifest를 동일하게 검사한다.
- 기존 오염을 자동 정리할지 거부할지 항목별로 명시한다.
- 거부 대상이면 SQLSTATE `42501`로 migration 전체가 rollback되어야 한다.
- 잘못된 owner·ACL·role graph·RLS·rule 상태에서 head stamp가 남지 않는지 확인한다.

## 5. 재개 순서와 검증

1. 중단 시 두 파일의 최소 안전 검증 결과 재확인  
   → 현재 증거: `py_compile`, 표적 단위/readiness `56 passed`, fresh PostgreSQL 통합시험 `3 passed`
2. P0-1~P0-6 구현  
   → 검증: 각 drift 주입 시 boundary `false`, mutation 거부, 정상 경로 유지
3. P1 migration assertion 구현  
   → 검증: drift가 있는 predecessor DB에서 upgrade rollback 및 head 미변경
4. 관리자 표적 PostgreSQL 통합시험  
   → 검증: downgrade→re-upgrade, ACL·역할·trigger·constraint manifest 전체 통과
5. Backend 전체 회귀  
   → 검증: fresh DB에서 전체 pytest 통과
6. 서로 다른 cluster 복원시험  
   → 검증: tombstone 재적용 2개 시나리오 통과
7. 실행 중 Backend 재시작과 스모크 시험  
   → 검증: DB·계정·관리자 경계 ready, Gateway·Voice 연결 유지
8. 문서·OpenAPI·비밀정보 검사 후 커밋·푸시  
   → 검증: 원격 `current` commit 확인

## 6. 이 백로그와 별개로 남는 외부 운영 입력

- 실제 SMTP 업체·발신 주소
- 실제 TMAP key와 운영 provider
- 오프라인 한국어 TTS, TalkBack, 실외 보행·지속 발열 시험
- 사업자·수탁자·보유기간·책임자·공개 URL과 개인정보 문안 승인
- 관리자 custody 승인 후 실제 관리자 업무 시험
- 사람이 기관에 제출하고 실제 접수 확인
- 운영 HTTPS origin, Android release signing과 배포 채널

기관 자동 제출이나 제어 자동화는 이 백로그의 범위가 아니다.
