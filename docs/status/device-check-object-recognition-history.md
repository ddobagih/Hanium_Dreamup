# 기기점검 객체 인식 문제 — 진행 이력

- 관련 현황 보고서: `device-check-object-recognition.md`
- 기록 범위: `WM-DEVICECHECK-MASK-PARITY`
- 최초 정리: 2026-11-05 23:14 KST 기준 증거의 회고 정리. 아래 개별 작업에 임의의 정확한 시각을 붙이지 않았다.
- 이후 기록 방식: 과거 항목을 지우지 않고 새 항목으로 추가한다. 현재 요약·최신 버전은 현황 보고서에도 반영한다.
- 증거 경로 기준: 현황 보고서 §8의 workspace root. 보고서만 전달받은 환경에는 원본 증거가 없을 수 있다.

## LOG-001 — 새 snapshot 격리 앱과 기준선

- 입력: 사용자 지정 `Hanium_Dreamup-codex-walkmate-temp-integration-20261026.zip`.
- 조치: 별도 applicationId와 전용 인증서, 정확한 이름 `최근 walkmate 테스트`, USB loopback 기본값을 적용했다. 과거 runtime/측정 생략 패치를 새 snapshot에 자동 이식하지 않았다.
- 산출 APK SHA: `3e7e695b6caeefafb41f453f1446b6e6d1eabc4e6a30fa3cc477ebce5755d90b`.
- 검증 경계: 실제 빌드/서명/패키지/asset 및 설치를 확인했다. 이것은 모델 실행이나 기기점검 전체 성공을 뜻하지 않는다.
- 보존: 기존 앱 삭제/초기화 없음. 현재 문제는 기존 Docker 서버의 계정으로 로그인한 뒤 로컬 기기점검에서 발생한다. 별도 새 서버의 전체 readiness를 성공했다고 주장하지 않는다.
- 증거: `android-build/REPORT_KO.md`, `phone/installation.json`.

## LOG-002 — 7개 항목 producer/consumer 계약 불일치 수정

- 관측: 실제 FAIL과 재시도 버튼이 있는데 다수 행이 `확인 중`으로 남았다.
- 원인: 새 producer 7개 key와 과거 panel 6개 key가 달랐다.
- 변경: 공유 `DeviceCheckItems` 라벨/순서, 실제 presenter/panel/호출어 활성 override를 연결했다.
- 새 APK SHA: `c7942d86e5df0a91519590202b928595ebcbf718471c56e2ac28ac3de2f53a9d`.
- 검증: 실제 producer 및 소스에서 추출한 adapter/panel 표현식의 Kotlin RED→GREEN, 정상 APK 빌드/서명/설치. full Android View/전체 Gradle suite PASS라는 뜻은 아니다.
- 결과: 실제 상태 값이 화면에 드러났다. 객체 `제한`과 현재 위치 `수신 대기`는 별도 조사로 분리했다.
- 증거: `device-check-ui-fix/REPORT_KO.md`, `phone/ui-fix-install/`.

## LOG-003 — 전용 현재 위치 probe 연결 복구와 후보 진단

- 관측: 위치 권한/서비스는 활성인데 전용 수신 상태가 계속 PENDING.
- 원인: 전용 start wrapper에 실제 production 호출이 없었다.
- 변경: authorized RUNNING 단계의 실제 자동 probe 연결, attempt당 1회 latch, 취소/timeout/retry/late callback 보호. raw acceptance policy와 필수 gate는 유지했다.
- 추가: 후보별 초기화/성능 탈락 원인을 typed bounded 진단으로 기록했다.
- 새 APK SHA: `feb9dbdff2faa4b48942f09c524e7746c8612b31ebc9c81fe2750296b4cebb17`.
- 검증: 실제 소스 host harness 132건 PASS; 정상 APK 빌드·서명·보존 업데이트. 이후 사용자 수동 점검에서 현재 위치 수신 통과를 확인했다.
- 결과: CPU 추론은 성공하지만 FastSAM CPU 처리 약 1.2초가 성능 기준에 걸리며, GPU 실패는 더 하위 원인 관측이 필요했다.
- 증거: `device-check-runtime-diagnosis-fix/REPORT_KO.md`, `diagnosis/instrumented-repro/`.

