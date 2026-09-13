# WalkSafe 원본 앱 GPU·영상 추적 개선 — 기술 보고서

기준: 2026-09-08 KST, **Phase5 최종 빌드·구성요소 시험·설치 확인 결과**와 현재 소스. 설치 확인 시점은 17:07이다. 아래 성능 수치는 S20 SM-G981N / Android 13(API 33)의 구성요소 시험이며, 실제 Main 화면의 FPS나 보행 정확도가 아니다. 이번 변경의 구현·검증 범위를 정리하며 전체 프로젝트 완료를 뜻하지 않는다.

[단계별 개선을 짧게 이해하기]

카메라 영상 → **입력 만들기(CPU)** → **객체 판정(GPU)** → 영상 추적·현재 Depth 결합 → 위험 판단·안내

| 구간 | 이전 평균 → 개선 후 평균 | 빨라진 이유 |
|---|---:|---|
| 입력 만들기 | 약 138ms → 79ms | 색 변환·크기 조정을 한 과정으로 합쳐 중간 이미지 생성과 중복 처리를 줄였다. 이전 138ms는 YUV 변환 57.03ms와 모델 전처리 81.37ms의 합이다. |
| 모델의 객체 판정 | 약 270ms → 73ms | 가중치와 768 입력 크기를 유지하며 연산 형식의 GPU 호환 문제를 고쳤다. 주요 연산을 GPU가 맡아 가장 큰 시간 감소를 얻었다. |
| 인식 전체 | 약 411ms → 160ms | 위 두 단계와 결과 해석 등을 포함한 합계다. ARCore·추적·음성 안내까지의 전체 시간은 아니다. |

검출 사이의 박스 위치는 가벼운 영상 추적으로 갱신한다. 이 기능은 새 객체를 매번 판정하는 것과 구분하며 끊김을 줄이는 역할을 한다. 추론 주기도 실제 처리 시간과 발열에 맞춰 조절한다.

GPU 초기화 캐시는 별도의 시작 단계 개선이다. 같은 기기의 측정에서 첫 준비 약 14.3초, 저장된 준비 정보를 재사용한 후속 준비 약 0.07초였다. 매 프레임의 추론 시간에 더하거나 빼는 수치가 아니다.

이전·이후 값은 같은 S20의 서로 다른 구성요소 관측 평균이다. 이전 ARCore 관측은 주로 TRACKING, 최종 관측은 PAUSED였고 열 상태·장면도 동일하게 통제하지 않았으므로, 감소량 전부를 코드 변경 효과로 확정하거나 실보행 보장값으로 해석하지 않는다. 마지막 시험에서는 유효 Depth가 없었다.

- 이전 평균 근거: [CPU 관측](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/navigation-runtime-followup/s20/adaptive-optimized-summary.json).
- 개선 후 평균 근거: [최종 GPU 관측](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase5-adaptive-production-records.json).

[현재 결과]

- CPU 전처리·추적·판단과 GPU 모델 연산을 결합하는 경로를 구현했다. 최신 제품 설정은 **FP32 768 GPU 호환 모델 + GPU strict + CPU 4스레드 복구 + GPU serialization cache**이다. 입력 해상도·가중치·클래스 임계값은 유지했다.
- Main에는 통합 YUV 전처리, OpenCV 영상 추적, 현재 영상 geometry와 Depth 결합, 적응형 추론 주기·추적 예산을 연결했다. 채택한 추론 실행은 **순차 실행 + 적응형 주기**이다. 다음 프레임 전처리 중첩과 CPU/GPU 두 실행기는 비교용 벤치마크에만 있다.
- GPU 호환 후보의 18초 순차 시험은 유효 완료 **5.61Hz**, 캡처→완료 중앙값 **183.5ms**였다. 다음 프레임 전처리 중첩은 **8.50Hz / 165ms**였다. CPU/GPU 두 실행기는 전체 8.00Hz 중 순서 역전 결과 47개를 폐기하여 유효 완료는 **5.39Hz**였다. 이 수치를 제품 Main FPS로 적용하지 않는다.
- Phase5 최종 설정의 적응형 구성요소 시험은 관측 18.031초에 신선한 결과 74개, 관측 구간 기준 **4.104Hz**, 캡처→완료 **평균 163.28ms / 최대 199ms**였다. 카메라 tracking은 전체 `PAUSED`, 양수 Depth·객체 수는 0으로 실제 거리·추적 정확도는 검증하지 못했다.
- Phase5 빌드·unit·lint와 실기기 JUnit **5개**를 통과했다. COCO 20장 모두 순서 무관 raw·필터 후 탐지 비교를 통과하고 GT 집계가 같았다. 같은 행 번호 raw 비교의 3/20 불일치는 진단으로 보존했다. 최종 APK 내부 모델·설정도 확인했다.

