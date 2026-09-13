# 미학습 객체 영역·깊이 통합 시험 결과

시험일: 2026-09-09. 대상은 현행 custom primary 13클래스 밖의 객체 영역을 찾는 추가 후보이며, 기존 객체 인식 모델과 앱 동작은 교체하지 않았다. [통합 계획](/home/ddobagi/Hanium_Dreamup/docs/planning/walksafe-unknown-object-depth-test-20260909.md)에 따라 자료·모델·깊이·Android·독립 검토를 병렬로 진행했다.

## 현재 판단

**기존 모델 768과 추가 모델 640의 결과를 각자 원본 이미지 좌표로 복원해 연결하는 설계는 가능하다.** 실제 동시 실행 성능은 아직 시험하지 않았다. 결과는 같은 관측의 깊이에 연결해야 한다. 주 모델까지 640으로 바꿀 이유는 이번 시험에서 제시되지 않았다.

FastSAM-S는 두 해상도 모두 S20 GPU에서 실제 실행되었다. 추가 후보의 기본 입력은 **640부터 제품 통합 시험을 시작하는 것이 합리적**이다. 768은 이 표본에서 낮은 IoU 기준 재현율이 조금 높았지만 더 느리고, 정밀한 경계 기준(R75)은 좋아지지 않았다. 단, 후보 수를 20개로 제한하면 YOLOE의 재현율이 높아 **FastSAM이 모든 처리 예산에서 최선이라는 결론은 아니다**. 출시/현장 채택은 아직 확정하지 않는다.

![후보 수와 해상도에 따른 정답 재현율 및 S20 모델 호출 시간](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/unknown-object-depth/comparison.png)

## 1. 객체 영역: 실제 정답 평가

COCO val2017 중 예측을 보기 전에 고른 80장. 현재 primary 학습 클래스와 겹치는 객체 251개, 그 밖의 객체 694개, crowd 13개다. 이름을 무시하고 confidence순 일대일 IoU 매칭 후 집계했다. 아래 R50은 정답과 마스크 겹침 IoU≥0.5인 비율이다. 제품 현장 인식률이나 공식 COCO AP가 아니다.

| 후보 | 현행 13클래스 밖 객체 mask R50 / 최대100개 | mask R75 | 작은 13클래스 밖 객체 R50 | 최대20개로 제한한 R50 |
|---|---:|---:|---:|---:|
| FastSAM-S 640 | 393/694, **56.6%** | 39.3% | 47.1% | 28.1% |
| FastSAM-S 768 | 410/694, **59.1%** | 39.0% | 48.9% | 29.0% |
| YOLOE26n prompt-free 640 | 291/694, **41.9%** | 29.3% | 21.7% | 34.6% |
| YOLOE26n prompt-free 768 | 322/694, **46.4%** | 32.3% | 24.5% | 36.3% |

이번 정답 평가는 정적 이미지 시험이며, 동영상에서 같은 객체를 계속 찾는 능력·접근 판단은 시험하지 않았다. 고정 비교값은 conf0.25, 최대100개, 실제 정사각 입력640/768, FP32, batch1이다. FastSAM의 평균 후보 수는 약66개, YOLOE는18~20개다. 따라서 같은 confidence 값이 같은 오탐률/처리량을 뜻하지 않는다. max100에 도달한 이미지 수도 FastSAM26/22장, YOLOE각2장으로 다르다. 기존 모델은 같은 conf0.25에서 클래스명을 무시한 bbox 중첩이8/694였다. 이 값은 오분류도 중첩하면 세며 제품의 클래스별 임계값을 재현하지 않았으므로 기존 앱의 실패율로 쓰지 않는다.

FastSAM 768−640 R50 차이는 +2.45%p다. 선택된 이미지 단위 bootstrap의95% 구간은 +0.49~+4.59%p이며, 이는 이 표본의 재표집 변동만 나타낸다. 야외 분포 차이·후보 학습데이터 중복은 다루지 않았다. 후보가 한 번도 학습하지 않은 객체에 대한 일반화 시험으로 확대하지 않는다.

