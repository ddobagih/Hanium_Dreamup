# WalkSafe 구현 형상

> 포함 산출물: DEV-01, DEV-07, DEV-09, DEV-10, DEV-11, DEV-12, DEV-13, DEV-14  
> 버전: 0.1.0 · 상태: Draft · 승인: 미승인  
> 정책 기준선: `PB-WALKSAFE-FEATURE-POLICY-1.0.1`  
> 검증: 아직 실행하지 않음 · 출시: NOT_ELIGIBLE

## 이 문서가 답하는 질문

현재 코드·의존성·빌드·배포·DB·고정 시험자료(fixture) 가운데 무엇이 정식 구현 후보이고 어떤 상태인가?

## 쉬운 요약

이 문서는 확정된 기능 정책을 개발과 시험에 옮기는 첫 초안입니다. 저장소에 코드나 시험 파일이 있다는 사실만으로 기능이 완성됐거나 시험에 합격한 것은 아닙니다. 같은 코드·앱·모델·설정 묶음으로 다시 확인해야 합니다. 출시 전에 반드시 끝내야 할 검증 5개도 남아 있습니다.


## Phase 1 current-source·provenance binding

| 항목 | 결속 값 |
|---|---|
| 대상 locators | `DLV-DEV-09` → `#dev-09`; `DLV-DEV-12` → `#dev-12`; `DLV-DEV-14` → `#dev-14` |
| 정책 authority | `PB-WALKSAFE-FEATURE-POLICY-1.0.1` · manifest SHA-256 `b6f5b850a3983b8059b85d93dd07864520219d31fa65a65d740b6bab78231308` · effective decision register SHA-256 `4a448f65280c2cd8cd850a749434f4e124769e47b7b84e31e2e14c58328d2faf` |
| 현재 source provenance | `DIRTY_WORKTREE_EXACT_CONTENT_SNAPSHOT` · `2026-07-26` · 2,960 stable files · `path_set_sha256=971cee4fbded63faf05e30b0dc7343a9eff41e4612edff307f3df5581c880eff` · `content_set_sha256=f7a05a1abd7b89053dd7d7d052508dfd43b19821174c8de5a82fe754ae90cade` · `source_commit=null` |
| current manifest | `docs/deliverables/05-implementation/implementation-manifest-20260727-r002.json` · SHA-256 `f26709242bf7520ec9385feb70f5df859124700657d8fe2dc71c8d1d1df839c2` |
| historical manifest | `docs/deliverables/05-implementation/implementation-manifest.json` · SHA-256 `df1ac6dbbbd9f24e2fef08c58b4de8def742830c3ae260d930dd09ec0cf5b6dc` · current successor가 이 exact hash를 `HISTORICAL_STALE` predecessor로 결속하므로 no-op 보존 |
| reviewer contract | 작성 `TECHNICAL_EVIDENCE_OWNER`; 검토 `QA_OWNER`; 승인 `PROJECT_SCOPE_OWNER`; 현재 `NOT_PERFORMED / NOT_APPROVED` |
| 실행 경계 | formal build, migration 실행, fixture 시험은 모두 `NOT_RUN`; 경로·입력·명령·출력 계약 준비만 기록한다. |

<a id="dev-01"></a>
## DEV-01 소스코드

Android 사용자 앱, 아직 경계가 없는 Android 관리자 앱, 백엔드, 모델, 음성, 배포·검증 도구 후보를 파일 목록과 지문 기록인 [implementation-manifest.json](implementation-manifest.json)에 등록합니다. SHA-256은 파일이 바뀌었는지 확인하는 파일 지문입니다. 파일이 존재한다는 사실만 확인했으며 정책 구현 완료는 주장하지 않습니다. Web/PWA와 관련 배포 파일은 과거 참고용으로 따로 표시합니다.

<a id="dev-07"></a>
## DEV-07 의존성 버전 고정 파일(lock 파일)

각 개발환경의 lock 파일(설치할 의존성 버전을 고정한 파일)을 그대로 두고 경로와 파일 지문(hash)을 파일 목록·지문 기록(manifest)에 연결합니다. lock 파일이 없는 의존성, 모델·데이터 라이선스, 서로 다른 Python 환경 간 충돌은 별도 검토가 필요합니다.

