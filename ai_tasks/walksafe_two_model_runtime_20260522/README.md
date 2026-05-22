# WalkSafe two-model runtime AI task handoff

- 기준일: 2026-05-22 KST
- 목적: custom tactile YOLO26s + COCO pretrained YOLO26n v2 runtime 후속 작업을 안전하게 넘기기 위한 안내

## 현재 상태

구현됨:

- CPU-only runtime helper: `model/two_model_runtime.py`
- Runtime config: `configs/walksafe_two_model_runtime_20260522.yaml`
- Backend v2 fake contract: `POST /detect/v2`
- Backend v2 tactile damage report: `POST /reports/v2`
- Frontend modes: `fake-v2`, `server-v2`
- Frontend automatic tactile damage report and voice-priority report flow

아직 미구현/미확정:

- 실제 YOLO26s custom weight adapter 연결
- 실제 YOLO26n COCO inference adapter 연결
- 서버/모바일 배포 방식
- 실제 latency/threshold/field metric
- 외부 tactile_damage_area decision 적용 및 reviewed dataset build

## 먼저 읽을 문서

- `docs/current_status.md`
- `docs/walksafe-v2/README.md`
- `docs/walksafe-v2/backend_api_contract.md`
- `docs/walksafe-v2/two_model_runtime_plan.md`
- `docs/walksafe-v2/auto_report_policy.md`
- `docs/walksafe-v2/coco_inference_policy.md`

## 안전 규칙

- 진행 중인 GPU 학습/평가/추론이 있으면 방해하지 않는다.
- 모델 weight, datasets image/label, `runs/`를 GitHub에 올리지 않는다.
- `model_key`, `source_model`, `model_class_id`를 보존한다.
- v2 class id를 전역 class id로 해석하지 않는다.
- cross-model NMS를 추가하지 않는다.
- COCO/general 객체를 신고 대상으로 저장하지 않는다.
- 자동 신고 완료/실패 TTS를 기본으로 추가하지 않는다. 음성 요청 신고만 짧게 말한다.

## 후속 작업 후보

1. `/detect/v2` real adapter 설계
   - fake contract와 real adapter를 설정으로 전환.
   - 실제 raw detection을 `model.two_model_runtime.Detection`으로 정규화.
2. COCO smoke test
   - allowlist 필터링, threshold, latency 확인.
3. YOLO26s tactile candidate 연결
   - Stage1 candidate path를 로컬 설정으로만 참조.
   - weight를 commit하지 않는다.
4. Risk evaluator 확장
   - bbox tracking, path intersection, depth/IMU/segmentation context 연결.
5. Docs/PR update
   - 실제 adapter 연결 후 `docs/walksafe-v2/`와 PR 설명을 최신화.

## Acceptance criteria

- 기존 v1 `/detect`, `/reports` 계약을 깨지 않는다.
- v2 `/reports/v2`는 tactile damage만 저장한다.
- 일반 객체는 위험 평가 입력일 뿐 신고 저장 대상이 아니다.
- 테스트와 문서가 현재 구현과 일치한다.
