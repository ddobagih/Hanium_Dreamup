# WalkSafe policy decisions - 2026-07-02

작성 기준: 2026-07-02 KST, 사용자 정책 결정 반영.

이 문서는 `abc.html` 정책 결정 보드의 사용자 확정값을 제품 정책으로 옮긴 기록이다. 코드 구현 완료 문서가 아니라, 구현과 검증이 따라야 할 정책 기준이다.

## 확정 정책

| ID | 항목 | 결정 |
|---|---|---|
| P-01 | 제품 1차 가치 | 점자블록 손상은 자동 신고 형태로 처리한다. 자동 신고는 사용자에게 알릴 필요가 없다. 사용자가 명시적으로 "신고해줘"라고 요청한 경우에만 TTS로 신고 안내 문구를 낸다. |
| P-02 | 자동 신고 대상 | 자동 신고 대상은 `damaged_tactile_block` 하나로 제한한다. 일반 객체, `normal_tactile_block`, `tactile_damage_area`는 신고 저장 대상이 아니다. |
| P-03 | 손상 점자블록 안내 | 손상 점자블록은 사용자의 보행을 즉시 막는 위험으로 보지 않는다. 손상 점자블록 자체에 대한 사용자 TTS/진동 안내는 하지 않는다. |
| P-04 | 신고 threshold | 우선 보수적으로 운영한다. threshold 값과 조정 근거는 별도 문서로 관리하고, 테스트 결과를 보고 사용자가 조정 결정을 내릴 수 있게 한다. |
| P-05 | GPS 없는 신고 | GPS가 없으면 자동/음성 신고 모두 저장하지 않는다. 단, 사용자가 명시적으로 신고를 요청했는데 GPS가 없으면 TTS로 위치 정보가 필요하다는 안내를 한다. |
| P-06 | 신고 이미지와 기본 정보 | report image는 사용자 기기에 저장하지 않고 서버에 저장한다. 위치, 신고자 식별자, 신고 시각 같은 기본 report 정보도 서버에 저장한다. 얼굴/차량번호 모자이크는 하지 않는다. decodable image의 EXIF/metadata 제거는 유지한다. 6개월 후 report row와 image를 자동 삭제한다. |
| P-07 | 중복 신고 | 같은 손상 지점의 반복 신고는 위치 기반으로 판정한다. 기준은 같은 `class_name`, 반경 `10m`, `captured_at` 전후 `1분`이다. 중복 후보여도 저장은 하되 duplicate tag를 붙인다. 사용자가 명시적으로 신고 요청을 했고 중복 후보면 "이미 신고가 된 상태입니다"라고 TTS 안내한다. |
| P-08 | 위험 알림 민감도 | 위험은 사용자/객체 상대속도 기반 TTC(Time To Collision)로 계산한다. 3초 이하는 STOP, 3~10초는 WARNING, 10~30초는 AWARE bucket으로 둔다. 30초 AWARE는 추천대로 사용자 발화가 아니라 내부 상태로만 쓴다. |
| P-09 | 진동 | 진동은 사용자가 즉시 멈춰야 하는 STOP 상황에서만 울린다. WARNING/INFO에서는 기본적으로 진동하지 않는다. |
| P-10 | 길안내 민감도와 진행음 | 길안내 민감도는 사용자가 `민감`, `보통`, `둔함` 중 선택할 수 있게 한다. 사용자가 제대로 경로를 따라가고 있으면 작은 진행음을 주기적으로 낸다. 진행음은 기본 on, 기본 볼륨 20%, 설정에서 off 가능으로 둔다. 진행음 음량은 TTS/위험 알림과 별도로 조절한다. |
| P-11 | 보폭/N보 | 사용자가 직접 보폭을 입력하는 방식보다, GPS 위치 이동량, 시간, step count를 이용해 자동 보정하는 방식을 우선한다. |
| P-12 | debug capture | debug/raw capture는 개발자 전용이다. 운영 기본 surface에는 노출하지 않는다. |
| P-13 | 기관 제출 | 공공기관 제출 기능은 제품 범위에 넣지 않는다. 자동 신고는 운영자 대시보드 리스트로 들어가고, 운영자가 필요하면 CSV 등으로 내보낼 수 있게 한다. |
| P-14 | AIHub189 depth | AIHub189 depthprediction은 offline ZED reference 검증으로만 쓴다. Android ARCore 최종 성능 PASS 근거로 과장하지 않는다. |
| P-15 | 데이터 보존 | report image와 위치, 신고자, 신고 시각 같은 기본 report 정보는 서버에 저장한다. 얼굴/차량번호 모자이크는 하지 않는다. 6개월이 지나면 자동 삭제한다. |
| P-16 | fake fallback | fake fallback은 demo에서만 허용한다. 실사용 경로에서는 fake fallback을 쓰지 않는다. |
| P-17 | 운영 서비스 방향 | 최종 목표는 운영 서비스 출시다. 기능 사용은 로그인 필수로 둔다. auth/RBAC/rate-limit/upload 접근 제어/audit/retention scheduler 같은 운영 보안은 출시 lane에서 구현한다. |