[7단계 구현·검증 상태]

| 단계 | 현재 구현·수행 내용 | 확인된 범위와 남은 항목 |
|---|---|---|
| 1. 기준선·측정 계약 | 모델 hash, 전처리·delegate, 워밍업/측정 구간, 캡처 시각, 지연·발열·객체 수 기록 | S20 Phase1–5 구성요소 실행 근거 확보. 각 실행의 장면·발열을 동일하게 통제한 반복 비교는 아님 |
| 2. 전처리 통합 | `FUSED_YUV_TO_TENSOR`: 선택한 YUV sample에서 바로 float tensor 작성, 전체 ARGB 중간 배열 제거 | 기존 색·좌표·반올림·padding 계약을 보존하는 회귀 검사와 CPU 비교 수행. 한 인스턴스의 반환 tensor는 재사용되므로 중첩에는 별도 소유권 필요 |
| 3. GPU·CPU 복구 | strict GPU, 동일 스레드 수명주기, CPU 복구, cache 연결; CPU 2/4/6·GPU 비교 | 후보 444/477 GPU 위임, Phase5 구성요소 시험 74회 GPU 실행 확인. Main 사용 흐름에서 GPU/CPU 전환·복구의 최종 E2E는 미확인 |
| 4. 실제 영상 추적 | 192×144 gray, OpenCV pyramidal LK, 최대 40 특징점, 회전·크기 변화 허용; 지연 검출을 원본→현재 영상으로 연결 | VOT2016 `bag` 196프레임의 고정·적응형 예산 S20 시험 수행. 긴 지연에서 추적 소실·ID 단절이 남음 |
| 5. 현재 Depth·운동 | 현재 추적 geometry와 현재 Depth/pose 연결, 검출 확인 횟수와 영상 관측 분리, 운동 시간창 보완 | 코드·회귀 범위의 계약 보존. 실제 거리·속도·TTC 정답 검증은 미수행 |
| 6. Main 통합·적응형 실행 | 통합 전처리·영상 추적·현재 Depth·적응형 추론/추적 예산을 Main에 연결 | 소스 연결 확인. 초기 CPU 경로를 준비하고 GPU 초기화를 별도 스레드에서 수행하는 코드 포함. 실제 사용 흐름·음성까지의 최종 E2E는 미확인 |
| 7. 선정·최종 검증 | GPU 호환 strict / CPU4 복구 / cache 사용 / Main 순차 적응형 조합 선정 | Phase5 build 성공, unit 2,473건 중 실패·오류 0·skip 1, lint 오류 0·warning 140. 실기기 JUnit 5개 통과, 최종 APK·내부 asset 확인. 설치본과 전달본 APK hash 일치 |

- 계획 근거: [PLAN.md](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/PLAN.md). 전처리 계약: [preprocessing-fusion.md](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/preprocessing-fusion.md).
- 현재 소스: [MainActivity.kt](/home/ddobagi/Hanium_Dreamup/apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt), [two_model_runtime.json](/home/ddobagi/Hanium_Dreamup/apps/android/app/src/main/assets/model-config/two_model_runtime.json). 설정 version은 `walksafe-android-runtime-unified-768-gpu-tracking-20260908`이다. 이전 CPU 설정이나 실험 APK와 최신 소스 설정을 구분해야 한다.