## LOG-004 — native child에서 사라지는 GPU 원본 오류 보존

- 관측: 상위에는 primary RUNTIME_INIT_FAILED / auxiliary VISION_RUNTIME 래핑만 남았다.
- 변경: compatibility/delegate/interpreter/tensor allocation/OPEN에서 원본이 사라지기 전에 고정 enum 진단을 남긴다. 임의 message/stack/경로/사용자 내용은 기록하지 않는다.
- 새 APK SHA: `739a4cc6cda8c0012612297fe9b2705ef139cdb0af976055533a1b228e0f903a`.
- 검증: 신규 진단 20 case 및 기존 회귀, strict 빌드/서명/native package audit. LiteRT 1.4.0 정렬과 GPU JNI 포함을 확인했다. 라이브러리 포함을 실기기 로드 성공과 혼동하지 않는다.
- 실제 결과: 앱 UID 범위의 종료된 native child까지 읽자 PRIMARY COMPATIBILITY의 GPU_UNSUPPORTED가 확인됐다.
- 증거: `device-check-gpu-init-repair/REPORT_KO.md`, `diagnosis/native-original-repro/`.

## LOG-005 — optional cache veto가 실제 GPU 생성까지 막던 경로 수정

- 원인: optional cache eligibility 검사가 전체 GPU 생성의 선행 필수조건처럼 작동했다. disabled cache도 non-null 객체였다.
- 변경: eligibility를 optional cache 준비 callback 안으로 이동. cache를 쓸 수 없으면 진실한 unavailable 상태로 uncached requested-GPU 생성을 수행한다. 실제 native 오류/텐서/해제/성능 조건은 유지한다.
- 새 APK SHA: `50d80f4fe4f410942e838411d82d2af8e0e6d31a32366c6347b639cb424d1e3a`.
- 검증: 원본 RED 4실패 → 관련 37 PASS 및 보존 회귀/실제 DEX/빌드/서명.
- 실제 결과: 기본 모델이 실제 GPU로 추론을 완료했다. FastSAM GPU OPEN은 여전히 실패했고 CPU 대안은 성능 기준에서 탈락했다.
- 증거: `device-check-gpu-cache-gate-fix/HANDOFF.md`, `diagnosis/gpu-cache-fix-repro/candidate-summary.json`.

## LOG-006 — timeout 오분류 수정과 20초 종료 직접 확인

- 문제: nested timeout/cancellation이 NATIVE_FAILED로 뭉뚱그려졌다. 당시 SIGKILL과 약 21초 창만으로는 watchdog 종료를 확정할 수 없었다.
- 변경: parent/child OPEN 단계, 실제 watchdog, 원래 timeout/child death/source cancellation을 bounded typed 진단으로 분리했다. 이 단계에서는 20초 예산을 변경하지 않았다.
- 새 APK SHA: `d7b2d0bb30931e2f84ce253b58f3f3f54f671d2ec452c7000ae1f8a5cc8c2fee`.
- 검증: focused/preserved JUnit 199건과 native 진단 20 assertions, 정상 APK 빌드/서명/설치.
- 실제 결과: AUX GPU의 NATIVE_ENTERED 후 SERVICE_WATCHDOG/TIMEOUT 19980ms, CHILD_EXIT 19981ms, 부모 await timeout 20001ms를 확인했다. 단순 추정에서 직접 관측으로 바뀐 지점이다.
- 증거: `device-check-fastsam-gpu-open-repair/REPORT.md`, `diagnosis/fastsam-open-repro/`.

## LOG-007 — 한정 cold-open 실험과 독립 검사 완료 순서