## P-07 설명: 중복 신고 정책

중복 신고 정책은 같은 손상 지점이 카메라에 계속 잡힐 때 같은 신고가 반복 저장되는 것을 막는 정책이다.

현재 구현은 두 층으로 나뉜다.

| 층 | 현재 상태 | 한계 |
|---|---|---|
| Android client | track/class/source key 기준 10초 client cooldown과 explicit 신고 TTS 처리 | 앱 재시작/track 변경을 넘는 최종 중복 판단은 backend 위치 기준에 맡긴다. |
| Backend | 같은 class, 반경 10m, `captured_at` 전후 1분 기준 duplicate 후보 반환 | 후보 표시와 tag는 자동화됐지만, 실제 병합/조치 판단은 운영자 검수 영역이다. |

사용자 결정에 맞춘 구현 기준:

1. backend duplicate 기준은 `class_name + 위치 10m + captured_at 전후 1분`으로 둔다.
2. Android client cooldown은 짧은 반복 업로드 억제용으로만 쓰고, 위치 기반 duplicate 최종 판정은 backend 응답을 따른다.
3. 중복 후보여도 report는 저장하고 duplicate tag를 붙인다. 실제 중복이 아닐 수 있기 때문이다.
4. 사용자가 명시적으로 "신고해줘"라고 요청한 voice 신고가 중복 후보이면 저장은 하되 "이미 신고가 된 상태입니다"라고 TTS 안내한다.
5. Android native에는 `SpeechRecognizer` 기반 "신고해줘" 버튼 경로를 두되, 실기기 mic/TTS 체감 PASS는 별도 Device evidence로 남긴다.

높이가 다른 같은 위치 문제:

- 일반 휴대폰 GPS altitude/floor 정보는 보행 환경에서 안정적인 중복 판단 근거로 쓰기 어렵다.
- 지상 보도/점자블록 MVP에서는 수평 위치 기반 중복 방지를 우선한다.
- 추후 지하상가, 육교, 고가 보행로처럼 같은 위경도에 다른 보행면이 있는 곳을 다룰 때는 `floor_label`, `route_segment_id`, `altitude_m` 같은 보조 필드를 추가한다.

## P-06/P-15 report image와 보존 정책

사용자 결정:

- report image는 서버에 저장한다.
- 위치, 신고자 식별자, 신고 시각 같은 기본 report 정보도 서버에 저장한다.
- 얼굴/차량번호 모자이크는 하지 않는다.
- 6개월 이후 report row와 image를 자동 삭제한다.

해석:

