# 프론트엔드 3일 실행 계획

작성 기준일: 2026-05-13 KST
실행 기간: 2026-05-14 ~ 2026-05-16
담당 범위: `apps/web` 기반 PWA/관리자 화면의 검증, 연동 준비, 접근성 점검

## 기준 자료

- 프로젝트 개요서: `2026 ICT 한이음 드림업 프로젝트 개요서.pdf`
- 수행계획서: `2026년 한이음 드림업 프로젝트 수행계획서.pdf`
- 전체 계획: `PROJECT_PLAN.md`
- 현재 상태: `docs/current_status.md`, `docs/pwa_backend_status.md`, `docs/ui_feature_inventory.md`
- 프론트/API 계약: `docs/frontend_handoff_without_model.md`, `docs/inference_contract.md`, `docs/frontend_api_examples.md`, `docs/api_reference.md`, `docs/backend_error_contract.md`
- 접근성/디자인: `DESIGN.md`, `docs/figma_ui_handoff.md`, `docs/figma_make_accessibility_review.md`
- 실폰 검증: `docs/neck_worn_phone_test_checklist.md`
- 모델/STT 의존성: `docs/model_integration_plan.md`, `docs/model_placeholder_systems.md`, `docs/voice_stt_tts_status.md`

## 현재 구현 상태

현재 PWA는 실제 YOLO/ONNX 모델이나 로컬 STT 서버와 연결되지 않았다. `NEXT_PUBLIC_DETECTOR_MODE=fake` 기준의 fake detector로 카메라, 위험 표시, TTS/진동, 신고 API, 관리자 운영 화면의 통합 흐름을 확인하는 단계다. fake 결과는 실제 보행 안전 판단, 모델 정확도, 최종 성능 수치에 사용하지 않는다.

| 영역 | 현재 상태 | 주요 파일 |
| --- | --- | --- |
| 보행자 메인 | 후면 카메라 권한 요청, 카메라 프리뷰, fake bbox overlay, GPS/방향 표시, Web Speech API TTS, Vibration API, 신고 버튼 | `apps/web/app/page.tsx` |
| 탐지 이벤트 | 4개 클래스 `DetectionEvent`를 순환 생성, `source: "fake"` 유지 | `apps/web/lib/detector.ts`, `apps/web/types/inference.ts` |
| 신고 API | 중복 후보 조회 후 `POST /reports` multipart 업로드, 이미지 캡처 JPEG 전송 | `apps/web/lib/report-api.ts` |
| 관리자 화면 | 신고 목록/필터/정렬/상세/이미지/위치 품질/검토 플래그/상태 변경 | `apps/web/app/admin/page.tsx` |
| PWA shell | manifest, standalone portrait, service worker 기본 shell cache | `apps/web/public/manifest.webmanifest`, `apps/web/public/sw.js` |
| 모델 상태 | `/detect/health`와 `/detect` 연동 UI는 아직 없음. 서버는 모델 미연결 시 `model_unavailable` | `docs/inference_contract.md`, `docs/model_integration_plan.md` |
| STT | 브라우저 녹음 UI와 `voice/server.py`의 `POST /speech/stt`는 아직 미연결 | `docs/voice_stt_tts_status.md` |

문서 작성 중 확인한 프론트엔드 검증:

```bash
cd apps/web
npm run lint
npm run typecheck
```

결과: 2026-05-13 기준 둘 다 통과.

## 3일 목표

1. fake detector 기반 PWA/백엔드 신고 흐름을 로컬과 실폰에서 검증 가능한 상태로 정리한다.
2. 시각장애인/저시력 보행자 전제에 맞게 카메라보다 TTS, 진동, 큰 터치 영역, 상태 문구가 실제로 쓸 수 있는지 확인한다.
3. 실제 모델과 STT 연결 전 필요한 프론트엔드 계약, 대기 상태, 수동 검증 항목, 막힘 조건을 분리한다.
4. 2026-05-16 종료 시점에는 “현재 되는 것”, “수동 검증 결과”, “모델/STT/백엔드에 의존하는 것”, “프론트 코드 수정 후보”가 구분되어야 한다.