<a id="dev-09"></a>
## DEV-09 빌드 스크립트

Gradle, Python, Docker와 기존 빌드 스크립트를 후보로 등록합니다. 정식 빌드에서는 사용한 코드 버전, 모델, 설정, DB 변경 순서를 함께 고정하고, 무엇으로 어떻게 만들었는지 DEV-20 기록에 남겨야 합니다.

현재 content binding은 source snapshot, lock, model/config, migration head, toolchain, named command, raw stdout/stderr, exit code, 산출물 경로·SHA-256을 한 run ID에 묶도록 요구합니다. 아직 그 run과 출력 artifact는 없으므로 `FORMAL_BUILD_NOT_RUN`입니다.

<a id="dev-10"></a>
## DEV-10 CI/CD 파이프라인

적용은 `ACTIVE_DRAFT`로 정합니다. `.github/workflows/quality.yml`의 과거 Web 중심 단계를 정식 범위에서 분리하고 Android 사용자 앱, 별도 관리자 앱, backend, API 계약, 비밀값 노출, 문서 구조검사를 파이프라인 대상으로 합니다. 관리자 앱 경계가 없거나 5개 gate가 `NOT_RUN`이면 build가 성공해도 출시는 계속 `NOT_ELIGIBLE`입니다.

### Phase 1 IN_SCOPE artifact 결속

범위는 `IN_SCOPE`, pipeline 실행·승인·release credit는 `0`입니다. add-only 정본 결속은 `implementation-manifest-20260728-r003.json`에 둡니다.

| artifact | bytes | SHA-256 | 현재 판정 |
|---|---:|---|---|
| `.github/workflows/quality.yml` | 3,736 | `9a8969eaf5453a28e9912f485bf7e63ae02c54d3fd18d659cbcd3f7e4c06ee83` | bytes 결속 완료, trigger/job/config 적합성 미검토 |
| `.github/workflows/android-device-acceptance.yml` | 10,624 | `14f8beb528b4f2337fcd646cbdf651658396b3f416e972d0f6fda90722016810` | bytes 결속 완료, 실제 기기 run 미실행 |

수용 crosswalk는 trigger·branch·환경, Android 사용자·관리자·gateway·backend·API 계약·secret scan·문서 job, cache key, artifact 전달·보존, 실패·release 차단을 포함합니다. 각 실제 값과 현재 정책 적합성은 `PENDING_CONTENT_AND_CONFIGURATION_REVIEW`, source commit은 `null`, source 기준은 R002 manifest의 dirty-worktree exact-content snapshot입니다. Web/PWA는 legacy reference로 제품 PASS에서 제외합니다.

범위 owner 김민호는 `USER_SELF_ASSERTED`입니다. 개발책임자·기술책임자의 개인 배정은 확인되지 않았고, 김민호의 일반 최종책임자 귀속을 기술승인으로 간주하지 않습니다. 승인행위는 `NOT_PERFORMED`, QA 검토자는 `UNASSIGNED`입니다. 책임자는 content review 전, actual CI run은 release candidate 고정 전까지 완료해야 합니다.

정책 gate `GATE-PHONE-QUEUE-BYTE-LIMIT`, `GATE-SERVER-CAPACITY-STATE-CONTRACT`, `GATE-RAW-COLLECTION-RELEASE-REVIEW`, `GATE-CLOUD-COST-MEASUREMENT`, `GATE-SINGLE-ADMIN-RECOVERY-DRILL`는 모두 `NOT_RUN`·미면제이며 workflow 존재·YAML parse·CI 성공으로 gate 또는 release PASS를 만들지 않습니다.

<a id="dev-11"></a>
## DEV-11 IaC

관리형 cloud의 staging·backend·PostGIS·원본 저장소·backup·관측 범위에서 `ACTIVE_DRAFT`로 정합니다. Android 앱스토어 배포는 서버 IaC와 별도로 관리합니다. 현재 Docker Compose, systemd, nginx는 재검증 후보이며 계정·원본 저장소·비밀관리·backup·관측을 같은 환경 ID로 재현하기 전에는 IaC 완료를 주장하지 않습니다.

### Phase 1 IN_SCOPE IaC 경계