- 변경: DEBUG + pinned AUXILIARY/GPU OPEN에만 60초 상한. 부모/RPC/자식 검증 일치, 숨은 trial45초 clamp에 초기화 증가분40초 예약. caller/phase/cleanup reserve/전체300초 및 warm800ms 기준은 연장하지 않았다.
- 변경: 이미 활성인 동일 attempt의 composite 객체 점검 동안에만 wake terminal 전환 보류. wake UNAVAILABLE/닫힌 gate/무저장을 유지하고 최종 FAIL을 진실하게 게시한다.
- 새 APK SHA: `e65c33eba3d9783f91c6c06538c975d12657d32f6e96a413e2c7bfa922b707ed`.
- 검증: RED3종 → JUnit209건 + native20 assertions PASS, 정상 APK/DEX/서명/설치. host callback은 실제 GPU 증거가 아니다.
- 실제 결과: FastSAM GPU 첫 OPEN 23.6초, warm OPEN247~254ms. provisional GPU 조합 처리112~123ms와 후보 선택 성공을 관측했다.
- 남음: FINAL_PREPARATION에서 거절. provisional 성공을 strict accuracy/soak/최종 등록 성공으로 보고하지 않았다.
- 증거: `device-check-fastsam-cold-open-fix/REPORT.md`, `diagnosis/fastsam-cold-repro/`.

## LOG-008 — 최종 검증의 상세 거절 사유 소실 복구

- 문제: MainActivity가 최종 예외를 클래스 이름으로 줄여 어떤 predicate가 실패했는지 사라졌다. 이 시점에는 특정 lifetime/config 버그를 입증하지 못했다.
- 변경: final host open/reference claim/primary strict/auxiliary strict/soak/qualification/drain/prepared/adoption/publish 단계와 정확한 fixed predicate, 안전한 raw/mask 집계를 기록했다.
- 새 APK SHA: `2dba03ea49abf931ee813b76c5fa9f47720a64ff2a8893a6438f4ca66b771c4b`.
- 검증: final/reference/output real-source harness38 PASS 및 보존 회귀 그룹99/31/20/19/3 PASS, 정상 APK/DEX/서명/설치. 허용오차·정밀도·마스크 equality는 바꾸지 않았다.
- 결과: 정확한 실기기 거절 조건을 관측할 수 있게 됐다. 이 진단 추가 자체를 모델 수리 완료라고 부르지 않는다.
- 증거: `device-check-final-preparation-fix/REPORT.md`, `phone/final-preparation-install/`.

## LOG-009 — 현재 직접 거절 조건 확정

- 사용자 수동 점검: 최신 설치본으로 완료, 호출어 품질은 계속 수정 대상에서 제외 요청.
- 실측: coco_785/coco_872의 양쪽 strict 성공. coco_11149의 primary strict 성공 뒤 AUXILIARY_STRICT에서 **MASK_BIT_PATTERN mismatch_count=1**.
- 이어 ACCURACY_UNCONFIRMED → PREPARED/ADOPTION 실패 → CAMERA_PIPELINE_UNAVAILABLE.
- 해석: packed mask XOR popcount 차이 1. 마스크 개수1개라는 뜻이 아니다. 원시 수치/실제 의미/위치/면적/IoU/경계/유효 픽셀·padding은 서로 구분해야 한다.
- 조치: 기준을 임의 완화하거나 가짜 PASS를 만들지 않고 사용자에게 후속 방향을 확인했다.
- 증거: `diagnosis/final-preparation-repro/app-uid-typed-trace.log`, `CONFIRMED_REJECTION_KO.md`.

## LOG-010 — 정량 분석 후 허용 기준 검토 방향 선택

- 사용자 선택: **차이를 정량 분석한 뒤 합리적인 허용 기준 검토**.
- 승인 범위: 차이를 측정해 근거를 만들고 기준 변경을 검토한다.
- 미승인: 특정 IoU/픽셀 수/정밀도/tolerance 변경, fixture별 예외, 무조건 통과, 모델 교체.
- 현재: 실제 분석/새 네이티브 수집/새 기준 구현은 아직 시작하지 않았다.
- 다음 작업: 현황 보고서 Q1~Q7. 기존 정보로 가능한 범위를 우선하고 필요한 경우에만 추가 사용자 수동 점검을 요청한다.

## LOG-011 — 조원 AI용 현황·지속 기록 문서화

