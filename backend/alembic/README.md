# Backend Migrations

PostgreSQL/PostGIS schema 변경을 관리한다.

- `env.py`는 `backend.app.models.Base` metadata와 migration 전용 DB 설정을 Alembic에 연결한다. development/test는 `DATABASE_URL` fallback을 허용하지만 field/staging/production은 repository `backend/.env`를 읽지 않고 `WALKSAFE_MIGRATION_DATABASE_URL`과 별도 `WALKSAFE_RUNTIME_DATABASE_ROLE`을 요구한다.
- `versions/`는 순서가 보존되어야 하는 migration revision을 둔다. 현행 단일 head는 `202609010002`이다. `202608290003`은 기본 비활성 raw collection 저장·불변 receipt·180일 retention class를, `202608290004`~`202608290006`은 관리자 감사·신고 전송·제출본 계약을, `202608290007`은 수동 raw retention 최소권한 역할을, `202608290008`은 사용자 신고별 정정·삭제 요청과 append-only 상태 사건을, `202608290009`는 일반 테스트용 암호화 이메일 계정·OTP·가입 동의 receipt를, `202608290010`은 삭제 수락 시 credential fence·최소권한 물리 삭제·이메일별 단일 live enrollment·세 키 fingerprint singleton·bounded delivery lease·수동 enrollment purge 역할을, `202608290011`은 전역·이메일 lookup-HMAC별 인증 rate group을 적용한다. `202608290012`~`202608290016`은 raw v2 lifecycle, 신고 정정·삭제, 이메일 계정 삭제 결속과 통합 동의 v1.1을 추가하며, `202608300001`은 신고 물리삭제 worker가 일반 UPDATE 권한 없이 정확한 승인 후보만 잠그는 제한 함수를, `202608300002`는 원본 증거 접근 성공과 정확 위치 공개를 단일 승인에 결속하는 v2 계약을, `202608300003`은 격리된 신고 복구 재적용 receipt와 worker 역할을, `202608300004`는 원본 증거 grant·감사·검토 결정 mutation을 capability-bound 최소권한 함수 뒤로 격리하고, `202608300005`는 신고 ingest consent/tombstone과 관리자 proof-bound review/package/delivery mutation을 DB 경계에서 재검증하며, `202608300006`은 외부 기관 보관본 삭제 요청·회신 사실을 append-only 사건으로, `202609010001`은 사용자 요청 discovery 순번을 삭제 tombstone까지 보존해 재설치 복원을, `202609010002`는 raw object digest 불일치를 비공개 일방향 거절 표식으로 고정한다. 삭제 worker는 결속된 계정과 같은 lookup HMAC의 enrollment를 삭제하지만 가입 동의 receipt와 계정 삭제 장부는 최소 감사 증적으로 유지한다.
- 워커 로그인은 `walksafe_account_deletion_worker`의 직접 구성원이어야 하며 membership은 정확히 `WITH ADMIN FALSE, INHERIT TRUE, SET FALSE`로 부여한다. 기본 `SET TRUE`, 역할 재위임, runtime·receipt-purger 겸임, 추가 ACL은 시작 검사에서 거부한다.
- 현재 초기 revision은 PostGIS extension, `reports` 테이블, 일반 인덱스와 GiST 위치 인덱스를 만든다.

```bash
python -m alembic -c backend/alembic.ini upgrade head
python -m alembic -c backend/alembic.ini current
```

운영 DB에서 downgrade나 reset을 실행하지 않는다. 로컬 초기화 절차는 `docs/backend/backend_db_reset.md`를 따른다.
