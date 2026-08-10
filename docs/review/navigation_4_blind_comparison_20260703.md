# `song4749/navigation_4_blind` 비교 및 도입 판단표 - 2026-07-03

> Status: SNAPSHOT / PLATFORM SUPERSEDED. 이 비교는 당시 Android-primary 가정을 사용했다. Web/PWA 주 앱 확정 이후에는 UI·음성·길안내 참고자료로만 사용하며 현재 플랫폼 판정에는 쓰지 않는다.

## 조사 범위

- 현재 프로젝트: `/home/ddobagi/Code/hanium-dreamup`
- 비교 repo: `https://github.com/song4749/navigation_4_blind`
- 로컬 클론: `/home/ddobagi/Downloads/codex-repo-research/navigation_4_blind`
- 비교 repo HEAD: `17410a07ee27de6e1fb4eeae5c1afe2bf8cf0a4d` (`2025-04-15`, `edit readme`)
- 조사 방식: 정적 코드/문서 조사. 서버 실행, 외부 TMAP/OpenAI/ElevenLabs 호출, 모델 다운로드, 배포는 하지 않음.
- 안전 원칙: `.env`/비밀값은 열지 않았고, raw 이미지/오디오/위치 데이터도 읽거나 생성하지 않음.

## 한 줄 결론

**팀원이 제안한 repo를 “기반 코드로 가져와 커스터마이징”하는 것은 비추천**한다. 현재 프로젝트는 Android native ARCore/TFLite + metric depth + 신고/운영 backend가 주 경로인데, 비교 repo는 웹/FastAPI 서버가 카메라 프레임을 받아 ONNX 추론하는 데모형 구조라 핵심 방향이 다르다. 다만 **점자블록 안내 정책, 음성 목적지 확인 UX, 고정 MP3 fallback, 탐지/깊이/세그멘테이션 디버그 화면 아이디어**는 참고할 가치가 있다.

## 현재 프로젝트 방향 요약

| 항목 | 현재 방향 | 근거 |
|---|---|---|
| 주 앱 | Android native ARCore/TFLite APK가 주 사용자 앱. Web/PWA는 demo/admin/운영 보조 | `README.md`, `apps/android/README.md`, `product/vision.md` |
| 핵심 가치 | 스마트폰 착용형 카메라처럼 사용해 보행 위험을 TTS/진동으로 안내하고, 손상 점자블록 등은 신고 데이터로 축적 | `product/vision.md`, `docs/status/current_status.md` |
| 모델 방향 | `unified_walksafe` 13-class 단일 모델 지향. 현재 unified TFLite asset은 미완료이며 legacy two-model fallback 유지 | `README.md`, `model/README.md`, `apps/android/README.md` |
| Depth/거리 | ARCore Raw/Full metric depth와 bbox-depth 정합이 핵심 gate. `N보` 안내는 metric depth 검증 전 완료 주장 금지 | `README.md`, `apps/android/README.md`, `docs/status/current_status.md` |
| Backend | FastAPI + PostGIS 기반 `/detect/v2`, `/reports/v2`, `/reports/export`, `/navigation/*` | `backend/app/api/*.py`, `docs/backend/api_reference.md` |
| 내비게이션 | backend TMAP pedestrian/POI proxy와 Android/웹 길안내 hook이 이미 있음. API key는 backend에 둠 | `backend/app/services/tmap_pedestrian.py`, `backend/app/api/navigation.py`, `backend/.env.example` |
| 완료 조건 | Android 실기기 bbox/depth 정합, trusted GPS, PostGIS no-skip, unified TFLite/export evidence가 필요 | `docs/review/requirements/walksafe_requirement_traceability_matrix_20260701.md`, `docs/android/android_native_mvp_evidence_20260630.md` |

## 비교 repo 요약

