# WalkSafe 신고 데이터 보존 정책

기준일: 2026-07-11
범위: `/reports`, `/reports/v2`, 업로드 이미지, field telemetry, export 산출물의 dry-run·guarded 운영 기준

## 원칙

- report image, 정확 위치, 신고 시각과 optional client `reporter_user_id` 같은 기본 report 정보는 서버에 저장한다. Web은 `reporter_user_id`를 보내지 않고, 별도 `ingested_by_actor_id`는 gateway session actor로 기록하되 외부 IdP identity로 해석하지 않는다.
- 얼굴/차량번호 모자이크는 하지 않는다.
- 실제 report row와 업로드 이미지는 6개월(180일)이 지나면 삭제 대상으로 삼는 기술적 timer 목표로 둔다. 이 수치는 승인된 법적 보유기간이나 최종 사용자 고지가 아니다.
- report retention은 기본 dry-run과, 명시적 확인·actor ID·DB row lock·image quarantine·audit manifest를 요구하는 guarded `--apply`를 제공한다. 일일 scheduler 템플릿은 제공하지만 저장소 변경만으로 enable/start되지는 않으며, 실제 운영 apply manifest도 아직 없다.
- Fake/Demo 데이터는 성능·field evidence·운영 export 기본 필터에서 제외한다.
- 공공기관 직접 제출 기능은 두지 않는다. 운영자 대시보드와 CSV/JSON/GeoJSON export만 제공한다.
- 원거리 field telemetry는 versioned 동의 뒤 allowlist metadata JSONL만 약 1 Hz로 저장한다. endpoint가 raw/base64 media를 거부하고 server rate limit·수신 시각·GPS 소수 5자리 최소화를 적용하며, UI에서 중지·현재 session 삭제가 가능하다.
- production 수동 test snapshot은 field session, 별도 production opt-in, 화면 동의 header가 모두 있을 때만 저장한다. named actor 소유권을 기록하고 동의 철회 시 해당 actor의 저장본을 삭제하며, 독립 일일 job으로 7일 상한을 강제한다.
- 손상 점자블록 자동 신고 증빙 이미지는 test snapshot이 아니라 report image이며 180일 report 보존 정책을 따른다.

## 보존 기간 기준

| 데이터 | 기술적 자동삭제 목표 | 비고 |
|---|---:|---|
| 실제 신고 row | 180일 | `source=android`/server 실사용 후보 전체. 상태와 무관하게 6개월 후 삭제 |
| 업로드 이미지 | 180일 | report row와 같이 삭제 |
| Fake/Demo 신고 | 최대 30일 | `source=fake`, `fake_source`, `data_origin=demo`. `performance_excluded`만으로는 이 bucket에 넣지 않음 |
| debug/test capture | 최대 7일 권장 | 개발자 전용. 운영 기본 surface 금지 |
| export 파일 | 7일 | 로컬 산출물 기준, 재생성 가능해야 함 |

## 자동 삭제 구현 기준

1. 삭제 대상은 report row와 연결된 upload image를 함께 묶는다.
2. 삭제 전 dry-run summary를 생성한다.
3. 운영 DB에서는 backup/rollback 가능 상태를 확인한다.
4. 삭제 실행자는 audit actor로 남긴다.
5. 삭제 후 row count, file count, 실패 목록을 기록한다.
6. destructive delete는 기본 off이며 report는 확인 문구와 audit manifest, telemetry는 `--apply`와 receipt 없이는 실행하지 않는다.

구현 경로:

- Report: `scripts/check_report_retention_dry_run.py`. `--apply`는 live DB·upload root·확인 문구·actor·manifest를 모두 요구하고 실패 전 DB rollback과 quarantine file 복원을 시도한다.
- Report scheduler: `scripts/run_walksafe_report_retention_20260717.sh`는 DB URL을 process argv에 넣지 않고 기존 guarded `--apply`를 그대로 호출한다. `deploy/systemd/walksafe-report-retention.service`와 `.timer`는 backend 서비스 사용자로 매일 실행하는 미활성 템플릿이다.
- Field telemetry/test capture: `scripts/manage_field_telemetry_retention_20260711.py`. server 날짜 폴더를 대상으로 기본 dry-run하고 `--apply --receipt`로 7일 삭제 결과를 기록한다. `scripts/run_walksafe_log_retention_20260711.sh`는 두 scope를 잠금 아래 매일 실행하고 원자적으로 receipt를 교체한다. `deploy/systemd/walksafe-log-retention.*`은 설치 전 운영 경로·사용자·쓰기 경로를 검토해야 하는 배포 템플릿이며 저장소 작업만으로 enable된 것은 아니다.
- Release gate: `scripts/check_walksafe_release_evidence_20260711.py`는 telemetry 7일 applied receipt, report 30/180일 completed apply manifest, OpenPGP backup·restore drill을 요구한다.

