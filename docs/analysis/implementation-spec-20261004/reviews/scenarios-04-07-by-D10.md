# SC04~07 독립 연결 검토 — D10 작성자

## 결과와 기준

- 검토자 `voice_data`는 시나리오 작성자 Root와 다른 인스턴스다. 수정 소유 파일은 이 리뷰뿐이며 `05-scenarios.md`, 시나리오 Mermaid, 타 도메인 JSON과 제품 소스는 직접 변경하지 않았다.
- 대상은 `05-scenarios.md`의 SC04~07 및 `diagrams/sc-04.mmd`~`sc-07.mmd`다. Root의 작성 고정 알림 후 시작했고, 발견사항은 Root가 수정했다. 수정된 문장·분기들을 다시 대조했다.
- 소스 기준은 `/tmp/walksafe-spec-20261004-source`, `2026-10-04T10:33:40+09:00`, commit `2e5744725a8c033a1817f3cc8a8ec679bc15ac9d` + 당시 WIP다. 아래 소스 경로·줄은 고정 사본 기준이다.
- 최초 전체 문서 SHA-256: `3bb6e3ef86fdba4df7326d352ac6788e7497d1d56e2654b1d6dc6054952c62d3`. 최초 SC04/05/06/07 그림은 각각 `08a26c6b…8840bb`, `5f513392…31df9`, `b7dafe55…9ea303`, `d16b00e5…fbf250`였다. 최종 전체 값과 선택 절 해시는 아래에 기록한다.
- 판정: 아래 6개 문서 연결 오류·모호성을 수정판에서 해결 확인했다. SC05의 FAILED 증거 제출은 검토 시작 전에 Root가 D01 교정에 맞춰 반영한 내용이며, 이를 이 리뷰의 신규 발견으로 계산하지 않았다.
- 제품 실행, 테스트/빌드, 모델 학습·추론, DB·기기·NGII·기관 서비스 호출은 하지 않았다. 기존 실행 로그도 읽지 않았다. 도식의 실제 렌더와 XML/표시 검증은 Root의 통합 범위다.

## 발견사항과 수정판 확인

| ID | 최초판 문제 | 근거 | 수정판 판정 |
|---|---|---|---|
| RV-SC-D10-01 | SC04가 모든 직접 신고의 안내 완료를 DONE으로 표현해 화면 draw와 음성 완료를 구분하지 못함 | MainActivity:40538-40622; F/P-D06-004 | 화면 draw 또는 음성 DONE 뒤 30초 단회 확인으로 수정. 해결. |
| RV-SC-D10-02 | SC04가 POST 뒤 불확실할 때만 status를 조회하는 흐름으로 읽힘. 첫 수정판도 같은 drain에서 즉시 두 번째 status 조회처럼 보임 | ReportQueueDrainCoordinator:151-201,228-240; F-D06-007, P-D06-007/008 | 항상 status GET 선행, exact receipt면 POST 없이 정리. status가 없을 때만 재검증 후 POST. 불확실 응답은 원본 보존·다음 허용 trigger에서 status 복구로 수정. 해결. |
| RV-SC-D10-03 | SC05의 Backend 일시 장애 분기 뒤에도 Android local purge가 무조건 이어짐 | Gateway routes:2811-2830, deletionPending:2648-2658; MainActivity:6304-6349 | Android의 현재 accepted journal·수락 상태 확인 gate 추가. 미확인은 동일 요청/status 복구·outbox 보존으로 끝남. 해결. |
| RV-SC-D10-04 | SC06은 gate 통과가 항상 실제 학습으로 이어짐 | model/train_yolo.py:62-101,103-125; F-D10-019/020 | dry-run=true는 학습 없이 종료하고 false에서만 train 호출. 해결. |
| RV-SC-D10-05 | SC06 철회/삭제/만료에서 artifact 정리·계보 판단으로 직접 연결해 자동 감시·재훈련처럼 읽힐 수 있음 | manage_training_artifact_retention.py:250-288; training_model_lineage.py:116-193; F/P-D13-042/043 | 명시적 운영 호출 점선과 runtime caller 미확인 표시. 본문도 자동 학습 중단·자동 재훈련 감시가 아님을 명시. 해결. |
| RV-SC-D10-06 | SC07 본문이 취소가 새 세대 결과 게시를 막는다고 표현함 | AnalysisCoordinator:367-426,641-681; F/P-D16-014 | 취소한 작업의 늦은 결과를 막고 새 세대 결과를 보호한다고 수정. 기존 도식의 현재 generation gate와 일치. 해결. |