- "모자이크 안 함"은 제품 report 원본 저장 정책이다.
- EXIF 제거/재인코딩은 얼굴·차량번호를 가리는 시각적 모자이크가 아니므로 유지한다.
- 공개 evidence, 외부 공유 자료, 로그에는 실제 주소/전화번호/secret 같은 민감 정보 노출을 계속 피한다.
- "누가 신고했는지"는 로그인 필수 정책에 맞춰 로그인 user id를 기준으로 저장한다. 현재 Android는 로컬 user id 입력 없이는 주요 기능을 막고, `/reports/v2` metadata/export에는 `reporter_user_id`를 남긴다. 운영 auth/RBAC는 출시 lane에서 별도 구현한다.

## P-08/P-09 권장 위험 알림 bucket

사용자 제안인 3초, 10초, 30초는 TTC 기반 정책으로 해석한다. TTC bucket은 "지금 상태가 계속되면 충돌 또는 경로 차단까지 몇 초 남았는지"를 구간으로 나눈 값이다.

예: 2m 앞 장애물로 사용자가 초속 1m로 걸어가면 TTC는 약 2초라서 STOP이다. 8m 앞 장애물로 초속 1m면 약 8초라서 WARNING이다. 움직이는 객체는 객체가 다가오는 속도까지 더해 상대속도로 계산한다.

| Bucket | 조건 후보 | 사용자 안내 | 진동 |
|---|---|---|---|
| STOP | 충돌/접촉 예상 시간이 3초 이하, 또는 전방 route-blocking 장애물이 매우 가까움 | "멈추세요." | 울림 |
| WARNING | 3초 초과 10초 이하 | "속도를 줄이세요." 또는 "전방 장애물 주의." | 없음 |
| AWARE | 10초 초과 30초 이하 | 기본 TTS 없음. 내부 상태/화면/debug에만 사용 | 없음 |

주의:

- 현재 모델은 traffic light 객체는 볼 수 있지만 빨간불/초록불 색상 판단은 별도 classifier 또는 신호 데이터가 필요하다.
- 정적 장애물은 객체가 실제로 다가오는 것이 아니라 사용자가 걸어가면서 상대거리가 줄어든다. 따라서 사용자 속도와 거리로 TTC를 계산한다.
- 30초 bucket은 너무 자주 말하면 보행을 방해할 수 있으므로 사용자 발화에는 쓰지 않고 내부 상태/진행 판단에 쓴다.

## 신호등 색상 STOP DEFERRED 조사 기준

현재 확인된 상태:

- 현재 class order에는 `traffic light` 객체 클래스 하나만 있다.
- `traffic_light_red`, `traffic_light_green`, `traffic_light_yellow`, `pedestrian_signal_red`, `pedestrian_signal_green` 같은 색상/보행자 신호 클래스는 없다.
- 따라서 현재 모델은 "신호등이 있다"는 객체 감지만 할 수 있고, "빨간불이라 멈춰야 한다"는 판단은 할 수 없다.

DEFERRED 권장 방향:

1. MVP에는 빨간 신호등 STOP을 포함하지 않는다.
2. 후속 기능은 기존 `traffic light` detector 뒤에 crop 기반 색상 classifier를 붙이는 2단계 구조를 우선 검토한다.
3. 라벨 정책은 차량 신호와 보행자 신호를 섞지 않고 `pedestrian_signal_red/green/unknown`처럼 제품 의미가 분명한 쪽으로 둔다.
4. 불확실하거나 가려진 신호등은 STOP이 아니라 unknown으로 둔다.
5. 테스트는 빨강만 STOP, 초록/노랑/unknown/야간/역광/가려짐은 STOP 금지로 잠근다.

## P-10 길안내 민감도 후보

| 모드 | off-route 기준 후보 | 재탐색 확인 샘플 | 진행음 후보 | 용도 |
|---|---:|---:|---:|---|
| 민감 | 20m | 2 | 5초마다 | 초행길, 길을 잃기 쉬운 상황 |
| 보통 | 35m | 2 | 8초마다 | 기본값 후보 |
| 둔함 | 50m | 3 | 12초마다 | GPS가 튀는 도심/실내 근처 |

진행음 정책:

