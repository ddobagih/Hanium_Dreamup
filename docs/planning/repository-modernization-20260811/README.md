# WalkSafe 저장소 현대화 계획

기준일: 2026-08-11

## 최종 목표

현행 WalkSafe 코드와 257종 산출물·검증 증거를 잃지 않으면서, 팀원이 GitHub 첫 화면에서 프로젝트·코드·산출물·테스트·스크립트·보안·릴리스 안내를 바로 찾을 수 있게 한다. 모든 추적 파일은 상태와 용도를 분류하고, 현재 제품에 필요하지 않은 자료는 복구 가능한 보존 지점과 명시적인 레거시 경계로 격리한다.

## 완료 기준

1. 현대화 전 `current`가 원격 archive 브랜치·태그와 로컬 bundle로 복구 가능하다.
2. 중앙 가이드가 실제 코드·산출물·검사 명령·출시 상태와 일치한다.
3. 모든 추적 파일이 정확히 하나의 상태로 분류된다.
4. `current` 브랜치에서 CI가 실행되고 테스트 계층·가이드 링크·분류 manifest 검사가 통과한다.
5. 레거시 이동은 통제·참조 결속이 없는 승인 대상에만 적용하며, 이동 목록과 복원 위치를 남긴다.
6. 제한 권한 신규 clone에서 같은 검증 결과를 재현하고 GitHub `current`에 게시한다.

## 상태 분류

| 상태 | 의미 | 기본 처리 |
|---|---|---|
| `CURRENT_PRODUCT` | 사용자·관리자 제품 코드 | 현재 경로 유지 |
| `SUPPORT` | Gateway, Backend, 모델·데이터, 계약·배포, 개발 도구 | 현재 경로 유지 |
| `GOVERNANCE` | 가이드, 정책, 계획, CI, 작업 규칙 | 최신화 후 유지 |
| `EVIDENCE` | 산출물, 체크포인트, 시험·실행 증거, 작업 로그 | 원래 경로와 이력 유지 |
| `GENERATED` | 결정론적으로 다시 만들 수 있는 카탈로그·보고서 | 생성기와 함께 유지 |
| `LEGACY_REFERENCE` | 제품 판단에 사용하지 않는 과거 코드·문서 | 보존 지점 또는 레거시 경계로 격리 |

캐시·임시 clone·비공개 bundle은 카탈로그 상태가 아니라 입력 제외 정책 `LOCAL_ONLY`로 다루고 Git 밖에서 권한을 제한한다.

## 단계별 실행 계약

각 단계는 `계획 확정 → 독립 검토 → 구현 → 검증 → 검토 기록` 순서로 수행한다.

### 0단계 — 기준선과 복구 지점

- 목표: 어떤 후속 변경도 현대화 전 상태를 잃게 만들지 않는다.
- 계획: HEAD·원격·기본 브랜치를 고정하고 archive 브랜치·태그·bundle을 만든다. 기존 실패 결과는 지문으로 고정한다.
- 검증: 원격 ref, bundle SHA-256, 제한 권한 clone, `git fsck`, 원격 `main` 불변.

### 1단계 — 가이드와 진입점

- 목표: 새 팀원이 두 번 이하의 링크 이동으로 코드·산출물·테스트에 도달한다.
- 계획: `docs/guides/` 중앙 가이드와 협업 문서를 만들고, 실제 상태와 충돌하는 활성 진입 문서만 최소 수정한다.
- 검증: 내부 링크 검사, 문서 사실 대조, `NOT_RUN`·`NOT_ELIGIBLE` 경계 유지.

### 2단계 — 전수 분류와 카탈로그

- 목표: 모든 추적 파일의 역할·상태·이동 정책을 기계적으로 확인한다.
- 계획: 결정론적 생성기로 저장소·스크립트·테스트 카탈로그를 만든다.
- 검증: 미분류·중복 분류 0개, 생성기 `--check` 재현, 카탈로그 수와 Git 파일 수 일치.

### 3단계 — CI·테스트·문서 검증

- 목표: 대표 개발 검사와 현재 브랜치 CI가 실제로 실행 가능한 상태가 된다.
- 계획: 누락된 테스트 계층 등록을 교정하고, 일반 제품 계층과 commit-stable checkpoint 검사를 분리하며, 가이드·분류 검사를 CI에 연결한다.
- 검증: test-layer `validate`, 관련 단위시험, 문서 링크·분류 검사, v2.4 continuation PASS.

