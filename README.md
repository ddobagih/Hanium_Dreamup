# Hanium Dreamup — WalkSafe

WalkSafe는 시각장애인의 도심 보행을 돕는 Android 보행 보조 프로젝트입니다. 카메라로 가까운 위험을 찾고, TMAP으로 큰 이동 방향을 안내하며, 손상된 점자블록 신고를 돕습니다. 안전시험이 끝나기 전에는 흰지팡이·안내견·보호자를 대신하거나 보행 안전을 보장하는 제품으로 설명하지 않습니다.

## 현재 제품 경계

정책 기준선은 `PB-WALKSAFE-FEATURE-POLICY-1.0.1`입니다.

| 구성 | 현재 역할 | 외부 배포 |
|---|---|---|
| `apps/android/app` | 일반 사용자용 Android 앱 | 정식 제품 후보. 승인된 서명·기기 시험·출시 승인을 거친 뒤 Google Play 배포 |
| `apps/android/adminapp` | 한 명의 지정 관리자가 신고 검수와 수동 기관 전달 사실·상태·감사기록을 처리하는 제품 경계 | FP-008 저장소 내부 구현과 host/offline 검증 완료. 관리자 전용 서명·사설 배포·실기기·기관 전달·정식시험·출시승인은 `NOT_RUN` |
| `backend` | 계정·신고·데이터·외부 API용 서버 | Android 제품을 지원하는 서버 후보 |
| `apps/android-gateway` | Android의 세션·경로·목적지 검색·신고를 중계하는 독립 API Gateway | 내부 구현·검증 완료, 실제 배포·실기기 연결은 `NOT_RUN` |
| `apps/web`의 UI·PWA·관리자·과거 API 코드 | `LEGACY_REFERENCE_ONLY` | 모든 Next 런타임 요청은 `410`, 외부 실행·정식 배포·출시 산출물 생성 금지 |

Web/PWA 소스는 과거 구현 회귀와 역사 참고자료로 보존합니다. Android가 사용하는 4개 API는 독립 Gateway로 추출됐고 Next 런타임 예외는 없습니다. Web 화면을 현재 사용자 제품, Android 완료 근거, 출시 후보로 사용하지 않습니다. `scripts/run_walksafe_remote_field_stack_20260711.sh`는 정책상 fail-closed 상태이며 공개 터널을 열지 않습니다.

현재 5개 출시 gate는 모두 `NOT_RUN`이고 출시는 `NOT_ELIGIBLE`입니다. 코드가 빌드되거나 내부 테스트가 통과해도 실폰·현장·접근성·복구·용량 검증이 끝났다는 뜻은 아닙니다.

## 작업 시작

터미널이나 대화 문맥이 사라졌다면 먼저 다음 파일을 읽습니다.

1. `AGENTS.md`
2. `docs/control/walksafe-project-resumption-runbook.md`
3. `docs/control/walksafe-project-continuation-checkpoint.json`

그다음 읽기 전용 검사를 실행합니다.

```bash
python3 -B scripts/check_walksafe_project_continuation.py
```

산출물의 승인 상태와 다음 작업은 위 체크포인트와 DOC-01을 따릅니다. 과거 README나 현재 코드가 승인 정책과 다르면 승인 정책을 우선합니다.

## Android 사용자 앱 개발

```bash
cd apps/android
./gradlew test --no-daemon
./gradlew assembleDebug --no-daemon
```

debug APK는 개발·내부 검증용입니다. 정식 배포에는 사용자 앱과 관리자 앱의 서로 다른 식별자·서명·배포 기록, 지원 기기 검사, 불변 릴리스 묶음과 별도 승인이 필요합니다. 사용자 앱은 `apps/android/README.md`, 관리자 앱은 `apps/android/adminapp/README.md`를 봅니다.

## 서버 개발

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r backend/requirements.txt
cp backend/.env.example backend/.env
docker compose up -d db
python -m alembic -c backend/alembic.ini upgrade head
PYTHONPATH=. python -m uvicorn backend.app.main:app --reload --port 8000
```

독립 Android API Gateway는 `apps/android-gateway`에 있습니다. 개발용 기본 bind는 `127.0.0.1:8081`, 내부 Backend origin은 `http://127.0.0.1:8000`입니다. 실제 HTTPS 배포·운영 비밀 주입·실기기 연결은 아직 실행하지 않았으므로 운영 완료로 해석하지 않습니다.

## Legacy Web 회귀검사

로컬 개발 환경에서 과거 동작의 회귀만 확인할 수 있습니다.

```bash
cd apps/web
npm ci
npm test
npm run lint
npm run typecheck
npm run build
```

`npm run dev`, `npm run start`를 실행해도 모든 Next 요청은 `410`으로 닫힙니다. PWA 설치, 공개 터널, Web release/full-RC 도구는 현재 제품 실행·배포 절차가 아닙니다. 자세한 경계는 `apps/web/README.md`와 `deploy/README.md`를 봅니다.

## 핵심 문서

- 정책 기준선: `docs/control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json`
- 산출물 관리대장: `docs/deliverables/00-control/artifact-register.json`
- 요구사항 추적표: `docs/deliverables/03-requirements/rtm.json`
- 구현 Gap: `docs/control/audits/walksafe-implementation-gap-analysis-20260722-r001.json`
- 수정 백로그: `docs/control/audits/walksafe-implementation-remediation-backlog-20260722-r001.json`
- 현재 구현 기록: `docs/control/execution/walksafe-epic-01-phase-a-implementation-record-20260722.md`

모델·데이터 원본, `.pt`·`.onnx`·`.tflite`, 사용자 영상·음성·정확 위치, 비밀값과 운영 로그는 Git에 넣지 않습니다.
