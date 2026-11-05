# Android 기기점검 객체 인식 제한 — AI 협업 보고서

- 문서 ID: `WM-DEVICECHECK-MASK-PARITY` (이 문서 안의 추적용 식별자)
- 기준 시각: **2026-11-05 23:55 KST**
- 상태: **OBJECT CHECK PASSED ON DEVICE — 객체 검증·지속 실행·최종 승인/적용 성공. 전체 attempt는 호출어 UNAVAILABLE로 FAIL**
- 대상: 사용자 지정 새 ZIP에서 빌드한 Android 테스트 앱
- 성격: 개발·진단 인계 기록. 정식 제품 정책, 출시 승인 또는 안전성 인증이 아니다.
- 진행 이력: `device-check-object-recognition-history.md`

## 1. AI가 먼저 알아야 할 결론

1. 현재 객체 인식의 `제한`을 GPU 초기화 실패나 단순 속도 부족으로 다시 진단하면 안 된다. 그 문제들을 수정한 후 **실제 GPU 초기화·추론·임시 성능 평가까지 통과**했다.
2. 수정 전 마지막 직접 거절 조건은 **FastSAM의 CPU 기준 마스크와 GPU 결과 마스크의 packed bit 완전 일치 실패**다. 새 APK에서는 아래 조건부 수치 마스크 동등성을 구현했다.
3. 수정 전 APK `2dba03…`에서는 coco_11149의 XOR popcount1로 거절됐다. 새 설치본 `9d5424…`의 사용자 수동 점검은 모든 내장 fixture의 PRIMARY/AUXILIARY STRICT, SOAK, QUALIFICATION, DRAIN, PREPARED, ADOPTION, PUBLISH를 성공했다.
4. 새 실측은 padding0/유효 XOR1, reference 면적14777/actual14776, intersection14776/union14777, IoU0.9999323272653448이다. 경계 조건1개와 자체 raw sign1개를 실제 검증했고 최대abs logit은2.86102294921875E-6이다. 작은 수치 경계 차이라는 구현 계약을 이 실행에서 확인했다. 일반 현장 안전성을 보증하는 자료는 아니다.
5. 사용자는 먼저 **차이를 정량 분석한 뒤 합리적인 허용 기준 검토**를 선택했고, 이어 **앱을 변경해 기기점검 통과를 목표로 진행**하라고 요청했다. 현재는 구조/원시 수치 검증을 유지하는 근거 있는 마스크 동등성 수정과 회귀 시험·APK 빌드를 진행하는 범위다. 무조건 통과나 특정 fixture 예외를 허용한 것은 아니다.
6. raw tolerance/추론 성능/모델/precision과 구조·등록·해제 gate는 유지했다. 마스크 bit 완전 동일은 §5의 좁은 조건부 동등성으로 수정했다. **객체의 최종 검증·지속 실행·승인·적용은 실기기 성공**, 전체 attempt는 WAKE_PHRASE_UNAVAILABLE로 FAIL이다. 전체 저장/온보딩/현장 안내 성공을 주장하지 않는다.
7. 호출어 인식 품질은 사용자가 이번 수정 대상에서 제외했다. 호출어를 가짜 통과로 만들거나 필수 gate를 삭제한 것은 아니다.
8. 단일 작업자가 수정·47건 host 시험·정상 APK 빌드를 완료했고 부모가 데이터 보존 업데이트했다. 사용자가 직접 점검했고 부모는 현재 APK/실행 경계의 UID 전체 typed trace를 읽기 전용으로 수집하여 성공 단계를 확인했다. 추가 점검이나 호출어 수리는 시작하지 않았다.

## 2. 현재 전달·설치 기준선

### 입력과 프로젝트

