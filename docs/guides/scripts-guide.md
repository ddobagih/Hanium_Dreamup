# 스크립트 가이드

이 문서는 [`scripts/`](../../scripts)의 도구를 현재 작업에 안전하게 고르는 기준이다. 각 스크립트의 상세 책임은 [scripts/README](../../scripts/README.md)가 설명하며, 이 문서는 긴 파일별 설명을 다시 복제하지 않는다.

## 두 축으로 분류한다

스크립트는 **생명주기 상태**와 **가장 강한 부작용 등급**을 함께 표시한다. 파일명에 날짜가 있다는 이유만으로 현재 또는 과거라고 단정하지 않는다. 현재 checkpoint, 제품 경계, 호출하는 CI·가이드와 스크립트 자체의 fail-closed 조건을 함께 확인한다.

### 생명주기 상태

| 상태 | 의미 | 사용 규칙 |
| --- | --- | --- |
| `CURRENT` | 현재 제품·제어·검증 흐름에서 사용하는 진입점 | 현재 가이드와 checkpoint가 가리키는 명령·버전을 그대로 사용 |
| `HISTORICAL` | 과거 계약·실행·제품 경계를 재현하기 위한 동결 자료 | 수정하거나 새 구현의 정본으로 사용하지 않고, 지정된 history adapter·감사에서만 실행 |
| `BLOCKED` | 정책상 금지됐거나 필수 전제·후속 계약이 없어 의도적으로 진행할 수 없는 진입점 | 종료 코드를 우회하거나 wrapper를 제거하지 말고 차단 이유와 대체 CURRENT 경로를 확인 |

분류가 없거나 서로 충돌하면 임의로 실행하지 않고 `BLOCKED_PENDING_CLASSIFICATION`으로 취급한다. 특히 `build_*`, `apply_*`, `materialize_*`, `promote_*`, `run_*` 접두어는 현재성이나 안전성을 보장하지 않는다.

### 부작용 등급

| 등급 | 가능한 변화 | 실행 전 최소 확인 |
| --- | --- | --- |
| `READ_ONLY` | 파일을 읽고 stdout·검사 결과만 반환 | 대상 commit·입력 경로·문서 버전 확인 |
| `LOCAL_BUILD` | Git 비추적 build, cache, 임시 출력 생성 | 디스크 공간, lock 의존성, 출력 위치와 정리 범위 확인 |
| `REPOSITORY_WRITE` | 추적 대상 문서·manifest·제어 event·산출물 변경 | 깨끗한 기준점, 복구 branch/bundle, 정확한 입력 hash, 생성 후 diff·validator·독립 검토 |
| `DB_DEVICE` | 격리 DB migration·행 변경, Android 설치·기기 자료 회수, 로컬 운영자료 변경 | 운영 대상과 분리된 정확한 DB/기기/경로, preview, 인증·권한, rollback과 개인정보 처리 확인 |
| `EXTERNAL` | 데이터셋·모델 다운로드, 제품 외부 서비스 호출, cloud 공개, 기관 제출처럼 프로젝트 자료나 외부 업무 상태를 주고받음 | 대상·계정·비용·공개범위·승인·receipt 위치를 명시하고 실제 외부 동작 여부를 재확인 |

한 스크립트가 여러 등급을 가질 수 있으면 실제 인자에서 가능한 가장 강한 등급을 사용한다. 예를 들어 preview는 `READ_ONLY`여도 `--apply`가 DB를 바꾸면 해당 실행은 `DB_DEVICE`다. 외부 제출 영수증을 만드는 도구와 실제 제출을 수행하는 행위도 구분한다. 정확한 lock·wrapper가 지정한 표준 package/Gradle 의존성 조회는 build 환경 준비로 보고 그 사실만으로 `EXTERNAL`로 올리지 않는다. 임의 URL, 제품·사용자 자료, 외부 API나 공개 상태를 다루면 `EXTERNAL`이다.

## 대표 CURRENT 진입점

