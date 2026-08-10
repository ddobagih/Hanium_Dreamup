# WalkSafe 개발자 안내서

> 포함 산출물: DEV-02, DEV-03, DEV-04, DEV-05, DEV-06, DEV-08  
> 버전: 0.1.0 · 상태: Draft · 승인: 미승인  
> 정책 기준선: WalkSafe 기능 정책 1.0.0  
> 검증: 아직 실행하지 않음 · 출시: NOT_ELIGIBLE

## 이 문서가 답하는 질문

정식 제품 경계를 지키면서 개발환경을 만들고, 실행·시험·변경 검토를 어떻게 해야 하는가?

## 쉬운 요약

이 문서는 확정된 기능 정책을 개발과 시험에 옮기는 첫 초안입니다. 저장소에 코드나 시험 파일이 있다는 사실만으로 기능이 완성됐거나 시험에 합격한 것은 아닙니다. 같은 코드·앱·모델·설정 묶음으로 다시 확인해야 합니다. 출시 전에 반드시 끝내야 할 검증 5개도 남아 있습니다.


## 확정된 제품 경계

- 정식 사용자 제품: Android 사용자 앱
- 정식 운영 제품: 별도 Android 관리자 앱
- 서버: 계정, TMAP 중계, 신고, 수집 원본과 그 저장정보, 운영 API
- 단말 우선 처리: 카메라 탐지, 위험 판단, 호출어·STT·TTS·진동
- 과거 참고자료: `apps/web`의 Web/PWA. 현재 Android 제품의 완료나 시험 합격 근거로 계산하지 않는다.
- 기준 정책: FP-007 사용자용 안드로이드 전용 앱, FP-008 관리자용 안드로이드 앱, FP-009 지원 기기와 과거 웹 버전

## 현재 프로젝트 단계

- 사용자가 답한 현재 마일스톤은 **2026-07-26 통제 시연**입니다.
- 이 날짜는 시연 대상이지 정식 출시일이 아닙니다. 5개 gate와 실기기·현장·접근성·보안 검증이 남아 있습니다.
- 정해진 예산은 없으며, 비용이 드는 cloud·기기·외부검토는 실제 견적과 승인이 생기기 전까지 금액을 꾸며 쓰지 않습니다.

<a id="dev-02"></a>
## DEV-02 프로젝트 README와 저장소 안내

| 위치 | 의미 | 현재 취급 |
|---|---|---|
| `apps/android` | Android 사용자 앱과 현재 Android 코드 후보 | 정책 재검증 필요 |
| `backend` | FastAPI·PostGIS·TMAP·신고 서버 후보 | 정책 재검증 필요 |
| `contracts/walksafe.openapi.json` | API 계약 후보 | DES-09·10 승인 전 Draft 입력 |
| `model` | 학습·평가·모델 도구 후보 | AIML 기준선과 별도 결속 필요 |
| `voice` | Python 음성 서비스 후보 | 단말 우선 정책과 책임 경계 재검토 필요 |
| `apps/web` | 과거 Web/PWA 구현 | 과거 비교용으로만 사용 |
| `deploy`, `.github/workflows` | 배포·CI 후보 | 정식 Android/서버 배포 기준으로 재검토 필요 |

<a id="dev-03"></a>
## DEV-03 개발환경 만들기

실제 비밀값을 문서나 Git에 넣지 않습니다. `.env.example`만 복사해 로컬 값을 별도 보관하고, 운영 키와 시험 키를 분리합니다.

### Android

1. JDK와 Android SDK 버전을 `apps/android`의 Gradle 설정에 맞춘다.
2. `apps/android/gradlew`를 사용하고 임의의 전역 Gradle 버전에 의존하지 않는다.
3. 모델 설정과 TFLite 자산은 `apps/android/app/src/main/assets`의 경로와 파일 지문(hash, 파일이 바뀌었는지 확인하는 값)을 함께 확인한다.

```bash
cd apps/android
./gradlew test --no-daemon
./gradlew lint --no-daemon
./gradlew assembleDebug --no-daemon
```

