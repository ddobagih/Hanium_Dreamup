# 2026-05-26 기능별 추가 구현 필요 리스트

## 기준

- 기준 문서: `plans/features/2026-05-25_additional_feature_20_plan.md`
- 기준 상태: 현재 working tree에 보이는 구현 상태. 커밋 기준이 아니라 현재 체크아웃 기준이다.
- 목표: 이미 완료된 safe slice 이후에도 10월 결과물/실제 사용 품질을 높이기 위해 추가 구현할 수 있는 항목을 기능별로 최대한 세분화한다.
- 제외: 실폰 field 검증, TalkBack 실기기 검증, 배포, secret/계정 작업, 외부 기관 실제 전송, 실제 긴급 전화/SMS 발신, 외부 LLM/API 호출, 장시간 학습, 반복 live TMAP 호출.
- 포함: 로컬/mock/static/dry-run으로 먼저 닫을 수 있는 코드, 테스트, 문서, evidence, 안전 gate.

## 팀 구성

| 팀 | 감사 범위 | 결과 반영 |
|---|---|---|
| Navigation/Voice 팀 | #1~#5, #16~#20 | 목적지 검색, 후보 선택, 길안내 시작/중지, 재탐색, 음성 intent, TTS cache |
| Risk/Detection/Model 팀 | #7~#12, #19 | 거리/방향/행동 문구, ROI, bbox 접근 판단, Stage1 trace/smoke |
| Admin/Ops/Reports 팀 | #12~#16 | report 저장/export, GeoJSON, fake/demo 분리, 검수 workflow, 제출 준비 |
| PWA/Settings/Accessibility/Data 팀 | #17~#18 및 공통 | PWA/offline, 설정, 접근성, 개인정보/보존, evidence |

## 우선순위 표기

- P0: 지금 바로 다음 구현 후보. 안전/개인정보/핵심 UX/검증 근거에 직접 영향.
- P1: 제품 완성도 강화. MVP 후반에 반드시 정리할 가치가 큼.
- P2: 구조 확장/운영 고도화. 구현은 가능하지만 범위가 커서 뒤로 미룰 수 있음.
- C: 외부 계정/비용/삭제/배포/실제 전송 등 별도 승인 필요. 여기서는 구현 대상이 아니라 gate/문서만 둔다.

## 기능별 추가 구현 필요 항목

### #1 목적지 이름 → 좌표 검색/geocoding

- [P0] `GET /navigation/destinations/search` 계약 문서 최신화 / 파일: `docs/walksafe-v2/backend_api_contract.md`, `docs/api_reference.md` / 검증: 코드 route와 문서 endpoint 대조.
- [P0] 목적지 검색 전용 health endpoint 추가 / 파일: `backend/app/api/navigation.py`, `backend/tests/test_navigation_routes.py` / 검증: TMAP key missing/ready ASGI test.
- [P0] TMAP POI mock/local provider 추가 / 파일: `backend/app/services/tmap_pedestrian.py`, `backend/app/config.py` / 검증: 외부 호출 없는 mock provider pytest.
- [P0] 목적지 검색 요청 throttle/debounce/cancel / 파일: `apps/web/app/_walksafe/hooks/useNavigationGuidance.ts` / 검증: 같은 query 반복 시 fetch 1회.
- [P0] 검색 실패 원인별 UI/TTS 메시지 분리 / 파일: `apps/web/lib/navigation-api.ts`, `AssistPanel.tsx` / 검증: key missing, timeout, no result fixture.
- [P1] 검색 query 정규화 강화 / 파일: `voice/intents.py`, `useNavigationGuidance.ts` / 검증: 공백, 조사, “으로/로”, “안내해줘” intent test.
- [P1] current GPS 기반 후보 거리/정렬 계약 / 파일: `backend/app/schemas.py`, `apps/web/types/navigation.ts` / 검증: origin 포함 fixture에서 가까운 후보 우선.
- [P1] POI와 주소/geocoding 타입 분리 / 파일: `backend/app/schemas.py`, `navigation-destination.ts` / 검증: provider/type별 candidate label.
- [P1] 후보 좌표 정밀도/범위 검증 / 파일: `backend/app/services/tmap_pedestrian.py` / 검증: 위도/경도 invalid response 실패.
- [P1] 목적지 검색 schema smoke script / 파일: `scripts/check_navigation_destination_search_policy_20260526.py` / 검증: mock fixture PASS, live 호출 없음.
- [P2] 검색 결과 캐시 TTL / 파일: `backend/app/services/tmap_pedestrian.py` 또는 frontend hook / 검증: TTL 내 같은 query cache hit.
- [P2] 목적지 별칭 사전 / 파일: `configs/navigation_destination_aliases.json` / 검증: “집/학교/회사” 로컬 alias fixture.

### #2 TMAP 검색 후보 선택