- 사용자 요청: 조원들의 AI가 현재 문제를 인지할 보고서를 만들고 앞으로의 해결 과정도 계속 기록.
- 조치: 현황 보고서, 이 진행 이력, AI 인계 README를 작성하고 저장소 AGENTS에 관련 탐색 포인터/기록 규칙을 연결했다. 후속 세션에서도 해당 문서를 갱신하도록 전용 task skill에 기록했다.
- 범위: 문서/탐색 연결만. 앱의 비교 기준·소스 런타임·완료 상태를 바꾸지 않는다.
- 기록 원칙: 관측과 추정, 구현·host 시험·빌드·설치·native 검증·최종 admission을 분리하고 과거 실패를 지우지 않는다.
- 공유 경계: 보고서/이력/인계 안내 등 메타데이터만. 자격정보·키·개인 앱 데이터·이미지·tensor·음성·APK는 묶지 않는다.
- 문서 검증: 실제 로컬 증거 경로22개, source locator17개, 직접 인용한 이벤트4줄의 원본 존재/일치를 확인했다. 최신 APK의 SHA를 실제 파일에서 다시 계산해 문서 값과 대조했다.
- 공유 패키지: 보고서/이력/인계 README/AGENTS 추가 절/metadata만 포함하는 ZIP을 생성하고 entry allowlist, 압축 CRC, 각 문서 byte/hash를 검증했다. 실행한 export와 검증 기록은 `ai-handoff/`에 보관한다. ZIP은 공유 시점 snapshot이고 원격 자동 동기화는 설정하지 않았다.
- 미실행: 앱 소스 변경/추가 빌드/설치/실기기 점검/마스크 정량 재측정은 하지 않았다. 다음 작업은 여전히 Q1부터다.

## LOG-012 — 앱 수정으로 정상 통과 목표의 작업 착수

- 시각: 2026-11-05 23:33 KST.
- 사용자 요청: “이번엔 앱쪽 변경해서 기기점검 통과를 목표로 하자”.
- 입력 기준선: 최신 설치 APK `2dba03ea49abf931ee813b76c5fa9f47720a64ff2a8893a6438f4ca66b771c4b`, MASK_BIT_PATTERN 차이1의 실제 로그.
- 조치: 앱 소스 단일 writer에 원인/구조 확인, 근거 있는 작은 수치 차이 조건부 동등성 수정, true-source RED→GREEN, 보존 회귀, 동일 신원/정상 gate APK 빌드를 위임했다. 진단만 추가하고 끝내지 않도록 범위를 정했다.
- 조건: raw finite/tolerance/shape/anchor/ROI/실제 GPU/성능/thermal/해제/등록/인증 gate 유지. fixture 예외·무조건 PASS 금지. 호출어 모델과 gate 변경은 범위 밖이다.
- 역할: 작업자는 앱 소스와 새로운 독립 산출물만 담당, 부모는 보고서와 검증 후 폰 설치 담당, 기기점검 시작/재시도는 사용자 담당.
- 관측된 진척: 작업 시작만 확인했다. 코드 수정/회귀 통과/빌드 성공/설치/최종 strict·기기점검 통과는 아직 확인하지 않았다.
- 다음: 완성된 수정/시험/APK 증거를 검토하고 데이터 보존 설치 후 사용자 수동 점검으로 object strict/soak/adoption 결과를 각각 확인한다.
- 현황 보고서: IN PROGRESS와 새 승인 범위로 갱신했다. 과거 분석 전용 범위는 당시 기록으로 보존한다.

## LOG-013 — 조건부 수치 마스크 동등성 구현·검증·설치 완료