[CPU·GPU 역할별 성능 비교]

공통으로 ARCore 카메라·Depth를 실행한 구성요소 시험이다. 각 행은 워밍업 5초 후 18초 측정이며 지연 단위는 ms, 대표값은 중앙값이다. **유효 Hz는 측정 구간 안에 접수·완료하고 시각·순서·출처 조건을 충족한 결과 수/측정 시간**이다. 객체가 정확히 탐지됐다는 뜻은 아니다. 초기 하네스의 유효성 미계측은 `—`로 표시하며 전체 완료를 대신 유효 완료로 계산하지 않는다.

| 모델·실행 | 전처리 | 모델 추론 | detector 전체 | 캡처→완료 중앙값 / P95 | 유효 Hz (전체 Hz) | 유효/전체 완료 | 폐기 / 미판정 |
|---|---|---:|---:|---:|---:|---:|---:|
| 원본 CPU 4 | 기존 2단계 | 270 | 401 | 428 / 438 | — (2.28) | 미계측/41 | 0 / 41 |
| 원본 CPU 2 | 통합 | 271 | 324 | 348 / 362 | 2.89 (2.89) | 52/52 | 0 / 0 |
| 원본 CPU 4 | 통합 | 270 | 323 | 347 / 359 | — (2.89) | 미계측/52 | 0 / 52 |
| 원본 CPU 6 | 통합 | 271 | 325 | 350 / 362 | 2.83 (2.83) | 51/51 | 0 / 0 |
| 원본 GPU strict | 기존 2단계 | 285 | 422 | 449 / 465 | — (2.22) | 미계측/40 | 0 / 40 |
| 원본 GPU 정밀도 손실 허용 | 통합 | 283 | 346 | 368 / 396 | — (2.67) | 미계측/48 | 0 / 48 |
| GPU 호환 후보 strict, 순차 | 통합 | 75 | 156 | 183.5 / 201 | 5.61 (5.61) | 101/101 | 0 / 0 |
| GPU 호환 후보 strict, 다음 프레임 CPU 준비 중첩 | 통합 | 78 | 142 | 165 / 199 | 8.50 (8.50) | 153/153 | 0 / 0 |
| GPU 호환 후보, CPU2 + strict GPU 두 실행기 | 통합 | 85 | 169.5 | 191 / 401 | **5.39 (8.00)** | **97/144** | **47 / 0** |

- 측정 경계에서 추가 제외한 수는 각 행의 **워밍업 접수 후 측정 중 완료 / 측정 종료 후 꼬리 완료** 순으로, 중첩 `2/2`, 두 실행기 `3/2`, 나머지 7개 행은 각각 `1/1`이다. 위 표의 폐기는 측정 구간 내 완료 중 제외한 수다. 미판정은 폐기 0이어도 통과를 의미하지 않는다.
- 지연 표본은 구간 안에 접수된 전체 완료를 사용하므로, 폐기된 결과와 구간 종료 후 꼬리 완료도 포함한다. 특히 두 실행기의 P95는 유효 결과만의 지연이 아니다. `1,000/중앙 지연`도 유효 Hz와 다를 수 있다.