| 항목 | 내용 | 근거 |
|---|---|---|
| 제품 목표 | 스마트폰 카메라로 장애물/점자블록/인도·도로를 인식하고 음성 경고 및 TMAP 보행 내비게이션 제공 | `README.md` |
| 실행 방식 | `uvicorn combined_app:app --reload`, 루트 `.env`, 4개 ONNX 모델을 별도 다운로드해 `onnx_models/`에 배치 | `README.md`, `combined_app.py` |
| Backend | FastAPI 통합 앱, `detection_router`와 `navigation_router` 포함, CORS `*`, static/template 제공 | `combined_app.py` |
| 탐지 | 서버가 업로드 프레임을 OpenCV로 디코딩 후 YOLO/Depth Anything/segmentation 실행 | `detection_app/detection_router.py`, `detection_app/models/*.py` |
| 모델 | `guide_block_yolo11m.onnx`, `obstacle_detect_yolo12s.onnx`, `road_segmentation_yolo11seg_small.onnx`, `depth_anything_v2_vitb.onnx` 전제. repo에는 모델 파일 없음 | `README.md`, `detection_app/models/*.py` |
| 내비게이션 | TMAP POI 검색, TMAP pedestrian route, Leaflet/OpenStreetMap 지도, 메모리 `route_sessions` | `navigation_app/navigation_router.py`, `navigation_app/static/js/*.js` |
| 음성 | Web Speech API + 사전 녹음 MP3 + 선택적 ElevenLabs/OpenAI 경로 | `navigation_app/static/js/speech.js`, `detection_app/utils/audio_map.py`, `navigation_app/navigation_router.py` |
| 품질 리스크 | LICENSE 없음, 테스트 없음, requirements 버전 미고정/누락, import 시 모델 로드, startup 외부 호출, API key 노출 가능성 | repo tree, `requirements.txt`, `combined_app.py`, `navigation_app/navigation_router.py` |

## 전체 비교표

| 비교 항목 | 현재 프로젝트 `hanium-dreamup` | 비교 repo `navigation_4_blind` | 방향 일치도 | 판단 |
|---|---|---|---:|---|
| 주 사용자 앱 | Android native ARCore/TFLite가 주 경로 | 웹/Jinja/vanilla JS + FastAPI 서버 중심 | 낮음 | 전체 기반으로 갈아타면 현재 Android native 전환 이유가 사라짐 |
| 카메라/추론 위치 | Android on-device TFLite + ARCore depth. debug도 metadata-only 원칙 | 브라우저가 주기적으로 JPEG frame을 서버에 업로드해 서버 ONNX 추론 | 낮음 | privacy/latency/offline/device gate 측면에서 현재 방향과 충돌 |
| Depth | ARCore metric depth. bbox와 같은 객체를 가리키는지 실기기 검증 필요 | Depth Anything normalized map. `depth_value > 0.2`식 rule | 낮음 | 제품 거리/`N보` 안내에 쓰면 퇴행. 연구/시각화 참고만 가능 |
| 모델 클래스 | unified 13-class: person, vehicles, traffic light, normal/damaged tactile, crosswalk, curb, uneven sidewalk, e-scooter obstruction | 점자블록 detect, 장애물 detect, 도로/인도 segmentation, depth 별도 모델 | 중간 | 목표는 비슷하지만 클래스 계약/런타임/모바일 배포 방식이 다름 |
| 점자블록 UX | 정상/손상 점자블록 중심. 손상은 기본 신고 대상, 일반 경고는 gate 뒤 | 점자블록 미탐지/이탈/재탐지, 정지 블록, 좌우 안내 MP3 | 중간 | 메시지 정책은 참고 가능. 모델/코드는 직접 이식 비추천 |
| 장애물 경고 | 경로 차단/접근 위험일 때만 TTS/진동. 단순 존재 경고 지양 | bbox 중심/depth 변화 rule로 좌우 회피 안내 | 중간 | “행동 가능한 짧은 안내” 아이디어만 참고 |
| 인도/차도 이탈 | 현재 unified 기본 계약에는 road segmentation 없음 | YOLO-Seg로 roadway/sidewalk 탐지 후 이탈 안내 | 중간 | 후순위 기능 spike로 참고 가능. Android TFLite 재설계 필요 |
| 내비게이션 API | `/navigation/walking`, `/navigation/destinations/search`, provider health, mock POI, TMAP-only schema/test 존재 | `/api/search`, `/api/get_route`, `/api/guidance_update`, 메모리 세션 | 중간 | 현재 구현이 더 체계적. repo 코드는 복붙하지 말고 UX만 참고 |
| TMAP key 처리 | backend `.env`의 `TMAP_APP_KEY`; Android에는 key 저장하지 않는 정책 | `TMAP_API_KEY`를 읽고 일부 템플릿에 주입. startup 때 외부 호출 | 낮음 | 보안/비용/외부 호출 리스크 때문에 직접 반입 금지 |
| Frontend | Next.js PWA는 보조/demo/admin. Android가 주 앱 | Leaflet + vanilla JS + Jinja template 웹앱 | 중간 | 화면 패턴은 참고 가능하지만 구현은 현재 스택으로 재작성 필요 |
| 음성 UX | Android TTS/SpeechRecognizer와 PWA/local STT prototype. 위험 경고 우선, 자동 신고 침묵 정책 | Web Speech API, MP3 queue, ElevenLabs fallback, 목적지 yes/no 확인 | 중간 | 목적지 확인 대화와 MP3 fallback은 참고 가능. 외부 TTS/NLP는 제외 |
| 신고/운영 | `/reports/v2`, PostGIS, duplicate, admin, export, retention/privacy 정책 | 신고/운영 기능 없음 | 낮음 | 현재 핵심 기능과 직접 겹치지 않음 |
| 테스트/검증 | Android/backend/web 테스트와 문서 evidence 다수. 단 Device PASS는 아직 제한 | 테스트 원본 없음, `__pycache__`만 포함 | 낮음 | repo 기반 전환 시 회귀 검증 비용 큼 |
| 의존성/재현성 | Android/Next/backend 각각 의존성 관리. 모델/데이터는 로컬 artifact 정책 | `requirements.txt` 버전 미고정, `fastapi/uvicorn/requests/openai/jinja2` 누락, GPU 전제 | 낮음 | 실행 재현성 낮음 |
| 라이선스 | 자체 프로젝트 파일. 외부 데이터/모델은 로컬 artifact 정책 | LICENSE/NOTICE 없음, Google Drive 모델 라이선스 불명 | 낮음 | 코드/모델/MP3/assets 복사 전 원저자 허락 또는 라이선스 확인 필요 |
| 보안/운영 | secret/배포/비용/외부 공개는 승인 전 금지. debug 기본 off | CORS `*`, startup TMAP 호출, key client 노출 가능, upload 제한 부족 | 낮음 | 그대로 반입하면 보안/비용 원칙 위반 가능 |