원래 COCO 전체 주석을 보존하고 crowd를 FN 분모에서 제외했다. COCO에 주석이 없는 모든 제안을 오탐으로 단정하지 않아 precision/AP는 보고하지 않는다. FastSAM은 NMS를 사용하지만 YOLOE26/기존YOLO26은 native end-to-end head이므로 동일 iou 인자가 같은 NMS 과정을 강제하지 않는다.

## 2. S20 실제 모델 실행 시간

Samsung SM-G981N, Android13, LiteRT1.4.0. 원본 앱과 분리된 self-targeting instrumentation APK에서 같은 공개 이미지의 미리 만든 FLOAT32 입력을 사용했다. 워밍업3회 제외 후20회 측정했다. CPU4는 통제된 기준선이며 이 휴대폰의 최적 CPU 스레드 선택이라는 뜻은 아니다.

| 모델 | 실행 | 호출 중앙값 | P95 | 이번 실행의 interpreter 준비 시간 |
|---|---|---:|---:|---:|
| FastSAM-S 640 | CPU4 | 1,044.6ms | 1,062.4ms | 0.10초 |
| FastSAM-S 640 | GPU | **160.8ms** | 164.0ms | 37.31초 |
| FastSAM-S 768 | CPU4 | 1,543.7ms | 1,584.0ms | 0.10초 |
| FastSAM-S 768 | GPU | **196.1ms** | 199.3ms | 75.77초 |

640은768보다 이번 GPU 호출 시간이 약18% 짧았다. **이는 모델 호출과 텐서 전달 시간이며, 카메라·전처리·최종 마스크 생성·깊이 결합·화면·기존 검출기와의 동시 실행을 포함하지 않는다.** 역수인 약6.2회/초와5.1회/초도 전체 앱 FPS가 아니다. 이 시험으로60fps를 충족했다고 할 수 없다. 최초 준비 시간은 이 실행에서 관측한 값이며 캐시 적용 전후나 매 실행 시작 시간을 일반화하지 않는다.

GPU 두 해상도는 native log에서289/289 노드 위임, OpenCL 초기화, GPU kernel 생성 후20회 성공을 확인했다. 앱 차원의 CPU fallback은 없었다. 모든 출력은 finite였고, 네 실행 모두 같은 모델·같은 입력의 PC TFLite 참조 출력에 atol/rtol1e-3 이내였다. 이 한 장의 수치 동등성을 모든 입력의 정확도 동등성으로 확대하지 않는다. thermal API 시작/종료는 모두0이었으며 장시간 발열·배터리·다른 앱 부하에 대한 시험은 아니다.

첫 GPU640 시험은 시험 APK의 optional OpenCL 선언 누락으로 OpenGL 경로의 transpose 지원 오류가 났다. 원본 앱에는 이미 선언이 있었다. 시험 APK에 해당 선언을 추가한 뒤 성공했다. GPU768 최초60초 외부 제한은 모델 준비 중 만료됐다. 시험 상한을 사용자 허용 최대5분 범위 안에서 확장하고180초 예산으로 다시 실행해 성공했다. 실패/중단 원출력도 보존했다.

## 3. PC 시간과 모델 변환

RTX5070Ti에서 이미지 decode+predict+CPU 출력 전달의 중앙값/P95는 FastSAM6409.36/13.53ms,76810.32/16.00ms, YOLOE6408.11/9.90ms,76810.26/11.66ms였다. 각 이미지의 단계 시간을 합한 뒤 percentile을 계산했다. RLE 저장·깊이 연결은 제외하며 다른 GPU 프로세스가 살아 있는 공유 PC 측정이다. 이 값을 휴대폰 속도로 사용할 수 없다.