도구와 gate가 존재한다는 사실은 운영 DB에서 삭제·backup·복구를 실제 수행했다는 뜻이 아니다.

## 실행 evidence

- 명령 예:
  - `python scripts/check_report_retention_dry_run.py --input-json fixture.json --as-of 2026-05-26T00:00:00Z`
  - `python scripts/check_report_retention_dry_run.py --output-md docs/execution/YYYY-MM-DD_report_retention_dry_run.md`
- 출력에는 candidate id, age_days, reason, would_delete=false를 포함한다.
- 직접 운영 apply는 `--database-url`, `--upload-dir`, `--manifest-json`, `--actor-id`, `--apply --confirm DELETE-EXPIRED-REPORTS`를 모두 요구하며 `--output-md`를 허용하지 않는다. scheduler는 DB URL만 `DATABASE_URL` 환경으로 전달한다. 비밀값이 포함될 수 있는 실제 DB URL은 문서·로그에 적지 않는다.
- scheduler는 backend 전체 env를 상속하지 않고 `/etc/walksafe/report-retention.env`만 읽는다. 이 전용 파일의 production PostgreSQL URL은 `postgresql+psycopg`, `sslmode=verify-full`, `gssencmode=disable`을 사용하고 연결/statement timeout을 제한한다. DB URL은 환경으로만 전달하며 wrapper와 journal은 child 출력을 공개하지 않고 실행별 manifest만 0600으로 발행한다.
- release PASS에는 `status=completed`, `destructive_action=true`, actor/as-of/candidate digest가 있는 최신 report manifest와 실제 7일 telemetry applied receipt가 필요하다.

## Report scheduler 배포 전 확인

1. `deploy/config/walksafe-report-retention.env.example`을 운영 전용 env 파일로 복사하고 DB·경로·fingerprint의 모든 placeholder를 교체한다. `GNUPGHOME`은 `walksafe-backend` 소유 0700으로 만들고 두 signer의 검증용 public key를 넣는다. 이 unit에는 backend env를 추가로 연결하지 않는다.
2. backup manifest와 restore receipt 경로는 같은 최신 signed backup/restore pair를 정확히 가리켜야 한다. restore target의 DB와 upload root는 각각 source와 달라야 한다. 새 pair를 만들 때 두 경로를 함께 갱신하며, 누락·stale·서명 불일치이면 timer 실행은 삭제 전에 실패한다.
3. manifest 디렉터리는 upload root·backup·restore receipt·maintenance lock과 분리된 `walksafe-backend` 소유 0700 canonical 디렉터리여야 하며, 실행별 manifest leaf는 새 경로여야 한다. unit의 `TimeoutStartSec=1h`, `TimeoutStopSec=130s`, `LimitCORE=0`을 유지하고, 템플릿과 env를 설치한 뒤 `systemd-analyze verify`와 통제된 1회 실행으로 `status=completed`, `destructive_action=true`, mode 0600 manifest를 확인한다.
4. 위 확인과 운영 승인 뒤에만 timer enable을 별도 변경으로 수행한다. 이 저장소 작업은 unit을 설치·enable·start하지 않는다.

실행 중단 시 비어 있지 않은 `.report-retention-*.json`은 0600으로 보존되고 child 출력은 별도 log로 남기지 않는다. 이 파일을 임의 삭제하거나 release PASS evidence로 쓰지 말고, `status`와 DB commit 여부를 확인해 report row/upload를 대조한 뒤 backup 복구 또는 cleanup 재실행 여부를 결정한다.

DB 서버가 commit을 완료했지만 client 응답 전에 연결이 끊기는 network-level ambiguous commit은 자동 판정할 수 없다. 이 경우 pending/failed manifest와 서명된 backup을 기준으로 DB row와 upload를 대조해 수동 reconciliation하며, commit 성공 응답 전에는 원본 이미지를 삭제하지 않는다.

## W9 데이터 class·location 통제표

아래 기간은 현재 구현과 운영 초안이 사용하는 **기술적 보존·삭제 목표**다. 승인된 법적 보유기간, 보존 예외와 최종 한국어 동의·철회·삭제 고지는 모두 `NOT_APPROVED`이며 외부 개인정보·법률 검토가 필요하다. 사용자의 `RAW_SOURCE_COLLECTION`, `AUTOMATIC_REPORTING`, `MOBILE_NETWORK_TRANSFER`, `TRAINING_REUSE` 선택은 서로 독립하고, 한 선택이나 Android 권한이 다른 선택을 대신하지 않는다.