- CPU 스레드를 2→4→6으로 늘리는 것만으로 이 조건의 개선은 확인되지 않았다. 통합 전처리에서 CPU 모델 추론은 약 270ms로 유지되고 detector 전체 비용이 줄었다.
- 원본 모델은 GPU를 요청해도 26/477개 연산만 GPU 위임된 로그가 있다. `activeDelegate=gpu`만으로 모델 전체가 GPU에서 실행되거나 빨라졌다고 판정하지 않는다.
- 다음 프레임 중첩은 독립 tensor 슬롯 2개를 소유한다. 슬롯 14,155,776B와 전처리 tensor 7,077,888B를 별도 기록했다. 18초 동안 CPU 준비 구간과 GPU 호출 구간이 약 9.643초 겹쳤다. 이는 **호스트에서 기록한 API 호출 구간의 중첩**이며 하드웨어 실행을 프로파일링한 증거는 아니다.
- CPU/GPU 두 실행기는 약 14.794초의 호출 구간 중첩을 보였으나 **144개 완료 중 47개(32.64%)를 `OLDER_THAN_COMPLETED`로 폐기**했다. 유효 완료 5.39Hz는 GPU 순차 5.61Hz보다 낮았고, 전체 완료의 캡처→완료 P95도 401ms로 커졌다.
- 다음 프레임 준비 중첩은 빠른 후보이나 Main의 적응형 주기·영상 추적·음성을 함께 실행하는 지속 시험을 하지 않았다. 현재 제품에는 순차 적응형 실행을 선정했다. 중첩의 120초 결과만으로 Main 채택 효과를 확정하지 않는다.
- 근거: [Phase1 분석](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase1-results.json), [CPU4·GPU 후보·중첩 분석](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/comparison-so-far.json), [CPU2 원로그](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase2-production-cpu2-fused-instrumentation.txt), [CPU6 원로그](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase2-production-cpu6-fused-instrumentation.txt), [GPU 정밀도 허용 원로그](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase1-gpu-fused-relaxed-instrumentation.txt), [두 실행기 분석](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase2-compatible-gpu-dual-summary.json). CPU2/6와 정밀도 허용 행은 저장 원로그를 기존 `analyze_benchmark.analyze()`로 읽기 전용 재집계했다.

120초 지속 시험도 별도로 수행했다.

| GPU 호환 strict·통합 전처리 | 유효 Hz (전체 Hz) | 유효/전체 완료 | 폐기 / 미판정 | 모델 중앙값 | 캡처→완료 중앙값 / P95 | thermal status 중앙값 / 범위 |
|---|---:|---:|---:|---:|---:|---:|
| 순차 | 4.35 (4.35) | 522/522 | 0 / 0 | 100 | 231 / 250 | 1 / 1–2 |
| 다음 프레임 준비 중첩 | 7.475 (7.475) | 897/897 | 0 / 0 | 101 | 209 / 238 | 2 / 0–2 |

- 워밍업 접수 후 측정 중 완료 / 측정 종료 후 꼬리 완료의 추가 제외는 순차 `1/1`, 중첩 `1/2`이다.
- 두 실행은 초기 온도·열 상태·순서를 동일하게 통제하지 않았다. 이 결과는 장기 동작의 관측값이며, 확정적인 개선 배율이나 발열 우열로 해석하지 않는다. 두 실행 모두 양수 Raw Depth와 감지 객체가 0인 장면이었다.
- 근거: [120초 순차 분석](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase2-compatible-sequential-120s-summary.json), [120초 중첩 분석](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase2-compatible-overlap-120s-summary.json).

[모델 변경의 정확한 범위]

- 채택 후보는 재학습·양자화·640 축소 모델이 아니다. 기존 **FLOAT32 768** 모델의 FLOAT32 MUL 91개와 ADD 21개, 총 **112개 노드의 opcode 참조**만 새 v1 opcode 두 항목으로 연결했다. 기존 opcode 25개와 INT64 MUL/ADD 버전은 유지했다.
- 가중치, tensor, 연산 옵션, metadata와 associated ZIP, 입력 `[1,768,768,3]` 및 출력 `[1,300,6]` FLOAT32 계약을 보존했다. 파일은 24B 늘어난 9,984,517B다.
- 원본 SHA256: `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19`.
- 후보 SHA256: `25ef119d4f07e1bffbd4ac6827fa46a44a0fa3deaaedfb0c867e7c655f813559`.
- 저장 CPU 동등성 시험은 zero/gradient와 실제 JPEG 16장의 18개 입력 × CPU XNNPACK/builtin 2경로 = **36 pair 모두 raw float32 bytes 동일**, 최대 절대 오차 0이다. 이 검사는 변환 동등성에 관한 것이며 Android 전처리나 GPU 정확도 전반을 증명하지 않는다.
- 후보의 native GPU 로그는 **444/477개 연산 위임**, GPU kernel 1개 생성을 보인다. 나머지 33개 연산이 남는 혼합 실행이므로 전체 GPU 실행이라고 부르지 않는다. 연산 개수 비율은 실행시간 비율이 아니다.
- FP16 768과 FP32 640은 별도 내보내기 후보이며 현재 채택 조합이 아니다. INT8은 생성하지 않았다.
- 근거: [GPU 호환 변환·보존 설명](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/model-candidates/gpu-compatible-768/README.md), [CPU 동등성 JSON](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/model-candidates/gpu-compatible-768/cpu-equivalence.json), [native 위임 로그](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase2-tflite-recovered-logcat.txt), [다른 후보 상태](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/model-candidates/README.md).