이 표는 문서 오류·모호성에 대한 판정이다. 실행으로 재현한 새 제품 결함을 뜻하지 않는다. 코드 자체의 알려진 제약은 기존 도메인의 issue와 함께 유지한다.

## SC04 — 신고·관리자 연결

- 대조 JSON: F-D06-001/004/007과 P-D06-004/007/008, F-D12-001, F-D15-033, R-D14-008을 상세 대조했다. 시나리오가 참조하는 나머지 D06/D08/D12/D15 항목은 ID·이름·상태·코드참조를 확인했으며 모든 본문을 독립 재감사한 것은 아니다.
- 직접 신고의 30초는 armed 시점부터의 확인 유효기간이며 `>`에서 만료된다. 안내 자체는 120초 한도다. pending은 문맥 불일치·만료·취소 때 zeroize하고 단회 소비한다.
- drain은 status1/POST1 범위로 현재 lease/actor/session/consent를 네트워크 경계마다 확인한다. exact status receipt면 upload를 건너뛴다. 네트워크 실패 후 무제한 자동 반복으로 그리지 않았고, 정상/실패 finally와 cancel에서 lease/nested call을 해제한다.
- queue·관리자 운영 UI는 조건부다. 관리자 보안 기능과 운영 기능은 다르며 기본 운영 flag=false이다. 재인증 시각 누락은 원래 JSON issue의 정적 경계로 유지하며 모든 admin API가 실패한다고 확대하지 않았다.
- source 확인 범위: `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/ExplicitReportConfirmationPolicy.kt:1-18,114-226`; `report/ReportQueueDrainPolicy.kt:52-143`; `report/ReportQueueDrainCoordinator.kt:123-246`; 같은 앱 `MainActivity.kt:40538-40675`; `apps/android/adminapp/build.gradle.kts:80-90`; `backend/app/api/admin_reports.py:147-172`; `backend/app/services/admin_security.py:1521-1587,3297-3322`.
- R-D14-008은 high-risk 확인을 소비해도 기존 identity를 반환하고 일부 route가 step_up_verified_at를 요구한다는 코드 일부와 JSON을 교차확인했다. PostgreSQL function·middleware 전체·실제 HTTP 재현은 이번 리뷰 범위 밖이다.
- 기관 전달은 F-D15-033의 기존 전달/회신 사실 append로 설명되어 있다. 이 service 전체·외부 발송 도구를 실행하거나 조사해 부재를 전수 증명한 것은 아니다.

## SC05 — 계정 삭제·다중 저장소

- 대조 JSON: F-D01-027/029/032, P-D01-020/021/023, F-D08-040/043, F-D13-012/015/017/018/021. 각 컴포넌트의 durable boundary가 다르다는 본문을 유지한다.
- Gateway ledger/outbox 수락과 Backend 수락은 같은 상태가 아니다. outbox 기록의 accepted_at/request receipt는 처음 null이며 Backend pending은 HTTP503이다. Android가 acceptedAt/accountGeneration/tombstone/requestReceipt를 모두 확인한 뒤 해당 purge 진행 경로를 선택한다는 gate가 수정 그림에 있다.
- 최신 교정의 핵심은 Main:6510의 `purgeSucceeded ? DELETED : FAILED`다. 두 결과 모두 marker EVIDENCE_PENDING·journal에 저장한 뒤 증거 제출을 시도한다. 저장 실패는 제출로 넘어가지 않는다. Backend도 FAILED를 target/item FAILED로 집계하며, 증거 ACK만으로 전체 COMPLETED를 만들지 않는다.
- 전체 9개 inventory가 terminal일 때만 COMPLETED이고, LEGAL_HOLD/FAILED/EXTERNAL_PENDING은 별도 상태다. 외부 기관 사본이 있으면 server_copies는 EXTERNAL_PENDING이며 내부 worker가 그 사본을 삭제했다고 확정하지 않는다. 백업도 즉시 일괄 물리삭제라는 설명이 없다.
- source 확인 범위: `apps/android-gateway/src/privacy-deletion-v2.ts:1735-1772`; `apps/android-gateway/src/routes.ts:2648-2662,2795-2835`; 앱 `MainActivity.kt:6295-6349,6485-6570`; `backend/app/services/privacy_lifecycle.py:1363-1382,2510-2587,2692-2723`.
- 미검토: 삭제 auth_epoch 전체 transaction·outbox backoff 스케줄러·account_deletion_worker의 파일/DB commit 전체·최종 Android reset 각 barrier·PG 서명 검증. 이 부분은 연결된 D01/D08/D13 원명세를 기준으로 하며 본 리뷰에서 실행/전수 검증했다고 주장하지 않는다.

## SC06 — RAW·격리·학습