종료할 때는 실행 중인 Gradle·ADB 명령을 종료하고, `apps/android/app/build/reports` 결과와 APK 파일 지문을 실행 기록에 연결합니다. build 디렉터리를 지우는 것은 증거 연결을 마친 뒤에만 합니다.

### 백엔드

1. 전용 Python 환경을 만든다.
2. `backend/requirements.lock` 의존성 버전 고정 파일(lock 파일)과 그 파일 지문(hash)을 확인해 같은 버전을 설치한다.
3. 이름에 `test`가 포함된 격리 PostGIS만 시험에 사용한다.

```bash
docker compose up -d db
python -m alembic -c backend/alembic.ini upgrade head
PYTHONPATH=. python -m uvicorn backend.app.main:app --reload --port 8000
```

서버는 `Ctrl+C`로 종료하고 시험 DB는 `docker compose stop db`로 정지합니다. 시험 DB volume 삭제는 복원·재현에 필요한 증거를 확인한 뒤 별도 승인된 정리 작업으로 합니다. 실행 log에는 secret·정확 위치·원본 영상·음성을 남기지 않습니다.

### 모델·음성·Web 참고 구현

- 모델과 음성은 각 디렉터리의 의존성 버전 고정 파일(lock 파일)과 README를 따른다.
- Web/PWA 명령은 과거 동작 비교에만 사용하고 Android 정식 제품의 인수 근거로 바꾸지 않는다.
- 실행 명령이 성공해도 정책 적합성과 실기기 안전성이 자동으로 증명되는 것은 아니다.

<a id="dev-08"></a>
## DEV-08 설정과 환경변수 규칙

- 예제 위치: `backend/.env.example`, `apps/web/.env.example`, `deploy/config/*.env.example`
- 실제 비밀값(secret), 인증 토큰, 인증서, 정확 위치, 원본 영상·음성은 문서나 고정 시험자료(fixture)에 넣지 않는다.
- 환경변수 이름·필수 여부·기본값·민감도·적용 모듈을 DES-12 데이터 사전과 구현 파일 목록·지문 기록(manifest)에 연결한다.
- release Android 앱에는 운영 secret을 내장하지 않고 서버 중계를 사용한다.
- 모델, threshold, class order, API schema는 버전과 SHA-256을 함께 고정한다.

<a id="dev-04"></a>
## DEV-04 실행·시험의 최소 원칙

1. 정확한 코드 버전(source commit), 빌드 ID, 모델, 설정, DB 구조 변경(migration), 기기·OS, 환경, 수행시각을 기록한다.
2. 자동 시험은 실패·skip·mock·부분 성공을 PASS로 바꾸지 않는다.
3. 실기기·현장 시험은 참여자 동의와 안전계획이 승인된 뒤 실행한다.
4. 원본 수집 관련 시험은 승인된 동의·보존·삭제 조건을 그대로 사용한다.
5. 결과는 `06-testing/evidence/executions/<run-id>.json`에 새 파일로 추가하고 과거 결과를 덮어쓰지 않는다.

<a id="dev-05"></a>
## DEV-05 기여·코드리뷰 규칙

- branch는 `feat/<issue-id>`, `fix/<issue-id>`, `docs/<issue-id>` 형식을 기본으로 하고 하나의 변경 목적만 담습니다.
- commit은 한 가지 논리 변경으로 나누고, 메시지와 리뷰 기록에 정책 ID·요구 ID·시험 ID를 연결합니다.
- PR이 있으면 diff·자동검사·미해결 위험을 확인한 뒤 merge합니다. 1인 개발에서는 없는 두 번째 사람을 기록하지 않고, 변경 작성과 승인 사이에 다시 읽는 시점을 나눠 checklist·자동검사·장애 복귀 계획을 남깁니다.
- 권한·위치·영상·음성·인증·삭제·보행 안전 변경은 보안·개인정보·안전 영향분석을 포함합니다. 외부·독립 검토가 명시된 gate는 실제 검토자 없이 닫지 않습니다.
- merge 전에 추가된 파일·Git 이력의 secret 후보와 개인정보 원본을 확인합니다. 유출이 의심되면 먼저 키를 폐기·회전하고 영향을 기록합니다.
- 돌릴 때는 승인 commit을 덮어쓰지 않고 revert commit과 재검증 결과를 남깁니다. 개인정보 삭제 상태·DB 구조·새 자료를 잃는 돌리기는 하지 않습니다.
- Android 정식 제품과 Web/PWA 레거시 경계를 흐리는 변경은 merge하지 않습니다.