## 공통 실행 원칙

- fake 탐지 화면은 `데모 탐지 모드` 또는 `모델 연결 대기`로만 표현한다.
- `source: "fake"` 신고는 API/UI 통합 확인용으로만 사용한다.
- 위치가 없어도 신고 흐름은 막지 않고, `missing_location`/`low_location_accuracy`는 운영자 검토 플래그로 본다.
- 중복 후보가 있어도 보행 중 확인 모달로 사용자를 멈추지 않는다.
- 실폰에서 확인해야 의미가 있는 항목은 반드시 `수동 검증 필요`로 표시한다.
- 앱 코드 수정이 필요하다고 판단되면 바로 고치지 말고, 담당 영역과 의존성을 적어 별도 작업으로 분리한다.

## 2026-05-14: 로컬 기준선과 API 통합 확인

목표: 현재 구현이 문서 계약과 맞는지 로컬에서 확인하고, 실폰 검증 전에 막히는 환경 문제를 제거한다.

| 체크 항목 | 수행 방법 | 완료 기준 | 의존성 | 수동 검증 방법 | 막히는 조건 |
| --- | --- | --- | --- | --- | --- |
| 프론트 정적 검증 | `cd apps/web && npm run lint && npm run typecheck` 실행 | 오류 없이 종료 | `apps/web/node_modules` 설치 | 해당 없음 | 의존성 설치 실패, TypeScript/ESLint 오류 |
| 백엔드 연결 준비 | `docker compose up -d db`, Alembic 적용, `uvicorn backend.app.main:app --reload --port 8000` 실행 | `GET http://127.0.0.1:8000/health`가 `{"status":"ok"}` 반환 | 백엔드/DB 담당 변경 사항 | 해당 없음 | DB 컨테이너 실패, 마이그레이션 실패, 포트 충돌 |
| 모델 placeholder 확인 | `GET /detect/health` 호출 | 모델 미연결이면 `model_status: "unavailable"` 확인 | 백엔드 `backend/app/detector.py` | 해당 없음 | 실제 모델 준비 전인데 UI/문서가 ready처럼 표현됨 |
| PWA 로컬 실행 | `cd apps/web && npm run dev`, `http://localhost:3000` 접속 | `/`가 보행 보조 화면으로 열리고 landing page가 아님 | Next.js dev server | 브라우저 확인 | 카메라 권한 차단, dev server 포트 충돌 |
| fake 탐지 확인 | 카메라 허용 후 2.8초 간격 탐지 변화를 관찰 | 4개 클래스가 순환되고 bbox, 신뢰도, `데모 탐지 모드`가 보임 | `apps/web/lib/detector.ts` | 브라우저 확인 | fake 결과가 실제 AI 탐지처럼 보이는 문구 |
| 신고 생성 smoke test | 탐지 발생 후 `현재 위험 신고` 클릭 | 성공 메시지와 앞 8자리 ID 또는 유사 신고 건수가 표시됨 | 백엔드 `/reports`, `NEXT_PUBLIC_API_BASE_URL` | 브라우저 확인 | API 422, 이미지 업로드 오류, CORS/포트 불일치 |
| 관리자 조회 확인 | `http://localhost:3000/admin` 접속 후 새 신고 확인 | 신고 목록, 상세 이미지, 위치 품질, `fake_source` 검토 표시가 보임 | 신고 생성 데이터 | 브라우저 확인 | 이미지 경로 접근 실패, 목록 API 실패 |
| 관리자 필터/상태 변경 | 소스 `Fake`, 상태, 위험 유형 필터와 `new/reviewed/resolved` 변경 확인 | 필터 결과와 상태 변경 후 UI가 갱신됨 | 백엔드 `GET /reports`, `PATCH /reports/{id}/status` | 브라우저 확인 | 상태 변경 실패, 빈 목록/오류 상태가 구분 안 됨 |
| 접근성 빠른 점검 | 키보드 Tab, 버튼 accessible label, `aria-live` 상태를 브라우저 devtools로 확인 | 신고/음성/재연결 조작이 키보드로 접근 가능 | `apps/web/app/page.tsx` | 브라우저 확인 | 카메라 장식 요소가 반복 낭독되거나 버튼 이유가 불명확 |