### 4단계 — 레거시 격리와 현재 구조 정리

- 목표: 현재 탐색 표면에서는 활성 자산과 필요한 증거만 보이고 레거시는 명확히 구분된다.
- 계획: 전수 분류와 참조 감사에서 안전 판정을 받은 항목만 이동한다. 경로·해시 결속 항목은 이동하지 않고 레거시 표식 또는 stub만 사용한다.
- 검증: 이동 manifest와 archive ref 대조, 현재 제품 runtime의 레거시 의존 0개, 레거시 경계 보존용 회귀는 별도 표시, 링크·테스트·continuation 재검증.

### 5단계 — 전체 검수와 게시

- 목표: 로컬 결과와 GitHub 결과가 같고 다른 작업자가 재현할 수 있다.
- 계획:
  1. 4단계 독립 PASS 뒤 쓰기 범위를 동결하고 코드·문서·구조·보안을 독립 검수한다.
  2. daylog 경로를 먼저 만든 뒤 카탈로그를 최종 생성하고, 마지막 managed 변경에 맞춰 checkpoint의 두 content hash만 공식 계산값으로 조정한다.
  3. 원 작업트리에서 continuation, 기존 Goal 실패 지문, OpenAPI, catalog, 활성 문서, test inventory, `model-audit`, `all`, checkpoint-managed `active-session-control`을 수행한다.
  4. 의도한 경로와 staged 경로를 exact 대조한다. staged index tree 자체를 secret·파일 유형·mode·크기 검사하고 검사한 tree ID를 기록한 뒤 `current`에 하나의 결속된 현대화 commit을 만든다. commit tree가 검사한 index tree와 다르면 게시하지 않는다.
  5. `umask 077`과 `--no-hardlinks`를 적용한 신규 clone에서 새 commit의 HEAD·tree·`git fsck`와 commit-stable 검사를 재현한다. 일반 검사는 정확한 CPython 3.12.13과 전용 hash lock을, backup integrity는 별도의 정확한 CPython 3.14.6과 `tests/backup-integrity-cp314.lock`을 사용하고 `WALKSAFE_BACKUP_PYTHON_BIN`으로 전달한다. Node 22.23.1·Java 21·격리 PostGIS도 함께 고정한다. checkpoint-managed `active-session-control`도 clone에서 재현한다.
  6. push 직전 원격 refs를 재확인하고 force 없이 `HEAD:refs/heads/current`만 게시한다. 게시 commit SHA에 결속된 quality CI가 끝날 때까지 감시한 뒤 원격 `current`와 불변 `main`·archive branch·peeled preservation tag를 다시 확인한다.
  7. CI 이후에는 self-referential 후속 commit을 만들지 않고 결과를 local-memory와 최종 보고에 기록한다.
- 검증: continuation PASS, catalog byte-exact, Goal graph exit 1·기존 63건·출력 SHA-256 `bfab9a20b8ab621f94b45dc27d153a03d0c9062c398fb9ac51a96e13ee098417` exact, `validate`·`model-audit`·`all` PASS, 테스트 전후 비무시 변경 0, 신규 clone과 원본의 commit-stable 결과 일치, exact-SHA CI PASS, 원격 refs와 최종 보고 일치.

## 변경 금지 경계

- `docs/control/walksafe-project-continuation-checkpoint.json`의 의미·event history를 임의 변경하지 않는다.
- v2.4 protected 파일과 imported v2.2/v2.3 Goal 문서, 기존 gate·result 증거를 직접 수정하거나 이동하지 않는다.
- canonical binding 41개는 별도 정식 transaction 없이 수정·이동하지 않는다.
- managed 818경로의 bytes를 바꾸면 작업 종료 전 checkpoint의 두 content hash만 공식 계산값으로 원자 조정한다. 경로 목록은 이번 작업에서 확대하지 않는다.
- 기존 `main`은 변경하지 않는다.

## 완료 범위의 한계

이 작업은 저장소 구조·가이드·개발 검증 체계를 현대화한다. 정식 시험 279건, 실제 기기·현장·배포·외부 검토는 계속 `NOT_RUN`이며 출시는 `NOT_ELIGIBLE`이다. 기존 Goal graph의 standalone-history 63건 실패는 별도 history migration 없이는 성공으로 바꾸지 않고, 신규 실패가 늘지 않았는지 비교한다.
