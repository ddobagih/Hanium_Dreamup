# WalkSafe 의사결정 질문 작성을 위한 출처·충돌 감사

- 감사 기준일: 2026-07-17 KST
- 목적: 기존 문서를 그대로 계승하지 않고, 사용자에게 확인해야 할 제품·정책·규격·운영 결정을 식별한다.
- 질문 기준 데이터: `docs/control/questionnaire/walksafe-project-decision-questions.json`
- 주의: 이 문서는 제품 결정을 확정하지 않는다. 코드에 존재하는 동작도 사용자 승인을 받기 전에는 제품 정책이 아니다.

## 1. 판정 원칙

| 분류 | 뜻 | 질문 작성 시 처리 |
|---|---|---|
| `USER_CONFIRMED` | 이번 설문 또는 추적 가능한 사용자 원문으로 확인된 결정 | 기준선 후보로 사용하되 변경 여부를 확인한다. |
| `CONFIRMED_BY_CODE` | 현재 소스·설정·계약·테스트에서 실제 동작이 확인됨 | 현재 구현 설명으로만 사용한다. 바람직한 정책이라고 추정하지 않는다. |
| `CURRENT_CANDIDATE` | 현재 제품 문서가 제안하는 방향이나 아직 승인 기준선은 아님 | 추천안 또는 선택지로 제시한다. |
| `CONFLICTING` | 같은 주제의 출처들이 서로 다른 결론을 가짐 | 반드시 사용자가 하나를 선택하게 한다. |
| `STALE` | 과거 범위·플랫폼·구현을 설명해 현재 기준으로 사용할 수 없음 | 역사적 근거로만 남기고 기본 답으로 채택하지 않는다. |
| `UNKNOWN` | 코드와 문서만으로 결정할 수 없거나 외부 사실·승인이 필요함 | 필수 질문으로 만들거나 명시적으로 미결정 처리한다. |

신뢰 순서는 `이번 사용자 답변 > 승인된 기준선 > 실행 코드·배포 설정 > 자동 테스트 > 현행 후보 문서 > 과거 문서`로 한다. 실행 코드가 문서와 다르면 “코드가 정답”으로 결론내리지 않고, 현재 구현과 의도한 정책이 다른 것으로 본다.

## 2. 조사 범위

| 출처군 | 대표 경로 | 확인한 내용 | 기본 판정 |
|---|---|---|---|
| 제품 후보 문서 | `product/vision.md`, `product/roadmap.md`, `product/backlog.md`, `product/done-criteria.md`, `product/decisions.md` | Web/PWA 주제품, Android 연구 경로, MVP·릴리스 조건 | `CURRENT_CANDIDATE` |
| Web 구현 | `apps/web/app/_walksafe/`, `apps/web/lib/`, `apps/web/public/sw.js`, `apps/web/app/page.tsx` | 세션, 센서, 탐지, 위험, 음성, 길안내, 신고, PWA | `CONFIRMED_BY_CODE` |
| Web 설정 | `apps/web/.env.example`, `deploy/config/walksafe-web.env.example` | detector/PWA/게이트웨이/로그 기본값 | `CONFIRMED_BY_CODE` |
| Backend 구현 | `backend/app/`, `backend/alembic/` | 탐지·신고·길안내·인증·감사·DB lifecycle | `CONFIRMED_BY_CODE` |
| Backend 설정 | `backend/app/config.py`, `backend/.env.example`, `deploy/config/walksafe-backend.env.example` | 개발/배포 provider, 보안, 저장소, timeout | `CONFIRMED_BY_CODE` |
| API 계약 | `contracts/walksafe.openapi.json`, `docs/walksafe-v2/backend_api_contract.md` | endpoint·schema·응답 계약 | 코드 생성 계약은 `CONFIRMED_BY_CODE`, 설명 문서는 `CURRENT_CANDIDATE` |
| 정책 문서 | `docs/walksafe-v2/*.md`, `docs/operations/*.md` | 위험·신고·음성·길안내·보존 운영 후보 | 혼합, 항목별 재판정 |
| 상태·감사 문서 | `docs/status/*.md` | 구현과 미검증 경계, 과거 스냅샷 | 날짜에 따라 `CURRENT_CANDIDATE` 또는 `STALE` |
| 시험·증거 | `apps/web/tests/`, `backend/tests/`, `tests/`, `docs/testing/` | 자동 검증 범위와 실기기 미검증 범위 | 테스트 코드는 `CONFIRMED_BY_CODE`, 과거 결과는 스냅샷 |
| Android 연구 | `apps/android/`, `docs/android/` | ARCore/TFLite·native 음성·신고 연구 경로 | 구현은 `CONFIRMED_BY_CODE`, 제품 역할은 사용자 결정 필요 |
| AI/ML | `model/`, `model/registry/`, `model/deployments/`, `configs/walksafe_unified_epoch270_field_20260711.json` | 클래스·후보 모델·배포 적격성·rollback | 구현/manifest는 확인 가능, 품질 승인 여부는 `UNKNOWN` |
| 과거 요구·실행 | `docs/review/requirements/`, `docs/execution/`, `plans/` | Android-primary 또는 이전 MVP 전제 | 원칙적으로 `STALE`, 유효 내용은 질문으로 재확인 |

## 3. 핵심 충돌·오염 감사