- [P0] 후보 선택 후 voice state와 실제 selected destination 동기화 / 파일: `useVoiceCommands.ts`, `useNavigationGuidance.ts` / 검증: 후보 선택 뒤 voice summary가 선택 후보명과 일치.
- [P0] 후보 범위 초과/후보 없음 음성 선택 거절 / 파일: `useVoiceCommands.ts`, `navigation-destination.ts` / 검증: “5번 선택”이 3개 후보에서 재질문.
- [P0] 후보 목록 접근성 보강 / 파일: `AssistPanel.tsx`, `globals.css` / 검증: 후보 list role, aria-live, focus/static test.
- [P1] 동명 후보 disambiguation 문구 개선 / 파일: `navigation-destination.ts` / 검증: 같은 이름/다른 주소 fixture.
- [P1] 후보 음성 표현 확장 / 파일: `voice/intents.py`, `tests/test_voice_intents.py` / 검증: “일번”, “첫번”, “두번 골라줘”, “세 번째” test.
- [P1] 후보 표시 개수/더 보기 정책 / 파일: `navigation-destination.ts`, `AssistPanel.tsx` / 검증: 3개 초과 결과에서 더 있음 문구.
- [P1] 선택 취소/다시 검색 명령 / 파일: `voice/intents.py`, `useNavigationGuidance.ts` / 검증: “다시 검색”, “취소” intent.
- [P2] 후보 번호 TTS 반복 명령 / 파일: `useVoiceCommands.ts`, `useNavigationGuidance.ts` / 검증: “후보 다시 말해줘” fixture.

### #3 목적지 설정 후 길안내 자동 시작 정책

- [P0] “서울역으로 안내해줘/가자” 자연어 처리 보강 / 파일: `voice/intents.py` / 검증: set_destination + requested_start slot test.
- [P0] 목적지 선택만으로 route request 금지 회귀 테스트 / 파일: `useNavigationGuidance.ts`, frontend policy test / 검증: `/navigation/walking` fetch 0회.
- [P0] start prerequisite matrix / 파일: `useNavigationGuidance.ts` / 검증: GPS 없음, 목적지 없음, 후보 미선택, 검색 실패별 메시지.
- [P0] 길안내 중지 UI/음성 intent 연결 / 파일: `AssistPanel.tsx`, `voice/intents.py`, `useNavigationGuidance.ts` / 검증: “길안내 중지” test.
- [P1] route start 성공 TTS 중복 방지 / 파일: `voice-priority.ts`, `navigation-guidance-policy.test.ts` / 검증: risk active/no-risk fixture.
- [P1] route request in-flight/cooldown UI 표시 / 파일: `useNavigationGuidance.ts`, `AssistPanel.tsx` / 검증: 연속 시작 명령에서 cooldown 안내.
- [P1] route health unavailable 상태 분리 / 파일: `navigation-api.ts`, `AssistPanel.tsx` / 검증: key missing/5xx/timeout fixture.
- [P1] 기존 route 교체 확인 prompt / 파일: `useNavigationGuidance.ts` / 검증: 안내 중 새 목적지 설정 시 기존 route 유지/교체 상태.
- [P2] route TTL 및 마지막 route local cache / 파일: `apps/web/lib/navigation-cache.ts` / 검증: TTL 만료 전/후 fixture.

### #4 경로 이탈 감지

- [P0] off-route threshold 설정화 / 파일: `route-progress.ts`, `config.ts` / 검증: threshold별 route-progress test.
- [P0] GPS accuracy edge case 보강 / 파일: `route-progress-policy.test.ts` / 검증: accuracy가 threshold보다 나쁠 때 unknown/candidate.
- [P0] route projection edge fixture 추가 / 파일: `route-progress-policy.test.ts` / 검증: 빈 route, 단일점, 중복점, 짧은 segment.
- [P1] 도착/route completed 상태 추가 / 파일: `useNavigationGuidance.ts` / 검증: 목적지 반경 이내에서 도착 안내 후 종료.
- [P1] 진행률/남은 거리 UI 보강 / 파일: `AssistPanel.tsx` / 검증: routeProgress summary DOM test.
- [P1] hysteresis 및 sample buffer 파라미터화 / 파일: `route-progress.ts` / 검증: 1회 GPS 튐은 off_route 금지.
- [P1] wrong-way/역방향 이동 감지 / 파일: `route-progress.ts` / 검증: 경로에서 멀어지며 progress 감소 fixture.
- [P2] 경로 재설정 시 history reset / 파일: `useNavigationGuidance.ts` / 검증: 이전 route 이탈 상태가 새 route에 남지 않음.
- [P2] offline route progress continuation / 파일: `navigation-cache.ts`, `useNavigationGuidance.ts` / 검증: route cache만으로 progress 계산.

### #5 재탐색 상태/음성 안내

- [P0] rerouting 문구와 실제 동작 일치 / 파일: `useNavigationGuidance.ts`, docs / 검증: 자동 재요청 전이면 “재탐색이 필요합니다”로 구분.
- [P0] “재탐색” 전용 intent/action 분리 / 파일: `voice/intents.py`, `useNavigationGuidance.ts` / 검증: start_navigation과 reroute 명령 구분.
- [P0] reroute gate를 정규 검증에 편입 / 파일: `scripts/check_navigation_reroute_gate_20260525.py`, docs / 검증: route request가 start/reroute 함수 밖에서 호출되지 않음.
- [P0] 위험 active일 때 reroute TTS 억제 회귀 / 파일: `voice-priority.ts`, `navigation-guidance-policy.test.ts` / 검증: 위험 안내가 우선.
- [P1] reroute cooldown 남은 시간 UI / 파일: `useNavigationGuidance.ts`, `AssistPanel.tsx` / 검증: cooldown 중 재요청 차단 문구.
- [P1] reroute 실패 시 기존 route 유지 정책 / 파일: `useNavigationGuidance.ts` / 검증: 실패 후 route null로 날아가지 않음.
- [P1] 최대 재탐색 횟수/시간 제한 / 파일: `config.ts`, `useNavigationGuidance.ts` / 검증: 반복 이탈 fixture에서 loop 차단.
- [P2] “현재 경로 유지/재탐색 취소” 명령 / 파일: `voice/intents.py` / 검증: intent + state fixture.
- [C] 실제 live TMAP 자동 재요청 연결 / 조건: 사용자 승인, quota/cooldown/중복 방지 확인 후.

