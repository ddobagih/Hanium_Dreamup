# Pre-Model Backend TODO

작성 기준일: 2026-05-12

## 목적

실제 YOLO/ONNX 모델이 들어오기 전까지 백엔드에서 유지할 것과, 모델 산출물 확보 후 바꿀 것을 구분한다.

## 지금 유지할 것

| 항목 | 이유 |
| --- | --- |
| `docs/inference_contract.md`의 탐지 이벤트 계약 | 프론트엔드와 백엔드 공통 계약 |
| `class_id`와 `class_name` 순서 | 데이터셋, API, 모델 출력이 함께 의존 |
| `/reports` multipart 계약 | 외부 프론트엔드 연동 기준 |
| `/detect/health` | 모델 상태 확인 진입점 |
| `/detect`의 multipart 계약 | 서버 추론 교체 지점 |
| `source` 값 `fake`, `onnx`, `server` | 모델 전후 데이터를 구분하기 위해 필요 |

## 모델 전 placeholder

| 항목 | 위치 | 현재 동작 |
| --- | --- | --- |
| 서버 추론 | `backend/app/detector.py` | 모델 없음 또는 adapter 미구현 상태 반환 |
| 서버 추론 API | `backend/app/main.py` | 이미지 검증 후 `model_unavailable` 반환 |
| fake source 허용 | `backend/app/schemas.py` | fake 신고 저장 허용 |
| 임시 시스템 문서 | `docs/model_placeholder_systems.md` | 제거 대상 추적 |

## 모델 준비 후 변경 순서

1. 모델 파일 위치를 정하고 `MODEL_ARTIFACT_PATH`에 절대 경로를 넣는다.
2. `backend/app/detector.py`에 모델 로딩 코드를 추가한다.
3. `detect_health()`가 로딩 성공 시 `ready`를 반환하게 한다.
4. `run_detection()`이 `DetectResponse`를 반환하게 한다.
5. 탐지 결과가 `ReportMetadata` 검증을 통과하는지 확인한다.
6. 프론트엔드가 `/detect` 결과로 `/reports`를 생성할 수 있는지 확인한다.
7. `docs/model_placeholder_systems.md`에서 완료된 placeholder를 정리한다.

## 바꾸면 안 되는 것

모델 연결 과정에서 다음 계약은 유지한다.

- `bbox`는 원본 카메라 프레임 기준 정규화 좌표다.
- `confidence`는 `0..1` 범위다.
- 서버 추론 결과의 `source`는 `server`다.
- 신고 이미지는 계속 multipart `image` part로 보낸다.
- 신고 metadata는 계속 JSON 문자열 `metadata` part로 보낸다.
- 위치가 없으면 `gps: null` 또는 필드 생략이 아니라 현재 스키마 기준 `gps` null 처리를 유지한다.

## 아직 하지 않을 것

| 항목 | 보류 이유 |
| --- | --- |
| 테스트 스크립트 자동화 | 현재 사용자가 이르다고 판단 |
| 성능 측정 자동화 | 실제 모델과 데이터셋 확보 후 의미 있음 |
| 배포 구성 | 로컬 병렬 개발 단계 |
| 외부 스토리지 연동 | 현재는 `backend/uploads` 로컬 저장 기준 |
| 로그인/권한 관리 | 1차 API 계약 밖 |

## 모델 준비 전 백엔드에서 할 수 있는 점검

- API 계약 문서가 외부 프론트엔드와 맞는지 확인한다.
- CORS origin이 외부 프론트엔드 개발 포트와 맞는지 확인한다.
- 이미지 업로드 오류 메시지를 프론트엔드가 처리할 수 있는지 확인한다.
- fake 신고가 성능 집계에 섞이지 않도록 운영 문서 기준을 공유한다.