산출물:

- 로컬 smoke 결과: 통과/실패와 실패 재현 조건
- 실폰 검증 전에 필요한 환경 준비 목록
- 앱 코드 수정 후보가 있으면 파일 경로와 현상만 기록

## 2026-05-15: 실폰/목걸이 착용 PWA 검증

목표: 계획서의 바디캠/목걸이 착용 전제에서 카메라, TTS, 진동, 위치, 신고 흐름이 실제로 확인 가능한지 검증한다.

권장 접속 방식:

```bash
adb reverse tcp:3000 tcp:3000
adb reverse tcp:8000 tcp:8000
```

Android Chrome에서 `http://localhost:3000` 접속을 우선한다. 같은 Wi-Fi의 `http://<PC-IP>:3000` 접속은 카메라, service worker, 위치 권한이 secure context 문제로 막힐 수 있다.

| 체크 항목 | 수행 방법 | 완료 기준 | 의존성 | 수동 검증 방법 | 막히는 조건 |
| --- | --- | --- | --- | --- | --- |
| 실폰 접속 | Android Chrome에서 PWA 접속, 카메라/위치 권한 허용 | 후면 카메라 프리뷰와 상태 패널이 첫 화면에 보임 | 실기기, USB 디버깅 또는 HTTPS 개발 URL | 수동 검증 필요 | 실기기 없음, 권한 정책 차단, ADB reverse 실패 |
| 목걸이 카메라 각도 | `docs/neck_worn_phone_test_checklist.md` 기준으로 목걸이/스트랩 착용 | 전방 1~3m와 바닥 일부가 함께 보임 | 목걸이/스트랩, 안전한 실내 공간 | 수동 검증 필요 | 카메라가 바닥/상체만 찍힘, 줄/옷깃이 렌즈를 가림 |
| 흔들림과 bbox 가독성 | 천천히 걸으며 fake bbox와 위험 상태를 관찰 | bbox가 화면 밖으로 과도하게 튀지 않고 위험 상태 문구가 읽힘 | 실폰 카메라 | 수동 검증 필요 | 흔들림이 심해 카메라 입력 자체가 불안정 |
| TTS 경고 청취 | 4개 위험 유형의 안내 문구를 직접 듣고 기록 | 짧고 행동 지시가 명확함: 발밑 주의, 천천히 이동, 우회 | Web Speech API 한국어 음성 | 수동 검증 필요 | 브라우저/기기에서 한국어 TTS 미지원, 음량 부족 |
| 진동 패턴 확인 | 음성 켜짐/꺼짐 각각에서 위험 알림, 신고 성공/실패를 확인 | 음성 꺼짐 상태에서 더 강한 진동이 구분됨 | Android Chrome Vibration API | 수동 검증 필요 | iOS/일부 브라우저에서 진동 미지원 |
| GPS 정확도 확인 | 실내/실외 안전 구역에서 위치와 정확도 표시 확인 | 좌표, 정확도 m, 위치 권한 실패 상태가 구분됨 | Geolocation 권한 | 수동 검증 필요 | 실내 GPS 불안정, 권한 거부, secure context 문제 |
| 방향 센서 확인 | 기기를 회전하며 방향 값과 방위 라벨 확인 | `대기 중` 또는 방위/도 값이 실제 회전에 따라 변함 | DeviceOrientation 지원 브라우저 | 수동 검증 필요 | iOS 권한 요구, 일부 Android 센서 미지원 |
| 실폰 신고 전송 | 실폰에서 fake 탐지 후 `현재 위험 신고` 실행 | 신고 성공 TTS/진동, `/admin`에서 이미지/위치/source 확인 | 백엔드 API, ADB reverse tcp:8000 | 수동 검증 필요 | Android의 `localhost:8000` 연결 실패, 업로드 실패 |
| PWA 설치/오프라인 shell | 브라우저 설치 프롬프트 또는 홈 화면 추가 후 재접속, 네트워크 차단 후 shell 확인 | 설치 가능하고 `/`, manifest, icon shell cache가 동작 | service worker, manifest | 수동 검증 필요 | HTTP IP 접속, service worker 등록 실패 |
| 화면 미주시 흐름 | 화면을 계속 보지 않고 TTS/진동만으로 위험과 신고 결과를 판단 | 위험 발생/신고 성공/신고 실패를 음성 또는 진동으로 구분 | 실폰, 조용한 테스트 환경 | 수동 검증 필요 | 안내가 늦거나 길어 보행 판단에 도움 안 됨 |