<a id="dev-06"></a>
## DEV-06 코딩 규칙

- Kotlin은 프로젝트 Gradle 설정과 Android Kotlin 스타일을 따르고, Python은 4칸 들여쓰기·명시적 type·짧은 함수를 기본으로 합니다. 도구 이름·버전·규칙을 lock하기 전에는 특정 formatter가 통과했다고 쓰지 않습니다.
- coroutine·thread·callback은 보행 세션 생명주기에 묶고, 일시중지·종료·권한 철회에서 취소합니다. Camera·Location·microphone·file·DB handle은 소유자와 닫는 지점을 코드에 보입니다.
- 실패 시 안전한 상태로 닫고, 불완전한 API 응답·센서값·모델 출력은 사용하지 않습니다.
- 수치와 상태 전이는 이름 있는 상수·정책 ID로 추적하고 시간·거리·좌표 단위와 null 의미를 API·DB 계약에 명시합니다.
- 로그에는 secret과 원본 개인정보를 남기지 않고 correlation ID·상태·오류 코드만 남깁니다.
- 네트워크 재시도는 같은 요청 ID로 중복을 막고 무한 반복하지 않으며, 취소·timeout·재시도 한계를 시험합니다.
- 새 동작에는 정상·거부·철회·오프라인·중복·복구 경계 시험을 함께 추가합니다.
- 현재 정식 최소 자동검사는 Android `./gradlew lint test`, Python 문법·단위·계약 시험입니다. 실제 lint·정적분석 산출물은 DEV-16에서 실행 build와 도구 버전에 결속하기 전까지 `NOT_RUN`입니다.

## 현재 차단 항목

- 휴대전화 대기자료의 실제 용량 한도 — 지원 기기별 저장공간과 대기자료 크기를 실측해 휴대폰 바이트 상한을 정한다.
- 서버 용량상태를 휴대전화에 전달하는 규칙 — 서버 부하·상태변화 지연·오프라인 시간을 측정해 온라인 조회주기와 TTL을 정하고, 버전·관측시각·오프라인 동작을 API와 단말 상태기계로 명세해 시험한다.
- 무가림 원본 수집의 출시 전 독립 검토 — 출시 전 독립 검토와 고지·동의·권리행사 절차를 완료한다. 검토 결과가 수집 범위나 절차 변경을 요구하면 변경요청을 만들고 영향분석과 제품책임자 재승인을 거친다.
- 실제 클라우드 저장비 측정 — 정책 가정과 실제 저장량·요청·복원 비용을 비교해 월 30,000원 상한 충족을 확인한다.
- 관리자 휴대전화 분실 복구훈련 — 실제 사용자시험 또는 배포 전에 관리자 휴대전화 분실을 가정해 외부 복구수단 사용, 기기 세션 폐기, 고위험 작업 동결과 복구를 한 번 실행하고 증거를 남긴다.

이 항목들은 개발 시작 전체를 막지는 않지만, 관련 문서 승인과 사용자시험·출시판정 전에 반드시 끝내야 합니다.

## 이번 버전 변경점

- 정책 기준선 1.0.0 승인 뒤 DEV-02~06·08의 정식 초안을 처음 개설했다.
- Android 사용자·관리자 앱을 정식 제품, Web/PWA를 과거 참고용으로 명시했다.
- 과거 코드와 시험을 PASS가 아닌 재검증 후보로 분리했다.