- 사용자 지정 archive: `Hanium_Dreamup-codex-walkmate-temp-integration-20261026.zip`
- archive SHA256: `a14f6717bc5daaa241105bb37f5cedd568ec90f638b2f34634a68a4a9683175d`
- 이 저장소는 해당 ZIP의 격리 작업 복사본이다. `.git` 메타데이터가 없어 commit hash를 만들거나 추정하지 않았다.
- 이전 수정 앱과 이번 새 snapshot의 소스·정책을 섞지 않았다. 과거의 측정 생략/`NOT_REQUIRED` 패치를 자동 이식하지 않았다.

### 조원 AI 주의 — 원본과 테스트 설치본의 이름·패키지·서명 변경

이번 작업은 원본 앱을 삭제하거나 덮어쓰지 않고 함께 설치하기 위한 **별도 테스트 앱**으로 진행했다. 객체 인식 수리와 별도로 아래 배포 식별자 변경이 포함되어 있다.

- 원본 applicationId: `kr.co.hanium.dreamup.walksafe`.
- 테스트 applicationId: `kr.co.hanium.dreamup.walksafe.latesttest`. debug applicationIdSuffix를 사용했으며 Kotlin namespace/Activity 경로와 production release applicationId는 유지했다.
- 테스트 런처 표시 이름: **최근 walkmate 테스트**. 원본 앱 이름을 이 이름으로 변경한 것이 아니라 별도 테스트 앱의 표시 이름이다. 원본 표시 이름은 이 문서에서 추정하지 않는다.
- 원본 인증서 SHA256: `16baa4cdd7f901659699095848ef67f917e1ee4339879c16cea8ee32734b516a`.
- 테스트 전용 인증서 SHA256: `c9d26f6848e9b7dd0b53c2800debb2af683cdaa0fa36962001822a09bd07fdbf`.
- 서명 변경만으로 동일 applicationId 앱을 함께 설치할 수 있는 것은 아니므로 **패키지와 서명을 함께 분리**했다. 기존 production 앱/서명 키/데이터를 교체하지 않았다.
- 후속 테스트 APK는 같은 `.latesttest` 패키지와 테스트 인증서를 유지해야 현재 테스트 앱에 데이터 보존 업데이트할 수 있다. 원본 패키지나 다른 인증서로 빌드한 APK를 현재 테스트 앱의 호환 업데이트로 취급하지 않는다.
- 인증서 지문은 공개 검증용 식별자일 뿐이다. 전용 keystore·private key·비밀번호는 보고서/공유 ZIP/GitHub에 포함하지 않는다. 조원 환경에서 이 키를 갖고 있다고 가정하지 않는다.
- 조원이 원본 제품용 변경을 검토할 때는 **모델/점검 로직 수정**과 **테스트 설치용 identity/이름/로컬 빌드 설정**을 구분해야 한다. 테스트 identity를 production release 정책으로 자동 승격하지 않는다.

### 현재 설치된 테스트 산출물

- workspace 상대 경로: `device-check-mask-parity-fix/Walkmate-Latest-Test-debug.apk`
- SHA256: `9d5424e15bb7ef099c9f38c7dafbde84bb920958667aae1f2ede8f416debd789`
- 표시 이름: **최근 walkmate 테스트**
- applicationId: `kr.co.hanium.dreamup.walksafe.latesttest`
- 전용 인증서 SHA256: `c9d26f6848e9b7dd0b53c2800debb2af683cdaa0fa36962001822a09bd07fdbf`
- 기존 앱과 별도 패키지/서명으로 공존한다. 업데이트는 동일 테스트 패키지의 `install -r`로 수행했고 삭제/데이터 초기화하지 않았다.
- 설치 전후 테스트 앱 및 기존 앱의 preference 파일 각 4개 해시 보존을 확인했다. 이 제한된 검증을 모든 앱 데이터가 영구 불변이라는 주장으로 확대하지 않는다.
- 정상 실행 후 자격정보 재입력 없이 기기점검 시작 화면으로 복귀했다. 이를 별도 내부 인증 enum 계측 또는 전체 온보딩 완료와 혼동하지 않는다.

### 실제 모델과 일반 gate

