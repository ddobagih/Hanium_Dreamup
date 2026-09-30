# 다른 컴퓨터에서 WalkSafe 작업 재개 가이드 (2026-09-25)

**먼저 읽을 문서:** `docs/handoff/WALKSAFE-TRANSFER-START-HERE.md`. 이 파일은 환경별 선택 시험과 재개 절차의 상세편이며, 전체 이전의 완료 판정이나 모든 자료의 목록이 아니다.

> 이 문서의 날짜는 **이 컴퓨터를 떠나는 인계일**이다. WalkSafe 분석·구현 작업의 시작일이나 전체 기간을 뜻하지 않는다. 9월 25일의 시험 결과만으로 장기간의 전체 작업을 대표하지 않는다.

## 0. 전체 이력의 시작점

- 먼저 `docs/handoff/2026-09-25-walksafe-provenance-map.md`를 읽는다. 21개 기능 분류 원문, 942행 계획, 9월 17일 이후의 역사 원장·증거·작업일지·제품 코드 사이의 관계와 읽는 순서를 적었다.
- `942`는 파일이나 확정 결함의 개수가 아니라 계획의 **항목 ID 942개**다. 원문 분석에서 온 872개와 보강 요구 70개를 구별한다. 원문 답변은 기능 분류 21개를 20개 답변으로 보관했고, 별도의 최초 분류 `00`이 있다. `16·17`은 합동 답변이다.
- 오래된 `RUNBOOK.md`/`progress.json`의 Chrome 자동화 상태와 9월 21일 계획의 `ACTIVE` 표기는 당시의 역사 기록이다. 이 컴퓨터의 목표는 인계를 위해 `paused`로 전환했다. 그 파일의 오래된 실행 지시를 현행 명령으로 그대로 재생하지 않는다.
- 9월 17일 스냅샷의 `DONE 61` 등 역사 집계와 현재 날짜형 CSV의 `DONE 52/PARTIAL 36/공란 854`는 **서로 다른 기준과 시점**이다. 수치를 합치거나 하나로 덮어쓰지 않는다.

## 1. 안전한 이전

1. 이 컴퓨터의 `/home/ddobagi/Hanium_Dreamup/` **전체 작업 디렉터리**와 `/home/ddobagi/Hanium_chat_review_2026-09-17/` 원문·증거 디렉터리를 보존한 채 다른 컴퓨터로 옮긴다. 이번 작업은 커밋/푸시 여부를 확인하지 않았으므로 원격 저장소만 새로 받으면 미커밋 변경이 빠질 수 있다.
2. 숨김 파일, 저장소 메타데이터, 파일 권한과 심볼릭 링크를 유지한다. 복사 대상의 기존 작업을 덮어쓰거나 삭제하는 동기화 옵션은 사용하지 않는다. 원본 컴퓨터의 파일은 인계 확인 전 삭제하지 않는다.
3. 운영 키링, API 키, 인증정보, DB 파일은 보고서에 붙이지 않는다. 필요한 비밀값은 별도 승인된 안전한 경로로 이전하고 새 컴퓨터에서 접근권한을 확인한다. `dist`, 빌드 출력과 `/tmp` 가상환경은 재생성 가능한 결과물로 취급하되 원본 작업트리의 사용자 데이터는 임의로 삭제하지 않는다.
4. 새 컴퓨터의 경로가 다르면 이 문서의 `/home/ddobagi/Hanium_Dreamup`을 실제 저장소 절대경로로 바꾼다. 프로젝트 `AGENTS.md`, 이 폴더의 **네 문서**, 해당 항목의 원문과 다일 작업일지를 먼저 읽는다. 기존 변경을 되돌리는 checkout/reset/clean은 하지 않는다.
5. 이전 완료 여부는 새 컴퓨터에서 파일과 미커밋 변경, 원문·증거, 빌드 환경을 각각 확인한다. 이 문서 작성 시점에는 원격 이전/복원 검증을 실행하지 않았다.

## 2. 환경과 자원