[CPU·GPU 출력과 소수 GT 확인]

- COCO val2017의 양성 이미지 20장, 대응 7개 클래스의 GT 110개를 사용했다. 원본 CPU4와 후보 strict GPU에 **같은 FLOAT32 입력 tensor**를 공급했다. GPU 정밀도 손실 허용·앱 CPU 복구 없이 실행했다.
- 실제 클래스 임계값을 적용하고, 같은 클래스·IoU ≥0.5의 최대 일대일 GT 매칭으로 집계했다. CPU/GPU 모두 **TP 69, FN 41, FP 27**, precision 0.71875, recall 0.62727이다. GT가 맞은 이미지 18/20, CPU 참조의 GT 매칭 소실·후보 추가 매칭은 각각 0이다.
- 필터 후 탐지 비교는 **20/20 통과**했다. 이때 박스·점수 허용 오차는 각각 0.01이다. CPU와 GPU가 모든 raw bytes까지 같다는 뜻은 아니다.
- Phase3의 같은 행 번호 raw 비교는 3/20에서 허용 범위를 벗어났다. Phase4와 최종 Phase5에서는 300개 출력 행을 순서 무관 최대 일대일 대응으로 비교했으며, **20/20에서 300/300행 대응**했다. 클래스 ID 정확 일치, 클래스 점수 gate 소속 동일, 좌표 절대 오차 ≤0.05(raw tensor 단위), 점수 오차 ≤0.001 조건이다.
- Phase5는 **raw-set·필터 후 결과 동등성·GT 매칭 보존**을 기능 판정 기준으로 명시한 뒤 재실행해 2개 테스트를 통과했다. 같은 행 번호 비교의 3/20 불일치는 `DIAGNOSTIC_ONLY`로 계속 기록한다. Phase3/4의 과거 `FAIL`도 보존하며, 유일한 행 순열이나 GPU 수치의 byte 단위 완전 일치를 증명한 것으로 설명하지 않는다.
- 이 20장은 비교적 큰 양성 객체를 선호하는 제한된 sanity fixture다. 전체 13개 클래스, 독립 평가 세트, COCO mAP, 보행 영상·작은 장애물·거리 정확도 평가가 아니다. 학습/개발 중 COCO 노출 가능성도 배제하지 않았다.
- 근거: [fixture 선정·범위](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/positive-fixtures/README.md), [Phase3 출력·GT 기록](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase3-positive-fixtures-records.json), [Phase4 raw-set·GT 기록](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase4-positive-fixtures-records.json), [Phase5 최종 출력·GT 기록](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase5-positive-fixtures-records.json).

[실제 영상 추적 결과]

- VOT2016 `bag` 한 시퀀스, 196프레임·30fps·6.533초를 S20에서 구성요소 재생했다. 400ms마다만 source 정답 박스를 검출 결과 대신 공급하고, 지정 지연 후 전달했다. 중간 정답은 추적 입력에 넣지 않고 출력 저장 후 평가에만 사용했다.
- 최초 Kotlin patch 추적은 400ms 지연에서 **0/184프레임** 출력으로 실패했다. OpenCV native로 전환하고 회전·크기 변화를 허용한 후 아래 결과를 얻었다. 첫 Android 하네스의 예산 보류 시 source/ID 소비 문제를 고친 **Phase3** 수치다.