FastSAM 640/768을 실제 TFLite로 변환했다(약47.4MB). 고정 COCO 첫4장×두 해상도에서 원본 PyTorch와 변환 모델의 후처리 결과 개수가 같았고, 대응 마스크의 최소IoU는0.999659로 시험 기준0.99를 통과했다. raw box 좌표의 정규화와 mask prototype의 NHWC 축을 맞춰 비교했다. 모델 안에 최종 NMS와 마스크 조립이 모두 들어 있다는 가정은 하지 않았다.

Qualcomm QNN/NPU는 이번에 실행하지 않았으며, 공개 Qualcomm640 수치를 이 S20 측정으로 바꾸어 부르지 않는다.

### YOLOE 모바일 변환 및 추가 비교

YOLOE 기본 export는 onnx2tf의 LRPC reshape에서 실패했다. batch1에서 동일한 slice/reshape로 바꾸는 변환 어댑터를 별도 실험 파일에만 적용했다. 첫 변환본은 end-to-end head의 agnostic/max_det 설정이 PC 시험과 달라 출력 개수가 달랐으므로 제외했다. 원본 체크포인트는 보존하고 `agnostic_nms=True,max_det=100`을 명시한 새 변환본을 만들었다. 공개4장×두 해상도에서 원본과 최종 후보 개수가 모두 같고 마스크는 픽셀까지 동일했다. 수정본 약16MB, 출력은 `[1,100,38]`과 mask prototype이다.

| YOLOE 검증 변환본 | CPU4 호출 중앙값/P95 | GPU delegate 요청 중앙값/P95 | 실제 GPU 위임 |
|---|---:|---:|---|
| 640 | 654.3/654.9ms | 674.7/681.6ms | 66/551 노드, 나머지 대부분 CPU |
| 768 | 966.9/971.2ms | 975.2/984.6ms | 66/551 노드, 나머지 대부분 CPU |

GPU 요청에서도 XNNPACK이432/486 노드를17개 partition으로 처리했다. 이 결과는 GPU와CPU의 혼합 실행이며 전 노드GPU 실행이 아니다. 이 짧은 시험에서GPU delegate를 추가한 속도 이득은 확인하지 못했다. 준비 시간은 GPU6403.04초/7686.06초였다. 모델 호출만 포함하므로 최종 mask/Depth 비용이 서로 다른 FastSAM과 전체 성능 순위로 직접 해석하지 않는다.

YOLOE640GPU는 원시 출력의 낮은 점수 두 행(score 약0.04837)이 순서를 바꿔 행별 strict allclose가 실패했다. 이를 결과JSON에 그대로 남겼다. 별도 검토자가 저장된 휴대폰 출력과PC 참조를 같은CPU 후처리로 비교한 결과, conf0.25를 통과한 최종 객체 수는640각8개/768각9개로 같았고 대응 마스크는 픽셀까지 같았다. 나머지3개 YOLOE 실행과 두 모델의 prototype tensor는 수치 허용오차를 통과했다.

원인 조사에서 YOLOE의FLOAT32 ADD23개가INT64 ADD3개와 같은ADDv4 opcode를 공유하는 것을 확인했다.640한정으로FLOAT32 연산용ADDv1 등록을 분리한 호환성 후보를 만들었다. 가중치·tensor·연산 옵션·INT64 연산·metadata는 그대로이며 새opcode1개와참조23개만 달라졌음을 기계적으로 확인했다. zero/gradient/고정 공개4장의12개 원시 출력은CPU에서 bit-exact였다.

| YOLOE640 GPU 호환성 실험 | 기존 검증 변환본 | ADD 등록 분리본 |
|---|---:|---:|
| GPU 위임 노드 | 66/551 | 492/551 |
| 모델 호출 중앙값/P95 | 674.7/681.6ms | **614.9/618.1ms** |
| 이번 interpreter 준비 | 3.04초 | 14.55초 |

