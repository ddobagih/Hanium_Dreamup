# WalkSafe Assist 현재 상태

- 기준일: 2026-05-22 KST
- 범위: 현재 Git 브랜치의 앱/백엔드/model helper/문서 상태 요약

## 한 줄 요약

WalkSafe Assist는 현재 **Next.js PWA + FastAPI/PostGIS 신고 흐름**과 **two-model v2 fake contract**가 구현되어 있다. v2는 타일/점자블록 손상 자동 신고와 일반 객체 경고 정책을 분리했지만, `/detect/v2`의 실제 YOLO26s/COCO 추론 연결은 아직 후속 작업이다.

## 구현된 주요 흐름

| 영역 | 현재 상태 | 남은 일 |
|---|---|---|
| Frontend PWA | `apps/web/app/_walksafe/`로 UI, 카메라, 센서, 탐지, 음성, 신고, 위험 피드백 hook 분리 | 실폰/TalkBack/목걸이 착용 현장 검증 |
| Detector mode | `fake`, `server`, `fake-v2`, `server-v2` 지원 | v2 실제 모델 adapter와 지연시간 측정 |
| Backend v1 | `/detect`, `/reports`, `/reports/duplicate-check`, admin 조회/상태 변경 유지 | 운영 인증/배포/스토리지 보강 |
| Backend v2 | `/detect/v2`, `/reports/v2` 구현. v2 신고는 custom tactile damage class만 저장 | 실제 YOLO26s/COCO adapter 연결, v2 query/filter 확장 |
| Model runtime | `model/two_model_runtime.py` CPU-only filtering/merge helper와 config/test 구현 | 실제 추론 결과를 helper에 연결하고 threshold 검증 |
| Risk policy | tactile damage는 report-only, general object는 tracking/path/depth context가 위험을 표시할 때만 alert | 접근/충돌 예측 입력 연결 및 실영상 검증 |
| Voice | `create_report`, 상태 반복, 위치 확인, 목적지/길 안내 준비 intent 흐름 | 실폰 마이크 E2E, TTS 품질/캐시/폴백 검증 |

## v2 detection/report 계약

- `POST /detect/v2`
  - 현재 fake contract 응답을 반환한다.
  - 응답 schema: `schema_version: "detect.v2"`, `model_key`, `source_model`, `model_class_id`, `class_name`, `category`, `confidence`, `bbox`, `threshold_used`, `captured_at`, optional `gps`, `heading`.
  - `model_key`: `custom_tactile` 또는 `coco_general`.
- `POST /reports/v2`
  - `custom_tactile`의 `tactile_damage_area`, `damaged_tactile_block`만 허용한다.
  - `normal_tactile_block`과 COCO/general 객체는 422로 거부한다.
  - 기존 `reports` 테이블을 재사용하고 원본 v2 payload를 JSON에 보존한다.

## 사용자 안내 정책

- 자동 신고: 타일/점자블록 손상 감지 시 조용히 저장한다. 자동 신고 완료/실패 TTS는 기본 생략한다.
- 음성 요청 신고: 사용자가 명시 요청한 신고는 자동 신고보다 우선하며 완료/실패를 짧게 TTS로 말한다.
- 일반 객체: 신고 대상이 아니다. 충돌 가능 접근, 경로 차단 등 보행 위험일 때만 경고한다.
- 정상 점자블록: 경고/신고 대상이 아니다.

## 모델/데이터 상태

- custom tactile 후보: YOLO26s 3-class
  - `normal_tactile_block`
  - `damaged_tactile_block`
  - `tactile_damage_area`
- 2026-05-22 검토 기준 Stage1 `best.pt`를 현재 MVP 후보로 둔다. 단, 모델 weight와 run 산출물은 GitHub에 올리지 않는다.
- `tactile_damage_area` 120-row 외부 검수 패키지는 GitHub 공유용으로 `ai_tasks/walksafe_tactile_damage_area_review_20260522/`에 정리되어 있다.
- 외부 검수 CSV는 존재하지만, 아직 최종 label decision 적용과 reviewed dataset build는 완료되지 않았다.

## 문서 기준

- 전체 문서 지도: `docs/README.md`
- v2 source of truth: `docs/walksafe-v2/README.md`
- AI 작업 패키지: `ai_tasks/README.md`
- `docs/execution/`은 과거 실행 기록 성격이 강하며, 최신 구현 기준은 위 문서들을 우선한다.