| 전달 지연 | 호출 예산 | 회전·크기 허용 | 박스 출력/평가 프레임 | 영상시간당 유효 관측 Hz | 전체 IoU¹ / 출력 조건부 IoU | 단일 타깃 ID 변화 |
|---:|---:|---|---:|---:|---:|---:|
| 0ms | 5ms | 예 | 180/196 | 27.55 | 0.774 / 0.842 | 3 |
| 100ms | 5ms | 예 | 149/193 | 23.16 | 0.609 / 0.789 | 3 |
| 200ms | 5ms | 예 | 113/190 | 17.84 | 0.465 / 0.782 | 5 |
| 200ms | 10ms | 예 | 135/190 | 21.32 | 0.550 / 0.775 | 6 |
| 200ms | 15ms | 예 | 143/190 | 22.58 | 0.585 / 0.778 | 6 |
| 200ms | 5ms | 아니요 | 5/190 | 0.79 | 0.024 / 0.927 | 0 |
| 400ms | 5ms | 예 | 58/184 | 9.46 | 0.234 / 0.741 | 2 |
| 400ms | 10ms | 예 | 87/184 | 14.18 | 0.357 / 0.755 | 4 |

¹ 전체 IoU는 LOST 프레임을 0으로 포함한다. 출력된 일부 프레임만의 높은 IoU나 출력이 거의 없는 조건의 ID 변화 0을 성공으로 해석하지 않는다.

- 지연 축소·예산 확대에 따른 이 영상의 출력 증가가 보이나, 소실과 ID 단절은 남는다. 400ms/10ms에서도 184개 중 97프레임은 출력하지 못했다. 이 표는 실제 YOLO 검출 품질·다중 객체 IDF1/IDSW·Main 카메라 cadence를 측정하지 않았다.
- 위 표에서 출력하지 못한 프레임 수는 전체 평가 프레임−박스 출력 수이며, 순서대로 16·44·77·55·47·185·126·97개다. 영상 관측 Hz의 분자는 실제 게시한 추적 결과만 포함한다.
- 근거: [검증 방법·초기 실패·하네스 변경](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/tracking-validation/README.md), [Phase3 전체 8조건 JSON](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase3-vot-matrix.json).

Phase5에서는 실제 제품의 적응형 예산 정책을 적용한 200/400ms 지연 시험을 각각 통과했다.

| 전달 지연 | 박스 출력/평가 프레임 | 미출력 | 영상시간당 유효 관측 Hz | 전체 IoU / 출력 조건부 IoU | 단일 타깃 ID 변화 |
|---:|---:|---:|---:|---:|---:|
| 200ms | 134/190 | 56 | 21.158 | 0.546513 / 0.774906 | 6 |
| 400ms | 87/184 | 97 | 14.185 | 0.357005 / 0.755045 | 4 |

- 두 시험 모두 196개 accepted gray의 예산 histogram은 첫 6개 **5ms**, 이후 190개 **9.999999ms**였다. 검출 결과가 도착하기 전에 조정되므로 실제 tracker 호출 예산은 모두 9.999999ms다.
- 호출 예산은 실행 중 native LK를 강제 중단하는 한도가 아니다. 200ms 시험은 예산 초과 호출 17개 중 검증된 geometry 게시 1개·미게시 16개, 400ms 시험은 31개 중 게시 1개·미게시 30개였다. 완료 후 검증된 결과의 예산 초과와 미완료 `TIME_BUDGET_EXCEEDED` 보류를 구분해 기록했다.
- 중간 결과 게시가 전혀 없는 실패를 막는 계측 테스트 통과이며, 보행 환경의 추적 품질 기준을 충족했다고 판정한 것은 아니다.
- 근거: [Phase5 적응형 200ms](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase5-vot-adaptive-200-records.json), [Phase5 적응형 400ms](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase5-vot-adaptive-400-records.json).

