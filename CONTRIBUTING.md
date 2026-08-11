# WalkSafe 기여 가이드

이 저장소의 기본 브랜치는 `current`입니다. `main`과 레거시 자료는 현재 제품 판단이나 새 작업의 출발점으로 사용하지 않습니다.

## 작업 시작

1. `README.md`, `AGENTS.md`, `docs/guides/README.md`를 순서대로 읽습니다.
2. 로컬 `current`를 원격과 맞춘 뒤 작은 작업 브랜치를 만듭니다.

   ```bash
   git switch current
   git pull --ff-only origin current
   git switch -c <종류>/<짧은-작업명>
   ```

3. 변경할 코드의 로컬 `README.md`, 관련 가이드, 기존 테스트를 확인합니다.
4. 목표와 성공 기준을 정하고, 요청 범위에 필요한 파일만 수정합니다.

한 브랜치와 Pull Request에는 검토 가능한 한 가지 목적만 담습니다. 기능 변경과 무관한 정리·리팩터링·포맷 변경을 함께 넣지 않습니다.

## 변경 원칙

- 제품은 `apps/android/app`과 `apps/android/adminapp`입니다. Gateway·Backend·모델·데이터·계약·배포 도구는 지원 구성요소입니다.
- `apps/web`을 비롯해 `LEGACY_REFERENCE`로 분류된 자료는 현재 정책이나 제품 구현의 근거로 사용하지 않습니다.
- 코드 동작이나 인터페이스가 바뀌면 관련 코드 가이드·산출물 설명·테스트도 같은 변경에서 갱신합니다.
- 새 의존성, 외부 서비스, 데이터 수집 범위, 권한을 임의로 추가하지 않습니다.
- 비밀값, 개인 식별 정보, 실제 위치·사진·신고 자료, 운영 로그를 커밋하지 않습니다.

## 정본·생성 파일

`docs/control`의 체크포인트·Goal·실행 증거와 산출물 원장의 canonical binding은 경로와 내용 해시로 결속될 수 있습니다. 승인된 정본, 과거 실행 원출력, receipt를 일반 문서처럼 직접 편집하거나 이동하지 않습니다.

`GENERATED` 파일은 해당 생성기와 입력을 먼저 수정한 뒤 생성 명령으로 다시 만듭니다. 생성기를 찾을 수 없거나 canonical·managed 여부가 불분명하면 변경을 멈추고 저장소 소유자에게 확인합니다. 기존 증거를 덮어쓰지 말고 필요한 경우 새 revision 또는 append-only 기록을 만듭니다.

자세한 경계는 `docs/guides/deliverables-guide.md`와 `docs/guides/repository-guide.md`를 따릅니다.

## 검증

먼저 테스트 분류가 유효한지 확인합니다.

```bash
export PYTHON_BIN="$PWD/.venv-tests/bin/python"
test -x "$PYTHON_BIN"
PYTHON_BIN="$PYTHON_BIN" scripts/run_walksafe_test_layers_current.sh validate
```

그다음 변경 영역의 가장 작은 테스트부터 실행하고, 필요할 때만 상위 계층으로 넓힙니다. 실행한 명령과 결과는 Pull Request에 그대로 적습니다. 테스트 선택과 환경은 `docs/guides/testing-guide.md`를 따릅니다.

개발 자동검증의 PASS는 정식 시험 완료가 아닙니다. 279건 정식 시험, 실제 기기·현장·기관·배포·외부 검토는 각각 등록된 실행 instance와 증거가 있어야만 상태를 바꿀 수 있습니다. 실행하지 않았다면 `NOT_RUN`으로 남기며, 자동검증만으로 출시 상태를 `ELIGIBLE`로 올리지 않습니다.

## 문서와 작업 기록

코드·문서·설정·검증 체계에 영향을 준 작업은 다음을 함께 처리합니다.

- 바뀐 사용법·경계·정책을 관련 가이드와 가까운 모듈 `README.md`에 반영합니다.
- 현재 날짜의 `daylog/YYYY-MM-DD.md`에 변경 파일과 검증 명령·결과를 짧게 기록합니다.
- 실제로 수행하지 않은 시험, 배포, 승인, 출시를 완료로 표현하지 않습니다.

## Pull Request

Pull Request의 대상 브랜치는 `current`입니다. 제출 전 다음을 확인합니다.

- 제목과 본문이 한 가지 목적과 실제 변경 범위를 설명합니다.
- 코드·가이드·테스트·daylog가 서로 일치합니다.
- 생성 파일은 재생성 방법과 입력 변경을 설명합니다.
- 정식 시험을 수행했다면 시험 ID·instance·증거 경로를 적고, 수행하지 않았다면 `NOT_RUN`이라고 적습니다.
- 보안 취약점이나 민감정보는 Pull Request에 쓰지 않고 `SECURITY.md`의 비공개 신고 절차를 따릅니다.

리뷰 지적은 필요한 줄만 수정하고, 승인 전에는 같은 작업 브랜치에서 자동검증 결과를 최신화합니다.