| ID | 주제 | 관찰된 근거 | 판정 | 반드시 받아야 할 결정 |
|---|---|---|---|---|
| A-001 | 주 사용자 플랫폼 | `product/vision.md`는 Web/PWA를 주제품으로 두고 `product/decisions.md`는 과거 Android-primary 판단을 superseded로 표시한다. 두 앱 모두 코드가 크다. | `CONFLICTING` | Web/PWA를 유일한 출시 제품으로 둘지, Android를 동등 제품으로 둘지 |
| A-002 | Android 역할 | 현재 제품 문서는 연구 경로라 하지만 Android에는 길안내·음성·신고·ARCore까지 구현돼 있다. | `CONFLICTING` | 연구·향후제품·현재제품 중 어느 역할인지와 Web 출시 차단 여부 |
| A-003 | detector 기본값 | Web `config.ts`와 `.env.example`은 `server-v2`, backend `config.py`의 개발 기본은 `fake`다. | `CONFLICTING` | 개발·데모·현장·운영별 허용 provider와 fake 표시/차단 규칙 |
| A-004 | PWA 기본값 | Web 제품 컨셉은 PWA지만 `.env.example`은 `NEXT_PUBLIC_WALKSAFE_PWA_ENABLED=false`다. | `CONFLICTING` | 개발·시험·운영에서 PWA를 언제 켜고 어떤 기능을 오프라인 제공할지 |
| A-005 | MVP 범위 | 5월 초기 범위는 길안내를 제외했으나 현재 Web과 backend에는 TMAP 검색·경로·재탐색이 연결돼 있다. | `STALE`/`CONFLICTING` | 길안내가 출시 MVP의 필수인지 |
| A-006 | 실사용 완료 정의 | 자동 정책 테스트는 광범위하지만 상태 문서는 실폰 camera/GPS/mic/TTS/진동 E2E를 미검증으로 둔다. | `CONFLICTING` | 자동검증·정지 실폰·현장 시험 중 각 단계의 완료 명칭과 출시 gate |
| A-007 | 탐지 실패 표현 | 실사용 server-v2는 fake fallback을 금지하지만 개발 문서와 fake 모드가 공존한다. | `CURRENT_CANDIDATE` | 사용자에게 “탐지 불가”를 어떻게 알리고 어떤 기능을 계속 제공할지 |
| A-008 | 모델 출시 적격성 | epoch270이 field candidate로 연결됐지만 registry는 `deployment_eligible=false`이고 데이터 누수·hash 문제가 남아 있다. | `CONFLICTING` | 현 모델의 demo/내부시험/베타/운영 사용 허용 범위 |
| A-009 | 모델 교체 시점 | 제품 로드맵은 모델 품질 복구를 P0로 보지만 사용자는 서비스를 먼저 만들고 모델을 교체하는 방향을 밝혔다. | `CONFLICTING` | 서비스 E2E와 모델 재학습의 독립 작업선·출시 gate 관계 |
| A-010 | 탐지 클래스 | 현재 unified 계약은 13개지만 v1에는 `parked_kickboard_bicycle`, `construction_obstacle`, `pothole` 등 다른 이름이 남아 있다. | `CONFLICTING` | 사용자에게 보이는 canonical 위험 분류와 legacy 호환 범위 |
| A-011 | 손상 점자블록 안내 | 정책 문서는 report-only·기본 무음으로 두지만 `config.ts`에는 손상 TTS 문구와 진동 패턴이 남아 있다. | `CONFLICTING` | 손상을 발견했을 때 경고·신고·무음 중 기본 행동 |
| A-012 | 자동 신고 피드백 | 정책 문서는 자동 신고 성공 TTS 없음이라고 하나 `shouldEmitReportUserFeedback`는 auto와 voice 모두 true다. UI/haptic 의미도 섞여 있다. | `CONFLICTING` | 자동 신고 성공·실패를 TTS, 진동, 화면 중 무엇으로 알릴지 |
| A-013 | 자동 신고 동의 | 코드에 세션 동의 gate가 있으나 동의 범위·유효기간·철회 후 처리 정책은 최종 승인되지 않았다. | `UNKNOWN` | 탐지 처리와 이미지·위치 신고 각각의 동의 단위와 철회 효과 |
| A-014 | 자동 신고 기준 | Web 코드 기본은 confidence 0.70, GPS 15m, 3 frame, 700ms, 10분 cooldown이다. | `CONFIRMED_BY_CODE` | 이 숫자를 제품 정책으로 승인할지, 현장 결과로 조정할지 |
| A-015 | 음성 신고 기준 | 음성 요청은 최신 분석 프레임이 없으면 fail-close하고 cooldown을 다르게 취급한다. | `CURRENT_CANDIDATE` | 사용자 명시 요청이 자동 gate 중 무엇을 우회할 수 있는지 |
| A-016 | 신고 대상 | backend는 손상 점자블록만 허용하고 일반 객체·손상 영역을 거부한다. | `CONFIRMED_BY_CODE` | 향후 신고 대상을 확대할지, 현재 범위를 확정할지 |
| A-017 | 이미지 저장 | 신고 API는 이미지 업로드를 저장하며 export는 이미지를 제외한다. 현장 telemetry는 metadata-only 후보도 있다. | `UNKNOWN` | 이미지가 필수인지, 대체 가능한지, 누가 열람하고 언제 삭제하는지 |
| A-018 | 위치 정밀도 | 자동 신고는 GPS accuracy 15m 이하를 요구하고 agency export도 정확 좌표를 사용한다. | `CURRENT_CANDIDATE` | 사용자 신고·운영 검수·기관 제출별 좌표 정밀도와 공개 범위 |
| A-019 | 중복 신고 | 공간·시간 중복 검사와 cooldown이 구현돼 있으나 중복 merge, 기존 신고 귀속, 사용자 알림의 정책 승인이 없다. | `UNKNOWN` | 중복의 기준·표시·병합·재신고 규칙 |
| A-020 | 신고 상태 | `new -> reviewed -> resolved`가 문서와 코드에 있으나 기각·오탐·재개방·기관 접수 상태가 분명하지 않다. | `UNKNOWN` | 운영 workflow와 각 상태 변경 권한 |
| A-021 | 기관 전달 | 현재 후보는 관리자 CSV 수동 제출이며 자동 기관 API는 제외한다. 과거 자료에는 공공 연계 확장 표현이 있다. | `CONFLICTING` | MVP의 기관 전달 방식과 접수 receipt 책임 |
| A-022 | 관리자 인증 | env token/named account가 구현됐지만 조직 IdP/RBAC가 남은 일로 기록돼 있다. | `CURRENT_CANDIDATE` | 운영 인증 방식, 역할 수, 계정 lifecycle과 비상 접근 |
| A-023 | 관리자 export | CSV/JSON/GeoJSON과 `internal/minimum/agency` profile이 구현돼 있다. | `CONFIRMED_BY_CODE` | 제품에서 제공할 형식·최대 건수·민감 필드·승인 조건 |
| A-024 | 전역 경로 권한 | 현재 코드·문서는 TMAP-only를 강조한다. | `CURRENT_CANDIDATE` | TMAP을 유일 provider로 고정할지, 장애 시 대체 경로를 둘지 |
| A-025 | 점자블록 local steering | Web에는 감독형 flag가 있으나 evidence supplier가 없어 비활성이고, 비계량 advisory만 가능하다. 과거 문서는 더 강한 조향을 암시한다. | `CONFLICTING` | 출시에서 단순 방향 보조만 할지, local steering 연구를 포함할지 |
| A-026 | 길안내 표현 | 정책은 meter 단독보다 시간/보폭을 선호하지만 계산 기본 보폭은 0.65m이고 사용자 보폭 설정도 존재한다. | `CURRENT_CANDIDATE` | 거리·시간·보폭 안내 우선순위와 개인화 방식 |
| A-027 | 경로 이탈 | 코드 기본 25m, GPS accuracy 35m, cooldown 30초, 최대 2회 재탐색이다. | `CONFIRMED_BY_CODE` | 숫자와 최대 실패 후 사용자 안내를 승인할지 |
| A-028 | 도착 판정 | 코드 기본 반경 8m, 허용 accuracy 20m다. | `CONFIRMED_BY_CODE` | 실외/실내·GPS 불량에서 도착 확인 방식을 결정해야 함 |
| A-029 | 음성 처리 위치 | Web은 local faster-whisper 서비스, Android는 플랫폼 SpeechRecognizer를 사용하며 cloud 후보 문서도 있다. | `CONFLICTING` | 출시 플랫폼별 STT provider, 네트워크 필요성, fallback |
| A-030 | 음성 명령 범위 | 현재 intent는 신고·목적지·길안내·음성 on/off 등이다. 신고 취소, 도움말, 긴급연락 등은 미결정이다. | `UNKNOWN` | MVP intent와 확인이 필요한 상태변경 명령 |
| A-031 | TTS 중재 | 위험 > interaction > navigation > advisory가 후보지만 녹음 중 위험이 녹음을 취소하는 체감 정책은 미검증이다. | `CURRENT_CANDIDATE` | 발화 선점·중단·재개·반복 규칙 |
| A-032 | 진동 의미 | high 중심 진동과 여러 패턴이 코드에 있으나 사용자가 패턴을 학습할 수 있는지 검증되지 않았다. | `UNKNOWN` | 진동 사용 범위·패턴 수·비활성화·접근성 대안 |
| A-033 | 계정 필요성 | Web gateway field/admin session은 있으나 일반 보행자 로그인 필요 여부와 익명 신고 정책이 명확하지 않다. | `UNKNOWN` | 보행 세션·신고·설정 동기화별 로그인 요구 |
| A-034 | 데이터 보존 | 보존 스크립트와 문서가 있지만 이미지·신고·감사·음성·현장 로그별 기간과 법적 근거가 최종 승인되지 않았다. | `UNKNOWN` | 데이터 종류별 기간, 삭제 방식, 보존 예외 |
| A-035 | offline 범위 | service worker, shell cache, offline report queue helper가 있으나 제품 UI 전체 연결 여부는 제한적이다. | `CONFLICTING` | offline에서 허용할 기능과 “안전 기능 작동 중” 오인 방지 문구 |
| A-036 | 지원 브라우저·기기 | 실폰 일부 기록은 있으나 공식 지원 matrix와 최소 OS/browser가 없다. | `UNKNOWN` | iOS Safari, Android Chrome, Samsung Internet 등의 지원/최선노력 범위 |
| A-037 | 카메라 자세 | 후면 camera와 보행 사용을 전제하지만 손-held/목걸이/가슴 장착 중 공식 시나리오가 없다. | `UNKNOWN` | 권장 장착·각도·손 사용·화면 꺼짐 정책 |
| A-038 | 센서 필수성 | GPS/heading/camera는 핵심이고 IMU는 일부 gate에서 선택 사항이다. | `CURRENT_CANDIDATE` | 센서별 필수/선택, 거부·미지원 시 degraded mode |
| A-039 | 안전 주장 | 코드에는 fail-close와 STOP 제한이 있으나 법적·사용자 고지의 구체 문구와 허용 주장이 없다. | `UNKNOWN` | 보조 서비스의 한계, 사용 금지 상황, 사용자 책임, 비상 대안 |
| A-040 | 성능 목표 | detector interval·timeout은 코드에 있으나 단말-서버 E2E 지연, FPS, 배터리, 데이터 사용량의 승인 목표가 없다. | `UNKNOWN` | 환경별 정량 SLA/SLO와 미달 시 동작 |
| A-041 | API 장애 | TMAP timeout과 readiness는 있으나 provider 장기 장애 시 사용자 정책과 운영 조치가 확정되지 않았다. | `UNKNOWN` | 기존 경로 유지·중지·재시도·대체 provider 여부 |
| A-042 | DB 운영 기준 | PostGIS, migration, no-skip 검증이 있으나 실제 운영 topology·HA·RTO/RPO가 미정이다. | `UNKNOWN` | 단일/관리형 DB, backup, 복구, migration 승인 |
| A-043 | 보안 경계 | TLS, secret, rate limit, audit가 강하게 구현됐으나 threat model과 실제 운영 소유자가 없다. | `UNKNOWN` | 보호 자산·위협·책임자·수용 위험 |
| A-044 | 로그와 telemetry | field/test 로그와 retention 설정이 있으나 운영 사용자 telemetry opt-in과 식별자 정책이 없다. | `UNKNOWN` | 수집 이벤트, 목적, 최소화, 동의, 보존, 접근 |
| A-045 | 배포 대상 | systemd/nginx 예제가 있지만 실제 cloud/domain/region/소유 계정이 없다. | `UNKNOWN` | 운영 환경, 리전, 도메인, 배포 책임, 비용 한도 |
| A-046 | 릴리스 증거 수준 | Full-RC는 SBOM·provenance·재현성까지 요구하지만 서비스 E2E보다 먼저 과도하게 강화됐다. | `CONFLICTING` | demo/beta/release별 필수 증거와 후순위 증거 |
| A-047 | 버전 단위 | Web·backend·model·config·DB가 독립 변경되지만 사용자에게 보이는 호환성/rollback 단위가 없다. | `UNKNOWN` | release manifest 결속과 호환성 정책 |
| A-048 | 운영 책임 | runbook과 timer는 있으나 서비스 소유자, 당직, 장애 연락, 승인자가 정해지지 않았다. | `UNKNOWN` | 역할·지원시간·에스컬레이션·비상중지 권한 |
| A-049 | 비용·쿼터 | TMAP, 서버 추론, 저장소, 음성 서비스의 비용·월 한도가 없다. | `UNKNOWN` | 예산, 쿼터 초과 시 기능 축소, 알림 책임 |
| A-050 | 중단·종료 기준 | 장기 backlog는 있으나 기능 중단, 모델 폐기, 서비스 종료와 데이터 처리 기준이 없다. | `UNKNOWN` | continue/pivot/stop 기준과 종료 절차 |

