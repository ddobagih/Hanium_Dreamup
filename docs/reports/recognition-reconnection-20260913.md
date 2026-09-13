# 객체 인식 실행 복구 및 동일 자료 검증

## 반영 내용

- MainActivity의 모델 실행 준비를 카메라 성능 점검과 분리했다. 보행 카메라 준비 시 설정된 GPU 주 모델을 고정 자료로 준비하고, 추론 작업이 끝나는 지점에서 임시 CPU 모델을 교체한다. 이후 FastSAM 서비스도 별도로 시작한다. 성능 점검 화면이 카메라를 점유하는 기존 경로는 재활성화하지 않았다. 이번 준비는 최적 조합을 탐색하는 5분 벤치마크가 아니다.
- CameraTestActivity에 독립 FastSAM 실행·영역 오버레이·마스크 깊이 연결을 추가했다. 주 모델은 `분류 <이름>`, FastSAM은 `이름 없는 영역`으로 표시한다. FastSAM의 영역은 이미 알려진 객체도 포함하며, 모두 미학습 물체라고 주장하지 않는다. 실제 원본 프레임/깊이/화면 변환을 동결해서 연결하고 오래된 결과·이전 화면 세대 결과는 표시하지 않는다.
- 주 모델 카메라 입력은 RGB uint8 bilinear resize, 원본 모델 방식의 letterbox, /255 정규화로 변경했다. 기존 FastSAM용 검증된 전처리 코드를 RgbLetterboxPreprocessor로 공통화했다. 비교용 기존 nearest 전처리는 남겨 두었다.
- 화면 회전 정보를 카메라 테스트, ARCore 보행, CameraX 대체 경로에 전달한다. 모델 출력은 다시 센서 좌표로 되돌린 후 기존 깊이·추적·화면 변환을 적용한다.
- 모델 가중치와 클래스별 임계값은 변경하지 않았다. 전처리 변경을 반영한 runtime config 버전으로 이전 튜닝 바인딩을 무효화했다.

## 검증

기기: Galaxy S25 SM-S931N, R3CY90SWYBX. APK 최종 설치 시각 2026-09-13 00:55:12.

동일 기기/이미지/모델/정답/임계값에서 nearest와 bilinear를 한 시험 안에서 비교했다. 저장된 COCO val2017 20장의 SHA256을 검증했고, 사람 63개를 포함한 crowd 제외 공통 클래스 정답 110개를 클래스 일치 및 IoU >= 0.5로 일대일 매칭했다.

| backend | 기존 nearest 전체 | 수정 bilinear 전체 | 기존 사람 | 수정 사람 |
|---|---:|---:|---:|---:|
| GPU | 20/110 | 23/110 | 0/63 | 1/63 |
| CPU | 20/110 | 23/110 | 0/63 | 1/63 |

- KnownDetectionAccuracyDeviceTest 통과. 원본 모델의 동일 자료 참조 결과보다 검출이 떨어지지 않는지와 사람 검출이 추가로 사라지지 않는지 검사한다. 이 수치는 선택된 표본에 대한 결과이며 현장 정확도나 전체 mAP가 아니다.
- FastSamProductionRuntimeDeviceTest 통과. 고정 양성 이미지에서 CPU/GPU 모두 유효 마스크 25개, 검은 이미지에서 0개. GPU 양성 이미지의 모델 추론은 약 86ms, 전처리/후처리를 포함한 해당 시험 처리 지연은 약 149ms였다. 카메라나 주 모델과 동시 실행한 속도는 아니다. 모델 실행과 자원 반환을 확인했으며 현장 깊이·접근 판단 정확도를 검증한 것은 아니다.
- bilinear OpenCV 기준 픽셀 비교, 홀수 padding, 회전·박스 역변환, normalized 좌표, runtime config 관련 JVM 검사 11개 통과. APK 및 instrumentation APK 빌드 성공.
- 실제 사용자 로그인/길 안내/현장 카메라 조작은 수행하지 않았다. 카메라 테스트와 보행 서비스의 연결 코드는 반영했지만 실제 장면에서의 사용자 재확인이 필요하다.

APK: `/home/ddobagi/Hanium_Dreamup/apps/android/app/build/outputs/apk/debug/app-debug.apk`

SHA256: `117cdb203c533a76fb25e25391c8927e6f963f6bccefdd05aa6545ac3868d00d`

## 남은 사람 모델 문제

- 현재 보관된 학습 ZIP 3개에서 라벨 파일 127,545개를 읽었고 class 20(person) 라벨은 0개였다. 문법 오류 라벨 행도 0개였다.
- 이 3개가 실제 전체 학습 데이터라는 근거는 없다. 공급 모델의 state.json에는 사람 검증 기록이 있으므로, 별도 사람 데이터가 있었는지 확인해야 한다. 사람 학습이 전혀 없었다고 단정하지 않는다.
- 원본 모델 자체도 진단용 사람 표본에서 매우 약했다. 앱 전처리를 복구한 뒤에도 사람 1/63이므로 사람 성능 보완이 완료된 상태가 아니다. 원본 person 이미지·라벨 경로를 사용자에게 요청했다. 경로를 받으면 클래스 번호·누락·박스 품질과 실제 학습 포함 여부를 검사할 수 있다.

근거: `work/recognition-reconnect-20260913/`의 빌드 로그, 동일 기기 전처리 비교, FastSAM 실행/반환 로그, training-label-audit.json. 원본 진단 자료는 `/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/outputs/recognition-audit-20260913/`에 있다.