### #6 보폭 자동 측정

- [P0] localStorage read/write 예외 처리 / 파일: `useWalkSafeSettings.ts`, `useAutoStepLength.ts` / 검증: localStorage throwing mock.
- [P0] 자동 보폭 추정 confidence 표시 / 파일: `step-length.ts`, `AssistPanel.tsx` / 검증: collecting/estimated/fallback 상태 test.
- [P0] DeviceMotion permission 상태 표시 / 파일: `useAutoStepLength.ts`, `AssistPanel.tsx` / 검증: permission denied/unavailable fixture.
- [P1] outlier 제거/최소 샘플 수 설정화 / 파일: `step-length.ts` / 검증: 튄 GPS 거리와 step count fixture.
- [P1] 보폭 재보정/초기화 버튼 / 파일: `AssistPanel.tsx`, `useAutoStepLength.ts` / 검증: reset 후 collecting 상태.
- [P1] 보폭 추정값 persistence와 TTL / 파일: `useWalkSafeSettings.ts`, `step-length.ts` / 검증: 오래된 estimate는 fallback.
- [P2] 보행 속도와 보폭 동시 산출 / 파일: `useNavigationGuidance.ts`, `step-length.ts` / 검증: speed source priority fixture.
- [P2] 보폭 추정 근거 요약 export/evidence / 파일: `docs/evidence/feature_matrix.md` / 검증: static document.

### #7 실제 거리/깊이 기반 “약 N보 앞” 위험 안내

- [P0] `detect.v2` API 문서에 `distance_m` 명시 / 파일: `backend_api_contract.md`, `api_reference.md` / 검증: schema와 문서 대조.
- [P0] `distance_m` 상한/현실성 검증 / 파일: `backend/app/schemas.py`, `apps/web/lib/detect-api-v2.ts` / 검증: 음수, NaN, 0, 과대값 parser/schema test.
- [P0] 거리 출처/신뢰도 필드 추가 / 파일: `backend/app/schemas.py`, `apps/web/types/inference-v2.ts` / 검증: source/confidence 없는 값은 TTS 보폭 문구 제외.
- [P0] 거리 없음 시 보폭 문구 금지 테스트 / 파일: `risk-guidance.ts`, `risk-evaluator-policy.test.ts` / 검증: bbox 크기만으로 “약 N보” 생성 안 함.
- [P1] 매우 가까운 거리 문구 정책 / 파일: `risk-guidance.ts` / 검증: 0.3m 이하 “바로 앞” 등 fixture.
- [P1] `model.two_model_runtime.Detection`에 optional distance 보존 / 파일: `model/two_model_runtime.py`, `detect_v2.py` / 검증: runtime dict→API response 전달.
- [P1] UI 위험 상태에도 거리 문구 표시 / 파일: `useRiskFeedback.ts`, `AssistPanel.tsx` / 검증: riskText에 “약 N보 앞”.
- [P1] distance source별 표시 정책 / 파일: `frontend_display_policy.md`, `risk-guidance.ts` / 검증: `sensor_depth`, `manual_fixture`, `unknown` 별 fixture.
- [P2] depth provider interface만 추가 / 파일: `backend/app/services/depth_provider.py` / 검증: default disabled returns no distance.
- [P2] time-to-collision 대체 안내 / 파일: `risk-evaluator.ts` / 검증: 거리 없이 접근 판단은 “다가옵니다”로 안내.
- [C] 실제 depth 센서/ARCore/WebXR 연결 / 조건: 실기기/권한/API 확인 후.

### #8 위험 객체 방향 안내

- [P0] 방향 경계값 문서화 및 fixture / 파일: `risk-guidance.ts`, `frontend_display_policy.md` / 검증: 0.39/0.40/0.60/0.61.
- [P0] invalid/overflow bbox 처리 테스트 / 파일: `detect-api-v2.ts`, `risk-guidance.ts` / 검증: x+width>1, NaN, 음수 fixture.
- [P1] 발밑/하단/상단 표현 분리 / 파일: `risk-guidance.ts`, `risk-roi.ts` / 검증: lower-half bbox “발밑/전방 하단”.
- [P1] 여러 alertable 객체 방향 우선순위 / 파일: `useRiskFeedback.ts`, `two-model-priority.ts` / 검증: 3개 이상 detection fixture.
- [P1] overlay label과 TTS 방향 일관성 / 파일: `CameraSurface.tsx`, tests / 검증: bbox 위치와 label/message 일치.
- [P2] 카메라 orientation/미러링 설정 / 파일: `CameraSurface.tsx`, `config.ts` / 검증: back/front camera direction policy.
- [P2] heading 기반 “왼쪽 앞/오른쪽 앞” 보정 / 파일: `risk-guidance.ts` / 검증: heading fixture.
- [P2] 방향별 진동 pattern / 파일: `feedback.ts` / 검증: left/right/front vibration pattern test.