- 대조 JSON: F-D06-017/019/020/021/023와 P-D06-018, F-D08-051, F-D13-025/026/028/033/038/039/040/042/043와 P-D13-028/033/040/042/043, F-D10-019/020와 P-D10-016/017/018.
- RAW sealing과 upload admission은 다르다. SAFE_STOP도 seal 지점이지만 upload는 READY/ENDED·Gateway lease 없음과 현재 owner/RAW consent 및 Wi-Fi network binding·charging을 요구한다. foreground 조건은 D06의 Main scheduling 명세와 연결한 조건이며 이번 source 재확인은 coordinator admission에 한정한다.
- commit의 v2 14일 QUARANTINED와 legacy v1 180일 COMMITTED가 코드에 분리돼 있다. 그림은 현행 v2 경로를 대상으로 하며 commit을 TRAINING 승인으로 취급하지 않는다.
- 목적 승인에서는 current consent/receipt 및 금지 source kind를 확인한다. VIDEO/AUDIO/EXACT_LOCATION/ROUTE는 TRAINING 승격을 차단한다. 이후 artifact·dataset revision·gate 발급이 별개 단계라는 설명과 일치한다.
- HMAC gate 최대10분·최소32byte key·current DB 검증은 학습 시점에 적용한다. wrapper의 data YAML 파일 집합과 검증 manifest 결속 한계, 직접 shell 학습의 별도 경로를 본문이 유지한다. dry-run도 hash/DB 검사를 수행하지만 YOLO를 import/train하지 않는다.
- retention은 명시적 CLI 실행이며 preview조차 이전 recovery 흔적을 정리할 수 있다. lineage helper의 RETRAIN_REQUIRED는 조회 결과/배포 가능성 helper이며 자동 재훈련 구현이 아니다. 실행 중 철회 감시/선행 개인정보 fence까지 있는 것으로 확대하지 않는다.
- source 확인 범위: 앱 `rawcollection/RawCollectionRuntimeCoordinator.kt:1023-1092`; `backend/app/services/raw_collection_storage.py:3100-3155`; `backend/app/services/raw_collection_lifecycle.py:30-35,156-232`; `backend/app/services/training_model_lineage.py:100-193`; `model/train_yolo.py:1-129`; `scripts/manage_training_dataset_lifecycle.py:1-203`; `scripts/manage_training_artifact_retention.py:100-288`; `backend/app/services/training_dataset_lifecycle.py:50-103,204-270,341-410`.
- 뒤의 학습/보존 파일 범위는 같은 리뷰어가 바로 앞 D10 작성 및 D11 독립 검토에서 읽은 고정 사본의 본문을 재사용했다. 여기서 모델·DB·retention 명령을 실행한 것은 아니다. raw upload의 모든 quota/hash/subject 잠금 및 Android active capture 연결을 전수 재검토하지 않았다.

## SC07 — 별도 위치 평가·PPK

- 대조 JSON: F/P-D16-003/004/008/010/012/013/014/015/016을 상세 확인하고, F-D16-005/006은 연결·입력 검증 경계를 확인했다.
- import는 비공개 사본을 만든 후 trace parser/GNSS validator를 실행하고 실패한 새 사본을 정리한다. 결과/입력의 프로세스 재시작 자동 복원을 약속하지 않으며 startup은 imports/work/key.part 고아 자료 정리를 한다.
- 자동 NGII 경로는 HTTPS·허용 host·443·credential path·redirect 및 bounded payload를 확인한다. 401/403·기타 HTTP 오류를 모두 참조 부족으로 뭉뚱그리지 않고 FailurePolicy allowlist가 최종 분류한다.
- JNI는 고정 native version이 일치해야 AVAILABLE이다. FIX 평가의 1초 grid, gap≤1500ms, estimate age≤2000ms, 최소30초/30truth/20channel/coverage≥0.90 정책과 결과 JSON의 FIX_ONLY·CAUSAL_LATEST_PRIOR_ESTIMATE가 연결된다. 이 정적 정책은 실제 위치 정확도나 JNI 성공을 입증하지 않는다.
- 취소한 run은 JSON 성공/실패 결과를 게시하지 않고, current generation와 !cancelled일 때만 complete한다. finishCancellation은 새로운 run/result가 있으면 옛 취소 문구로 덮지 않는다. 삭제는 busy/cancelling일 때 거부하고 일부 실패 시 실제 남은 파일로 입력 상태를 정합한다.
- `reference_provenance`는 SAME_PHONE_POSTPROCESSED_PPK / NOT_INDEPENDENT / DEVELOPMENT_EVALUATION_ONLY다. 외부 SAF 원본/내보낸 JSON은 private root 삭제 대상이 아니고 공개 station cache도 clear/startup 삭제 목록에 포함되지 않는다.
- source 확인 범위(모두 positionevalapp의 Kotlin core/활동 코드): `AnalysisCoordinator.kt:133-174,215-279,367-484,527-617,616-693,747-767`; `core/PpkEvaluation.kt:1-100,134-180`; `core/ResultJson.kt:1-70,113-157`; `core/PpkEngines.kt:83-129`; `core/NgiiClient.kt:260-327,337-354`; `core/ManualBaseRinexImporter.kt:15-77`.
- 미검토: TraceV2Parser 전체와 GnssLogger 변환/해제 루프, JNI C 전체, RTKLIB vendor, Android ContentResolver/Keystore 실동작, NGII 실제 응답. 취소 지연 한계는 D16 원명세와 연결해 기록하며 새 성능 측정으로 주장하지 않는다.