[최종 설정의 적응형 기기 관측]

- Phase5의 별도 ARCore 하네스는 최종 제품 설정과 통합 전처리·적응형 추론 정책을 실행했다. **관측 18.031초의 완료 74개 모두 GPU 활성·출처 일치·800ms 이내 신선한 결과**, 관측 구간당 4.104Hz였다. 폐기·오류 기록은 0이다. 이 하네스는 관측 전체 74개를 집계하며, 앞 벤치마크와 같은 워밍업/꼬리 제외 계약을 적용한 비교값은 아니다.
- 아래 값은 **평균**이다: 모델 73.42ms, 전처리 78.80ms, 파싱 6.69ms, detector 전체 160.01ms, 캡처→완료 163.28ms(최대 199ms). 종료 시 목표 간격 225ms·cooldown 42ms, 최대 진행 중 추론 1개, 종료 후 보유 Image 0, cleanup 오류 0을 기록했다.
- **527개 frame 모두 tracking `PAUSED`, 양수 Raw/Full Depth 0, 감지 객체 0, 추론 74회 모두 pose unavailable**이었다. 앞선 Phase2의 `TRACKING`·양수 Full Depth 장면과 다른 상태다. 최종 설정의 추론·주기·자원 정리 관측 근거이며, 실제 거리·ARCore tracking·현재 Depth 결합 품질의 성공 근거가 아니다.
- Activity·Main 화면·위험 음성을 실행하지 않았다. 전체 테스트 시간 약 34.4초에는 모델 초기화 등이 포함되며, cache 재사용이 매번 보장된다는 뜻이 아니다. 명시적인 cache 재사용 증거는 아래 Phase4 시험이다.
- 근거: [Phase5 적응형 최종 설정 기록](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase5-adaptive-production-records.json), [Phase5 기기 테스트 요약](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase5-device-test-summary.json).

[GPU 초기화 캐시]

- Phase4 첫 실행에서 `Created TensorFlow Lite delegate for GPU`→`Initialized OpenCL-based API` 로그 간격은 **14.302초**였다. 후속 실행에서는 같은 구간이 **69ms**였고, `Found serialized data` 10,626,488B 및 `Initialized OpenCL-based API from serialized data`를 명시했다.
- 두 실행 모두 444/477 GPU 위임과 같은 cache token, GPU 활성·앱 CPU fallback 미사용을 기록했다. cache가 설정됐다는 상태와 실제 재사용 로그를 함께 확인한 결과다.
- 이 수치는 native 초기화 로그 사이의 간격이다. 앱 전체 시작 시간·첫 안내까지의 지연·항상 보장되는 cache 효과가 아니다. cache 무효화 조건 전반과 최신 Main 전환의 최종 사용 흐름은 미검증이다.
- 근거: [첫 실행 native 로그](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase4-cache-first-gpu-logcat.txt), [후속 실행 native 로그](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase4-cache-second-gpu-logcat.txt), [첫 실행 계측](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase4-cache-first-gpu-instrumentation.txt), [후속 실행 계측](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase4-cache-second-gpu-instrumentation.txt).

[FPS·신선도 해석과 미검증 범위]