- 기본 모델: `walkmate_yolo26n_21cls_768_float32.tflite` — YOLO26n, 21개 클래스, 입력 768.
- 추가 모델: `fastsam_s_768_float32.tflite` — class-agnostic mask proposal.
- 이번 수리 과정에서 모델/가중치/입출력 계약을 교체하지 않았다. 기존 연구의 "YOLO21" 표현은 여기서 제품 모델 버전명이 아니다.
- quick start=false, guidance bypass=false, post-login device check required=true, server STT=false.
- DEBUG 실험에서 고정 계약을 검증한 AUXILIARY/GPU OPEN만 최대 60,000ms. CPU/PRIMARY/RELEASE OPEN 기본 20,000ms 유지.
- 해당 DEBUG GPU trial 외부 예약은 최대 85,000ms지만 기존 caller/phase/cleanup reserve/전체 300초 한도를 연장하지 않는다.
- 실제 warm 성능/추론/결과 신선도/thermal/정확도/텐서/권한·세션/해제 조건은 유지했다. 60초는 초기화 실험 상한이며 실측 성공 보장값이 아니다.

## 3. 상태 표시 항목 — 사용자가 확인한 현재 계약

순서와 이름은 `DeviceCheckItems`에서 공유한다.

1. 거리 API 지원
2. 객체 인식(내장 이미지)
3. GPS·위치 서비스
4. 현재 위치 수신
5. 한국어 음성 합성
6. 기기 내 호출어 인식
7. 기기 상태

표시 항목과 필수 통과 조건은 별개다. 예를 들어 API 지원 확인은 실제 거리 추정 성공이 아니고, 시스템 위치 서비스 활성화는 신선하고 유효한 현재 위치 수신이 아니다. 현재 위치 수신 행을 연결하면서 그것을 새로운 필수 gate로 추가하지 않았다.

## 4. 지금까지 해결·확인한 경로

### A. 모든 항목이 `확인 중`으로 남던 표시 계약 불일치 — 수정·설치됨

- producer는 새 7개 이름을 출력했으나 native panel은 예전 6개 이름으로 조회했다.
- 누락 key의 기본값이 `확인 중`이라 실제 terminal FAIL 뒤에도 검사 중처럼 보였다.
- `DeviceCheckItems` 공통 라벨/순서와 호출어 활성 행 override를 연결했다. 실제 상태 계산을 가짜 PASS로 바꾸지 않았다.

### B. 현재 위치 수신 `수신 대기` — 전용 probe 연결 수정·실기기 통과 관측

- 위치 권한/시스템 위치 기능은 허용돼 있었지만 전용 위치 점검 wrapper에 production 호출이 없었다.
- 일반 길안내 위치 subscription과 전용 점검 결과를 구분했다.
- 허가·journal/storage·runtime lease 확인 이후 실제 attempt에서 시작하도록 연결하고, attempt당 1회 latch와 취소/timeout/새 attempt/늦은 callback 보호를 추가했다.
- 이후 실제 화면에서 현재 위치 수신 통과를 관측했다. 좌표나 사용자 이동 경로를 이 문서에 포함하지 않는다.

### C. 기본 모델 GPU 초기화 차단 — 구체 코드 수정·실제 GPU 추론 관측

- optional serialization cache와 연결된 `CompatibilityList` false가 실제 GPU 생성 전에 전체 로드를 중단했다.
- 비활성 cache도 non-null 객체여서 이전 외부 veto가 작동하던 경우를 RED로 재현했다.
- advisory 검사는 optional cache parameter preparation 안으로 이동했다. 실패하면 cache unavailable로 보고하고, 기존 uncached GPU 생성 경로를 실제로 실행한다.
- backend를 CPU로 몰래 바꾸거나 GPU 메타데이터를 합성하지 않았다. 이후 GPU가 포함된 기본 모델의 실제 추론 완료를 확인했다.

### D. FastSAM GPU의 20초 cold-open 종료 — 직접 원인 확인·한정 수정 후 초기화 성공

