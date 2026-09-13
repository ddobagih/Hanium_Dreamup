# WalkSafe Position Evaluator

별도 applicationId `kr.co.hanium.dreamup.walksafe.positioneval`을 사용하는 후처리 전용 Android 앱이다. 사용자 앱의 실시간 위치 기능과 분리되어 있다.

## 분석 흐름

1. SAF로 WalkSafe trace v2와 같은 시간대의 GNSS Raw TXT(야외 테스트 또는 GnssLogger)를 선택한다.
2. 앱이 입력을 비공개 저장소로 복사하고 크기, SHA-256, trace 시간 계약을 검사한다.
3. 내장 Kotlin 변환기가 GnssLogger Raw 측정값을 RINEX3 관측 파일로 변환한다.
4. 기준국 자료는 아래 두 경로 중 하나로 준비한다. 기준국 거리 계산에는 trace의 GNSS-anchored filtered 좌표 중앙값을 사용하고, filtered 좌표가 없을 때만 anchored raw 좌표를 사용한다.
   - 수동: 홈페이지에서 받은 Hourly RINEX2 파일을 여러 개 선택한다. 키나 네트워크 없이 헤더의 기준국 좌표, 30km 거리, 관측·항법 시간 범위를 검증한다.
   - 자동: 개인 `NGII-File-Key`를 저장하면 30km 내 정상 기준국을 탐색하고 세션 전체의 Hourly RINEX2 자료를 다운로드·검증한다.
5. 검증된 기준국 관측 파일과 GPS 항법 파일을 준비한다. 수동 선택은 분석할 때 원본 사본의 SHA-256과 파일 구성을 다시 검사한다.
6. 내장 RTKLIB-EX JNI가 모든 시간대의 RINEX 파일로 사후 PPK를 수행한다.
7. `Q=1` FIX만 1Hz 정답 격자로 사용해 raw/filtered/matched의 P50, P95, RMSE, 최대오차, 2m 이내 비율과 availability를 계산한다.
8. 충분한 FIX나 기준국 자료가 없으면 점수를 0으로 만들지 않고 `정답 부족`으로 기록한다.

## 홈페이지 자료로 키 없이 분석하기

야외 테스트 화면은 trace와 GnssLogger 호환 형식의 Raw TXT를 함께 저장하므로, 해당 두 파일을 그대로 평가 앱에 가져올 수 있다.

1. 야외 테스트에서 **같이 저장한** trace v2와 원시 GNSS TXT를 휴대폰에 준비한다. 앱에서 `1. WalkSafe trace v2 선택`, `2. GNSS Raw TXT 선택 (야외 테스트·GnssLogger)`를 누른다.
2. trace 아래 표시된 시작·종료 시각은 UTC(`Z`)다. 한국 시각에서 9시간을 빼면 UTC이며 날짜가 전날로 바뀔 수 있다. 테스트 전체가 포함되는 날짜·Hourly 자료를 다운로드한다. 예전에 확인한 다른 날짜의 다운로드 파일은 이번 테스트의 기준 자료가 아니다.
3. 홈페이지에서 같은 기준국의 **Hourly RINEX2 관측 O + GPS 항법 N**을 모두 준비한다. 여러 시간대에 걸친 테스트면 해당 시간대의 파일을 함께 준비한다. `3. 홈페이지에서 받은 기준국 파일 선택`에서 ZIP 하나 또는 O/N 파일들을 길게 눌러 다중선택한다.
4. `수동 기준국 자료 확인 완료`가 나타나면 `4. 선택 파일로 분석 (키 불필요)`를 누른다. 다운로드 키 입력·저장이 없어도 실행된다.
5. 완료 또는 정답 부족 결과를 `진단·결과 JSON 저장`으로 내보낸다. 자료 오류가 나면 같은 테스트 시간과 O/N 구성을 확인해 다시 선택한다.

지원 파일 구성은 다음과 같다. 확장자는 대소문자를 구분하지 않는다.

```text
download.zip
└── 임의의_상대_폴더/
    ├── KUNW251j.26o.Z   # 예시: 2026년 251일, UTC 09시 관측
    └── KUNW251j.26n.gz  # 같은 시간 GPS 항법
```