### #9 위험 행동 안내

- [P0] risk type별 action matrix 문서화 / 파일: `risk-guidance.ts`, `frontend_display_policy.md` / 검증: action table fixture.
- [P0] `path_obstacle` action 회귀 테스트 / 파일: `risk-evaluator-policy.test.ts` / 검증: path_obstacle expected phrase.
- [P0] 손상 점자블록 report-only와 사용자 경고 분리 고정 / 파일: `risk-evaluator-policy.test.ts` / 검증: damage는 자동 신고, 불필요한 TTS action 없음.
- [P1] class/category별 세분화 / 파일: `risk-guidance.ts`, `risk-evaluator.ts` / 검증: 사람/차량/자전거/킥보드/벤치별 phrase.
- [P1] risk_level 반영 / 파일: `risk-guidance.ts` / 검증: high는 “멈추세요”, medium은 “천천히 피하세요” 등.
- [P1] 짧은 한국어 phrase catalog / 파일: `apps/web/app/_walksafe/risk-phrases.ts` / 검증: snapshot fixture.
- [P1] 반복 경고 쿨다운 per risk/action / 파일: `useRiskFeedback.ts` / 검증: 같은 위험 반복 억제, 새 위험은 안내.
- [P2] 사용자 설정별 안내 상세도 / 파일: `useWalkSafeSettings.ts`, `risk-guidance.ts` / 검증: 짧게/표준 모드 fixture.

### #10 IMU/ROI 기반 충돌 가능 영역 필터

- [P0] ROI helper runtime 연결 여부를 debug gate로 분리 / 파일: `useTwoModelRiskHistory.ts`, `risk-roi.ts` / 검증: debug off이면 TTS 영향 없음.
- [P0] heading/speed unknown 시 ROI unknown 처리 / 파일: `risk-roi.ts` / 검증: 센서 없음에서 on_path 과대판정 금지.
- [P1] heading/speed/horizon 기반 motion ROI helper / 파일: `motion-roi.ts` / 검증: 3초/5초 horizon fixture.
- [P1] `apps/web/scripts` 또는 `scripts` ROI fixture runner / 파일: `scripts/check_frontend_motion_roi_policy_20260526.sh` / 검증: node/ts fixture PASS.
- [P1] 비차단 context class 제외 / 파일: `risk-roi.ts` / 검증: traffic light 등 비차단 객체 제외.
- [P1] ROI threshold 설정값 문서화 / 파일: `frontend_display_policy.md` / 검증: docs/code 상수 일치.
- [P1] lower-half/center-band 설정화 / 파일: `risk-roi.ts`, `config.ts` / 검증: center band 변경 fixture.
- [P2] route bearing과 camera heading 결합 / 파일: `useNavigationGuidance.ts`, `risk-roi.ts` / 검증: route 방향 기준 ROI.
- [P2] sensor sample local logging schema / 파일: `docs/sensor_log_schema.md` / 검증: schema static.
- [C] 실제 DeviceMotion field calibration / 조건: 실기기 검증 범위 재개 시.

### #11 bbox 변화 기반 접근 중 객체 판단

- [P0] 객체 단위 tracking key / 파일: `risk-evaluator.ts`, `useTwoModelRiskHistory.ts` / 검증: 같은 class 2객체 교차 fixture.
- [P0] 모든 detections history 저장 / 파일: `useTwoModelRiskHistory.ts` / 검증: primary/secondary 밖 3번째 객체 접근 감지.
- [P0] stale history reset 검증 / 파일: `risk-evaluator-policy.test.ts` / 검증: max_history_ms 초과 시 접근 false.
- [P1] camera motion/jitter 억제 / 파일: `risk-evaluator.ts` / 검증: 흔들림/zoom-like bbox 증가 negative fixture.
- [P1] TTC threshold 경계값 fixture / 파일: `risk-evaluator-policy.test.ts` / 검증: 1800/3500ms 경계.
- [P1] alert cooldown key에 객체 위치/risk 반영 / 파일: `useRiskFeedback.ts` / 검증: 같은 class 다른 위치는 별도 경고.
- [P1] bbox 감소/정지 negative 테스트 확장 / 파일: `risk-evaluator-policy.test.ts` / 검증: constant/decreasing area.
- [P2] IoU 기반 단순 tracker / 파일: `risk-tracker.ts` / 검증: frame 간 객체 matching fixture.
- [P2] 접근 중이지만 ROI 밖인 객체 억제 / 파일: `risk-evaluator.ts`, `risk-roi.ts` / 검증: approaching + off_path false.

### #12 Stage1 detector payload → report → export trace