- 초기 main-PID 로그만으로는 종료되는 `:vision_runtime` child의 원본 원인을 놓쳤다. 이후 앱 UID + 현재 APK 실행 시각으로 native child 로그까지 수집했다.
- 실제 `SERVICE_WATCHDOG/TIMEOUT`과 parent await timeout으로 20초 OPEN 제한 종료를 확인했다. 단순 SIGKILL 기록만으로 원인을 확정한 것은 아니다.
- nested timeout/cancellation을 일반 `NATIVE_FAILED`로 오분류하던 진단도 수정했다.
- DEBUG의 pinned FastSAM GPU 초기화에만 60초 실험 상한을 적용하고, 숨은 45초 caller trial clamp도 맞췄다. 일반 성능 gate를 낮춘 것이 아니다.
- 후속 실기기에서 최초 GPU OPEN 완료 **23.6초**, 이후 warm OPEN **247~254ms**를 관측했다.
- 같은 실행의 통과한 임시 GPU 조합에서 FastSAM 처리 최대값 **112~123ms**를 관측했다. 이는 해당 표본의 provisional 측정이지 장시간 성능 보장/전체 정확도 통과가 아니다.

### E. 호출어 실패에 따른 객체 점검 중간 취소 — 완료 순서 분리

- 호출어 실패가 shared attempt를 즉시 폐기해 객체 후보 검색을 중단시키는 경우가 있었다.
- 동일 권한/binding의 RUNNING attempt에서 이미 활성인 composite 객체 점검만 끝까지 관측한다.
- 호출어 관측은 계속 UNAVAILABLE이며 feature gate는 닫히고 결과 저장도 없다. 객체가 끝난 뒤 필요한 진실한 최종 FAIL을 게시한다.
- background, 다른 blocking failure, session/storage, deadline 및 해제 보호는 유지한다. 호출어 모델/인식 기준 자체는 수정하지 않았다.

### F. 수정 전 막혔던 직접 거절 — CPU/GPU 마스크 완전 동일 실패

최신 실행에서 선택된 임시 조합은 기본 GPU:1 / 추가 GPU:1이었다. 선택은 `SELECTED_UNVERIFIED`이며 정식 최종 통과 증거가 아니다.

수정 전 APK2dba03의 직접 관측 로그(실패 이력 보존):

```text
event=JOINT_FINAL stage=PRIMARY_STRICT outcome=SUCCEEDED kind=NONE reason=UNCLASSIFIED fixture=coco_11149
event=JOINT_FINAL stage=AUXILIARY_STRICT outcome=STARTED kind=NONE reason=UNCLASSIFIED fixture=coco_11149
event=JOINT_STRICT role=AUXILIARY fixture=coco_11149 predicate=MASK_BIT_PATTERN mismatch_count=1 max_abs=NA
event=JOINT_FINAL stage=AUXILIARY_STRICT outcome=FAILED kind=ACCURACY_UNCONFIRMED reason=UNCLASSIFIED fixture=coco_11149
```

- 앞선 `coco_785`, `coco_872`의 primary/auxiliary strict 검사는 성공했다.
- `coco_11149`에서는 primary strict가 성공하고 auxiliary mask bit 비교에서 거절됐다.
- 이후 PREPARED/ADOPTION 실패와 CAMERA_PIPELINE_UNAVAILABLE로 이어졌다. 이 후속 메시지를 또 다른 GPU 초기화 오류로 재해석하지 않는다.
- raw 불일치는 별도 RAW_COUNT/RAW_NONFINITE/RAW_TOLERANCE 진단 분기다. 이번 직접 실패에 기록된 것은 MASK_BIT_PATTERN이다.
- 실패 뒤 나머지 fixture와 최종 soak/qualification/adoption이 모두 성공했다고 주장할 수 없다.

## 5. 수정된 비교 규칙과 아직 모르는 것

### 수정 전 규칙 — 과거 실패를 해석하는 기준