## 4. 코드로 확인됐지만 정책 승인이 필요한 주요 기본값

다음 값은 구현에 존재한다. 질문 HTML에서는 “현재 구현값 유지”를 추천안으로 제시할 수 있지만, 현장 검증 없이 안전 기준으로 확정해서는 안 된다.

| 항목 | 현재 구현값 | 근거 |
|---|---:|---|
| Web 탐지 요청 간격 | 450ms | `apps/web/app/_walksafe/config.ts` |
| 일반 TTS cooldown | 6초 | `apps/web/app/_walksafe/config.ts` |
| 기본 보폭 | 0.65m | `apps/web/app/_walksafe/config.ts` |
| off-route 거리 | 25m | `apps/web/app/_walksafe/config.ts` |
| 자동 재탐색 cooldown/최대 횟수 | 30초 / 2회 | `apps/web/app/_walksafe/config.ts` |
| 길안내 GPS accuracy | 15m 이하 | `apps/web/app/_walksafe/config.ts` |
| 도착 반경/accuracy | 8m / 20m 이하 | `apps/web/app/_walksafe/config.ts` |
| 음성 녹음 상한 | 5초 | `apps/web/app/_walksafe/config.ts` |
| intent threshold | 0.70 | `apps/web/app/_walksafe/config.ts` |
| 위험 안정화 | 서로 다른 3 frame AND 700ms | `apps/web/app/_walksafe/risk-evaluator.ts` |
| TTC bucket | stop 3초, warning 10초, aware 30초 | `apps/web/app/_walksafe/risk-evaluator.ts` |
| 자동 신고 gate | confidence 0.70, GPS 15m, 3 frame, 700ms | `apps/web/lib/auto-report-v2.ts` |
| 자동 신고 cooldown | 동일 공간키 10분 | `apps/web/lib/auto-report-v2.ts` |
| backend 업로드 기본 상한 | 8MiB | `backend/app/config.py` |
| backend 추론 timeout | 2초 | `backend/app/config.py` |
| TMAP timeout | 4초 | `backend/app/config.py` |