| 데이터 class | location | 기술적 목표 | 삭제 trigger·처리 | owner | legal basis | review due |
|---|---|---|---|---|---|---|
| 수신확인된 휴대전화 사본 | Android app private storage | 수신 확인 뒤 최대 24시간 | receipt 확인 또는 삭제요청 뒤 제거 | `ANDROID_DATA_OWNER_ROLE` | `NOT_APPROVED` | `BEFORE_NAMED_RELEASE_CANDIDATE` |
| 미전송 활동원본 | Android app encrypted queue | 최대 30일 | 만료·철회·삭제요청·용량보호 시 전송 차단 뒤 제거 | `ANDROID_DATA_OWNER_ROLE` | `NOT_APPROVED` | `BEFORE_NAMED_RELEASE_CANDIDATE` |
| 서버 수신·검역 원본 | backend quarantine storage | 최대 14일 | 검역 종료·거부·만료·삭제요청에 따라 격리 삭제 | `BACKEND_DATA_OWNER_ROLE` | `NOT_APPROVED` | `BEFORE_NAMED_RELEASE_CANDIDATE` |
| 신고 row·업로드 이미지 | backend DB and report object storage | 180일 | guarded retention apply가 row와 image를 한 묶음으로 처리 | `BACKEND_DATA_OWNER_ROLE` | `NOT_APPROVED` | `BEFORE_NAMED_RELEASE_CANDIDATE` |
| field telemetry·production test capture | controlled telemetry storage | 최대 7일 | actor별 철회·만료 또는 검증된 apply receipt에 따라 제거 | `FIELD_DATA_OWNER_ROLE` | `NOT_APPROVED` | `BEFORE_NAMED_RELEASE_CANDIDATE` |
| 승인 학습자료·label·고정 검증자료 | controlled ML storage | 승인 뒤 최대 3년 기술 목표 | `TRAINING_REUSE` 철회·만료·삭제요청 시 학습대상 제외와 저장본 삭제를 분리 기록 | `ML_DATA_OWNER_ROLE` | `NOT_APPROVED` | `BEFORE_NAMED_RELEASE_CANDIDATE` |
| 운영 backup | encrypted backup storage | 35일 순환 기술 목표 | active-store 삭제와 별개로 backup 만료일·tombstone을 기록하고 순환 만료 시 제거 | `BACKUP_OPERATOR_ROLE` | `NOT_APPROVED` | `BEFORE_NAMED_RELEASE_CANDIDATE` |
| 삭제·이관 검증 receipt | controlled audit storage | 3년 기술 목표 | receipt 자체의 만료·보존 승인에 따라 별도 처분 | `PRIVACY_OWNER_ROLE` | `NOT_APPROVED` | `BEFORE_NAMED_RELEASE_CANDIDATE` |

`owner`는 책임 역할이며 실제 운영자·법률 승인자의 신원을 꾸며 쓰지 않는다. 목표 기간을 변경하거나 법적 기간으로 전환하려면 근거 ID, 검토자, 승인 시각과 새 정책 version이 필요하다.

## 이관 형식·암호화 계약

| 단계 | 허용 형식·최소 metadata | 암호화·credential 경계 | 현재 상태 |
|---|---|---|---|
| Android → backend | versioned JSON metadata와 허용된 binary payload, content hash, consent version, request ID | 전송구간 TLS 필수, queue와 server 저장 암호화 필수, key 값은 외부 secret store에만 보관 | 운영 이관·전송 검증 `NOT_RUN` |
| backend → 통제 저장소 | manifest에 선언된 object와 최소 index, SHA-256, byte length | storage-side encryption과 최소권한 역할 필요, credential 원문 기록 금지 | 실제 이관 `NOT_RUN / EXTERNAL` |
| 통제 저장소 → 새 운영 주체 | 승인된 export schema, object manifest, hash·count reconciliation | 수신자 공개키 또는 승인된 암호화 채널 필요 | 수신자·계약·실행 `NOT_RUN / EXTERNAL` |

보행 중 일반 활동원본은 서버로 전송하지 않는다. 정지 판정 뒤 `MOBILE_NETWORK_TRANSFER`를 선택한 경우에만 이동통신망 전송을 허용하며, 선택하지 않은 경우 Wi-Fi에서만 전송한다.

## 철회·삭제·backup·통지

1. 철회는 해당 선택의 새 수집·보고·전송·학습 재사용을 차단한다. 기존 서버 자료 삭제를 자동으로 완료 처리하지 않는다.
2. 삭제요청은 저장 위치별 작업 ID를 만들고 active store, quarantine, 학습자료와 backup 만료를 분리 추적한다.
3. active store 삭제 성공은 backup 삭제 완료를 뜻하지 않는다. backup은 요청 ID와 tombstone을 연결하고 예정 만료일을 기록한다.
4. 부분 실패나 ambiguous commit은 `PARTIAL_OR_RECONCILIATION_REQUIRED`로 남기며 성공으로 바꾸지 않는다.
5. 사용자·기관 통지는 접수, 처리 중, 부분 완료, 완료, 거부·보존 예외 상태를 구분해야 한다. 최종 한국어 문안은 `NOT_APPROVED`다.
6. 실제 운영 이관, destructive deletion, 법률 승인과 사용자 통지는 `NOT_RUN / EXTERNAL`이다.