- reference는 CPU1 실행으로 생성한다.
- raw float 비교는 finite/count 확인 후 `abs(a - b) <= 0.0001 + 0.001 * abs(a)`를 사용한다.
- mask 비교는 개수/중복 anchor/anchor/이미지 크기/ROI/packed bit array의 완전 동일을 요구한다.
- `mismatch_count`는 packed byte XOR의 popcount 합이다. 동일 형상 조건과 구분되지만 실제 유효 픽셀 위치·padding 여부를 이번 로그로 확정하지 않는다.

### 새 설치본의 조건부 마스크 동등성 — 실제 구현됨

- 기존 raw finite/count 및 `1e-4 + 1e-3*abs(reference)` tolerance 유지. 모델/precision/confidence/NMS/실제 이진화의 logit>0 조건은 변경하지 않았다.
- mask 개수/중복 없는 anchor/이미지 크기/ROI/packed 길이가 정확히 같아야 하며, padding set bits=0과 actual area/popcount 일치를 요구한다.
- bit가 동일하면 통과. 다른 경우 mask마다 **유효 XOR <= min(8, floor(min(area_ref,area_actual)/1000))**, **IoU >= 0.999**를 동시에 요구한다. 1000픽셀 미만의 작은 mask는 여전히 bit 완전 동일이다.
- 각 변경점은 인접 변경이 없는 독립점이며, 중심을 뺀 3x3 ring에서 foreground8-connectivity=1/background4-connectivity=1, 4방향 foreground 이웃2~3개라는 보수적인 topology 조건을 요구한다. endpoint/hole/bridge 오류를 일반 경계 오차로 허용하지 않는다.
- 각 변경점을 CPU/GPU **각자의 실제 raw**로 production decoder와 같은 float32 누산·보간 순서로 sparse replay하여 packed bit의 sign과 일치해야 하고, 양쪽 모두 finite 및 **abs(logit)<=0.001**이어야 한다.
- 이 상수는 실제 coco_11149를 정량 측정해 calibration한 값이 아니라 좁은 수치 경계 실험 계약이다. 현장 안전성 인증이나 모든 구조 오류 배제를 보장하지 않으며 실제 차이가 이 조건을 충족하지 않으면 계속 거절한다.
- `JOINT_MASK_PARITY`에 면적/교집합/합집합/IoU/유효 XOR/padding/경계 검증 수/raw sign 검증 수/최대 abs logit/raw_match/accepted를 기록한다. 좌표/이미지/raw 배열은 기록하지 않는다. verified=0/max_logit=0은 단계 미평가일 수도 있다.

### 새 실기기 결과와 남은 범위

- 사용자 수동 점검에서 strict 성공 조합8개(4개 fixture×primary/auxiliary)를 확인했다. 뒤이은 SOAK/QUALIFICATION/DRAIN/PREPARED/ADOPTION/PUBLISH 및 DEVICE_CHECK FINAL_PREPARATION이 모두 SUCCEEDED다.
- JOINT_MASK_PARITY123개가 모두 accepted=true이며 그중 유효 XOR가0이 아닌 항목은1개다. 해당 차이는 padding0, valid_xor1, boundary_verified1, raw_sign_verified1, raw_match=true로 직접 확인했다.
- 전체 attempt의 관측된 FAIL 원인은 WAKE_PHRASE_UNAVAILABLE이다. 이후 진단 flow9의 ATTEMPT SUCCEEDED만 보고 전체 점검 PASS로 바꾸어 해석하지 않는다. state=FAIL의 실제 실패 event와 구분한다.
- 원본 trace와 프로그램 집계는 `diagnosis/mask-parity-repro/app-uid-typed-trace.log`, `diagnosis/mask-parity-repro/verified-result.json`에 있다.

### 미확인 / 일반화하지 않는 범위