- [P0] 실제 Stage1 이미지 → `/detect/v2` → `/reports/v2` → export trace mode / 파일: `check_detect_report_export_trace_20260524.py` / 검증: positive fixture report id가 CSV/JSON/GeoJSON 포함.
- [P0] trace에서 `source=server` 필수 옵션 / 파일: trace script / 검증: fake payload는 `--require-server-source` 실패.
- [P0] export deep field 대조 / 파일: trace script, `test_reports_v2.py` / 검증: model_key, source_model, threshold, bbox, gps, heading, distance_m.
- [P0] dry-run도 Pydantic/report policy 검증 / 파일: trace script / 검증: bad payload dry-run 실패.
- [P0] reject 대상 확대 / 파일: `backend/tests/test_reports_v2.py` / 검증: normal_tactile_block, tactile_damage_area, unknown, COCO 422.
- [P0] trace 결과 JSON/Markdown artifact 출력 / 파일: trace script / 검증: report id, payload hash, export URLs 기록.
- [P1] report trace id 추가 / 파일: `types/inference-v2.ts`, backend schemas/reports / 검증: detect→report→export trace id 유지.
- [P1] image/payload SHA-256 저장 / 파일: `reports.py`, serialization / 검증: payload hash export 포함.
- [P1] `/detect/v2/health`에 threshold/source 요약 / 파일: `detect_v2.py` / 검증: damaged threshold=0.50 확인.
- [P1] Stage1 positive/negative smoke 의미 분리 / 파일: smoke script/docs / 검증: negative PASS가 target PASS로 표시되지 않음.
- [P1] read-only smoke mode / 파일: `check_detect_v2_stage1_image_smoke_20260524.py` / 검증: `--no-write` stdout-only.
- [P1] trace run isolation / 파일: trace script/docs / 검증: disposable DB 또는 run_id filter.
- [P1] duplicate candidates list/detail/export 보존 / 파일: `duplicates.py`, `report_serialization.py` / 검증: 중복 2건 생성 후 export에 duplicate info.
- [P2] custom-only Stage1 smoke support / 파일: `detect_v2.py`, smoke script / 검증: COCO missing 시 명확한 BLOCKED.
- [P2] Stage1 threshold FP/FN review pack / 파일: `data_sources/manifests/*`, docs / 검증: review queue manifest.

### #13 Admin GeoJSON export UI

- [P0] Admin export 링크 DOM 회귀 테스트 / 파일: `apps/web/tests/admin-export-ui.test.ts` / 검증: CSV/JSON/GeoJSON href에 필터 유지.
- [P0] JSON export attachment filename / 파일: `backend/app/api/reports.py` / 검증: `Content-Disposition: walksafe-reports.json`.
- [P0] export `Cache-Control: no-store` / 파일: `reports.py` / 검증: header test.
- [P1] GeoJSON properties 확장 / 파일: `reports.py` / 검증: location_quality, accuracy_m, review_flags, source_model 포함.
- [P1] “기관 제출 후보 export” preset / 파일: `admin/page.tsx`, `report-api.ts` / 검증: reviewed + exclude_fake + custom_tactile query.
- [P1] fake 포함 export 경고 강화 / 파일: `admin/page.tsx` / 검증: fake 포함 상태에서 버튼 옆 경고.
- [P1] 빈 결과 export 사전 안내 / 파일: `admin/page.tsx` / 검증: visible count 0 안내.
- [P1] CSV Excel BOM 옵션 / 파일: `reports.py` / 검증: `format=csv&bom=true`.
- [P1] CSV injection 방어 / 파일: `reports.py` / 검증: `=`, `+`, `-`, `@` 시작 문자열 escape.
- [P1] redacted/public export mode / 파일: `reports.py`, admin UI / 검증: 좌표 rounding, image_path 제외.
- [P2] export manifest JSON / 파일: `reports.py` / 검증: 필터, 건수, checksum, 생성시각.
- [P2] cluster aggregate GeoJSON export / 파일: `reports.py` / 검증: `aggregate=grid` FeatureCollection.

### #14 Admin 신고 지도/히트맵/클러스터 뷰

- [P0] backend cluster/summary endpoint / 파일: `reports.py`, `schemas.py` / 검증: list limit 50과 무관하게 전체 cluster count.
- [P0] 현재 frontend summary가 목록 limit 기반임을 문서/문구로 명시 / 파일: `admin/page.tsx`, docs / 검증: 문구 확인.
- [P1] backend summary로 Admin 전환 / 파일: `report-api.ts`, `admin/page.tsx` / 검증: 100건 fixture에서 전체 count.
- [P1] cluster 클릭 → 반경 필터 적용 / 파일: `admin/page.tsx` / 검증: cluster 클릭 시 lat/lng/radius_m query.
- [P1] 외부 SDK 없는 SVG/grid map / 파일: `admin/page.tsx`, `globals.css` / 검증: grid cell DOM test.
- [P1] status/source/fake별 cluster breakdown / 파일: backend summary, frontend summary / 검증: cluster별 new/reviewed/resolved/server/fake count.
- [P1] 위치 없음 review queue / 파일: `admin/page.tsx` / 검증: missing location filter.
- [P1] 좌표 rounding/privacy mode / 파일: `reports.py`, admin UI / 검증: 공개 모드 좌표 정밀도 낮춤.
- [P2] PostGIS grid aggregation / 파일: `backend/app/services/report_summary.py` / 검증: ST_SnapToGrid fixture.
- [P2] cluster time window filter / 파일: `reports.py`, admin UI / 검증: 최근 7일/30일 cluster count.