범위는 `IN_SCOPE`입니다. 현행 후보 bytes는 `implementation-manifest-20260728-r003.json`의 `DEV11-*` binding 8개에 결속했습니다: `docker-compose.yml`, `deploy/README.md`, Android gateway nginx/env/systemd, backend env/systemd/migration service. 이는 self-host deployment candidate의 물리 결속이며 managed cloud IaC, 실제 staging, PostGIS·원본저장소·backup·관측 resource가 provision됐다는 뜻이 아닙니다.

| 필수 내용 | 현재 상태 | 책임 | 기한 조건 |
|---|---|---|---|
| target provider/account/region/environment ID | `BLOCKED_NO_APPROVED_STAGING_OR_ACCOUNT` | 기술책임자·프로젝트책임자 | staging 설계 승인 전 |
| resource/module/version과 network·IAM·encryption | `BLOCKED_TARGET_UNDECIDED` | 기술·보안책임자 | plan 생성 전 |
| secret 변수 schema와 state backend/lock/access | `BLOCKED_TARGET_UNDECIDED` | 기술·보안책임자 | 최초 apply 전 |
| plan review, backup/restore, rollback procedure | `NOT_RUN` | 기술·운영·QA | 배포 승인 전 |
| 후보 파일 bytes/hash/provenance | `BOUND_PHYSICAL_ONLY` | 개발책임자 | 내용 적합성 review 전 |

범위 owner 김민호는 `USER_SELF_ASSERTED`입니다. 개발·기술 approver 개인 배정과 실제 승인은 `NOT_PERFORMED`, QA 검토자는 `UNASSIGNED`입니다. 목표 환경을 정하기 전에는 임의 provider·resource·계정 값을 만들지 않습니다.

정책 gate `GATE-PHONE-QUEUE-BYTE-LIMIT`, `GATE-SERVER-CAPACITY-STATE-CONTRACT`, `GATE-RAW-COLLECTION-RELEASE-REVIEW`, `GATE-CLOUD-COST-MEASUREMENT`, `GATE-SINGLE-ADMIN-RECOVERY-DRILL`는 모두 `NOT_RUN`·미면제입니다. 특히 server capacity contract, cloud cost 측정과 single-admin recovery drill은 별 실행 receipt 없이는 닫히지 않습니다.

<a id="dev-12"></a>
## DEV-12 DB 구조 변경(migration)

`backend/alembic/versions`를 정본 후보로 등록합니다. DB 변경 적용 순서, 이전 버전으로 되돌릴 수 있는 범위(rollback), 운영 데이터 보존·삭제 정책, 마지막 DB 변경 버전을 앱·서버 빌드와 함께 시험해야 합니다.

현재 content binding은 exact source snapshot의 migration 파일 집합, 정렬된 revision chain, 적용 전·후 schema identity, rollback 가능 범위와 raw migration log/hash를 같은 formal build generation에 묶도록 요구합니다. 현재 migration 실행·rollback 검증은 `NOT_RUN`입니다.

<a id="dev-13"></a>
## DEV-13 초기·샘플 데이터

계약시험·시연·개발환경 초기화에 필요하므로 `ACTIVE_DRAFT`로 정합니다. 고정 시험자료(fixture)와 예시 자료(sample)는 실제 개인정보를 포함하지 않아야 하며 출처·사용권리·자료 구조 버전·초기화 명령·기대 결과를 기록합니다. 실제 원본 영상·음성·정확 위치는 Git 예시 자료로 두지 않습니다.

### Phase 1 IN_SCOPE fixture 계약

범위는 `IN_SCOPE`입니다. `implementation-manifest-20260728-r003.json`은 route fixture 1개, navigation policy case 1개, model-registry dataset fixture 4개와 voice placeholder 1개의 path/bytes/SHA-256을 결속합니다. 결속은 파일 존재와 identity만 뜻하며 source rights, 개인정보 부재, schema 적합성, load/reset 결과는 아직 검증하지 않았습니다.

fixture row schema는 `fixture_id`, path, bytes, sha256, purpose, allowed_environment, schema_id/version, generator/source, rights_status, privacy_status, load_command, reset_command, expected_result, reviewer를 사용합니다. voice `.gitkeep`은 `PLACEHOLDER_NOT_SAMPLE_DATA`로만 분류합니다.