- 이번 실행의 유효 픽셀/면적/IoU/보수적 경계 조건은 확인했다. 과거 모든 실행의 차이 위치/원시 tensor까지 복원한 것은 아니다.
- CPU1↔CPU1, GPU↔GPU 반복에서도 발생하는지, CPU↔GPU에만 발생하는지.
- 모든 fixture·후보·정밀도 설정에서 재현되는 범위.
- 여러 번의 재시작·온도·부하·환경을 아우르는 반복 신뢰성, 실제 안내 사용/현장 안전성 및 RELEASE 기본20초 cold-start 적합성은 이번 단일 DEBUG 기기점검으로 완료되지 않는다.

따라서 이 실행의 객체 점검 성공은 말할 수 있지만 모든 환경에서 항상 통과하거나 일반적인 현장 안전성이 보장된다고 말할 수는 없다.

## 6. 사용자 결정과 다음 작업

확인된 선택: **차이를 정량 분석한 뒤 합리적인 허용 기준 검토**. 이후 2026-11-05 23:33 KST 사용자는 **“이번엔 앱쪽 변경해서 기기점검 통과를 목표로 하자”**라고 요청했다.

현재 작업 범위는 진단 전용 APK를 반복하는 대신, 기존 raw finite/tolerance 및 구조 검증을 유지하면서 작은 수치·경계 변화에 대한 근거 있는 조건부 마스크 동등성을 구현하고 실제 회귀 시험·정상 gate APK를 만드는 것이다. 구체 수용 조건과 근거는 수정 완료 시 기록한다. 모델/원시 수치 tolerance/precision/호출어 gate를 임의 변경하거나 PASS를 합성하지 않는다. 이전 분석 전용 단계의 '구체 기준 미승인' 기록은 당시 상태로 보존하되 새 구현 요청을 무시하는 근거로 삼지 않는다.

- [x] **Q1. 비트 차이의 정체:** 이번 실기기 실행에서 padding0/유효 XOR1을 확인했다. 형상/anchor는 기존 exact gate를 통과했다.
- [x] **Q2. mask별 정량:** 면적14777↔14776, intersection14776/union14777, IoU0.9999323272653448을 기록했다.
- [x] **Q3. 조건부 경계 검증:** 실제 raw의 sparse replay sign1과 경계/topology 조건1이 모두 만족된다. 실제 영상/좌표/배열은 공유 문서에 넣지 않았다.
- [ ] **Q4. 선택적 후속 신뢰성 실험:** CPU1 반복/GPU 반복과 장시간 온도·부하 대조는 별도 미실행 범위다. 이번 객체 점검 성공을 취소하는 미완료 조건이나 자동 추가 재측정 요청으로 취급하지 않는다.
- [x] **Q5. 보수적 조건부 동등성 구현:** raw/구조 gate와 IoU/절대·상대 XOR/simple-point/자체 raw sign·near-zero 조건을 결합했다. 구체 기준과 한계는 §5 및 구현 보고서에 기록했다.
- [x] **Q6. 앱 수정 요청 반영:** 사용자 구현 요청 범위에서 위 실험 계약을 적용했다. fixture ID 예외나 `1비트면 무조건 통과`는 없다. 큰 의미 변화 및 현장 안전성에 대한 일반 보장은 하지 않는다.
- [x] **Q7. 객체 통과 검증:** RED→GREEN47, 보존 회귀, 정상 APK/보존 설치 후 사용자 수동 실기기의 모든 strict→soak→qualification→adoption→publish 성공까지 확인했다. 전체 attempt는 호출어 실패와 별도로 기록한다.

새 정량 분석을 위해 native 재실행이 필요한지 먼저 검토한다. 이미 남아 있는 정보로 가능한 분석부터 수행하고, 필요할 때만 사용자에게 한 번의 수동 점검을 요청한다.

## 7. 조원 AI가 볼 코드 위치

저장소 상대 경로 기준이며, line 번호는 수정으로 이동할 수 있어 **symbol**을 우선 검색한다.

- `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/composite/JointRuntimeReferenceSet.kt`
  - `Verifier.auxiliary`, `equivalentRaw`, `sameMasks`, MASK_BIT_PATTERN 집계.