| 목적 | 진입점 | 상태·부작용 | 주의 |
| --- | --- | --- | --- |
| 현재 작업 재개 경계 | [`check_walksafe_project_continuation_v2_4.py`](../../scripts/check_walksafe_project_continuation_v2_4.py) | `CURRENT / READ_ONLY` | 이전 `check_walksafe_project_continuation.py`나 v2.3 checker를 현재 정본으로 사용하지 않음 |
| 현재 Goal graph 검사 | [`check_walksafe_goal_graph_v2_4.py`](../../scripts/check_walksafe_goal_graph_v2_4.py) | `CURRENT / READ_ONLY` | PASS는 Goal 시작·완료 event나 외부 승인 생성이 아님 |
| 저장소 전수 catalog | [`generate_repository_catalogs.py`](../../scripts/generate_repository_catalogs.py) `--check` | `CURRENT / READ_ONLY` | 인자 없는 생성은 세 JSON을 쓰므로 `REPOSITORY_WRITE`; [catalog 설명](../catalogs/README.md)을 먼저 확인 |
| OpenAPI 일치 검사 | [`generate_walksafe_openapi.py`](../../scripts/generate_walksafe_openapi.py) `--check` | `CURRENT / READ_ONLY` | 생성 모드는 저장소 파일을 바꿀 수 있으므로 별도 `REPOSITORY_WRITE`로 취급 |
| 테스트 inventory | [`run_walksafe_test_layers_current.sh`](../../scripts/run_walksafe_test_layers_current.sh) `validate` | `CURRENT / DB_DEVICE` capability | `validate`는 테스트·DB·기기를 건드리지 않지만 같은 진입점의 functional/integration이 전용 DB migration과 opt-in 기기 테스트를 지원하므로 catalog에는 가장 강한 capability를 기록 |
| 자동 테스트 계층 | 같은 runner의 `unit`, `all` | `CURRENT / DB_DEVICE` capability | `all`은 `unit`→`functional`→`integration`만 실행한다. 잠긴 Python 환경을 입력으로 요구하고 Node toolchain lock·Gradle wrapper·전용 DB를 확인 |
| DB 포함 계층 | 같은 runner의 `functional`, `integration` | `CURRENT / DB_DEVICE` | 전용 PostGIS에 migration을 적용하며 integration의 연결 기기는 명시적 opt-in일 때만 실행 |
| 모델·데이터 감사 | 같은 runner의 `model-audit` | `CURRENT / LOCAL_BUILD` | 일반 `all`과 분리된 CI 단계이며 정식 모델·데이터 검증이나 권리 확인을 대신하지 않음 |
| 활성 세션 제어 | 같은 runner의 `active-session-control` | `CURRENT / LOCAL_BUILD` | strict continuation checker로 현재 managed bytes를 먼저 확인한 뒤 transition lifecycle test를 실행한다. commit-stable이며 일반 `all`과 분리된 CI 단계와 로컬 작업 시작·종료에 실행 |
| 중앙 활성 문서 검사 | [`check_walksafe_active_docs.py`](../../scripts/check_walksafe_active_docs.py) `--check` | `CURRENT / READ_ONLY` | 중앙 가이드·진입 문서만 검사하며 역사·증거 Markdown 전체를 현재 문서로 승격하지 않음 |
| 모델 runtime 확인 | [`manage_local_model_registry.py`](../../scripts/manage_local_model_registry.py) `verify-runtime`, [`check_android_tflite_contract_20260531.py`](../../scripts/check_android_tflite_contract_20260531.py) | `CURRENT / READ_ONLY` | 정적 hash·tensor 계약은 모델 정확도·기기 성능 PASS가 아님 |
| APK model asset 확인 | [`check_android_apk_model_asset_20260713.py`](../../scripts/check_android_apk_model_asset_20260713.py) | `CURRENT / READ_ONLY` | 먼저 생성된 APK만 검사하며 빌드·서명·기기 실행을 대신하지 않음 |
| report 보존 preview/apply | [`check_report_retention_dry_run.py`](../../scripts/check_report_retention_dry_run.py) | fixture/stdout preview `READ_ONLY`, DB preview·`--apply`는 `DB_DEVICE`; `--output-md`는 출력 위치에 따라 `LOCAL_BUILD` 또는 `REPOSITORY_WRITE` | apply 확인문·관리자 재인증·격리 경로 없이는 실행하지 않음 |
| 현장 telemetry 보존 | [`manage_field_telemetry_retention_20260711.py`](../../scripts/manage_field_telemetry_retention_20260711.py) | stdout preview `READ_ONLY`, `--receipt`는 출력 위치에 따라 `LOCAL_BUILD` 또는 `REPOSITORY_WRITE`, apply는 `DB_DEVICE` | 실제 수신일·scope·관리자 세션 결속 확인 |
| 백업·복구 drill | [`backup_walksafe_data_20260711.sh`](../../scripts/backup_walksafe_data_20260711.sh), [`restore_walksafe_backup_drill_20260711.sh`](../../scripts/restore_walksafe_backup_drill_20260711.sh) | `CURRENT / DB_DEVICE` | 문서화된 전용 0700 경로와 정확한 DB URL을 사용하고 shell로 우회 실행하지 않음 |
| 기관 제출 receipt | [`record_walksafe_agency_submission_20260711.py`](../../scripts/record_walksafe_agency_submission_20260711.py) | `CURRENT / REPOSITORY_WRITE` | 실제 제출을 수행하지 않으며, 외부 제출이 끝난 뒤 받은 접수정보만 기록 |

