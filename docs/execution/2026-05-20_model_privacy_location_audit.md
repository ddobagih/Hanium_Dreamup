# WalkSafe KR v3 Privacy/Location 자동 보조 Audit (2026-05-20)

## 범위

- 입력: `data_sources/manifests/walksafe_kr_v3_review_queue_2026-05-20.csv`
- 대상: review queue 196행의 `source_image`
- 출력:
  - `data_sources/manifests/walksafe_kr_v3_privacy_location_audit_results_2026-05-20.csv`
  - `data_sources/manifests/walksafe_kr_v3_privacy_location_audit_summary_2026-05-20.json`
  - `runs/review/walksafe_kr_v3_privacy_audit_20260520/contact_sheets/contact_sheet_*.jpg`

## 방법

- `.venv/bin/python`에서 PIL로 이미지 열기, 존재 여부, 이미지 크기, EXIF 존재 여부를 확인했다.
- OpenCV Haar cascade(`haarcascade_frontalface_default.xml`)로 얼굴 후보 수를 기계적으로 산출했다.
  - 처리 시간을 줄이기 위해 긴 변이 1000px을 초과하는 이미지는 감지용 그레이스케일 이미지만 축소했다.
  - 이 값은 얼굴 후보 자동 보조 신호이며, 실제 얼굴 존재 여부 판정이 아니다.
- 차량번호 OCR/탐지는 수행하지 않았고 `unavailable_not_run`으로 기록했다.
- `source_image` basename에서 8자리 날짜 패턴과 scene 관련 토큰을 추출해 `location_filename_signal`을 기록했다.
- 원본 이미지는 복사하지 않고 thumbnail contact sheet만 생성했다.
- 새 학습, 이미지 수정, 라벨 수정은 수행하지 않았다.

## 결과 요약

- 입력 행 수: 196
- 출력 CSV 행 수: 196
- 이미지 존재: 196 / 196
- EXIF 있음: 75 / 196
- 얼굴 후보 1개 이상: 60 / 196
- 차량번호 체크: 196행 모두 `unavailable_not_run`
- 위치 파일명 신호: 196행 모두 `filename_scene_date_present`
- contact sheet: 6개

## 주의

이 audit은 자동/보조 audit이다. 인간이 원본 고해상도 이미지를 확인하는 최종 privacy/location audit을 대체하지 않는다. 특히 Haar cascade 얼굴 후보는 false positive/false negative가 있을 수 있고, 파일명 기반 location 신호는 실제 촬영 위치 식별 가능성을 확정하지 않는다.