- `.../inference/composite/JointMaskNumericalParity.kt`, `.../inference/unknown/segmentation/FastSamDecoder.java`
  - 조건부 동등성, topology/aggregate와 `maskLogitAt` sparse replay. 실제 추론 decoder threshold는 그대로다.
- `.../inference/composite/JointRuntimeFinalPairPreparation.kt`
  - strict fixture → soak → qualification → prepared 순서. strict 실패를 건너뛰지 않는다.
- `.../inference/composite/JointRuntimeFinalDiagnostic.kt`
  - `WalkSafeJointFinal`의 고정 stage/kind/reason 분류.
- `.../inference/unknown/segmentation/InstanceMask.java`
  - `copyPackedBits`: ROI row-major / byte 내 low-bit-first.
- `.../inference/LocalPrimaryTensorEngine.kt`, `.../inference/GpuSerializationCache.kt`
  - optional cache eligibility와 실제 GPU 생성 분리.
- `.../runtime/vision/remote/RemoteVisionClient.kt`, `VisionRuntimeService.kt`, `VisionRuntimeProtocol.kt`
  - OPEN deadline/검증/watchdog 및 typed 진단.
- `.../device/PostLoginDeviceCheckController.kt`, `PostLoginDeviceCheckPolicy.kt`, `.../device/postlogin/AndroidPostLoginDeviceProbes.kt`
  - 위치 start latch, 동일 attempt의 composite/wake 완료 순서와 기존 gate.
- `.../ui/DeviceCheckItems.kt`, `DeviceCheckPresenter.kt`, `OnboardingReadinessPanel.kt`, `NativeScreenPresenter.kt`
  - 현재 7항목 producer/consumer 계약.
- `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt`
  - `prepareMainRuntimePair`, `receiveCompositeSelection`, `finishDeviceCompositeApplicationIfReady`.

위 `.../`는 동일 `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe` 아래를 뜻한다.

## 8. 로컬 증거 찾아가기

**workspace root는 이 저장소 `source/`의 상위 폴더**다. 아래는 workspace 상대 경로이며, 조원이 보고서만 받은 경우 파일이 자동으로 존재하는 것은 아니다. 원본 로그가 없으면 제공되지 않은 증거를 재현했다고 주장하지 않는다.

- 최신 직접 거절: `diagnosis/final-preparation-repro/app-uid-typed-trace.log`, `CONFIRMED_REJECTION_KO.md`
- 최신 실제 성공: `diagnosis/mask-parity-repro/app-uid-typed-trace.log`, `diagnosis/mask-parity-repro/verified-result.json`
- cold GPU 성공/후속 최종 실패: `diagnosis/fastsam-cold-repro/candidate-summary.json`, `progress-summary.json`, `app-uid-typed-trace.log`
- 실제 20초 watchdog: `diagnosis/fastsam-open-repro/app-uid-typed-trace.log`
- cache/CPU 성능 원인 분리: `diagnosis/instrumented-repro/`, `diagnosis/native-original-repro/`
- 최신 판정 수정·테스트·DEX·diff: `device-check-mask-parity-fix/REPORT.md`, `artifact.json`, `completion-verification.json`, `final-green.log`, `source.diff`
- 이전 최종 진단본: `device-check-final-preparation-fix/REPORT.md`, `artifact.json`, `source.diff`
- cold-open/wake 분리: `device-check-fastsam-cold-open-fix/REPORT.md`
- cache gate 수리: `device-check-gpu-cache-gate-fix/HANDOFF.md`
- 위치·후보 진단: `device-check-runtime-diagnosis-fix/REPORT_KO.md`
- 표시 계약: `device-check-ui-fix/REPORT_KO.md`
- 최신 설치 보존 증거: `phone/mask-parity-install/installation.json`, `preservation-hashes.json`, `signature-gate.json`
- 현재 phone 범위: `phone/USER_CONTROL.md`

