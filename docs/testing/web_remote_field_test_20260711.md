# Web/PWA 원거리 현장 테스트 실행서

> `LEGACY_REFERENCE_ONLY` — 이 문서는 2026-07-11 Web/PWA 현장 실행의 역사 기록이다. 현재 제품 시험 절차로 실행하지 않는다. 원격 tunnel과 Web field-service launcher는 시작 전에 종료 코드 78로 차단되어 있으며, 과거 공개 URL과 기존 설치 PWA가 실제로 폐기됐는지는 별도 외부 증거가 없어 `NOT_VERIFIED`다.

- 기준일: 2026-07-16 KST
- 당시 대상: 외출용 Android 휴대폰의 모바일 Chrome
- 당시 주 앱: Web/PWA(현재 제품 아님)
- 목적: 실제 epoch270 13-class 모델, 접근 경고, 음성 TMAP 길안내, 점자블록 보조 안내, 손상 점자블록 자동 신고를 실외에서 검증한다.

## 현재 시험 환경

PC에서는 `walksafe-field-20260711.service`가 다음 프로세스를 함께 관리한다.

- Next.js production Web/PWA: `127.0.0.1:3000`
- FastAPI backend와 epoch270 단일 모델: `127.0.0.1:8000`
- local STT/intent server: `127.0.0.1:9001`
- Cloudflare quick tunnel: 임시 HTTPS 주소
- PostGIS: Docker `walksafe-postgis`

현재 공개 주소는 `artifacts/cloudflare-field-test/current-public-url.txt`, 현재 실행 로그는
`artifacts/cloudflare-field-test/current/`에서 확인한다. quick tunnel이 재시작되면 주소가
바뀌므로 출발 직전에 다시 확인한다. 접근 토큰은 문서나 로그에 기록하지 않는다.

`scripts/check_walksafe_remote_field_browser_20260711.py`는 headless Chromium에 fixture 카메라,
가짜 GPS와 모바일 viewport를 주입하는 **통제 합성 E2E**다. 서버 동의·탐지·JSONL 계약을
점검하지만 실제 휴대폰 camera/GPS/TTS/진동이나 보행 Field 근거로 쓰지 않는다. 아래 절차의
실폰 PASS는 합성 checker 결과와 별도로 기록한다.

```bash
systemctl --user status walksafe-field-20260711.service --no-pager
cat artifacts/cloudflare-field-test/current-public-url.txt
```

PC 전원, 인터넷, Docker와 위 user service를 시험이 끝날 때까지 유지한다. PC 재부팅
또는 transient unit 삭제 뒤 service가 없다면 프로젝트 루트에서 다음과 같이 다시
시작한다. 기존 unit이 실행 중일 때는 중복 실행하지 않는다.

```bash
systemctl --user stop walksafe-field-20260711.service 2>/dev/null || true
systemd-run --user --unit=walksafe-field-20260711 \
  --property=Restart=on-failure --property=RestartSec=5s \
  --property=StartLimitIntervalSec=300s --property=StartLimitBurst=3 \
  --property=TimeoutStopSec=20s \
  --working-directory=/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715 \
  /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715/scripts/run_walksafe_remote_field_stack_20260711.sh
```

재시작 뒤 `active (running)`과 새 URL을 확인한다. field token 원문은 ignored local
file `apps/web/.env.local`의 `WALKSAFE_FIELD_TEST_TOKEN`에만 두고 문서·URL·현장 로그에
넣지 않는다.

## 적용된 기능 계약

| 기능 | 현장 버전 동작 |
|---|---|
| 객체 인식 | epoch270 SHA-256 `a38857e...a5669` 단일 모델, img768, 13개 class, fake fallback 없음 |
| 거리/접근 | 지원 센서 depth가 없으면 bbox 크기 변화와 class 기준 높이의 단안 추정으로 거리·접근 추세를 계산한다. 이는 계측 depth가 아니며 화면에도 추정값으로 구분한다 |
| 경고 | 모든 후보가 3 frames **AND** 700ms 안정화와 경로 조건을 거친다. TTC 기반 STOP/high는 trusted metric depth가 있을 때만 허용하며 bbox-scale TTC 단독으로 high/STOP을 만들지 않는다. 현재 WebXR depth는 위험 근거로 fail-closed한다. |
| 손상 신고 | `damaged_tactile_block`만 신뢰도 0.70 이상, GPS 정확도 15m 이내, 동일 공간 후보 3개 연속 프레임일 때 자동 저장. 자동 신고는 TTS·진동 없음 |
| 길안내 | 음성 목적지 검색과 후보 선택 후 TMAP `STAIR_AVOID` 보행 경로를 사용한다 |
| 비계량 카메라 보조 경고 | 실제 depth 없이도 후면 camera·detector·활성 TMAP/GPS·foreground gate와 서로 다른 연속 3프레임·700ms를 통과하면 low 좌/정면/우 안내만 제공한다. 거리·걸음·STOP/high·local steering·경로 변경·신고 권한은 없고 TMAP이 계속 경로 권한을 가진다 |
| 신호등 | 신호등 존재만 탐지한다. 보행 신호 색상과 횡단 가능 여부는 판단하지 않는다 |
| 현장 기록 | 인증 세션 동안 1초마다 metadata JSONL 저장. telemetry에는 원본 카메라·음성을 넣지 않는다. 단, 손상 점자블록 자동 신고가 gate를 통과하면 신고 증빙 JPEG가 위치·metadata와 함께 report로 자동 저장된다. 그 외 이미지는 사용자가 동의하고 버튼을 누른 수동 스냅샷만 별도 저장 |