## 5. 설문 작성 원칙

1. 서로 독립적으로 변경 가능한 결정은 한 질문에 합치지 않는다.
2. 코드의 현재값, 현행 문서 후보, 과거 문서 표현을 `current_context`에서 구분한다.
3. 추천안은 “현재 구현이 그렇다”가 아니라 Web/PWA 서비스를 먼저 완성하고 안전하게 검증한다는 목표에 맞춘다.
4. 모델 재학습과 Android 연구는 Web/PWA 서비스 완성을 자동으로 차단하지 않도록 별도 결정으로 묻는다.
5. 숫자는 현재 구현값을 그대로 승인할지, 현장시험으로 조정할지 선택하게 한다.
6. 개인정보·안전·정식 출시·운영 책임은 AI가 대신 승인하지 않는다.
7. 필수 질문이 모두 답변되고, 의존 질문과 충돌 규칙을 통과하고, 자유입력 필수값이 채워져야 “결정 완결”로 본다.
8. 답변 후에도 외부 사실이 필요한 항목은 `사용자 결정 완료 / 외부 증거 미완료`로 분리한다.

## 6. 질문 데이터 커버리지

질문 JSON은 다음 25개 독립 영역을 모두 명시적으로 포함한다.

1. 목표·KPI·범위
2. 사용자·이해관계자
3. Web/PWA·설치·지원환경·Android 역할
4. 보행 세션 생명주기
5. camera·GPS·heading·IMU·mic 권한
6. 탐지 class·model·runtime
7. 위험등급·우선순위·TTS·진동
8. 점자블록·local steering
9. TMAP 검색·경로·reroute·도착
10. STT·TTS·명령·중재
11. 신고·동의·중복·이미지·위치
12. Admin·검수·export·기관 수동 신고
13. 개인정보·보존·삭제
14. 인증·RBAC·session·audit
15. offline·degraded·error·retry
16. 접근성·사용성
17. 성능·배터리·용량
18. API·외부 서비스
19. DB·migration·data lifecycle
20. 보안·위협·비밀
21. AI 데이터·모델 교체·평가·rollback
22. 시험·UAT·증거·완료 정의
23. release·deploy·version·호환성
24. 운영·monitoring·backup·incident
25. 우선순위·roadmap·중단·종료