### #15 fake/demo 신고 데이터 운영 분리

- [P0] backend/frontend fake 판정 로직 통합 / 파일: `report_policy.py`, `report-api.ts` / 검증: 같은 fixture 동일 판정.
- [P0] row/detail 단위 fake/demo badge / 파일: `admin/page.tsx` / 검증: 개별 행에서 demo badge.
- [P0] demo_filter와 source 필터 상호작용 테스트 / 파일: `admin-report-summary-policy.test.ts` / 검증: source/server/fake/demo_filter 조합.
- [P1] `review_flags`에 metadata fake 반영 / 파일: `report_serialization.py` / 검증: metadata fake fixture에 fake_source.
- [P1] `data_origin`/`runtime_mode` 명시 필드 / 파일: backend schemas, frontend types / 검증: source 문자열 휴리스틱 없이 필터.
- [P1] export에 `performance_excluded` 필드 / 파일: `reports.py` / 검증: fake row excluded reason.
- [P1] fake/demo export filename 표기 / 파일: `reports.py` / 검증: `demo-included` filename/header.
- [P1] fake-v2 저장 허용/차단 정책 명시 / 파일: `docs/report_operations.md`, policy tests / 검증: 정책 맞게 201/422.
- [P2] 모델 성능 리포트 fake 자동 제외 / 파일: scripts/model report / 검증: fake fixture가 metric 집계 제외.
- [P2] demo seed data 생성/삭제 없는 초기화 script / 파일: `scripts/seed_demo_reports.py` / 검증: dry-run만 기본, 실제 저장은 승인 옵션.

### #16 신고 검수 상태 workflow 강화

- [P0] 상태 변경 이력/audit trail / 파일: backend models/schemas/reports / 검증: status 변경 2회 후 history 조회.
- [P0] review note/resolution reason / 파일: schemas, admin UI / 검증: resolved 변경 시 reason 저장.
- [P0] 상태 전이 guard / 파일: `report_policy.py` / 검증: 허용/비허용 transition matrix.
- [P0] status conflict 방지 / 파일: `reports.py` / 검증: `updated_at` 조건부 PATCH 충돌.
- [P1] reopen workflow / 파일: backend API, admin UI / 검증: resolved→reviewed reopen reason.
- [P1] bulk status update / 파일: `reports.py`, `admin/page.tsx` / 검증: 선택 N건 PATCH.
- [P1] duplicate merge/mark workflow / 파일: models, duplicates service, admin UI / 검증: duplicate_of export/list 반영.
- [P1] review readiness 계산 / 파일: serialization, admin UI / 검증: 사진/위치/fake/신뢰도/중복 checklist.
- [P1] 담당자/queue 필터 / 파일: models, admin UI / 검증: assignee filter.
- [P1] status count dashboard / 파일: backend summary, admin UI / 검증: 상태별 count.
- [P1] 검수 checklist UI / 파일: admin UI / 검증: 이미지/위치/중복/데모 제외 체크 상태.
- [P1] reviewed-only export guard / 파일: reports API, admin UI / 검증: `submission_ready=true`는 reviewed 이상만.
- [P2] manual submission batch 계층 / 파일: `backend/app/api/submissions.py` / 검증: reviewed report ids로 draft batch.
- [P2] 제출 preview payload / 파일: `submission_preview.py`, admin UI / 검증: 실제 전송 없이 제목/본문/좌표/이미지 목록.
- [P2] 수동 접수번호/상태 기록 / 파일: models, admin UI / 검증: receipt number 저장/조회.

### #17 긴급 연락처 설정

- [P0] localStorage 저장 hook 단위 테스트 / 파일: `useWalkSafeSettings.ts`, frontend tests / 검증: 저장 후 reload mock.
- [P0] 전화번호 validation/error message / 파일: `useWalkSafeSettings.ts`, `AssistPanel.tsx` / 검증: invalid phone 안내.
- [P0] 연락처 삭제/초기화 버튼 / 파일: `AssistPanel.tsx`, hook / 검증: clear action.
- [P0] 연락처가 report/STT payload로 새지 않음 회귀 / 파일: frontend tests, report client / 검증: guardian field 미전송.
- [P1] 보호자 연락처 마스킹 표시 / 파일: hook, AssistPanel / 검증: `010-****-1234` 등.
- [P1] “전화/SMS 발신 없음” 문구 고정 / 파일: AssistPanel, docs / 검증: static grep.
- [P1] 복수 연락처 구조 준비 / 파일: `useWalkSafeSettings.ts` / 검증: primary/secondary local-only.
- [C] 실제 전화/SMS 발신 / 조건: 사용자 확인, Android 권한, 긴급 오작동 방지 UX 확정 후.

### #18 보호자/초기 설정 최소 UI