전체 파일의 기계적 lifecycle·부작용 분류는 [scripts catalog](../catalogs/scripts.json)를 사용하고, 긴 책임 설명은 [scripts/README](../../scripts/README.md)를 함께 본다. catalog는 경로 분류 원장이지 현재 정책·실행 상태를 승격하는 근거가 아니다.

## HISTORICAL·BLOCKED 예시

| 범위 | 분류 | 규칙 |
| --- | --- | --- |
| `check_walksafe_project_continuation.py`, `check_walksafe_goal_graph.py`, v2.3 checker·builder | `HISTORICAL` | 지정된 archive/history adapter로만 재생하고 현재 checkpoint 판정에 직접 사용하지 않음 |
| [`run_walksafe_test_layers_20260711.sh`](../../scripts/run_walksafe_test_layers_20260711.sh) | `HISTORICAL / DB_DEVICE` capability | 과거 제어 해시에 결속된 byte-exact 자료다. 일반 개발에는 실행하지 않고 현행 [`run_walksafe_test_layers_current.sh`](../../scripts/run_walksafe_test_layers_current.sh)를 사용한다. 현재 focus의 exact event 계약이 hash-bound 과거 명령을 직접 요구할 때만 그 계약 재현 범위에서 실행하고 현행 `validate`도 함께 수행 |
| 과거 FP/EPIC trace의 날짜별 builder·apply·materialize | 기본 `HISTORICAL / REPOSITORY_WRITE` | 현재 Goal이 해당 exact 경로·입력 hash·sequence를 요구하지 않는 한 실행하지 않음 |
| [`audit_project_classification_20260708.py`](../../scripts/audit_project_classification_20260708.py), [`validate_project_classification_20260708.py`](../../scripts/validate_project_classification_20260708.py) | `HISTORICAL / REPOSITORY_WRITE` | Web/PWA 주제품이던 2026-07-08 분류를 생성하며 validator도 결과 JSON·Markdown을 덮어쓴다. 현재 분류 정본으로 실행하지 않음 |
| `check_frontend_*`, PWA/Web release·field 계열 | 기본 `HISTORICAL` | Legacy Web 경계 회귀에만 사용하며 Android 제품 근거로 승격하지 않음 |
| [`build_walksafe_web_release_20260711.sh`](../../scripts/build_walksafe_web_release_20260711.sh) | `BLOCKED` | Legacy Web release를 의도적으로 차단한다. 종료 78을 우회하지 않음 |
| [`run_walksafe_remote_field_stack_20260711.sh`](../../scripts/run_walksafe_remote_field_stack_20260711.sh) | `BLOCKED` | 과거 Cloudflare 공개 launcher를 의도적으로 차단한다. 외부 공개 대체 경로로 쓰지 않음 |

## 실행 전 체크리스트

1. 저장소 루트, 현재 branch·HEAD와 `git status --short`를 확인한다.
2. [scripts/README](../../scripts/README.md), 현재 checkpoint와 호출하는 가이드에서 해당 파일이 `CURRENT`인지 확인한다.
3. 스크립트의 `--help`, `--dry-run`, `--print-only`, `--check`, preview 모드가 있으면 먼저 사용한다.
4. 입력·출력·DB·기기·외부 대상은 실행 전에 절대경로 또는 명시 값으로 확인한다. 빈 변수, glob, 운영 DB, 광범위한 정리 경로를 사용하지 않는다.
5. `REPOSITORY_WRITE` 이상은 복구 지점과 예상 변경 파일을 먼저 적고, 실행 뒤 `git diff --check`, 전용 validator와 독립 검토를 수행한다.
6. secret, `.env`, 원본 영상·음성, 정확 위치, 개인키와 운영 DB 덤프를 stdout·Git 산출물에 남기지 않는다.
7. 전제가 없으면 skip 성공으로 바꾸지 말고 `BLOCKED` 또는 `NOT_RUN`과 필요한 다음 조치를 기록한다.

## 새 스크립트를 추가할 때

- 한 가지 책임과 명시적인 exit code를 갖게 하고 기본 동작은 가능한 한 read-only 또는 preview로 둔다.
- 파일 상단이나 `--help`에 입력, 출력, 부작용, 필수 환경변수와 rollback을 적는다.
- `scripts/README.md`에 생명주기·부작용 분류와 현재 호출자를 추가한다.
- 테스트·validator를 함께 추가하고, 현재 진입점을 대체하면 기존 파일은 삭제 대신 `HISTORICAL` 또는 `BLOCKED` 경계와 대체 경로를 남긴다.