## 7. 답변 후 기준선화할 때의 사용법

- 설문 JSON 자체는 질문 기준선이다. 사용자 답변은 별도 응답 JSON으로 저장한다.
- 답변을 기존 문서에 자동 덮어쓰지 않는다. 먼저 결정 원장과 변경요청을 생성한다.
- `USER_CONFIRMED` 답변과 코드가 다르면 구현 gap으로, 답변과 기존 문서가 다르면 문서 오염 또는 superseded 후보로 기록한다.
- 질문 하나의 답이 여러 산출물에 영향을 주면 `affected_deliverable_types`를 통해 헌장·SRS·SDD·시험·운영 문서를 함께 갱신한다.
- 미응답, 의존성 위반, 충돌, 승인 주체 부재가 하나라도 있으면 관련 산출물을 `Approved/Baselined`로 올리지 않는다.

## 8. 감사 항목→canonical 질문 추적 매트릭스

이 표의 질문 ID는 `walksafe-project-decision-questions.json`의 canonical ID다. 하나의 감사 항목이 범위 결정과 세부 정책 결정을 함께 요구하면 복수 질문에 연결한다.

| 감사 ID | 해소 질문 ID | 해소 범위 |
|---|---|---|
| A-001 | `Q-PLT-001` | 주 출시 플랫폼 확정 |
| A-002 | `Q-PLT-004`, `Q-PLT-005` | Android 역할과 Web 출시 차단 경계 |
| A-003 | `Q-DET-001`, `Q-DET-002` | 운영 runtime과 fake 허용 범위 |
| A-004 | `Q-PLT-002`, `Q-PLT-003`, `Q-RES-007` | PWA 설치·실행 형태·offline 약속 |
| A-005 | `Q-NAV-001` | 길안내 MVP 포함 여부 |
| A-006 | `Q-TST-001`, `Q-TST-004`, `Q-TST-005` | 완료 정의와 실기기·현장 증거 등급 |
| A-007 | `Q-DET-002`, `Q-RES-001`, `Q-RES-002`, `Q-RES-012`, `Q-RES-013`, `Q-DET-019`, `Q-AIML-025`, `Q-AIML-026`, `Q-RES-015` | 탐지 실패·stale·복구·legacy fallback·부분 readiness 처리 |
| A-008 | `Q-AIML-001`, `Q-AIML-012`, `Q-AIML-016` | 현 모델 사용 등급·평가·승인 |
| A-009 | `Q-AIML-002`, `Q-AIML-019`, `Q-AIML-020`, `Q-AIML-021`, `Q-AIML-022`, `Q-AIML-023`, `Q-AIML-024`, `Q-AIML-025`, `Q-AIML-026`, `Q-AIML-027`, `Q-AIML-028`, `Q-AIML-029` | 모델 개선과 서비스 완성의 독립성, drop-in 교체·승격·rollback 계약 |
| A-010 | `Q-DET-003`, `Q-DET-004`, `Q-AIML-003` | canonical 클래스와 legacy migration·단계 승인 |
| A-011 | `Q-DET-016`, `Q-RPT-012` | 손상 점자블록 안내·신고 피드백 |
| A-012 | `Q-RPT-012`, `Q-VOI-013`, `Q-RPT-025`, `Q-RPT-026`, `Q-VOI-016`, `Q-VOI-017`, `Q-DET-018` | 자동 신고 성공·실패의 음성·진동·상태 표시와 위험 피드백 중재 |
| A-013 | `Q-RPT-005`, `Q-PRV-002`, `Q-PRV-012`, `Q-SES-014`, `Q-PRV-018`, `Q-PRV-019`, `Q-PRV-020`, `Q-PRV-021`, `Q-PRV-022`, `Q-SES-023` | 동의 단위·유효기간·상태 전이·철회·창 간 동기화 효과 |
| A-014 | `Q-DET-009`, `Q-DET-010`, `Q-RPT-008`, `Q-RPT-009`, `Q-RPT-027` | 안정화·confidence·GPS·cooldown 수치와 receipt 기준 시각 |
| A-015 | `Q-RPT-004` | 사용자 명시 신고의 필수 gate와 대체 흐름 |
| A-016 | `Q-RPT-003` | 자동 신고 대상 클래스 |
| A-017 | `Q-RPT-006`, `Q-PRV-005`, `Q-RPT-023`, `Q-PRV-018`, `Q-PRV-019`, `Q-PRV-020` | 신고 이미지 필요성·offline 원본·동의·보존·삭제 |
| A-018 | `Q-RPT-007`, `Q-RPT-008`, `Q-RPT-017`, `Q-PRV-006`, `Q-PRV-021` | 위치 필수성·품질·기관 공개·보존·철회 receipt |
| A-019 | `Q-RPT-009`, `Q-RPT-010`, `Q-RPT-021`, `Q-RPT-022`, `Q-RPT-024` | cooldown·취소 유예·idempotency·중복·재시도 semantics |
| A-020 | `Q-RPT-013`, `Q-RPT-014`, `Q-RPT-028`, `Q-RPT-029`, `Q-RPT-030`, `Q-RPT-031` | 내부 검수·기관 전달 상태 의미, 전이 권한·증거·receipt 책임 |
| A-021 | `Q-RPT-018` | 기관 수동 제출·API·외부전달 제외 선택 |
| A-022 | `Q-RPT-015`, `Q-PRV-013`, `Q-PRV-014`, `Q-PRV-015` | 관리자 역할·인증·session·감사 |
| A-023 | `Q-RPT-016`, `Q-RPT-017` | export 형식과 기관 최소 필드 |
| A-024 | `Q-NAV-002`, `Q-NAV-016` | TMAP 단일 공급자와 장애 동작 |
| A-025 | `Q-DET-017`, `Q-NAV-014`, `Q-NAV-015` | 낮은 camera advisory와 local steering 경계 |
| A-026 | `Q-NAV-013` | meter·시간·보폭 안내 우선순위 |
| A-027 | `Q-NAV-009`, `Q-NAV-010` | off-route와 재탐색 기준 |
| A-028 | `Q-NAV-011` | 도착 판정과 사용자 확인 |
| A-029 | `Q-VOI-002` | Web STT 공급자·처리 위치 |
| A-030 | `Q-VOI-005`, `Q-VOI-006`, `Q-VOI-007`, `Q-VOI-008` | MVP intent·확인·낮은 신뢰도·부정형 처리 |
| A-031 | `Q-VOI-009`, `Q-VOI-010`, `Q-VOI-011`, `Q-DET-018`, `Q-VOI-016`, `Q-VOI-017` | 위험 queue·녹음·길안내 발화의 선점·대체·재개 |
| A-032 | `Q-VOI-013`, `Q-ACC-006` | 진동 사건·패턴과 동등 대체수단 |
| A-033 | `Q-SES-002`, `Q-PLT-010`, `Q-SES-013`, `Q-SES-021`, `Q-SES-022` | 일반 로그인·공개 접근·세션 만료·다중기기 범위 |
| A-034 | `Q-PRV-004`, `Q-PRV-005`, `Q-PRV-006`, `Q-PRV-007`, `Q-PRV-008`, `Q-PRV-009`, `Q-OPS-012` | 데이터 종류별 보존·삭제와 job 운영 |
| A-035 | `Q-PLT-002`, `Q-RES-005`, `Q-RES-007`, `Q-REL-013`, `Q-SES-019`, `Q-SES-020`, `Q-SES-023`, `Q-RES-014`, `Q-RPT-023`, `Q-RPT-024` | PWA lifecycle·offline 기능·세션/queue 지속·인증 복귀·업데이트 |
| A-036 | `Q-PLT-006`, `Q-PLT-007`, `Q-PLT-008`, `Q-ACC-001` | 공식 OS·브라우저·스크린리더 지원 matrix |
| A-037 | `Q-USR-007` | 휴대폰 장착·카메라 자세 |
| A-038 | `Q-SES-004`, `Q-SES-005`, `Q-SES-006`, `Q-SES-007`, `Q-SES-008`, `Q-SES-012`, `Q-SES-015`, `Q-SES-016` | camera·GPS·mic·heading·IMU 필수성, 철회·중도 상실 전이 |
| A-039 | `Q-USR-002`, `Q-USR-003`, `Q-USR-005`, `Q-DET-011`, `Q-DET-012`, `Q-TST-007`, `Q-TST-015` | 보조 한계·사용 환경·강한 경고·현장 안전·잔여위험 |
| A-040 | `Q-DET-006`, `Q-DET-007`, `Q-ACC-008`, `Q-ACC-009`, `Q-ACC-010`, `Q-RES-009`, `Q-RES-010` | sampling·freshness·지연·배터리·데이터·thermal·용량 |
| A-041 | `Q-NAV-016`, `Q-RES-001`, `Q-OPS-014`, `Q-RES-015`, `Q-RES-017` | TMAP·detector·신고 저장 장애와 기능별 readiness/degraded mode |
| A-042 | `Q-ARC-005`, `Q-ARC-006`, `Q-OPS-007`, `Q-OPS-008`, `Q-OPS-009`, `Q-ARC-013`, `Q-RES-017`, `Q-OPS-021` | PostGIS·replica·부분실패·migration·backup·RTO/RPO·복원 |
| A-043 | `Q-PRV-013`, `Q-PRV-014`, `Q-PRV-015`, `Q-PRV-016`, `Q-PRV-017`, `Q-TST-012` | 인증·session·감사·secret·사고·보안검증 |
| A-044 | `Q-PRV-007`, `Q-PRV-008`, `Q-PRV-010`, `Q-PRV-021`, `Q-PRV-022`, `Q-PRV-025`, `Q-OPS-022` | transcript·telemetry·식별자·간접 저장·quota 최소화 |
| A-045 | `Q-REL-007`, `Q-REL-008`, `Q-REL-009`, `Q-OPS-001` | 배포 환경·도메인/TLS·승격·운영 owner |
| A-046 | `Q-GOV-003`, `Q-TST-001`, `Q-TST-014`, `Q-REL-004`, `Q-REL-005`, `Q-REL-006` | 단계별 완료·증거 결속·artifact·hash·SBOM |
| A-047 | `Q-ARC-003`, `Q-ARC-007`, `Q-REL-002`, `Q-REL-003`, `Q-REL-013`, `Q-AIML-019`, `Q-AIML-020`, `Q-AIML-021`, `Q-AIML-022`, `Q-AIML-023`, `Q-AIML-024`, `Q-AIML-027`, `Q-AIML-028`, `Q-AIML-029`, `Q-ARC-013` | API·model/config·manifest·version·session pin·rollback·release unit 호환성 |
| A-048 | `Q-OPS-001`, `Q-OPS-002`, `Q-OPS-005`, `Q-OPS-006`, `Q-OPS-010`, `Q-OPS-020`, `Q-OPS-021`, `Q-RES-016` | 소유자·지원시간·alert·runbook·kill switch·maintenance·장애 등급 |
| A-049 | `Q-OPS-014`, `Q-OPS-015`, `Q-OPS-022` | 외부 API·저장공간 quota, core 격리·제한 모드·월 비용 상한 |
| A-050 | `Q-GOV-011`, `Q-OPS-017`, `Q-OPS-018`, `Q-OPS-019` | 전환·기능 중단·서비스 종료·종료 후 데이터 |