- [P0] 설정 schema version/migration / 파일: `useWalkSafeSettings.ts` / 검증: v0/invalid JSON migration.
- [P0] 설정 카드 접기/펼치기 / 파일: `AssistPanel.tsx`, CSS / 검증: aria-expanded DOM test.
- [P0] 첫 실행 setup checklist / 파일: hook, AssistPanel / 검증: 위치/카메라/음성/개인정보 안내 상태.
- [P0] `viewport.maximumScale=1` 제거 / 파일: `apps/web/app/layout.tsx` / 검증: metadata static test.
- [P0] DOM 순서: 상태/위험 요약을 카메라보다 먼저 / 파일: `page.tsx` / 검증: DOM/static test.
- [P0] skip link 추가 / 파일: `page.tsx`, CSS / 검증: skip link static.
- [P0] 단일 live region controller / 파일: `useLiveAnnouncement.ts`, AssistPanel / 검증: 위험 assertive, 일반 polite.
- [P1] 음성 속도/피치 설정 / 파일: `feedback.ts`, settings hook / 검증: speak mock.
- [P1] 안내 상세도 짧게/표준 / 파일: `risk-guidance.ts`, settings / 검증: phrase fixture.
- [P1] 진동 강도/무음 보강 설정 / 파일: `feedback.ts` / 검증: vibration pattern.
- [P1] 위험 알림 쿨다운 설정 / 파일: `useRiskFeedback.ts`, settings / 검증: cooldown fixture.
- [P1] 자동 신고 on/off 설정 / 파일: `useAutoReportV2.ts`, settings / 검증: auto-report policy.
- [P1] 이미지 증거/GPS 포함 동의 UI / 파일: settings, docs / 검증: payload 포함/제외 test.
- [P1] 큰 글씨/고대비/reduced motion CSS / 파일: `globals.css` / 검증: CSS static.
- [P1] 터치 타깃 48px 이상 정책 / 파일: `globals.css` / 검증: CSS policy script.
- [P2] screen-reader optimized mode / 파일: `page.tsx`, AssistPanel / 검증: 상태 우선 DOM.
- [P2] 접근성 정적 체크 script / 파일: `scripts/check_frontend_accessibility_static.py` / 검증: py_compile + grep.

### #19 TTS HTTP cache header 자동 확인

- [P0] 여러 TTS 문구 batch smoke / 파일: `check_voice_tts_http_cache_20260525.py` / 검증: 7문구 loopback cache.
- [P0] 정상 cache header unit test / 파일: `tests/test_voice_tts.py`, `voice/server.py` / 검증: fake engine으로 `X-Voice-Cached=true`.
- [P0] 응답 media type/bytes 검증 / 파일: cache script / 검증: `audio/wav`, bytes > 0.
- [P0] BLOCKED JSON/exit 정책 옵션 / 파일: cache script / 검증: `--json`, `--blocked-exit-zero`.
- [P1] TTS phrase catalog / 파일: `voice/phrases.py` 또는 JSON / 검증: catalog completeness.
- [P1] `/speech/tts/cache-status` dry-run endpoint / 파일: `voice/server.py`, `voice/tts.py` / 검증: 모델 로드 없이 cached/key 반환.
- [P1] PWA HTTP TTS client 선택지 / 파일: `voice-api.ts`, `feedback.ts` / 검증: mocked audio playback.
- [P1] `allow_fallback=false` 테스트 / 파일: `tests/test_voice_tts.py` / 검증: fallback 금지 시 에러/header.
- [P1] cache key 입력 확장 / 파일: `voice/tts.py` / 검증: speaker/ref 변경 시 key 변경.
- [P1] TTS text 길이 제한 / 파일: `voice/server.py` / 검증: max length 422.
- [P1] fallback과 cache 검증 분리 / 파일: cache script / 검증: fallback은 cache PASS로 보지 않음.
- [P2] CORS/OPTIONS voice contract / 파일: `check_voice_contract.py`, `voice/server.py` / 검증: OPTIONS smoke.

### #20 unknown/저신뢰 명령용 NLU fallback

- [P0] NLU fallback no-op 확장점 구현 / 파일: `voice/intents.py`, `voice/server.py` / 검증: disabled fallback은 reprompt만 반환.
- [P0] fallback도 `should_execute=false` 강제 test / 파일: `tests/test_voice_intents.py` / 검증: fallback candidate가 있어도 실행 금지.
- [P0] 부정 명령 안전 처리 / 파일: `voice/intents.py` / 검증: “신고하지 마”, “신고 취소”가 create_report로 오탐 안 됨.
- [P0] 목적지/신고 단어 충돌 처리 / 파일: `voice/intents.py` / 검증: “신고센터로 안내해줘”는 목적지.
- [P1] intent schema endpoint / 파일: `voice/server.py`, `check_voice_contract.py` / 검증: intent 목록/slot schema 반환.
- [P1] raw audio 없는 intent telemetry schema / 파일: `voice/telemetry.py`, docs / 검증: 개인정보 없이 normalized, intent, reason만.
- [P1] 반복 unknown UX / 파일: `useVoiceCommands.ts` / 검증: N회 실패 후 짧은 도움말.
- [P1] sample manifest schema / 파일: `samples/voice/stt/manifest.schema.json` / 검증: JSON schema validation.
- [P1] low-confidence threshold 설정화 / 파일: `voice/intents.py`, config/docs / 검증: threshold fixture.
- [P2] 로컬 NLU provider interface / 파일: `voice/nlu.py` / 검증: default disabled, mock provider returns candidates.
- [C] 실제 LLM/Cloud NLU 연결 / 조건: 비용, 개인정보, secret, 장애 대응 승인 후.

## 공통 추가 구현 필요 항목

### PWA/offline