산출물:

- `docs/neck_worn_phone_test_checklist.md` 형식에 맞춘 실폰 테스트 기록
- 기기명, 브라우저, Android 버전, 접속 방식
- 카메라 각도/TTS/진동/GPS/방향/신고 흐름의 통과 여부
- 실폰에서만 재현되는 이슈와 재현 단계

## 2026-05-16: 모델/STT 연결 준비와 데모 기준 확정

목표: 실제 모델/STT가 아직 연결되지 않은 상태를 유지하면서도, 연결 산출물을 받았을 때 프론트엔드가 무엇을 확인해야 하는지 정리한다.

| 체크 항목 | 수행 방법 | 완료 기준 | 의존성 | 수동 검증 방법 | 막히는 조건 |
| --- | --- | --- | --- | --- | --- |
| detector mode 분기 기준 정리 | `NEXT_PUBLIC_DETECTOR_MODE=fake`와 향후 `server`/`onnx` 모드에서 필요한 UI 상태를 표로 정리 | fake는 데모, server/onnx는 준비 중 또는 실제 source로 명확히 구분 | 모델 담당의 산출물 형식 | 해당 없음 | 실제 모델이 없는데 운영 문구로 보임 |
| `/detect/health` UI 처리 기준 | `model_unavailable`을 오류 팝업이 아닌 모델 연결 대기 상태로 처리하는 기준 작성 | `model_not_configured`, `model_adapter_not_implemented`, `ready`별 화면 문구가 정해짐 | 백엔드 `/detect/health` | 해당 없음 | 상태 코드/응답 형식이 문서와 다름 |
| 서버 추론 연결 전제 확인 | `docs/model_integration_plan.md` 기준으로 `/detect` 응답이 `DetectionEvent[]`를 반환해야 함을 확인 | `source: "server"`, bbox 정규화, class_id/name 일치 조건이 명확함 | 모델/백엔드 담당 | 해당 없음 | 모델 출력 클래스 순서가 `apps/web/types/inference.ts`와 다름 |
| STT 명령 연결 후보 정리 | `voice/server.py`의 `POST /speech/stt` intent를 PWA 동작에 매핑 | `create_report`, `voice_on`, `voice_off`, `repeat_last`의 프론트 동작이 정의됨 | 음성 담당, 로컬 voice server | 수동 검증 필요 | 브라우저 녹음 권한/업로드 지연 미측정 |
| 음성 명령 제외 범위 명시 | `set_destination`, `start_navigation`, `지금 어디야?`는 현재 3일 범위 밖으로 표시 | 목적지/경로 안내가 완성 기능처럼 보이지 않음 | 기획/음성 담당 결정 | 해당 없음 | UI가 미구현 내비게이션을 실제 기능처럼 표현 |
| 관리자 운영 데모 기준 | `/admin`에서 fake 신고를 “데모 탐지”로 보여주고 상태 변경을 시연하는 스크립트 작성 | fake 데이터가 성능 근거가 아니라 통합 테스트 데이터로 설명됨 | 백엔드 신고 데이터 | 브라우저 확인 | fake_source 표시 누락, 실제 신고처럼 오해 |
| 회귀 검증 명령 확정 | 최종 전 `npm run lint`, `npm run typecheck`, 필요 시 `npm run build`를 실행 기준으로 적음 | 실행 명령과 실패 시 조치가 문서화됨 | Node/Next 환경 | 해당 없음 | 빌드 산출물/환경 변수가 로컬마다 다름 |
| 3일 종료 판단 | 통과/미통과/보류를 current status 문서와 맞춰 정리 | 다음 주 모델/STT/실폰 후속 작업으로 넘길 항목이 분리됨 | 각 담당자 결과 공유 | 해당 없음 | 백엔드/모델/STT 결과가 없어 프론트 판단 불가 |