- **카메라/ARCore API 빈도**: 18초 GPU 순차 시험은 고유 camera timestamp 528개, 약 29.33Hz다. 같은 실행의 유효 검출 완료는 5.61Hz다. API에서 영상을 받는 빈도와 모델이 새 결과를 만드는 빈도는 다르다.
- **Depth 빈도**: timestamp가 자주 바뀌고 API가 Depth를 반환해도 새롭고 정확한 거리 관측을 증명하지 않는다. GPU 비교 장면의 양수 Raw Depth는 0이었다. 일부 실행은 smoothed Full Depth 양수 픽셀이 있었으나 거리 GT가 없으며, 유효 객체 거리·속도·위험 판단의 근거로 사용할 수 없다.
- **추적 빈도**: 위 9.46–27.55Hz 등은 저장 영상 시간당 출력 관측률이다. replay의 처리 wall-time FPS, 카메라 FPS, 독립 검출 빈도와 구분한다. 박스를 유지해 30회 표시하는 것도 새 영상 관측 30회가 아니다.
- **제품 신선도 계약**: 원래 검출 시각, 현재 영상 추적 시각, 원래 Depth 시각을 분리한다. 추적·표시만으로 검출 800ms 나이 제한이나 검출 3회 확인을 갱신하지 않는다. 같은 Raw Depth 재투영을 새 독립 깊이로 세지 않고, epoch·좌표계·추적 ID 단절 시 운동 이력을 끊는다.
- **미검증**: Main 화면과 음성까지의 실제 E2E, GPU/CPU 전환·수명주기의 최종 사용 흐름, 객체가 있는 동적 보행 장면, 실측 거리·속도·TTC, 다중 객체 추적, 장시간 열 조건을 통제한 반복 시험. 구성요소 테스트 통과와 APK 생성만으로 이 범위를 완료했다고 보고하지 않는다.

[최종 빌드·APK 근거]

- Phase5 build는 **58초, 성공**으로 종료했다. unit 2,473건 중 실패·오류 0·skip 1, lint 오류 0·warning 140이다. GPU/OpenCV 추가 의존성의 새 버전 안내 3건이 포함되며 라이브러리 업그레이드는 이번 범위 밖이다.
- 실기기 JUnit은 양성 fixture 2개, 적응형 VOT 200ms 1개·400ms 1개, 최종 설정 적응형 관측 1개로 **총 5개 통과**했다. 각 시험의 품질·장면 한계는 해당 절에 명시했다.
- 사용자 APK: [WalkSafe-original-user-20260908-gpu-tracking-debug.apk](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/outputs/WalkSafe-original-user-20260908-gpu-tracking-debug.apk), 394,210,214B. SHA256 `e8ac85c39072c0d6312158b9b47ee1c3c9c0e1ff9bdaa7c664ea246c5ac1db7d`.
- APK 내부 활성 모델 SHA256·입력/출력·설정 version과 GPU strict·CPU4 복구·cache 설정을 확인했다. 원본 FP32 모델도 APK 안에 보존되어 hash가 기존 값과 같다. 보고서 작성 시 전달 APK·테스트 APK의 전체 hash와 사용자 APK 내부 두 모델 hash·설정을 manifest와 직접 재대조했다.
- 설치 검증에서 **기기 설치본과 전달본 APK SHA256이 일치**했다. `install -r`로 기존 UID 10360, 최초 설치 시각 2026-08-30 23:33:34, 카메라·마이크·정밀 위치 권한을 유지했다. Main 자동 실행·앱 데이터 초기화는 하지 않았으며 계정 세션 E2E 재시험은 미수행이다.
- 전달 자료: [사용자 APK](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/outputs/WalkSafe-original-user-20260908-gpu-tracking-debug.apk), [검증 집계 JSON](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/outputs/WalkSafe-gpu-tracking-validation.json). APK hash 요약은 `e8ac85c3…5ac1db7d`이며 전체 값은 위와 manifest에 보존했다.
- 소스 기준 HEAD는 `37dd42c8939cce5b9c43e4d620c18f3841e0d01f`다. 이번 변경은 **로컬 WIP**이며 commit·push하지 않았다.
- 근거: [Phase5 빌드 로그](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/build-phase5.log), [Phase5 APK·asset·unit·lint manifest](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/apk/phase5-final-manifest.json), [Phase5 실기기 판정 요약](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase5-device-test-summary.json), [설치본 대조 기록](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/heterogeneous-runtime/device/phase5-install-verification.json). 최종 전달·작업 취합은 Root가 담당한다.