추가 실기기20회에서도 모든 값finite, raw 참조 수치 비교가 통과했다. GPU 위임은 늘었지만 호출 시간 개선은약8.9%였고 FastSAM640160.8ms보다 여전히 길었다. **노드 개수 비율은 연산량이나GPU 사용률 비율이 아니다.** 나머지CPU 연산·partition 전환·출력 구조 비용을 분리 계측하지 않았으므로 추가 병목의 비중은 확정하지 않는다.768 호환성 분리본과 추가 그래프 튜닝은 수행하지 않았다. 이 호환성 실험은 학습이나 원본 앱 변경이 아니다.

후처리 계약도 남은 항목이다. 설치된 Ultralytics는 작은CPU 배치의 mask crop에서box 경계를 반올림하고GPU에서는 부동소수 경계를 사용한다. 독립 검토에서 같은 원시 출력이어도 한 마스크의CPU후처리와 기존GPU캐시 사이IoU가약0.949인 사례를 확인했다. 위 변환/휴대폰 저장출력 동등성은 같은CPU후처리끼리의 비교이며, COCO 전체 평가의GPU후처리나 아직 구현하지 않은Android마스크 복원과 완전히 같은 결과라고 주장하지 않는다.

## 4. 영역에 깊이 붙이기: 합성 시험과 공개 RGB-D

깊이 연결 프로토타입의44개 합성 시험을 작성·실행했고 별도 검토자가 다시 실행해 통과했다.640/768 letterbox 역변환, portrait/landscape, 경계·클리핑, 전경/배경 혼합,0/invalid/confidence 부족, 시각·프레임 불일치, 반복 raw timestamp, 광축 깊이와 직선거리, 분리 마스크를 검사했다. 미확인 깊이는 객체를 없애거나0m로 바꾸지 않고 거리 상태만 unknown으로 남긴다.

공개 UOIS 배포 예제의 RGB·XYZ·인스턴스GT가 함께 있는4장, 총53개 객체에 실제 후보 마스크와 깊이를 연결했다. 이53개에는 semantic class GT가 없어 전부 기존13클래스 밖이라고 분류하지 않는다.

| 후보 | 영역 IoU≥0.5 매칭 /53 | 그중 거리값 산출 | 미산출 |
|---|---:|---:|---:|
| FastSAM640 | 47 | 37 | 10 |
| FastSAM768 | 48 | 35 | 13 |
| YOLOE640 | 38 | 26 | 12 |
| YOLOE768 | 41 | 29 | 12 |

FastSAM의 거리 미산출10/13건은 모두 작은 분리 마스크 조각의 내부 표본 부족이 전체 결과를 unknown으로 만든 경우였다. 주 객체 영역은 정상 거리를 산출했다. 예를 들어 주성분13,102픽셀은 정상인데6픽셀 조각 때문에 전체가 unknown인 경우가 있었다. **768이 실제 깊이를 더 못 재서 나온 차이가 아니라 현재 연결성분 검증 정책의 영향**이다. GT 마스크를 넣어도9/53개가 이 보수 정책으로 unknown이다. 최초 결과를 유리하게 바꾸기 위해 정책을 사후 변경하지 않았다.

거리값이 나온 FastSAM64037건의 예측 영역 산출값과 GT 영역 내부의 같은 센서Z 중앙값 차이는 중앙값0.002, P95약0.0072였다. 이 수치의 작은 크기로 거리 정확도를 주장하지 않는다. UOIS 코드의 XYZ convention은 m이지만 개별 예제 생성 시 단위 변환/내참수가 제공되지 않아 **같은 원본Z에서 어떤 표본을 선택했는지의 차이**로만 해석한다. 물리적 실제 거리 오차·ARCore 정답·전체 객체의 성공률이 아니다. 결과JSON은 산출 가능한 조건부 집계와 미산출 수를 함께 보존한다.

별도 GraspNet RGB-D1장은 factor_depth=1000과 내참수를 실제 확인했지만 인스턴스GT가 없어 위53개 표에 포함하지 않았다. 전체 확보 자료는 RGB-D5장이다. 임의 confidence·내참수·시각을 생성하지 않았으며 공개 등록RGB-D는ARCore raw와 별도 입력 타입으로 처리했다.

## 5. 실제 ARCore의 현재 상태