공유 대상은 이 Markdown와 진행 이력 등 메타데이터다. private credential/key/account store, 원본 앱 데이터, 영상·이미지·tensor·음성, 자료 권리가 확인되지 않은 fixture 포함 APK를 공유 패키지에 넣지 않는다.

## 9. 검증 결과를 읽는 방법

- 최신 수정본의 decoder/final/reference/output 실제 소스 JVM harness: **47 PASS**. 이전38건과 새 parity9건이다.
- 별도 보존 회귀 그룹: GPS/controller99, OPEN/protocol/release31, candidate20, host19, nested classifier3 PASS.
- 이 수치를 모든 버전의 누적 전체 시험 수로 더하거나 full Gradle suite/실기기 전체 PASS로 부르지 않는다.
- strict/offline JDK21/SDK36 빌드, 동일 signer/package, 모델/asset/native byte identity, DEX 정상 flag/callsite는 확인했다.
- host fixture의 fake native callback은 실기기 GPU 성공 증거가 아니다. 실제 GPU 진전은 위 실기기 로그로 별도로 증명한다.
- 최신 실기기에서 strict 전체/soak/qualification/adoption/publish까지 성공했다. 아직 완료되지 않은 범위: 호출어를 포함한 전체 attempt PASS, durable 저장/복원, 실제 안내 사용/현장 안전성 및 일반 반복 신뢰성.

## 10. 이후 기록 유지 규칙

이 절은 사용자가 요청한 **이 문제의 향후 해결 과정 기록**을 위한 것이다. 전역 Goal graph/통제 체계를 새로 만들거나 무관한 기능을 막는 gate가 아니다.

1. 이 문제의 새 조사·코드 변경·테스트·빌드·설치·사용자 재현·정책 결정마다 진행 이력에 항목을 추가한다.
2. 보고서의 기준 시각/현재 상태/최신 설치 SHA/확정 사실/미확인/다음 작업을 함께 갱신한다.
3. 과거 실패를 지우거나 이전 테스트를 현재 버전 PASS로 고쳐 쓰지 않는다. 정정은 새 이력에서 이유와 근거를 남긴다.
4. 각 항목에 입력 버전, 관측/가설, 변경, 실제 검증, 미실행 범위, 결과 및 다음 단계를 구분한다.
5. 소스 수정·빌드 성공·폰 설치·native 실행·성능·정확도·최종 gate·현장 검증을 별도 단계로 기록한다.
6. 추가 수동 재현이 필요하면 기존 로그로 부족한 이유와 이번 재현에서 확인할 정확한 조건을 적는다. 조원이 이전 APK로 재시험하지 않도록 SHA를 명시한다.
7. 새 허용 기준을 제안/변경하면 근거 데이터·승인 범위·여전히 거절해야 할 조건과 회귀 결과를 기록한다.
8. 호출어 모델 품질, 인증·동의·교육, 기존 앱/DB/키/데이터 보호 등 범위를 임의로 넓히지 않는다. 기기점검 실행은 사용자가 담당한다.
9. 문서만 갱신한 작업에서는 앱을 빌드·설치·점검한 것으로 기록하지 않는다.

## 11. 다음 AI에게 전달할 요약 프롬프트

> 이 보고서와 진행 이력을 읽고 현재 상태를 확인하라. 現APK9d5424e15bb7ef099c9f38c7dafbde84bb920958667aae1f2ede8f416debd789에서 사용자가 수동 점검했고 모든 객체 strict/soak/qualification/adoption/publish가 실제 성공했다. 유효 경계 XOR1/IoU0.9999323272653448/raw sign1을 직접 확인했다. 전체 attempt FAIL의 남은 원인은 WAKE_PHRASE_UNAVAILABLE이며 사용자는 호출어 품질을 범위에서 제외했다. 객체 문제를 계속 실패 상태로 취급하거나 요청 없이 재측정/호출어 수정/전체 PASS 조작을 하지 말라. 저장/복원 및 현장 안전성은 별도 미확인 범위이고 기록을 이어서 유지하라.