## 출발 전 10분 점검

1. 외출용 폰의 모바일 데이터, GPS, Chrome을 켜고 절전·데이터 절약 모드를 끈다.
2. PC에서 위 service가 `active (running)`인지 확인한다.
3. PC에서 `current-public-url.txt`의 주소를 폰 Chrome으로 연다.
4. 전달받은 현장 토큰을 한 번 입력한다. 토큰은 12시간 HttpOnly 세션으로 바뀌며 URL에 붙지 않는다.
5. 전송 항목을 읽고 `전송 항목 확인·서버 탐지 동의`를 누른다. 이 처리 동의 전에는 카메라를 켜도 server-v2 프레임을 전송하지 않는다.
6. 자동 신고까지 시험할 때만 별도 보존 고지를 읽고 `저장 항목 확인·자동 신고 동의`를 누른다. 보조 경고 자체에는 신고 권한이 없다.
7. JSONL 진단 기록이 필요할 때만 별도 고지를 읽고 `고지 확인·수집 동의`를 누른다. 이 동의는 프레임 처리·신고 저장 동의를 대신하지 않는다.
8. 그 뒤 `카메라/GPS 권한 요청`을 누르고 카메라와 정확한 위치를 허용한다. 음성 시험을 할 때만 마이크를 허용한다.
9. 상단에 `unified-v2 서버 모드`가 표시되는지 확인한다. `데모`, `fake`, `모델 연결 대기`면 출발하지 않는다.
10. 정지 상태에서 사람 또는 차량을 비춰 bbox와 탐지명이 나타나는지 확인한다.
11. 음성 명령 버튼을 눌러 `목적지 서울역으로 설정해` 같은 실제 목적지를 말한다.
12. 후보가 여러 개면 `1번 선택`, 이어서 `길 안내 시작해`라고 말한다.
13. 원본 수동 저장이 필요할 때만 `동의 후 활성화`를 누른다. 원본 저장을 원하지 않으면 맞음/틀림/스냅샷 버튼은 누르지 않는다. 별도 신고 저장 동의 후 damage 자동 신고 gate를 통과한 프레임만 신고 증빙 이미지로 저장된다.

## 보행 중 수행

- 휴대폰은 목걸이 방식으로 전방과 바닥이 함께 보이도록 고정하고 Chrome을 foreground로 유지한다.
- 출발, 직진, 회전, 일시 정지, 경로 이탈 후 복귀를 각각 한 번 이상 수행한다.
- 사람, 자전거, 차량, 킥보드, 단차·파손 보도처럼 안전하게 촬영 가능한 대상을 지나며 탐지와 경고 시점을 기록한다.
- 정상 점자블록이나 기존 위험으로 승격되지 않은 장애물이 3프레임·700ms 이상 안정적으로 보일 때 low 좌/정면/우 보조 경고가 나오고, TMAP 안내와 경로는 그대로 유지되는지 확인한다.
- 손상 점자블록은 안전한 위치에서 2초 이상 화면에 유지해 자동 신고가 조용히 완료되는지 확인한다.
- 명백한 정탐은 `맞음`, 오탐은 `틀림`, 중요한 장면만 `스냅샷`으로 기록한다.
- 앱 경고만 믿고 도로에 진입하지 않는다. 첫 현장시험은 동행자와 수행하고 실제 보행 신호·주변 소리를 우선한다.

## 현장에서 기록할 판정