## 9. 추가 공백→canonical 질문 추적 매트릭스

이 표는 독립 감사에서 확인한 상태기계·운영 경계 추가 공백 60개를 canonical 질문과 1:1로 연결한다. 질문의 근거는 현재 동작·계약을 설명할 뿐 정책 승인을 대신하지 않는다.

| 공백 ID | canonical 질문 ID | 추가 결정 경계 | 직접 근거 |
|---|---|---|---|
| G-001 | `Q-VOI-016` | 음성 끄기와 중대 위험 대체 채널 | `apps/web/app/_walksafe/feedback.ts` |
| G-002 | `Q-DET-018` | 동시 위험 queue 용량·TTL·교체 | `apps/web/app/_walksafe/risk-feedback-sequencer.ts` |
| G-003 | `Q-VOI-017` | 위험 TTS 반복 실패 fallback | `apps/web/app/_walksafe/risk-feedback-sequencer.ts` |
| G-004 | `Q-RES-012` | detector busy·timeout·unavailable 전이 | `apps/web/app/_walksafe/detection-availability.ts` |
| G-005 | `Q-RES-013` | detector 복구 확정·history 초기화 | `apps/web/app/_walksafe/hooks/useTwoModelRiskHistory.ts` |
| G-006 | `Q-SES-012` | camera track 종료·권한 철회 전이 | `apps/web/app/page.tsx` |
| G-007 | `Q-SES-013` | wake lock 실패·해제 동작 | `apps/web/app/_walksafe/hooks/usePwaStatus.ts` |
| G-008 | `Q-DET-019` | 위험 해제와 안전 선언 | `apps/web/app/_walksafe/detection-presence-policy.ts` |
| G-009 | `Q-USR-011` | local-only 긴급 연락처 기능 | `apps/web/app/_walksafe/hooks/useWalkSafeSettings.ts` |
| G-010 | `Q-SES-014` | 세션 사건별 frame·신고 동의 전이표 | `apps/web/app/page.tsx` |
| G-011 | `Q-PRV-018` | frame 처리·신고 저장 동의 유효기간 | `apps/web/app/_walksafe/server-v2-privacy.ts` |
| G-012 | `Q-PRV-019` | 동의 철회 중 in-flight 결과 표시 | `apps/web/app/page.tsx` |
| G-013 | `Q-PRV-020` | 철회 전 완료 신고의 삭제 경로 | `apps/web/app/page.tsx` |
| G-014 | `Q-SES-015` | 세션 중 camera revoke 데이터 정리 | `apps/web/app/page.tsx` |
| G-015 | `Q-SES-016` | GPS·mic·heading·IMU 중도 상실 matrix | `apps/web/app/_walksafe/hooks/useSensors.ts` |
| G-016 | `Q-PRV-021` | telemetry 철회 삭제 범위·receipt | `apps/web/app/api/walksafe-field-log/route.ts` |
| G-017 | `Q-PRV-022` | 추론 frame의 간접 저장 금지 범위 | `backend/app/api/detect.py` |
| G-018 | `Q-PRV-023` | 서버 전송 전 얼굴·번호판 redaction | `apps/web/app/page.tsx` |
| G-019 | `Q-PRV-024` | 신고 이미지 redacted본·원본 권한 | `apps/web/app/admin/page.tsx` |
| G-020 | `Q-SES-017` | 보행 세션 최초 상태 | `apps/web/app/page.tsx` |
| G-021 | `Q-SES-018` | 세션 재개의 starting 전이 | `apps/web/app/page.tsx` |
| G-022 | `Q-SES-019` | background 후 길안내 복구 | `apps/web/app/page.tsx` |
| G-023 | `Q-SES-020` | refresh·crash·OS kill 후 복구 범위 | `apps/web/app/_walksafe/hooks/useWalkSafeSettings.ts` |
| G-024 | `Q-SES-021` | 일반·field 세션 만료·재인증 | `apps/web/app/api/_gateway-auth.ts` |
| G-025 | `Q-SES-022` | actor 다중기기 session 정책 | `apps/web/app/api/_gateway-auth.ts` |
| G-026 | `Q-RES-014` | offline 인증 해제·복귀 UX | `apps/web/app/_walksafe/hooks/useGatewaySession.ts` |
| G-027 | `Q-SES-023` | 여러 tab·PWA window의 active owner | `apps/web/app/page.tsx` |
| G-028 | `Q-NAV-017` | 길안내 중 새 목적지 검색·전환 | `apps/web/app/_walksafe/hooks/useNavigationGuidance.ts` |
| G-029 | `Q-NAV-018` | 사용자·자동 재탐색 budget 분리 | `apps/web/app/_walksafe/navigation-request-coordinator.ts` |
| G-030 | `Q-RPT-020` | 신고 저장 동의와 자동신고 toggle 분리 | `apps/web/app/page.tsx` |
| G-031 | `Q-RPT-021` | 자동신고 전 접근 가능한 취소 유예 | `apps/web/app/_walksafe/hooks/useAutoReportV2.ts` |
| G-032 | `Q-RPT-022` | 신고 응답 유실의 unknown outcome | `backend/app/api/reports.py` |
| G-033 | `Q-RPT-023` | offline 신고 queue의 원본·정확 GPS 저장 | `apps/web/lib/offline-report-queue.ts` |
| G-034 | `Q-RPT-024` | offline 신고 replay·재시도·만료 | `apps/web/public/sw.js` |
| G-035 | `Q-RPT-025` | 자동신고 성공 피드백 채널 | `apps/web/lib/auto-report-v2.ts` |
| G-036 | `Q-RPT-026` | 신고 실패·중복·취소·결과불명 피드백 | `apps/web/app/page.tsx` |
| G-037 | `Q-RPT-027` | 사용자 신고 receipt·상태 조회 | `apps/web/app/admin/page.tsx` |
| G-038 | `Q-RPT-028` | 신고 상태 용어의 정확한 의미 | `backend/app/models.py` |
| G-039 | `Q-RPT-029` | 신고 상태 전이·재개방 권한 | `backend/app/api/reports.py` |
| G-040 | `Q-RPT-030` | 기관 영향 상태 변경의 사유·증거 | `apps/web/app/admin/page.tsx` |
| G-041 | `Q-RPT-031` | 기관 제출·receipt 책임 추적 | `backend/app/services/report_read_audit.py` |
| G-042 | `Q-AIML-019` | 모델 drop-in 호환 계약 | `model/registry/walksafe-model-registry.json` |
| G-043 | `Q-AIML-020` | threshold·runtime config 독립 버전 | `configs/walksafe_unified_epoch270_field_20260711.json` |
| G-044 | `Q-AIML-021` | 보행 세션 model·config pin | `backend/app/services/detect_v2.py` |
| G-045 | `Q-AIML-022` | 불가피한 세션 중 model 전환 초기화 | `backend/app/services/detect_v2.py` |
| G-046 | `Q-AIML-023` | 불변 model generation·원자 전환 | `backend/app/services/detect_v2.py` |
| G-047 | `Q-AIML-024` | 새 model traffic 전 readiness gate | `backend/app/api/health.py` |
| G-048 | `Q-AIML-025` | unified 실패 시 legacy fallback | `backend/app/services/detect_v2.py` |
| G-049 | `Q-AIML-026` | fallback disclosure·자동신고 허용 | `apps/web/app/_walksafe/detection-availability.ts` |
| G-050 | `Q-AIML-027` | 승격 전 known-good rollback target | `model/deployments/local-deployment.json` |
| G-051 | `Q-AIML-028` | runtime model source of truth | `backend/app/config.py` |
| G-052 | `Q-AIML-029` | class schema 변경의 breaking release | `backend/app/schemas.py` |
| G-053 | `Q-RES-015` | rollout readiness와 기능별 degraded readiness | `backend/app/api/health.py` |
| G-054 | `Q-ARC-013` | gateway session store와 replica 수 | `apps/web/app/api/_gateway-auth.ts` |
| G-055 | `Q-RES-016` | auth·rate-limit store 장애 오류 구분 | `backend/app/services/actor_rate_limit.py` |
| G-056 | `Q-RES-017` | 신고 파일·DB 부분실패와 reconciliation | `backend/app/services/report_storage.py` |
| G-057 | `Q-OPS-020` | 기능별 kill switch 통제 | `apps/web/app/_walksafe/config.ts` |
| G-058 | `Q-OPS-021` | maintenance·배포 중 active 세션 안전중지 | `deploy/systemd/walksafe-backend.service` |
| G-059 | `Q-PRV-025` | telemetry 미동의 시 최소 운영·보안 지표 | `apps/web/app/_walksafe/hooks/useFieldTestTelemetry.ts` |
| G-060 | `Q-OPS-022` | telemetry·로그 quota와 core 격리 | `apps/web/app/api/walksafe-field-log/route.ts` |