연결 기기는 `SUPPORTED_INSTALLED`, `AUTOMATIC=true`, `RAW_DEPTH_ONLY=true`를 반환했다. declared manifest feature가false여도 실제 session 지원은true다.

사용자 확인대로 후면이 바닥을 향한 조건에서15.036초 관측했다. 서로 다른 카메라 프레임425개, raw depth 이미지392개, raw timestamp 변화385회를 받았지만 **raw/full의 양수 깊이 픽셀은0개**였다. 지원 여부와 자료 획득을 확인했으며, 유효한 객체 거리·ARCore 정확도는 확인하지 못했다. 객체가 보이는 장면과 실측 기준 거리 없이는 이 항목을 완료할 수 없다.

## 다음 단계: 제품 통합 전에 필요한 작업

1. 작은 분리 조각 때문에 전체 거리가 사라지는 정책을 개선한다. 주성분 거리·부분 깊이 미확인을 함께 유지하는 표현을 검토하고, 진짜 별개 장애물을 지우지 않는 독립 정답 자료로 재시험한다.
2. 처리 가능한 후보 수에 맞춰FastSAM/YOLOE를 선택한다. 영상 전체 상위 점수20개만 남기는 정책은 중요 물체를 놓칠 수 있으므로 전방영역·깊이 유효성·영역 중복 기준도 검증한다. 단순 점수순 제한의 결과와 구분한다.
3. 선택 모델의 마스크 복원·깊이 연결을 Android 시험 경로에 넣고 기존 검출기·ARCore와 동시에 실행해 전체 지연·유효 결과 빈도·메모리·발열을 잰다. 현재 제품 자료구조는 단일polygon 중심이어서 hole/분리 영역을 보존할 최소 변경이 필요하다.
4. 물체가 보이는 실기기 장면에서 기준 거리와 비교한다. 이후 별도 합의한 순서대로 연속 추적·접근 판단·휴대폰 움직임 보정을 진행한다.

현재 시험으로 미학습 물체 영역을 추가 확보하고 깊이와 연결하는 접근의 가능성, 처리 예산에 따른 후보 순위, 기기 실행 비용, 다음 개선 지점을 확인했다. 원본 앱에 이 후보를 활성화하거나 모델을 교체하지 않았다.

## 근거와 재현 산출물

- [후보 상세표·정확도·속도·모델변환](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/unknown-object-depth/candidates/REPORT.md)
- [기기 원출력 집계](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/unknown-object-depth/android/phone-summary.json)
- [마스크-깊이 실제 결합 결과](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/unknown-object-depth/integration/rgbd-summary.json)
- [깊이 프로토타입·합성 시험](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/unknown-object-depth/depth/RESULTS.md)
- [COCO 정답·선택·검증](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/unknown-object-depth/fixtures/README.md)
- [RGB-D 출처·정답·단위 제한](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/unknown-object-depth/rgbd/VALIDATION_REPORT.md)
- [독립 검토](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/unknown-object-depth/review.md)
- [휴대폰 저장 출력의 독립 후처리 동등성](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/unknown-object-depth/review_phone_equivalence.json)
- [Root 최종 확인](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/unknown-object-depth/final-verification.json)
- [YOLOE GPU 호환성 변환·가중치 보존](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/unknown-object-depth/candidates/exports/yoloe640_gpu_opcode/README.md)
- [시험 APK와 실행 계약](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/unknown-object-depth/android/audit.md)
- [원본 모델·설정 보존 기준](/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/unknown-object-depth/baseline.json)

공식 문서: [FastSAM](https://docs.ultralytics.com/models/fast-sam/), [YOLOE](https://docs.ultralytics.com/models/yoloe/), [Qualcomm FastSam-S](https://huggingface.co/qualcomm/FastSam-S), [ARCore Raw Depth](https://developers.google.com/ar/develop/java/depth/raw-depth), [Android native library 선언](https://developer.android.com/guide/topics/manifest/uses-native-library-element).
