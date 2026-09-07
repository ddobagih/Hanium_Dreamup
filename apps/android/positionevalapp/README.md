# WalkSafe Position Evaluator

별도 applicationId `kr.co.hanium.dreamup.walksafe.positioneval`을 사용하는 후처리 전용 Android 앱이다. 사용자 앱의 실시간 위치 기능과 분리되어 있다.

## 분석 흐름

1. SAF로 WalkSafe trace v2와 같은 시간대의 GnssLogger TXT를 선택한다.
2. 앱이 입력을 비공개 저장소로 복사하고 크기, SHA-256, trace 시간 계약을 검사한다.
3. 내장 Kotlin 변환기가 GnssLogger Raw 측정값을 RINEX3 관측 파일로 변환한다.
4. 앱이 trace의 GNSS-anchored filtered 좌표 중앙값을 기준으로 30km 내 국토지리정보원 기준국을 탐색한다. filtered 좌표가 없을 때만 anchored raw 좌표를 사용한다.
5. 개인 `NGII-File-Key`로 세션 전체의 Hourly RINEX2 관측·항법 자료를 자동 다운로드하고 검증한다.
6. 내장 RTKLIB-EX JNI가 모든 시간대의 RINEX 파일로 사후 PPK를 수행한다.
7. `Q=1` FIX만 1Hz 정답 격자로 사용해 raw/filtered/matched의 P50, P95, RMSE, 최대오차, 2m 이내 비율과 availability를 계산한다.
8. 충분한 FIX나 기준국 자료가 없으면 점수를 0으로 만들지 않고 `정답 부족`으로 기록한다.

## 보안과 데이터 수명

- SAF 입력은 `noBackupFilesDir`에만 저장하며 입력 교체, 파싱 실패, 전체 삭제 시 제거한다.
- File-Key는 Android Keystore AES-256-GCM envelope로 저장하며 결과 JSON과 로그에는 넣지 않는다.
- HTTPS host, redirect, 응답 크기, 빈 응답, 압축률, ZIP 경로와 RINEX header/time/station을 검사한다.
- 결과 JSON은 원래 파일명과 내부 오류 경로를 제외하고 입력 SHA-256과 공개 reason code만 남긴다.
- 화면 회전과 중복 탭에도 프로세스 전역 단일 worker에서 분석 한 건만 실행한다.

## 고정 구성요소

- GnssLogger 변환기: `rtklibexplorer/android_rinex` commit `b27fcd07bc085e5213ba29655ad6168b61d60b9e` 기반 Kotlin 포트, 앱에 포함되어 실행됨
- PPK 엔진: RTKLIB-EX v2.5.1 commit `62d4677ed8425a4e2748c6d390b500d1afb493fc`, arm64-v8a JNI로 빌드·실행됨
- 두 구성요소의 라이선스는 BSD-2-Clause이며 전문은 `src/main/assets/THIRD_PARTY_NOTICES.txt`에 있다.

변환기는 Android GNSS 수식 회귀 테스트와 RTKLIB `readrnxt` round-trip을 통과해야만 `available=true`가 된다. 엔진도 native version pin이 정확히 일치할 때만 실행된다. 어느 한쪽이 일치하지 않으면 정답을 만들지 않고 fail-closed한다.

## 알려진 운영 제한

- NGII File-Key 발급과 최초 입력은 사용자가 직접 해야 한다.
- 기준국 목록에는 무결성·스키마 검증된 30일 캐시가 있지만, RINEX 다운로드 파일은 민감 데이터와 저장공간을 줄이기 위해 작업 종료 시 삭제한다. 같은 분석을 다시 실행하면 RINEX를 다시 다운로드한다.
- 실제 NGII 서버 다운로드는 유효한 개인 File-Key와 동시간 테스트 로그가 있어야 검증할 수 있다.
- 결과는 같은 휴대폰 원시 GNSS를 사후 처리한 개발용 기준이다. 독립 측량 장비로 얻은 법적·측량용 ground truth가 아니다.
