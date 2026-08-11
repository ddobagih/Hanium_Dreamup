# 모델 임시 대체 시스템 관리

> **문서 상태(2026-06-02): legacy v1.** fake/server placeholder 관리 기록이다. 현재 demo/fake 분리 기준은 `docs/current_status.md`와 `docs/walksafe-v2/README.md`다.


작성 기준일: 2026-05-12

## 목적

서버 `.pt` 추론 adapter가 추가된 뒤에도 fake detector와 임시 표시/테스트 경로가 남아 있어, 운영 전 제거 또는 분리할 시스템을 따로 추적한다. 이 문서의 항목은 데모/통합 개발용으로만 사용한다.

## 2026-05-18 상태 보정

- backend `/detect`는 `MODEL_ARTIFACT_PATH`에 `best.pt`를 지정하면 `source: "server"` 탐지 결과를 반환할 수 있다.
- PWA server detector mode headless smoke는 완료됐지만, Android 실폰 field 성능 근거와 browser/ONNX Runtime Web 근거는 아직 없다.
- fake detector는 UI/API/운영 흐름 검증용으로만 유지한다.

## 원칙

- `source: "fake"` 데이터는 모델 성능, 정확도, 지연시간 수치에 사용하지 않는다.
- fake 탐지로 생성한 신고는 API와 UI 통합 확인용이다.
- 실제 모델 연결 시 탐지 이벤트 형식은 `docs/inference_contract.md`를 유지한다.
- 모델이 연결되면 아래 항목별 제거 또는 기본값 변경 여부를 확인한다.

## 임시 시스템 목록

| 항목 | 위치 | 현재 역할 | 교체 조건 |
| --- | --- | --- | --- |
| Fake detector | `apps/web/lib/detector.ts` | 4개 클래스의 `DetectionEvent`를 순환 생성 | ONNX 또는 서버 추론 어댑터가 같은 계약으로 탐지 이벤트를 반환 |
| Fake detector 실행 모드 | `apps/web/app/page.tsx`, `apps/web/.env.example` | `NEXT_PUBLIC_DETECTOR_MODE=fake`일 때 fake 탐지 실행 | 기본값을 `onnx` 또는 `server`로 변경 |
| Fake source 타입 | `apps/web/types/inference.ts`, `backend/app/schemas.py`, `docs/inference_contract.md` | fake/onnx/server 결과를 같은 API로 처리 | 운영/최종 데모에서 fake source 저장을 막거나 별도 test-only 처리 |
| Fake 탐지 UI 문구 | `apps/web/app/page.tsx` | 화면에 `Fake 탐지` 또는 `모델 연결 대기` 표시 | 실제 추론 모드와 모델 상태 문구로 변경 |
| Fake 신고 테스트 데이터 | `backend/tests/test_reports.py` | multipart 신고 API 검증용 이미지/metadata 생성 | API 회귀 테스트로는 유지 가능하되, 성능/모델 검증과 분리 |
| 서버 추론 unavailable 경로 | `backend/app/detector.py`, `backend/app/main.py` | 모델 env 미설정 또는 로드 실패 시 `/detect`에 `model_unavailable` 반환 | `.pt` ready와 unavailable 양쪽 회귀를 유지 |

## fake 제거/운영 전 체크리스트

1. `apps/web/lib/detector.ts`를 실제 detector adapter로 대체하거나 `onnx-detector.ts`를 추가한다.
2. `NEXT_PUBLIC_DETECTOR_MODE` 기본값을 `fake`에서 실제 모드로 바꾼다.
3. PWA 화면에서 `Fake 탐지` 문구가 보이지 않는지 확인한다.
4. 신고 API에 저장되는 `source`가 `onnx` 또는 `server`인지 확인한다.
5. 기존 fake 신고 데이터는 성능 집계에서 제외한다.
6. `docs/inference_contract.md`의 필드가 실제 모델 출력과 맞는지 다시 검증한다.
7. `/detect/health ready`, `/detect` 200, `source: "server"` 저장을 실제 배포 env에서 재검증한다.

## 현재 허용되는 사용

- PWA 카메라 권한, 오버레이, TTS, 신고 버튼 동작 확인
- FastAPI multipart 업로드와 PostGIS 저장 흐름 확인
- 프론트/백엔드 계약 변경이 필요한지 빠르게 검토

## 현재 금지되는 사용

- 모델 성능 수치 산출
- 실제 보행 안전 판단
- 최종 보고서의 정확도, 지연시간, 위험 감지 결과 근거로 사용
- 실제 사용자 대상 현장 테스트