- 시각: 2026-11-05 23:50 KST.
- 입력: 이전2dba03 APK의 MASK_BIT_PATTERN 차이1. 실제 비트 위치/IoU가 아직 없다는 한계는 유지한다.
- 변경: JointRuntimeReferenceSet, 신규 JointMaskNumericalParity, FastSamDecoder의 sparse maskLogitAt 및 테스트. raw tolerance/모델/precision/실제 이진화 조건은 유지했다.
- 새 계약: raw/개수/anchor/ROI/packed길이/면적·padding 조건 + XOR<=min(8,floor(최소면적/1000)) + IoU>=0.999 + 독립 topology-preserving simple-point + 양쪽 자체 raw replay sign 일치·abs(logit)<=0.001. 구체 근거·한계는 현황 §5와 구현 REPORT에 기록한다.
- RED/GREEN: 실제 decoder→Verifier의 raw 통과/경계 binarization 차이 거절 RED 뒤47건 PASS. 합성8비트/IoU0.9996491/자체sign8 허용, 9비트는 높은IoU에도 절대상한 거절. 휴대폰 tensor를 재연한 실측이 아니다.
- 보존 회귀: GPS99/OPEN31/candidate20/host19/nested3 PASS. strict/offline 실제 빌드 SUCCESS, 정상 flags/DEX 신규 호출/서명/model-assets-native bytes 동일 확인.
- 새 APK: `device-check-mask-parity-fix/Walkmate-Latest-Test-debug.apk`, SHA `9d5424e15bb7ef099c9f38c7dafbde84bb920958667aae1f2ede8f416debd789`.
- 부모 실행: exact BEFORE/AFTER SHA와 서명 사전검증 → 데이터 보존 install-r → 기기측 APK SHA readback. 기존 production APK 불변 및 양쪽 preference각4파일 설치 전후 해시 동일. 삭제/clear 없음.
- 정상 cold launcher Status:ok 후 자격정보 입력 없이 기기 점검 시작 버튼을 읽기 전용으로 확인했다. 점검/재시도/완료조작은 하지 않았다.
- 새 UID/실행 경계는 최신 SHA와 mask-parity-repro scope로 분리했다. 아직 이 수정본의 실기기 strict/soak/qualification/adoption 결과는 없다.
- 증거: `phone/mask-parity-install/installation.json`, `preservation-hashes.json`, `signature-gate.json`; 구현 REPORT/final-green.log/completion-verification.json.
- 다음 단일 작업: 사용자가 수동 점검한 뒤 JOINT_MASK_PARITY 및 가장 이른 final 실패/성공 단계 수집. 호출어 품질은 수정하지 않는다.

## LOG-014 — 사용자 수동 점검에서 객체 최종 검증·적용 실제 성공

- 시각: 2026-11-05 23:55 KST, APK9d5424e15bb7ef099c9f38c7dafbde84bb920958667aae1f2ede8f416debd789.
- 사용자: 점검 완료 후 호출어는 무시하고 해결 여부 확인 요청. 에이전트는 시작/재시도/완료 플래그를 조작하지 않았다.
- 관측: 내장4fixture×primary/auxiliary strict8개 성공. SOAK/QUALIFICATION/DRAIN/PREPARED/ADOPTION/PUBLISH 및 DEVICE_CHECK FINAL_PREPARATION도 성공했다.
- 정량: parity123개 모두 accepted=true. 유효 XOR가 있는1개는 면적14777↔14776/intersection14776/union14777/IoU0.9999323272653448/valid_xor1/padding0/boundary_verified1/raw_sign_verified1/maxAbsLogit2.86102294921875E-6/raw_match=true.
- 해석: 이번 실행에서 실제 경계 수치 차이에 대한 새 계약을 만족했고 객체 최종 검증·등록·적용 경로까지 통과했다. 코드/host 시험만을 근거로 성공이라고 한 것이 아니다.
- 전체 결과: state=FAIL, failure=WAKE_PHRASE_UNAVAILABLE. 이어 나온 별도 flow9 ATTEMPT SUCCEEDED는 전체 PASS 증거로 사용하지 않는다. 호출어 모델/gate를 수정하거나 전체 완료를 위조하지 않는다.
- 증거: `diagnosis/mask-parity-repro/app-uid-typed-trace.log`, `diagnosis/mask-parity-repro/verified-result.json`. 프로그램으로 fixture쌍/성공단계/비트차이 레코드/전체실패를 집계해 검증했다.
- 남은 한계: 한 번의 내장 이미지 DEBUG 기기점검 결과이며 일반 현장 안전성/장시간 신뢰성/durable 전체 저장·복원을 입증하지 않는다.
- 다음: 이 객체 결함의 이번 실기기 수정 검증을 완료로 기록하고 사용자에게 구분해 보고한다. 추가 실험·호출어 수리·새 gate변경을 자동으로 시작하지 않는다.