- ZIP 내부의 상대 하위 폴더와 RINEX2 파일, 각 RINEX 파일의 `.Z`·`.gz` 압축, 압축 없는 파일을 지원한다. ZIP 없이 위 파일을 직접 다중선택해도 된다.
- 파일 이름은 `기준국4글자 + 연중일3자리 + 시간 a~x + .연도2자리 + 종류` 형식이어야 한다. 관측 O와 GPS 항법 N이 필수이며, 같은 형식의 추가 항법 파일도 검증한다. 관측 헤더의 marker·좌표가 서로 일치해야 한다.
- 중첩 ZIP, 절대·상위 이동 경로, 중복 파일, README 등 RINEX 외 파일이 포함된 ZIP, Daily(`0`)·Hatanaka·RINEX3 기준국 파일은 지원하지 않는다. ZIP에 안내문이 섞여 있다면 휴대폰에서 압축을 풀고 지원되는 O/N 파일만 선택한다.
- 선택 파일은 최대 32개, 파일당 128MiB, 합계 256MiB다. 압축 해제는 최대 64개 파일, 파일당 256MiB, 합계 512MiB이며 압축률 제한도 적용한다. 각 ZIP에는 폴더를 포함해 최대 64개 항목을 허용한다.
- 관측 시각은 헤더와 실제 epoch를 검사하고, 기존 2분 간격·시작/종료 허용 오차를 적용한다. GPS 항법의 날짜와 전체 record 구성, 테스트와 최대 4시간인 시간 범위를 확인한다. 이 검사를 통과해도 실제 PPK FIX를 보장하지 않는다.

수동 기준국 정보는 선택 파일의 RINEX 헤더에서 읽는다. 공개 카탈로그로 공식 기준국 운영 상태나 좌표를 인증한 것으로 표시하지 않으며, 결과 JSON에 `USER_SELECTED_RINEX_HEADER_VALIDATED`와 입력 해시·크기를 남긴다. 수동 경로는 기준국 검색이나 파일 다운로드 요청을 보내지 않는다.

## 보안과 데이터 수명

- SAF 입력은 `noBackupFilesDir`에만 저장하며 입력 교체, 파싱 실패, 전체 삭제 시 제거한다. 수동 기준국 원본 사본은 다시 분석할 수 있도록 유지하고, 검증·분석에서 푼 파일은 작업 종료·취소 시 삭제한다. trace를 교체하면 이전 trace에 맞춰 검증한 기준국 선택도 초기화한다.
- File-Key는 Android Keystore AES-256-GCM envelope로 저장하며 결과 JSON과 로그에는 넣지 않는다.
- HTTPS host, redirect, 응답 크기, 빈 응답, 압축률, ZIP 경로와 RINEX header/time/station을 검사한다.
- 결과 JSON은 원래 파일명과 내부 오류 경로를 제외하고 입력 SHA-256과 공개 reason code만 남긴다. 선택한 원본 SAF 파일은 변경하거나 삭제하지 않는다.
- 화면 회전과 중복 탭에도 프로세스 전역 단일 worker에서 분석 한 건만 실행한다.

## 고정 구성요소

- GnssLogger 변환기: `rtklibexplorer/android_rinex` commit `b27fcd07bc085e5213ba29655ad6168b61d60b9e` 기반 Kotlin 포트, 앱에 포함되어 실행됨
- PPK 엔진: RTKLIB-EX v2.5.1 commit `62d4677ed8425a4e2748c6d390b500d1afb493fc`, arm64-v8a JNI로 빌드·실행됨
- 두 구성요소의 라이선스는 BSD-2-Clause이며 전문은 `src/main/assets/THIRD_PARTY_NOTICES.txt`에 있다.

변환기는 Android GNSS 수식 회귀 테스트와 RTKLIB `readrnxt` round-trip을 통과해야만 `available=true`가 된다. 엔진도 native version pin이 정확히 일치할 때만 실행된다. 어느 한쪽이 일치하지 않으면 정답을 만들지 않고 fail-closed한다.

## 알려진 운영 제한

- 자동 다운로드를 쓰는 경우 NGII File-Key 발급과 최초 입력은 사용자가 직접 해야 한다. 홈페이지 파일을 선택하는 수동 분석에는 키가 필요 없다.
- 기준국 목록에는 무결성·스키마 검증된 30일 캐시가 있지만, RINEX 다운로드 파일은 민감 데이터와 저장공간을 줄이기 위해 작업 종료 시 삭제한다. 같은 분석을 다시 실행하면 RINEX를 다시 다운로드한다.
- 실제 NGII 서버 다운로드는 유효한 개인 File-Key와 동시간 테스트 로그가 있어야 검증할 수 있다.
- 홈페이지의 신청·다운로드 버튼까지는 확인했지만 실제 내려받은 ZIP 바이트와 이번 수동 가져오기 조합은 아직 확인하지 않았다. 위 지원 구조와 오류 처리는 합성 RINEX fixture로 검증했으며 실제 홈페이지 자료, 실기기 SAF 선택, 현장 PPK 결과는 별도 확인 대상이다.
- 결과는 같은 휴대폰 원시 GNSS를 사후 처리한 개발용 기준이다. 독립 측량 장비로 얻은 법적·측량용 ground truth가 아니다.
