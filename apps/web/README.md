# WalkSafe Legacy Web/PWA

분류: `LEGACY_REFERENCE_ONLY`

이 디렉터리의 Web UI, PWA, 브라우저 보행 화면, Web 관리자 화면과 과거 API 코드는 현재 WalkSafe 제품이 아닙니다. 과거 구현의 회귀검사와 역사 참고자료로만 보존합니다. 외부 주소에 공개하거나 사용자 시험·정식 배포·출시 후보로 사용하지 않습니다. Android용 4개 API는 `apps/android-gateway`로 추출됐으며 Next 요청 경계는 예외 없이 모든 경로에 `410 Gone`을 반환합니다.

현재 제품은 일반 사용자용 Android 앱과 별도 Android 관리자 앱입니다. Web 시험 결과를 Android 앱의 기능·안전·접근성·출시 완료 근거로 바꾸어 사용할 수 없습니다.

## 보존 범위

| 경로 | 분류 | 처리 원칙 |
|---|---|---|
| `app/page.tsx`, `app/_walksafe/`, `public/` | Legacy Web UI/PWA | 회귀·역사 참고만 허용, 외부 실행·배포 금지 |
| `app/admin/` | Legacy Web 관리자 UI | 별도 Android 관리자 앱의 대체물이 아님 |
| `app/api/` | 과거 Web API 참고 코드 | Android용 활성 route 4개는 제거, 남은 코드도 Next 경계에서 전부 `410` |
| `lib/`, `types/`, `tests/` | 공유 계약·회귀자료 후보 | Android/서버 계약과 일치하는 부분만 명시적으로 재사용 |

Android 사용자 앱은 독립 Android API Gateway의 다음 경로를 사용합니다.

- `/api/field-session`
- `/api/navigation/walking`
- `/api/navigation/destinations/search`
- `/api/reports/v2`

동일한 경로 문자열을 쓰더라도 Next route가 아닙니다. Gateway 실패 시 Next나 Backend 직접 연결로 자동 우회하지 않습니다.

## 허용되는 loopback Legacy 회귀검사

```bash
npm ci
npm test
npm run lint
npm run typecheck
npm run build
```

이 명령은 Legacy Web 참고 코드가 빌드되는지만 확인합니다. Web 제품 승인, PWA 출시, 실제 휴대전화 시험 또는 안전 성능 증거를 만들지 않습니다. `dev`와 `start` 공식 스크립트는 `127.0.0.1:3000`에만 묶이며 Web 화면·PWA·Web 관리자·API를 포함한 모든 요청은 `410 Gone`입니다.

`npm run dev`와 `npm run start`는 자동화된 격리 회귀에 필요한 경우만 사용합니다. loopback 개발 환경을 벗어나 공개하지 않습니다. 독립 Gateway의 내부 구현 검증은 운영 배포 승인이나 실기기 연결 완료가 아닙니다.

## 금지된 실행·배포

- `scripts/run_walksafe_remote_field_stack_20260711.sh`를 이용한 Cloudflare 공개 터널
- Web/PWA URL을 사용자·시연·현장시험·운영 주소로 제공하는 행위
- `build_walksafe_web_release_20260711.sh`, `build_walksafe_full_rc_20260713.py`, `validate_walksafe_full_rc_20260713.py`, `run_cloudflare_field_test_services_20260711.sh` 실행. 이 네 CLI는 부작용 전에 코드 78로 종료됩니다.
- `check_walksafe_release_evidence_20260711.py`의 `web-release`·`full` profile 실행. 두 역사 profile은 release PASS를 만들지 못하도록 import 전에 코드 78로 종료됩니다.
- PWA manifest·service worker·Web 접근성 시험을 Android 출시 증거로 사용하는 행위
- Web 관리자 화면을 현행 관리자 제품으로 사용하는 행위

원격 field launcher는 정책상 fail-closed입니다. `deploy/`의 Web systemd/nginx/env 파일은 활성 지시문이 전혀 없는 `LEGACY_REFERENCE_ONLY` 주석 자료이며 설치 가능한 템플릿이 아닙니다.

## 과거 구현을 읽을 때 주의할 점

과거 코드는 브라우저 camera/GPS, server-v2 탐지, TMAP 길안내, 음성, 신고와 PWA lifecycle을 포함합니다. 이는 2026-07-11 이전 Web 전제를 바탕으로 한 역사적 구현입니다. 코드 안의 `production`, `release`, `field`, `current` 같은 이름은 현재 제품 상태를 뜻하지 않습니다.

현재 승인 정책은 `PB-WALKSAFE-FEATURE-POLICY-1.0.1`이며 출시는 `NOT_ELIGIBLE`입니다. 제품 경계는 저장소 루트 `README.md`와 `docs/control/walksafe-project-resumption-runbook.md`를 우선합니다.