## LOG-015 — 원본 대비 테스트 앱 이름·패키지·서명 변경을 명시

- 사용자 요청: 조원들의 AI가 원본 상태에서 앱 서명과 이름을 바꿨다는 사실을 알 수 있도록 추가.
- 기록: 현황 보고서 §2에 원본 applicationId/인증서 지문, 테스트 `.latesttest` 패키지/정확한 런처 이름/별도 인증서 지문, namespace·release ID 유지 및 별도 공존 목적을 명시했다.
- 구분: 이 내용은 이번 턴에 앱을 다시 개명/재서명했다는 뜻이 아니라 기존 승인·설치 작업의 배포 식별자 차이를 더 명확히 문서화한 것이다.
- 보존/공유: 기존 앱/서명 키/데이터는 그대로이며 테스트 후속 업데이트는 동일 테스트 signer/패키지가 필요하다. private keystore/key/password는 공유하지 않는다.
- 범위: 문서 보완만 수행했다. 추가 빌드/폰 설치/기기점검은 하지 않았다. GitHub 새 브랜치 업로드 완료를 뜻하지 않는다.

## LOG-016 — GitHub 새 브랜치 업로드와 원격 readback 검증

- 사용자 요청: 다음 작업으로 넘어갈 수 있도록 업로드를 실제 진행.
- 인증: Git Credential Manager의 정상 browser 로그인 후 GitHub API에서 로그인 Symok03 및 대상 저장소 push 권한을 확인했다. 토큰/비밀번호를 명령 인수/로그/공유 파일에 보존하지 않았다.
- 대상: ddobagih/Hanium_Dreamup, 새 브랜치 `fix/walkmate-device-check-20261106`.
- 기준 검증: 원본 archive9341개 파일 blob이 f4108026dd61af4ce8cf2d4ab28979ddae70474f와 전부 일치. 기존 integration tip의 이후 조원 변경은 덮어쓰지 않고 원본 시점에서 분기했다.
- 게시: 소스/시험/문서42파일, 코드 커밋d9f84138dbf394474e61db4ef4aef1736c535496. 게시 복사본의 CRLF→LF만 정규화하고 git diff --check 통과. 원래 빌드 작업 복사본/APK는 변경하지 않았다.
- 원격 검증: push 뒤 GitHub ref를 다시 읽어 commit 일치를 확인하고, 완전한 recursive tree의42개 게시 blob을 로컬 게시 파일과 비교했다. 성공 tool 결과만으로 업로드를 단정하지 않았다.
- 제외: 신규 APK/모델/image/raw log/빌드 결과/키/비밀값/계정 데이터 없음. 원본/current/integration 브랜치 merge/force-push 없음. PR도 자동 생성하지 않았다.
- 범위: 검증된 기존 앱 수리와 문서의 게시다. 추가 모델·폰 검사·전체 CI/현장 검증은 하지 않았다. 이 기록과 provenance 보완은 뒤의 문서 전용 커밋으로 업로드한다.
- 로컬 증거: github-export-audit의 archive-base/full-archive-base-comparison/precommit-audit/publication-file-map/local-commit/remote-verification 자료. 이 로컬 보조 자료를 원격에서 제공했다고 주장하지 않는다.

## 이후 항목 템플릿

```markdown
## LOG-NNN — 작업 제목

- 시각/시간대:
- 입력 source/APK SHA 또는 대상 버전:
- 사용자 승인/범위:
- 관측 사실:
- 가설 및 반증 조건:
- 변경:
- 실제 검증: 명령/테스트 종류/실행 결과/산출물/대상 버전
- 실행하지 않은 범위와 한계:
- 결과: 해결/부분 해결/실패/미확인/사용자 결정 대기
- 근거: 저장소 또는 workspace 상대 경로, 필요한 symbol/라인
- 다음 단일 작업:
- 현황 보고서 갱신 내용:
```