| 항목 | PASS 기준 |
|---|---|
| 13-class 관찰성 | 보이는 대상의 bbox/class가 지속 표시되고 fake source가 없음 |
| 접근 경고 | 멀리 있거나 경로 밖인 대상은 과도하게 경고하지 않고, 가까운 경로 방해 대상은 안정화 후 경고 |
| STOP 진동 | STOP에서만 진동. WARNING·자동 신고에서는 진동 없음 |
| 음성 목적지 | 발화가 목적지 검색으로 변환되고 후보 선택 가능 |
| TMAP 안내 | 경로 요약, 회전 안내, 진행, 이탈·재탐색이 현재 GPS에 맞게 변함 |
| 비계량 보조 경고 | `CAMERA_NON_METRIC_ADVISORY`, low 좌/정면/우만 허용. 거리·걸음·STOP/high·local steering·안전 경로·경로 변경·신고가 없어야 하며 TMAP 안내가 우선 |
| 손상 자동 신고 | 1프레임에는 신고하지 않고 3프레임 이후 조용히 완료. 관리자 목록에는 `trigger=auto`로 저장 |
| 신호등 | 존재 탐지만 표시하고 색상·횡단 가능 판단을 말하지 않음 |

## 귀가 후 회수와 분석

사용자가 `테스트하고 왔어`라고 말하면 PC의 최신 JSONL과 수동 판정을 분석한다. 직접 확인할 때는 다음 명령을 사용한다.

```bash
RUN_DIR="$(readlink -f artifacts/cloudflare-field-test/current)"
python3 scripts/summarize_web_field_session_20260711.py \
  --log-root "$RUN_DIR/web-field-logs" \
  --output-root "$RUN_DIR/web-field-summary" \
  --require-non-metric-advisory \
  --expected-source-commit "$(git rev-parse HEAD)"
```

수동 맞음/틀림/스냅샷의 JPEG와 JSON은 같은 run의
`web-test-captures/YYYY-MM-DD/`에 저장된다. 자동 damage 신고 증빙 이미지는 PostGIS
report의 `image_path`와 backend upload 경로에서 관리한다.

분석 범위는 class별 탐지 수·신뢰도, request latency p50/p95, GPS 이동, 위험 경고,
비계량 보조 경고의 활성 record·활성화 횟수·방향·TMAP 동시 활성·계약 위반, 길안내 상태,
자동 신고 상태다. telemetry는 `risk_active`와 별도로 `non_metric_advisory_active`, tier,
방향, 문구, `non_metric_advisory_metric=false`, `non_metric_advisory_tmap_authoritative=true`,
`non_metric_advisory_reports_allowed=false`, 실행 시 GPS 정확도 상한, 연속 frame 수와 안정화 ms를 남긴다.
strict 요약은 server-v2·camera·TMAP navigation·foreground/online·유효 GPS·3프레임/700ms와
서버 `WALKSAFE_SOURCE_COMMIT`/`BUILD_ID`가 일치해 기록된 정확한 source commit을 모두 확인한다.
연속 원본 영상은 남지 않으므로 육안 오탐/누락은
수동 `맞음/틀림/스냅샷` 기록과 함께 해석한다. damage 자동 신고의 단일 증빙 이미지는
관리자 검수용 report image로 남는다.

## 실폰과 합성 fixture 증거 경계

- 실폰 Field에서는 Chrome의 실제 권한 화면에서 직접 허용하고 실제 후면 camera와 GPS를 사용한다.
- `Browser.grantPermissions`, `Emulation.setGeolocationOverride`, fixture camera 주입, 모바일 viewport 흉내를 사용한 실행은 통제 합성 E2E로만 표시한다.
- 화면·telemetry로 advisory 상태와 TMAP 동시 활성은 자동 확인할 수 있지만 실제 TTS 청취, TMAP 발화 비선점, 진동 체감, 오탐·미탐과 보행 안전은 사람이 실폰에서 확인한다.
- checker가 sessionStorage의 `{actor_id, session_id}` JSON을 검증하고 서버 JSONL을 찾더라도 이는 합성 입력의 actor/session 결합 근거이며 실폰 근거로 승격되지 않는다.

## 현재 미검증·제한

- 실제 외출용 폰 camera/mic/TTS/진동 보행 E2E는 이 체크리스트로 처음 수행한다.
- Cloudflare quick tunnel은 임시 시험망이지 실서비스 도메인이나 가용성 보장이 아니다.
- 단안 bbox 거리는 대략적 추정이며 실제 depth sensor 계측이 아니다.
- TMAP 경로는 계단 회피 우선 경로이고 점자블록 전체 위치를 알고 계산한 경로가 아니다.
- 현재 validation의 약한 class와 대표 샘플 실패가 있어 모델은 안전 승인 상태가 아니다. 특히 `curb_step`, `uneven_sidewalk`, 일부 `truck` 누락을 현장에서 집중 기록한다.
- 시험 종료 후 service를 멈출 때만 다음 명령을 사용한다.

```bash
systemctl --user stop walksafe-field-20260711.service
```