산출물:

- 프론트엔드 모델 연결 준비 체크리스트
- STT intent와 PWA 동작 매핑 초안
- 발표/데모 때 fake detector를 설명하는 문구
- 다음 작업으로 넘길 코드 수정 후보와 의존성 목록

## 완료 기준

2026-05-16 종료 시 아래가 모두 충족되면 3일 프론트엔드 계획을 완료로 본다.

| 기준 | 완료 조건 |
| --- | --- |
| 로컬 품질 | `npm run lint`, `npm run typecheck`가 통과한다. |
| 로컬 통합 | fake 탐지에서 신고 생성, 중복 후보 안내, `/admin` 조회/상태 변경까지 확인된다. |
| 실폰 검증 | 카메라 각도, TTS, 진동, GPS, 방향, 실폰 신고 전송 결과가 `수동 검증 필요` 항목별로 기록된다. |
| 접근성 | 위험 상태, 음성 토글, 신고 버튼, 권한 실패 상태가 화면 미주시 사용을 방해하지 않는지 기록된다. |
| 모델/STT 상태 | 실제 모델/STT가 미연결임을 명확히 표시하고, 연결 전제와 대기 상태가 분리된다. |
| 협업 인계 | 프론트 수정 후보가 모델/백엔드/STT 의존성과 함께 분리되어 다른 담당자 변경을 되돌릴 필요가 없다. |

## 주요 의존성

| 의존성 | 필요한 이유 | 담당/참조 |
| --- | --- | --- |
| FastAPI/PostGIS 로컬 서버 | 신고 생성, 중복 후보, 관리자 목록 검증 | `docs/backend_environment.md`, `docs/api_reference.md` |
| `/detect/health` 계약 | 모델 연결 대기/준비 상태 표시 | `docs/model_integration_plan.md` |
| 실제 YOLO/ONNX 또는 서버 adapter | fake detector 교체 | 모델/백엔드 담당 |
| 로컬 voice server | STT 음성 명령 연결 | `docs/voice_stt_tts_status.md`, `voice/server.py` |
| Android 실폰 | 카메라, 진동, GPS, 방향, PWA 설치 검증 | `docs/neck_worn_phone_test_checklist.md` |
| HTTPS 또는 ADB reverse | 모바일 브라우저 권한과 API 연결 | 개발 환경 |

## 막히는 조건

- 실폰 또는 ADB/HTTPS 접속 수단이 없으면 2026-05-15 항목은 완료가 아니라 `수동 검증 대기`로 남긴다.
- 실제 모델 adapter가 없으면 `source: "server"` 탐지 결과 검증은 하지 않는다.
- 로컬 STT 서버가 준비되지 않으면 음성 명령은 UI 연결 후보만 정리하고 기능 완료로 표시하지 않는다.
- fake detector 결과는 모델 성능, 경보 지연, 실제 보행 안전 검증의 근거로 쓰지 않는다.
- 지자체 민원 API, 지도/경로 안내, MLOps 자동 업로드, 히트맵 대시보드는 이번 3일 프론트엔드 완료 기준에 포함하지 않는다.