## TMAP/navigation 기능 별도 비교

| 항목 | 현재 프로젝트 | 비교 repo | 판단 |
|---|---|---|---|
| Route API | `fetch_tmap_pedestrian_route()`가 async `httpx`, timeout, provider error mapping, schema normalize | `requests.post()` 동기 호출, timeout 없음 | 현재 구현 유지 |
| POI 검색 | live/mock provider, 한국어 suffix normalize, response model, health endpoint | `/api/search`, 일부 자연어/근처 검색 시 미정의 함수 위험 | 현재 구현 유지. 자연어 UX만 참고 |
| 우선순위 | `RECOMMEND`, `MAIN_STREET`, `DISTANCE`, `STAIR_AVOID`; 기본 `STAIR_AVOID` | `searchOption="0"` 최적 경로 중심 | 현재 구현이 시각장애인 보행 정책에 더 가까움 |
| API key | backend proxy. Android에 TMAP key 저장하지 않음 | `api_key`를 template에 전달 가능 | 비교 repo 방식은 배제 |
| 경로 세션 | Android/웹에서 route response를 구조화해 사용 | 서버 전역 `route_sessions` dict, 30분 cleanup | 멀티유저/멀티워커 취약. 반입 비추천 |
| 안내 업데이트 | Android `RouteNavigator`/웹 hook의 off-route, reroute, arrival 등 gate | `guidance_update`에서 거리/nearest point 근사 | 현재 구현 유지. 단, 안내 UX 문구는 참고 가능 |
| 테스트 | `backend/tests/test_navigation_routes.py` 존재 | 테스트 없음 | 현재 구현 유지 |

## 선별 도입 후보 판단표