## 남은 검토 경계

- SC01~03과 다른 통합 문서, 모든 도메인·소스의 전수 정확성, 테스트 기대 전수, 과거 실행 성공, 실제 현장/기관/모델 성능은 이 리뷰 범위 밖이다.
- 이 시나리오는 컴포넌트 간 설명용 연결이며 사용자 버튼 한 번으로 관리자/운영 CLI/외부기관 업무가 자동 연쇄 실행된다는 뜻이 아니다. 조건부 영역의 설치·배포·계정 설정은 이번에 확인하지 않았다.
- 지정한 범위에서 해결되지 않은 추가 수정 요청은 없다. Root의 최종 통합·링크/렌더 검증과 전체 완료 판정은 별도로 남는다.

## 최종 검토 버전

- `05-scenarios.md` 최종 SHA-256: `5dab63666f297f5fd4a3a3658ccdb0ec1aa1605f52822bd329426595ab3e1b68`. 최종 수정된 SC04~07을 다시 읽은 뒤 기록했으며 기록 전후 전체 문서 bytes가 동일하다.
- 전체 문서는 타 범위 검토자의 변경도 포함할 수 있으므로 아래 선택 절 SHA도 함께 고정한다.

| 대상 | SHA-256 |
|---|---|
| SC04 절 UTF-8 | `aad2c5c052017d57b8e797bc2cd2b24a79b3d5e17ab32ec51bb61a1d5fbce514` |
| `sc-04.mmd` | `b605f5b77f145b052b94ef8768bd206e45a151ed3d8ef448e186ce87ac5956bf` |
| SC05 절 UTF-8 | `73d9d6e38cc74465f4da8191c592bff45aa0b609ae8e65f40b8060feb41d2e2c` |
| `sc-05.mmd` | `e2c7323011044b7afb6adb1198dab0be248fd371b08abaa872c45f5e6929dc75` |
| SC06 절 UTF-8 | `588b79b502576f842618f575973facd90b55aa0cf6ddd25ea7857aec02312948` |
| `sc-06.mmd` | `fb8200f55fd831a047f4858028c403e1dff58d048880999842ed160c4f5ef539` |
| SC07 절 UTF-8 | `2603ee8f42cf5dc2f1cfb0cd1150e6e95299741839c1deada3a663bf0716298a` |
| `sc-07.mmd` | `d16b00e52f6adbce0939f5911a0219c2cce02c15c714f5f39605f05ee0fbf250` |

| 대조한 도메인 JSON | 읽은 최종 파일 SHA-256 |
|---|---|
| `D01.json` | `039ab6b9265e690a97f7c21b37d125404c2d27ba8181f9c4816857e1c0c67b0b` |
| `D06.json` | `b05231bd312a8a649a6db638e2cb2e2f9ee35f3b1eacf9f9cd7a474be9aa469c` |
| `D08.json` | `895e5ad7e29255881ea5bc73f53d44b899d643c89ddf73ac216cc1ac6ab31ca6` |
| `D10.json` | `b196dd20d41a4716bb1704fe135e50c01ded8e05c80b58359c7471dba2dd49e9` |
| `D12.json` | `baf8864332f587da496eeacea90fab6357a77360ce977ea84e0093a2290eab67` |
| `D13.json` | `d4955e245f869acd4af53e3222edb5743076395c05c8772bddc911cfa180a843` |
| `D14.json` | `684f009acbd721bb09e28bccf834386b7d1cb400d49153139872b1ff3f4a4d45` |
| `D15.json` | `c34111caa8d57f526e3ec6b17cc323325f9b55f2d00d62b17a9a2db52b039f43` |
| `D16.json` | `f0edb4ca3e8e06c05251b1761017dc83378f6683a6db7a86b9a461a27f084b4d` |