## 검증·receipt schema

실제 실행 전에는 receipt 행을 만들지 않는다. 실행 시 외부 통제 저장소의 receipt에는 다음 필드가 모두 필요하다.

| 필드 | 계약 |
|---|---|
| identity | `receipt_id`, `request_id`, `policy_version`, `actor_role`, `executed_at` |
| scope | `data_class`, `location`, `subject_scope`, `candidate_digest` |
| action | `TRANSFER`, `DELETE`, `EXCLUDE_FROM_TRAINING`, `BACKUP_TOMBSTONE` 중 하나 |
| result | `NOT_RUN`, `COMPLETED`, `PARTIAL`, `FAILED`, `RECONCILIATION_REQUIRED` |
| reconciliation | before/after row·object count, byte count, failed item reference |
| backup | backup scope, tombstone ID, scheduled expiry, actual expiry receipt |
| integrity | manifest SHA-256, external storage ID, verifier role, verification status |
| authority | legal review ID, approval ID, notification ID; 없으면 `NOT_APPROVED` 또는 `NOT_RUN` |

현재 실제 transfer receipt, deletion receipt, legal approval과 최종 한국어 notice는 0건이다. 이 문서와 timer·service template의 존재는 실행·승인·출시 증거가 아니며 release 상태는 `NOT_ELIGIBLE`이다.

## Raw collection 14일 검역·legacy 180일 수동 TTL

신규 raw commit은 receipt v2, `QUARANTINED`, `RAW_QUARANTINE_14D`로 분리되고 `quarantine_expires_at = committed_at + 14 days`인 기술 목표를 DB 제약으로 고정한다. REPORT/TRAINING 승인 여부는 원본 수명을 늘리지 않는다. 최신 법적 보존 APPLY event가 만료 전인 행만 삭제 후보에서 제외된다. 기존 receipt v1·`COMMITTED`·`RAW_ORIGINAL_180D` 행과 hash는 바꾸지 않으며 `retention_expires_at <= as-of`인 legacy 후보로 함께 처리한다.

`scripts/manage_raw_collection_retention.py`의 기본 preview는 DB·파일을 바꾸지 않는다. apply/reconcile은 별도 운영 승인과 최소권한 역할 아래 수동 one-shot으로만 실행하며, 14일/180일 후보를 fail-closed로 검증하고 실제 삭제 시 내용 없는 DB 삭제 receipt를 남긴다. service, timer, 자동 실행은 제공하지 않는다. 실제 운영 DB·object store에서의 삭제와 reconcile은 `NOT_RUN`이다.

TRAINING 승격은 최신 Backend `TRAINING_REUSE` 동의, 사람 승인, 명시적 비식별 PASS, 영상·원본 음성·정확 위치·경로·불명확한 제3자 얼굴 제외를 요구하며 raw 원본과 다른 sanitized artifact 저장 경계를 사용한다. 승인 dataset revision과 member/lifecycle lineage는 append-only이고 승인 시각부터 3년 만료를 기술 목표로 둔다. 철회·계정삭제·만료 revision은 새 학습 gate 발급을 차단하며 `model/train_yolo.py`는 dry-run을 포함한 실행 직전에 Backend DB에서 동의·삭제 fence·최신 revision을 다시 확인한다. `scripts/manage_training_dataset_lifecycle.py`는 수동 승인·짧은 HMAC gate 발급을, `scripts/manage_training_artifact_retention.py`는 삭제 가능한 파생물의 수동 preview/apply/reconcile과 삭제 receipt를 제공한다. 실제 운영 승격·학습·파생물 삭제는 `NOT_RUN`이다.

- 14일과 3년은 승인된 법적 보유기간이나 최종 사용자 고지가 아니라 현재 구현의 기술 목표다.
- backup restore tombstone reapply 검증은 `NOT_RUN`으로 유지한다.

## 개인정보 보호

- 검증 로그에는 실제 전화번호, 정확한 집 주소, secret을 그대로 붙이지 않는다.
- 제품 정책상 report image와 GPS는 서버 저장을 허용하지만, 공개/export 기본값은 필요한 범위로 제한한다.
- 이미지 업로드는 Pillow decode와 metadata 없는 재인코딩을 필수로 수행한다. decode/sanitize 불가 또는 Pillow 미설정이면 원본을 저장하지 않고 요청을 거부한다.
- 보호자 연락처는 local-only 설정으로 유지하며 report/STT/detect payload에 포함하지 않는다.