| 상태 | blocker | 책임 | 기한 조건 |
|---|---|---|---|
| `BLOCKED` | fixture별 source·rights·privacy/de-identification 판정 | 데이터·보안책임자 | fixture 승인 전 |
| `BLOCKED` | schema version, load/reset command, expected result | 개발책임자 | 계약시험 baseline 전 |
| `NOT_RUN` | 두 번의 clean reset/load와 결과 비교 | 개발·QA | 정식 시험 사용 전 |

범위 owner 김민호는 `USER_SELF_ASSERTED`입니다. 개발·기술 approver 배정과 승인행위는 `NOT_PERFORMED`, QA 검토자는 `UNASSIGNED`입니다. 실제 개인정보가 없다고 추정해 적지 않습니다.

정책 gate `GATE-PHONE-QUEUE-BYTE-LIMIT`, `GATE-SERVER-CAPACITY-STATE-CONTRACT`, `GATE-RAW-COLLECTION-RELEASE-REVIEW`, `GATE-CLOUD-COST-MEASUREMENT`, `GATE-SINGLE-ADMIN-RECOVERY-DRILL`는 모두 `NOT_RUN`·미면제이며 fixture hash나 schema parse는 formal test 또는 release PASS가 아닙니다.

<a id="dev-14"></a>
## DEV-14 고정 시험자료(fixture)

API 약속과 고정 시험자료(fixture)는 운영 데이터와 분리하고, 기대하는 자료 구조·시험 목적·변경 조건을 기록합니다. 가짜 입력으로 만든 시험자료의 성공은 실제 휴대전화·현장시험 합격이 아닙니다.

현재 content binding은 fixture 경로·SHA-256, schema/version, 비식별 출처, 적용 test-case ID와 기대 결과를 결속하고 운영 원본과 분리하도록 요구합니다. fixture inventory는 current successor와 `docs/deliverables/06-testing/registers/test-cases.json`을 통해 추적하며 실제 case 실행은 `NOT_RUN`입니다.

## 후보 파일 집계

| 그룹 | 파일 수 | 예시 |
|---|---:|---|
| dependency_locks | 13 | `apps/android/app/gradle.lockfile`, `apps/web/package-lock.json`, `apps/web/quality-requirements.lock`, `backend/requirements.lock` |
| build_scripts | 14 | `apps/android/app/build.gradle.kts`, `apps/android/build.gradle.kts`, `apps/android/settings.gradle.kts`, `apps/web/package.json` |
| ci_pipeline | 2 | `.github/workflows/android-device-acceptance.yml`, `.github/workflows/quality.yml` |
| database_migrations | 10 | `backend/alembic/versions/202605120001_create_reports.py`, `backend/alembic/versions/202607110001_report_query_indexes.py`, `backend/alembic/versions/202607110002_report_data_constraints.py`, `backend/alembic/versions/202607110003_backend_safety_controls.py` |
| fixtures | 6 | `contracts/fixtures/walking-route-v1.json`, `tests/fixtures/model_registry_dataset/image.fixture`, `tests/fixtures/model_registry_dataset/label.txt`, `tests/fixtures/model_registry_dataset/manifest.csv` |
| deployment_candidates | 14 | `deploy/config/walksafe-backend.env.example`, `deploy/config/walksafe-report-retention.env.example`, `deploy/config/walksafe-voice.env.example`, `deploy/config/walksafe-web.env.example` |
| engineering_tools | 176 | `configs/README.md`, `configs/submission_exact8_requirements.lock`, `configs/submission_installer_requirements.lock`, `configs/submission_toolchain_lock_20260713.json` |
| legacy_references | 147 | `apps/web/.env.example`, `apps/web/README.md`, `apps/web/app/README.md`, `apps/web/app/_walksafe/README.md` |

## 승인 전 완료조건

- Android 사용자 앱과 별도 관리자 앱의 module·build 경계 확정
- 승인 요구·설계와 파일·설정·DB 구조 변경(migration)의 추적표(RTM) 연결
- 앱·서버 버전별 코드·모델·설정·소프트웨어 구성품 목록(SBOM)·빌드 생성 이력(provenance) 연결
- 남은 필수 검증 5개와 보안·현장·릴리스 선행조건 우회 0건

현재 상태는 Draft이며 구현 완료·통합 완료·출시 가능을 주장하지 않습니다.