- [P0] PWA 설치 가능/설치됨 상태 감지 / 파일: `usePwaInstall.ts`, `page.tsx` / 검증: display-mode/beforeinstallprompt mock.
- [P0] online/offline 상태 배너 / 파일: `page.tsx`, AssistPanel / 검증: `navigator.onLine` mock.
- [P0] API 요청 offline preflight / 파일: detect/report/navigation API clients / 검증: offline이면 즉시 사용자 메시지.
- [P0] API/업로드 응답 캐시 금지 / 파일: `sw.js`, backend headers / 검증: no-store header.
- [P1] manifest `id/scope/lang/categories` 보강 / 파일: `manifest.webmanifest` / 검증: manifest audit script.
- [P1] PNG 192/512 maskable icon / 파일: `public/icons/*` / 검증: manifest icon audit.
- [P1] service worker version/cache name 화면 표시 / 파일: `sw.js`, `page.tsx` / 검증: SW message mock.
- [P1] SW update banner / 파일: `page.tsx`, `sw.js` / 검증: updatefound mock.
- [P1] offline shell 전용 fallback UI / 파일: `public/offline.html` 또는 app fallback / 검증: SW fetch fixture.
- [P2] opt-in IndexedDB report queue / 파일: `offline-report-queue.ts` / 검증: TTL/용량/수동 재시도 test.

### 개인정보/데이터 보존

- [P0] v2 raw payload allowlist 저장 / 파일: `backend/app/api/reports.py` / 검증: extra field가 metadata에 저장되지 않음.
- [P0] metadata 크기 제한 / 파일: backend schema/config / 검증: 과대 payload 413/422.
- [P0] localStorage PII 미전송 회귀 / 파일: `apps/web/tests/settings-privacy.test.ts` / 검증: 보호자 연락처 payload 불포함.
- [P0] export/upload `Cache-Control: no-store` / 파일: `reports.py`, `uploads.py` / 검증: header test.
- [P1] 개인정보 동의 버전 저장 / 파일: settings/report metadata / 검증: consent_version 포함.
- [P1] report retention policy 문서 / 파일: `docs/data_retention_policy.md` / 검증: 보존 기간/삭제 승인 gate 명시.
- [P1] retention dry-run script / 파일: `scripts/check_report_retention_dry_run.py` / 검증: 삭제 없이 만료 대상 산출.
- [P1] 업로드 이미지 EXIF 제거/재인코딩 / 파일: uploads service / 검증: EXIF 포함 fixture에서 제거.
- [P1] public/redacted export mode / 파일: reports API / 검증: 좌표 rounding, image_path 제외.
- [P2] admin 상세 좌표 마스킹 모드 / 파일: admin UI / 검증: 발표/스크린샷 모드.

### 접근성/evidence

- [P0] 기능번호→테스트→증거 matrix / 파일: `docs/evidence/feature_matrix.md` / 검증: link/static check.
- [P0] PASS/BLOCKED/미검증 표준 템플릿 / 파일: `docs/evidence/templates.md` / 검증: 문서 확인.
- [P0] fake/headless/device/model evidence badge 규칙 / 파일: `product/done-criteria.md` / 검증: 과대 완료 표현 방지.
- [P1] PWA manifest/SW audit 결과 문서 / 파일: `docs/execution/*_pwa_offline.md` / 검증: audit script 결과.
- [P1] Accessibility static audit 문서 / 파일: `docs/execution/*_accessibility_static.md` / 검증: static script.
- [P1] Data retention/privacy checklist / 파일: `docs/evidence/privacy_checklist.md` / 검증: checklist review.
- [P1] CSV/JSON/GeoJSON shape snapshot / 파일: backend tests/fixtures / 검증: pytest.
- [P2] final report evidence index / 파일: `docs/evidence/final_report_index.md` / 검증: static link check.
- [P2] source-of-truth 문서 우선순위 고정 / 파일: `docs/README.md` / 검증: docs link check.

## 바로 다음 구현 후보 30개

1. `distance_m` source/confidence/상한 검증.
2. 거리 없음 시 보폭 문구 금지 회귀 테스트.
3. 목적지 검색 health endpoint.
4. TMAP POI mock/local provider.
5. 검색 요청 throttle/cancel.
6. 목적지 선택만으로 route request 금지 테스트.
7. 길안내 중지 intent/UI.
8. rerouting 문구와 실제 동작 일치.
9. off-route threshold 설정화.
10. GPS accuracy edge fixture.
11. 객체 단위 bbox tracking key.
12. ROI debug gate.
13. Stage1 image→detect→report→export trace mode.
14. trace `--require-server-source`.
15. export deep field 검증.
16. Admin export DOM 회귀 테스트.
17. JSON export attachment filename.
18. export/upload no-store header.
19. backend cluster/summary endpoint.
20. fake 판정 로직 backend/frontend 통합.
21. 상태 변경 history + reason.
22. status transition guard.
23. settings localStorage 예외 처리.
24. 연락처 삭제/마스킹/미전송 테스트.
25. 설정 카드 접기/펼치기.
26. `viewport.maximumScale=1` 제거.
27. 단일 live region controller.
28. TTS batch cache smoke.
29. NLU no-op fallback + should_execute=false test.
30. evidence feature matrix.