- Backend: 새 컴퓨터에서 프로젝트의 고정 의존성에 맞는 Python 가상환경을 만든다. 이 컴퓨터의 `/tmp/walksafe-goal-backend-unit.34XYr6` 경로는 이식 대상이 아니다. `pytest`가 없다면 해당 환경에 설치한 뒤 오프라인/가짜 DB·네트워크 시험부터 실행한다.
- Android: 설치된 JDK, Android SDK 및 기존 Gradle 캐시를 확인한다. `ANDROID_HOME`/`ANDROID_SDK_ROOT`는 새 SDK 절대경로로 지정한다. Gradle은 `--offline --max-workers=1 --no-daemon`과 제한된 JVM 메모리로 직렬 실행한다. 이 컴퓨터에서는 JVM 2GiB/Kotlin daemon 4GiB가 앱 선택 시험에 쓰였다.
- Gateway: 잠금 파일/기존 도구 버전에 맞는 Node 의존성을 새 컴퓨터에서 복원한다. `npm run build`는 `dist`를 삭제·재생성할 수 있으므로 기존 사용자 파일이 아닌지 먼저 확인한다. 실제 네트워크·운영 키·DB를 직접 시험에 연결하지 않는다.
- 한 번에 무거운 Android 빌드는 하나만 실행한다. Backend 집중 시험과 Gateway 가벼운 작업은 CPU·가용 메모리·swap을 확인한 범위에서만 병렬화한다. OOM이나 timeout을 PASS로 바꾸지 않는다.

## 3. 재개용 집중 명령

아래 명령은 새 컴퓨터에 해당 의존성이 준비됐다는 전제의 **선택 시험**이다. 현재 `HOLD` 항목의 실패 결과를 통과로 기대하지 않는다. 전체 suite 또는 운영 통합 시험 명령으로 확대하지 않는다.

```bash
cd /path/to/Hanium_Dreamup
export PYTHONPATH="$PWD"
export PYTHONDONTWRITEBYTECODE=1
export PYTEST_DISABLE_PLUGIN_AUTOLOAD=1
PYTHON=/path/to/backend-venv/bin/python
"$PYTHON" -m pytest -q -p no:cacheprovider -o addopts= \
  backend/tests/test_accounts.py::test_post_smtp_mark_commit_failure_cannot_replace_otp_on_lease_retry \
  backend/tests/test_report_image_keyring_locking.py \
  backend/tests/test_report_image_key_rotation_lifecycle.py::test_published_rotation_rejects_all_old_managers_and_new_workers_keep_old_decryption \
  backend/tests/test_report_image_key_rotation_lifecycle.py::test_provider_ready_cannot_substitute_for_successful_generation_synchronization
```

```bash
cd /path/to/Hanium_Dreamup/apps/android-gateway
npm run typecheck
npm run build
node --test dist/test/backend-session-current-route.test.js
```

```bash
cd /path/to/Hanium_Dreamup/apps/android
export ANDROID_HOME=/path/to/Android/Sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew :app:testDebugUnitTest \
  --tests kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceCommandTest \
  --offline --max-workers=1 --no-daemon \
  '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m' \
  '-Pkotlin.daemon.jvmargs=-Xmx4g'
```

```bash
cd /path/to/Hanium_Dreamup/apps/android
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :adminapp:testDebugUnitTest \
  --tests kr.co.hanium.dreamup.walksafe.admin.security.AdminLocalOperationCloseTest \
  --offline --max-workers=1 --no-daemon --rerun-tasks \
  '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m'
```

- S20-11은 기존 readiness 첫 시험이 현재 실패한다. 제품 오류 분류를 바로잡기 전에는 전체 backend suite로 판정을 확장하지 않는다.
- Gateway 현재성 게이트의 집중 16건은 마지막 실행에서 14 passed/2 failed였다. `manual`/`explicit` 정책 입력 차이가 가능한 원인일 뿐 확정 원인이 아니다.
- 실제 PostgreSQL 동시성, SMTP 송달, Android 실기기 마이크/음성/보행 시험은 이 명령들로 대체되지 않는다.

## 4. 완료 판정 순서

1. `2026-09-25-walksafe-status-report.md`의 `HOLD`를 우선 확인하고 각 파일의 단일 수정 담당자를 정한다. 같은 파일을 두 작업자가 동시에 고치지 않는다.
2. 첫 실패의 실제 원인을 분리해 최소 제품 수정과 직접 회귀를 만든다. 기존 안전 검사/시험 기대를 단지 통과시키려고 약화하지 않는다. 실패 로그와 첫 실행을 보존한다.
3. 해당 범위의 선택 시험을 한 번에 하나씩 실행하고 독립 정적 검토를 받는다. 실행 수, PASS 수, 빌드/실DB/실기기 범위를 분리한다.
4. 원문 항목의 전체 해결 조건을 대조한 뒤에만 날짜형 CSV 또는 후속 정본 상태를 갱신한다. `HOLD`나 국소 PASS를 자동으로 `DONE`에 합산하지 않는다.
5. 새 컴퓨터의 daylog에 환경, 시작 상태, 수정 파일, 명령/종료 코드, 남은 위험을 기록한다. 이전 컴퓨터의 원본과 증거는 그대로 둔다.