- route active, off-route 아님, 위험 알림 없음, TTS 재생 중 아님일 때만 낸다.
- TTS와 다른 별도 볼륨 값을 둔다.
- 기본값은 on, 볼륨 20%로 시작하고 사용자가 끌 수 있어야 한다.

## P-17 로그인 필수 정책

- WalkSafe 주요 기능은 로그인한 사용자만 사용할 수 있게 한다.
- report에는 로그인 user id를 `reporter_user_id`로 남긴다.
- Android native는 현재 로컬 user id 입력 gate로 depth start, route/search, explicit report를 막는다. 이 gate는 운영 인증이 아니라 runtime policy guard다.
- auth/RBAC/rate-limit/upload 접근 제어/audit은 운영 출시 lane에서 구현한다.

## P-14 설명: AIHub189 depth 검증의 의미

AIHub189 `DepthPrediction` 데이터는 ZED 스테레오 카메라에서 나온 disparity/depth reference다.

검증할 수 있는 것:

- nested zip에서 `left/disp16/confidence/conf` frame 매칭
- disparity를 meter 단위 depth로 바꾸는 계산
- bbox 내부 depth sample 추출
- invalid/outlier 제거, median/p20/p80, valid ratio 계산
- `ceil(distance / step_length)` N보 변환
- 3/10/30초 같은 risk policy offline replay

검증할 수 없는 것:

- Android ARCore Raw/Full Depth가 실제로 정확한지
- Android preview bbox와 ARCore depth 좌표가 같은 객체를 가리키는지
- 목걸이 착용, 흔들림, 실외 조도, TTS/진동 체감
- 최종 안전성 PASS

따라서 문서에는 `source_kind=offline_zed_reference`, `reference_not_ground_truth=true`, `arcore_pass=false`를 유지한다.

현재 판정:

- "AIHub189 원본 데이터로 Android ARCore 깊이 기능을 테스트했다"라고 말하면 안 된다.
- 맞는 표현은 "AIHub189 형식의 offline ZED reference evaluator를 구현했고, 실제 `Depth_001~005.zip` 원본 전체에 대해 generated probe bbox 기반 offline validation을 돌렸다"이다.
- evidence: `docs/evidence/aihub189_depthprediction_original_full_20260702.md`
- full run을 하더라도 결과는 Android ARCore PASS가 아니라 offline reference 결과다.

## P-15 보존 정책

사용자 결정:

- report image는 서버에 저장한다.
- 위치, 신고자, 신고 시각 같은 기본 report 정보도 서버에 저장한다.
- 얼굴/차량번호 모자이크는 하지 않는다.
- 6개월 이후 report row와 image를 자동 삭제한다.

구현 기준:

- report row와 upload image는 같은 retention bucket으로 묶는다.
- 기본 retention은 모든 실제 report에 180일을 적용한다.
- fake/demo/debug capture는 더 짧은 기간을 둘 수 있지만, 실제 report보다 길게 보존하지 않는다.
- 자동 삭제 job은 삭제 전 dry-run summary, backup/rollback, audit actor를 남겨야 한다.

## 추가 결정이 필요한 항목

| ID | 질문 | 추천 기본값 |
|---|---|---|
| Q-01 | 위치 기반 중복 방지 반경과 시간창 | 확정: `10m / 1분` |
| Q-02 | voice 신고가 중복 후보일 때 처리 | 확정: 저장 + duplicate tag + "이미 신고가 된 상태입니다" TTS |
| Q-03 | 30초 TTC bucket 처리 | 확정: 내부 상태로만 사용 |
| Q-04 | 길안내 진행음 기본값 | 확정: 기본 on, 볼륨 20%, 설정에서 off 가능 |
| Q-05 | "누가 신고했는지" 식별자 | 확정: 로그인 필수, 로그인 user id |
| Q-06 | 180일 자동 삭제 job enable 방식 | 확정: dry-run, backup/audit 확인 후 운영에서만 enable |
| Q-07 | 빨간 신호등 STOP | DEFERRED: 현재 `traffic light`는 객체 클래스이며 색상 구분 여부 조사/데이터 확인 필요 |