| 후보 | 가져올 가치 | 그대로 가져올 때 리스크 | 추천 | 구현 방식 |
|---|---|---|---|---|
| 점자블록 이탈/재탐지/정지 블록 안내 문구 | 시각장애인 보행 UX에 직접 관련 | 비교 repo 모델 class와 현재 unified class가 다름 | 조건부 채택 | 코드 복사 없이 `MessagePolicy`/문서 정책으로 재작성 |
| 음성 목적지 설정 yes/no 확인 UX | 화면 조작을 줄이는 데 유용 | Web Speech/Jinja 구조와 Android 주경로 불일치 | 채택 후보 | Android/Next 현 구조에 “목적지 후보 읽기 → 번호/네/아니오 선택” ticket로 설계 |
| 고정 한국어 MP3 fallback | 네트워크/외부 TTS 없이 일관된 경고 가능 | MP3 assets 라이선스 불명, Android TTS와 중복 | 보류 | 직접 녹음/생성한 자체 asset 또는 Android TTS fallback 정책으로 별도 구현 |
| 도로/인도 segmentation 이탈 경고 | 보행로 이탈 감지 기능으로 가치 있음 | 서버 YOLO-Seg, 모델/라이선스/모바일 성능 불명 | 후순위 spike | Android TFLite segmentation 모델 후보와 데이터/성능 검증부터 별도 진행 |
| Depth Anything 단안 depth | ARCore 미지원 기기 fallback 연구 소재 | metric depth 아님. 현재 `N보`/TTC gate와 충돌 | 제품 미도입 | 연구/시각화 비교 artifact로만 사용 |
| Leaflet 지도/검색 UI 패턴 | 웹 데모에서 빠르게 설명 가능 | 현재 Next.js/Admin 구조와 다름 | 부분 참고 | Next 컴포넌트/Android UI로 재작성 |
| TMAP route/POI backend 코드 | 목적은 같음 | 현재 코드보다 보안/테스트/에러 처리 약함 | 미도입 | 현재 `tmap_pedestrian.py` 유지, 필요한 UX만 추가 |
| 서버 프레임 업로드 추론 | 데모 구현 속도는 빠름 | privacy, latency, upload 비용, 서버 GPU, raw frame 저장 위험 | 미도입 | Android on-device 경로 유지 |
| 전체 repo fork 후 커스터마이징 | 빠른 시연처럼 보일 수 있음 | 라이선스/테스트/보안/아키텍처 전환 비용 큼 | 비추천 | 참고 repo로만 보관 |

## 의사결정 권고

1. **전체 시스템을 이 repo 기반으로 바꾸는 선택은 하지 않는 것이 좋다.**
   - 현재 프로젝트의 핵심 차별점은 Android ARCore metric depth, local TFLite, Device Gate, PostGIS 신고/운영 evidence다.
   - 비교 repo는 빠른 웹 데모로는 유사하지만, 현재 gate를 대부분 대체하지 못한다.

2. **TMAP navigation은 현재 구현을 유지한다.**
   - 현재 backend는 provider health, response schema, error mapping, mock POI, tests가 이미 있고, Android key 미저장 정책도 맞다.
   - 비교 repo의 navigation code는 동기 `requests`, timeout 없음, 전역 route session, startup 외부 호출, key 노출 가능성이 있어 직접 반입하지 않는다.

3. **팀원 제안은 “코드 반입”이 아니라 “UX/정책 참고”로 수용하는 게 안전하다.**
   - 참고할 것: 점자블록 이탈/재탐지 안내, 목적지 음성 확인 UX, 경고 우선순위, 탐지/깊이/segmentation 시연 화면 구성.
   - 복사하지 말 것: backend router, TMAP key 처리, 서버 프레임 업로드 pipeline, Depth Anything 거리 판단, 외부 TTS/OpenAI 경로, 모델/MP3 assets.

4. **만약 팀에서 반드시 활용하려면 먼저 확인할 것**
   - LICENSE/모델/MP3 asset 재사용 허가.
   - 모델 파일 출처, 체크섬, 라이선스.
   - requirements 재현성, 누락 dependency, Python/CUDA 버전.
   - 외부 API 비용/키 관리 방식.
   - 최소 smoke/unit test와 보안 upload 제한.

## 다음 액션 제안

| 우선순위 | 액션 | 산출물 |
|---|---|---|
| P0 | 비교 repo는 reference로만 두고 현재 Android/backend 방향 유지 결정 | 팀 회의 결정 기록 |
| P1 | “음성 목적지 후보 확인 UX”를 현재 Android/Next 구조에 맞춰 작은 ticket으로 작성 | `product/backlog.md` 또는 별도 issue |
| P1 | “점자블록 이탈/재탐지/정지 블록 안내 정책”을 현재 `MessagePolicy` 기준으로 검토 | 정책 문서/테스트 후보 |
| P2 | 도로/인도 segmentation은 Android TFLite 가능성 조사만 별도 spike | 모델 후보/데이터/성능 조사 문서 |
| P2 | 고정 MP3 fallback은 자체 제작 asset 정책 확정 후 검토 | 라이선스 안전한 음성 asset 정책 |
